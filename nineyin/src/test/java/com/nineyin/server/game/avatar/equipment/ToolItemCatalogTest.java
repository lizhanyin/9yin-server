package com.nineyin.server.game.avatar.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * GM 发放物品目录测试：equipment.ini + tool_item.ini 合并、大小写不敏感查找、
 * 时装/武器 EquipType 可枚举（资源不可用时跳过真实表断言）。
 */
class ToolItemCatalogTest {

    private static final Path E_GO_ROOT = Path.of("E:/Workspace/project/game/9yin-go-server1");
    private static final Path G_GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    private static ToolItemCatalog catalog() {
        Path root = Files.isDirectory(E_GO_ROOT.resolve("resources/modern/share/item")) ? E_GO_ROOT
                : Files.isDirectory(G_GO_ROOT.resolve("resources/modern/share/item")) ? G_GO_ROOT : null;
        assumeTrue(root != null, "参考服 share 资源不可用，跳过真实物品目录断言");
        // 不走 loadDefault/ServerPaths 覆盖——全局根覆盖会污染同 JVM 内其他测试。
        return ToolItemCatalog.load(root.resolve(Path.of("resources", "modern", "share", "item")));
    }

    @Test
    void lookupIsCaseInsensitiveAndUnknownFails() {
        ToolItemCatalog catalog = catalog();
        ToolItemCatalog.ItemInfo fashion = catalog.lookup("cloth_sz001");
        assertNotNull(fashion);
        assertEquals("FashionCoat", fashion.equipType());
        assertEquals(fashion.configId(), catalog.lookup("CLOTH_SZ001").configId());
        assertNull(catalog.lookup("no_such_item"));
        assertNull(catalog.lookup(""));
        assertNull(catalog.lookup(null));
    }

    @Test
    void catalogCoversEquipAndToolItems() {
        ToolItemCatalog catalog = catalog();
        assertTrue(catalog.all().size() > 10_000); // equipment 2.6万 + tool 5万+
        int weapons = 0;
        int fashionCoats = 0;
        for (ToolItemCatalog.ItemInfo info : catalog.all()) {
            if (info.equipType().equals("Weapon")) {
                weapons++;
            }
            if (info.equipType().equals("FashionCoat")) {
                fashionCoats++;
            }
        }
        assertTrue(weapons > 1000, "EquipType=Weapon 应有数千件，实际 " + weapons);
        assertEquals(525, fashionCoats, "GM 面板口径：FashionCoat 时装共 525 套");
    }

    @Test
    void tempIniParsesViewIdAndScript() throws Exception {
        Path itemRoot = java.nio.file.Files.createTempDirectory("tool-catalog");
        // 目录按 GBK 读表（GmCatalog 同口径），临时 ini 也须 GBK 落盘。
        java.nio.file.Files.write(itemRoot.resolve("tool_item.ini"), (""
                + "[//注释节]\r\nName=应跳过\r\n\r\n"
                + "[gift_box_1]\r\nName=礼包\r\nViewID=1\r\nscript=BoxItem\r\n"
                + "ItemType=2102\r\nMaxAmount=10\r\n"
                + "[card_item_10001]\r\nName=屠龙刀鞘\r\nscript=CardItem\r\nCardID=1\r\nViewID=1\r\n")
                .getBytes(java.nio.charset.Charset.forName("GBK")));
        ToolItemCatalog catalog = ToolItemCatalog.load(itemRoot);
        assertNull(catalog.lookup("//注释节"));
        ToolItemCatalog.ItemInfo gift = catalog.lookup("gift_box_1");
        assertNotNull(gift);
        assertEquals("礼包", gift.name());
        assertEquals(1, gift.viewId());
        assertEquals("BoxItem", gift.script());
        assertEquals(2102, gift.itemType());
        assertEquals(10, gift.maxAmount());
        // 风物志卡片：CardID 直连 rule/card.ini 皮肤号（使用后收录衣橱）。
        ToolItemCatalog.ItemInfo card = catalog.lookup("card_item_10001");
        assertNotNull(card);
        assertEquals(1, card.cardId());
        assertTrue(card.isSkinCard());
        assertFalse(gift.isSkinCard());
        assertEquals("card_item_10001", catalog.cardItemBySkinId(1).configId());
        assertNull(catalog.cardItemBySkinId(777));
    }

    @Test
    void realCatalogMapsCardItemsToSkins() {
        ToolItemCatalog catalog = catalog();
        ToolItemCatalog.ItemInfo tulong = catalog.cardItemBySkinId(1);
        assertNotNull(tulong, "皮肤 1（屠龙）应有卡片道具 card_item_10001");
        assertEquals("card_item_10001", tulong.configId());
        assertEquals("CardItem", tulong.script());
        int cards = 0;
        for (ToolItemCatalog.ItemInfo info : catalog.all()) {
            if (info.isSkinCard()) {
                cards++;
            }
        }
        assertTrue(cards > 1000, "script=CardItem 卡片应上千张，实际 " + cards);
    }
}
