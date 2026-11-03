package com.nineyin.tools.mpq.res;

import com.nineyin.tools.mpq.MpqWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 三层资源仓：本地 > patch > base，墓碑三层皆 miss。 */
class ResStoreTest {

    @TempDir
    Path dir;

    private byte[] createArchive(String file, LinkedHashMap<String, byte[]> files) {
        byte[] base = MpqWriter.create(MpqWriter.CreateOptions.defaults());
        return MpqWriter.modify(base, List.of(), files).bytes();
    }

    @Test
    void threeLayerOverrideAndTombstone() throws Exception {
        // base：a.txt / sub/b.txt / gone.txt
        LinkedHashMap<String, byte[]> baseFiles = new LinkedHashMap<>();
        baseFiles.put("resources\\a.txt", "base a".getBytes());
        baseFiles.put("resources\\sub\\b.txt", "base b".getBytes());
        baseFiles.put("resources\\gone.txt", "to be deleted".getBytes());
        Files.write(dir.resolve("res-base.rep"), createArchive("base", baseFiles));

        // patch：覆盖 a.txt + 新增 c.txt
        LinkedHashMap<String, byte[]> patchFiles = new LinkedHashMap<>();
        patchFiles.put("resources\\a.txt", "patch a".getBytes());
        patchFiles.put("resources\\c.txt", "patch c".getBytes());
        Files.write(dir.resolve("res-patch.rep"), createArchive("patch", patchFiles));

        // 本地散文件：覆盖 sub/b.txt
        Files.createDirectories(dir.resolve("resources/sub"));
        Files.write(dir.resolve("resources/sub/b.txt"), "local b".getBytes());

        // 清单：墓碑 gone.txt
        Files.write(dir.resolve("res-manifest.json"), ("""
                {
                  "base":  { "file": "res-base.rep",  "version": "0001" },
                  "patch": { "file": "res-patch.rep", "version": "0003" },
                  "deleted": ["resources\\\\gone.txt"]
                }""").getBytes());

        try (ResStore store = ResStore.open(dir)) {
            // 各层命中。
            assertEquals("local", store.layer("resources\\sub\\b.txt"));
            assertEquals("patch", store.layer("resources\\a.txt"));
            assertNull(store.layer("resources\\gone.txt")); // 墓碑：即便 base 有也 miss
            assertEquals("patch", store.layer("resources\\c.txt"));
            assertNull(store.layer("resources\\nope.txt"));

            // 内容按层正确。
            assertEquals("local b", new String(store.read("resources\\sub\\b.txt")));
            assertEquals("patch a", new String(store.read("resources\\a.txt")));
            assertEquals("patch c", new String(store.read("resources\\c.txt")));

            // 名字规范化：/ 与 \、大小写不敏感。
            assertEquals("local b", new String(store.read("resources/sub/B.TXT")));

            // 墓碑：三层都 miss。
            assertFalse(store.exists("resources\\gone.txt"));
            assertThrows(Exception.class, () -> store.read("resources\\gone.txt"));

            // 路径穿越被拒绝。
            assertNull(store.layer("..\\res-manifest.json"));

            // 合并清单：a(patch) + b(local) + c(patch)，gone 被移除。
            List<ResStore.ResFile> files = store.list();
            assertEquals(3, files.size());
            var byName = new java.util.HashMap<String, ResStore.ResFile>();
            files.forEach(f -> byName.put(f.name().toLowerCase(), f));
            assertEquals("patch", byName.get("resources\\a.txt".toLowerCase()).layer());
            assertEquals("local", byName.get("resources\\sub\\b.txt".toLowerCase()).layer());
            assertEquals("patch", byName.get("resources\\c.txt".toLowerCase()).layer());

            // 热重载：换 patch 内容后 reload 可见。
            LinkedHashMap<String, byte[]> patchV2 = new LinkedHashMap<>();
            patchV2.put("resources\\a.txt", "patch a v2".getBytes());
            patchV2.put("resources\\c.txt", "patch c".getBytes());
            Files.write(dir.resolve("res-patch.rep"), createArchive("patch", patchV2));
            store.reload();
            assertEquals("patch a v2", new String(store.read("resources\\a.txt")));
        }
    }

    @Test
    void manifestlessBaseOnlyMode() throws Exception {
        LinkedHashMap<String, byte[]> baseFiles = new LinkedHashMap<>();
        baseFiles.put("resources\\a.txt", "base a".getBytes());
        Files.write(dir.resolve("res-base.rep"), createArchive("base", baseFiles));

        // 无清单：按缺省布局（base + 本地层）打开。
        try (ResStore store = ResStore.open(dir)) {
            assertFalse(store.hasPatch());
            assertEquals("base", store.layer("resources\\a.txt"));
            assertEquals("base a", new String(store.read("resources\\a.txt")));
        }
    }

    @Test
    void missingBaseIsRejected() {
        assertThrows(Exception.class, () -> ResStore.open(dir));
    }
}
