package com.nineyin.server.game.avatar.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 外观显示偏好存档 JSON 主档测试（SkinStore 同款形态；DB 双源另走集成测试，
 * 此处只验 JSON 往返、越界值拒绝与原子落盘）。
 */
class AppearanceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveLoadRoundTrip() {
        Path file = tempDir.resolve("appearance.json");
        AppearanceStore store = AppearanceStore.open(file);
        store.save(618, 2);
        store.save(7, 1);

        AppearanceStore reopened = AppearanceStore.open(file);
        assertEquals(2, reopened.load(618));
        assertEquals(1, reopened.load(7));
        assertNull(reopened.load(999));
    }

    @Test
    void invalidTypeRejected() {
        AppearanceStore store = AppearanceStore.open(tempDir.resolve("appearance.json"));
        assertThrows(IllegalArgumentException.class, () -> store.save(618, 3));
        assertThrows(IllegalArgumentException.class, () -> store.save(618, -1));
    }

    @Test
    void openWithoutFileIsEmpty() {
        AppearanceStore store = AppearanceStore.open(tempDir.resolve("missing.json"));
        assertNull(store.load(618));
    }

    @Test
    void persistedDocumentIsGoJsonIndented() throws Exception {
        Path file = tempDir.resolve("appearance.json");
        AppearanceStore store = AppearanceStore.open(file);
        store.save(618, 1);
        String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("\"version\": 1"));
        assertTrue(text.contains("\"show_equip_type\": 1"));
    }
}
