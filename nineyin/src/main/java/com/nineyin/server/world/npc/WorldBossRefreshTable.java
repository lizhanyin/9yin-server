package com.nineyin.server.world.npc;

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
 * 世界 BOSS 刷新表（GM worldboss_* 动作数据源）：share/npc/ 三张官方 GBK INI。
 * <ul>
 *   <li>normalbossrefreshinfo.ini —— 门派固定点：节号 → bosslist（分号模板表）/
 *       scene（场景号）/ position（x,y,z）；timeid/maxnumber/notice 为定时刷新
 *       语义，GM 强制生成不消费。</li>
 *   <li>jhscenebossrefreshinfo.ini —— 江湖 BOSS：节名 = BOSS 模板号，Scene =
 *       随机场景池（逗号分隔）；刷新时段/奖励段等本期间不消费。</li>
 *   <li>jhscenebossrefreshpoint.ini —— 江湖 BOSS 场景随机坐标点池：节名 = 场景号，
 *       Point= 重复键每行一个 x,y,z。</li>
 * </ul>
 * 生产表经 {@link ResourceCache#of} 按数据根缓存（同 SceneNames 惯例）；
 * 文件缺失 → 空表 + present=false（调用方 fail 提示，不抛异常）。
 */
public final class WorldBossRefreshTable {

    /** GBK 数据文件（与 npcconfig/creator 一致）。 */
    private static final Charset GBK = Charset.forName("GBK");

    /** 门派固定刷新点（normalbossrefreshinfo 一节）。 */
    public record NormalPoint(int id, int scene, List<String> bossList, float x, float y, float z) {
    }

    /** 江湖 BOSS（jhscenebossrefreshinfo 一节，本条只取随机场景池）。 */
    public record JhBoss(String bossId, List<Integer> scenes) {
    }

    private final boolean present;
    private final List<NormalPoint> normalPoints;
    private final List<JhBoss> jhBosses;
    private final Map<Integer, List<float[]>> jhPoints;

    /** 生产加载：share/npc 根，按路径缓存。 */
    public static WorldBossRefreshTable load() {
        Path npcRoot = ServerPaths.projectPath("resources", "modern", "share", "npc");
        return ResourceCache.of("world-boss-refresh:" + npcRoot.toAbsolutePath().normalize(),
                () -> build(npcRoot));
    }

    /** 测试注入：指定 share/npc 根（直读不缓存，临时目录可重复构建）。 */
    static WorldBossRefreshTable load(Path npcRoot) {
        return build(npcRoot);
    }

    private static WorldBossRefreshTable build(Path npcRoot) {
        boolean present = Files.isDirectory(npcRoot);
        List<NormalPoint> normalPoints = new ArrayList<>();
        List<JhBoss> jhBosses = new ArrayList<>();
        Map<Integer, List<float[]>> jhPoints = new HashMap<>();
        if (present) {
            parseNormalPoints(sections(npcRoot.resolve("normalbossrefreshinfo.ini")), normalPoints);
            parseJhBosses(sections(npcRoot.resolve("jhscenebossrefreshinfo.ini")), jhBosses);
            parseJhPoints(sections(npcRoot.resolve("jhscenebossrefreshpoint.ini")), jhPoints);
        }
        return new WorldBossRefreshTable(present && (!normalPoints.isEmpty() || !jhBosses.isEmpty()),
                List.copyOf(normalPoints), List.copyOf(jhBosses), Map.copyOf(jhPoints));
    }

    /** 数据根可解析且有内容（false = 刷新表缺失，worldboss_spawn 应 fail）。 */
    public boolean present() {
        return present;
    }

    /** 场景的门派固定刷新点（按节号升序）。 */
    public List<NormalPoint> normalPointsForScene(int scene) {
        List<NormalPoint> matched = new ArrayList<>();
        for (NormalPoint point : normalPoints) {
            if (point.scene() == scene) {
                matched.add(point);
            }
        }
        return matched;
    }

    /** 随机场景池包含该场景的江湖 BOSS。 */
    public List<JhBoss> jhBossesForScene(int scene) {
        List<JhBoss> matched = new ArrayList<>();
        for (JhBoss boss : jhBosses) {
            if (boss.scenes().contains(scene)) {
                matched.add(boss);
            }
        }
        return matched;
    }

    /** 江湖 BOSS 在该场景的随机坐标点池（无配置返回空表）。 */
    public List<float[]> jhPointsForScene(int scene) {
        return jhPoints.getOrDefault(scene, List.of());
    }

    // ---- 解析 ----

    /**
     * INI → 节有序视图：节名 → (键 → 值列表)。ResourceCache.ini 逐行回调，
     * Point= 等重复键按出现序累计。
     */
    private static Map<String, Map<String, List<String>>> sections(Path file) {
        Map<String, Map<String, List<String>>> sections = new LinkedHashMap<>();
        ResourceCache.ini(file, GBK, (section, key, value) -> sections
                .computeIfAbsent(section, unused -> new LinkedHashMap<>())
                .computeIfAbsent(key, unused -> new ArrayList<>())
                .add(value));
        return sections;
    }

    private static void parseNormalPoints(Map<String, Map<String, List<String>>> sections,
                                          List<NormalPoint> out) {
        for (Map.Entry<String, Map<String, List<String>>> entry : sections.entrySet()) {
            Map<String, List<String>> kv = entry.getValue();
            Integer scene = intValue(first(kv, "scene"));
            String bossList = first(kv, "bosslist");
            float[] position = floats(first(kv, "position"));
            if (scene == null || bossList == null || bossList.isBlank() || position.length != 3) {
                continue;
            }
            List<String> templates = new ArrayList<>();
            for (String template : bossList.split(";")) {
                if (!template.isBlank()) {
                    templates.add(template.trim());
                }
            }
            if (templates.isEmpty()) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(entry.getKey().trim());
            } catch (NumberFormatException e) {
                continue;
            }
            out.add(new NormalPoint(id, scene, List.copyOf(templates),
                    position[0], position[1], position[2]));
        }
        out.sort((a, b) -> Integer.compare(a.id(), b.id()));
    }

    private static void parseJhBosses(Map<String, Map<String, List<String>>> sections,
                                      List<JhBoss> out) {
        for (Map.Entry<String, Map<String, List<String>>> entry : sections.entrySet()) {
            String bossId = entry.getKey().trim();
            String sceneList = first(entry.getValue(), "Scene");
            if (bossId.isEmpty() || sceneList == null || sceneList.isBlank()) {
                continue;
            }
            List<Integer> scenes = new ArrayList<>();
            for (String scene : sceneList.split(",")) {
                Integer value = intValue(scene);
                if (value != null) {
                    scenes.add(value);
                }
            }
            if (!scenes.isEmpty()) {
                out.add(new JhBoss(bossId, List.copyOf(scenes)));
            }
        }
    }

    private static void parseJhPoints(Map<String, Map<String, List<String>>> sections,
                                      Map<Integer, List<float[]>> out) {
        for (Map.Entry<String, Map<String, List<String>>> entry : sections.entrySet()) {
            Integer scene = intValue(entry.getKey());
            if (scene == null) {
                continue;
            }
            List<float[]> points = new ArrayList<>();
            for (String value : entry.getValue().getOrDefault("Point", List.of())) {
                float[] xyz = floats(value);
                if (xyz.length == 3) {
                    points.add(xyz);
                }
            }
            if (!points.isEmpty()) {
                out.put(scene, List.copyOf(points));
            }
        }
    }

    private static String first(Map<String, List<String>> kv, String key) {
        List<String> values = kv.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Integer intValue(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 逗号分隔浮点（容忍 "896.4,-6.3,391.1"）；任何列不可解析返回短数组。 */
    private static float[] floats(String text) {
        if (text == null) {
            return new float[0];
        }
        String[] parts = text.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return new float[0];
            }
        }
        return values;
    }

    private WorldBossRefreshTable(boolean present, List<NormalPoint> normalPoints,
                                  List<JhBoss> jhBosses, Map<Integer, List<float[]>> jhPoints) {
        this.present = present;
        this.normalPoints = normalPoints;
        this.jhBosses = jhBosses;
        this.jhPoints = jhPoints;
    }
}
