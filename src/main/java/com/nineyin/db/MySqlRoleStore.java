package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * MySQL 角色存储（accounts / roles / role_appearances / role_locations 四表，
 * 表结构与 C++ migrations 完全一致，可互为备份恢复）。
 * 事务语义对齐 C++ RoleStore：put 为四表事务 upsert，get 为 JOIN 读取，
 * updateLocation 只更新位置；全部 PreparedStatement（修掉 C++ 拼 SQL 的注入面）。
 * 同时双写 JSON 文件后端兜底。
 */
public final class MySqlRoleStore implements RoleStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlRoleStore.class);

    private final HikariDataSource dataSource;
    private final JsonRoleStore fileBackup;

    public MySqlRoleStore(MysqlDsn.JdbcConfig config, java.nio.file.Path jsonBackupFile) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setPoolName("nineyin-role");
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(5000);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        this.dataSource = new HikariDataSource(hc);
        this.fileBackup = new JsonRoleStore(jsonBackupFile);
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SHOW TABLES LIKE 'accounts'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return; // 已建表
                }
            }
            String sql = readResource("/nineyin/db/0001_init_roles.sql");
            try (Statement st = conn.createStatement()) {
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    st.execute(trimmed);
                }
            }
            log.info("role schema created (4 tables)");
        } catch (SQLException e) {
            log.error("role schema init failed: {}", e.getMessage());
        }
    }

    private static String readResource(String path) {
        try (InputStream in = MySqlRoleStore.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("migration resource missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("migration resource read failed", e);
        }
    }

    @Override
    public boolean exists(String accountKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM accounts WHERE account_key = ?")) {
            ps.setString(1, accountKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("exists failed: {}", e.getMessage());
            return fileBackup.exists(accountKey);
        }
    }

    @Override
    public StoredRole get(String accountKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT r.name, a.appearance, l.scene_config, l.scene_resource, "
                             + "l.pos_x, l.pos_y, l.pos_z, l.orient "
                             + "FROM accounts acc "
                             + "JOIN roles r ON r.account_id = acc.account_id "
                             + "JOIN role_appearances a ON a.role_id = r.role_id "
                             + "JOIN role_locations l ON l.role_id = r.role_id "
                             + "WHERE acc.account_key = ?")) {
            ps.setString(1, accountKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                StoredRole role = new StoredRole();
                role.setName(rs.getString(1));
                role.setAppearance(JSON.parseArray(rs.getString(2), String.class));
                role.setSceneConfig(rs.getString(3));
                role.setSceneResource(rs.getString(4));
                role.setX(rs.getFloat(5));
                role.setY(rs.getFloat(6));
                role.setZ(rs.getFloat(7));
                role.setOrient(rs.getFloat(8));
                return role;
            }
        } catch (SQLException e) {
            log.error("get failed, fallback to file: {}", e.getMessage());
            return fileBackup.get(accountKey);
        }
    }

    @Override
    public void put(String accountKey, StoredRole role) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long accountId = upsertAccount(conn, accountKey);
                long roleId = upsertRole(conn, accountId, role.getName());
                upsertAppearance(conn, roleId, role.getAppearance());
                upsertLocation(conn, roleId, role);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException | RuntimeException e) {
            log.error("put failed: {}", e.getMessage());
        }
        fileBackup.put(accountKey, role);
    }

    private static long upsertAccount(Connection conn, String accountKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (account_key) VALUES (?) "
                        + "ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP(6)")) {
            ps.setString(1, accountKey);
            ps.executeUpdate();
        }
        return queryId(conn, "SELECT account_id FROM accounts WHERE account_key = ?", accountKey);
    }

    private static long upsertRole(Connection conn, long accountId, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT role_id FROM roles WHERE account_id = ? AND slot = 0")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long roleId = rs.getLong(1);
                    // 名字可能变化（同名唯一约束冲突由调用方避免）
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE roles SET name = ? WHERE role_id = ?")) {
                        upd.setString(1, name);
                        upd.setLong(2, roleId);
                        upd.executeUpdate();
                    }
                    return roleId;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO roles (account_id, slot, name) VALUES (?, 0, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, accountId);
            ps.setString(2, name);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private static void upsertAppearance(Connection conn, long roleId, List<String> appearance)
            throws SQLException {
        String json = JSON.toJSONString(appearance == null ? List.of() : appearance);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO role_appearances (role_id, appearance) VALUES (?, CAST(? AS JSON)) "
                        + "ON DUPLICATE KEY UPDATE appearance = CAST(? AS JSON)")) {
            ps.setLong(1, roleId);
            ps.setString(2, json);
            ps.setString(3, json);
            ps.executeUpdate();
        }
    }

    private static void upsertLocation(Connection conn, long roleId, StoredRole role)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO role_locations (role_id, scene_config, scene_resource, "
                        + "pos_x, pos_y, pos_z, orient) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE scene_config = VALUES(scene_config), "
                        + "scene_resource = VALUES(scene_resource), pos_x = VALUES(pos_x), "
                        + "pos_y = VALUES(pos_y), pos_z = VALUES(pos_z), orient = VALUES(orient)")) {
            ps.setLong(1, roleId);
            ps.setString(2, role.getSceneConfig());
            ps.setString(3, role.getSceneResource());
            ps.setFloat(4, role.getX());
            ps.setFloat(5, role.getY());
            ps.setFloat(6, role.getZ());
            ps.setFloat(7, role.getOrient());
            ps.executeUpdate();
        }
    }

    @Override
    public void updateLocation(String accountKey, float x, float y, float z, float orient) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE role_locations l "
                             + "JOIN roles r ON r.role_id = l.role_id "
                             + "JOIN accounts acc ON acc.account_id = r.account_id "
                             + "SET l.pos_x = ?, l.pos_y = ?, l.pos_z = ?, l.orient = ? "
                             + "WHERE acc.account_key = ?")) {
            ps.setFloat(1, x);
            ps.setFloat(2, y);
            ps.setFloat(3, z);
            ps.setFloat(4, orient);
            ps.setString(5, accountKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("updateLocation failed: {}", e.getMessage());
        }
        fileBackup.updateLocation(accountKey, x, y, z, orient);
    }

    @Override
    public int size() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            return fileBackup.size();
        }
    }

    @Override
    public void flush() {
        fileBackup.flush();
    }

    public void close() {
        dataSource.close();
    }

    /** 测试用：原始连接池。 */
    com.zaxxer.hikari.HikariDataSource dataSourceForTest() {
        return dataSource;
    }

    /** 测试用：该账号下的角色行数（验证 upsert 不产生重复行）。 */
    int roleCountForTest(String accountKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM roles r JOIN accounts a ON a.account_id = r.account_id "
                             + "WHERE a.account_key = ?")) {
            ps.setString(1, accountKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long queryId(Connection conn, String sql, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("account row missing after upsert: " + key);
                }
                return rs.getLong(1);
            }
        }
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            if (!rs.next()) {
                throw new SQLException("no generated key returned");
            }
            return rs.getLong(1);
        }
    }
}
