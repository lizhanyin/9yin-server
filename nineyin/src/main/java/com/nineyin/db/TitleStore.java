package com.nineyin.db;

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
 * 称号存取（game_titles 定义目录 + role_titles 玩家进度）。
 * GM 面板 /api/titles 与 grant/wear/cancel/clear_title 动作的数据源（用户定的权威源：库表）。
 * 仅 MySQL 后端可用；JSON 文件后端 from() 返回 null，GM 动作回退会话内存。
 */
public final class TitleStore {

    private static final Logger log = LoggerFactory.getLogger(TitleStore.class);

    public record TitleDef(int id, String name, int type) {
    }

    private final HikariDataSource dataSource;

    private TitleStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 仅 MySQL 后端可用；JSON 文件后端返回 null。 */
    public static TitleStore from(RoleStore store) {
        if (store instanceof MySqlRoleStore mysql) {
            return new TitleStore(mysql.dataSourceForTest());
        }
        return null;
    }

    /** 定义目录（game_titles 全表，按 title_id 升序）；查询失败返回空表。 */
    public List<TitleDef> catalog() {
        List<TitleDef> out = new ArrayList<>(2400);
        String sql = "SELECT title_id, name, title_type FROM game_titles ORDER BY title_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new TitleDef(rs.getInt(1), rs.getString(2), rs.getShort(3)));
            }
        } catch (SQLException e) {
            log.error("title catalog query failed", e);
        }
        return out;
    }

    /** 面板目录 JSON：[{id,name,type}]；查询失败返回 null（调用方回退 data/称号数据.json）。 */
    public JSONArray catalogJson() {
        List<TitleDef> defs = catalog();
        if (defs.isEmpty()) {
            return null;
        }
        JSONArray out = new JSONArray(defs.size());
        for (TitleDef d : defs) {
            JSONObject r = new JSONObject(true);
            r.put("id", d.id());
            r.put("name", d.name());
            r.put("type", d.type());
            out.add(r);
        }
        return out;
    }

    /** 是否已拥有（role_titles 有行即拥有）。 */
    public boolean owns(long roleId, int titleId) {
        String sql = "SELECT 1 FROM role_titles WHERE role_id=? AND title_id=? LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, roleId);
            ps.setInt(2, titleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("title owns query failed role={} title={}", roleId, titleId, e);
            return false;
        }
    }

    /** 已拥有称号 id 列表。 */
    public List<Integer> owned(long roleId) {
        List<Integer> out = new ArrayList<>();
        String sql = "SELECT title_id FROM role_titles WHERE role_id=? ORDER BY title_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            log.error("title owned query failed role={}", roleId, e);
        }
        return out;
    }

    /** 当前佩戴称号（is_worn=1；无返回 null）。 */
    public Integer worn(long roleId) {
        String sql = "SELECT title_id FROM role_titles WHERE role_id=? AND is_worn=1 LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            log.error("title worn query failed role={}", roleId, e);
            return null;
        }
    }

    /**
     * 发放称号：仅在 game_titles 有定义时插入（type 一并带入），已拥有幂等。
     * 返回 true = 新发放；false = 定义不存在或已拥有（或库错误）。
     */
    public boolean grant(long roleId, int titleId) {
        String sql = "INSERT IGNORE INTO role_titles(role_id, title_id, title_type, is_worn) "
                + "SELECT ?, title_id, title_type, 0 FROM game_titles WHERE title_id=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, roleId);
            ps.setInt(2, titleId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("title grant failed role={} title={}", roleId, titleId, e);
            return false;
        }
    }

    /** 佩戴：清掉其它 is_worn 后置 1（obtained_unix 首次佩戴补秒级时间戳）。 */
    public void wear(long roleId, int titleId) {
        String clear = "UPDATE role_titles SET is_worn=0 WHERE role_id=? AND is_worn=1";
        String wear = "UPDATE role_titles SET is_worn=1, "
                + "obtained_unix=COALESCE(obtained_unix, UNIX_TIMESTAMP()) "
                + "WHERE role_id=? AND title_id=?";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement clearPs = c.prepareStatement(clear);
                 PreparedStatement wearPs = c.prepareStatement(wear)) {
                clearPs.setLong(1, roleId);
                clearPs.executeUpdate();
                wearPs.setLong(1, roleId);
                wearPs.setInt(2, titleId);
                wearPs.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("title wear failed role={} title={}", roleId, titleId, e);
        }
    }

    /** 取消佩戴（is_worn 全部置 0）。 */
    public void cancelWorn(long roleId) {
        String sql = "UPDATE role_titles SET is_worn=0 WHERE role_id=? AND is_worn=1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, roleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("title cancel failed role={}", roleId, e);
        }
    }

    /** 清空该角色全部称号。 */
    public void clear(long roleId) {
        String sql = "DELETE FROM role_titles WHERE role_id=?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, roleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("title clear failed role={}", roleId, e);
        }
    }
}
