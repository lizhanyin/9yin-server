package com.nineyin.game.avatar.neigong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;

import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import org.junit.jupiter.api.Test;

import com.nineyin.util.MySqlEnv;

/**
 * role_faculty DB 双源集成（无 MySQL 自动跳过，MySqlRoleRepositoryIntegrationTest 同策略）。
 * 用真实 role 618 快照验证宽松解析（超集字段忽略、books 子集字段齐备）。
 */
class FacultyStoreDbIntegrationTest {

    private static final long PROBE_ROLE = 618L;

    private static boolean databaseAvailable() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), user(), password())) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static String dsn() {
        return MySqlEnv.loadDsn()
                .orElse("nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin");
    }

    private static String[] dsnParts() {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn());
        assertTrue(matcher.matches(), "test DSN must be Go-style");
        return new String[]{matcher.group(1), matcher.group(2), matcher.group(3),
                matcher.group(4), matcher.group(5)};
    }

    private static String jdbcUrl() {
        String[] parts = dsnParts();
        return "jdbc:mysql://" + parts[2] + ":" + parts[3] + "/" + parts[4]
                + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true";
    }

    private static String user() {
        return dsnParts()[0];
    }

    private static String password() {
        return dsnParts()[1];
    }

    @Test
    void loadRoleFacultySnapshotFromDatabase() throws Exception {
        assumeTrue(databaseAvailable(), "MySQL 不可用，跳过 role_faculty 集成");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), user(), password());
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT COUNT(*) FROM role_faculty WHERE role_id = " + PROBE_ROLE)) {
            assumeTrue(result.next() && result.getLong(1) > 0, "role 618 无 faculty 行，跳过");
        }

        FacultyStore store = FacultyStore.open(java.nio.file.Path.of(
                java.nio.file.Files.createTempDirectory("faculty-test").toString(), "faculty.json"));
        store.attachDatabase(dsn());
        PlayerProgress.Snapshot snapshot = store.load(PROBE_ROLE);
        assertNotNull(snapshot, "DB 优先读取 role 618 快照");
        assertNotNull(snapshot.curNeiGong());
        assertTrue(!snapshot.books().isEmpty(), "快照至少含一本书");
        assertTrue(snapshot.faculty() >= 0);
        // last_advance_utc 可解析（超集字段 title_id/skill_caps 等被宽松忽略）。
        Instant.parse(snapshot.lastAdvanceUtc().toString());
        store.close();
    }
}
