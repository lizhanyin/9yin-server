package com.nineyin.server.world.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 四期面板域 DB 访问（RoleInventoryStore 同策略：失败由调用方回退种子值）：
 * role_titles（佩戴称号，读+事务写）/role_binglu（兵录 12 槽）/role_repute（声望汇总）/
 * role_mounts（坐骑 snapshot）。均为低频小查询，独立小连接池。
 */
public final class PanelDataStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PanelDataStore.class);

    /** 兵录槽进度（bingluIndex 0-11；MaxBingLuLevel=max(curLevel)）。 */
    public record Binglu(int bingluIndex, int curLevel, long fillValue, int maxLevel) {
    }

    private static final Pattern DSN = Pattern.compile(
            "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)(?:\\?(.*))?$");

    private final HikariDataSource dataSource;

    public static PanelDataStore open(String dsn) {
        Matcher matcher = DSN.matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("panel store: invalid MySQL DSN");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8&connectionTimeZone=UTC"
                        + "&connectTimeout=5000&socketTimeout=5000&allowPublicKeyRetrieval=true",
                matcher.group(3), Integer.parseInt(matcher.group(4)), matcher.group(5)));
        config.setUsername(matcher.group(1));
        config.setPassword(matcher.group(2));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setPoolName("nineyin-panel");
        return new PanelDataStore(new HikariDataSource(config));
    }

    private PanelDataStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /** 当前佩戴称号（is_worn=1；无 → 0）。 */
    public int wornTitle(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT title_id FROM role_titles WHERE role_id = ? AND is_worn = 1 "
                             + "ORDER BY obtained_unix DESC LIMIT 1")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? (int) result.getLong(1) : 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("wornTitle query failed: " + e.getMessage(), e);
        }
    }

    /** 佩戴称号（校验已拥有；清旧 is_worn + 置新 + obtained_unix，事务）。返回是否成功。 */
    public boolean wearTitle(long roleId, int titleId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement owned = connection.prepareStatement(
                    "SELECT COUNT(*) FROM role_titles WHERE role_id = ? AND title_id = ?")) {
                owned.setLong(1, roleId);
                owned.setLong(2, titleId);
                try (ResultSet result = owned.executeQuery()) {
                    if (!result.next() || result.getLong(1) <= 0) {
                        connection.rollback();
                        return false;
                    }
                }
            }
            try (PreparedStatement clear = connection.prepareStatement(
                    "UPDATE role_titles SET is_worn = 0 WHERE role_id = ?")) {
                clear.setLong(1, roleId);
                clear.executeUpdate();
            }
            try (PreparedStatement wear = connection.prepareStatement(
                    "UPDATE role_titles SET is_worn = 1, obtained_unix = UNIX_TIMESTAMP(UTC_TIMESTAMP(6)) "
                            + "WHERE role_id = ? AND title_id = ?")) {
                wear.setLong(1, roleId);
                wear.setLong(2, titleId);
                wear.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("wearTitle failed: " + e.getMessage(), e);
        }
    }

    /** 取消佩戴（is_wown 全清）。 */
    public void cancelTitle(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE role_titles SET is_worn = 0 WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("cancelTitle failed: " + e.getMessage(), e);
        }
    }

    /** 兵录 12 槽进度。 */
    public List<Binglu> binglu(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT binglu_index, cur_level, fill_value, max_level FROM role_binglu "
                             + "WHERE role_id = ? ORDER BY binglu_index")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                List<Binglu> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new Binglu(result.getInt(1), result.getInt(2),
                            result.getLong(3), result.getInt(4)));
                }
                return rows;
            }
        } catch (Exception e) {
            throw new IllegalStateException("binglu query failed: " + e.getMessage(), e);
        }
    }

    /** 声望总量（role_repute 各阵营 repute_value 汇总；无行=0）。 */
    public long reputeTotal(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COALESCE(SUM(repute_value), 0) FROM role_repute WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("reputeTotal query failed: " + e.getMessage(), e);
        }
    }

    /** 各阵营声望行（repute_id 字典序 → LinkedHashMap，行序=记录行下发序）。 */
    public java.util.LinkedHashMap<String, Long> reputeRows(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT repute_id, repute_value FROM role_repute WHERE role_id = ? "
                             + "ORDER BY repute_id")) {
            statement.setLong(1, roleId);
            java.util.LinkedHashMap<String, Long> rows = new java.util.LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long value = result.getLong(2);
                    if (value > 0) {
                        rows.put(result.getString(1), value);
                    }
                }
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("reputeRows query failed: " + e.getMessage(), e);
        }
    }

    /** GM 声望落库（逐阵营 upsert；best-effort，异常上抛由调用方 warn）。 */
    public void upsertRepute(long roleId, Map<String, Long> values) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_repute (role_id, repute_id, repute_value) VALUES (?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE repute_value = VALUES(repute_value)")) {
            for (Map.Entry<String, Long> row : values.entrySet()) {
                statement.setLong(1, roleId);
                statement.setString(2, row.getKey());
                statement.setLong(3, row.getValue());
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("upsertRepute failed: " + e.getMessage(), e);
        }
    }

    /**
     * 身份集合（role_origins：origin_id → status，1=已激活 / 2=已领奖；
     * origin_id 升序 → LinkedHashMap，行序 = Origin_Active/Origin_Prized 记录行下发序）。
     */
    public java.util.LinkedHashMap<String, Integer> originRows(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT origin_id, status FROM role_origins WHERE role_id = ? "
                             + "ORDER BY origin_id")) {
            statement.setLong(1, roleId);
            java.util.LinkedHashMap<String, Integer> rows = new java.util.LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.put(String.valueOf(result.getInt(1)), Math.max(1, result.getInt(2)));
                }
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("originRows query failed: " + e.getMessage(), e);
        }
    }

    /** 身份激活落库（INSERT IGNORE 批量，重复激活幂等；best-effort）。 */
    public void insertOrigins(long roleId, List<Integer> originIds) {
        if (originIds.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT IGNORE INTO role_origins (role_id, origin_id, status) VALUES (?, ?, 1)")) {
            for (int originId : originIds) {
                statement.setLong(1, roleId);
                statement.setInt(2, originId);
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("insertOrigins failed: " + e.getMessage(), e);
        }
    }

    /** 身份领奖标记升级（行缺失则补插；status 只进不退）。 */
    public void markOriginPrized(long roleId, int originId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_origins (role_id, origin_id, status) VALUES (?, ?, 2) "
                             + "ON DUPLICATE KEY UPDATE status = 2")) {
            statement.setLong(1, roleId);
            statement.setInt(2, originId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("markOriginPrized failed: " + e.getMessage(), e);
        }
    }

    /** 授予称号所有权（role_titles INSERT IGNORE，重复授予幂等；不动佩戴位）。 */
    public void grantTitle(long roleId, int titleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT IGNORE INTO role_titles (role_id, title_id, title_type, is_worn, "
                             + "obtained_unix) VALUES (?, ?, 1, 0, UNIX_TIMESTAMP(UTC_TIMESTAMP(6)))")) {
            statement.setLong(1, roleId);
            statement.setInt(2, titleId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("grantTitle failed: " + e.getMessage(), e);
        }
    }

    /**
     * 当前坐骑 configId（role_mounts snapshot JSON 的 current/configId/mount 字段，
     * 取不到 → ""）。坐骑库写入端待接，此处容错解析先行。
     */
    public String mount(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT snapshot FROM role_mounts WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return "";
                }
                return mountOf(result.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("mount query failed: " + e.getMessage(), e);
        }
    }

    private static String mountOf(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONObject snapshot = JSON.parseObject(json);
            for (String key : new String[]{"current", "configId", "mount"}) {
                String value = snapshot.getString(key);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return "";
        } catch (RuntimeException e) {
            log.warn("role_mounts snapshot unparsable: {}", e.getMessage());
            return "";
        }
    }
}
