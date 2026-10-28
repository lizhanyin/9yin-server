package com.nineyin.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * mappath 巡逻路线：creator/mappath/&lt;scene&gt;_npc.ini / _mst.ini，
 * 节名 = PathId（如 [AI_yyz_001]），行 r=,x,y,z,orient,wait,…。
 * 绑定策略与 C++ 一致：严格 PathId → 路线，去重相邻点，就近吸附（>35m 原地站立）。
 */
public final class PatrolPaths {

    private static final Logger log = LoggerFactory.getLogger(PatrolPaths.class);

    private static final float SNAP_MAX = 35f;

    private PatrolPaths() {
    }

    public static Map<String, List<Waypoint>> load(Path mappathDir, String sceneResource) {
        Map<String, List<Waypoint>> table = new HashMap<>();
        if (!Files.isDirectory(mappathDir)) {
            log.warn("mappath dir missing: {}", mappathDir);
            return table;
        }
        String res = shortName(sceneResource).toLowerCase(Locale.ROOT);
        try (Stream<Path> files = Files.list(mappathDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ini"))
                    .filter(p -> {
                        String stem = stem(p).toLowerCase(Locale.ROOT);
                        return stem.startsWith(res) && !stem.contains("offline");
                    })
                    .forEach(p -> loadOne(p, table));
        } catch (IOException e) {
            log.warn("mappath scan error: {}", mappathDir, e);
        }
        log.info("mappath table scene={} named_paths={}", res, table.size());
        return table;
    }

    private static String shortName(String sceneResource) {
        String res = sceneResource;
        int slash = Math.max(res.lastIndexOf('/'), res.lastIndexOf('\\'));
        if (slash >= 0) {
            res = res.substring(slash + 1);
        }
        int us = res.indexOf('_');
        return us >= 0 ? res.substring(0, us) : res;
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private static void loadOne(Path path, Map<String, List<Waypoint>> table) {
        try {
            String section = "";
            String data = new String(Files.readAllBytes(path), Charset.forName("GBK"));
            for (String raw : data.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#') {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    continue;
                }
                if (section.isEmpty() || (line.charAt(0) != 'r' && line.charAt(0) != 'R')) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String[] parts = line.substring(eq + 1).split(",", -1);
                int base = !parts[0].isEmpty() ? 0 : 1; // r=, 前导逗号 → parts[0] 为空
                if (parts.length < base + 3) {
                    continue;
                }
                try {
                    float x = Float.parseFloat(parts[base]);
                    float y = Float.parseFloat(parts[base + 1]);
                    float z = Float.parseFloat(parts[base + 2]);
                    float orient = parts.length > base + 3 && !parts[base + 3].isEmpty()
                            ? Float.parseFloat(parts[base + 3]) : 0f;
                    int waitMs = 200;
                    if (parts.length > base + 4 && !parts[base + 4].isEmpty()) {
                        try {
                            waitMs = (int) Float.parseFloat(parts[base + 4]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    table.computeIfAbsent(section, k -> new ArrayList<>())
                            .add(new Waypoint(x, y, z, orient, waitMs));
                } catch (NumberFormatException ignored) {
                }
            }
            log.info("mappath loaded {} sections_now={}", path.getFileName(), table.size());
        } catch (IOException e) {
            log.warn("mappath read failed: {}", path, e);
        }
    }

    /** 把 PathId 绑定到路线并吸附（修改 npc 的 route/routeIdx/位置）。 */
    public static void assignRoutes(List<NpcInstance> npcs, Map<String, List<Waypoint>> table) {
        int walkers = 0;
        int stand = 0;
        int missing = 0;
        for (NpcInstance n : npcs) {
            n.route.clear();
            n.routeIdx = 0;
            n.routeDir = 1;
            if (n.pathId.isEmpty()) {
                stand++;
                continue;
            }
            List<Waypoint> raw = table.get(n.pathId);
            if (raw == null || raw.size() < 2) {
                missing++;
                stand++;
                continue;
            }
            List<Waypoint> cleaned = new ArrayList<>(raw.size());
            for (Waypoint w : raw) {
                if (!cleaned.isEmpty()) {
                    Waypoint last = cleaned.get(cleaned.size() - 1);
                    float dx = w.x() - last.x();
                    float dy = w.y() - last.y();
                    float dz = w.z() - last.z();
                    if (dx * dx + dy * dy + dz * dz < 0.25f) {
                        continue;
                    }
                }
                cleaned.add(w);
            }
            if (cleaned.size() < 2) {
                missing++;
                stand++;
                continue;
            }
            int best = 0;
            float bestD2 = Float.MAX_VALUE;
            for (int i = 0; i < cleaned.size(); i++) {
                Waypoint w = cleaned.get(i);
                float dx = w.x() - n.homeX;
                float dy = w.y() - n.homeY;
                float dz = w.z() - n.homeZ;
                float d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = i;
                }
            }
            if (bestD2 > SNAP_MAX * SNAP_MAX) {
                missing++;
                stand++;
                continue;
            }
            n.route.addAll(cleaned);
            n.routeIdx = best;
            n.routeDir = 1;
            Waypoint snap = cleaned.get(best);
            n.x = snap.x();
            n.y = snap.y();
            n.z = snap.z();
            if (snap.orient() != 0f) {
                n.orient = snap.orient();
            }
            walkers++;
        }
        log.info("patrol policy: walkers={} stand={} missing_or_far={} named_paths={}",
                walkers, stand, missing, table.size());
    }

    /**
     * 开始下一段巡逻（不移动 NPC 位置）：单腿 XZ ≤ 28m、|dy| ≤ 6m，
     * 速度 2.5m/s（travel_ms 700..10000 + wait）。patrolSign = 总毫秒。
     */
    public static boolean stepPatrol(NpcInstance n) {
        if (n.route.size() < 2) {
            return false;
        }
        final float maxLeg = 28f;
        final float maxDy = 6f;
        int dir = n.routeDir == 0 ? 1 : n.routeDir;
        for (int attempt = 0; attempt < n.route.size(); attempt++) {
            int next = n.routeIdx + dir;
            if (next < 0 || next >= n.route.size()) {
                dir = -dir;
                next = n.routeIdx + dir;
                if (next < 0 || next >= n.route.size()) {
                    return false;
                }
            }
            Waypoint w = n.route.get(next);
            float ddx = w.x() - n.x;
            float ddy = w.y() - n.y;
            float ddz = w.z() - n.z;
            float distXz = (float) Math.sqrt(ddx * ddx + ddz * ddz);
            if (distXz > maxLeg || Math.abs(ddy) > maxDy) {
                n.routeIdx = next; // 跳过不可达点
                n.routeDir = dir;
                continue;
            }
            if (distXz < 0.15f) {
                n.routeIdx = next;
                n.routeDir = dir;
                continue;
            }
            int travelMs = (int) (distXz / 2.5f * 1000f);
            travelMs = Math.max(700, Math.min(10000, travelMs));
            int wait = w.waitMs() > 0 ? w.waitMs() : 200;
            n.orient = (float) Math.atan2(ddx, ddz);
            n.routeIdx = next;
            n.routeDir = dir;
            n.patrolSign = travelMs + wait;
            return true;
        }
        return false;
    }

    /** 到达：权威位置落到当前路点。 */
    public static void confirmArrive(NpcInstance n) {
        if (n.route.isEmpty() || n.routeIdx < 0 || n.routeIdx >= n.route.size()) {
            return;
        }
        Waypoint w = n.route.get(n.routeIdx);
        n.x = w.x();
        n.y = w.y();
        n.z = w.z();
        if (w.orient() != 0f) {
            n.orient = w.orient();
        }
    }
}
