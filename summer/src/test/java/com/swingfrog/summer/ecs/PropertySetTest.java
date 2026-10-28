package com.swingfrog.summer.ecs;

import com.swingfrog.summer.ecs.property.PropertySet;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertySetTest {

    @Test
    void setMarksDirtyAndNotifies() {
        List<Map.Entry<Object, Object>> changes = new ArrayList<>();
        PropertySet props = new PropertySet();
        props.addListener((id, old, cur) -> changes.add(new SimpleEntry<>(old, cur)));

        props.set(PropertyId.HP_TEST, 100);
        props.set(PropertyId.HP_TEST, 950);

        assertTrue(props.isDirty());
        assertArrayEquals(new int[]{PropertyId.HP_TEST}, props.drainDirty());
        assertFalse(props.isDirty());
        assertEquals(2, changes.size());
        assertEquals(null, changes.get(0).getKey());
        assertEquals(100, changes.get(0).getValue());
        assertEquals(100, changes.get(1).getKey());
        assertEquals(950, changes.get(1).getValue());
    }

    @Test
    void sameValueDoesNotDirty() {
        PropertySet props = new PropertySet();
        props.set(PropertyId.HP_TEST, 100);
        props.drainDirty();
        props.set(PropertyId.HP_TEST, 100);
        assertFalse(props.isDirty());
    }

    @Test
    void typedAccessors() {
        PropertySet props = new PropertySet();
        props.set(1, 42).set(2, 9999999999L).set(3, 1.5f).set(4, "stand");
        assertEquals(42, props.getInt(1, -1));
        assertEquals(9999999999L, props.getLong(2, -1));
        assertEquals(1.5f, props.getFloat(3, -1));
        assertEquals("stand", props.getString(4, null));
        assertEquals(-1, props.getInt(99, -1));
        assertEquals("x", props.getString(99, "x"));
    }

    @Test
    void multipleDirtyIdsKeepInsertionOrder() {
        PropertySet props = new PropertySet();
        props.set(3, 1).set(1, 2).set(3, 3);
        assertArrayEquals(new int[]{3, 1}, props.drainDirty());
    }

    private static final class PropertyId {
        static final int HP_TEST = 464;
    }
}
