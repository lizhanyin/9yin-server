package com.nineyin.server.game.avatar.panel;

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
 * 兵录存档：data/binglu.json 主档 + MySQL role_binglu 双源
 * （MountStore 同款：读优先后备、原子替换写、DB 同步 best-effort）。
 *
 * <p>数据域 = 记录表 binglu_rank_rec（官方下标 178，列 [2,2,2]=3×u16：
 * CurLevel/FillValue/MaxLevel），客户端 form_binglu.lua 按密集行号=槽位
 * QueryRecord —— 存档恒为 12 行密集（缺行补默认 [0,0,10]，10=binglubaseinfo.ini
 * 全 12 节 MaxLevel；扩展档 bingluexpandinfo 可到 15，GM set 可显式指定）。
 *
 * <p>槽位序（binglubaseinfo.ini 节序，"注意不要修改武器的配置顺序"）：
 * 0 徒手/1 单刀/2 单剑/3 匕首/4 双刀/5 双剑/6 双刺/7 长棍/8 短棍/9 笛(ditze，
 * 客户端 UI 跳过)/10 暗器/11 奇门。
 */
public final class BingluStore {

    private static final int STORE_VERSION = 1;
    private static final int SLOT_COUNT = 12;

    /** 兵录等级默认上限（binglubaseinfo.ini 12 节全为 10；bingluexpandinfo 可扩到 15）。 */
    public static final int DEFAULT_MAX_LEVEL = 10;

    /** 槽位中文名（console.html 兵录下拉同源；9=ditze 客户端 UI 不展示但行保留）。 */
    private static final String[] SLOT_NAMES = {
            "徒手", "单刀", "单剑", "匕首", "双刀", "双剑",
            "双刺", "长棍", "短棍", "笛", "暗器", "奇门"
    };

    /** 单槽进度（binglu_rank_rec 行镜像；fillValue DB 侧 BIGINT，线型 u16 由 GM 校验兜住）。 */
    public record Row(int index, int curLevel, long fillValue, int maxLevel) {

        /** 单行升满的目标等级（无上限行的保守默认 10）。 */
        public int effectiveMax() {
            return maxLevel > 0 ? maxLevel : DEFAULT_MAX_LEVEL;
        }
    }

    /** 无存档/缺行的默认密集 12 行（[0,0,10]——面板文案"登录自动初始化全部 12 行"口径）。 */
    public static List<Row> defaults() {
        List<Row> rows = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            rows.add(new Row(i, 0, 0, DEFAULT_MAX_LEVEL));
        }
        return rows;
    }

    /** 槽位文案（越界回退"槽位N"）。 */
    public static String slotName(int index) {
        return index >= 0 && index < SLOT_NAMES.length ? SLOT_NAMES[index] : ("槽位" + index);
    }

    public static int slotCount() {
        return SLOT_COUNT;
    }

    private final Path path;
    private final Map<Long, List<Row>> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private com.zaxxer.hikari.HikariDataSource database;

    public static BingluStore open(Path path) {
        BingluStore store = new BingluStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read binglu store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode binglu store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                List<Row> rows = readRows(entry.getValue());
                if (!rows.isEmpty()) {
                    store.roles.put(Long.parseLong(entry.getKey()), rows);
                }
            }
        }
        return store;
    }

    private BingluStore(Path path) {
        this.path = path;
    }

    /** Go 风格 DSN → 独立小池；缺表自动建（与 schema 0002 role_binglu 同构）。 */
    public void attachDatabase(String dsn) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("binglu store: invalid MySQL DSN");
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
        config.setPoolName("nineyin-binglu");
        this.database = new com.zaxxer.hikari.HikariDataSource(config);
        try (java.sql.Connection connection = database.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS role_binglu ("
                    + "role_id BIGINT UNSIGNED NOT NULL,"
                    + "binglu_index TINYINT UNSIGNED NOT NULL,"
                    + "cur_level INT NOT NULL DEFAULT 0,"
                    + "fill_value BIGINT NOT NULL DEFAULT 0,"
                    + "max_level INT NOT NULL DEFAULT 0,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (role_id, binglu_index),"
                    + "CONSTRAINT fk_binglu_role FOREIGN KEY (role_id) REFERENCES roles(role_id)"
                    + " ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            // 建表失败不阻断：JSON 主档仍可用。
        }
    }

    /** 12 行密集（DB 行优先补默认，回退 JSON，双空=默认行；兵录域恒有形状）。 */
    public List<Row> load(long roleId) {
        if (roleId == 0) {
            return defaults();
        }
        List<Row> fromDb = loadFromDatabase(roleId);
        if (fromDb != null) {
            return fromDb;
        }
        lock.lock();
        try {
            List<Row> fromFile = roles.get(roleId);
            return fromFile != null ? fromFile : defaults();
        } finally {
            lock.unlock();
        }
    }

    private List<Row> loadFromDatabase(long roleId) {
        if (database == null) {
            return null;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT binglu_index, cur_level, fill_value, max_level FROM role_binglu "
                             + "WHERE role_id = ? ORDER BY binglu_index")) {
            statement.setLong(1, roleId);
            Row[] dense = new Row[SLOT_COUNT];
            int found = 0;
            try (java.sql.ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int index = result.getInt(1);
                    if (index >= 0 && index < SLOT_COUNT) {
                        dense[index] = new Row(index, result.getInt(2),
                                result.getLong(3), result.getInt(4));
                        found++;
                    }
                }
            }
            if (found == 0) {
                // 无行 → JSON 回退（GM 落档期间 DB 曾不可用的补偿路径，MountStore 同款）。
                return null;
            }
            List<Row> rows = new ArrayList<>(SLOT_COUNT);
            for (int i = 0; i < SLOT_COUNT; i++) {
                rows.add(dense[i] != null ? dense[i] : new Row(i, 0, 0, DEFAULT_MAX_LEVEL));
            }
            return List.copyOf(rows);
        } catch (Exception e) {
            return null; // DB 不可用回退 JSON
        }
    }

    /** 保存 12 行进度（GM binglu set/max）。 */
    public void save(long roleId, List<Row> rows) {
        if (roleId == 0) {
            throw new IllegalArgumentException("binglu store: zero role id");
        }
        if (rows == null || rows.size() != SLOT_COUNT) {
            throw new IllegalArgumentException("binglu store: want dense " + SLOT_COUNT + " rows");
        }
        lock.lock();
        try {
            List<Row> previous = roles.put(roleId, List.copyOf(rows));
            try {
                persist();
                upsertDatabase(roleId, rows);
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

    private void upsertDatabase(long roleId, List<Row> rows) {
        if (database == null) {
            return;
        }
        try (java.sql.Connection connection = database.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_binglu (role_id, binglu_index, cur_level, fill_value, max_level) "
                             + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                             + "cur_level = VALUES(cur_level), fill_value = VALUES(fill_value), "
                             + "max_level = VALUES(max_level)")) {
            for (Row row : rows) {
                statement.setLong(1, roleId);
                statement.setInt(2, row.index());
                statement.setInt(3, row.curLevel());
                statement.setLong(4, row.fillValue());
                statement.setInt(5, row.maxLevel());
                statement.executeUpdate();
            }
        } catch (Exception ignored) {
            // 文件已持久化；DB 侧下次登录前可重试。
        }
    }

    public void close() {
        if (database != null) {
            database.close();
        }
    }

    /** GoJson MarshalIndent 兼容输出 + 原子替换（roles → roleId → [[cur,fill,max]×12]）。 */
    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", STORE_VERSION);
        if (!roles.isEmpty()) {
            GoJson.Object rolesNode = new GoJson.Object();
            List<Long> ids = new ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                rolesNode.raw(Long.toString(id), renderRows(roles.get(id)));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".binglu-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write binglu store", e);
        }
    }

    /** GoJson 缩进渲染（文件主档可读性；行=[cur, fill, max] 紧凑三元组）。 */
    private static String renderRows(List<Row> rows) {
        List<String> items = new ArrayList<>(rows.size());
        for (Row row : rows) {
            items.add("[" + row.curLevel() + ", " + row.fillValue() + ", " + row.maxLevel() + "]");
        }
        return GoJson.renderArray(items, 2);
    }

    /** 兼容 [[cur,fill,max]×12] 紧凑形态与 [{Cur,Fill,Max}] 对象形态。 */
    private static List<Row> readRows(Object node) {
        if (!(node instanceof JSONArray array) || array.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < array.size() && rows.size() < SLOT_COUNT; i++) {
            if (array.get(i) instanceof JSONArray triple && triple.size() >= 3) {
                rows.add(new Row(rows.size(), triple.getIntValue(0),
                        triple.getLongValue(1), triple.getIntValue(2)));
            } else if (array.get(i) instanceof JSONObject row) {
                rows.add(new Row(rows.size(), row.getIntValue("Cur"),
                        row.getLongValue("Fill"), row.getIntValue("Max")));
            }
        }
        while (rows.size() < SLOT_COUNT) {
            rows.add(new Row(rows.size(), 0, 0, DEFAULT_MAX_LEVEL));
        }
        return List.copyOf(rows);
    }
}
