package com.nineyin.server.world.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.util.ServerPaths;

/**
 * 商店/对话数据层解析：share/trade/shop.ini（键不可信——节内重复 "0" 与顺排两态）
 * 与 optiontalk 三件套，基准 9yin_server1 部署目录（与 tools/mpq 同款真值源）。
 * 值锚点来自 F 服开商店抓包（tools/smoke/shop_probe_frames.txt）。
 */
class ShopCatalogTest {

    private static final Path SERVER_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");

    private static ShopCatalog shops;
    private static OptionTalkCatalog optionTalk;

    @BeforeAll
    static void needsSharedResources() {
        assumeTrue(Files.isRegularFile(
                SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "trade", "shop.ini"))),
                "shop.ini 不可用，跳过商店目录测试");
        assumeTrue(Files.isRegularFile(
                SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "rule",
                        "optiontalk_npc.ini"))),
                "optiontalk 资源不可用，跳过对话目录测试");
        ServerPaths.overrideServerRoot(SERVER_ROOT);
        shops = ShopCatalog.load(SERVER_ROOT.resolve(
                Path.of("resources", "modern", "share", "trade", "shop.ini")));
        optionTalk = OptionTalkCatalog.load(SERVER_ROOT.resolve(
                Path.of("resources", "modern", "share", "rule")));
    }

    @Test
    void khdShopRowsSurviveDuplicateZeroKeys() {
        ShopCatalog.ShopSection section = shops.section("shop_khd_ywb");
        assertEquals("骰子", section.nameId(), "NameID（GBK）");
        assertEquals(1, section.pageCount(), "PageInfo=Page1 → PageCount=1（抓包 103=1）");
        List<ShopCatalog.GoodsRow> goods = section.goods();
        assertEquals(9, goods.size(), "九行全是键 \"0\"，按文件顺序保留（抓包 ViewAdd 恰 9 行）");
        assertEquals("item_khd_001", goods.get(0).itemId());
        assertEquals(1, goods.get(0).amount(), "f1 → Amount（抓包 106=1）");
        assertEquals(10000, goods.get(0).sellPrice2(), "f3 → SellPrice2（抓包 109=10000）");
        assertEquals("item_khd_xyl", goods.get(8).itemId(), "末行顺序位不变");
        assertEquals(8, goods.get(8).index(), "顺序下标 0 起");
    }

    @Test
    void sequentialKeySectionsParse() {
        ShopCatalog.ShopSection section = shops.section("Shop_menpai_xtc_01");
        assertEquals(3, section.pageCount(), "PageInfo=Page1,Page2,Page3 → 3");
        assertEquals(3, section.goods().size(), "0/1/2 顺排键节同样三行");
        assertEquals("signup_schoolpose_12", section.goods().get(0).itemId());
        assertEquals(100000, section.goods().get(0).sellPrice2());
        assertEquals(0, section.goods().get(1).sellPrice2(), "无价行（兑换类）价格 0");
    }

    @Test
    void lookupIsCaseInsensitiveAndUnknownSafe() {
        assertEquals("item_khd_001", shops.section("SHOP_KHD_YWB").goods().get(0).itemId());
        assertNull(shops.section("Shop_00302"), "operate.ini 死引用（shop.ini 无此节）返回 null");
        assertNull(shops.section(null));
        assertNull(shops.section(""));
    }

    @Test
    void optionTalkChainForBornVillage() {
        assertEquals(62, optionTalk.dialogIdFor("WorldNpc00002"), "born04 千灯镇 → DialogID=62");
        OptionTalkCatalog.Node node = optionTalk.node(62);
        assertEquals(List.of(63, 64), node.nextPages(), "NextPage=63,64 双链");
        assertTrue(node.options().isEmpty(), "剧情节点无选项");

        OptionTalkCatalog.Node page = optionTalk.node(63);
        assertEquals(2, page.options().size());
        assertEquals("task", page.options().get(0).type());
        assertEquals("title_10187", page.options().get(0).textKey());
        assertEquals("10187", page.options().get(0).target());
    }

    @Test
    void shopAndGiveItemOptionsParse() {
        OptionTalkCatalog.Node node = optionTalk.node(69);
        assertEquals(1, node.options().size(), "FuncNpc00009 → 69：一项 shop + 两条 NextPage");
        OptionTalkCatalog.Option shop = node.options().get(0);
        assertEquals("shop", shop.type());
        assertEquals("biaoti00007", shop.textKey(), "biaoti00007=听说这里出售李白精选诗集？");
        assertEquals("Shop_special_002", shop.target());

        OptionTalkCatalog.Option giveItem = optionTalk.node(2381).options().get(0);
        assertEquals("giveitem", giveItem.type());
        assertEquals("Box_qyjl_gcd_009", giveItem.itemId(), "目标 Box_qyjl_gcd_009*1 → itemId");
        assertEquals(1, giveItem.itemCount());

        assertEquals(2, optionTalk.operateType("shop"), "operater_type：shop=2 task=1");
        assertEquals(1, optionTalk.operateType("task"));
        assertEquals(0, optionTalk.operateType("nope"));
    }
}
