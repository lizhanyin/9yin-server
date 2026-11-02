package com.nineyin.server.world.npc;

import java.util.List;

/**
 * mappath 巡逻路线（不可变，场景目录缓存内跨连接共享）：
 * 节名 = creator PathId，路点按行序往返。
 */
public record PatrolRoute(String pathId, List<Waypoint> waypoints) {

    /** 单个路点；run=false 走 WalkSpeed，true 跑 RunSpeed。 */
    public record Waypoint(float x, float y, float z, float orient, boolean run) {

        /** 数据朝向哨兵（0=未设、-10=按行进方向）：非零且 |o|≤2π 才有效。 */
        public boolean hasFacing() {
            return orient != 0f && Math.abs(orient) <= (float) (Math.PI * 2);
        }
    }

    public PatrolRoute {
        waypoints = List.copyOf(waypoints);
    }

    public int size() {
        return waypoints.size();
    }

    public Waypoint at(int index) {
        return waypoints.get(index);
    }
}
