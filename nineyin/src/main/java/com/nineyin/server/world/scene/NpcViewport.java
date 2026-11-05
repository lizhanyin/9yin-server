package com.nineyin.server.world.scene;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.db.role.Roles;

/**
 * NPC 视口同步，逐行移植 Go main.go synchronizeNpcViewport：
 * 目录下标 +2 为对象 ID（玩家恒 1-1）；EventNpc 是任务/事件触发器，
 * 无归属状态前一律不上线（唯一放行点）。
 */
public final class NpcViewport {

    /** 返回（活跃数，是否有变化）。 */
    public static boolean synchronizeNpcViewport(SceneLifecycle world, java.util.List<NpcSpawn> catalog,
                                                 Set<Integer> active, Roles.Position position, float radius) {
        return synchronizeNpcViewport(world, catalog, active, position, radius, npc -> true);
    }

    /**
     * 带额外门控的视口同步：gate=false 的目录项不进 desired（副本波次门控——
     * NpcProgress 大于当前波的摆位不上线，波次推进后重跑即 0x0D 补上）。
     */
    public static boolean synchronizeNpcViewport(SceneLifecycle world, java.util.List<NpcSpawn> catalog,
                                                 Set<Integer> active, Roles.Position position, float radius,
                                                 java.util.function.Predicate<NpcSpawn> gate) {
        float radiusSquared = radius * radius;
        Set<Integer> desired = new TreeSet<>();
        for (int i = 0; i < catalog.size(); i++) {
            if (!visibleInLocalViewport(catalog.get(i)) || !gate.test(catalog.get(i))) {
                continue;
            }
            float dx = catalog.get(i).x - position.x();
            float dz = catalog.get(i).z - position.z();
            if (dx * dx + dz * dz <= radiusSquared) {
                desired.add(i);
            }
        }
        boolean changed = false;
        for (int i : new HashSet<>(active)) {
            if (desired.contains(i)) {
                continue;
            }
            world.unregister(i + 2);
            active.remove(i);
            changed = true;
        }
        for (int i : desired) {
            if (active.contains(i)) {
                continue;
            }
            NpcSpawn npc = catalog.get(i).cloneSpawn();
            npc.synchronizeTransform();
            world.registerNpc(i + 2, npc);
            active.add(i);
            changed = true;
        }
        return changed;
    }

    /** Go npcVisibleInLocalViewport：EventNpc 在任务归属接入前不可见。 */
    static boolean visibleInLocalViewport(NpcSpawn npc) {
        String scriptClass = npc.resolved.scriptClass == null ? "" : npc.resolved.scriptClass;
        return !scriptClass.trim().equalsIgnoreCase("EventNpc");
    }

    private NpcViewport() {
    }
}
