package com.nineyin.server.game.avatar.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 风物志皮肤存档 JSON 主档测试（CustomizingStore 同款形态；DB 双源另走
 * 集成测试，此处只验 JSON 往返与原子落盘）。
 */
class SkinStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveLoadRoundTrip() {
        Path file = tempDir.resolve("skins.json");
        SkinStore store = SkinStore.open(file);
        store.save(618, new SkinStore.State(
                java.util.List.of(1, 2, 33), java.util.List.of(33)));
        store.save(7, new SkinStore.State(java.util.List.of(5), java.util.List.of()));

        SkinStore reopened = SkinStore.open(file);
        SkinStore.State state = reopened.load(618);
        assertEquals(java.util.List.of(1, 2, 33), state.collected());
        assertEquals(java.util.List.of(33), state.mounted());
        assertTrue(state.collectedContains(33));
        assertFalse(state.collectedContains(9));

        SkinStore.State empty = reopened.load(7);
        assertEquals(java.util.List.of(5), empty.collected());
        assertEquals(java.util.List.of(), empty.mounted());
        assertNull(reopened.load(999));
    }

    @Test
    void emptyStateNotPersisted() {
        Path file = tempDir.resolve("skins.json");
        SkinStore store = SkinStore.open(file);
        store.save(618, SkinStore.State.empty());
        assertNull(SkinStore.open(file).load(618));
    }

    @Test
    void openWithoutFileIsEmpty() {
        SkinStore store = SkinStore.open(tempDir.resolve("missing.json"));
        assertNull(store.load(618));
    }

    @Test
    void persistedDocumentIsGoJsonIndented() throws Exception {
        Path file = tempDir.resolve("skins.json");
        SkinStore store = SkinStore.open(file);
        store.save(618, new SkinStore.State(java.util.List.of(1), java.util.List.of(1)));
        String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("\"version\": 1"));
        assertTrue(text.contains("\"collected\": ["));
        assertTrue(text.contains("\"mounted\": ["));
    }
}
