package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 集成测试：仅在本机 3306 实例可达时执行（不可达自动跳过）。
 * 使用 nineyin 库，表由 MySqlRoleStore 自动建（幂等）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlRoleStoreTest {

    private static final String DSN = "nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin";

    @TempDir
    Path tempDir;

    private MySqlRoleStore open() {
        MySqlRoleStore store;
        try {
            store = new MySqlRoleStore(MysqlDsn.parse(DSN), tempDir.resolve("backup.json"));
            try (Connection conn = store.dataSourceForTest().getConnection()) {
                Assumptions.assumeTrue(true);
            } catch (Exception e) {
                Assumptions.assumeTrue(false, "MySQL not reachable: " + e.getMessage());
                return null;
            }
            return store;
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "MySQL init failed: " + t.getMessage());
            return null;
        }
    }

    @Test
    void putGetUpdateLocationRoundTrip() throws Exception {
        MySqlRoleStore store = open();
        String key = "acct:v1:it-" + System.nanoTime();

        StoredRole role = new StoredRole();
        role.setName("集成测试侠");
        role.setAppearance(List.of("book4", "icon\\players\\boy01_create.png", "3333",
                "0", "b_hair1", "cloth_b0001", "pants_b0001", "shoes_b0001"));
        role.setSceneConfig("ini\\scene\\city05_ChengDu");
        role.setSceneResource("city05");
        role.setX(915.753f);
        role.setY(31.192f);
        role.setZ(538.193f);
        role.setOrient(6.170f);
        // 原生存档扩展字段经 role_ext JSON 列往返不丢
        role.getExtra().put("power_level", 7);
        role.getExtra().put("nei_gong_points", 120);
        role.getExtra().put("max_ene", 1000);
        store.put(key, role);

        assertTrue(store.exists(key));
        StoredRole loaded = store.get(key);
        assertNotNull(loaded);
        assertEquals("集成测试侠", loaded.getName(), "中文名必须经 JSON 列往返不丢");
        assertEquals("book4", loaded.getAppearance().get(0));
        assertEquals(8, loaded.getAppearance().size());
        assertEquals("city05", loaded.getSceneResource());
        assertEquals(915.753f, loaded.getX(), 0.001f);
        assertEquals(6.170f, loaded.getOrient(), 0.001f);
        assertEquals(3, loaded.getExtra().size());
        assertEquals(7, ((Number) loaded.getExtra().get("power_level")).intValue());
        assertEquals(120, ((Number) loaded.getExtra().get("nei_gong_points")).intValue());

        store.updateLocation(key, 100.5f, 1f, 200.25f, 3.14f);
        StoredRole moved = store.get(key);
        assertEquals(100.5f, moved.getX(), 0.001f);
        assertEquals(3.14f, moved.getOrient(), 0.001f);
        assertEquals(7, ((Number) moved.getExtra().get("power_level")).intValue(),
                "updateLocation 不得清掉扩展字段");

        // JSON 双写兜底：同样为原生包装格式，扩展字段随兜底文件保留
        assertTrue(tempDir.resolve("backup.json").toFile().exists());
        JSONObject backup = JSON.parseObject(
                Files.readString(tempDir.resolve("backup.json"), StandardCharsets.UTF_8),
                com.alibaba.fastjson.parser.Feature.OrderedField);
        JSONObject entry = backup.getJSONObject("accounts").getJSONObject(key);
        assertNotNull(entry);
        assertEquals(7, ((Number) entry.get("power_level")).intValue(),
                "双写兜底文件必须保留扩展字段");

        // 二次 put 走 upsert 分支（不产生第二行，扩展字段不丢）
        role.setName("集成测试侠");
        store.put(key, role);
        assertEquals(1, store.roleCountForTest(key));
        assertEquals(3, store.get(key).getExtra().size());

        store.close();
    }

    @Test
    void missingKeyReturnsNull() {
        MySqlRoleStore store = open();
        assertNull(store.get("acct:v1:no-such-" + System.nanoTime()));
        store.close();
    }

    @Test
    void termsAgreeResetRoundTrip() {
        MySqlRoleStore store = open();
        String key = "acct:v1:it-terms-" + System.nanoTime();

        // 账号无行：未同意
        long[] before = store.termsState(key);
        assertEquals(0, before[0]);
        assertEquals(0, before[1]);

        // 无行直接标记：补建账号行并落时间
        assertTrue(store.markTermsAgreed(key), "无行时标记应视为首次");
        long[] agreed = store.termsState(key);
        assertEquals(1, agreed[0]);
        assertTrue(agreed[1] > 0, "同意时间必须落库");
        assertFalse(store.markTermsAgreed(key), "重复同意应幂等返回非首次");
        long[] again = store.termsState(key);
        assertEquals(1, again[0]);
        assertEquals(agreed[1], again[1], "重复同意不得刷新时间");

        store.resetTermsAgreed(key);
        long[] reset = store.termsState(key);
        assertEquals(0, reset[0]);
        assertEquals(0, reset[1]);
        store.close();
    }

    /**
     * 集成测试不自清理会留下 '集成测试侠'，其唯一名约束会卡死下一次
     * mvn package，故每次跑完删掉本测试的痕迹。
     */
    @AfterAll
    void cleanupLeftovers() {
        MySqlRoleStore store;
        try {
            store = new MySqlRoleStore(MysqlDsn.parse(DSN), tempDir.resolve("cleanup.json"));
        } catch (Throwable unreachable) {
            return;
        }
        try (Connection conn = store.dataSourceForTest().getConnection()) {
            exec(conn, "DELETE l FROM role_locations l JOIN roles r ON r.role_id = l.role_id WHERE r.name = ?", "集成测试侠");
            exec(conn, "DELETE a FROM role_appearances a JOIN roles r ON a.role_id = r.role_id WHERE r.name = ?", "集成测试侠");
            exec(conn, "DELETE FROM roles WHERE name = ?", "集成测试侠");
            exec(conn, "DELETE FROM accounts WHERE account_key LIKE 'acct:v1:it-%'", null);
        } catch (Throwable ignoreCleanupFailure) {
            // 清理尽力而为：MySQL 不可达或权限不足时静默跳过
        } finally {
            store.close();
        }
    }

    private static void exec(Connection conn, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (arg != null) {
                ps.setString(1, arg);
            }
            ps.executeUpdate();
        }
    }
}
