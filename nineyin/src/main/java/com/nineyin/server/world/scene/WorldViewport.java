package com.nineyin.server.world.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端视口快照与增量，逐行移植 Go internal/world/viewport.go。
 * Plan 只算不提交：网络写全部成功后才 Commit，失败留下旧快照供下次对账重试。
 */
public final class WorldViewport {

    /** Go worldcore.Delta。 */
    public static final class Delta {
        public final long previousGeneration;
        public final long generation;
        public final List<WorldScene.Entity> adds = new ArrayList<>();
        public final List<WorldScene.Entity> updates = new ArrayList<>();
        public final List<WorldScene.Entity> removes = new ArrayList<>();
        final long baseEpoch;
        final Map<Integer, WorldScene.Entity> desired;

        Delta(long previousGeneration, long generation, long baseEpoch, int capacity) {
            this.previousGeneration = previousGeneration;
            this.generation = generation;
            this.baseEpoch = baseEpoch;
            this.desired = new HashMap<>(capacity);
        }
    }

    private long generation;
    private long epoch;
    private Map<Integer, WorldScene.Entity> materialized = new HashMap<>();

    /** Go Viewport.Plan（同代：按 revision 判更新/新增/移除；换代：全删全加）。 */
    public synchronized Delta plan(WorldScene.Snapshot snapshot) {
        Delta delta = new Delta(generation, snapshot.generation(), epoch, snapshot.entities().size());
        for (WorldScene.Entity entity : snapshot.entities()) {
            delta.desired.put(entity.id, entity);
        }
        if (snapshot.generation() != generation) {
            for (WorldScene.Entity entity : materialized.values()) {
                delta.removes.add(entity);
            }
            for (WorldScene.Entity entity : snapshot.entities()) {
                delta.adds.add(entity);
            }
        } else {
            for (WorldScene.Entity entity : snapshot.entities()) {
                WorldScene.Entity old = materialized.get(entity.id);
                if (old == null) {
                    delta.adds.add(entity);
                } else if (old.revision != entity.revision) {
                    delta.updates.add(entity);
                }
            }
            for (Map.Entry<Integer, WorldScene.Entity> entry : materialized.entrySet()) {
                if (!delta.desired.containsKey(entry.getKey())) {
                    delta.removes.add(entry.getValue());
                }
            }
        }
        delta.removes.sort((a, b) -> Integer.compare(a.id, b.id));
        return delta;
    }

    public synchronized void commit(Delta delta) {
        if (delta.baseEpoch != epoch || delta.previousGeneration != generation) {
            throw new IllegalStateException("world: stale viewport delta");
        }
        generation = delta.generation;
        materialized = new HashMap<>(delta.desired);
        epoch++;
    }

    public synchronized long generation() {
        return generation;
    }
}
