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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 三期只读物品域 DB 访问（GmCatalogDb 同策略：失败抛出由调用方回退 starter）：
 * role_equip_items（穿戴）/role_bag_items（背包，game_items 富化）/
 * role_skills+game_skills（已学技能）/role_shortcuts/role_bagpacks（扩容锚点）。
 */
public final class RoleInventoryStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RoleInventoryStore.class);

    /** 穿戴装备（身体槽位 1 帽/3 衣/4 裤/6 腕/7 腿/8 鞋/22 武器…；propBuilt 词缀串 "1,id;1,id"）。 */
    public record Equip(String configId, int itemType, String equipType, int slot,
                        int hardiness, int maxHardiness, int artPack, String propBuilt) {
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

    /** affixes 列是本服迁移加的；DB 被 F 版参考服数据还原后该列可能不存在，需降级查询。 */
    private static final String EQUIP_SQL_FULL =
            "SELECT config_id, item_type, equip_type, slot, hardiness, max_hardiness, art_pack, affixes "
                    + "FROM role_equip_items WHERE role_id = ? ORDER BY seq";
    private static final String EQUIP_SQL_NO_AFFIXES =
            "SELECT config_id, item_type, equip_type, slot, hardiness, max_hardiness, art_pack "
                    + "FROM role_equip_items WHERE role_id = ? ORDER BY seq";

    public List<Equip> equips(long roleId) {
        try {
            return query(EQUIP_SQL_FULL,
                    statement -> statement.setLong(1, roleId),
                    (result) -> new Equip(result.getString(1), result.getInt(2), result.getString(3),
                            result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7),
                            propBuiltOf(result.getString(8))));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Unknown column 'affixes'")) {
                log.warn("role_equip_items has no affixes column (F-schema db); retry without affixes role={}",
                        roleId);
                return query(EQUIP_SQL_NO_AFFIXES,
                        statement -> statement.setLong(1, roleId),
                        (result) -> new Equip(result.getString(1), result.getInt(2), result.getString(3),
                                result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7), ""));
            }
            throw e;
        }
    }

    /** affixes JSON [{pack_rec,prop_built}] → 协议 PropBuilt 串 "1,id;1,id"（四期装备词缀层）。 */
    private static String propBuiltOf(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONArray affixes = JSON.parseArray(json);
            StringBuilder built = new StringBuilder();
            for (int i = 0; i < affixes.size(); i++) {
                JSONObject affix = affixes.getJSONObject(i);
                if (affix == null) {
                    continue;
                }
                if (built.length() > 0) {
                    built.append(';');
                }
                built.append("1,").append(affix.getIntValue("prop_built"));
            }
            return built.toString();
        } catch (RuntimeException e) {
            return "";
        }
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

    /**
     * 键位主串（role_keybinds.bind_value，官服键位映射 "1,87;2,83;3,65;4,68;…"，87/83/65/68
     * = W/S/A/D）。登录 post-spawn 以玩家属性 ShortcutKey（0x09 表索引 198，type7 string）
     * 下发；不下发则客户端恒为默认 QWES 映射。无行/空串返回 null。
     */
    public String keybind(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT bind_value FROM role_keybinds WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String value = result.getString(1);
                return value == null || value.isEmpty() ? null : value;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("keybind: " + e.getMessage(), e);
        }
    }

    /**
     * 操作模式（role_keybinds.operate_mode，C2S 99 SET_OPERATEMODE：
     * 0=3D WASD / 1=2.5D 点击）。NULL=从未设置（登录不下发，客户端保持本地默认）。
     */
    public Integer operateMode(long roleId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT operate_mode FROM role_keybinds WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                int mode = result.getInt(1);
                return result.wasNull() ? null : mode;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("operateMode: " + e.getMessage(), e);
        }
    }

    /**
     * 键位方案串（C2S 195 引擎上行 `[195, scheme, str]`）：slot 0=当前启用
     * （bind_value，属性 ShortcutKey=198）/1=方案一（shortcut_key1，199）/
     * 2=方案二（shortcut_key2，200）。空/无行回 null。
     */
    public String keybindScheme(long roleId, int slot) {
        String column = switch (slot) {
            case 0 -> "bind_value";
            case 1 -> "shortcut_key1";
            case 2 -> "shortcut_key2";
            default -> throw new IllegalArgumentException("keybind slot " + slot);
        };
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + column + " FROM role_keybinds WHERE role_id = ?")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                String value = result.getString(1);
                return value == null || value.isEmpty() ? null : value;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("keybindScheme: " + e.getMessage(), e);
        }
    }

    /**
     * 保存键位方案串（UPSERT；bind_value NOT NULL 无默认值，首插补空串防整行拒绝；
     * slot 0 目标列即 bind_value，列单写防 specified twice）。
     */
    public void saveKeybindScheme(long roleId, int slot, String value) {
        String column = switch (slot) {
            case 0 -> "bind_value";
            case 1 -> "shortcut_key1";
            case 2 -> "shortcut_key2";
            default -> throw new IllegalArgumentException("keybind slot " + slot);
        };
        String sql = slot == 0
                ? "INSERT INTO role_keybinds (role_id, bind_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE bind_value = VALUES(bind_value)"
                : "INSERT INTO role_keybinds (role_id, bind_value, " + column + ") "
                        + "VALUES (?, '', ?) "
                        + "ON DUPLICATE KEY UPDATE " + column + " = VALUES(" + column + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, roleId);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("saveKeybindScheme: " + e.getMessage(), e);
        }
    }

    /**
     * 保存操作模式（UPSERT；bind_value NOT NULL 无默认值，首插补空串防整行拒绝）。
     */
    public void saveOperateMode(long roleId, int mode) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO role_keybinds (role_id, bind_value, operate_mode) "
                             + "VALUES (?, '', ?) "
                             + "ON DUPLICATE KEY UPDATE operate_mode = VALUES(operate_mode)")) {
            statement.setLong(1, roleId);
            statement.setInt(2, mode);
            statement.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("saveOperateMode: " + e.getMessage(), e);
        }
    }

    /**
     * 快捷栏设置持久化（C2S 200 set_shortcut）：同 shortcut_index 原位更新
     * （主键是 (role_id, seq)，先按 index 定位 seq，无则追加 seq=max+1）。
     */
    public void upsertShortcut(long roleId, int shortcutIndex, String kind, String id) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer seq = null;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT seq FROM role_shortcuts WHERE role_id = ? AND shortcut_index = ? "
                                + "LIMIT 1")) {
                    find.setLong(1, roleId);
                    find.setInt(2, shortcutIndex);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) {
                            seq = result.getInt(1);
                        }
                    }
                }
                if (seq != null) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE role_shortcuts SET kind = ?, id = ?, updated_at = UTC_TIMESTAMP(6) "
                                    + "WHERE role_id = ? AND seq = ?")) {
                        update.setString(1, kind);
                        update.setString(2, id);
                        update.setLong(3, roleId);
                        update.setInt(4, seq);
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO role_shortcuts (role_id, seq, shortcut_index, kind, id, updated_at) "
                                    + "VALUES (?, (SELECT COALESCE(MAX(s.seq), 0) + 1 FROM role_shortcuts s "
                                    + "WHERE s.role_id = ?), ?, ?, ?, UTC_TIMESTAMP(6))")) {
                        insert.setLong(1, roleId);
                        insert.setLong(2, roleId);
                        insert.setInt(3, shortcutIndex);
                        insert.setString(4, kind);
                        insert.setString(5, id);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("upsertShortcut failed: " + e.getMessage(), e);
        }
    }

    /** 快捷栏摘除持久化（C2S 201 remove_shortcut，按 shortcut_index 删行）。 */
    public void deleteShortcut(long roleId, int shortcutIndex) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM role_shortcuts WHERE role_id = ? AND shortcut_index = ?")) {
            statement.setLong(1, roleId);
            statement.setInt(2, shortcutIndex);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("deleteShortcut failed: " + e.getMessage(), e);
        }
    }

    /** GM forget_all_taolu：清角色全部已学武学行（neigong/qinggong 行不动；五件套由登录补齐）。 */
    public void deleteSkills(long roleId, String skillType) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM role_skills WHERE role_id = ? AND skill_type = ?")) {
            statement.setLong(1, roleId);
            statement.setString(2, skillType);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("deleteSkills failed: " + e.getMessage(), e);
        }
    }

    /**
     * GM 发放穿戴（grant_item wearNow）：同槽先删后插（seq 追加）。
     * affixes 列在 F 版参考服还原库可能缺失，写入按读路径同款降级（无词缀列）。
     */
    public void upsertEquip(long roleId, Equip equip) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM role_equip_items WHERE role_id = ? AND slot = ?")) {
                    delete.setLong(1, roleId);
                    delete.setInt(2, equip.slot());
                    delete.executeUpdate();
                }
                boolean affixes = equip.propBuilt() != null && !equip.propBuilt().isEmpty();
                try {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO role_equip_items (role_id, seq, config_id, item_type, equip_type, "
                                    + "hardiness, max_hardiness, slot, art_pack, affixes) "
                                    + "VALUES (?, (SELECT COALESCE(MAX(e.seq), 0) + 1 FROM role_equip_items e "
                                    + "WHERE e.role_id = ?), ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))")) {
                        bindEquip(insert, roleId, equip);
                        insert.setString(10, affixesJson(equip.propBuilt()));
                        insert.executeUpdate();
                    }
                } catch (java.sql.SQLException e) {
                    if (e.getMessage() == null || !e.getMessage().contains("Unknown column 'affixes'")) {
                        throw e;
                    }
                    log.warn("role_equip_items has no affixes column (F-schema db); insert without affixes role={}",
                            roleId);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO role_equip_items (role_id, seq, config_id, item_type, equip_type, "
                                    + "hardiness, max_hardiness, slot, art_pack) "
                                    + "VALUES (?, (SELECT COALESCE(MAX(e.seq), 0) + 1 FROM role_equip_items e "
                                    + "WHERE e.role_id = ?), ?, ?, ?, ?, ?, ?, ?)")) {
                        bindEquip(insert, roleId, equip);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("upsertEquip failed: " + e.getMessage(), e);
        }
    }

    private static void bindEquip(PreparedStatement statement, long roleId, Equip equip) throws Exception {
        statement.setLong(1, roleId);
        statement.setLong(2, roleId);
        statement.setString(3, equip.configId());
        statement.setInt(4, equip.itemType());
        statement.setString(5, equip.equipType() == null ? "" : equip.equipType());
        statement.setInt(6, equip.hardiness());
        statement.setInt(7, equip.maxHardiness());
        statement.setInt(8, equip.slot());
        statement.setInt(9, equip.artPack());
    }

    /** propBuilt "1,id;1,id2" → affixes JSON [{"pack_rec":0,"prop_built":id},…]（读路径反解同构）。 */
    static String affixesJson(String propBuilt) {
        if (propBuilt == null || propBuilt.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("[");
        for (String segment : propBuilt.split(";")) {
            String trimmed = segment.trim();
            int comma = trimmed.indexOf(',');
            try {
                int value = Integer.parseInt(comma >= 0 ? trimmed.substring(comma + 1) : trimmed);
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append("{\"pack_rec\":0,\"prop_built\":").append(value).append('}');
            } catch (NumberFormatException ignored) {
                // 非数值段跳过
            }
        }
        return json.length() > 1 ? json.append(']').toString() : null;
    }

    /** GM 卸下装备（按身体槽位删行）。 */
    public void deleteEquip(long roleId, int slot) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM role_equip_items WHERE role_id = ? AND slot = ?")) {
            statement.setLong(1, roleId);
            statement.setInt(2, slot);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("deleteEquip failed: " + e.getMessage(), e);
        }
    }

    // ---- 移动物品（msgId=30 穿/脱装备，bak 同构事务） ----

    /** 卸下：装备栏(身体槽位) → 背包装备页(指定背包格，view_id=2)。 */
    public boolean moveEquipToBag(long roleId, int equipSlot, int bagSlot) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String configId;
                int itemType;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT config_id, item_type FROM role_equip_items "
                                + "WHERE role_id = ? AND slot = ? FOR UPDATE")) {
                    ps.setLong(1, roleId);
                    ps.setInt(2, equipSlot);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return false;
                        }
                        configId = rs.getString(1);
                        itemType = rs.getInt(2);
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM role_equip_items WHERE role_id = ? AND slot = ?")) {
                    del.setLong(1, roleId);
                    del.setInt(2, equipSlot);
                    del.executeUpdate();
                }
                insertBagRow(conn, roleId, bagSlot, configId, itemType, 1, 2);
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("moveEquipToBag failed role={} slot={}: {}", roleId, equipSlot, e.getMessage());
            return false;
        }
    }

    /** 穿上：背包页(格位，viewCategory=game_items 分类限定源页) → 装备栏(身体槽位)。 */
    public boolean moveBagToEquip(long roleId, int bagSlot, int equipSlot, int viewCategory) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String configId;
                int itemType;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT config_id, item_type FROM role_bag_items "
                                + "WHERE role_id = ? AND slot = ? AND view_id = ? FOR UPDATE")) {
                    ps.setLong(1, roleId);
                    ps.setInt(2, bagSlot);
                    ps.setInt(3, viewCategory);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return false;
                        }
                        configId = rs.getString(1);
                        itemType = rs.getInt(2);
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM role_bag_items WHERE role_id = ? AND slot = ? AND view_id = ?")) {
                    del.setLong(1, roleId);
                    del.setInt(2, bagSlot);
                    del.setInt(3, viewCategory);
                    del.executeUpdate();
                }
                insertEquipRow(conn, roleId, equipSlot, configId, itemType);
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("moveBagToEquip failed role={} bagSlot={}: {}", roleId, bagSlot, e.getMessage());
            return false;
        }
    }

    /** 背包页之间移动（同类格位换页/换位；fromCategory 限定源页防跨页误删）。 */
    public boolean moveBagSlot(long roleId, int fromSlot, int fromCategory,
                               int toSlot, int toViewCategory) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE role_bag_items SET slot = ?, view_id = ? "
                             + "WHERE role_id = ? AND slot = ? AND view_id = ?")) {
            ps.setInt(1, toSlot);
            ps.setInt(2, toViewCategory);
            ps.setLong(3, roleId);
            ps.setInt(4, fromSlot);
            ps.setInt(5, fromCategory);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("moveBagSlot failed role={} {}->{}: {}", roleId, fromSlot, toSlot, e.getMessage());
            return false;
        }
    }

    /** 交换装备栏两件（换装时旧装备让位入背包装备页）。 */
    public boolean swapEquipSlot(long roleId, int equipSlot, int bagSlotForOld) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String oldConfig;
                int oldType;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT config_id, item_type FROM role_equip_items "
                                + "WHERE role_id = ? AND slot = ? FOR UPDATE")) {
                    ps.setLong(1, roleId);
                    ps.setInt(2, equipSlot);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return false;
                        }
                        oldConfig = rs.getString(1);
                        oldType = rs.getInt(2);
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM role_equip_items WHERE role_id = ? AND slot = ?")) {
                    del.setLong(1, roleId);
                    del.setInt(2, equipSlot);
                    del.executeUpdate();
                }
                insertBagRow(conn, roleId, bagSlotForOld, oldConfig, oldType, 1, 2);
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.warn("swapEquipSlot failed role={} slot={}: {}", roleId, equipSlot, e.getMessage());
            return false;
        }
    }

    private static void insertBagRow(Connection conn, long roleId, int slot, String configId,
                                     int itemType, int amount, int viewCategory) throws Exception {
        int seq = nextSeq(conn, roleId, "role_bag_items");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                        + "view_id, name, script) VALUES (?, ?, ?, ?, ?, ?, ?, '', '')")) {
            ps.setLong(1, roleId);
            ps.setInt(2, seq);
            ps.setInt(3, slot);
            ps.setString(4, configId);
            ps.setInt(5, itemType);
            ps.setInt(6, amount);
            ps.setInt(7, viewCategory);
            ps.executeUpdate();
        }
    }

    /** game_items 联表补 equip_type/hardiness/max_hardiness/art_pack（无命中用背包行类型兜底）。 */
    private static void insertEquipRow(Connection conn, long roleId, int slot, String configId,
                                       int itemType) throws Exception {
        int seq = nextSeq(conn, roleId, "role_equip_items");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO role_equip_items (role_id, seq, config_id, item_type, equip_type, "
                        + "hardiness, max_hardiness, slot, art_pack) "
                        + "SELECT ?, ?, g.item_id, g.item_type, g.equip_type, g.hardiness, "
                        + "g.max_hardiness, ?, g.art_pack FROM game_items g WHERE g.item_id = ?")) {
            ps.setLong(1, roleId);
            ps.setInt(2, seq);
            ps.setInt(3, slot);
            ps.setString(4, configId);
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement fb = conn.prepareStatement(
                        "INSERT INTO role_equip_items (role_id, seq, config_id, item_type, equip_type, "
                                + "hardiness, max_hardiness, slot, art_pack) "
                                + "VALUES (?, ?, ?, ?, '', 0, 0, ?, 0)")) {
                    fb.setLong(1, roleId);
                    fb.setInt(2, seq);
                    fb.setString(3, configId);
                    fb.setInt(4, itemType);
                    fb.setInt(5, slot);
                    fb.executeUpdate();
                }
            }
        }
    }

    private static int nextSeq(Connection conn, long roleId, String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) FROM " + table + " WHERE role_id = ?")) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 1;
            }
        }
    }

    /**
     * GM 发放入背包（grant_item 非 wearNow）：找 1..90 首个空槽（扩容页锚点
     * 之外的固定 90 格口径同登录下发），seq 追加。满包抛 IllegalStateException。
     *
     * @return 占用槽位（1..90）
     */
    public int insertBagItem(long roleId, BagItem item) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                java.util.Set<Integer> used = new java.util.HashSet<>();
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT slot FROM role_bag_items WHERE role_id = ?")) {
                    find.setLong(1, roleId);
                    try (ResultSet result = find.executeQuery()) {
                        while (result.next()) {
                            used.add(result.getInt(1));
                        }
                    }
                }
                int slot = item.slot();
                if (slot <= 0) {
                    slot = -1;
                    for (int candidate = 1; candidate <= BAG_SLOTS; candidate++) {
                        if (!used.contains(candidate)) {
                            slot = candidate;
                            break;
                        }
                    }
                    if (slot < 0) {
                        throw new IllegalStateException("bag full (1..90)");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                                + "view_id, name, script) VALUES (?, "
                                + "(SELECT COALESCE(MAX(b.seq), 0) + 1 FROM role_bag_items b "
                                + "WHERE b.role_id = ?), ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setLong(1, roleId);
                    insert.setLong(2, roleId);
                    insert.setInt(3, slot);
                    insert.setString(4, item.configId());
                    insert.setInt(5, item.itemType());
                    insert.setInt(6, item.amount());
                    insert.setInt(7, item.viewId());
                    insert.setString(8, item.name() == null ? "" : item.name());
                    insert.setString(9, item.script() == null ? "" : item.script());
                    insert.executeUpdate();
                }
                connection.commit();
                return slot;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("insertBagItem failed: " + e.getMessage(), e);
        }
    }

    /** 背包槽位容量上限（与登录下发裁剪口径一致）。 */
    private static final int BAG_SLOTS = 90;

    /**
     * 道具消耗（卡片收录/使用/丢弃）：指定分类页槽位数量减 count，减到 0 删行。
     * slot 为页内编号（各页独立 1..90），须带 viewCategory（game_items view_id 分类
     * 1-4）限定，否则跨页同 slot 会扣错行。
     *
     * @return 消耗后剩余数量（删行返回 0）；槽位无行/数量不足返回 -1
     */
    public int consumeBagItem(long roleId, int viewCategory, int slot, int count) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Integer amount = null;
                try (PreparedStatement find = connection.prepareStatement(
                        "SELECT amount FROM role_bag_items WHERE role_id = ? AND slot = ?"
                                + " AND view_id = ?")) {
                    find.setLong(1, roleId);
                    find.setInt(2, slot);
                    find.setInt(3, viewCategory);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) {
                            amount = result.getInt(1);
                        }
                    }
                }
                if (amount == null || amount < count) {
                    connection.commit();
                    return -1;
                }
                if (amount > count) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE role_bag_items SET amount = ? WHERE role_id = ? AND slot = ?"
                                    + " AND view_id = ?")) {
                        update.setInt(1, amount - count);
                        update.setLong(2, roleId);
                        update.setInt(3, slot);
                        update.setInt(4, viewCategory);
                        update.executeUpdate();
                    }
                    connection.commit();
                    return amount - count;
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM role_bag_items WHERE role_id = ? AND slot = ?"
                                + " AND view_id = ?")) {
                    delete.setLong(1, roleId);
                    delete.setInt(2, slot);
                    delete.setInt(3, viewCategory);
                    delete.executeUpdate();
                }
                connection.commit();
                return 0;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("consumeBagItem failed: " + e.getMessage(), e);
        }
    }

    public List<Bagpack> bagpacks(long roleId) {
        return query("SELECT page, anchor, config_id, boost, expires_at FROM role_bagpacks "
                        + "WHERE role_id = ? ORDER BY page, anchor",
                statement -> statement.setLong(1, roleId),
                (result) -> new Bagpack(result.getInt(1), result.getInt(2), result.getString(3),
                        result.getInt(4), getInstant(result, 5)));
    }

    // ------------------------------------------------------------------
    // role_fashion_suits —— 时装页签（FashionSuitRec 行的 DB 源）
    // ------------------------------------------------------------------

    /** 已激活时装套装（suit_id 升序；表缺失/无行返回空）。 */
    public List<String> fashionSuits(long roleId) {
        return query("SELECT suit_id FROM role_fashion_suits WHERE role_id = ? ORDER BY suit_id",
                statement -> statement.setLong(1, roleId),
                result -> result.getString(1));
    }

    /** 批量激活时装套装（INSERT IGNORE 幂等；grant_all_fashion 用）。 */
    public void activateFashionSuits(long roleId, List<String> suitIds) {
        if (suitIds == null || suitIds.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT IGNORE INTO role_fashion_suits (role_id, suit_id) VALUES (?, ?)")) {
            for (String suitId : suitIds) {
                statement.setLong(1, roleId);
                statement.setString(2, suitId);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("activateFashionSuits failed: " + e.getMessage(), e);
        }
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
