package com.nineyin.server.game.avatar.customizing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.util.GoJson;

/**
 * 玩家个性配置（按键/操作习惯）存档：data/customizing.json 主档 + MySQL
 * role_customizing 双源（FacultyStore/MeridianStore 同款：读优先后备、原子替换、
 * DB 同步 best-effort）。
 *
 * <p>配置串协议（52795 抓包钉死）：客户端引擎 customizing_manager 的序列化串，
 * 纯 ASCII+控制字节（0x01-0x65，UTF-8 无损），登录期上传 C2S **107**
 * （SaveConfigToServer，`[107, configStr]`），服务端回发 S2C **705**
 * （on_parser_customizing，`[705, configStr]` → UpdateToConfigObj 灌回配置对象）。
 * 客户端本地不落盘——登录即默认键位并立即上传，服务端不回存档则每次重置
 * （F 端只回显当次上传、不持久化，即 bug.txt「个性设置没修」根因）。
 */
public final class CustomizingStore {

    private static final int STORE_VERSION = 1;
    /** 抓包样本 144B；设置项增多会变长，DB 列 VARBINARY(65535) 兜住。 */
    public static final int MAX_CONFIG_BYTES = 65_535;

    private final Path path;
    private final Map<Long, String> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private com.zaxxer.hikari.HikariDataSource database;

    public static CustomizingStore open(Path path) {
        CustomizingStore store = new CustomizingStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read customizing store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode customizing store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                String config = ((JSONObject) entry.getValue()).getString("config");
                if (config != null && !config.isEmpty()) {
                    store.roles.put(Long.parseLong(entry.getKey()), config);
                }
            }
        }
        return store;
    }

    private CustomizingStore(Path path) {
        this.path = path;
    }

    /** Go 风格 DSN → 独立小池；缺表自动建（role_customizing），失败仅告警（JSON 仍可用）。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("customizing store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-customizing");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            // 列名 customizing_value 对齐存量表（F 数据同步结构，3306/3307 实测一致）；
            // 早于此处的历史版本曾用列名 config，与存量表分叉后 DB 读写全部静默失败。
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_customizing ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "customizing_value VARBINARY(65535) NOT NULL,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用（权威建表走 admin/sql 升级脚本）。
        }
    }

    /** 无存档返回 null（调用方跳过 705 下发，客户端保持默认键位）。 */
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
                     "SELECT customizing_value FROM role_customizing WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                byte[] bytes = result.getBytes(1);
                return bytes == null || bytes.length == 0
                        ? null : new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    /** 保存个性配置串（C2S 107 上传）。 */
    public void save(long roleId, String config) {
        if (roleId == 0) {
            throw new IllegalArgumentException("customizing store: zero role id");
        }
        if (config == null || config.isEmpty()
                || config.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            return; // 空串/超长静默丢弃（不覆盖已有存档）
        }
        lock.lock();
        try {
            String previous = roles.put(roleId, config);
            try {
                persist();
                upsertDatabase(roleId, config);
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

    private void upsertDatabase(long roleId, String config) {
        if (database == null) {
            return;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_customizing (role_id, customizing_value, updated_at) "
                             + "VALUES (?, ?, UTC_TIMESTAMP(6)) "
                             + "ON DUPLICATE KEY UPDATE customizing_value = VALUES(customizing_value), "
                             + "updated_at = UTC_TIMESTAMP(6)")) {
            statement.setLong(1, roleId);
            statement.setBytes(2, config.getBytes(StandardCharsets.UTF_8));
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
            List<Long> ids = new java.util.ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                GoJson.Object roleNode = new GoJson.Object();
                roleNode.field("config", roles.get(id));
                rolesNode.raw(Long.toString(id), roleNode.render(2));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".customizing-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write customizing store", e);
        }
    }
}
