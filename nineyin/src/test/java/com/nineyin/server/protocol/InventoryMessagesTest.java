package com.nineyin.server.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.WireType;
import org.junit.jupiter.api.Test;

import com.nineyin.server.world.db.RoleInventoryStore;

/**
 * InventoryMessages 行为回归（bak InventoryPacketsTest 语义）：
 * 背包成对 remove/add 槽位存活（计数器误删防回归）、视图路由、锚点
 * BeginPos/EndPos 累计数学、UniqueID 稳定性。
 */
class InventoryMessagesTest {

    private static RoleInventoryStore.BagItem bagItem(int slot, String configId, int viewId) {
        return new RoleInventoryStore.BagItem(slot, configId, 106, 1, viewId,
                "测试物品", "Weapon", 6, null, null, null, null, 100, 100);
    }

    /** 视图模型重放：0x19 删行、0x18 加行，末态槽位集合。 */
    private static Map<Integer, Map<Integer, Boolean>> replay(List<byte[]> frames) {
        Map<Integer, Map<Integer, Boolean>> views = new HashMap<>();
        for (byte[] frame : frames) {
            switch (frame[0] & 0xFF) {
                case 0x15 -> views.putIfAbsent(u16(frame, 1), new HashMap<>());
                case 0x19 -> views.computeIfAbsent(u16(frame, 1), k -> new HashMap<>())
                        .remove(u16(frame, 3));
                case 0x18 -> views.computeIfAbsent(u16(frame, 1), k -> new HashMap<>())
                        .put(u16(frame, 3), true);
                default -> {
                }
            }
        }
        return views;
    }

    private static int u16(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
    }

    @Test
    void loginBagRowsSurviveRemoveAddPairing() {
        // 四件物品：槽 2/3（基础页）与 19/20（扩容页），每件 0x19+0x18+0x19(镜像) 三帧。
        List<RoleInventoryStore.BagItem> items = List.of(
                bagItem(2, "item_a", 2), bagItem(3, "item_b", 2),
                bagItem(19, "item_c", 2), bagItem(20, "item_d", 2));
        List<byte[]> frames = InventoryMessages.inventoryFrames(618L, 0, List.of(), items, null);
        Map<Integer, Map<Integer, Boolean>> views = replay(frames);
        Map<Integer, Boolean> equipBag = views.get(InventoryMessages.VIEW_EQUIP_BAG);
        assertEquals(4, equipBag.size(), "四件物品全部存活");
        for (int slot : new int[]{2, 3, 19, 20}) {
            assertTrue(equipBag.containsKey(slot), "槽 " + slot + " 存活");
        }
        // 镜像页被成对清位：不残留假行。
        Map<Integer, Boolean> mirror = views.get(176);
        assertEquals(0, mirror.size(), "镜像页无残留");
        // 槽 91+ 溢出裁剪。
        List<RoleInventoryStore.BagItem> overflow =
                List.of(bagItem(91, "overflow", 2));
        Map<Integer, Map<Integer, Boolean>> clipped =
                replay(InventoryMessages.inventoryFrames(618L, 0, List.of(), overflow, null));
        assertTrue(clipped.getOrDefault(InventoryMessages.VIEW_EQUIP_BAG, new HashMap<>()).isEmpty());
    }

    @Test
    void bagViewRoutesByViewId() {
        // game_items.view_id 分类（bak 同）：1道具→2 2装备→121 3材料→123 4任务→125。
        assertEquals(2, InventoryMessages.bagViewOf(1));
        assertEquals(121, InventoryMessages.bagViewOf(2));
        assertEquals(123, InventoryMessages.bagViewOf(3));
        assertEquals(125, InventoryMessages.bagViewOf(4));
        assertEquals(2, InventoryMessages.bagViewOf(99));
    }

    @Test
    void bagViewCreatesSeventeenContainersWithBaseCap() {
        List<byte[]> frames = InventoryMessages.bagViewCreateFrames();
        assertEquals(17, frames.size());
        assertEquals(90, u16(frames.get(0), 3), "主页 cap=90");
        assertEquals(1, u16(frames.get(0), 5), "BaseCap 属性存在");
        // View1 纸娃娃收尾。
        byte[] doll = frames.get(16);
        assertEquals(1, u16(doll, 1));
        assertEquals(48, u16(doll, 3));
    }

    @Test
    void anchorPositionsAccumulatePerBoost() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        List<RoleInventoryStore.Bagpack> packs = List.of(
                new RoleInventoryStore.Bagpack(1, 1, "ITEMBAG_006", 36,
                        Instant.parse("9999-12-31T00:00:00Z")),
                new RoleInventoryStore.Bagpack(1, 2, "ITEMBAG_006", 36,
                        Instant.parse("9999-12-31T00:00:00Z")),
                new RoleInventoryStore.Bagpack(2, 1, "ITEMBAG_006", 18,
                        Instant.parse("9999-12-31T00:00:00Z")));
        List<byte[]> frames = InventoryMessages.itemBagFrames(618L, packs, now);
        assertEquals(3, frames.size());
        // 页1 锚1：19-54；锚2：55-90；页2 锚1：19-36。
        assertEquals(19, viewProp(frames.get(0), "BeginPos"));
        assertEquals(54, viewProp(frames.get(0), "EndPos"));
        assertEquals(55, viewProp(frames.get(1), "BeginPos"));
        assertEquals(90, viewProp(frames.get(1), "EndPos"));
        assertEquals(122, u16(frames.get(2), 1), "页2 锚点落扩展页 122");
        assertEquals(720, viewProp(frames.get(0), "Hardiness"), "永久锚点=720 小时");
        // 过期锚点不下发。
        List<RoleInventoryStore.Bagpack> expired = List.of(
                new RoleInventoryStore.Bagpack(1, 1, "ITEMBAG_006", 36, now.minusSeconds(60)));
        assertEquals(0, InventoryMessages.itemBagFrames(618L, expired, now).size());
    }

    /** 视图帧按名取数值属性（u8/u16/i32）。 */
    private static int viewProp(byte[] frame, String name) {
        int propCount = u16(frame, 5);
        int offset = 7;
        for (int i = 0; i < propCount; i++) {
            int index = u16(frame, offset);
            offset += 2;
            String current = nameOf(index);
            WireType type = current.isEmpty() ? null : PropertyTable.wireType(current);
            if (name.equals(current)) {
                return switch (type) {
                    case BYTE -> frame[offset] & 0xFF;
                    case WORD -> u16(frame, offset);
                    default -> (int) ((frame[offset] & 0xFFL) | (frame[offset + 1] & 0xFFL) << 8
                            | (frame[offset + 2] & 0xFFL) << 16 | (frame[offset + 3] & 0xFFL) << 24);
                };
            }
            offset = skipValue(frame, offset, current);
        }
        throw new IllegalStateException("prop " + name + " not found");
    }

    @Test
    void uniqueIdIsStablePerRoleAndSlot() {
        String first = InventoryMessages.uniqueId(618L, "bag", 3);
        String second = InventoryMessages.uniqueId(618L, "bag", 3);
        assertEquals(first, second, "同角色同槽稳定");
        assertEquals(16, first.length());
        assertTrue(!first.equals(InventoryMessages.uniqueId(619L, "bag", 3)), "跨角色不同");
        assertTrue(!first.equals(InventoryMessages.uniqueId(618L, "equip", 3)), "跨域不同");
    }

    @Test
    void equipmentRowCarriesFullPropertySet() {
        RoleInventoryStore.Equip equip = new RoleInventoryStore.Equip(
                "pants_daxia_30301", 142, "Pants", 4, 100, 100, 1704);
        byte[] frame = InventoryMessages.equipmentViewAdd(618L, equip, null);
        assertEquals(0x18, frame[0] & 0xFF);
        assertEquals(1, u16(frame, 1), "View1");
        assertEquals(4, u16(frame, 3), "index=身体槽位 4");
        int propCount = u16(frame, 5);
        // 通用 6 + EquipType/BuildCount×2/RecoverFlag/ColorLevel/Hardiness×2/Level ≥ 14。
        assertTrue(propCount >= 14, "装备行属性数=" + propCount);
        List<Integer> indexes = new ArrayList<>();
        int offset = 7;
        for (int i = 0; i < propCount; i++) {
            indexes.add(u16(frame, offset));
            offset += 2;
            int index = indexes.get(i);
            String name = nameOf(index);
            offset = skipValue(frame, offset, name);
        }
        assertTrue(indexes.contains(PropertyTable.idx("EquipType")), "EquipType 必在（空值不渲染）");
        assertTrue(indexes.contains(PropertyTable.idx("UniqueID")));
    }

    private static String nameOf(int index) {
        PropertyTable.Entry entry = PropertyTable.byIndex(index);
        return entry == null ? "" : entry.name();
    }

    private static int skipValue(byte[] frame, int offset, String name) {
        WireType type = name.isEmpty() ? WireType.INT32 : PropertyTable.wireType(name);
        return switch (type) {
            case BYTE -> offset + 1;
            case WORD -> offset + 2;
            case INT32, FLOAT32 -> offset + 4;
            case STRING, WIDE_STRING -> offset + 4
                    + (int) ((frame[offset] & 0xFFL) | (frame[offset + 1] & 0xFFL) << 8
                    | (frame[offset + 2] & 0xFFL) << 16 | (frame[offset + 3] & 0xFFL) << 24);
            default -> offset + 8;
        };
    }
}
