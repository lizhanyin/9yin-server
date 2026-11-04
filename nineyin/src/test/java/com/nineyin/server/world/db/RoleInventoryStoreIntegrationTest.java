package com.nineyin.server.world.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;

import com.nineyin.server.world.db.RoleInventoryStore;
import org.junit.jupiter.api.Test;

import com.nineyin.util.MySqlEnv;

/** RoleInventoryStore 集成（无 MySQL 自跳过）：role 618 真实数据计数与富化。 */
class RoleInventoryStoreIntegrationTest {

    private static final long ROLE = 618L;

    private static String dsn() {
        return MySqlEnv.loadDsn().orElse("nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin");
    }

    private static boolean available() {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn());
            assertTrue(m.matches());
            try (Connection c = DriverManager.getConnection(
                    "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                            + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                    m.group(1), m.group(2))) {
                return c.isValid(2);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void role618InventoryCounts() {
        assumeTrue(available(), "MySQL 不可用，跳过物品域集成");
        try (RoleInventoryStore store = RoleInventoryStore.open(dsn())) {
            assumeTrue(store.hasRoleData(ROLE), "role 618 无数据");
            // 真机穿脱/GM 测试会改这些数字（2026-11-04 按当期 DB 对齐：武器卸回
            // 背包、背包三页 17+13+5、快捷栏 +1）。漂移时按当期 DB 核对后更新。
            assertEquals(7, store.equips(ROLE).size(), "穿戴装备 7 件");
            assertEquals(35, store.bagItems(ROLE).size(), "背包 35 件");
            assertEquals(190, store.skills(ROLE, "wuxue").size(), "武学 190");
            assertEquals(48, store.skills(ROLE, "neigong").size(), "内功 48");
            assertEquals(118, store.shortcuts(ROLE).size(), "快捷栏 118 行");
            assertEquals(4, store.bagpacks(ROLE).size(), "扩容锚点 4 个（每页首锚）");
            // game_items 富化：武器行拿到 ItemType/名称/EquipType。
            var weapon = store.bagItems(ROLE).stream()
                    .filter(item -> "Weapon".equals(item.equipType())).findFirst();
            assertTrue(weapon.isPresent(), "背包含 Weapon 行且 EquipType 富化成功");
        }
    }

    /** msg30 穿/脱四事务往返（专用临时角色 990031，测后清理，不碰真实角色数据）。 */
    @Test
    void moveMethodsRoundTripOnScratchRole() throws Exception {
        assumeTrue(available(), "MySQL 不可用，跳过物品域集成");
        long role = 990_031L;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn());
        assertTrue(m.matches());
        try (RoleInventoryStore store = RoleInventoryStore.open(dsn());
             Connection jdbc = DriverManager.getConnection(
                     "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                             + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                     m.group(1), m.group(2))) {
            String config = firstWeaponConfig(jdbc);
            assumeTrue(config != null, "game_items 无 Weapon 行");
            cleanScratch(jdbc, role);
            insertScratchRole(jdbc, role);
            try {
                insertBagRow(jdbc, role, 3, config, 2);
                // 穿上：背包(3, 分类2) → 槽 22（insertEquipRow 联 game_items 补 EquipType）
                assertTrue(store.moveBagToEquip(role, 3, 22, 2), "穿上落库");
                assertEquals(1, store.equips(role).size());
                assertEquals(22, store.equips(role).get(0).slot());
                assertEquals("Weapon", store.equips(role).get(0).equipType());
                // 换装让位：旧武器(槽22) → 背包格 1
                insertBagRow(jdbc, role, 4, config, 2);
                assertTrue(store.swapEquipSlot(role, 22, 1), "旧装备让位");
                assertTrue(store.moveBagToEquip(role, 4, 22, 2), "新武器上槽");
                assertEquals(1, store.equips(role).size(), "同槽仍只一件");
                assertTrue(store.bagItems(role).stream().anyMatch(
                        item -> item.slot() == 1 && item.viewId() == 2), "让位行落装备页格 1");
                // 卸下：槽 22 → 背包格 5
                assertTrue(store.moveEquipToBag(role, 22, 5), "卸下落库");
                assertTrue(store.equips(role).isEmpty(), "装备栏已清");
                assertTrue(store.bagItems(role).stream().anyMatch(item -> item.slot() == 5),
                        "卸下行落背包格 5");
                // 背包页内移动：格 5 → 格 2（同分类限定防误删）
                assertTrue(store.moveBagSlot(role, 5, 2, 2, 2), "页内移动");
                assertTrue(store.bagItems(role).stream().noneMatch(item -> item.slot() == 5),
                        "源格已清");
                // 源格无行/分类不符 → false（不误删他页同格行）
                assertFalse(store.moveBagToEquip(role, 5, 22, 2), "源格无行拒绝");
                assertFalse(store.moveBagSlot(role, 5, 1, 6, 1), "分类不符拒绝");
            } finally {
                cleanScratch(jdbc, role);
            }
        }
    }

    private static String firstWeaponConfig(Connection jdbc) throws Exception {
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT item_id FROM game_items WHERE equip_type = 'Weapon' LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void insertBagRow(Connection jdbc, long role, int slot,
                                     String config, int viewId) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                        + "view_id, name, script) VALUES (?, 1, ?, ?, 0, 1, ?, '', '')")) {
            ps.setLong(1, role);
            ps.setInt(2, slot);
            ps.setString(3, config);
            ps.setInt(4, viewId);
            ps.executeUpdate();
        }
    }

    /** 临时角色行（role_bag_items 有 roles 外键；借 role 618 的 account_id，slot=900
     * 避开 uq_roles_account_slot 唯一键——slot=0 会撞既有行被 ON DUPLICATE 吞成空更新）。 */
    private static void insertScratchRole(Connection jdbc, long role) throws Exception {
        Long accountId = null;
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT account_id FROM roles WHERE role_id = " + ROLE)) {
            accountId = rs.next() ? rs.getLong(1) : null;
        }
        assumeTrue(accountId != null, "role 618 缺席，无法借 account_id");
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO roles (role_id, account_id, slot, name) VALUES (?, ?, 900, ?) "
                        + "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)")) {
            ps.setLong(1, role);
            ps.setLong(2, accountId);
            ps.setString(3, "穿脱测试" + role);
            ps.executeUpdate();
        }
    }

    private static void cleanScratch(Connection jdbc, long role) throws Exception {
        // roles 行删除按外键 ON DELETE CASCADE 连带清 role_bag_items/role_equip_items。
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "DELETE FROM roles WHERE role_id = ?")) {
            ps.setLong(1, role);
            ps.executeUpdate();
        }
    }
}
