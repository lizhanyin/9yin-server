package com.swingfrog.summer.ecs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 属性容器：协议属性 ID → 类型化值。写入即标记脏并通知监听器，
 * 同步层按脏标记生成 0x10 ServerObjectProperty 增量包（属性 ID ↔ 值）。
 * 与 Summer 实体一样约定单线程访问（玩家/场景线程）。
 */
public final class PropertySet {

    /** 属性变更监听器，oldValue 为 null 表示首次写入。 */
    public interface Listener {

        void onPropertyChange(int propertyId, Object oldValue, Object newValue);
    }

    private final Map<Integer, Object> values = new HashMap<>();
    private final LinkedHashSet<Integer> dirtyIds = new LinkedHashSet<>();
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public PropertySet set(int propertyId, Object value) {
        Object old = value == null ? values.remove(propertyId) : values.put(propertyId, value);
        if (!Objects.equals(old, value)) {
            dirtyIds.add(propertyId);
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).onPropertyChange(propertyId, old, value);
            }
        }
        return this;
    }

    public Object get(int propertyId) {
        return values.get(propertyId);
    }

    public int getInt(int propertyId, int defaultValue) {
        Object v = values.get(propertyId);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    public long getLong(int propertyId, long defaultValue) {
        Object v = values.get(propertyId);
        return v instanceof Number n ? n.longValue() : defaultValue;
    }

    public float getFloat(int propertyId, float defaultValue) {
        Object v = values.get(propertyId);
        return v instanceof Number n ? n.floatValue() : defaultValue;
    }

    public String getString(int propertyId, String defaultValue) {
        Object v = values.get(propertyId);
        return v instanceof String s ? s : defaultValue;
    }

    public boolean has(int propertyId) {
        return values.containsKey(propertyId);
    }

    public boolean isDirty() {
        return !dirtyIds.isEmpty();
    }

    /** 返回脏属性 ID 的有序快照并清除脏标记。 */
    public int[] drainDirty() {
        if (dirtyIds.isEmpty()) {
            return new int[0];
        }
        int[] ids = new int[dirtyIds.size()];
        int i = 0;
        for (Integer id : dirtyIds) {
            ids[i++] = id;
        }
        dirtyIds.clear();
        return ids;
    }
}
