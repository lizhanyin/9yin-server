package com.nineyin.server.world.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 三期只读物品域 DB 访问（GmCatalogDb 同策略：失败抛出由调用方回退 starter）：
 * role_equip_items（穿戴）/role_bag_items（背包，game_items 富化）/
 * role_skills+game_skills（已学技能）/role_shortcuts/role_bagpacks（扩容锚点）。
 */
public final class RoleInventoryStore implements AutoCloseable {

    /** 穿戴装备（身体槽位 1 帽/3 衣/4 裤/6 腕/7 腿/8 鞋/22 武器…）。 */
    public record Equip(String configId, int itemType, String equipType, int slot,
                        int hardiness, int maxHardiness, int artPack) {
    }

    /** 背包行（slot=真实槽位 1..90；viewId 决定进哪个视图页）。 */
    public record BagItem(int slot, String configId, int itemType, int amount, int viewId,
                          String name, String equipType, int colorLevel, String script,
                          Integer anchorSlot, Instant expiresAt, Integer artPack,
                          int hardiness, int maxHardiness) {
    }

    /** 已学技能（skillType: neigong/wuxue/qinggong）。 */
    public record Skill(String skillId, String skillType, int staticData, int itemType) {
    }

    /** 快捷栏行（shortcutIndex 0 基；kind: skill/item/func/zhenfa）。 */
    public record Shortcut(int shortcutIndex, String kind, String id) {
    }

    /** 扩容包锚点（page 1..4；boost=扩容格数；expiresAt 永久=9999-12-31）。 */
    public record Bagpack(int page, int anchor, String configId, int boost, Instant expiresAt) {
    }

    private static final Pattern DSN = Pattern.compile(
            "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)(?:\\?(.*))?$");

    private final HikariDataSource dataSource;

    public static RoleInventoryStore open(String dsn) {
        Matcher matcher = DSN.matcher(dsn.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("inventory store: invalid MySQL DSN");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8&connectionTimeZone=UTC"
                        + "&connectTimeout=5000&socketTimeout=5000&allowPublicKeyRetrieval=true",
                matcher.group(3), Integer.parseInt(matcher.group(4)), matcher.group(5)));
        config.setUsername(matcher.group(1));
        config.setPassword(matcher.group(2));
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setPoolName("nineyin-inventory");
        return new RoleInventoryStore(new HikariDataSource(config));
    }

    private RoleInventoryStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void close() {
        dataSource.close();
    }

    /** 角色是否有任何 DB 物品域数据（DB 模式判定；异常=false 回退 starter）。 */
    public boolean hasRoleData(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT (SELECT COUNT(*) FROM role_equip_items WHERE role_id=?) "
                             + "+ (SELECT COUNT(*) FROM role_bag_items WHERE role_id=?) "
                             + "+ (SELECT COUNT(*) FROM role_skills WHERE role_id=?) "
                             + "+ (SELECT COUNT(*) FROM role_shortcuts WHERE role_id=?) AS total")) {
            statement.setLong(1, roleId);
            statement.setLong(2, roleId);
            statement.setLong(3, roleId);
            statement.setLong(4, roleId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public List<Equip> equips(long roleId) {
        return query("SELECT config_id, item_type, equip_type, slot, hardiness, max_hardiness, art_pack "
                        + "FROM role_equip_items WHERE role_id = ? ORDER BY seq",
                statement -> statement.setLong(1, roleId),
                (result) -> new Equip(result.getString(1), result.getInt(2), result.getString(3),
                        result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7)));
    }

    /** 背包行 + game_items 富化（color_level/script 缺省时补目录值）。 */
    public List<BagItem> bagItems(long roleId) {
        List<BagItem> raw = query(
                "SELECT slot, config_id, item_type, amount, view_id, name, equip_type, color_level, "
                        + "script, anchor_slot, expires_at, art_pack, hardiness, max_hardiness "
                        + "FROM role_bag_items WHERE role_id = ? ORDER BY seq",
                statement -> statement.setLong(1, roleId),
                (result) -> new BagItem(result.getInt(1), result.getString(2), result.getInt(3),
                        result.getInt(4), result.getInt(5), result.getString(6), result.getString(7),
                        result.getInt(8), result.getString(9), getInteger(result, 10),
                        getInstant(result, 11), getInteger(result, 12),
                        result.getInt(13), result.getInt(14)));
        if (raw.isEmpty()) {
            return raw;
        }
        Map<String, String[]> catalog = itemCatalog(raw);
        List<BagItem> enriched = new ArrayList<>(raw.size());
        for (BagItem item : raw) {
            String[] info = catalog.get(item.configId());
            if (info != null) {
                enriched.add(new BagItem(item.slot(), item.configId(),
                        item.itemType() != 0 ? item.itemType() : parseInt(info[0], 0),
                        item.amount(), item.viewId() != 0 ? item.viewId() : parseInt(info[1], 0),
                        item.name() != null && !item.name().isEmpty() ? item.name() : info[2],
                        item.equipType() != null && !item.equipType().isEmpty() ? item.equipType() : info[3],
                        item.colorLevel(), item.script(), item.anchorSlot(), item.expiresAt(),
                        item.artPack(), item.hardiness(), item.maxHardiness()));
            } else {
                enriched.add(item);
            }
        }
        return enriched;
    }

    /** configId → [item_type, view_id, name, equip_type]（一次 IN 批查 game_items）。 */
    private Map<String, String[]> itemCatalog(List<BagItem> items) {
        Map<String, String[]> catalog = new HashMap<>();
        List<String> ids = items.stream().map(BagItem::configId).distinct().toList();
        for (int start = 0; start < ids.size(); start += 200) {
            List<String> chunk = ids.subList(start, Math.min(start + 200, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT item_id, item_type, view_id, item_name, equip_type FROM game_items "
                                 + "WHERE item_id IN (" + placeholders + ")")) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        catalog.put(result.getString(1), new String[]{
                                result.getString(2), result.getString(3),
                                result.getString(4), result.getString(5)});
                    }
                }
            } catch (Exception ignored) {
                // 富化失败保留 DB 行原值。
            }
        }
        return catalog;
    }

    public List<Skill> skills(long roleId, String skillType) {
        return query("SELECT s.skill_id, g.static_data, g.item_type FROM role_skills s "
                        + "JOIN game_skills g ON g.skill_id = s.skill_id "
                        + "WHERE s.role_id = ? AND s.skill_type = ? ORDER BY s.skill_id",
                statement -> {
                    statement.setLong(1, roleId);
                    statement.setString(2, skillType);
                },
                (result) -> new Skill(result.getString(1), skillType,
                        result.getInt(2), result.getInt(3)));
    }

    public List<Shortcut> shortcuts(long roleId) {
        return query("SELECT shortcut_index, kind, id FROM role_shortcuts WHERE role_id = ? "
                        + "ORDER BY shortcut_index",
                statement -> statement.setLong(1, roleId),
                (result) -> new Shortcut(result.getInt(1), result.getString(2), result.getString(3)));
    }

    public List<Bagpack> bagpacks(long roleId) {
        return query("SELECT page, anchor, config_id, boost, expires_at FROM role_bagpacks "
                        + "WHERE role_id = ? ORDER BY page, anchor",
                statement -> statement.setLong(1, roleId),
                (result) -> new Bagpack(result.getInt(1), result.getInt(2), result.getString(3),
                        result.getInt(4), getInstant(result, 5)));
    }

    interface StatementBinder {
        void bind(PreparedStatement statement) throws Exception;
    }

    interface RowMapper<T> {
        T map(ResultSet result) throws Exception;
    }

    private <T> List<T> query(String sql, StatementBinder binder, RowMapper<T> mapper) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(mapper.map(result));
                }
                return rows;
            }
        } catch (Exception e) {
            throw new IllegalStateException("inventory query failed: " + e.getMessage(), e);
        }
    }

    private static Integer getInteger(ResultSet result, int column) throws java.sql.SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Instant getInstant(ResultSet result, int column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
