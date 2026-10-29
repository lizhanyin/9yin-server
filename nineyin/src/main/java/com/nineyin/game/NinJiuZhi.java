package com.nineyin.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 九阴志全局门控：全服唯一的单向阶段刻度（1..9，1=1内 … 9=6内，
 * 对应客户端 power_guide.ini 的 step 主步骤 1..6）。
 *
 * 阶段只增不减；击杀/任务事件点累计到阈值自动推进。五类内容按
 * required_stage 门控开放：features / tasks / npcs / scenes / inner_books
 * （键分别对应功能任务 ID、任务 ID、NPC configId、场景 resource、内功 configId）。
 *
 * 数据源（均容缺，缺省 = 全部开放、阶段 1）：
 * - data/ninjiuzhi.json           运行时状态（stage/active/points/events/revision，
 *                                 与 server_ninjiuzhi 表及 data-import 同构，变更即落盘）
 * - data/ninjiuzhi_requirements.json  阶段要求覆写（五类 map + initial_stage + 可选 thresholds）
 */
public final class NinJiuZhi {

    public static final int MIN_STAGE = 1;
    public static final int MAX_STAGE = 9;

    /** 阶段展示名（GM 面板口径：1=1内 … 9=6内）。 */
    private static final String[] STAGE_NAMES = {
            "", "1内", "2内", "3内", "4内", "5内", "5内后期", "6内前期", "6内中期", "6内"
    };

    /** 默认升阶累计事件点（本地验证节奏：10/30/60/…/360，可用 requirements.thresholds 覆写）。 */
    private static final long[] DEFAULT_THRESHOLDS = {
            0, 0, 10, 30, 60, 100, 150, 210, 280, 360
    };

    private static final Logger log = LoggerFactory.getLogger(NinJiuZhi.class);

    /** 门控类别（对应 game_ninjiuzhi_overrides.category）。 */
    public enum Category {
        FEATURES("features"), TASKS("tasks"), NPCS("npcs"), SCENES("scenes"), INNER_BOOKS("inner_books");

        final String key;

        Category(String key) {
            this.key = key;
        }
    }

    /** 阶段生效回调（全服广播 419 阶段帧；在 NinJiuZhi 锁内触发，只做非阻塞下发）。 */
    public interface StageListener {

        void onStageChanged(int oldStage, int newStage);
    }

    private final Path stateFile;                       // 可空 = 不落盘（测试/未接线）
    private final Map<Category, Map<String, Integer>> requirements = new LinkedHashMap<>();
    private final long[] thresholds = DEFAULT_THRESHOLDS.clone();
    private boolean thresholdsOverridden;
    private final List<StageListener> listeners = new CopyOnWriteArrayList<>();
    private final int initialStage;

    private int stage = MIN_STAGE;
    private int active;
    private long points;
    private final Map<String, Long> events = new LinkedHashMap<>();
    private long revision;
    private boolean loadFailed;

    private NinJiuZhi(Path stateFile, int initialStage) {
        this.stateFile = stateFile;
        this.initialStage = Math.max(MIN_STAGE, Math.min(MAX_STAGE, initialStage));
        this.stage = this.initialStage;
    }

    /** 全开放空实例：阶段 1、无门控、不落盘（测试与未接线路径）。 */
    public static NinJiuZhi alwaysOpen() {
        return new NinJiuZhi(null, MIN_STAGE);
    }

    /**
     * 从 data 目录装载：状态 ninjiuzhi.json（缺省用 initial_stage）+
     * 门控 ninjiuzhi_requirements.json。读档失败时保持缺省并拒绝落盘覆盖。
     */
    public static NinJiuZhi load(Path dataDir) {
        int initial = MIN_STAGE;
        NinJiuZhi nz = null;
        Path reqFile = dataDir.resolve("ninjiuzhi_requirements.json");
        JSONObject req = readJson(reqFile);
        if (req != null) {
            Integer v = req.getInteger("initial_stage");
            if (v != null) {
                initial = v;
            }
        }
        nz = new NinJiuZhi(dataDir.resolve("ninjiuzhi.json"), initial);
        if (req != null) {
            nz.loadRequirements(req);
        }
        nz.loadState();
        log.info("ninjiuzhi loaded: stage={} ({}) points={} overrides={} thresholds=custom:{} file={}",
                nz.stage, STAGE_NAMES[nz.stage], nz.points, nz.overrideCount(),
                nz.thresholdsOverridden, nz.stateFile);
        return nz;
    }

    // ---------- 查询 ----------

    public synchronized int stage() {
        return stage;
    }

    /** 阶段展示名（1内/2内/…）。 */
    public static String stageName(int stage) {
        return stage >= MIN_STAGE && stage <= MAX_STAGE ? STAGE_NAMES[stage] : "?";
    }

    /** 门控查询：未配置覆写 = 阶段 1 即开放。 */
    public synchronized boolean isOpen(Category category, String key) {
        Map<String, Integer> map = requirements.get(category);
        if (map == null) {
            return true;
        }
        Integer required = map.get(key);
        return required == null || stage >= required;
    }

    /** 任务 ID 门控（tasks 类别，键为任务号字符串）。 */
    public boolean taskOpen(int taskId) {
        return isOpen(Category.TASKS, String.valueOf(taskId));
    }

    /** NPC 门控（npcs 类别，键为 configId）。 */
    public boolean npcOpen(String configId) {
        return isOpen(Category.NPCS, configId);
    }

    /** 场景/剧本门控（scenes 类别，键为场景 resource 或 bookN）。 */
    public boolean sceneOpen(String key) {
        return isOpen(Category.SCENES, key);
    }

    /** 按门控过滤键列表（保持原顺序）。 */
    public synchronized List<String> filterOpen(Category category, List<String> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (isOpen(category, key)) {
                out.add(key);
            }
        }
        return out;
    }

    public synchronized int overrideCount() {
        return requirements.values().stream().mapToInt(Map::size).sum();
    }

    // ---------- 变更（GM / 事件点） ----------

    /** 单调设阶：小于当前阶段被忽略并返回原因；生效即落盘 + 触发监听。 */
    public synchronized String setStage(int target) {
        if (target < MIN_STAGE || target > MAX_STAGE) {
            return "stage 应为 " + MIN_STAGE + "~" + MAX_STAGE;
        }
        if (target <= stage) {
            return "阶段只增不减：当前 " + stage + "（" + STAGE_NAMES[stage] + "），目标 " + target + " 被忽略";
        }
        int old = stage;
        stage = target;
        active = 1;
        afterChange("set");
        fireStageChanged(old, stage);
        return null;
    }

    /** 重置：回到 initial_stage、点数/事件清零（GM 面板专用）。 */
    public synchronized String reset() {
        int old = stage;
        stage = initialStage;
        active = 0;
        points = 0;
        events.clear();
        afterChange("reset");
        if (old != stage) {
            fireStageChanged(old, stage);
        }
        return null;
    }

    /** 事件点 +1（kill/task…）：累计到阈值自动升阶（可连跳多阶）。 */
    public synchronized void addEvent(String kind) {
        events.merge(kind, 1L, Long::sum);
        points++;
        active = 1;
        int old = stage;
        while (stage < MAX_STAGE && points >= thresholds[stage + 1]) {
            stage++;
        }
        boolean changed = stage != old;
        afterChange("event:" + kind);
        if (changed) {
            fireStageChanged(old, stage);
        }
    }

    /** 击杀事件（SceneFlow 战斗管线 NPC 死亡时调用）。 */
    public void onKill() {
        addEvent("kill");
    }

    // ---------- 状态文本（GM） ----------

    public synchronized String status() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("九阴志：阶段 ").append(stage).append("/").append(MAX_STAGE)
                .append("（").append(STAGE_NAMES[stage]).append("）");
        if (stage < MAX_STAGE) {
            sb.append("，距下一阶还差 ").append(Math.max(0, thresholds[stage + 1] - points)).append(" 事件点");
        }
        sb.append("；累计 ").append(points).append(" 点");
        if (!events.isEmpty()) {
            sb.append("（");
            events.forEach((k, v) -> sb.append(k).append(':').append(v).append(' '));
            sb.setLength(sb.length() - 1);
            sb.append('）');
        }
        sb.append("；门控覆写 ").append(overrideCount()).append(" 条");
        return sb.toString();
    }

    public synchronized long revision() {
        return revision;
    }

    public void addListener(StageListener listener) {
        listeners.add(listener);
    }

    // ---------- 内部 ----------

    private void fireStageChanged(int oldStage, int newStage) {
        for (StageListener l : listeners) {
            try {
                l.onStageChanged(oldStage, newStage);
            } catch (Exception e) {
                log.error("stage listener error", e);
            }
        }
    }

    private void afterChange(String cause) {
        revision++;
        save(cause);
    }

    /** ninjiuzhi_requirements.json：五类覆写 + 可选 thresholds（对象 {"2":30} 或数组 [0,0,30,…]）。 */
    private void loadRequirements(JSONObject root) {
        for (Category c : Category.values()) {
            JSONObject map = root.getJSONObject(c.key);
            if (map == null) {
                continue;
            }
            Map<String, Integer> overrides = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                try {
                    overrides.put(e.getKey(), Integer.parseInt(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignore) {
                    log.warn("ninjiuzhi requirement ignored: {} {} = {}", c.key, e.getKey(), e.getValue());
                }
            }
            requirements.put(c, overrides);
        }
        Object th = root.get("thresholds");
        boolean overridden = false;
        if (th instanceof JSONArray arr) {
            for (int i = 0; i < arr.size() && i < thresholds.length; i++) {
                thresholds[i] = arr.getLongValue(i);
                overridden = true;
            }
        } else if (th instanceof JSONObject obj && !obj.isEmpty()) {
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                try {
                    int idx = Integer.parseInt(e.getKey());
                    if (idx >= MIN_STAGE + 1 && idx <= MAX_STAGE) {
                        thresholds[idx] = Long.parseLong(String.valueOf(e.getValue()));
                        overridden = true;
                    }
                } catch (NumberFormatException ignore) {
                    // 非法阈值键值忽略
                }
            }
        }
        thresholdsOverridden = overridden;
    }

    private void loadState() {
        if (stateFile == null) {
            return;
        }
        JSONObject root = readJson(stateFile);
        if (root == null) {
            return;
        }
        try {
            Integer st = root.getInteger("stage");
            if (st != null) {
                stage = Math.max(MIN_STAGE, Math.min(MAX_STAGE, st));
            }
            active = root.getIntValue("active");
            points = root.getLongValue("points");
            revision = root.getLongValue("revision");
            if (root.get("events") instanceof JSONObject ev) {
                for (Map.Entry<String, Object> e : ev.entrySet()) {
                    events.put(e.getKey(), Long.parseLong(String.valueOf(e.getValue())));
                }
            }
        } catch (RuntimeException e) {
            loadFailed = true;
            log.error("ninjiuzhi state load failed: {}", stateFile, e);
        }
    }

    /** 状态落盘（与 data-import 的 ninjiuzhi.json 同构，revision 随变更递增）。 */
    private void save(String cause) {
        if (stateFile == null) {
            return;
        }
        if (loadFailed) {
            log.error("ninjiuzhi save skipped — load previously failed, refusing to overwrite {}", stateFile);
            return;
        }
        JSONObject root = new JSONObject(true);
        root.put("stage", stage);
        root.put("active", active);
        root.put("points", points);
        JSONObject ev = new JSONObject(true);
        ev.putAll(events);
        root.put("events", ev);
        root.put("revision", revision);
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, JSON.toJSONString(root, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("ninjiuzhi save failed ({}): {}", cause, stateFile, e);
        }
    }

    private static JSONObject readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return JSON.parseObject(text);
        } catch (IOException | RuntimeException e) {
            log.error("ninjiuzhi read failed: {}", file, e);
            return null;
        }
    }
}
