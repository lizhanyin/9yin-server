package com.nineyin.server.game.avatar.appearance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.util.GoJson;

/**
 * 外观显示偏好存档：三套外观（江湖装/身份装/时装）的显示开关
 * {@code ShowEquipType}（0/1/2，custom 61 上行切换）。data/appearance.json 主档 +
 * MySQL role_appearance 双源（SkinStore 同款：读优先后备、原子替换写、DB 同步
 * best-effort）。模型本身不下档——穿戴占 role_equip_items 槽 16-19，最终模型
 * 由 {@link AppearanceSync} 按显示类型现场裁决。
 */
public final class AppearanceStore {

    private static final int STORE_VERSION = 1;

    private final Path path;
    private final Map<Long, Integer> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private com.zaxxer.hikari.HikariDataSource database;

    public static AppearanceStore open(Path path) {
        AppearanceStore store = new AppearanceStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read appearance store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode appearance store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                int showEquipType = ((JSONObject) entry.getValue()).getIntValue("show_equip_type");
                if (showEquipType >= 0) {
                    store.roles.put(Long.parseLong(entry.getKey()), showEquipType);
                }
            }
        }
        return store;
    }

    private AppearanceStore(Path path) {
        this.path = path;
    }

    /** Go 风格 DSN → 独立小池；缺表自动建（role_appearance），失败仅告警（JSON 仍可用）。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("appearance store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-appearance");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            // 权威建表走 admin/sql/0007；此处兜底与 role_skins 同款（FK CASCADE）。
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_appearance ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "show_equip_type TINYINT UNSIGNED NOT NULL DEFAULT 0,"
                    + "version BIGINT UNSIGNED NOT NULL DEFAULT 1,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id),"
                    + "CONSTRAINT fk_appearance_role FOREIGN KEY (role_id) REFERENCES roles(role_id)"
                    + " ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用。
        }
    }

    /** 无存档返回 null（登录保持客户端默认 0=江湖装，不推送偏好）。 */
    public Integer load(long roleId) {
        if (roleId == 0) {
            return null;
        }
        Integer fromDb = loadFromDatabase(roleId);
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

    private Integer loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT show_equip_type FROM role_appearance WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : null;
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    /** 保存显示偏好（custom 61 切换 / GM set_showequip_type）。 */
    public void save(long roleId, int showEquipType) {
        if (roleId == 0) {
            throw new IllegalArgumentException("appearance store: zero role id");
        }
        if (showEquipType < 0 || showEquipType > 2) {
            throw new IllegalArgumentException("appearance store: invalid show equip type " + showEquipType);
        }
        lock.lock();
        try {
            Integer previous = roles.put(roleId, showEquipType);
            try {
                persist();
                upsertDatabase(roleId, showEquipType);
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

    private void upsertDatabase(long roleId, int showEquipType) {
        if (database == null) {
            return;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_appearance (role_id, show_equip_type, version, updated_at) "
                             + "VALUES (?, ?, 1, UTC_TIMESTAMP(6)) "
                             + "ON DUPLICATE KEY UPDATE show_equip_type = VALUES(show_equip_type), "
                             + "version = version + 1, updated_at = UTC_TIMESTAMP(6)")) {
            statement.setLong(1, roleId);
            statement.setInt(2, showEquipType);
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

    /** GoJson MarshalIndent 兼容输出 + 原子替换。 */
    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", STORE_VERSION);
        if (!roles.isEmpty()) {
            GoJson.Object rolesNode = new GoJson.Object();
            for (Map.Entry<Long, Integer> entry : roles.entrySet()) {
                GoJson.Object roleNode = new GoJson.Object();
                roleNode.field("show_equip_type", entry.getValue());
                rolesNode.raw(Long.toString(entry.getKey()), roleNode.render(2));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".appearance-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write appearance store", e);
        }
    }
}
