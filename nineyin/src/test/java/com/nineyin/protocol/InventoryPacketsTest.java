package com.nineyin.protocol;

import com.nineyin.db.RoleInventoryStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 库存 view 帧（对齐 F 盘参考 exe 抓包的布局）。 */
class InventoryPacketsTest {

    @Test
    void bagViewCreates() {
        List<byte[]> frames = InventoryPackets.bagViewCreateFrames();
        assertEquals(17, frames.size());
        // 首个主页：cap 90 + BaseCap 属性
        byte[] v121 = frames.get(0);
        assertEquals(0x15, v121[0]);
        assertEquals(121, leU16(v121, 1));
        assertEquals(90, leU16(v121, 3));
        assertEquals(1, leU16(v121, 5));                 // 属性数
        assertEquals(2213, leU16(v121, 7));              // BaseCap
        assertEquals(18, leU32(v121, 9));
        // 装备纸娃娃 View 1：cap 48 无属性
        byte[] doll = frames.get(16);
        assertEquals(1, leU16(doll, 1));
        assertEquals(48, leU16(doll, 3));
        assertEquals(0, leU16(doll, 5));

        List<byte[]> bags = InventoryPackets.itemBagFrames();
        assertEquals(4, bags.size());
        byte[] ib = bags.get(0);
        assertEquals(0x18, ib[0]);
        assertEquals(3, leU16(ib, 1));                    // 扩展页 view 3
        assertEquals(4, leU16(ib, 5));
        assertTrue(new String(ib, 13, 11, StandardCharsets.US_ASCII).startsWith("ITEMBAG"));
    }

    @Test
    void viewRemoveLayout() {
        byte[] v = InventoryPackets.viewRemove(2, 3);
        assertEquals(0x19, v[0]);
        assertEquals(5, v.length);
        assertEquals(2, leU16(v, 1));
        assertEquals(3, leU16(v, 3));
    }

    @Test
    void equipmentViewAddLayout() {
        RoleInventoryStore.Equipped hat = new RoleInventoryStore.Equipped(
                1, "helmet_sell_10101", 140, "Hat", 100, 100, "");
        byte[] msg = InventoryPackets.equipmentViewAdd(hat, "147dd5f2efa2e6bf");
        assertEquals(0x18, msg[0]);
        assertEquals(1, leU16(msg, 1));     // View 1 纸娃娃
        assertEquals(1, leU16(msg, 3));     // 对象索引 = 身体槽位
        int count = leU16(msg, 5);
        // 测试环境无 resources 时 Photo/EffectPackID 缺省省略（14），有资源时 16
        assertTrue(count >= 14 && count <= 16, "count=" + count);
        assertTrue(containsProp(msg, 1439));
        assertTrue(containsProp(msg, 1887));
        assertTrue(containsProp(msg, 1891));
        assertEquals(140, propValue(msg, 1887));
        assertEquals(100, propValue(msg, 1933));   // Hardiness
        assertEquals(100, propValue(msg, 1934));   // MaxHardiness
    }

    @Test
    void bagViewRoutesAndFrames() {
        assertEquals(2, InventoryPackets.bagViewOf(1));
        assertEquals(121, InventoryPackets.bagViewOf(2));
        assertEquals(123, InventoryPackets.bagViewOf(3));
        assertEquals(125, InventoryPackets.bagViewOf(4));

        List<byte[]> frames = InventoryPackets.inventoryFrames(2,
                List.of(new RoleInventoryStore.Equipped(1, "helmet_sell_10101", 140, "Hat", 100, 100, "")),
                List.of(new RoleInventoryStore.Bagged(8, "yaopin_00200", 1, 10, 1, "止血草")),
                (byte) 0);
        // 1 装备 + 道具页成对版本帧（0x19 view2 + add + 0x19 view174）+ 武器模型帧
        assertEquals(5, frames.size());
        assertEquals(0x18, frames.get(0)[0]);
        assertEquals(1, leU16(frames.get(0), 1));       // View 1
        assertEquals(0x19, frames.get(1)[0]);
        assertEquals(2, leU16(frames.get(1), 1));
        assertEquals(0x18, frames.get(2)[0]);
        assertEquals(2, leU16(frames.get(2), 1));       // 道具页
        assertEquals(8, leU16(frames.get(2), 3));       // 背包格位
        assertEquals(6, leU16(frames.get(2), 5));       // 道具 6 属性
        assertEquals(0x19, frames.get(3)[0]);
        assertEquals(174, leU16(frames.get(3), 1));     // 镜像页
        // 末帧：无武器时 WeaponMode(1597)="" 的 0x10 属性推送
        byte[] weapon = frames.get(4);
        assertEquals(0x10, weapon[0]);
        assertEquals(1597, leU16(weapon, 12));
    }

    @Test
    void weaponModeFramePushesEquippedWeaponModel() {
        // 无武器：空串属性
        byte[] empty = InventoryPackets.weaponModeFrame(List.of(), (byte) 0);
        assertEquals(0x10, empty[0]);
        assertEquals(1597, leU16(empty, 12));

        // 穿武器（EquipType=Weapon）：有 resources 时解析出模型路径，缺失时回退 config 原值
        List<RoleInventoryStore.Equipped> worn = List.of(
                new RoleInventoryStore.Equipped(3, "helmet_sell_10101", 140, "Hat", 100, 100, ""),
                new RoleInventoryStore.Equipped(1, "sword_60701", 130, "Weapon", 100, 100, ""));
        byte[] frame = InventoryPackets.weaponModeFrame(worn, (byte) 0);
        assertEquals(0x10, frame[0]);
        assertEquals(1597, leU16(frame, 12));
        String frameText = new String(frame, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(frameText.contains("sword_60701") || frameText.contains("sword_"),
                "武器模型应为解析路径或回退 config 原值");

        // 武器判定：Weapon/ShotWeapon/InnerWeapon 是武器，Hat 不是
        assertTrue(InventoryPackets.isWeaponEquipType("Weapon"));
        assertTrue(InventoryPackets.isWeaponEquipType("ShotWeapon"));
        assertTrue(InventoryPackets.isWeaponEquipType("InnerWeapon"));
        assertFalse(InventoryPackets.isWeaponEquipType("Hat"));
        assertFalse(InventoryPackets.isWeaponEquipType(null));
    }

    @Test
    void roleSkillFramesReferenceLayout() {
        List<byte[]> skills = InventoryPackets.roleSkillFrames(List.of(
                new RoleInventoryStore.SkillRow("CS_yhwq_hsqs01", 1, 11, 0, 156550, 12636, 1000, 0)));
        assertEquals(2, skills.size());
        assertEquals(0x15, skills.get(0)[0]);
        assertEquals(1, leU16(skills.get(0), 3));       // capacity = 1
        byte[] add = skills.get(1);
        assertEquals(40, leU16(add, 1));
        assertEquals(10, leU16(add, 5));                // 参考抓包的 10 属性布局
        assertTrue(containsProp(add, 2235));            // StaticData
        assertEquals(12636, propValue(add, 2235));
        assertEquals(156550, propValue(add, 764));      // TotalFillValue
        assertEquals(11, propValue(add, 2081));         // MaxLevel
    }

    /** choose 阶段目录行（§3.16）：Level/MaxLevel=12 展示值，TotalFill 取文件 total。 */
    @Test
    void skillCatalogRowReferenceLayout() {
        byte[] row = InventoryPackets.skillCatalogRow(1, "BSZ_skill_bwy_gdj", 14513, 1000, 156550);
        assertEquals(0x18, row[0]);
        assertEquals(40, leU16(row, 1));
        assertEquals(1, leU16(row, 3));
        assertEquals(10, leU16(row, 5));
        assertEquals(12, propValue(row, 1464));      // Level=12（参考实测展示值）
        assertEquals(12, propValue(row, 2081));      // MaxLevel=12
        assertEquals(0, propValue(row, 763));        // CurFill=0
        assertEquals(156550, propValue(row, 764));   // TotalFill=文件 total
        assertEquals(14513, propValue(row, 2235));
        assertEquals(17905, InventoryPackets.SKILL_CATALOG_CAPACITY);
        // itemType=0 → 1000 兜底；负 total 夹 0
        byte[] row2 = InventoryPackets.skillCatalogRow(2, "x", 5, 0, -3);
        assertEquals(1000, propValue(row2, 1887));
        assertEquals(0, propValue(row2, 764));
    }

    /** 装备背包对象携带 equipment.ini 的 EquipType（参考帧为 "Weapon" 等，空串装备页不渲染）。 */
    @Test
    void bagEquipCarriesIniEquipType() {
        EquipmentModels.ItemStatic st = EquipmentModels.itemStatic("sthorn_60701");
        assumeTrue(st != null, "无 resources（设 NINEYIN_SERVER_ROOT 指向部署目录可跑）");
        assertEquals("Weapon", st.equipType());
        List<byte[]> frames = InventoryPackets.inventoryFrames(2, List.of(),
                List.of(new RoleInventoryStore.Bagged(1, "sthorn_60701", 106, 1, 2, null)),
                (byte) 0);
        byte[] add = null;
        for (byte[] f : frames) {
            if (f[0] == 0x18 && leU16(f, 1) == 121) {
                add = f;
                break;
            }
        }
        assertTrue(add != null, "view=121 装备对象应存在");
        assertTrue(new String(add, StandardCharsets.US_ASCII).contains("Weapon"),
                "装备背包对象应携带真实 EquipType");
    }

    /**
     * 登录整推回归（2026-10-30 wire 重放实锤）：remove 必须与 add 同槽位配对。
     * 旧实现 remove 用顺序计数器 n，会把前一件刚 add 的行删掉——105 行装备
     * 登录后只剩 12 行、格子错乱（背包装备不显示）。
     */
    @Test
    void loginBagRowsSurviveRemoveAddPairing() {
        List<RoleInventoryStore.Bagged> bagged = List.of(
                new RoleInventoryStore.Bagged(2, "sthorn_leader_em_001", 106, 1, 2, ""),
                new RoleInventoryStore.Bagged(3, "lstuff_leader_sl_001", 107, 1, 2, ""),
                new RoleInventoryStore.Bagged(19, "sthorn_60701", 106, 1, 2, ""),
                new RoleInventoryStore.Bagged(20, "sthorn_60701", 106, 1, 2, ""),
                new RoleInventoryStore.Bagged(91, "sthorn_overflow", 106, 1, 2, ""));  // 超 cap 90
        List<byte[]> frames = InventoryPackets.inventoryFrames(2, List.of(), bagged, (byte) 0);

        // 重放模拟客户端 view 121：0x19 删行 / 0x18 加行
        java.util.Set<Integer> rows = new java.util.HashSet<>();
        boolean overflowSkipped = true;
        for (byte[] f : frames) {
            if (leU16(f, 1) != 121) {
                continue;
            }
            if (f[0] == 0x19) {
                rows.remove(leU16(f, 3));
            } else if (f[0] == 0x18) {
                rows.add(leU16(f, 3));
            }
        }
        assertEquals(java.util.Set.of(2, 3, 19, 20), rows, "配对 remove+add 后所有行必须存活");
        for (byte[] f : frames) {
            if (f[0] == 0x18 && leU16(f, 1) == 121 && leU16(f, 3) == 91) {
                overflowSkipped = false;
            }
        }
        assertTrue(overflowSkipped, "超 cap 90 的历史行不应推送");
    }

    // ---- 解析辅助：按 u16 索引在属性区线性找值（u8/i32 通吃小值） ----

    private static boolean containsProp(byte[] msg, int want) {
        return propOffset(msg, want) >= 0;
    }

    private static int propValue(byte[] msg, int want) {
        int off = propOffset(msg, want);
        int idx = leU16(msg, off);
        int after = off + 2;
        // 官方表：Level(1464)/RecoverFlag(1969) 为 u8，其余 i32/f32
        return idx == 1464 || idx == 1969 ? msg[after] : leU32(msg, after);
    }

    private static int propOffset(byte[] msg, int want) {
        int p = 7;
        while (p + 2 <= msg.length) {
            int idx = leU16(msg, p);
            if (idx == want) {
                return p;
            }
            p += 2;
            if (idx == 1439 || idx == 1891 || idx == 184 || idx == 1936 || idx == 1952) {
                int len = leU32(msg, p);
                p += 4 + len;
            } else if (idx == 1464 || idx == 1969) {
                p += 1;
            } else {
                p += 4;
            }
        }
        return -1;
    }

    private static int leU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
