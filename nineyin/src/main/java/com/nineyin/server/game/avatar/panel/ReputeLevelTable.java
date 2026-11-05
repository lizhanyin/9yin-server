package com.nineyin.server.game.avatar.panel;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

/**
 * 声望等级表（GM repute_apply 数据源）：官方 {@code share/rule/repute_level.ini}（GBK）。
 *
 * <ul>
 *   <li>{@code [Repute]} —— 声望阵营闭名单（重复键 r= 逐行一个，36 项：八大门派 +
 *       江湖 + 朝廷 + 隐世势力族；GM reputeName="all" 即按此表序全量）。</li>
 *   <li>{@code [Level]} —— 默认 16 级阈值曲线（1=0、2=700 … 16=200000000）；
 *       {@code levelOf} 取 ≤value 的最高级。</li>
 *   <li>{@code [repute_max_value]} —— 特例上限（wanshou=40 万等）；未配置的阵营
 *       按 [Level] 最大值为上限（与 gm-web GmCatalog.reputeStatus 展示口径一致；
 *       官方注释的 repute_reduction.ini max_repute=60418382 口径更紧，GM 输入
 *       本就被入口限制在 ≤9999999，此处取宽不裁真机语义）。</li>
 *   <li>{@code [repute_<阵营>]} —— 阵营自定义阈值段（如 repute_yihua，低级段
 *       特化、高级并轨默认曲线）；levelOf 优先用它。</li>
 * </ul>
 *
 * <p>生产经 {@link ResourceCache#of} 按路径缓存（WorldBossRefreshTable 同款）；
 * 文件缺失 → 空表 + present=false（调用方 fail 提示，不抛异常）。
 */
public final class ReputeLevelTable {

    /** GBK 数据文件（与 npcconfig/rule 一致）。 */
    private static final Charset GBK = Charset.forName("GBK");

    private final boolean present;
    private final List<String> camps;
    private final Map<String, Long> maxValues;
    private final Map<String, Curve> thresholds;

    /** 生产加载：share/rule 根，按路径缓存。 */
    public static ReputeLevelTable load() {
        Path ruleRoot = ServerPaths.projectPath("resources", "modern", "share", "rule");
        return ResourceCache.of("repute-level:" + ruleRoot.toAbsolutePath().normalize(),
                () -> build(ruleRoot));
    }

    /** 测试注入：指定 share/rule 根（直读不缓存，临时目录可重复构建）。 */
    static ReputeLevelTable load(Path ruleRoot) {
        return build(ruleRoot);
    }

    private static ReputeLevelTable build(Path ruleRoot) {
        boolean present = Files.isRegularFile(ruleRoot.resolve("repute_level.ini"));
        List<String> camps = new ArrayList<>();
        Map<String, Long> maxValues = new HashMap<>();
        Map<String, Curve> thresholds = new LinkedHashMap<>();
        if (present) {
            Map<String, Map<String, List<String>>> sections = new LinkedHashMap<>();
            ResourceCache.ini(ruleRoot.resolve("repute_level.ini"), GBK,
                    (section, key, value) -> sections
                            .computeIfAbsent(section, unused -> new LinkedHashMap<>())
                            .computeIfAbsent(key, unused -> new ArrayList<>())
                            .add(value));
            // [Repute]：r= 重复键逐行（兼容逗号分隔），按出现序去重。
            Map<String, List<String>> reputeSec = sections.get("Repute");
            if (reputeSec != null) {
                for (List<String> values : reputeSec.values()) {
                    for (String line : values) {
                        for (String name : line.split(",")) {
                            name = name.trim();
                            if (!name.isEmpty() && !camps.contains(name)) {
                                camps.add(name);
                            }
                        }
                    }
                }
            }
            // [Level]：1..16 默认阈值（键=级号）。
            Curve defaultCurve = curveOf(sections.get("Level"));
            // [repute_max_value]：特例上限；缺省 = 默认曲线最大值。
            long defaultMax = maxValueOf(defaultCurve);
            Map<String, List<String>> maxSec = sections.get("repute_max_value");
            if (maxSec != null) {
                for (Map.Entry<String, List<String>> entry : maxSec.entrySet()) {
                    Long value = longValue(first(entry.getValue()));
                    if (value != null) {
                        maxValues.put(entry.getKey().trim(), value);
                    }
                }
            }
            for (String camp : camps) {
                maxValues.putIfAbsent(camp, defaultMax);
            }
            // [repute_<阵营>]：自定义阈值段。
            for (Map.Entry<String, Map<String, List<String>>> entry : sections.entrySet()) {
                String section = entry.getKey().trim();
                if (section.startsWith("repute_") && !"repute_max_value".equals(section)) {
                    Curve curve = curveOf(entry.getValue());
                    if (!curve.isEmpty()) {
                        thresholds.put(section, curve);
                    }
                }
            }
            if (!defaultCurve.isEmpty()) {
                thresholds.putIfAbsent(DEFAULT_CURVE, defaultCurve);
            }
        }
        return new ReputeLevelTable(present && !camps.isEmpty(),
                List.copyOf(camps), Map.copyOf(maxValues), Map.copyOf(thresholds));
    }

    private ReputeLevelTable(boolean present, List<String> camps,
                             Map<String, Long> maxValues, Map<String, Curve> thresholds) {
        this.present = present;
        this.camps = camps;
        this.maxValues = maxValues;
        this.thresholds = thresholds;
    }

    /** 表可解析且有阵营（false = repute_level.ini 缺失，repute_apply 应 fail）。 */
    public boolean present() {
        return present;
    }

    /** 阵营闭名单（[Repute] 出现序；reputeName="all" 按此全量）。 */
    public List<String> camps() {
        return camps;
    }

    public boolean isCamp(String name) {
        return name != null && camps.contains(name.trim());
    }

    /** 阵营声望上限（[repute_max_value] 特例；缺省 [Level] 最大值）。 */
    public long maxValue(String camp) {
        return maxValues.getOrDefault(camp == null ? "" : camp.trim(), Long.MAX_VALUE);
    }

    /**
     * 声望等级：阵营自定义段（[repute_&lt;camp&gt;]）优先，否则 [Level] 默认曲线；
     * 取阈值 ≤ value 的最高档，级号 = 该档 INI 键（1..16，非连续键不重排）。
     * 无曲线返回 1。
     */
    public int levelOf(String camp, long value) {
        Curve curve = camp != null
                ? thresholds.get(camp.trim()) : null;
        if (curve == null) {
            curve = thresholds.get(DEFAULT_CURVE);
        }
        if (curve == null || curve.isEmpty()) {
            return 1;
        }
        int level = 1;
        for (int i = 0; i < curve.levels.length; i++) {
            if (value >= curve.thresholds[i]) {
                level = curve.levels[i];
            } else {
                break;
            }
        }
        return level;
    }

    // ---- 解析 ----

    /** 默认曲线在 thresholds 表里的键（空串，避免与阵营名冲突）。 */
    private static final String DEFAULT_CURVE = "";

    /** 阈值曲线：级号（INI 键）与阈值按级号升序成对。 */
    private record Curve(int[] levels, long[] thresholds) {

        boolean isEmpty() {
            return levels.length == 0;
        }
    }

    /** 节 → 级号/阈值曲线（键=级号数值升序取 value；非数字键忽略）。 */
    private static Curve curveOf(Map<String, List<String>> section) {
        if (section == null) {
            return new Curve(new int[0], new long[0]);
        }
        Map<Integer, Long> points = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : section.entrySet()) {
            Integer level;
            try {
                level = Integer.valueOf(entry.getKey().trim());
            } catch (NumberFormatException e) {
                continue;
            }
            Long value = longValue(first(entry.getValue()));
            if (value != null) {
                points.put(level, value);
            }
        }
        if (points.isEmpty()) {
            return new Curve(new int[0], new long[0]);
        }
        List<Integer> levels = new ArrayList<>(points.keySet());
        levels.sort(Integer::compareTo);
        int[] levelKeys = new int[levels.size()];
        long[] thresholds = new long[levels.size()];
        for (int i = 0; i < levels.size(); i++) {
            levelKeys[i] = levels.get(i);
            thresholds[i] = points.get(levels.get(i));
        }
        return new Curve(levelKeys, thresholds);
    }

    private static long maxValueOf(Curve curve) {
        long max = 0;
        for (long value : curve.thresholds) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Long longValue(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
