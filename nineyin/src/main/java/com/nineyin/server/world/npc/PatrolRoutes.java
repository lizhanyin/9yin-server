package com.nineyin.server.world.npc;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mappath 巡逻路线表：share/creator/mappath/&lt;场景短名&gt;_*.ini（offline 排除），
 * 节名 = PathId，行 r= x,y,z,orient,…。行有三种格式（对齐尾部 stand/nil/walk 列）：
 * <ol>
 *   <li>{@code r=,<x>,<y>,<z>,…}（前导空列，主流）</li>
 *   <li>{@code r=<导航点id>,<x>,<y>,<z>,…}（首列整数 id，须跳过——旧服误读成 x，
 *       坐标整体错位，即“上天入地/穿墙”的根因）</li>
 *   <li>{@code r=<x>,<y>,<z>,…}（无 id 列，少量）</li>
 * </ol>
 * 绑定策略：creator PathId 优先、模板 PathID 缺省；路线 ≥2 点（去重后）且出生点到
 * 最近路点 ≤35m 才就近吸附并巡逻，否则原地站立；attacknpc/bossnpc 不绑定
 * （敌对巡逻与战斗互斥，留待敌对 AI 期）。
 */
public final class PatrolRoutes {

    private static final Logger log = LoggerFactory.getLogger(PatrolRoutes.class);

    /** GBK 数据文件（与 npcconfig/creator 一致）。 */
    private static final Charset GBK = Charset.forName("GBK");

    /** 出生点离路线最近路点的吸附上限（米）。 */
    static final float SNAP_MAX_METERS = 35f;

    /** 相邻路点去重阈值（米）。 */
    private static final float DEDUPE_METERS = 0.5f;

    /** bind 统计（日志/测试读取）。 */
    public record BindStats(int walkers, int stand, int missingPath, int farOrShort) {
    }

    private PatrolRoutes() {
    }

    /** 场景 creator 目录名 → 短名（首个 '_' 前，如 city02_suzhou → city02）。 */
    static String shortName(String sceneFolder) {
        String name = sceneFolder;
        int us = name.indexOf('_');
        return us >= 0 ? name.substring(0, us) : name;
    }

    /**
     * 加载场景路线表：mappath 目录下 stem 以场景短名开头且不含 offline 的 .ini。
     * 目录缺失/无匹配 → 空表（该场景无巡逻，不报错）。
     */
    public static Map<String, PatrolRoute> load(Path mappathDir, String sceneFolder) {
        Map<String, PatrolRoute> table = new HashMap<>();
        if (!Files.isDirectory(mappathDir)) {
            log.warn("mappath dir missing: {}", mappathDir);
            return table;
        }
        String prefix = shortName(sceneFolder).toLowerCase(Locale.ROOT);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(mappathDir)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ini"))
                    .filter(p -> {
                        String stem = stem(p).toLowerCase(Locale.ROOT);
                        return stem.startsWith(prefix) && !stem.contains("offline");
                    })
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("mappath scan error: {}", mappathDir, e);
            return table;
        }
        for (Path file : files) {
            loadOne(file, table);
        }
        log.info("mappath table scene={} files={} named_paths={}", prefix, files.size(), table.size());
        return table;
    }

    private static String stem(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void loadOne(Path path, Map<String, PatrolRoute> table) {
        String section = "";
        List<PatrolRoute.Waypoint> points = new ArrayList<>();
        try {
            for (String raw : new String(Files.readAllBytes(path), GBK).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#') {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    flushSection(table, section, points);
                    section = line.substring(1, line.length() - 1);
                    points = new ArrayList<>();
                    continue;
                }
                int eq = line.indexOf('=');
                if (section.isEmpty() || eq <= 0
                        || (line.charAt(0) != 'r' && line.charAt(0) != 'R')) {
                    continue;
                }
                PatrolRoute.Waypoint waypoint = parseWaypoint(line.substring(eq + 1));
                if (waypoint != null) {
                    points.add(waypoint);
                }
            }
            flushSection(table, section, points);
        } catch (IOException e) {
            log.warn("mappath read failed: {}", path, e);
        }
    }

    /** 解析 r= 载荷；格式见类注释，不可解析返回 null。 */
    static PatrolRoute.Waypoint parseWaypoint(String payload) {
        String[] parts = payload.split(",", -1);
        int base = coordinateBase(parts);
        if (base < 0 || parts.length < base + 3) {
            return null;
        }
        try {
            float x = Float.parseFloat(parts[base].trim());
            float y = Float.parseFloat(parts[base + 1].trim());
            float z = Float.parseFloat(parts[base + 2].trim());
            float orient = 0f;
            if (parts.length > base + 3 && !parts[base + 3].isBlank()) {
                orient = Float.parseFloat(parts[base + 3].trim());
            }
            // walk/run 标记在尾部固定列（stand,nil,walk/run,nil,…）。
            boolean run = parts.length > 8 && "run".equalsIgnoreCase(parts[8].trim());
            return new PatrolRoute.Waypoint(x, y, z, orient, run);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 坐标起始列：首列为空（格式1）或纯整数导航点 id（格式2）→ x 在第 1 列；
     * 首列为浮点（格式3，无 id 列）→ x 在第 0 列；其余 -1。
     */
    static int coordinateBase(String[] parts) {
        if (parts.length == 0) {
            return -1;
        }
        String first = parts[0].trim();
        if (first.isEmpty()) {
            return 1;
        }
        boolean hasDot = first.indexOf('.') >= 0;
        try {
            if (hasDot) {
                Float.parseFloat(first);
                return 0;
            }
            Long.parseLong(first, 10);
            return 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 节收尾：相邻点去重（&lt;0.5m）后 ≥2 点才成路线。 */
    private static void flushSection(Map<String, PatrolRoute> table, String section,
                                     List<PatrolRoute.Waypoint> points) {
        if (section.isEmpty() || points.size() < 2) {
            return;
        }
        List<PatrolRoute.Waypoint> cleaned = new ArrayList<>(points.size());
        for (PatrolRoute.Waypoint w : points) {
            if (!cleaned.isEmpty() && distanceSq(cleaned.get(cleaned.size() - 1), w)
                    < DEDUPE_METERS * DEDUPE_METERS) {
                continue;
            }
            cleaned.add(w);
        }
        if (cleaned.size() < 2 || table.containsKey(section)) {
            return;
        }
        table.put(section, new PatrolRoute(section, cleaned));
    }

    private static float distanceSq(PatrolRoute.Waypoint a, PatrolRoute.Waypoint b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        float dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 把路线表绑到场景 spawns 上（就地吸附改写坐标）：PathId → 路线 → 最近路点吸附。
     * 不满足条件（无 PathId/无路线/过远/敌对脚本）→ route 置空站立。
     */
    public static BindStats bind(List<NpcSpawn> spawns, Map<String, PatrolRoute> table) {
        int walkers = 0;
        int stand = 0;
        int missingPath = 0;
        int farOrShort = 0;
        for (NpcSpawn spawn : spawns) {
            spawn.route = null;
            spawn.routeStartIdx = 0;
            if (combatScriptClass(spawn.resolved.scriptClass)) {
                stand++;
                continue;
            }
            String pathId = pathIdOf(spawn);
            if (pathId.isEmpty()) {
                stand++;
                continue;
            }
            PatrolRoute route = table.get(pathId);
            if (route == null) {
                missingPath++;
                stand++;
                continue;
            }
            int best = -1;
            float bestD2 = Float.MAX_VALUE;
            for (int i = 0; i < route.size(); i++) {
                PatrolRoute.Waypoint w = route.at(i);
                float dx = w.x() - spawn.x;
                float dy = w.y() - spawn.y;
                float dz = w.z() - spawn.z;
                float d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = i;
                }
            }
            if (best < 0 || bestD2 > SNAP_MAX_METERS * SNAP_MAX_METERS) {
                farOrShort++;
                stand++;
                continue;
            }
            PatrolRoute.Waypoint snap = route.at(best);
            spawn.x = snap.x();
            spawn.y = snap.y();
            spawn.z = snap.z();
            if (snap.hasFacing()) {
                spawn.orient = snap.orient();
            }
            spawn.route = route;
            spawn.routeStartIdx = best;
            walkers++;
        }
        log.info("patrol bind: walkers={} stand={} missingPath={} farOrShort={} named_paths={}",
                walkers, stand, missingPath, farOrShort, table.size());
        return new BindStats(walkers, stand, missingPath, farOrShort);
    }

    /** creator PathId 优先（PathId/PathID 两种拼法），模板 PathID 缺省。 */
    static String pathIdOf(NpcSpawn spawn) {
        for (String key : new String[]{"creator.PathId", "creator.PathID",
                "template.PathID", "template.PathId"}) {
            String value = spawn.resolved.extensions.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 与 SceneLifecycle.isCombatScriptClass 同判据（world.scene 包私有，此处本地复刻）。 */
    private static boolean combatScriptClass(String scriptClass) {
        String normalized = scriptClass == null ? "" : scriptClass.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("attacknpc") || normalized.equals("bossnpc");
    }
}
