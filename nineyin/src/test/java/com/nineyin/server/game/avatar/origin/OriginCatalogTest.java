package com.nineyin.server.game.avatar.origin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 手写最小四表解析：数字节过滤、r 重复键多行收集、坏行丢弃、缺根 present=false
 * （生产 1197 表的全量口径由 LoginFlowGmCommandTest 身份全链用例覆盖）。
 */
class OriginCatalogTest {

    @Test
    void parsesMinimalTablesAndFiltersBadRows(@TempDir Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("origin.ini"),
                "[2]\nType1=1\n\n[bogus]\nr=x\n\n[10]\nType1=2\n");
        Files.writeString(root.resolve("prize_item.ini"),
                "[2]\nr=item_a,2\nr=item_b\n\n[10]\nr=item_c,x\nr=,1\n");
        Files.writeString(root.resolve("prize_title.ini"),
                "[2]\nr=5\nr=6\n\n[10]\nr=bogus\n");
        Files.writeString(root.resolve("prize_prop.ini"),
                "[2]\nr=repute_shaolin,50\n\n[10]\nr=bogus\nr=repute_x,\n");

        OriginCatalog catalog = OriginCatalog.load(root);

        assertTrue(catalog.present());
        assertEquals(List.of(2, 10), catalog.originIds(), "数字升序，非数字节剔除");
        assertTrue(catalog.contains(2));
        assertFalse(catalog.contains(3));

        List<OriginCatalog.ItemPrize> items = catalog.itemPrizes(2);
        assertEquals(2, items.size(), "r 重复键逐行收集");
        assertEquals(new OriginCatalog.ItemPrize("item_a", 2), items.get(0));
        assertEquals(new OriginCatalog.ItemPrize("item_b", 1), items.get(1), "缺数量段默认 1");
        assertTrue(catalog.itemPrizes(10).isEmpty(), "坏数量/空 config 行丢弃");

        assertEquals(List.of(5, 6), catalog.titlePrizes(2));
        assertTrue(catalog.titlePrizes(10).isEmpty(), "非数字称号行丢弃");

        assertEquals(1, catalog.propPrizes(2).size());
        assertEquals(new OriginCatalog.PropPrize("repute_shaolin", 50), catalog.propPrizes(2).get(0));
        assertTrue(catalog.propPrizes(10).isEmpty(), "缺值/非阵营格式行丢弃");
    }

    @Test
    void missingRootYieldsEmptyNotPresent(@TempDir Path root) {
        OriginCatalog catalog = OriginCatalog.load(root);
        assertFalse(catalog.present());
        assertTrue(catalog.originIds().isEmpty());
        assertTrue(catalog.itemPrizes(1).isEmpty());
    }
}
