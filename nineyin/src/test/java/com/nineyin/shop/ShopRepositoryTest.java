package com.nineyin.shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesSectionsAndDuplicateRowKeys() throws Exception {
        Path ini = tempDir.resolve("shop.ini");
        Files.writeString(ini, """
                [Shop_test_01]
                NameID=测试商店
                PageInfo=Page1,Page2
                Type=1
                0=item_a,1,2,100,0,1,0,13094,543,,
                1=item_b,1,3,0,0,0,0,13093,0,,

                [Shop_free]
                NameID=免费商店
                PageInfo=Page1
                Type=0
                0=box_1,1,3,0,0,1,0,13097,0,,
                0=box_2,1,3,0,0,1,1,13098,0,,
                """, Charset.forName("GBK"));

        ShopRepository repo = new ShopRepository();
        repo.load(ini);
        assertEquals(2, repo.size());

        ShopRepository.Shop test = repo.get("Shop_test_01");
        assertEquals("测试商店", test.nameId());
        assertEquals("Page1,Page2", test.pageInfo());
        assertEquals(1, test.type());
        assertEquals(2, test.items().size());
        assertEquals("item_a", test.items().get(0).columns().get(0));
        assertEquals("13094", test.items().get(0).columns().get(7));

        // 同键多行按出现顺序保留
        ShopRepository.Shop free = repo.get("Shop_free");
        assertEquals(2, free.items().size());
        assertEquals("box_1", free.items().get(0).columns().get(0));
        assertEquals("box_2", free.items().get(1).columns().get(0));
    }
}
