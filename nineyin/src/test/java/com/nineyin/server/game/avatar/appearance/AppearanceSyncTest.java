package com.nineyin.server.game.avatar.appearance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import com.nineyin.server.protocol.PropertyValues;
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
        // 饰品可穿戴（官方 equip.json 存档实测 11-15），大小写不敏感
        assertEquals(AppearanceSync.SLOT_NECKLACE, AppearanceSync.slotOfEquipType("Necklace"));
        assertEquals(AppearanceSync.SLOT_EARRING_L, AppearanceSync.slotOfEquipType("Earring"));
        assertEquals(AppearanceSync.SLOT_FINGERRING_L, AppearanceSync.slotOfEquipType("Fingerring"));
        assertEquals(AppearanceSync.SLOT_EARRING_L, AppearanceSync.slotOfEquipType("earring"));
        // 道具/空串不可穿戴
        assertEquals(0, AppearanceSync.slotOfEquipType(""));
        assertEquals(0, AppearanceSync.slotOfEquipType(null));
        assertEquals(0, AppearanceSync.slotOfEquipType("FacultyBook"));
    }

    /** 双槽类型（耳环 11,12/戒指 14,15）候选与自动选槽：优先空槽，全占用替换首槽。 */
    @Test
    void dualSlotTypesPreferFirstFreeSlot() {
        assertArrayEquals(new int[]{11, 12}, AppearanceSync.slotsForEquipType("Earring"));
        assertArrayEquals(new int[]{14, 15}, AppearanceSync.slotsForEquipType("Fingerring"));
        assertArrayEquals(new int[]{22}, AppearanceSync.slotsForEquipType("Weapon"));
        assertEquals(0, AppearanceSync.slotsForEquipType("Treasure").length);
        // 空槽优先
        assertEquals(11, AppearanceSync.equipSlotFor("Earring", List.of()));
        assertEquals(12, AppearanceSync.equipSlotFor("Earring",
                List.of(equip("Earring", AppearanceSync.SLOT_EARRING_L))));
        // 全占用替换首槽（bak equipSlotFor 同构）
        assertEquals(11, AppearanceSync.equipSlotFor("Earring", List.of(
                equip("Earring", AppearanceSync.SLOT_EARRING_L),
                equip("Earring2", AppearanceSync.SLOT_EARRING_R))));
        assertEquals(0, AppearanceSync.equipSlotFor("Mask", List.of()));
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
        assertTrue(models.isEmpty()); // 武器走 Weapon/ActionSet 属性，饰品不覆盖模型
    }

    @Test
    void appearanceFramePushesClothAndWeaponProps() {
        RoleInventoryStore.Equip wornCloth = new RoleInventoryStore.Equip(
                "cfg_cloth_worn", 0, "Cloth", AppearanceSync.SLOT_CLOTH, 100, 100, 9, "");
        RoleInventoryStore.Equip weapon = new RoleInventoryStore.Equip(
                "cfg_weapon", 102, "Weapon", AppearanceSync.SLOT_WEAPON, 100, 100, 41, "");
        byte[] frame = AppearanceSync.appearanceFrame(1, 1, AppearanceSync.SHOW_EQUIP_GEAR, 0,
                List.of(wornCloth, weapon), weaponResolver());
        assertEquals(0x10, frame[0] & 0xFF);
        String text = new String(frame, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("obj\\worn\\cloth"));
        // 武器三属性：Weapon=裸模型名（引擎挂 obj\weapon\<名>.xmod）+ ActionSet=表值 2h
        assertTrue(text.contains("sword_art_001"));
        assertTrue(text.contains("2h"));
        // WeaponMode@1599（NPC 域语义）不再下发
        assertFalse(text.contains("WeaponMode"));
    }

    @Test
    void appearanceFrameClearsWeaponWithEmptyStrings() {
        // 官服收武器 0x10 实证：Weapon=""/ActionSet="" 显式清除（衣裤鞋空值仍跳过）
        RoleInventoryStore.Equip wornCloth = new RoleInventoryStore.Equip(
                "cfg_cloth_worn", 0, "Cloth", AppearanceSync.SLOT_CLOTH, 100, 100, 9, "");
        byte[] frame = AppearanceSync.appearanceFrame(1, 1, AppearanceSync.SHOW_EQUIP_GEAR, 0,
                List.of(wornCloth), weaponResolver());
        assertEquals(0x10, frame[0] & 0xFF);
        List<PropertyValues.Indexed> rows = indexedRows(frame);
        assertEquals(3, rows.stream().filter(r -> r.int16Index() == 396
                || r.int16Index() == 398 || r.int16Index() == 406).count(),
                "396 Weapon / 398 ShotWeapon / 406 ActionSet 三行都在（值空串）");
    }

    /** 0x10 帧粗解（只认 Cloth/Weapon/ShotWeapon/ActionSet/ShowEquipType 行）。 */
    private static List<PropertyValues.Indexed> indexedRows(byte[] frame) {
        List<PropertyValues.Indexed> rows = new java.util.ArrayList<>();
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(frame)) {
            in.readNBytes(12); // 0x10 头：op+isView+objId+ownerId+count
            while (in.available() > 0) {
                int b1 = in.read();
                int b2 = in.read();
                if (b1 < 0 || b2 < 0) {
                    break;
                }
                int index = b1 | (b2 << 8);
                if (index == 408) { // ShowEquipType type1
                    in.readNBytes(1);
                    continue;
                }
                if (index == 393 || index == 396 || index == 398 || index == 406) { // type7 串行
                    byte[] len = in.readNBytes(4);
                    int n = (len[0] & 0xFF) | ((len[1] & 0xFF) << 8)
                            | ((len[2] & 0xFF) << 16) | ((len[3] & 0xFF) << 24);
                    String value = new String(in.readNBytes(n),
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                    rows.add(PropertyValues.Indexed.of(index, "",
                            PropertyValues.Value.ofString(value)));
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /** 武器桩表：江湖装衣（9）+ ArtPack 41 → 模型 sword_art_001 / ActionSet 2h（ItemType 102 剑）。 */
    private EquipmentResolver weaponResolver() {
        try {
            java.nio.file.Files.writeString(tempDir.resolve("equipment.ini"),
                    "[cfg_cloth_worn]\r\nArtPack=9\r\n\r\n[cfg_weapon]\r\nArtPack=41\r\n\r\n");
            java.nio.file.Files.writeString(tempDir.resolve("itemartstatic.ini"),
                    "[9]\r\nMaleModel=obj\\worn\\cloth\r\n\r\n"
                    + "[41]\r\nMaleModel=sword_art_001\r\nActionSet=2h\r\n\r\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return EquipmentResolver.load(tempDir);
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
