package com.nineyin.server.game.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * box_contents.json 描述重建对照：命中全发（amount=max）、键大小写不敏感、
 * 脏条目跳过、缺文件/空表容错（查询恒 null 回落 DropPacks）。
 */
class BoxContentsTest {

    @TempDir
    Path dir;

    private static final String CONTENTS = """
            {
              "note": "test fixture",
              "boxes": {
                "box_sample": [
                  { "item": "item_dmp_ptz", "min": 40, "max": 40 },
                  { "item": "item_dmp_fjsj", "min": 1, "max": 1 }
                ],
                "box_dirty": [
                  { "item": "", "min": 1, "max": 1 },
                  { "item": "lo0", "min": 0, "max": 1 },
                  { "item": "rev", "min": 5, "max": 2 },
                  { "item": "big", "min": 1, "max": 1000 },
                  { "item": "ok_min_only", "max": 3 },
                  { "item": "no_amount" }
                ]
              }
            }
            """;

    private BoxContents load(String json) throws IOException {
        Path file = dir.resolve("box-contents-" + System.nanoTime() + ".json");
        Files.writeString(file, json);
        return BoxContents.load(file);
    }

    @Test
    void hitGrantsAllEntriesWithMaxAmount() throws IOException {
        BoxContents contents = load(CONTENTS);
        List<DropPacks.GrantItem> out = contents.byBox("box_sample");
        assertNotNull(out);
        assertEquals(2, out.size());
        assertEquals("item_dmp_ptz", out.get(0).configId());
        assertEquals(40, out.get(0).amount());
        assertEquals("item_dmp_fjsj", out.get(1).configId());
        assertEquals(1, out.get(1).amount());
    }

    @Test
    void lookupIsCaseInsensitiveAndMissReturnsNull() throws IOException {
        BoxContents contents = load(CONTENTS);
        assertNotNull(contents.byBox("BOX_SAMPLE"));
        assertNotNull(contents.byBox(" Box_Sample "));
        assertNull(contents.byBox("box_not_in_table"));
        assertNull(contents.byBox(null));
        assertNull(contents.byBox(""));
    }

    @Test
    void invalidEntriesSkippedAndDefaultsApplied() throws IOException {
        BoxContents contents = load(CONTENTS);
        // lo0（min<1）/rev（max<min）/big（max>999）丢弃；ok_min_only 缺 min 以 max 补；no_amount 缺 max 默认 1
        List<DropPacks.GrantItem> out = contents.byBox("box_dirty");
        assertNotNull(out);
        assertEquals(2, out.size());
        assertEquals("ok_min_only", out.get(0).configId());
        assertEquals(3, out.get(0).amount());
        assertEquals("no_amount", out.get(1).configId());
        assertEquals(1, out.get(1).amount());
    }

    @Test
    void missingFileAndEmptyBoxesTolerated() throws IOException {
        BoxContents missing = BoxContents.load(dir.resolve("absent.json"));
        assertEquals(0, missing.size());
        assertNull(missing.byBox("box_sample"));
        BoxContents empty = load("{\"boxes\": {}}");
        assertEquals(0, empty.size());
        assertNull(empty.byBox("box_sample"));
        BoxContents malformed = load("not json at all");
        assertEquals(0, malformed.size());
    }
}
