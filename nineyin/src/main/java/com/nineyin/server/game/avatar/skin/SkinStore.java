package com.nineyin.server.game.avatar.skin;

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
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.util.GoJson;

/**
 * 风物志皮肤状态存档（风物志=装备的皮肤外观；收集 CardRec 位图源 + 挂载
 * UseCardRec 列）：data/skins.json 主档 + MySQL role_skins 双源
 * （CustomizingStore 同款：读优先后备、原子替换写、DB 同步 best-effort）。
 *
 * <p>风物志在客户端是图鉴按位收集（官方 {@code CardRec[3]}），挂载走
 * {@code UseCardRec[2,2]} 行；F 服 V3 的「风物志挂载切换」由 GM 网页直接
 * 驱动（bug.txt：不用点背包收集），本存档承载服务端权威状态。
 */
public final class SkinStore {

    private static final int STORE_VERSION = 1;

    /** 单角色皮肤状态（collected 已收录升序去重；mounted 挂载顺序保留）。 */
    public record State(List<Integer> collected, List<Integer> mounted) {

        public static State empty() {
            return new State(List.of(), List.of());
        }

        public boolean collectedContains(int skinId) {
            return java.util.Collections.binarySearch(collected, skinId) >= 0;
        }
    }

    private final Path path;
    private final Map<Long, State> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private com.zaxxer.hikari.HikariDataSource database;

    public static SkinStore open(Path path) {
        SkinStore store = new SkinStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read skin store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode skin store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                JSONObject roleNode = ((JSONObject) entry.getValue());
                State state = new State(readIds(roleNode.getJSONArray("collected")),
                        readIds(roleNode.getJSONArray("mounted")));
                if (!state.collected().isEmpty() || !state.mounted().isEmpty()) {
                    store.roles.put(Long.parseLong(entry.getKey()), state);
                }
            }
        }
        return store;
    }

    private SkinStore(Path path) {
        this.path = path;
    }

    /** Go 风格 DSN → 独立小池；缺表自动建（role_skins），失败仅告警（JSON 仍可用）。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("skin store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-skins");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            // 权威建表走 admin/sql/0006；此处兜底与 role_meridians 同款（FK CASCADE）。
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_skins ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "collected JSON NOT NULL,"
                    + "mounted JSON NOT NULL,"
                    + "version BIGINT UNSIGNED NOT NULL DEFAULT 1,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id),"
                    + "CONSTRAINT fk_skins_role FOREIGN KEY (role_id) REFERENCES roles(role_id)"
                    + " ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用。
        }
    }

    /** 无存档返回 null（登录恢复跳过推送，客户端保持未收集）。 */
    public State load(long roleId) {
        if (roleId == 0) {
            return null;
        }
        State fromDb = loadFromDatabase(roleId);
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

    private State loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT collected, mounted FROM role_skins WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new State(readIds(JSON.parseArray(result.getString(1))),
                        readIds(JSON.parseArray(result.getString(2))));
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    /** 保存皮肤状态（GM equip_card/unequip_card/grant_all_cards）。 */
    public void save(long roleId, State state) {
        if (roleId == 0) {
            throw new IllegalArgumentException("skin store: zero role id");
        }
        if (state == null) {
            return;
        }
        lock.lock();
        try {
            State previous = roles.put(roleId, state);
            try {
                persist();
                upsertDatabase(roleId, state);
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

    private void upsertDatabase(long roleId, State state) {
        if (database == null) {
            return;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_skins (role_id, collected, mounted, version, updated_at) "
                             + "VALUES (?, CAST(? AS JSON), CAST(? AS JSON), 1, UTC_TIMESTAMP(6)) "
                             + "ON DUPLICATE KEY UPDATE collected = VALUES(collected), "
                             + "mounted = VALUES(mounted), version = version + 1, "
                             + "updated_at = UTC_TIMESTAMP(6)")) {
            statement.setLong(1, roleId);
            statement.setString(2, writeIds(state.collected()));
            statement.setString(3, writeIds(state.mounted()));
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
            List<Long> ids = new ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                State state = roles.get(id);
                GoJson.Object roleNode = new GoJson.Object();
                roleNode.raw("collected", writeIds(state.collected()));
                roleNode.raw("mounted", writeIds(state.mounted()));
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
            Path temporary = Files.createTempFile(directory, ".skins-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write skin store", e);
        }
    }

    private static List<Integer> readIds(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            ids.add(array.getIntValue(i));
        }
        return List.copyOf(ids);
    }

    /** ID 数组 JSON 文本（升序去重，空集 "[]"）。 */
    private static String writeIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>(ids);
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Integer id : sorted) {
            if (!first) {
                out.append(',');
            }
            out.append(id);
            first = false;
        }
        return out.append(']').toString();
    }
}
