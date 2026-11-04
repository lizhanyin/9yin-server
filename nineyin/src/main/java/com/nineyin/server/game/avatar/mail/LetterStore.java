package com.nineyin.server.game.avatar.mail;

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
 * 邮件存档（data/letters.json 主档 + MySQL role_letters.snapshot 双源），
 * 逐行对齐 {@link com.nineyin.server.game.avatar.task.TaskStore} 的
 * 读优先后备/原子替换/DB best-effort 模式。
 *
 * <p>Letter 即 RecvLetterRec 记录行的服务端镜像（列型/顺序见
 * {@link com.nineyin.server.protocol.RecordMessages#RECORD_RECV_LETTER}）：
 * 宽串三列（发件人/标题/正文）、数字窄串三列（金/银/交易金）、附录 XML 窄串。
 * 领取/已读等变更由调用方构造新 Letter 后整表 save。
 */
public final class LetterStore {

    private static final int STORE_VERSION = 1;

    /** 单封信（RecvLetterRec 16 列中服务端关心的 11 列；其余恒 0/空）。 */
    public record Letter(String serial, int type, String sender, String senderUid,
                         String title, String content, String gold, String silver,
                         String appendixXml, long dateUnixSeconds, boolean readFlag) {

        public Letter withRead(boolean read) {
            return new Letter(serial, type, sender, senderUid, title, content,
                    gold, silver, appendixXml, dateUnixSeconds, read);
        }

        public Letter withAppendix(String xml) {
            return new Letter(serial, type, sender, senderUid, title, content,
                    gold, silver, xml, dateUnixSeconds, readFlag);
        }
    }

    private final Path path;
    private final Map<Long, List<Letter>> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    /** 可选 DB 源：读优先、写同步 best-effort（缺表自动建）。 */
    private com.zaxxer.hikari.HikariDataSource database;

    public static LetterStore open(Path path) {
        LetterStore store = new LetterStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read letter store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode letter store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                JSONObject snapshot = (JSONObject) entry.getValue();
                store.roles.put(Long.parseLong(entry.getKey()),
                        parseLetters(snapshot.getJSONArray("letters")));
            }
        }
        return store;
    }

    private LetterStore(Path path) {
        this.path = path;
    }

    private static List<Letter> parseLetters(JSONArray node) {
        List<Letter> letters = new ArrayList<>();
        for (Object item : node) {
            JSONObject entry = (JSONObject) item;
            letters.add(new Letter(
                    entry.getString("serial"),
                    entry.getIntValue("type"),
                    entry.getString("sender"),
                    entry.getString("sender_uid"),
                    entry.getString("title"),
                    entry.getString("content"),
                    entry.getString("gold"),
                    entry.getString("silver"),
                    entry.getString("appendix_xml"),
                    entry.getLongValue("date_unix_seconds"),
                    entry.getBooleanValue("read_flag")));
        }
        return letters;
    }

    /**
     * Go 风格 DSN → 独立小池；缺表自动建（role_letters，基线快照表 role_tasks
     * 形态），失败仅告警（JSON 仍可用）。
     */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("letter store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-letters");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_letters ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "snapshot JSON NOT NULL,"
                    + "version BIGINT UNSIGNED NOT NULL DEFAULT 1,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id),"
                    + "CONSTRAINT fk_letters_role FOREIGN KEY (role_id)"
                    + " REFERENCES roles (role_id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用（权威建表走 admin/sql 升级脚本）。
        }
    }

    /** 读档：DB 优先，回退 JSON；null = 无档（调用方按空邮箱处理）。 */
    public List<Letter> load(long roleId) {
        if (roleId == 0) {
            return null;
        }
        List<Letter> fromDb = loadFromDatabase(roleId);
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

    /** role_letters.snapshot（JSON 超集，宽松解析子集字段）。 */
    private List<Letter> loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT snapshot FROM role_letters WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                JSONObject node = JSON.parseObject(result.getString(1));
                return parseLetters(node.getJSONArray("letters"));
            }
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    public void save(long roleId, List<Letter> value) {
        if (roleId == 0) {
            throw new IllegalArgumentException("letter store: zero role id");
        }
        lock.lock();
        try {
            List<Letter> previous = roles.put(roleId, List.copyOf(value));
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
    private void upsertDatabase(long roleId, List<Letter> value) {
        if (database == null) {
            return;
        }
        String snapshot = renderSnapshot(value, 0);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_letters (role_id, snapshot, version, updated_at) "
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
            Path temporary = Files.createTempFile(directory, ".letters-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write letter store", e);
        }
    }

    private static String renderSnapshot(List<Letter> letters, int depth) {
        GoJson.Object node = new GoJson.Object();
        List<String> items = new ArrayList<>(letters.size());
        for (Letter letter : letters) {
            GoJson.Object entry = new GoJson.Object();
            entry.field("serial", letter.serial());
            entry.field("type", letter.type());
            entry.field("sender", letter.sender());
            entry.field("sender_uid", letter.senderUid());
            entry.field("title", letter.title());
            entry.field("content", letter.content());
            entry.field("gold", letter.gold());
            entry.field("silver", letter.silver());
            entry.field("appendix_xml", letter.appendixXml());
            entry.field("date_unix_seconds", letter.dateUnixSeconds());
            entry.field("read_flag", letter.readFlag());
            items.add(entry.render(depth + 1));
        }
        node.raw("letters", GoJson.renderArray(items, depth));
        return node.render(depth);
    }
}
