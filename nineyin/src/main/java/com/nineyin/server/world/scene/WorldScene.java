package com.nineyin.server.world.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.nineyin.server.protocol.PropertyValues.Value;
import com.nineyin.server.protocol.WireType;

/**
 * 服务端场景实体库，逐行移植 Go internal/world（entity.go/scene.go）。
 * 属性以稳定客户端属性名索引；数值可视索引属于协商线型，不进世界模型。
 */
public final class WorldScene {

    public enum EntityKind {
        PLAYER, NPC, OBJECT
    }

    /** Go worldcore.Transform。 */
    public record Transform(float x, float y, float z, float orient) {
        public boolean finite() {
            return x == x && y == y && z == z && orient == orient
                    && x != Float.POSITIVE_INFINITY && x != Float.NEGATIVE_INFINITY
                    && y != Float.POSITIVE_INFINITY && y != Float.NEGATIVE_INFINITY
                    && z != Float.POSITIVE_INFINITY && z != Float.NEGATIVE_INFINITY
                    && orient != Float.POSITIVE_INFINITY && orient != Float.NEGATIVE_INFINITY;
        }
    }

    /** Go worldcore.Entity + VersionedEntity。 */
    public static final class Entity {
        public final int id;
        public final int ownerId;
        public final EntityKind kind;
        public Transform transform;
        public final Map<String, Value> properties;
        public final long revision;

        Entity(int id, int ownerId, EntityKind kind, Transform transform,
               Map<String, Value> properties, long revision) {
            this.id = id;
            this.ownerId = ownerId;
            this.kind = kind;
            this.transform = transform;
            this.properties = properties;
            this.revision = revision;
        }
    }

    /** Go worldcore.Snapshot。 */
    public record Snapshot(long generation, String config, String resource, List<Entity> entities) {
    }

    private long generation;
    private long revision;
    private String config = "";
    private String resource = "";
    private final Map<Integer, Entity> entities = new TreeMap<>();

    public synchronized long begin(String config, String resource) {
        String cleanConfig = config.trim();
        String cleanResource = resource.trim();
        if (cleanConfig.isEmpty() || cleanResource.isEmpty()) {
            throw new IllegalStateException("world: scene config and resource are required");
        }
        generation++;
        if (generation == 0) {
            generation++;
        }
        this.config = cleanConfig;
        this.resource = cleanResource;
        entities.clear();
        return generation;
    }

    /** 当前场景 config（begin 时更新；副本波次门控等轻量查询用，不复制实体表）。 */
    public synchronized String sceneConfig() {
        return config;
    }

    public synchronized void add(int id, int ownerId, EntityKind kind, Transform transform,
                                 Map<String, Value> properties) {
        validate(id, kind, transform, properties);
        if (generation == 0) {
            throw new IllegalStateException("world: scene generation has not begun");
        }
        if (entities.containsKey(id)) {
            throw new IllegalStateException("world: duplicate entity " + id);
        }
        revision++;
        entities.put(id, new Entity(id, ownerId, kind, transform, copy(properties), revision));
    }

    public synchronized void replace(Entity replacement) {
        validate(replacement.id, replacement.kind, replacement.transform, replacement.properties);
        if (!entities.containsKey(replacement.id)) {
            throw new IllegalStateException("world: entity not found " + replacement.id);
        }
        revision++;
        entities.put(replacement.id, new Entity(replacement.id, replacement.ownerId, replacement.kind,
                replacement.transform, copy(replacement.properties), revision));
    }

    public synchronized Entity get(int id) {
        Entity entity = entities.get(id);
        if (entity == null) {
            throw new IllegalStateException("world: entity not found " + id);
        }
        return new Entity(entity.id, entity.ownerId, entity.kind, entity.transform,
                copy(entity.properties), entity.revision);
    }

    public synchronized void remove(int id) {
        if (entities.remove(id) == null) {
            throw new IllegalStateException("world: entity not found " + id);
        }
    }

    public synchronized Snapshot snapshot() {
        List<Entity> list = new ArrayList<>(entities.size());
        for (Entity entity : entities.values()) {
            list.add(new Entity(entity.id, entity.ownerId, entity.kind, entity.transform,
                    copy(entity.properties), entity.revision));
        }
        return new Snapshot(generation, config, resource, list);
    }

    private static Map<String, Value> copy(Map<String, Value> properties) {
        return new HashMap<>(properties);
    }

    private static void validate(int id, EntityKind kind, Transform transform,
                                 Map<String, Value> properties) {
        if (id == 0) {
            throw new IllegalStateException("world: entity ID 0 is invalid");
        }
        if (!transform.finite()) {
            throw new IllegalStateException("world: entity transform contains a non-finite value");
        }
        for (Map.Entry<String, Value> property : properties.entrySet()) {
            if (property.getKey().trim().isEmpty()) {
                throw new IllegalStateException("world: entity contains an empty property name");
            }
            Value value = property.getValue();
            if (value.type() == WireType.FLOAT32
                    && (value.f32() != value.f32() || value.f32() == Float.POSITIVE_INFINITY
                    || value.f32() == Float.NEGATIVE_INFINITY)) {
                throw new IllegalStateException("world: property " + property.getKey()
                        + " is a non-finite float");
            }
        }
    }
}
