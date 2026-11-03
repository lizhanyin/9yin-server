package com.nineyin.server.game.avatar.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 已穿装备对 RoleVisual 的部位覆盖（EquipmentResolver 用临时 ini 桩表）。
 */
class AppearanceSyncTest {

    @TempDir
    Path tempDir;

    /** 两行桩表：cfg_cloth_worn→ArtPack 9→male 模型路径。 */
    private EquipmentResolver resolver() {
        try {
            java.nio.file.Files.writeString(tempDir.resolve("equipment.ini"), ""
                    + "[cfg_cloth_worn]\r\nArtPack=9\r\n\r\n");
            java.nio.file.Files.writeString(tempDir.resolve("itemartstatic.ini"), ""
                    + "[9]\r\nMaleModel=obj\\worn\\cloth\r\n\r\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return EquipmentResolver.load(tempDir);
    }

    private static RoleInventoryStore.Equip equip(String equipType, int slot) {
        return new RoleInventoryStore.Equip("cfg_" + equipType, 0, equipType, slot,
                100, 100, 0, "");
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
        assertEquals(AppearanceSync.SLOT_FASHION_HAT, AppearanceSync.slotOfEquipType("FashionHat"));
        assertEquals(AppearanceSync.SLOT_FASHION_SHOES, AppearanceSync.slotOfEquipType("FashionShoes"));
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
        byte[] frame = AppearanceSync.appearanceFrame(1, 1, 0,
                List.of(wornCloth, weapon), resolver());
        assertEquals(0x10, frame[0] & 0xFF);
        // WeaponMode 非空时随帧下发（weaponModeOf 无 ArtPack 表回退原 configId）
        String text = new String(frame, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("obj\\worn\\cloth"));
        assertTrue(text.contains("cfg_Weapon"));
    }
}
