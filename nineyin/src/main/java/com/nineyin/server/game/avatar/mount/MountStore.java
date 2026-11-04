package com.nineyin.server.game.avatar.mount;

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
 * 坐骑存档：data/坐骑.json 主档 + MySQL role_mounts(snapshot) 双源
 * （SkinStore 同款：读优先后备、原子替换写、DB 同步 best-effort）。
 *
 * <p>快照形态沿用库内既有档（role 618 官服迁移数据）：JSON 数组，元素
 * {@code {"ConfigID":"MOUNT_HAWK_003","StaticID":41523,"UsedDate":0,
 * "ValidTime":30,"Selected":true}}——字段即 NewRideRec 列镜像（col0=StaticID
 * 坐骑包、col1=UsedDate、col2=ValidTime 有效期、col3=Selected 当前选中）。
 * GM 发放 ValidTime=0（永久口径，真机校准点）。
 */
public final class MountStore {

    private static final int STORE_VERSION = 1;

    /** 单条坐骑（NewRideRec 行镜像；configId 仅服务端便捷字段）。 */
    public record Entry(String configId, int packId, int usedDate, int validTime, boolean selected) {
    }

    /** 单角色坐骑状态（packId 升序去重；至多一条 selected）。 */
    public record State(List<Entry> mounts) {

        public static State empty() {
            return new State(List.of());
        }

        public boolean owns(int packId) {
            return byPack(packId) != null;
        }

        public Entry byPack(int packId) {
            for (Entry entry : mounts) {
                if (entry.packId() == packId) {
                    return entry;
                }
            }
            return null;
        }

        public int currentPack() {
            for (Entry entry : mounts) {
                if (entry.selected()) {
                    return entry.packId();
                }
            }
            return 0;
        }

        /** 保证拥有（不存在则补，ValidTime=0 永久），返回新状态。 */
        public State withOwned(int packId, String configId) {
            if (owns(packId)) {
                return this;
            }
            List<Entry> next = new ArrayList<>(mounts);
            next.add(new Entry(configId == null ? "" : configId, packId, 0, 0, false));
            next.sort(Comparator.comparingInt(Entry::packId));
            return new State(List.copyOf(next));
        }

        /** 切换选中（唯一 selected；packId 不在库中返回原状态）。 */
        public State withSelected(int packId) {
            if (!owns(packId)) {
                return this;
            }
            List<Entry> next = new ArrayList<>(mounts.size());
            for (Entry entry : mounts) {
                next.add(entry.packId() == packId
                        ? new Entry(entry.configId(), entry.packId(), entry.usedDate(),
                                entry.validTime(), true)
                        : new Entry(entry.configId(), entry.packId(), entry.usedDate(),
                                entry.validTime(), false));
            }
            return new State(List.copyOf(next));
        }
    }

    private final Path path;
    private final Map<Long, State> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private com.zaxxer.hikari.HikariDataSource database;

    public static MountStore open(Path path) {
        MountStore store = new MountStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read mount store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode mount store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                State state = new State(readEntries(entry.getValue()));
                if (!state.mounts().isEmpty()) {
                    store.roles.put(Long.parseLong(entry.getKey()), state);
                }
            }
        }
        return store;
    }

    private MountStore(Path path) {
        this.path = path;
    }

    /** Go 风格 DSN → 独立小池；缺表自动建（snapshot 形态，FK 与库内既有档一致）。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("mount store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-mounts");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            // 与库内既有表/0011 迁移同构（snapshot 数组形态）。
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_mounts ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "snapshot JSON NOT NULL,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id),"
                    + "CONSTRAINT fk_role_mounts_role FOREIGN KEY (role_id) REFERENCES roles(role_id)"
                    + " ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用。
        }
    }

    /** 无存档返回 null（登录恢复跳过推送，客户端保持无坐骑）。 */
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
                     "SELECT snapshot FROM role_mounts WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new State(readEntries(JSON.parse(result.getString(1))));
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    /** 保存坐骑状态（GM mount_ride / C2S 骑乘链）。 */
    public void save(long roleId, State state) {
        if (roleId == 0) {
            throw new IllegalArgumentException("mount store: zero role id");
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
                     "INSERT INTO role_mounts (role_id, snapshot, updated_at) "
                             + "VALUES (?, CAST(? AS JSON), UTC_TIMESTAMP(6)) "
                             + "ON DUPLICATE KEY UPDATE snapshot = VALUES(snapshot), "
                             + "updated_at = UTC_TIMESTAMP(6)")) {
            statement.setLong(1, roleId);
            statement.setString(2, writeEntries(state));
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

    /** GoJson MarshalIndent 兼容输出 + 原子替换（roles → roleId → 快照数组）。 */
    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", STORE_VERSION);
        if (!roles.isEmpty()) {
            GoJson.Object rolesNode = new GoJson.Object();
            List<Long> ids = new ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                rolesNode.raw(Long.toString(id), renderEntries(roles.get(id)));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".mounts-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write mount store", e);
        }
    }

    /** 快照数组 JSON 文本（compact，与库内既有档一致）。 */
    private static String writeEntries(State state) {
        if (state.mounts().isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Entry entry : state.mounts()) {
            if (!first) {
                out.append(',');
            }
            out.append("{\"ConfigID\":").append(JSON.toJSONString(entry.configId()))
                    .append(",\"StaticID\":").append(entry.packId())
                    .append(",\"UsedDate\":").append(entry.usedDate())
                    .append(",\"ValidTime\":").append(entry.validTime())
                    .append(",\"Selected\":").append(entry.selected())
                    .append('}');
            first = false;
        }
        return out.append(']').toString();
    }

    /** GoJson 缩进渲染（文件主档可读性）。 */
    private static String renderEntries(State state) {
        List<String> rows = new ArrayList<>(state.mounts().size());
        for (Entry entry : state.mounts()) {
            rows.add("{\"ConfigID\": " + JSON.toJSONString(entry.configId())
                    + ", \"StaticID\": " + entry.packId()
                    + ", \"UsedDate\": " + entry.usedDate()
                    + ", \"ValidTime\": " + entry.validTime()
                    + ", \"Selected\": " + entry.selected() + "}");
        }
        return GoJson.renderArray(rows, 2);
    }

    /** 兼容数组（DB 既有档）与旧 owned/current 对象形态。 */
    private static List<Entry> readEntries(Object node) {
        if (node instanceof JSONArray array) {
            List<Entry> entries = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JSONObject row = array.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                entries.add(new Entry(
                        row.getString("ConfigID"),
                        row.getIntValue("StaticID"),
                        row.getIntValue("UsedDate"),
                        row.getIntValue("ValidTime"),
                        row.getBooleanValue("Selected")));
            }
            return List.copyOf(entries);
        }
        if (node instanceof JSONObject legacy) {
            // 过渡形态 {"owned":[...],"current":N}：无 ConfigID/ValidTime 细节。
            List<Entry> entries = new ArrayList<>();
            int current = legacy.getIntValue("current");
            for (Object id : legacy.getJSONArray("owned")) {
                int pack = ((Number) id).intValue();
                entries.add(new Entry("", pack, 0, 0, pack == current));
            }
            return List.copyOf(entries);
        }
        return List.of();
    }
}
