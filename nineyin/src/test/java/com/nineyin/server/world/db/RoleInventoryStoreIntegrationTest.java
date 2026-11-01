package com.nineyin.server.world.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(8, store.equips(ROLE).size(), "穿戴装备 8 件");
            assertEquals(27, store.bagItems(ROLE).size(), "背包 27 件");
            assertEquals(190, store.skills(ROLE, "wuxue").size(), "武学 190");
            assertEquals(48, store.skills(ROLE, "neigong").size(), "内功 48");
            assertEquals(117, store.shortcuts(ROLE).size(), "快捷栏 117 行");
            assertEquals(4, store.bagpacks(ROLE).size(), "扩容锚点 4 个（每页首锚）");
            // game_items 富化：武器行拿到 ItemType/名称/EquipType。
            var weapon = store.bagItems(ROLE).stream()
                    .filter(item -> "Weapon".equals(item.equipType())).findFirst();
            assertTrue(weapon.isPresent(), "背包含 Weapon 行且 EquipType 富化成功");
        }
    }
}
