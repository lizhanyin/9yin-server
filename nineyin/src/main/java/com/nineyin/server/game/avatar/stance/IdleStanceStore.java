package com.nineyin.server.game.avatar.stance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.util.GoJson;

/**
 * 待机站姿存档（每角色一个 b_action_index.ini 动作名，空=默认站姿）：
 * data/待机站姿.json 主档 + MySQL role_idle_stance 双源（AppearanceStore 同款：
 * 读优先后备、原子替换写、DB 同步 best-effort）。
 *
 * <p>下发通道 = 玩家属性 {@code PlayActionName}(821, string)；重登/过图/
 * 受击/挖矿/技能结束后的回站由服务端在这些节点重推保持（引擎动作会被打断）。
 */
public final class IdleStanceStore {

    private static final int STORE_VERSION = 1;

    private final Path path;
    private final Map<Long, String> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private com.zaxxer.hikari.HikariDataSource database;

    public static IdleStanceStore open(Path path) {
        IdleStanceStore store = new IdleStanceStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read idle stance store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode idle stance store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                // 两种方言兼容：扁平串 {"2":"khbd_stand_01"}（Go 根 data/待机站姿.json
                // 既有档）与嵌套对象 {"2":{"stance":"..."}}。
                Object value = entry.getValue();
                String stance = value instanceof JSONObject nested
                        ? nested.getString("stance") : (String) value;
                if (stance != null && !stance.isEmpty()) {
                    store.roles.put(Long.parseLong(entry.getKey()), stance);
                }
            }
        }
        return store;
    }

    private IdleStanceStore(Path path) {
        this.path = path;
    }

    /** Go 风格 DSN → 独立小池；缺表自动建（role_idle_stance），失败仅告警。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("idle stance store: invalid MySQL DSN");
        }
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&characterEncoding=UTF-8&connectionTimeZone=UTC"
                        + "&connectTimeout=5000&socketTimeout=5000&allowPublicKeyRetrieval=true",
                matcher.group(3), matcher.group(4), matcher.group(5)));
        config.setUsername(matcher.group(1));
        config.setPassword(matcher.group(2));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setPoolName("nineyin-idle-stance");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            // 权威建表走 admin/sql/0011；此处兜底与 role_appearance 同款（FK CASCADE）。
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_idle_stance ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "stance_action VARCHAR(64) NOT NULL DEFAULT '',"
                    + "version BIGINT UNSIGNED NOT NULL DEFAULT 1,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id),"
                    + "CONSTRAINT fk_idle_stance_role FOREIGN KEY (role_id) REFERENCES roles(role_id)"
                    + " ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用。
        }
    }

    /** 无存档/空站姿返回 null（重推跳过，客户端默认站姿）。 */
    public String load(long roleId) {
        if (roleId == 0) {
            return null;
        }
        String fromDb = loadFromDatabase(roleId);
        if (fromDb != null) {
            return fromDb;
        }
        lock.lock();
        try {
            return roles.get(roleId);
        } finally {
            lock.unlock();
        }
    }

    private String loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT stance_action FROM role_idle_stance WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String stance = result.getString(1);
                return stance == null || stance.isEmpty() ? null : stance;
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    /** 保存站姿（空串 = 清除回默认）。 */
    public void save(long roleId, String stanceAction) {
        if (roleId == 0) {
            throw new IllegalArgumentException("idle stance store: zero role id");
        }
        String stance = stanceAction == null ? "" : stanceAction.trim();
        lock.lock();
        try {
            String previous = stance.isEmpty() ? roles.remove(roleId) : roles.put(roleId, stance);
            try {
                persist();
                upsertDatabase(roleId, stance);
            } catch (RuntimeException e) {
                if (previous == null) {
                    roles.remove(roleId);
                } else {
                    roles.put(roleId, previous);
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private void upsertDatabase(long roleId, String stance) {
        if (database == null) {
            return;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_idle_stance (role_id, stance_action, version, updated_at) "
                             + "VALUES (?, ?, 1, UTC_TIMESTAMP(6)) "
                             + "ON DUPLICATE KEY UPDATE stance_action = VALUES(stance_action), "
                             + "version = version + 1, updated_at = UTC_TIMESTAMP(6)")) {
            statement.setLong(1, roleId);
            statement.setString(2, stance);
            statement.executeUpdate();
        } catch (Exception ignored) {
            // 文件已持久化；DB 侧下次登录前可重试。
        }
    }

    public void close() {
        if (database != null) {
            database.close();
        }
    }

    /** GoJson MarshalIndent 兼容输出 + 原子替换（扁平方言：roles → roleId → 动作名）。 */
    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", STORE_VERSION);
        if (!roles.isEmpty()) {
            GoJson.Object rolesNode = new GoJson.Object();
            List<Long> ids = new ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                rolesNode.field(Long.toString(id), roles.get(id));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".idle-stance-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write idle stance store", e);
        }
    }
}
