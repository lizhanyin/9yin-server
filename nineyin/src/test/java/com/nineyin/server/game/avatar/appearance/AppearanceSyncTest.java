package com.nineyin.server.game.avatar.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.world.db.RoleInventoryStore;

/**
 * 装备外观挂载测试：EquipType→槽位映射、时装镜像 16-19→40-43、
 * 已穿装备对 RoleVisual 的部位覆盖、三模式最终模型裁决（EquipmentResolver
 * 用临时 ini 桩表）。
 */
class AppearanceSyncTest {

    @TempDir
    Path tempDir;

    /** 桩表：江湖装衣/裤 + 身份装整套（分件帽/鞋包）+ 时装衣（套装式帽包）+ 时装帽/鞋单件。 */
    private EquipmentResolver resolver() {
        try {
            java.nio.file.Files.writeString(tempDir.resolve("equipment.ini"), ""
                    + "[cfg_cloth_worn]\r\nArtPack=9\r\n\r\n"
                    + "[cfg_pants_gear]\r\nArtPack=31\r\n\r\n"
                    + "[cfg_identity]\r\nArtPack=11\r\nHatArtPack=12\r\nPlantsArtPack=-1\r\nShoesArtPack=13\r\n\r\n"
                    + "[cfg_fashion_coat]\r\nArtPack=21\r\nHatArtPack=22\r\nShoesArtPack=-1\r\n\r\n"
                    + "[cfg_fashion_hat]\r\nArtPack=23\r\n\r\n"
                    + "[cfg_fashion_shoes]\r\nArtPack=24\r\n\r\n");
            java.nio.file.Files.writeString(tempDir.resolve("itemartstatic.ini"), ""
                    + "[9]\r\nMaleModel=obj\\worn\\cloth\r\n\r\n"
                    + "[31]\r\nMaleModel=obj\\gear\\pants\r\n\r\n"
                    + "[11]\r\nMaleModel=obj\\identity\\suit\r\n\r\n"
                    + "[12]\r\nMaleModel=obj\\identity\\hat\r\n\r\n"
                    + "[13]\r\nMaleModel=obj\\identity\\shoes\r\n\r\n"
                    + "[21]\r\nMaleModel=obj\\fashion\\coat\r\n\r\n"
                    + "[22]\r\nMaleModel=obj\\fashion\\packhat\r\n\r\n"
                    + "[23]\r\nMaleModel=obj\\fashion\\hat\r\n\r\n"
                    + "[24]\r\nMaleModel=obj\\fashion\\shoes\r\n\r\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return EquipmentResolver.load(tempDir);
    }

    private static RoleInventoryStore.Equip equip(String equipType, int slot) {
        return new RoleInventoryStore.Equip("cfg_" + equipType, 0, equipType, slot,
                100, 100, 0, "");
    }

    private static RoleInventoryStore.Equip equipAt(String configId, String equipType, int slot) {
        return new RoleInventoryStore.Equip(configId, 0, equipType, slot, 100, 100, 0, "");
    }

    @Test
    void slotMappingMatchesWornSlots() {
        assertEquals(AppearanceSync.SLOT_HAT, AppearanceSync.slotOfEquipType("Hat"));
        assertEquals(AppearanceSync.SLOT_CLOTH, AppearanceSync.slotOfEquipType("Cloth"));
        assertEquals(AppearanceSync.SLOT_PANTS, AppearanceSync.slotOfEquipType("Pants"));
        assertEquals(AppearanceSync.SLOT_WRIST, AppearanceSync.slotOfEquipType("Wrist"));
        assertEquals(AppearanceSync.SLOT_LEG, AppearanceSync.slotOfEquipType("Leg"));
        assertEquals(AppearanceSync.SLOT_SHOES, AppearanceSync.slotOfEquipType("Shoes"));
        assertEquals(AppearanceSync.SLOT_SUIT, AppearanceSync.slotOfEquipType("Suit"));
        assertEquals(AppearanceSync.SLOT_FASHION_COAT, AppearanceSync.slotOfEquipType("FashionCoat"));
        assertEquals(AppearanceSync.SLOT_FASHION_SHOES, AppearanceSync.slotOfEquipType("FashionShoes"));
        assertEquals(AppearanceSync.SLOT_FASHION_HAT, AppearanceSync.slotOfEquipType("FashionHat"));
        assertEquals(AppearanceSync.SLOT_WEAPON, AppearanceSync.slotOfEquipType("Weapon"));
        assertEquals(AppearanceSync.SLOT_WEAPON, AppearanceSync.slotOfEquipType("ShotWeapon"));
        assertEquals(AppearanceSync.SLOT_WEAPON, AppearanceSync.slotOfEquipType("InnerWeapon"));
        // 饰品/道具/空串不可穿戴（v1 只入背包）
        assertEquals(0, AppearanceSync.slotOfEquipType("Necklace"));
        assertEquals(0, AppearanceSync.slotOfEquipType("Earring"));
        assertEquals(0, AppearanceSync.slotOfEquipType(""));
        assertEquals(0, AppearanceSync.slotOfEquipType(null));
    }

    @Test
    void fashionSlotsMirrorToNewPanel() {
        assertEquals(40, AppearanceSync.mirrorSlot(AppearanceSync.SLOT_SUIT));
        assertEquals(41, AppearanceSync.mirrorSlot(AppearanceSync.SLOT_FASHION_COAT));
        assertEquals(42, AppearanceSync.mirrorSlot(AppearanceSync.SLOT_FASHION_HAT));
        assertEquals(43, AppearanceSync.mirrorSlot(AppearanceSync.SLOT_FASHION_SHOES));
        // 非时装槽不镜像
        assertEquals(AppearanceSync.SLOT_HAT, AppearanceSync.mirrorSlot(AppearanceSync.SLOT_HAT));
        assertEquals(AppearanceSync.SLOT_WEAPON, AppearanceSync.mirrorSlot(AppearanceSync.SLOT_WEAPON));
    }

    @Test
    void mergeVisualOverridesWornPartsOnly() {
        RoleVisual base = new RoleVisual(0, "photo", "face", "hair_base",
                "cloth_base", "pants_base", "shoes_base", "0h");
        RoleInventoryStore.Equip wornCloth = new RoleInventoryStore.Equip(
                "cfg_cloth_worn", 0, "Cloth", AppearanceSync.SLOT_CLOTH, 100, 100, 9, "");
        RoleVisual merged = AppearanceSync.mergeVisual(base, 0,
                List.of(wornCloth, equip("Weapon", AppearanceSync.SLOT_WEAPON)),
                resolver());
        assertEquals("obj\\worn\\cloth", merged.cloth());
        assertEquals("pants_base", merged.pants()); // 无装备部位保持初始装
        assertEquals("hair_base", merged.hair());
        assertEquals("face", merged.face());
        assertEquals("0h", merged.actionSet());
    }

    @Test
    void wornModelsIgnoreWeaponAndUnknownSlots() {
        Map<Integer, String> models = AppearanceSync.wornModels(0,
                List.of(equip("Weapon", AppearanceSync.SLOT_WEAPON),
                        equip("Necklace", 12)),
                resolver());
        assertTrue(models.isEmpty()); // 武器走 WeaponMode，饰品不覆盖模型
    }

    @Test
    void appearanceFramePushesClothAndWeaponMode() {
        RoleInventoryStore.Equip wornCloth = new RoleInventoryStore.Equip(
                "cfg_cloth_worn", 0, "Cloth", AppearanceSync.SLOT_CLOTH, 100, 100, 9, "");
        RoleInventoryStore.Equip weapon = equip("Weapon", AppearanceSync.SLOT_WEAPON);
        byte[] frame = AppearanceSync.appearanceFrame(1, 1, AppearanceSync.SHOW_EQUIP_GEAR, 0,
                List.of(wornCloth, weapon), resolver());
        assertEquals(0x10, frame[0] & 0xFF);
        // WeaponMode 非空时随帧下发（weaponModeOf 无 ArtPack 表回退原 configId）
        String text = new String(frame, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("obj\\worn\\cloth"));
        assertTrue(text.contains("cfg_Weapon"));
    }

    @Test
    void finalModelsGearModeKeepsEquipment() {
        Map<String, String> finals = AppearanceSync.finalModels(AppearanceSync.SHOW_EQUIP_GEAR, 0,
                List.of(equipAt("cfg_cloth_worn", "Cloth", AppearanceSync.SLOT_CLOTH),
                        equipAt("cfg_pants_gear", "Pants", AppearanceSync.SLOT_PANTS),
                        equipAt("cfg_identity", "Suit", AppearanceSync.SLOT_SUIT),
                        equipAt("cfg_fashion_coat", "FashionCoat", AppearanceSync.SLOT_FASHION_COAT)),
                resolver());
        assertEquals("obj\\worn\\cloth", finals.get(AppearanceSync.PART_CLOTH));
        assertEquals("obj\\gear\\pants", finals.get(AppearanceSync.PART_PANTS));
        assertFalse(finals.containsKey(AppearanceSync.PART_HAT), "江湖装模式不吃身份装/时装模型");
    }

    @Test
    void finalModelsIdentitySuitOverridesWholeSet() {
        Map<String, String> finals = AppearanceSync.finalModels(AppearanceSync.SHOW_EQUIP_IDENTITY, 0,
                List.of(equipAt("cfg_cloth_worn", "Cloth", AppearanceSync.SLOT_CLOTH),
                        equipAt("cfg_pants_gear", "Pants", AppearanceSync.SLOT_PANTS),
                        equipAt("cfg_identity", "Suit", AppearanceSync.SLOT_SUIT)),
                resolver());
        // 整套：主 ArtPack=衣，Hat/Shoes 分件包生效；PlantsArtPack=-1 裤回落装备。
        assertEquals("obj\\identity\\suit", finals.get(AppearanceSync.PART_CLOTH));
        assertEquals("obj\\identity\\hat", finals.get(AppearanceSync.PART_HAT));
        assertEquals("obj\\identity\\shoes", finals.get(AppearanceSync.PART_SHOES));
        assertEquals("obj\\gear\\pants", finals.get(AppearanceSync.PART_PANTS));
    }

    @Test
    void finalModelsIdentityWithoutSuitFallsBackToGear() {
        Map<String, String> finals = AppearanceSync.finalModels(AppearanceSync.SHOW_EQUIP_IDENTITY, 0,
                List.of(equipAt("cfg_cloth_worn", "Cloth", AppearanceSync.SLOT_CLOTH)),
                resolver());
        assertEquals("obj\\worn\\cloth", finals.get(AppearanceSync.PART_CLOTH), "无身份装回落装备模型");
    }

    @Test
    void finalModelsFashionPiecesOverrideSuitPacks() {
        Map<String, String> finals = AppearanceSync.finalModels(AppearanceSync.SHOW_EQUIP_FASHION, 0,
                List.of(equipAt("cfg_cloth_worn", "Cloth", AppearanceSync.SLOT_CLOTH),
                        equipAt("cfg_pants_gear", "Pants", AppearanceSync.SLOT_PANTS),
                        equipAt("cfg_fashion_coat", "FashionCoat", AppearanceSync.SLOT_FASHION_COAT),
                        equipAt("cfg_fashion_hat", "FashionHat", AppearanceSync.SLOT_FASHION_HAT),
                        equipAt("cfg_fashion_shoes", "FashionShoes", AppearanceSync.SLOT_FASHION_SHOES)),
                resolver());
        // 套装式时装衣自带帽包，单件帽/鞋槽再覆盖；时装无裤件回落装备。
        assertEquals("obj\\fashion\\coat", finals.get(AppearanceSync.PART_CLOTH));
        assertEquals("obj\\fashion\\hat", finals.get(AppearanceSync.PART_HAT));
        assertEquals("obj\\fashion\\shoes", finals.get(AppearanceSync.PART_SHOES));
        assertEquals("obj\\gear\\pants", finals.get(AppearanceSync.PART_PANTS));
    }

    @Test
    void finalModelsFashionCoatPackAlone() {
        Map<String, String> finals = AppearanceSync.finalModels(AppearanceSync.SHOW_EQUIP_FASHION, 0,
                List.of(equipAt("cfg_fashion_coat", "FashionCoat", AppearanceSync.SLOT_FASHION_COAT)),
                resolver());
        assertEquals("obj\\fashion\\coat", finals.get(AppearanceSync.PART_CLOTH));
        assertEquals("obj\\fashion\\packhat", finals.get(AppearanceSync.PART_HAT), "套装式时装自带帽包");
        assertFalse(finals.containsKey(AppearanceSync.PART_SHOES), "ShoesArtPack=-1 无鞋件");
    }
}
