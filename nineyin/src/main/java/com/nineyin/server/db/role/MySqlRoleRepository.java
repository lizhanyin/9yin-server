package com.nineyin.server.db.role;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson2.JSON;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * MySQL 角色仓库，逐行对齐 Go internal/role/mysql_repository.go：
 * accounts/roles/role_appearances/role_locations 四表（migrations/0001 M2 结构），
 * CAS 版本写、创建事务 + FOR UPDATE、1062 重名/占位错误分类。
 */
public final class MySqlRoleRepository implements RoleRepository {

    private static final Pattern DSN = Pattern.compile("^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)(?:\\?(.*))?$");

    private final HikariDataSource dataSource;
    private final String tablePrefix;

    /** 解析 Go 风格 DSN（user:pass@tcp(host:port)/db）为 JDBC 连接。 */
    public static MySqlRoleRepository open(String dsn) {
        Matcher matcher = DSN.matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT,
                    "role: invalid MySQL DSN");
        }
        String user = matcher.group(1);
        String password = matcher.group(2);
        String host = matcher.group(3);
        int port = Integer.parseInt(matcher.group(4));
        String database = matcher.group(5);
        HikariConfig config = new HikariConfig();
        // ProductionDSN 等价：utf8mb4 + UTC + 5s 超时。
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8&connectionTimeZone=UTC"
                        + "&connectTimeout=5000&socketTimeout=5000&allowPublicKeyRetrieval=true",
                host, port, database));
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(16);
        config.setMinimumIdle(8);
        config.setPoolName("nineyin-roles");
        try {
            MySqlRoleRepository repository = new MySqlRoleRepository(new HikariDataSource(config), "");
            repository.ping();
            return repository;
        } catch (RuntimeException e) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, e.getMessage());
        }
    }

    MySqlRoleRepository(HikariDataSource dataSource, String tablePrefix) {
        this.dataSource = dataSource;
        this.tablePrefix = tablePrefix;
    }

    private String table(String base) {
        return tablePrefix + base;
    }

    private void ping() {
        try (Connection ignored = dataSource.getConnection()) {
            return;
        } catch (SQLException e) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, e.getMessage());
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @Override
    public Roles.Account findByKey(String key) {
        validateAccountKey(key);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT account_id, account_key, status, version FROM " + table("accounts") + " WHERE account_key = ?")) {
            statement.setBytes(1, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
                }
                Roles.AccountStatus status = Roles.AccountStatus.of(rows.getInt("status"));
                if (status != Roles.AccountStatus.ACTIVE) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.ACCOUNT_DISABLED);
                }
                return new Roles.Account(rows.getLong("account_id"), key, status, rows.getLong("version"));
            }
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public Roles.Account provisionLegacy(String key) {
        validateAccountKey(key);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + table("accounts") + "(account_key) VALUES (?)")) {
            statement.setBytes(1, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            statement.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 已存在：走 FindByKey。
        } catch (SQLException e) {
            throw storage(e);
        }
        return findByKey(key);
    }

    @Override
    public List<Roles.RoleSummary> listByAccount(long accountId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role_id, slot, name, delete_requested_at, version"
                             + " FROM " + table("roles") + " WHERE account_id = ? ORDER BY slot ASC")) {
            statement.setLong(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Roles.RoleSummary> result = new ArrayList<>();
                while (rows.next()) {
                    Timestamp deleted = rows.getTimestamp("delete_requested_at");
                    result.add(new Roles.RoleSummary(rows.getLong("role_id"), rows.getInt("slot"),
                            rows.getString("name"), deleted != null, rows.getLong("version")));
                }
                return result;
            }
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public Roles.RoleSummary findRoleByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role_id, slot, name, delete_requested_at, version"
                             + " FROM " + table("roles") + " WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Timestamp deleted = rows.getTimestamp("delete_requested_at");
                return new Roles.RoleSummary(rows.getLong("role_id"), rows.getInt("slot"),
                        rows.getString("name"), deleted != null, rows.getLong("version"));
            }
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    private String snapshotColumns() {
        return "SELECT r.role_id, r.account_id, r.slot, r.name, r.delete_requested_at, r.version,"
                    + " a.format_version, a.appearance, a.version AS appearance_version,"
                    + " l.scene_config, l.scene_resource, l.pos_x, l.pos_y, l.pos_z, l.orient, l.version AS location_version"
                    + " FROM " + table("roles") + " r"
                    + " LEFT JOIN " + table("role_appearances") + " a ON a.role_id = r.role_id"
                    + " LEFT JOIN " + table("role_locations") + " l ON l.role_id = r.role_id";
    }

    @Override
    public Roles.RoleSnapshot loadOwned(long accountId, long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     snapshotColumns() + " WHERE r.account_id = ? AND r.role_id = ?")) {
            statement.setLong(1, accountId);
            statement.setLong(2, roleId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return scanSnapshot(rows);
                }
            }
            classifyOwnership(connection, accountId, roleId);
            throw new AssertionError("unreachable");
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public Roles.RoleSnapshot loadOwnedByName(long accountId, String name) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     snapshotColumns() + " WHERE r.account_id = ? AND r.name = ?")) {
            statement.setLong(1, accountId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return scanSnapshot(rows);
                }
            }
            try (PreparedStatement ownerStatement = connection.prepareStatement(
                    "SELECT account_id FROM " + table("roles") + " WHERE name = ?")) {
                ownerStatement.setString(1, name);
                try (ResultSet rows = ownerStatement.executeQuery()) {
                    if (!rows.next()) {
                        throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
                    }
                    if (rows.getLong("account_id") != accountId) {
                        throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_OWNED);
                    }
                }
            }
            throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    private static Roles.RoleSnapshot scanSnapshot(ResultSet rows) throws SQLException {
        Timestamp deleted = rows.getTimestamp("delete_requested_at");
        int format = rows.getInt("format_version");
        boolean formatValid = !rows.wasNull();
        String appearance = rows.getString("appearance");
        boolean appearanceValid = !rows.wasNull();
        long appearanceVersion = rows.getLong("appearance_version");
        boolean appearanceVersionValid = !rows.wasNull();
        String sceneConfig = rows.getString("scene_config");
        String sceneResource = rows.getString("scene_resource");
        float x = rows.getFloat("pos_x");
        float y = rows.getFloat("pos_y");
        float z = rows.getFloat("pos_z");
        float orient = rows.getFloat("orient");
        long locationVersion = rows.getLong("location_version");
        boolean locationVersionValid = !rows.wasNull();
        if (!formatValid || !appearanceValid || !appearanceVersionValid || sceneConfig == null
                || sceneResource == null || !locationVersionValid) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
        }
        List<String> values;
        try {
            values = JSON.parseArray(appearance).toJavaList(String.class);
        } catch (RuntimeException e) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
        }
        Roles.Location location = new Roles.Location(
                new Roles.Scene(sceneConfig, sceneResource), new Roles.Position(x, y, z, orient), locationVersion);
        try {
            validateLocationStatic(location);
        } catch (RoleRepositoryException e) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
        }
        return new Roles.RoleSnapshot(rows.getLong("role_id"), rows.getInt("slot"), rows.getString("name"),
                deleted != null, rows.getLong("version"), rows.getLong("account_id"),
                new Roles.Appearance(format, values, appearanceVersion), location);
    }

    private static void validateLocationStatic(Roles.Location location) {
        if (location.scene().config().isEmpty() || location.scene().resource().isEmpty()) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "empty scene");
        }
    }

    @Override
    public Roles.RoleSnapshot create(long accountId, Roles.NewRole draft) {
        if (draft.slot() >= Roles.DEFAULT_MAX_ROLE_SLOTS || draft.name() == null || draft.name().isBlank()
                || draft.name().codePointCount(0, draft.name().length()) > Roles.MAX_ROLE_NAME_RUNES) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "invalid role slot or name");
        }
        validateLocationStatic(draft.location());
        int formatVersion = draft.appearance().formatVersion() == 0 ? 1 : draft.appearance().formatVersion();
        List<String> values = draft.appearance().values() == null ? List.of() : draft.appearance().values();
        String encoded = JSON.toJSONString(values);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int status;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT status FROM " + table("accounts") + " WHERE account_id = ? FOR UPDATE")) {
                    statement.setLong(1, accountId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
                        }
                        status = rows.getInt("status");
                    }
                }
                if (status != Roles.AccountStatus.ACTIVE.value) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.ACCOUNT_DISABLED);
                }
                long roleId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + table("roles") + "(account_id, slot, name) VALUES (?, ?, ?)",
                        PreparedStatement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, accountId);
                    statement.setInt(2, draft.slot());
                    statement.setString(3, draft.name());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        roleId = keys.getLong(1);
                    }
                } catch (SQLIntegrityConstraintViolationException duplicate) {
                    throw classifyCreateError(duplicate);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + table("role_appearances") + "(role_id, format_version, appearance) VALUES (?, ?, ?)")) {
                    statement.setLong(1, roleId);
                    statement.setInt(2, formatVersion);
                    statement.setString(3, encoded);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + table("role_locations") + "(role_id, scene_config, scene_resource, pos_x, pos_y, pos_z, orient)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    statement.setLong(1, roleId);
                    statement.setString(2, draft.location().scene().config());
                    statement.setString(3, draft.location().scene().resource());
                    statement.setFloat(4, draft.location().position().x());
                    statement.setFloat(5, draft.location().position().y());
                    statement.setFloat(6, draft.location().position().z());
                    statement.setFloat(7, draft.location().position().orient());
                    statement.executeUpdate();
                }
                connection.commit();
                return new Roles.RoleSnapshot(roleId, draft.slot(), draft.name(), false, 1, accountId,
                        new Roles.Appearance(formatVersion, new ArrayList<>(values), 1),
                        new Roles.Location(draft.location().scene(), draft.location().position(), 1));
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof RoleRepositoryException) {
                    throw (RoleRepositoryException) e;
                }
                if (e instanceof SQLIntegrityConstraintViolationException integrity) {
                    throw classifyCreateError(integrity);
                }
                if (e instanceof SQLException sql) {
                    throw storage(sql);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public void requestDelete(long accountId, long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + table("roles") + " SET delete_requested_at = COALESCE(delete_requested_at, CURRENT_TIMESTAMP(6)),"
                             + " version = version + 1 WHERE account_id = ? AND role_id = ?")) {
            statement.setLong(1, accountId);
            statement.setLong(2, roleId);
            if (statement.executeUpdate() == 1) {
                return;
            }
            classifyOwnership(connection, accountId, roleId);
            throw new AssertionError("unreachable");
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public long saveAppearance(long roleId, long expectedVersion, Roles.Appearance value) {
        if (value.formatVersion() == 0) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "zero appearance format");
        }
        List<String> values = value.values() == null ? List.of() : value.values();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + table("role_appearances") + " SET format_version = ?, appearance = ?, version = version + 1"
                             + " WHERE role_id = ? AND version = ?")) {
            statement.setInt(1, value.formatVersion());
            statement.setString(2, JSON.toJSONString(values));
            statement.setLong(3, roleId);
            statement.setLong(4, expectedVersion);
            return classifyCas(connection, statement.executeUpdate(), "role_appearances", roleId, expectedVersion);
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    @Override
    public long saveLocation(long roleId, long expectedVersion, Roles.Location value) {
        validateLocationStatic(value);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + table("role_locations") + " SET scene_config = ?, scene_resource = ?, pos_x = ?, pos_y = ?,"
                             + " pos_z = ?, orient = ?, version = version + 1 WHERE role_id = ? AND version = ?")) {
            statement.setString(1, value.scene().config());
            statement.setString(2, value.scene().resource());
            statement.setFloat(3, value.position().x());
            statement.setFloat(4, value.position().y());
            statement.setFloat(5, value.position().z());
            statement.setFloat(6, value.position().orient());
            statement.setLong(7, roleId);
            statement.setLong(8, expectedVersion);
            return classifyCas(connection, statement.executeUpdate(), "role_locations", roleId, expectedVersion);
        } catch (SQLException e) {
            throw storage(e);
        }
    }

    private long classifyCas(Connection connection, int updated, String table, long roleId,
                                    long expectedVersion) throws SQLException {
        if (updated == 1) {
            return expectedVersion + 1;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM " + tablePrefix + table + " WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
                }
                long current = rows.getLong("version");
                throw new RoleRepositoryException(RoleRepositoryException.Code.VERSION_CONFLICT,
                        "current=" + current);
            }
        }
    }

    private void classifyOwnership(Connection connection, long accountId, long roleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT account_id FROM " + table("roles") + " WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
                }
                if (rows.getLong("account_id") != accountId) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_OWNED);
                }
                throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
            }
        }
    }

    private static RoleRepositoryException classifyCreateError(SQLIntegrityConstraintViolationException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("uq_roles_name")) {
            return new RoleRepositoryException(RoleRepositoryException.Code.ROLE_NAME_TAKEN);
        }
        if (message.contains("uq_roles_account_slot")) {
            return new RoleRepositoryException(RoleRepositoryException.Code.ROLE_SLOT_TAKEN);
        }
        return new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, message);
    }

    private static void validateAccountKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ACCOUNT_KEY);
        }
    }

    private static RoleRepositoryException storage(SQLException e) {
        return new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, e.getMessage());
    }

    /** 测试用：连接元信息。 */
    Map<String, Object> dataSourceStats() {
        return Map.of(
                "active", dataSource.getHikariPoolMXBean().getActiveConnections(),
                "idle", dataSource.getHikariPoolMXBean().getIdleConnections());
    }
}
