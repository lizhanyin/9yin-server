package com.nineyin.server.gm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GM 面板目录数据的 MySQL 数据源，对应 GmCatalog 的 data/ 文件源：
 * game_cards（卡牌/发型卡）、game_identity_suits（身份装）、game_gift_boxes（礼包内容）、
 * game_meridians（经脉）、game_titles（称号）、server_bots（假人）、game_origins（出身）。
 *
 * <p>任一来源查询失败（表缺失/连接异常）返回 null，GmCatalog 回退 data/ 文件；
 * 查询成功则原样返回（空集表示已加载 0 条）。方法可覆写以便无库单测打桩。
 */
public class GmCatalogDb {

    private static final Logger log = LoggerFactory.getLogger(GmCatalogDb.class);

    /** 与 role 仓库一致的 Go 风格 DSN：user:pass@tcp(host:port)/db。 */
    private static final Pattern DSN = Pattern.compile("^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)(?:\\?(.*))?$");

    private final HikariDataSource dataSource;

    /** 解析 DSN 建独立小连接池（GM 目录懒加载一次性读，池无需大）。 */
    public static GmCatalogDb open(String dsn) {
        Matcher matcher = DSN.matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("gm catalog: invalid MySQL DSN");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8&connectionTimeZone=UTC"
                        + "&connectTimeout=5000&socketTimeout=5000&allowPublicKeyRetrieval=true",
                matcher.group(3), Integer.parseInt(matcher.group(4)), matcher.group(5)));
        config.setUsername(matcher.group(1));
        config.setPassword(matcher.group(2));
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setPoolName("nineyin-gm-catalog");
        return new GmCatalogDb(new HikariDataSource(config));
    }

    /** dataSource 为 null 时所有来源返回 null（单测桩基类用）。 */
    GmCatalogDb(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------------
    // game_cards —— /api/cards、/api/hair-cards
    // ------------------------------------------------------------------

    /** 全量卡牌（{id,name,config_id,category}，card_id 升序）。 */
    public JSONArray cards() {
        return queryCards(null);
    }

    /** 发型卡：模型含 head 或名称含 发型/hair；语义对齐 GmCatalog 文件版过滤。 */
    public JSONArray hairCards() {
        return queryCards("WHERE female_model_data LIKE '%head%' OR male_model_data LIKE '%head%'"
                + " OR name LIKE '%发型%' OR name LIKE '%hair%'");
    }

    private JSONArray queryCards(String where) {
        return queryJson("cards", "SELECT card_id, name, config_id, female_model_data, male_model_data"
                + " FROM game_cards" + (where == null ? "" : " " + where) + " ORDER BY card_id", rs -> {
            JSONObject o = new JSONObject(true);
            o.put("id", rs.getLong(1));
            o.put("name", orEmpty(rs.getString(2)));
            o.put("config_id", rs.getInt(3));
            String model = rs.getString(4);
            if (model == null || model.isEmpty()) {
                model = rs.getString(5);
            }
            o.put("category", modelCategory(model));
            return o;
        });
    }

    // ------------------------------------------------------------------
    // game_identity_suits —— /api/fashion-catalog（分组在 GmCatalog 做）
    // ------------------------------------------------------------------

    /** 身份装原始行：{configId,name,needSex}，need_sex、suit_id 升序。 */
    public JSONArray identitySuits() {
        return queryJson("identity_suits", "SELECT suit_id, name, need_sex FROM game_identity_suits"
                + " ORDER BY need_sex, suit_id", rs -> {
            JSONObject o = new JSONObject(true);
            o.put("configId", rs.getString(1));
            o.put("name", orEmpty(rs.getString(2)));
            o.put("needSex", rs.getInt(3));
            return o;
        });
    }

    // ------------------------------------------------------------------
    // game_gift_boxes —— /api/giftboxes 的 hasContent 判定
    // ------------------------------------------------------------------

    /** 有内容配置的礼包 ID 集合（box_id 升序去重）。 */
    public Set<String> giftBoxIds() {
        return query("gift_boxes", "SELECT DISTINCT box_id FROM game_gift_boxes ORDER BY box_id", rs -> rs.getString(1),
                rows -> {
                    Set<String> ids = new LinkedHashSet<>();
                    rows.forEach(ids::add);
                    return ids;
                });
    }

    // ------------------------------------------------------------------
    // game_meridians —— /api/meridians
    // ------------------------------------------------------------------

    /** 经脉：{id,name,defaultMax}，meridian_id 升序。 */
    public JSONArray meridians() {
        return queryJson("meridians", "SELECT meridian_id, name, max_level FROM game_meridians"
                + " ORDER BY meridian_id", rs -> {
            JSONObject o = new JSONObject(true);
            o.put("id", rs.getString(1));
            o.put("name", orEmpty(rs.getString(2)));
            o.put("defaultMax", rs.getInt(3));
            return o;
        });
    }

    // ------------------------------------------------------------------
    // game_titles —— /api/titles
    // ------------------------------------------------------------------

    /** 称号：{id,name,type}，title_id 升序。 */
    public JSONArray titles() {
        return queryJson("titles", "SELECT title_id, name, title_type FROM game_titles ORDER BY title_id", rs -> {
            JSONObject o = new JSONObject(true);
            o.put("id", rs.getLong(1));
            o.put("name", orEmpty(rs.getString(2)));
            o.put("type", rs.getInt(3));
            return o;
        });
    }

    // ------------------------------------------------------------------
    // server_bots —— /api/bots（JSON 列原样还原为 data/bots.json 条目形状）
    // ------------------------------------------------------------------

    /** 假人条目（bot_id 升序）：{id,key,name,enabled,revision,scene,position,equipment,appearance}。 */
    public JSONArray bots() {
        return queryJson("bots", "SELECT bot_id, bot_key, name, enabled, revision, scene, position,"
                + " equipment, appearance FROM server_bots ORDER BY bot_id", rs -> {
            JSONObject o = new JSONObject(true);
            o.put("id", rs.getLong(1));
            o.put("key", rs.getString(2));
            o.put("name", orEmpty(rs.getString(3)));
            o.put("enabled", rs.getShort(4) != 0);
            o.put("revision", rs.getLong(5));
            putParsed(o, "scene", rs.getString(6));
            putParsed(o, "position", rs.getString(7));
            putParsed(o, "equipment", rs.getString(8));
            putParsed(o, "appearance", rs.getString(9));
            return o;
        });
    }

    // ------------------------------------------------------------------
    // game_origins —— /api/origin/status
    // ------------------------------------------------------------------

    /** 出身总数；不可用返回 null。 */
    public Long originCount() {
        return query("origin_count", "SELECT COUNT(*) FROM game_origins",
                rs -> rs.getLong(1), rows -> rows.isEmpty() ? null : rows.get(0));
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** 行映射为 JSONObject 列表；dataSource 缺失或 SQL 失败返回 null（调用方回退文件）。 */
    private JSONArray queryJson(String source, String sql, RowMapper<JSONObject> mapper) {
        List<JSONObject> rows = query(source, sql, mapper, List::copyOf);
        if (rows == null) {
            return null;
        }
        JSONArray out = new JSONArray();
        out.addAll(rows);
        return out;
    }

    private <T, R> R query(String source, String sql, RowMapper<T> mapper,
                           java.util.function.Function<List<T>, R> finish) {
        if (dataSource == null) {
            return null;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return finish.apply(rows);
        } catch (SQLException e) {
            log.warn("gm catalog db [{}] failed, fallback to files: {} ({})", source, e.getSQLState(), e.getMessage());
            return null;
        }
    }

    private static void putParsed(JSONObject o, String field, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            o.put(field, JSON.parse(json));
        } catch (RuntimeException e) {
            log.warn("gm catalog db bots.{} parse failed: {}", field, e.getMessage());
        }
    }

    /** 与 GmCatalog 文件版一致：取模型串第一个逗号前的类别（如 Weapon,Hat）。 */
    static String modelCategory(String modelData) {
        if (modelData == null || modelData.isEmpty()) {
            return "";
        }
        int comma = modelData.indexOf(',');
        return comma > 0 ? modelData.substring(0, comma) : modelData;
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
