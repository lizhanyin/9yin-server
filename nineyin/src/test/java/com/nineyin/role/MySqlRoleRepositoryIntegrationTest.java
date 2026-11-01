package com.nineyin.role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.nineyin.server.db.role.MySqlRoleRepository;
import com.nineyin.server.db.role.RoleRepositoryException;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.db.role.Roles;
import com.nineyin.util.MySqlEnv;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MySQL 仓库集成测试：本机 nineyin 库内建 Go migrations/0001 M2 结构的 it_ 前缀临时表，
 * 验证 provision/create/load/CAS/门派持久化。本机无 MySQL 或无建表权限时整体跳过。
 */
class MySqlRoleRepositoryIntegrationTest {

    private static Connection admin;
    private static MySqlRoleRepository repository;

    @BeforeAll
    static void setUp() throws SQLException {
        // DSN 来源与生产一致：NINEYIN_MYSQL_DSN 环境变量 > 根目录 mysql.env；均无则跳过。
        String base = System.getenv("NINEYIN_MYSQL_DSN");
        if (base == null || base.isBlank()) {
            base = MySqlEnv.loadDsn().orElse(null);
        }
        assumeTrue(base != null && !base.isBlank(),
                "未配置 MySQL DSN（环境变量 NINEYIN_MYSQL_DSN 或根目录 mysql.env），跳过集成测试");
        var matcher = MySqlRoleRepositoryTestHarness.DSN.matcher(base);
        assumeTrue(matcher.matches(), "测试 DSN 形如 user:pass@tcp(host:port)/db");
        String url = String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000",
                matcher.group(3), matcher.group(4), matcher.group(5));
        try {
            admin = DriverManager.getConnection(url, matcher.group(1), matcher.group(2));
        } catch (SQLException unavailable) {
            assumeTrue(false, "本机 MySQL 不可用：" + unavailable.getMessage());
        }
        try (Statement statement = admin.createStatement()) {
            for (String table : new String[]{"it_roles", "it_role_appearances", "it_role_locations", "it_accounts"}) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            statement.execute("""
                    CREATE TABLE it_accounts (
                      account_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      account_key VARBINARY(128) NOT NULL,
                      password_hash VARBINARY(255) NULL,
                      status TINYINT UNSIGNED NOT NULL DEFAULT 1,
                      version BIGINT UNSIGNED NOT NULL DEFAULT 1,
                      created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                      updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                      PRIMARY KEY (account_id),
                      UNIQUE KEY uq_accounts_key (account_key)
                    ) ENGINE=InnoDB""");
            statement.execute("""
                    CREATE TABLE it_roles (
                      role_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      account_id BIGINT UNSIGNED NOT NULL,
                      slot SMALLINT UNSIGNED NOT NULL,
                      name VARCHAR(64) NOT NULL,
                      delete_requested_at TIMESTAMP(6) NULL,
                      version BIGINT UNSIGNED NOT NULL DEFAULT 1,
                      created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                      updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                      PRIMARY KEY (role_id),
                      UNIQUE KEY uq_roles_account_slot (account_id, slot),
                      UNIQUE KEY uq_roles_name (name),
                      KEY ix_roles_account (account_id)
                    ) ENGINE=InnoDB""");
            statement.execute("""
                    CREATE TABLE it_role_appearances (
                      role_id BIGINT UNSIGNED NOT NULL,
                      format_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
                      appearance JSON NOT NULL,
                      version BIGINT UNSIGNED NOT NULL DEFAULT 1,
                      updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                      PRIMARY KEY (role_id)
                    ) ENGINE=InnoDB""");
            statement.execute("""
                    CREATE TABLE it_role_locations (
                      role_id BIGINT UNSIGNED NOT NULL,
                      scene_config VARCHAR(255) NOT NULL,
                      scene_resource VARCHAR(64) NOT NULL,
                      pos_x FLOAT NOT NULL, pos_y FLOAT NOT NULL, pos_z FLOAT NOT NULL, orient FLOAT NOT NULL,
                      version BIGINT UNSIGNED NOT NULL DEFAULT 1,
                      updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                      PRIMARY KEY (role_id)
                    ) ENGINE=InnoDB""");
        } catch (SQLException e) {
            admin.close();
            assumeTrue(false, "建临时表失败（无建表权限则跳过）：" + e.getMessage());
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(matcher.group(1));
        config.setPassword(matcher.group(2));
        config.setMaximumPoolSize(4);
        repository = new MySqlRoleRepository(new HikariDataSource(config), "it_");
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (repository != null) {
            repository.close();
        }
        if (admin != null) {
            try (Statement statement = admin.createStatement()) {
                for (String table : new String[]{"it_roles", "it_role_appearances", "it_role_locations", "it_accounts"}) {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                }
            }
            admin.close();
        }
    }

    @Test
    void provisionCreateLoadCas() {
        String key = "acct:v1:" + "ab".repeat(28);
        Roles.Account account = repository.provisionLegacy(key);
        assertTrue(account.id() > 0);
        assertEquals(Roles.AccountStatus.ACTIVE, account.status());

        Roles.RoleSnapshot created = repository.create(account.id(), new Roles.NewRole(0, "mysql甲",
                new Roles.Appearance(1, List.of()), RoleStoreFacade.defaultRoleLocation()));
        assertEquals("mysql甲", created.name());
        assertEquals(1, created.location().version());

        RoleRepositoryException slotTaken = assertThrows(RoleRepositoryException.class,
                () -> repository.create(account.id(), new Roles.NewRole(0, "mysql甲",
                        new Roles.Appearance(1, List.of()), RoleStoreFacade.defaultRoleLocation())));
        assertTrue(slotTaken.is(RoleRepositoryException.Code.ROLE_SLOT_TAKEN));

        // 重名（跨账号）→ NAME_TAKEN。
        Roles.Account other = repository.provisionLegacy("acct:v1:" + "cd".repeat(28));
        RoleRepositoryException nameTaken = assertThrows(RoleRepositoryException.class,
                () -> repository.create(other.id(), new Roles.NewRole(0, "mysql甲",
                        new Roles.Appearance(1, List.of()), RoleStoreFacade.defaultRoleLocation())));
        assertTrue(nameTaken.is(RoleRepositoryException.Code.ROLE_NAME_TAKEN));

        Roles.Account again = repository.findByKey(key);
        List<Roles.RoleSummary> summaries = repository.listByAccount(again.id());
        assertEquals(1, summaries.size());
        assertEquals(0, summaries.get(0).slot());
        Roles.RoleSnapshot loaded = repository.loadOwned(again.id(), summaries.get(0).id());
        assertEquals("mysql甲", loaded.name());
        assertEquals("ini\\scene\\school09_YiHuaGong", loaded.location().scene().config());
        assertEquals(340.160f, loaded.location().position().x());

        RoleRepositoryException conflict = assertThrows(RoleRepositoryException.class,
                () -> repository.saveLocation(loaded.id(), 999, loaded.location()));
        assertTrue(conflict.is(RoleRepositoryException.Code.VERSION_CONFLICT));
        long next = repository.saveLocation(loaded.id(), loaded.location().version(), new Roles.Location(
                new Roles.Scene("ini\\scene\\city05_ChengDu", "city05"),
                new Roles.Position(1, 2, 3, 4), loaded.location().version()));
        assertEquals(2, next);

        long appearanceNext = repository.saveAppearance(loaded.id(), loaded.appearance().version(),
                new Roles.Appearance(1, java.util.Arrays.asList("", "", "", "", "", "", "", "", "force_emei")));
        assertEquals(2, appearanceNext);
        Roles.RoleSnapshot reloaded = repository.loadOwned(again.id(), loaded.id());
        assertEquals("force_emei", reloaded.appearance().values().get(8));
        assertEquals("city05", reloaded.location().scene().resource());

        RoleRepositoryException notOwned = assertThrows(RoleRepositoryException.class,
                () -> repository.loadOwned(account.id() + 999999, loaded.id()));
        assertTrue(notOwned.is(RoleRepositoryException.Code.NOT_OWNED));
    }
}
