package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色物品/技能进度的只读查询（role_equip_items / role_bag_items /
 * role_skill_progress，由 data-import 从原服 equip.json / skills.json 导入）。
 * 登录链用它替代硬编码的起始装备/技能；任何查询失败返回空列表，
 * 调用方回退到 starter 集合，不影响登录。
 */
public final class RoleInventoryStore {

    private static final Logger log = LoggerFactory.getLogger(RoleInventoryStore.class);

    /** 已穿戴装备（view 1 纸娃娃，slot 为身体槽位；propBuilt 为词缀串 "1,id;1,id"）。 */
    public record Equipped(int slot, String configId, int itemType, String equipType,
                           int hardiness, int maxHardiness, String propBuilt) {
    }

    /** 背包物品（viewId 为背包页分类：1道具 2装备 3材料 4任务）。 */
    public record Bagged(int slot, String configId, int itemType, int amount,
                         int viewId, String name) {
    }

    /** 已修炼技能（extra 携带 static_data/item_type/cool_down_time）。 */
    public record SkillRow(String configId, int level, int maxLevel, long fill, long total,
                           int staticData, int itemType, int cooldownMs) {
    }

    /** 快捷栏位（kind: skill/item/func/zhenfa）。 */
    public record Shortcut(int index, String kind, String id) {
    }

    private final HikariDataSource dataSource;

    private RoleInventoryStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 仅 MySQL 后端可用；JSON 文件后端返回 null（登录链回退 starter 集合）。 */
    public static RoleInventoryStore from(RoleStore store) {
        if (store instanceof MySqlRoleStore mysql) {
            return new RoleInventoryStore(mysql.dataSourceForTest());
        }
        return null;
    }

    public List<Equipped> equipment(long roleId) {
        List<Equipped> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT slot, config_id, item_type, equip_type, hardiness, max_hardiness, affixes "
                             + "FROM role_equip_items WHERE role_id = ? ORDER BY seq")) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Equipped(rs.getInt(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), rs.getInt(5), rs.getInt(6),
                            propBuiltOf(rs.getString(7))));
                }
            }
        } catch (SQLException e) {
            log.warn("equipment query failed role={} : {}", roleId, e.getMessage());
        }
        return out;
    }

    /** affixes JSON [{pack_rec,prop_built}] → 参考实现的 PropBuilt 串 "1,id;1,id"。 */
    private static String propBuiltOf(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JSONArray affixes = JSON.parseArray(json);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < affixes.size(); i++) {
                JSONObject a = affixes.getJSONObject(i);
                if (a == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append("1,").append(a.getIntValue("prop_built"));
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public List<Bagged> bag(long roleId) {
        List<Bagged> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT slot, config_id, item_type, amount, view_id, name "
                             + "FROM role_bag_items WHERE role_id = ? ORDER BY seq")) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Bagged(rs.getInt(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5), rs.getString(6)));
                }
            }
        } catch (SQLException e) {
            log.warn("bag query failed role={} : {}", roleId, e.getMessage());
        }
        return out;
    }

    /**
     * 修炼技能（仅 wuxue 套路技能——View 40 容器只放套路；内功/轻功走各自
     * 容器 View 43/46，数字 id 脏行由联表自然过滤）。按 config_id 排序
     * 保证确定性；limit 截断——官服存档里 GM 角色可能带全表 1.7 万条，
     * 客户端视图容器按设计上限 3000 条。
     */
    public List<SkillRow> skills(long roleId, int limit) {
        List<SkillRow> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT p.skill_config_id, p.level, p.max_level, p.fill, p.total, p.extra "
                             + "FROM role_skill_progress p "
                             + "JOIN game_skills g ON g.skill_id = p.skill_config_id "
                             + "AND g.skill_type = 'wuxue' "
                             + "WHERE p.role_id = ? "
                             + "ORDER BY p.skill_config_id LIMIT " + Math.max(0, limit))) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject extra = parseExtra(rs.getString(6));
                    out.add(new SkillRow(rs.getString(1), rs.getInt(2), rs.getInt(3),
                            rs.getLong(4), rs.getLong(5),
                            extra.getIntValue("static_data"),
                            extra.getIntValue("item_type"),
                            extra.getIntValue("cool_down_time")));
                }
            }
        } catch (SQLException e) {
            log.warn("skills query failed role={} : {}", roleId, e.getMessage());
        }
        return out;
    }

    /** 快捷栏位（按导入 seq 顺序）。 */
    public List<Shortcut> shortcuts(long roleId) {
        List<Shortcut> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT shortcut_index, kind, id FROM role_shortcuts "
                             + "WHERE role_id = ? ORDER BY seq")) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Shortcut(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            log.warn("shortcuts query failed role={} : {}", roleId, e.getMessage());
        }
        return out;
    }

    // ---- 移动物品（msgId=30 装卸装备） ----

    /** 卸下：装备栏(身体槽位) → 背包装备页(指定背包格)。 */
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
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.warn("moveEquipToBag failed role={} slot={}: {}", roleId, equipSlot, e.getMessage());
            return false;
        }
    }

    /** 穿上：背包页(格位，viewCategory=game_items 分类) → 装备栏(身体槽位)。 */
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
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
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
        } catch (SQLException e) {
            log.warn("moveBagSlot failed role={} {}->{}: {}", roleId, fromSlot, toSlot, e.getMessage());
            return false;
        }
    }

    /**
     * 发放物品入包（商城购买/奖励发放）：item_type 与落页由 game_items 统一表驱动
     * （equip_type 非空 → 装备页 2，否则道具页 1），分配目标页第一个空格（1..90）。
     * 返回 null 且 fail="missing"：game_items 无此 config（专用 store 的货品，调用方走 pending 路由）；
     * 返回 null 且 fail="full"：目标页无空格。
     */
    public record BagAdd(int slot, int view, String fail) {

        static BagAdd missing() {
            return new BagAdd(0, 0, "missing");
        }

        static BagAdd full() {
            return new BagAdd(0, 0, "full");
        }
    }

    public BagAdd addBagItem(long roleId, String configId, int amount) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int itemType;
                int view;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT item_type, IF(equip_type IS NULL OR equip_type = '', 1, 2) "
                                + "FROM game_items WHERE item_id = ?")) {
                    ps.setString(1, configId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return BagAdd.missing();
                        }
                        itemType = rs.getInt(1);
                        view = rs.getInt(2);
                    }
                }
                int slot = freeBagSlot(conn, roleId, view);
                if (slot <= 0) {
                    return BagAdd.full();
                }
                insertBagRow(conn, roleId, slot, configId, itemType, amount, view);
                conn.commit();
                return new BagAdd(slot, view, null);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.warn("addBagItem failed role={} config={}: {}", roleId, configId, e.getMessage());
            return BagAdd.missing();
        }
    }

    /** 目标页第一个空格（1..90；无空位返回 0）。 */
    private static int freeBagSlot(Connection conn, long roleId, int view) throws SQLException {
        boolean[] used = new boolean[91];
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT slot FROM role_bag_items WHERE role_id = ? AND view_id = ?")) {
            ps.setLong(1, roleId);
            ps.setInt(2, view);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int s = rs.getInt(1);
                    if (s > 0 && s < used.length) {
                        used[s] = true;
                    }
                }
            }
        }
        for (int i = 1; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return 0;
    }

    /** 交换装备栏两件（换装时旧装备让位）。 */
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
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.warn("swapEquipSlot failed role={} slot={}: {}", roleId, equipSlot, e.getMessage());
            return false;
        }
    }

    private static void insertBagRow(Connection conn, long roleId, int slot, String configId,
                                     int itemType, int amount, int viewCategory) throws SQLException {
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

    private static void insertEquipRow(Connection conn, long roleId, int slot, String configId,
                                       int itemType) throws SQLException {
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
                // game_items 无此 config（少见）：用背包行的类型兜底
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

    private static int nextSeq(Connection conn, long roleId, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) FROM " + table + " WHERE role_id = ?")) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 1;
            }
        }
    }

    private static JSONObject parseExtra(String json) {
        if (json == null || json.isEmpty()) {
            return new JSONObject();
        }
        try {
            JSONObject o = JSON.parseObject(json);
            return o == null ? new JSONObject() : o;
        } catch (RuntimeException e) {
            return new JSONObject();
        }
    }
}
