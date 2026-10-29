package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
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
                if (!rs.next()) {
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
                }
            }
            ensureRoleExtColumn(conn);
        } catch (SQLException e) {
            log.error("role schema init failed: {}", e.getMessage());
        }
    }

    /** 存量库（建表早于 role_ext 列）补挂 JSON 扩展列，幂等。 */
    private static void ensureRoleExtColumn(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.COLUMNS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'roles' "
                        + "AND column_name = 'role_ext'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE roles ADD COLUMN role_ext JSON NULL");
            }
            log.info("roles.role_ext column added");
        } catch (SQLException e) {
            log.error("role_ext column check/add failed: {}", e.getMessage());
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
                             + "l.pos_x, l.pos_y, l.pos_z, l.orient, r.role_ext "
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
                String extJson = rs.getString(9);
                if (extJson != null && !extJson.isEmpty()) {
                    JSONObject ext = JSON.parseObject(extJson, Feature.OrderedField);
                    if (ext != null) {
                        role.getExtra().putAll(ext);
                    }
                }
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
                long roleId = upsertRole(conn, accountId, role);
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

    private static long upsertRole(Connection conn, long accountId, StoredRole role) throws SQLException {
        String extJson = role.getExtra().isEmpty() ? null : JSON.toJSONString(role.getExtra());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT role_id FROM roles WHERE account_id = ? AND slot = 0")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long roleId = rs.getLong(1);
                    // 名字可能变化（同名唯一约束冲突由调用方避免）
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE roles SET name = ?, role_ext = CAST(? AS JSON) WHERE role_id = ?")) {
                        upd.setString(1, role.getName());
                        upd.setString(2, extJson);
                        upd.setLong(3, roleId);
                        upd.executeUpdate();
                    }
                    return roleId;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO roles (account_id, slot, name, role_ext) VALUES (?, 0, ?, CAST(? AS JSON))",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, accountId);
            ps.setString(2, role.getName());
            ps.setString(3, extJson);
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

    /**
     * 启动时逐表统计行数并输出加载摘要（当前库 SHOW TABLES 全量），
     * 失败只告警不影响启动。
     */
    public void logTableStats() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            List<String> tables = new java.util.ArrayList<>();
            try (ResultSet rs = st.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            if (tables.isEmpty()) {
                log.warn("表数据加载：库中无表");
                return;
            }
            StringBuilder line = new StringBuilder();
            long totalRows = 0;
            int printed = 0;
            for (String table : tables) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                    rs.next();
                    long n = rs.getLong(1);
                    totalRows += n;
                    if (line.length() > 0) {
                        line.append(", ");
                    }
                    line.append(table).append('=').append(n);
                    if (++printed % 4 == 0) {
                        log.info("表数据加载: {}", line);
                        line.setLength(0);
                    }
                }
            }
            if (line.length() > 0) {
                log.info("表数据加载: {}", line);
            }
            log.info("表数据加载完成: {} 张表, 合计 {} 行", tables.size(), totalRows);
        } catch (SQLException e) {
            log.warn("表数据加载统计失败: {}", e.getMessage());
        }
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
