package com.nineyin.server.game.avatar.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 风物志皮肤目录解析测试：ModelData 解析纯函数 + 真实 card.ini 目录
 * （资源不可用时跳过，ProtocolGoldenTest 同款 assume 方言）。
 */
class SkinCatalogTest {

    private static final Path E_GO_ROOT = Path.of("E:/Workspace/project/game/9yin-go-server1");
    private static final Path G_GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    private static SkinCatalog catalog() {
        Path root = Files.isDirectory(E_GO_ROOT.resolve("resources/modern/share/rule")) ? E_GO_ROOT
                : Files.isDirectory(G_GO_ROOT.resolve("resources/modern/share/rule")) ? G_GO_ROOT : null;
        Assumptions.assumeTrue(root != null, "参考服 share 资源不可用，跳过真实 card.ini 目录断言");
        // 不走 loadDefault/ServerPaths 覆盖——全局根覆盖会污染同 JVM 内其他测试
        // （JsonRoleRepository 的 data/ 目录按 serverRoot 解析）。
        return SkinCatalog.load(root.resolve(Path.of("resources", "modern", "share", "rule")));
    }

    @Test
    void parseModelsSinglePart() {
        List<SkinCatalog.PartModel> models = SkinCatalog.parseModels("Weapon,blade_card_001");
        assertEquals(1, models.size());
        assertEquals("Weapon", models.get(0).part());
        assertEquals("blade_card_001", models.get(0).model());
    }

    @Test
    void parseModelsMultiPartSuit() {
        List<SkinCatalog.PartModel> models = SkinCatalog.parseModels(
                "Hat,obj\\char\\b_h1\\b_helmet_1;Cloth,obj\\char\\b_h1\\b_cloth_1;Shoes,obj\\char\\b_h1\\b_shoes_1");
        assertEquals(3, models.size());
        assertEquals("Cloth", models.get(1).part());
        assertEquals("obj\\char\\b_h1\\b_shoes_1", models.get(2).model());
    }

    @Test
    void parseModelsToleratesGarbage() {
        assertTrue(SkinCatalog.parseModels("").isEmpty());
        assertTrue(SkinCatalog.parseModels("nodata").isEmpty());
        assertEquals(1, SkinCatalog.parseModels("Hat,ok_model;;,").size());
    }

    @Test
    void hairOnlyCardFiltersHatPart() {
        SkinCatalog.Skin hairOnly = new SkinCatalog.Skin(1, "card_a",
                List.of(new SkinCatalog.PartModel("Hat", "helmet_x")), List.of(),
                0, 0, 0, 0, 0);
        SkinCatalog.Skin weapon = new SkinCatalog.Skin(2, "card_b",
                List.of(new SkinCatalog.PartModel("Weapon", "blade_card_001")), List.of(),
                0, 0, 0, 0, 0);
        SkinCatalog.Skin suit = new SkinCatalog.Skin(3, "card_c",
                List.of(new SkinCatalog.PartModel("Hat", "helmet_y"),
                        new SkinCatalog.PartModel("Cloth", "cloth_y")), List.of(),
                0, 0, 0, 0, 0);
        assertTrue(hairOnly.hairOnly());
        assertFalse(weapon.hairOnly());
        assertFalse(suit.hairOnly());
        // 无女性数据回退男性列
        assertEquals(weapon.models(1).get(0).model(), "blade_card_001");
    }

    @Test
    void realCatalogLoadsWeaponSkin() {
        SkinCatalog catalog = catalog();
        assertFalse(catalog.isEmpty());
        assertTrue(catalog.maxId() > 0);
        // card.ini 首节 [1]：屠龙（Weapon,blade_card_001）
        SkinCatalog.Skin first = catalog.byId(1);
        assertNotNull(first);
        assertEquals("Weapon", first.male().get(0).part());
        assertEquals("blade_card_001", first.male().get(0).model());
        assertEquals(catalog.all().size(), catalog.all().size()); // 稳定快照
    }
}
