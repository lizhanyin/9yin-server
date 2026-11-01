package com.nineyin.game.scene;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.nineyin.game.npc.NpcSpawn;
import com.nineyin.role.Roles;

/**
 * NPC 视口同步，逐行移植 Go main.go synchronizeNpcViewport：
 * 目录下标 +2 为对象 ID（玩家恒 1-1）；EventNpc 是任务/事件触发器，
 * 无归属状态前一律不上线（唯一放行点）。
 */
public final class NpcViewport {

    /** 返回（活跃数，是否有变化）。 */
    public static boolean synchronizeNpcViewport(SceneLifecycle world, java.util.List<NpcSpawn> catalog,
                                                 Set<Integer> active, Roles.Position position, float radius) {
        float radiusSquared = radius * radius;
        Set<Integer> desired = new TreeSet<>();
        for (int i = 0; i < catalog.size(); i++) {
            if (!visibleInLocalViewport(catalog.get(i))) {
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
