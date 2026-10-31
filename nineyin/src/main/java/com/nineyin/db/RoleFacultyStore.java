package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.nineyin.game.FacultyState;
import com.nineyin.game.WuxueLevelInfo;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 修炼/内功快照存取（role_faculty.snapshot JSON，参考服 F 版 exe 同表同格式）。
 * 只托管 FacultyState 管理的键；F 版 exe 写入的扩展键（skill_levels/skill_caps/
 * title_id/owned_titles/character_* /qg_levels/fill_progress）读-合-写原样保留，
 * 保证两套服务端共用一行数据互不破坏。查询失败返回 null，调用方回退 starter 种子。
 */
public final class RoleFacultyStore {

    private static final Logger log = LoggerFactory.getLogger(RoleFacultyStore.class);

    /** game_skills IN 查询分块上限。 */
    private static final int IN_CHUNK = 100;

    private final HikariDataSource dataSource;

    private RoleFacultyStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 仅 MySQL 后端可用；JSON 文件后端返回 null（登录链回退 starter 种子）。 */
    public static RoleFacultyStore from(RoleStore store) {
        if (store instanceof MySqlRoleStore mysql) {
            return new RoleFacultyStore(mysql.dataSourceForTest());
        }
        return null;
    }

    /**
     * 恢复角色修炼状态：读快照 → 解析托管键 → game_skills 目录补 static_data/item_type。
     * 无行/损坏返回 null（调用方回退 {@link FacultyState#bootstrap()}）。
     */
    public FacultyState load(long roleId, WuxueLevelInfo curves) {
        if (roleId == 0) {
            return null;
        }
        JSONObject snapshot = readSnapshot(roleId);
        if (snapshot == null) {
            return null;
        }
        FacultyState state = FacultyState.fromSnapshotJson(snapshot, curves);
        if (state == null) {
            log.warn("role faculty snapshot unparsable role={} keys={}", roleId, snapshot.keySet());
            return null;
        }
        enrichFromCatalog(state);
        // static_data 补齐后按当前等级回填 varprop 特效（五维/上限 Add + 运功 Buff）
        state.refreshEffects(com.nineyin.game.NeigongEffectCatalog.instance());
        log.info("role faculty from db role={} books={} cur={} faculty={} state={} style={} fillSpeed={}",
                roleId, state.books.size(), state.curNeigong, state.faculty,
                state.facultyState, state.facultyStyle, state.fillSpeed);
        return state;
    }

    /** game_skills 内功目录补齐书本 static_data/item_type（GoodsGrid 解析纸面/图标依赖）。 */
    private void enrichFromCatalog(FacultyState state) {
        List<String> ids = new ArrayList<>(state.books.size());
        for (FacultyState.Book book : state.books) {
            if (book.staticData == 0) {
                ids.add(book.configId);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, int[]> catalog = new HashMap<>();
        String sql = "SELECT skill_id, static_data, item_type FROM game_skills "
                + "WHERE skill_type = 'neigong' AND skill_id IN (%s)";
        try (Connection conn = dataSource.getConnection()) {
            for (int from = 0; from < ids.size(); from += IN_CHUNK) {
                List<String> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
                String placeholders = "?" + ",?".repeat(chunk.size() - 1);
                try (PreparedStatement ps = conn.prepareStatement(String.format(sql, placeholders))) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setString(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            catalog.put(rs.getString(1), new int[]{rs.getInt(2), rs.getInt(3)});
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("neigong catalog enrich failed : {}", e.getMessage());
            return;
        }
        int enriched = 0;
        for (FacultyState.Book book : state.books) {
            int[] row = catalog.get(book.configId);
            if (row != null && row[0] > 0) {
                // staticData/itemType 为 final 构造值，绕不开重建：改在 load 重建行
                book.applyCatalog(row[0], row[1]);
                enriched++;
            }
        }
        if (enriched < ids.size()) {
            log.warn("neigong catalog missing {}/{} books", ids.size() - enriched, ids.size());
        }
    }

    /**
     * 回写修炼状态：事务内 SELECT FOR UPDATE 重读快照 → 仅覆盖托管键 → UPSERT。
     * 返回是否成功；失败仅告警（下一轮 dirty 冲刷重试）。
     */
    public boolean save(long roleId, FacultyState state) {
        if (roleId == 0 || state == null) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String existing = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT snapshot FROM role_faculty WHERE role_id = ? FOR UPDATE")) {
                    ps.setLong(1, roleId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existing = rs.getString(1);
                        }
                    }
                }
                String merged = mergeSnapshot(existing, state);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO role_faculty (role_id, snapshot, version) VALUES (?, ?, 1) "
                                + "ON DUPLICATE KEY UPDATE snapshot = VALUES(snapshot), "
                                + "version = version + 1")) {
                    ps.setLong(1, roleId);
                    ps.setString(2, merged);
                    ps.executeUpdate();
                }
                conn.commit();
                state.persistDirty = false;
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("role faculty save failed role={} : {}", roleId, e.getMessage());
            return false;
        }
    }

    /**
     * 现有快照文档 + 托管键合并（保留 F 版 exe 扩展键：skill_levels/skill_caps/
     * title_id/owned_titles/character_* /qg_levels/fill_progress 等）。
     * 现有文档缺失/损坏时从托管键新建。
     */
    static String mergeSnapshot(String existingRaw, FacultyState state) {
        JSONObject doc = null;
        if (existingRaw != null && !existingRaw.isEmpty()) {
            try {
                doc = JSON.parseObject(existingRaw);
            } catch (RuntimeException ignored) {
                doc = null;
            }
        }
        if (doc == null) {
            doc = new JSONObject(true);
        }
        doc.putAll(state.toManagedJson());
        return doc.toJSONString();
    }

    private JSONObject readSnapshot(long roleId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT snapshot FROM role_faculty WHERE role_id = ?")) {
            ps.setLong(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String raw = rs.getString(1);
                return raw == null || raw.isEmpty() ? null : JSON.parseObject(raw);
            }
        } catch (SQLException e) {
            log.warn("role faculty query failed role={} : {}", roleId, e.getMessage());
            return null;
        }
    }

    /** 建表缺失时自愈（幂等；Go 迁移建过的库直接命中 IF NOT EXISTS 空转）。 */
    public boolean ensureSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS role_faculty ("
                    + "role_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,"
                    + "snapshot JSON NOT NULL,"
                    + "version BIGINT UNSIGNED NOT NULL DEFAULT 1,"
                    + "updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
                    + " ON UPDATE CURRENT_TIMESTAMP(6)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            return true;
        } catch (SQLException e) {
            log.warn("role_faculty ensure schema failed : {}", e.getMessage());
            return false;
        }
    }
}
