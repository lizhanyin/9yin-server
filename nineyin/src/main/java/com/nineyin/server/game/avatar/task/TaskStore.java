package com.nineyin.server.game.avatar.task;

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
 * 任务存档（data/tasks.json 主档 + MySQL role_tasks.snapshot 双源），
 * 逐行对齐 {@link com.nineyin.server.game.avatar.jingmai.MeridianStore} 的
 * 读优先后备/原子替换/DB best-effort 模式。
 */
public final class TaskStore {

    private static final int STORE_VERSION = 1;

    private final Path path;
    private final Map<Long, PlayerTask.Snapshot> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** 可选 DB 源：读优先、写同步 best-effort（缺表自动建）。 */
    private com.zaxxer.hikari.HikariDataSource database;

    public static TaskStore open(Path path) {
        TaskStore store = new TaskStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read task store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode task store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                store.roles.put(Long.parseLong(entry.getKey()), parseSnapshot((JSONObject) entry.getValue()));
            }
        }
        return store;
    }

    private TaskStore(Path path) {
        this.path = path;
    }

    private static PlayerTask.Snapshot parseSnapshot(JSONObject node) {
        List<PlayerTask.Accepted> accepted = new ArrayList<>();
        JSONArray acceptedNode = node.getJSONArray("accepted");
        if (acceptedNode != null) {
            for (Object item : acceptedNode) {
                JSONObject entry = (JSONObject) item;
                accepted.add(new PlayerTask.Accepted(
                        entry.getIntValue("task_id"),
                        entry.getIntValue("status"),
                        entry.getLongValue("accepted_at")));
            }
        }
        List<Integer> completed = new ArrayList<>();
        JSONArray completedNode = node.getJSONArray("completed");
        if (completedNode != null) {
            for (Object item : completedNode) {
                completed.add(((Number) item).intValue());
            }
        }
        // 进度计数（二期）：taskId → {步骤 TypeID → curnum}；老档缺节点 → 空。
        Map<Integer, Map<Integer, Integer>> progress = new TreeMap<>();
        JSONObject progressNode = node.getJSONObject("progress");
        if (progressNode != null) {
            for (Map.Entry<String, Object> taskEntry : progressNode.entrySet()) {
                Map<Integer, Integer> counts = new TreeMap<>();
                JSONObject countsNode = (JSONObject) taskEntry.getValue();
                if (countsNode != null) {
                    for (Map.Entry<String, Object> stepEntry : countsNode.entrySet()) {
                        counts.put(Integer.parseInt(stepEntry.getKey()),
                                ((Number) stepEntry.getValue()).intValue());
                    }
                }
                progress.put(Integer.parseInt(taskEntry.getKey()), counts);
            }
        }
        return new PlayerTask.Snapshot(List.copyOf(accepted), List.copyOf(completed), Map.copyOf(progress));
    }

    /**
     * Go 风格 DSN → 独立小池；缺表自动建（role_tasks，基线快照表 role_meridians
     * 形态），失败仅告警（JSON 仍可用）。
     */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("task store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-task");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_tasks ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "snapshot JSON NOT NULL,"
                    + "version BIGINT UNSIGNED NOT NULL DEFAULT 1,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id),"
                    + "CONSTRAINT fk_tasks_role FOREIGN KEY (role_id)"
                    + " REFERENCES roles (role_id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用（权威建表走 admin/sql 升级脚本）。
        }
    }

    public PlayerTask.Snapshot load(long roleId) {
        if (roleId == 0) {
            return null;
        }
        PlayerTask.Snapshot fromDb = loadFromDatabase(roleId);
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

    /** role_tasks.snapshot（JSON 超集，宽松解析子集字段）。 */
    private PlayerTask.Snapshot loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT snapshot FROM role_tasks WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return parseSnapshot(JSON.parseObject(result.getString(1)));
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    public void save(long roleId, PlayerTask.Snapshot value) {
        if (roleId == 0) {
            throw new IllegalArgumentException("task store: zero role id");
        }
        lock.lock();
        try {
            PlayerTask.Snapshot previous = roles.put(roleId, value);
            try {
                persist();
                upsertDatabase(roleId, value);
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

    /** DB 同步 best-effort：失败不回滚文件（文件是主档）。 */
    private void upsertDatabase(long roleId, PlayerTask.Snapshot value) {
        if (database == null) {
            return;
        }
        String snapshot = renderSnapshot(value, 0);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_tasks (role_id, snapshot, version, updated_at) "
                             + "VALUES (?, ?, 1, UTC_TIMESTAMP(6)) "
                             + "ON DUPLICATE KEY UPDATE snapshot = VALUES(snapshot), "
                             + "version = version + 1, updated_at = UTC_TIMESTAMP(6)")) {
            statement.setLong(1, roleId);
            statement.setString(2, snapshot);
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

    /** GoJson MarshalIndent 兼容输出 + 原子替换（FacultyStore.persist 同款）。 */
    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", STORE_VERSION);
        if (!roles.isEmpty()) {
            GoJson.Object rolesNode = new GoJson.Object();
            List<Long> ids = new ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                rolesNode.raw(Long.toString(id), renderSnapshot(roles.get(id), 2));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".tasks-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write task store", e);
        }
    }

    private static String renderSnapshot(PlayerTask.Snapshot snapshot, int depth) {
        GoJson.Object node = new GoJson.Object();
        List<String> accepted = new ArrayList<>();
        for (PlayerTask.Accepted entry : snapshot.accepted()) {
            GoJson.Object entryNode = new GoJson.Object();
            entryNode.field("task_id", entry.taskId());
            entryNode.field("status", entry.status());
            entryNode.field("accepted_at", entry.acceptedAtUnixMillis());
            accepted.add(entryNode.render(depth + 1));
        }
        node.raw("accepted", GoJson.renderArray(accepted, depth));
        List<String> completed = new ArrayList<>(snapshot.completed().size());
        for (Integer taskId : snapshot.completed()) {
            completed.add(Integer.toString(taskId));
        }
        node.raw("completed", GoJson.renderArray(completed, depth));
        if (!snapshot.progress().isEmpty()) {
            GoJson.Object progressNode = new GoJson.Object();
            for (Map.Entry<Integer, Map<Integer, Integer>> taskEntry : snapshot.progress().entrySet()) {
                GoJson.Object countsNode = new GoJson.Object();
                for (Map.Entry<Integer, Integer> stepEntry : taskEntry.getValue().entrySet()) {
                    countsNode.field(Long.toString(stepEntry.getKey()), stepEntry.getValue());
                }
                progressNode.raw(Long.toString(taskEntry.getKey()), countsNode.render(depth + 1));
            }
            node.raw("progress", progressNode.render(depth));
        }
        return node.render(depth);
    }
}
