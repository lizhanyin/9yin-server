package com.nineyin.server.world.npc;

import java.util.ArrayList;
import java.util.List;

/**
 * 巡逻时间表：把路线展开成"行程段 + 停留"的绝对时间线（往返一个周期），
 * 任意时刻可采样当前位置与下一动作。所有连接共享同一份（绑定时刻构建，
 * epochMs 为共同时钟起点），因此同场景不同玩家看到一致的 NPC 位置，
 * 视口重进时从中断处继续而非重走。
 *
 * <p>段序：w0→w1→…→w(n-1)→w(n-2)→…→w0；每段 = 行程(距离/速度) +
 * 到达路点停留（mappath 停留列归属到达行）。周期 = 全部行程 + 全部停留。
 * 构建时剔除与前一保留点高度差 &gt;6m 的路点（脏数据防飞天，全连接一致）。
 */
public final class PatrolSchedule {

    /** 行程时长钳制（毫秒）：下限防零碎腿刷帧，上限防脏数据长腿挂死。 */
    static final long TRAVEL_MIN_MILLIS = 300;
    static final long TRAVEL_MAX_MILLIS = 90_000;

    /** 高度差钳制（米）：与前一保留点 |dy| 超过的路点剔除。 */
    static final float MAX_LEG_DY_METERS = 6f;

    /** 采样位置（行程中按比例插值，停留中即路点坐标）。 */
    public record Position(float x, float y, float z, float orient) {
    }

    /** 行进中：目标路点坐标/朝向、剩余行程毫秒。 */
    public record Moving(float destX, float destY, float destZ, float destOrient,
                         long remainingMs) {
    }

    /** 停留中：原地等待毫秒数。 */
    public record Dwelling(long waitMs) {
    }

    private final String pathId;
    private final long epochMs;
    private final long cycleMs;
    private final List<PatrolRoute.Waypoint> points;
    private final long[] starts;
    private final long[] travels;
    private final long[] dwells;
    private final int[] fromIdx;
    private final int[] toIdx;

    private PatrolSchedule(String pathId, long epochMs, long cycleMs,
                           List<PatrolRoute.Waypoint> points,
                           long[] starts, long[] travels, long[] dwells,
                           int[] fromIdx, int[] toIdx) {
        this.pathId = pathId;
        this.epochMs = epochMs;
        this.cycleMs = cycleMs;
        this.points = points;
        this.starts = starts;
        this.travels = travels;
        this.dwells = dwells;
        this.fromIdx = fromIdx;
        this.toIdx = toIdx;
    }

    /**
     * 构建（不可用返回 null：高度过滤后不足 2 点或速度非法）。
     * walkSpeed/runSpeed 为该 NPC 的 0x0D 同值属性（m/s）。
     */
    public static PatrolSchedule build(PatrolRoute route, float walkSpeed, float runSpeed,
                                       long epochMs) {
        if (walkSpeed <= 0f || runSpeed <= 0f
                || !Float.isFinite(walkSpeed) || !Float.isFinite(runSpeed)) {
            return null;
        }
        List<PatrolRoute.Waypoint> kept = filterByDy(route.waypoints());
        if (kept.size() < 2) {
            return null;
        }
        int segments = (kept.size() - 1) * 2;
        long[] starts = new long[segments];
        long[] travels = new long[segments];
        long[] dwells = new long[segments];
        int[] fromIdx = new int[segments];
        int[] toIdx = new int[segments];
        long cursor = 0;
        int k = 0;
        // 去程 w0→…→w(n-1)，回程 w(n-1)→…→w0；停留归属到达行。
        for (int i = 0; i + 1 < kept.size(); i++) {
            cursor = appendSegment(kept, i, i + 1, walkSpeed, runSpeed,
                    cursor, k++, starts, travels, dwells, fromIdx, toIdx);
        }
        for (int i = kept.size() - 1; i - 1 >= 0; i--) {
            cursor = appendSegment(kept, i, i - 1, walkSpeed, runSpeed,
                    cursor, k++, starts, travels, dwells, fromIdx, toIdx);
        }
        return new PatrolSchedule(route.pathId(), epochMs, cursor, List.copyOf(kept),
                starts, travels, dwells, fromIdx, toIdx);
    }

    private static List<PatrolRoute.Waypoint> filterByDy(List<PatrolRoute.Waypoint> waypoints) {
        List<PatrolRoute.Waypoint> kept = new ArrayList<>(waypoints.size());
        for (PatrolRoute.Waypoint w : waypoints) {
            if (!kept.isEmpty()
                    && Math.abs(w.y() - kept.get(kept.size() - 1).y()) > MAX_LEG_DY_METERS) {
                continue;
            }
            kept.add(w);
        }
        return kept;
    }

    private static long appendSegment(List<PatrolRoute.Waypoint> kept, int from, int to,
                                      float walkSpeed, float runSpeed, long cursor, int k,
                                      long[] starts, long[] travels, long[] dwells,
                                      int[] fromIdx, int[] toIdx) {
        PatrolRoute.Waypoint a = kept.get(from);
        PatrolRoute.Waypoint b = kept.get(to);
        float dx = b.x() - a.x();
        float dz = b.z() - a.z();
        float distXz = (float) Math.sqrt(dx * dx + dz * dz);
        float speed = b.run() ? runSpeed : walkSpeed;
        long travel = Math.max(TRAVEL_MIN_MILLIS, Math.min(TRAVEL_MAX_MILLIS,
                (long) (distXz / speed * 1000f)));
        starts[k] = cursor;
        travels[k] = travel;
        dwells[k] = b.dwellMs();
        fromIdx[k] = from;
        toIdx[k] = to;
        return cursor + travel + b.dwellMs();
    }

    public String pathId() {
        return pathId;
    }

    public long epochMs() {
        return epochMs;
    }

    public long cycleMs() {
        return cycleMs;
    }

    public int waypoints() {
        return points.size();
    }

    /** 时刻 nowMs 的当前位置（行程中插值；朝向=目标朝向或行进方向）。 */
    public Position positionAt(long nowMs) {
        int k = segmentAt(nowMs);
        PatrolRoute.Waypoint a = points.get(fromIdx[k]);
        PatrolRoute.Waypoint b = points.get(toIdx[k]);
        long offset = offsetOf(nowMs);
        long travelEnd = starts[k] + travels[k];
        if (offset >= travelEnd) {
            return new Position(b.x(), b.y(), b.z(), facingOf(a, b));
        }
        float frac = (offset - starts[k]) / (float) travels[k];
        return new Position(
                a.x() + (b.x() - a.x()) * frac,
                a.y() + (b.y() - a.y()) * frac,
                a.z() + (b.z() - a.z()) * frac,
                facingOf(a, b));
    }

    /** 时刻 nowMs 的下一动作：行进（含目标与剩余时长）或停留等待。 */
    public Object actionAt(long nowMs) {
        int k = segmentAt(nowMs);
        PatrolRoute.Waypoint a = points.get(fromIdx[k]);
        PatrolRoute.Waypoint b = points.get(toIdx[k]);
        long offset = offsetOf(nowMs);
        long travelEnd = starts[k] + travels[k];
        if (offset < travelEnd) {
            return new Moving(b.x(), b.y(), b.z(), facingOf(a, b), travelEnd - offset);
        }
        return new Dwelling(Math.max(0, travelEnd + dwells[k] - offset));
    }

    /** 朝向：路点有效朝向优先，否则按段行进方向。 */
    private static float facingOf(PatrolRoute.Waypoint from, PatrolRoute.Waypoint to) {
        if (to.hasFacing()) {
            return to.orient();
        }
        return (float) Math.atan2(to.x() - from.x(), to.z() - from.z());
    }

    private int segmentAt(long nowMs) {
        long offset = offsetOf(nowMs);
        for (int k = starts.length - 1; k >= 0; k--) {
            // 段区间 [starts[k], starts[k]+travel+dwell)；倒序首命中。
            if (offset >= starts[k]) {
                return k;
            }
        }
        return 0;
    }

    private long offsetOf(long nowMs) {
        return Math.floorMod(nowMs - epochMs, cycleMs);
    }
}
