package com.nineyin.db;

import com.alibaba.fastjson.JSONArray;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TitleStore 集成测试（本地 MySQL game_titles/role_titles，DSN 缺省
 * nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin；库不可达时整组跳过）。
 * role_titles.role_id 有外键 fk_titles_role → roles，假角色插不进去，
 * 因此取 roles 表第一个角色（冒烟测试号）作载体，前后清行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TitleStoreTest {

    private long testRole;

    private TitleStore store;

    @BeforeAll
    void connect() {
        MySqlRoleStore mysql;
        try {
            mysql = new MySqlRoleStore(
                    MysqlDsn.parse("nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin"),
                    java.nio.file.Path.of("data", "roles.json"));
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "MySQL unavailable: " + e.getMessage());
            return;
        }
        store = TitleStore.from(mysql);
        Assumptions.assumeTrue(store != null, "TitleStore requires MySQL backend");
        try (var c = mysql.dataSourceForTest().getConnection();
             var ps = c.prepareStatement("SELECT role_id FROM roles ORDER BY role_id LIMIT 1");
             var rs = ps.executeQuery()) {
            Assumptions.assumeTrue(rs.next(), "roles table empty");
            testRole = rs.getLong(1);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "roles query failed: " + e.getMessage());
            return;
        }
        store.clear(testRole);
    }

    @Test
    void catalogLoadsFromGameTitles() {
        List<TitleStore.TitleDef> defs = store.catalog();
        Assumptions.assumeTrue(defs.size() > 100, "game_titles not seeded");
        assertEquals(1, defs.get(0).id());
        JSONArray json = store.catalogJson();
        assertEquals(defs.size(), json.size());
        assertTrue(json.getJSONObject(0).containsKey("name"));
    }

    @Test
    void grantWearCancelClearLifecycle() {
        assertFalse(store.owns(testRole, 1));
        assertTrue(store.grant(testRole, 1));      // 定义存在 → 新发放
        assertFalse(store.grant(testRole, 1));     // 幂等
        assertFalse(store.grant(testRole, 999999)); // 定义不存在 → 拒绝
        assertTrue(store.owns(testRole, 1));

        store.grant(testRole, 2);
        store.wear(testRole, 2);
        assertEquals(2, store.worn(testRole));
        assertEquals(List.of(1, 2), store.owned(testRole));

        store.cancelWorn(testRole);
        assertNull(store.worn(testRole));

        store.wear(testRole, 1);
        assertEquals(1, store.worn(testRole));

        store.clear(testRole);
        assertTrue(store.owned(testRole).isEmpty());
        assertNull(store.worn(testRole));
    }
}
