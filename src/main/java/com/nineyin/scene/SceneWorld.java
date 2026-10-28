package com.nineyin.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 共享场景世界：每 scene_resource 一个权威模拟（所有客户端看到同一份 NPC 位置）。
 * 巡逻腿（leg）状态：开始时不移动 NPC，到达时刻 confirmArrive 落位——
 * 与 C++ 一致，避免晚到的 0x1F 把客户端拽回旧位置。
 * 所有方法 synchronized：Netty 线程与 tick 线程都会访问。
 */
public final class SceneWorld {

    /** 一条进行中的巡逻腿。 */
    public static final class PendingLeg {
        public final long startedMs;
        public final long arriveAtMs;
        public final float x;
        public final float y;
        public final float z;
        public final float orient;

        PendingLeg(long startedMs, long arriveAtMs, float x, float y, float z, float orient) {
            this.startedMs = startedMs;
            this.arriveAtMs = arriveAtMs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.orient = orient;
        }
    }

    public final String sceneResource;
    public final List<NpcInstance> npcs = new ArrayList<>();
    public final Map<String, List<Waypoint>> pathTable = new HashMap<>();
    public final Map<Integer, PendingLeg> legs = new HashMap<>();

    private long lastTickMs;
    private static final long TICK_INTERVAL_MS = 800;
    private static final int MAX_NEW_LEGS = 512;

    SceneWorld(String sceneResource) {
        this.sceneResource = sceneResource;
    }

    public synchronized NpcInstance npcById(int objectId) {
        for (NpcInstance n : npcs) {
            if (n.objectId == objectId) {
                return n;
            }
        }
        return null;
    }

    /**
     * 推进巡逻：到点的腿落位；随后（≥800ms 节流）为空闲的巡逻 NPC 开新腿。
     */
    public synchronized void tick(long nowMs) {
        Iterator<Map.Entry<Integer, PendingLeg>> it = legs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PendingLeg> e = it.next();
            if (nowMs < e.getValue().arriveAtMs) {
                continue;
            }
            NpcInstance n = npcById(e.getKey());
            if (n != null) {
                PatrolPaths.confirmArrive(n);
            }
            it.remove();
        }
        if (lastTickMs != 0 && nowMs - lastTickMs < TICK_INTERVAL_MS) {
            return;
        }
        lastTickMs = nowMs;
        int moved = 0;
        for (NpcInstance n : npcs) {
            if (n.route.size() < 2 || legs.containsKey(n.objectId)) {
                continue;
            }
            if (!PatrolPaths.stepPatrol(n)) {
                continue;
            }
            Waypoint w = n.route.get(n.routeIdx);
            int legMs = Math.max(600, (int) n.patrolSign);
            legs.put(n.objectId, new PendingLeg(nowMs, nowMs + legMs,
                    w.x(), w.y(), w.z(), w.orient() != 0f ? w.orient() : n.orient));
            if (++moved >= MAX_NEW_LEGS) {
                break;
            }
        }
    }

    public synchronized PendingLeg legOf(int objectId) {
        return legs.get(objectId);
    }

    public synchronized List<NpcInstance> snapshotNear(float px, float pz, float radius,
                                                       int maxCount, java.util.function.Predicate<NpcInstance> filter) {
        List<NpcInstance> hits = new ArrayList<>();
        float r2 = radius * radius;
        for (NpcInstance n : npcs) {
            float dx = n.x - px;
            float dz = n.z - pz;
            float d2 = dx * dx + dz * dz;
            if (d2 <= r2 && (filter == null || filter.test(n))) {
                hits.add(n);
                if (hits.size() >= maxCount) {
                    break;
                }
            }
        }
        return hits;
    }
}
