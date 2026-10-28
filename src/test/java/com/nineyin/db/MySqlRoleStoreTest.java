package com.nineyin.db;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void putGetUpdateLocationRoundTrip() {
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

        store.updateLocation(key, 100.5f, 1f, 200.25f, 3.14f);
        StoredRole moved = store.get(key);
        assertEquals(100.5f, moved.getX(), 0.001f);
        assertEquals(3.14f, moved.getOrient(), 0.001f);

        // JSON 双写兜底
        assertTrue(tempDir.resolve("backup.json").toFile().exists());

        // 二次 put 走 upsert 分支（不产生第二行）
        role.setName("集成测试侠");
        store.put(key, role);
        assertEquals(1, store.roleCountForTest(key));

        store.close();
    }

    @Test
    void missingKeyReturnsNull() {
        MySqlRoleStore store = open();
        assertNull(store.get("acct:v1:no-such-" + System.nanoTime()));
        store.close();
    }
}
