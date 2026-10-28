package com.swingfrog.summer.ecs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * 均匀网格 AOI：实体位置索引 + 观察者可见集 diff。
 * 距离按 XZ 平面计算（地面游戏惯例，与 C++ 版 450m/250m 规则一致），Y 不参与判定。
 * 支撑场景同步：观察者 diff → View 进出（0x0D AddObject / 0x0E RemoveObject / 0x18/0x19），
 * 半径查询 → 初始 NPC 下发与移动增量补发。单场景单线程访问。
 */
public final class AoiManager {

    /** 观察者一次位置更新产生的可见集变化。 */
    public record Diff(List<Long> entered, List<Long> left) {

        public static final Diff EMPTY = new Diff(List.of(), List.of());
    }

    private static final class Entity {
        final long id;
        float x;
        float y;
        float z;
        long cell;

        Entity(long id, float x, float y, float z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final float cellSize;
    private final Map<Long, Entity> entities = new HashMap<>();
    private final Map<Long, Set<Long>> cells = new HashMap<>();
    private final Map<Long, Set<Long>> watcherVisible = new HashMap<>();
    private final Map<Long, Float> watcherRadius = new HashMap<>();

    public AoiManager(float cellSize) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("cellSize must be positive");
        }
        this.cellSize = cellSize;
    }

    public void addEntity(long id, float x, float y, float z) {
        Entity e = new Entity(id, x, y, z);
        e.cell = cellOf(x, z);
        entities.put(id, e);
        cellSet(e.cell).add(id);
    }

    public void removeEntity(long id) {
        Entity e = entities.remove(id);
        if (e != null) {
            removeFromCell(e);
        }
        // 不急切清理观察者可见集：实体已离开网格，下一轮 diff 自然报出 leave 事件
    }

    public boolean hasEntity(long id) {
        return entities.containsKey(id);
    }

    public int entityCount() {
        return entities.size();
    }

    /** 移动实体并更新网格归属。若 id 同时是观察者，由调用方决定何时刷新观察（updateWatcher）。 */
    public void moveEntity(long id, float x, float y, float z) {
        Entity e = entities.get(id);
        if (e == null) {
            return;
        }
        e.x = x;
        e.y = y;
        e.z = z;
        long newCell = cellOf(x, z);
        if (newCell != e.cell) {
            removeFromCell(e);
            e.cell = newCell;
            cellSet(newCell).add(id);
        }
    }

    /** 注册观察者（玩家），返回初始可见集。viewRadius 为其视野半径。 */
    public List<Long> addWatcher(long id, float x, float y, float z, float viewRadius) {
        if (!entities.containsKey(id)) {
            addEntity(id, x, y, z);
        }
        watcherRadius.put(id, viewRadius);
        Set<Long> visible = new HashSet<>(
                queryNearby(x, z, viewRadius, Integer.MAX_VALUE, other -> other != id));
        watcherVisible.put(id, visible);
        return List.copyOf(visible);
    }

    public void removeWatcher(long id) {
        watcherVisible.remove(id);
        watcherRadius.remove(id);
    }

    /** 观察者移动后刷新可见集，返回进出 diff（观察者自身不会出现在集合中）。 */
    public Diff updateWatcher(long id, float x, float y, float z) {
        Float radius = watcherRadius.get(id);
        if (radius == null) {
            return Diff.EMPTY;
        }
        moveEntity(id, x, y, z);
        Set<Long> oldVisible = watcherVisible.get(id);
        Set<Long> newVisible = new HashSet<>(
                queryNearby(x, z, radius, Integer.MAX_VALUE, other -> other != id));
        List<Long> entered = new ArrayList<>();
        List<Long> left = new ArrayList<>();
        for (Long v : newVisible) {
            if (!oldVisible.contains(v)) {
                entered.add(v);
            }
        }
        for (Long v : oldVisible) {
            if (!newVisible.contains(v)) {
                left.add(v);
            }
        }
        oldVisible.clear();
        oldVisible.addAll(newVisible);
        return new Diff(entered, left);
    }

    /**
     * 半径查询（XZ 平面），按距离升序返回，可带上限与过滤。
     * 对齐 C++ 场景规则：初始下发 450m/上限 200、增量补发上限 50。
     */
    public List<Long> queryNearby(float x, float z, float radius, int maxCount, LongPredicate filter) {
        List<Long> result = new ArrayList<>();
        float r2 = radius * radius;
        int span = (int) Math.ceil(radius / cellSize);
        long cx = Math.round(Math.floor(x / cellSize));
        long cz = Math.round(Math.floor(z / cellSize));
        List<long[]> hits = new ArrayList<>(); // [id, distSq] 打包避免再查一次
        for (long gz = cz - span; gz <= cz + span; gz++) {
            for (long gx = cx - span; gx <= cx + span; gx++) {
                Set<Long> cell = cells.get(packCell(gx, gz));
                if (cell == null) {
                    continue;
                }
                for (Long id : cell) {
                    Entity e = entities.get(id);
                    if (e == null || (filter != null && !filter.test(id))) {
                        continue;
                    }
                    float dx = e.x - x;
                    float dz = e.z - z;
                    float d2 = dx * dx + dz * dz;
                    if (d2 <= r2) {
                        hits.add(new long[]{id, Float.floatToRawIntBits(d2)});
                    }
                }
            }
        }
        hits.sort((a, b) -> Long.compare(a[1], b[1]));
        for (int i = 0; i < hits.size() && result.size() < maxCount; i++) {
            result.add(hits.get(i)[0]);
        }
        return result;
    }

    private void removeFromCell(Entity e) {
        Set<Long> set = cells.get(e.cell);
        if (set != null) {
            set.remove(e.id);
            if (set.isEmpty()) {
                cells.remove(e.cell);
            }
        }
    }

    private Set<Long> cellSet(long cell) {
        return cells.computeIfAbsent(cell, k -> new HashSet<>(16));
    }

    private long cellOf(float x, float z) {
        long gx = (long) Math.floor(x / cellSize);
        long gz = (long) Math.floor(z / cellSize);
        return packCell(gx, gz);
    }

    private long packCell(long gx, long gz) {
        return (gx << 32) | (gz & 0xFFFFFFFFL);
    }
}
