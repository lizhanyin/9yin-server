package com.nineyin.server.world.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nineyin.server.world.npc.PatrolSchedule.Dwelling;
import com.nineyin.server.world.npc.PatrolSchedule.Moving;
import com.nineyin.server.world.npc.PatrolSchedule.Position;
import com.nineyin.server.world.npc.PatrolRoute.Waypoint;
import org.junit.jupiter.api.Test;

/**
 * 巡逻时间表测试：行程/停留时间线展开、任意时刻位置采样（插值/停留/回绕）、
 * 高度差路点过滤、run 腿用 RunSpeed。
 */
class PatrolScheduleTest {

    private static PatrolRoute route(Waypoint... points) {
        return new PatrolRoute("test", List.of(points));
    }

    @Test
    void timelinePositionsAndActionsMatchGeometry() {
        // w0(0,0,0) → w1(3,0,0,停留1000)：walk=1.5 → 去程 2000+1000、回程 2000+0，周期 5000。
        PatrolRoute r = route(
                new Waypoint(0f, 0f, 0f, 0f, false, 0),
                new Waypoint(3f, 0f, 0f, 0f, false, 1000));
        PatrolSchedule schedule = PatrolSchedule.build(r, 1.5f, 4f, 0L);
        assertNotNull(schedule);
        assertEquals(5000L, schedule.cycleMs());
        assertEquals(2, schedule.waypoints());

        // t=1000：去程中点（插值 1.5），朝向=行进方向 π/2。
        Position mid = schedule.positionAt(1000L);
        assertEquals(1.5f, mid.x(), 1e-4f);
        assertEquals((float) (Math.PI / 2), mid.orient(), 1e-4f);
        // t=2500：w1 停留窗（2000..3000）。
        assertEquals(3f, schedule.positionAt(2500L).x(), 1e-4f);
        // t=4000：回程 w1→w0 中点。
        assertEquals(1.5f, schedule.positionAt(4000L).x(), 1e-4f);
        assertEquals((float) -Math.PI / 2, schedule.positionAt(4000L).orient(), 1e-4f);
        // 周期回绕：t=6000 ≡ t=1000。
        assertEquals(1.5f, schedule.positionAt(6000L).x(), 1e-4f);

        // 动作采样：行进剩余时长 / 停留剩余时长。
        Moving moving = (Moving) schedule.actionAt(1000L);
        assertEquals(3f, moving.destX(), 1e-4f);
        assertEquals(1000L, moving.remainingMs());
        Dwelling dwelling = (Dwelling) schedule.actionAt(2500L);
        assertEquals(500L, dwelling.waitMs());
        Moving back = (Moving) schedule.actionAt(4000L);
        assertEquals(0f, back.destX(), 1e-4f, "回程目标 w0");
        assertEquals(1000L, back.remainingMs());
    }

    @Test
    void runWaypointsUseRunSpeed() {
        // w0→w1 8m，w1 run=true：去程 RunSpeed=4 → 2000ms；回程到达 w0（walk=1）→ 8000ms。
        PatrolRoute r = route(
                new Waypoint(0f, 0f, 0f, 0f, false, 0),
                new Waypoint(8f, 0f, 0f, 0f, true, 0));
        PatrolSchedule schedule = PatrolSchedule.build(r, 1f, 4f, 0L);
        assertEquals(10000L, schedule.cycleMs(), "去程 2000 + 回程 8000");
        assertEquals(4f, schedule.positionAt(1000L).x(), 1e-4f, "run 腿中点 4m");
        // 回程 t=6000（回程 2000..10000 中点）→ x=4。
        assertEquals(4f, schedule.positionAt(6000L).x(), 1e-4f);
    }

    @Test
    void filtersHighJumpWaypointsAndRejectsUnusable() {
        // w1/w2 相对保留点高差均 >6m → 只剩 w0 → 不可用。
        PatrolRoute bad = route(
                new Waypoint(0f, 0f, 0f, 0f, false, 0),
                new Waypoint(1f, 10f, 0f, 0f, false, 0),
                new Waypoint(5f, 20f, 0f, 0f, false, 0));
        assertNull(PatrolSchedule.build(bad, 1.5f, 4f, 0L));
        // 剔除中间跳点后两端可达 → 时间表按保留点展开。
        PatrolRoute withJump = route(
                new Waypoint(0f, 0f, 0f, 0f, false, 0),
                new Waypoint(2f, 8f, 0f, 0f, false, 0),
                new Waypoint(3f, 1f, 0f, 0f, false, 0));
        PatrolSchedule schedule = PatrolSchedule.build(withJump, 1.5f, 4f, 0L);
        assertNotNull(schedule);
        assertEquals(2, schedule.waypoints(), "跳点被剔除");
        assertEquals(2.25f, schedule.positionAt(1500L).x(), 1e-4f, "1.5m/s 行 1.5s = 2.25m");
        // 非法速度 → null。
        assertNull(PatrolSchedule.build(withJump, 0f, 4f, 0L));
        assertNull(PatrolSchedule.build(withJump, 1.5f, Float.NaN, 0L));
        assertTrue(PatrolSchedule.build(withJump, 1.5f, 4f, 0L).cycleMs() > 0);
    }
}
