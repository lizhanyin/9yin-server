package com.nineyin.game.avatar.neigong;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nineyin.util.GoJson;

/** 内功进度存档（data/faculty.json），对齐 Go cmd/protocol-probe/faculty_store.go。 */
public final class FacultyStore {

    private static final int STORE_VERSION = 1;

    private final Path path;
    private final Map<Long, PlayerProgress.Snapshot> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** 三期可选 DB 源（role_faculty.snapshot）：读优先、写同步 best-effort。 */
    private com.zaxxer.hikari.HikariDataSource database;

    public static FacultyStore open(Path path) {
        FacultyStore store = new FacultyStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read faculty store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode faculty store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                store.roles.put(Long.parseLong(entry.getKey()), parseSnapshot((JSONObject) entry.getValue()));
            }
        }
        return store;
    }

    private FacultyStore(Path path) {
        this.path = path;
    }

    private static PlayerProgress.Snapshot parseSnapshot(JSONObject node) {
        List<PlayerProgress.BookSnapshot> books = new ArrayList<>();
        JSONArray booksNode = node.getJSONArray("books");
        if (booksNode != null) {
            for (Object item : booksNode) {
                JSONObject book = (JSONObject) item;
                books.add(new PlayerProgress.BookSnapshot(
                        book.getString("config_id"),
                        book.getIntValue("level"),
                        book.getIntValue("fill"),
                        book.getIntValue("total"),
                        book.getIntValue("power"),
                        book.getIntValue("max_power")));
            }
        }
        Instant lastAdvance = null;
        String lastAdvanceText = node.getString("last_advance_utc");
        if (lastAdvanceText != null && !lastAdvanceText.isEmpty()) {
            lastAdvance = Instant.parse(lastAdvanceText);
        }
        return new PlayerProgress.Snapshot(
                node.getString("cur_neigong"),
                node.getIntValue("faculty"),
                node.getIntValue("faculty_state"),
                node.getIntValue("faculty_style"),
                node.getString("faculty_name"),
                node.getIntValue("fill_speed"),
                lastAdvance,
                books);
    }

    /** Go 风格 DSN（user:pass@tcp(host:port)/db）→ 独立小池；失败仅告警（JSON 仍可用）。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("faculty store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-faculty");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
    }

    public PlayerProgress.Snapshot load(long roleId) {
        if (roleId == 0) {
            return null;
        }
        PlayerProgress.Snapshot fromDb = loadFromDatabase(roleId);
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

    /** role_faculty.snapshot（JSON 超集，宽松解析子集字段）。 */
    private PlayerProgress.Snapshot loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT snapshot FROM role_faculty WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return parseSnapshot(JSON.parseObject(result.getString(1)));
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON（与 GM 目录同策略）
        }
    }

    public void save(long roleId, PlayerProgress.Snapshot value) {
        if (roleId == 0) {
            throw new IllegalArgumentException("faculty store: zero role id");
        }
        lock.lock();
        try {
            PlayerProgress.Snapshot previous = roles.put(roleId, value);
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
    private void upsertDatabase(long roleId, PlayerProgress.Snapshot value) {
        if (database == null) {
            return;
        }
        String snapshot = renderSnapshot(value, 0);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_faculty (role_id, snapshot, version, updated_at) "
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

    /** Go persistLocked：GoJson MarshalIndent 兼容输出 + 原子替换。 */
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
            Path temporary = Files.createTempFile(directory, ".faculty-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write faculty store", e);
        }
    }

    private static String renderSnapshot(PlayerProgress.Snapshot snapshot, int depth) {
        GoJson.Object node = new GoJson.Object();
        node.field("cur_neigong", snapshot.curNeiGong());
        node.field("faculty", snapshot.faculty());
        node.field("faculty_state", snapshot.facultyState());
        node.field("faculty_style", snapshot.facultyStyle());
        node.field("faculty_name", snapshot.facultyName());
        node.field("fill_speed", snapshot.fillSpeed());
        node.field("last_advance_utc", formatInstant(snapshot.lastAdvanceUtc()));
        List<String> books = new ArrayList<>();
        for (PlayerProgress.BookSnapshot book : snapshot.books()) {
            GoJson.Object bookNode = new GoJson.Object();
            bookNode.field("config_id", book.configId());
            bookNode.field("level", book.level());
            bookNode.field("fill", book.fill());
            bookNode.field("total", book.total());
            bookNode.field("power", book.power());
            bookNode.field("max_power", book.maxPower());
            books.add(bookNode.render(depth + 1));
        }
        node.raw("books", GoJson.renderArray(books, depth));
        return node.render(depth);
    }

    /** Go time.Time JSON：RFC3339Nano（UTC Z 后缀）。 */
    private static String formatInstant(Instant value) {
        if (value == null) {
            return "0001-01-01T00:00:00Z";
        }
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}
