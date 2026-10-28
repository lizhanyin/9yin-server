package com.nineyin.tools.dataimport.importer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 角色数据导入器集合：data/ 各文件的 roles{...} → role_* 表。
 * 只导入 roles 表中已存在的角色（配合 roles 引导导入，其余键为假人/占位，计数跳过）。
 * roles 的值可能是对象/数组/字符串，因此按原始值透传给各导入器。
 */
public final class RoleDataImporters {

    private RoleDataImporters() {
    }

    public static List<Importer> all() {
        List<Importer> list = new ArrayList<>();
        list.add(ConfigImporters.simple("vip", "vip.json → role_vip", RoleDataImporters::vip));
        list.add(ConfigImporters.simple("mails", "mail.json → role_mails", RoleDataImporters::mails));
        list.add(ConfigImporters.simple("lifejob", "lifejob.json → role_lifejob_state/role_lifejob_jobs",
                RoleDataImporters::lifejob));
        list.add(ConfigImporters.simple("binglu", "binglu.json → role_binglu", RoleDataImporters::binglu));
        list.add(ConfigImporters.simple("story-tasks", "story_tasks.json → role_story_tasks",
                RoleDataImporters::storyTasks));
        list.add(ConfigImporters.simple("homepoints",
                "homepoints.json → role_homepoints/role_homepoint_state/role_homepoint_hires",
                RoleDataImporters::homepoints));
        list.add(ConfigImporters.simple("clone-usage", "clone.json → role_clone_usage", RoleDataImporters::cloneUsage));
        list.add(ConfigImporters.simple("wuji", "武技数据.json → role_wuji_learned/unlocked/worn",
                RoleDataImporters::wuji));
        list.add(ConfigImporters.simple("fashion-suits", "新版服装数据.json → role_fashion_suits",
                RoleDataImporters::fashionSuits));
        list.add(ConfigImporters.simple("role-titles", "称号数据.json roles → role_titles", RoleDataImporters::roleTitles));
        list.add(ConfigImporters.simple("dressing", "梳妆阁数据.json → role_dressing", RoleDataImporters::dressing));
        list.add(ConfigImporters.simple("buffs", "玩家buff.json → role_buffs", RoleDataImporters::buffs));
        list.add(ConfigImporters.simple("relive", "relive.json → role_relive", RoleDataImporters::relive));
        list.add(ConfigImporters.simple("stat-snapshot", "玩家属性存档.json → role_stat_snapshots",
                RoleDataImporters::statSnapshot));
        list.add(ConfigImporters.simple("stances", "待机站姿.json → role_stances", RoleDataImporters::stances));
        list.add(ConfigImporters.simple("skill-progress", "skills.json → role_skill_progress（level>0 或 fill>0）",
                RoleDataImporters::skillProgress));
        list.add(ConfigImporters.simple("origin-progress", "origin/身份进度.json → role_origin_progress",
                RoleDataImporters::originProgress));
        list.add(ConfigImporters.simple("repute", "repute/声望进度.json → role_repute", RoleDataImporters::repute));
        return list;
    }

    /** 遍历 roles{roleId:value}，跳过库中不存在的角色；返回处理的角色数。 */
    private static int eachRole(ImportContext ctx, JSONObject roles, BiConsumer<Long, Object> body) {
        if (roles == null) {
            return 0;
        }
        int used = 0;
        int skipped = 0;
        for (Map.Entry<String, Object> e : roles.entrySet()) {
            long roleId;
            try {
                roleId = Long.parseLong(e.getKey());
            } catch (NumberFormatException ex) {
                skipped++;
                continue;
            }
            if (!ctx.roleExists(roleId)) {
                skipped++;
                continue;
            }
            body.accept(roleId, e.getValue());
            used++;
        }
        if (skipped > 0) {
            System.out.printf("  跳过不存在的角色键 %d 个（假人/占位）%n", skipped);
        }
        return used;
    }

    private static JSONObject obj(Object v) {
        return v instanceof JSONObject o ? o : null;
    }

    private static JSONArray arr(Object v) {
        return v instanceof JSONArray a ? a : null;
    }

    /** 行数计数器（lambda 内可变）。 */
    private static int[] counter() {
        return new int[1];
    }

    // ------------------------------------------------------------------

    private static void vip(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("vip.json");
        int roles = 0;
        if (root != null) {
            ctx.clearTable("role_vip");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_vip (role_id, gold, guan_yin, vip_valid_until, vip_total_time, "
                            + "vip_flag, vip_prize_time) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                roles = eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    try {
                        b.ps().setLong(1, id);
                        b.ps().setLong(2, o.getLongValue("gold"));
                        b.ps().setLong(3, o.getLongValue("guan_yin"));
                        b.ps().setLong(4, o.getLongValue("vip_valid_until"));
                        b.ps().setLong(5, o.getLongValue("vip_total_time"));
                        b.ps().setInt(6, o.getIntValue("vip_flag"));
                        b.ps().setLong(7, o.getLongValue("vip_prize_time"));
                        b.add();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  VIP %d 行%n", roles);
    }

    private static void mails(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("mail.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_mails");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_mails (role_id, mail_serial, created_unix, sender_id, title_id, "
                            + "content_id, is_read, attachment_config_id, attachment_amount) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONArray mails = arr(v);
                    if (mails == null) {
                        return;
                    }
                    for (int i = 0; i < mails.size(); i++) {
                        JSONObject m = mails.getJSONObject(i);
                        try {
                            b.ps().setLong(1, id);
                            b.ps().setString(2, ConfigImporters.str(m, "serial"));
                            b.ps().setLong(3, m.getLongValue("created_at"));
                            b.ps().setString(4, ConfigImporters.str(m, "sender_id"));
                            b.ps().setString(5, ConfigImporters.str(m, "title_id"));
                            b.ps().setString(6, ConfigImporters.str(m, "content_id"));
                            b.ps().setInt(7, m.getBooleanValue("read") ? 1 : 0);
                            JSONObject att = m.getJSONObject("attachment");
                            if (att != null) {
                                b.ps().setString(8, ConfigImporters.str(att, "config_id"));
                                b.ps().setInt(9, att.getIntValue("amount"));
                            } else {
                                b.ps().setNull(8, java.sql.Types.VARCHAR);
                                b.ps().setNull(9, java.sql.Types.INTEGER);
                            }
                            b.add();
                            rows[0]++;
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        }
        System.out.printf("  邮件 %d 行%n", rows[0]);
    }

    private static void lifejob(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("lifejob.json");
        int states = 0;
        int[] jobs = counter();
        if (root != null) {
            ctx.clearTable("role_lifejob_jobs");
            ctx.clearTable("role_lifejob_state");
            try (ImportContext.Batcher bs = ctx.batch(
                    "INSERT INTO role_lifejob_state (role_id, ene) VALUES (?, ?)");
                 ImportContext.Batcher bj = ctx.batch(
                         "INSERT INTO role_lifejob_jobs (role_id, job_id, level, exp, next_exp, formulas, points) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                states = eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    try {
                        bs.ps().setLong(1, id);
                        bs.ps().setInt(2, o.getIntValue("ene"));
                        bs.add();
                        JSONArray list = o.getJSONArray("jobs");
                        if (list != null) {
                            for (int i = 0; i < list.size(); i++) {
                                JSONObject j = list.getJSONObject(i);
                                bj.ps().setLong(1, id);
                                bj.ps().setString(2, ConfigImporters.str(j, "id"));
                                bj.ps().setInt(3, j.getIntValue("level"));
                                bj.ps().setLong(4, j.getLongValue("exp"));
                                bj.ps().setLong(5, j.getLongValue("next_exp"));
                                bj.ps().setInt(6, j.getIntValue("formulas"));
                                bj.ps().setInt(7, j.getIntValue("points"));
                                bj.add();
                                jobs[0]++;
                            }
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  精力 %d 行，职业 %d 行%n", states, jobs[0]);
    }

    private static void binglu(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("binglu.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_binglu");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_binglu (role_id, binglu_index, cur_level, fill_value, max_level) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject slots = obj(v);
                    if (slots == null) {
                        return;
                    }
                    try {
                        for (Map.Entry<String, Object> e : slots.entrySet()) {
                            JSONObject s = slots.getJSONObject(e.getKey());
                            b.ps().setLong(1, id);
                            b.ps().setInt(2, Integer.parseInt(e.getKey()));
                            b.ps().setInt(3, s.getIntValue("cur_level"));
                            b.ps().setLong(4, s.getLongValue("fill_value"));
                            b.ps().setInt(5, s.getIntValue("max_level"));
                            b.add();
                            rows[0]++;
                        }
                    } catch (SQLException | NumberFormatException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
        System.out.printf("  兵录 %d 行%n", rows[0]);
    }

    private static void storyTasks(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("story_tasks.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_story_tasks");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_story_tasks (role_id, task_id, status, step, counts) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS JSON))")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    try {
                        JSONObject active = o.getJSONObject("active");
                        if (active != null) {
                            for (Map.Entry<String, Object> e : active.entrySet()) {
                                JSONObject t = active.getJSONObject(e.getKey());
                                b.ps().setLong(1, id);
                                b.ps().setInt(2, t.getIntValue("task_id"));
                                b.ps().setString(3, "active");
                                b.ps().setInt(4, t.getIntValue("step"));
                                ConfigImporters.setJson(b, 5, t.get("counts"));
                                b.add();
                                rows[0]++;
                            }
                        }
                        JSONArray done = o.getJSONArray("completed");
                        if (done != null) {
                            for (int i = 0; i < done.size(); i++) {
                                b.ps().setLong(1, id);
                                b.ps().setLong(2, done.getLong(i));
                                b.ps().setString(3, "completed");
                                b.ps().setInt(4, 0);
                                b.ps().setNull(5, java.sql.Types.VARCHAR);
                                b.add();
                                rows[0]++;
                            }
                        }
                    } catch (SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
        System.out.printf("  剧情任务 %d 行%n", rows[0]);
    }

    private static void homepoints(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("homepoints.json");
        int[] points = counter();
        int[] hires = counter();
        int[] states = counter();
        if (root != null) {
            ctx.clearTable("role_homepoint_hires");
            ctx.clearTable("role_homepoints");
            ctx.clearTable("role_homepoint_state");
            try (ImportContext.Batcher bp = ctx.batch(
                    "INSERT INTO role_homepoints (role_id, point_id, point_type) VALUES (?, ?, ?)");
                 ImportContext.Batcher bh = ctx.batch(
                         "INSERT INTO role_homepoint_hires (role_id, point_id, point_type) VALUES (?, ?, ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONArray list = arr(v);
                    if (list == null) {
                        return;
                    }
                    try {
                        for (int i = 0; i < list.size(); i++) {
                            JSONObject p = list.getJSONObject(i);
                            bp.ps().setLong(1, id);
                            bp.ps().setString(2, ConfigImporters.str(p, "id"));
                            bp.ps().setInt(3, p.getIntValue("type"));
                            bp.add();
                            points[0]++;
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                JSONObject hire = root.getJSONObject("hire");
                if (hire != null) {
                    eachRole(ctx, hire, (id, v) -> {
                        JSONArray list = arr(v);
                        if (list == null) {
                            return;
                        }
                        try {
                            for (int i = 0; i < list.size(); i++) {
                                JSONObject p = list.getJSONObject(i);
                                bh.ps().setLong(1, id);
                                bh.ps().setString(2, ConfigImporters.str(p, "id"));
                                bh.ps().setInt(3, p.getIntValue("type"));
                                bh.add();
                                hires[0]++;
                            }
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                JSONObject slots = root.getJSONObject("slots");
                JSONObject expires = root.getJSONObject("hire_expires");
                if (slots != null) {
                    try (ImportContext.Batcher bst = ctx.batch(
                            "INSERT INTO role_homepoint_state (role_id, home_point_count, guild_count, "
                                    + "jianghu_count, school_count, hire_expires_unix) VALUES (?, ?, ?, ?, ?, ?)")) {
                        eachRole(ctx, slots, (id, v) -> {
                            JSONObject s = obj(v);
                            if (s == null) {
                                return;
                            }
                            try {
                                bst.ps().setLong(1, id);
                                bst.ps().setInt(2, s.getIntValue("home_point_count"));
                                bst.ps().setInt(3, s.getIntValue("guild_count"));
                                bst.ps().setInt(4, s.getIntValue("jianghu_count"));
                                bst.ps().setInt(5, s.getIntValue("school_count"));
                                bst.ps().setLong(6, expires != null
                                        ? expires.getLongValue(String.valueOf(id)) : 0);
                                bst.add();
                                states[0]++;
                            } catch (SQLException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
            }
        }
        System.out.printf("  回城点 %d，槽位状态 %d，租借 %d%n", points[0], states[0], hires[0]);
    }

    private static void cloneUsage(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("clone.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_clone_usage");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_clone_usage (role_id, usage_date, scene_config, used) "
                            + "VALUES (?, ?, ?, CAST(? AS JSON))")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    String day = ConfigImporters.str(o, "day");
                    JSONArray used = o.getJSONArray("used");
                    if (day.isEmpty() || used == null) {
                        return;
                    }
                    try {
                        b.ps().setLong(1, id);
                        b.ps().setDate(2, java.sql.Date.valueOf(day));
                        b.ps().setString(3, "");
                        b.ps().setString(4, used.toJSONString());
                        b.add();
                        rows[0]++;
                        JSONObject byClone = o.getJSONObject("byClone");
                        if (byClone != null) {
                            for (Map.Entry<String, Object> e : byClone.entrySet()) {
                                JSONArray cu = byClone.getJSONArray(e.getKey());
                                b.ps().setLong(1, id);
                                b.ps().setDate(2, java.sql.Date.valueOf(day));
                                b.ps().setString(3, e.getKey());
                                b.ps().setString(4, cu.toJSONString());
                                b.add();
                                rows[0]++;
                            }
                        }
                    } catch (SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
        System.out.printf("  禁地次数 %d 行%n", rows[0]);
    }

    private static void wuji(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("武技数据.json");
        int[] learned = counter();
        int[] unlocked = counter();
        int[] worn = counter();
        if (root != null) {
            ctx.clearTable("role_wuji_worn");
            ctx.clearTable("role_wuji_unlocked");
            ctx.clearTable("role_wuji_learned");
            try (ImportContext.Batcher bl = ctx.batch(
                    "INSERT INTO role_wuji_learned (role_id, art_id, level, is_active) VALUES (?, ?, ?, ?)");
                 ImportContext.Batcher bu = ctx.batch(
                         "INSERT INTO role_wuji_unlocked (role_id, group_id, unlocked_count) VALUES (?, ?, ?)");
                 ImportContext.Batcher bw = ctx.batch(
                         "INSERT INTO role_wuji_worn (role_id, group_id, slot_index, art_id) VALUES (?, ?, ?, ?)")) {
                eachRole(ctx, root, (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    try {
                        JSONObject active = o.getJSONObject("active");
                        JSONObject learnedMap = o.getJSONObject("learned");
                        if (learnedMap != null) {
                            for (Map.Entry<String, Object> e : learnedMap.entrySet()) {
                                bl.ps().setLong(1, id);
                                bl.ps().setInt(2, Integer.parseInt(e.getKey()));
                                bl.ps().setInt(3, Integer.parseInt(String.valueOf(e.getValue())));
                                bl.ps().setInt(4, active != null && active.containsKey(e.getKey()) ? 1 : 0);
                                bl.add();
                                learned[0]++;
                            }
                        }
                        JSONObject unlockedMap = o.getJSONObject("unlocked");
                        if (unlockedMap != null) {
                            for (Map.Entry<String, Object> e : unlockedMap.entrySet()) {
                                bu.ps().setLong(1, id);
                                bu.ps().setInt(2, Integer.parseInt(e.getKey()));
                                bu.ps().setInt(3, Integer.parseInt(String.valueOf(e.getValue())));
                                bu.add();
                                unlocked[0]++;
                            }
                        }
                        JSONObject wornMap = o.getJSONObject("worn");
                        if (wornMap != null) {
                            for (Map.Entry<String, Object> e : wornMap.entrySet()) {
                                int group = Integer.parseInt(e.getKey());
                                JSONArray arts = wornMap.getJSONArray(e.getKey());
                                for (int i = 0; i < arts.size(); i++) {
                                    bw.ps().setLong(1, id);
                                    bw.ps().setInt(2, group);
                                    bw.ps().setInt(3, i);
                                    bw.ps().setLong(4, arts.getLong(i));
                                    bw.add();
                                    worn[0]++;
                                }
                            }
                        }
                    } catch (SQLException | NumberFormatException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
        System.out.printf("  武技：已学 %d，解锁 %d，佩戴 %d%n", learned[0], unlocked[0], worn[0]);
    }

    private static void fashionSuits(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("新版服装数据.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_fashion_suits");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_fashion_suits (role_id, suit_id) VALUES (?, ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    JSONArray suits = o != null ? o.getJSONArray("suits") : arr(v);
                    if (suits == null) {
                        return;
                    }
                    try {
                        for (int i = 0; i < suits.size(); i++) {
                            b.ps().setLong(1, id);
                            b.ps().setString(2, suits.getString(i));
                            b.add();
                            rows[0]++;
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  服装收藏 %d 行%n", rows[0]);
    }

    private static void roleTitles(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("称号数据.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_titles");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_titles (role_id, title_id, title_type, is_worn) VALUES (?, ?, ?, ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    long worn = o.getLongValue("worn");
                    JSONArray owned = o.getJSONArray("owned");
                    if (owned == null) {
                        return;
                    }
                    try {
                        for (int i = 0; i < owned.size(); i++) {
                            JSONObject t = owned.getJSONObject(i);
                            long tid = t.getLongValue("id");
                            b.ps().setLong(1, id);
                            b.ps().setLong(2, tid);
                            b.ps().setInt(3, t.getIntValue("type"));
                            b.ps().setInt(4, tid == worn ? 1 : 0);
                            b.add();
                            rows[0]++;
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  角色称号 %d 行%n", rows[0]);
    }

    private static void dressing(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("梳妆阁数据.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_dressing");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_dressing (role_id, blend, show_type) VALUES (?, CAST(? AS JSON), ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null || o.getJSONObject("blend") == null) {
                        return;
                    }
                    try {
                        b.ps().setLong(1, id);
                        ConfigImporters.setJson(b, 2, o.getJSONObject("blend"));
                        b.ps().setInt(3, o.getIntValue("show_type"));
                        b.add();
                        rows[0]++;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  梳妆阁 %d 行（blend 为空的角色跳过）%n", rows[0]);
    }

    private static void buffs(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("玩家buff.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("role_buffs");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_buffs (role_id, snapshot) VALUES (?, CAST(? AS JSON))")) {
                rows = eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    try {
                        b.ps().setLong(1, id);
                        b.ps().setString(2, com.alibaba.fastjson.JSON.toJSONString(v));
                        b.add();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  buff %d 行（源为空属正常）%n", rows);
    }

    private static void relive(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("relive.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("role_relive");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_relive (role_id, scene_config, scene_resource, pos_x, pos_y, pos_z, "
                            + "orient, ui_name, home_point_id, is_manual, affiliation) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                rows = eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    JSONObject anchor = o != null ? o.getJSONObject("anchor") : null;
                    if (anchor == null) {
                        return;
                    }
                    try {
                        JSONObject scene = anchor.getJSONObject("scene");
                        JSONObject pos = anchor.getJSONObject("position");
                        b.ps().setLong(1, id);
                        b.ps().setString(2, scene != null ? ConfigImporters.str(scene, "config") : "");
                        b.ps().setString(3, scene != null ? ConfigImporters.str(scene, "resource") : "");
                        b.ps().setFloat(4, pos != null ? pos.getFloatValue("x") : 0f);
                        b.ps().setFloat(5, pos != null ? pos.getFloatValue("y") : 0f);
                        b.ps().setFloat(6, pos != null ? pos.getFloatValue("z") : 0f);
                        b.ps().setFloat(7, pos != null ? pos.getFloatValue("orient") : 0f);
                        b.ps().setString(8, ConfigImporters.str(anchor, "ui_name"));
                        b.ps().setString(9, ConfigImporters.str(anchor, "home_point_id"));
                        b.ps().setInt(10, o.getBooleanValue("manual") ? 1 : 0);
                        b.ps().setString(11, ConfigImporters.str(o, "affiliation"));
                        b.add();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  复活锚点 %d 行%n", rows);
    }

    private static void statSnapshot(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("玩家属性存档.json");
        if (root == null) {
            System.out.println("  属性存档缺失，跳过");
            return;
        }
        long roleId = root.getLongValue("roleId");
        if (!ctx.roleExists(roleId)) {
            System.out.printf("  属性存档角色 %d 不在库中，跳过%n", roleId);
            return;
        }
        long archived;
        try {
            archived = OffsetDateTime.parse(root.getString("_time"), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().getEpochSecond();
        } catch (RuntimeException e) {
            archived = System.currentTimeMillis() / 1000;
        }
        ctx.clearTable("role_stat_snapshots");
        try (ImportContext.Batcher b = ctx.batch(
                "INSERT INTO role_stat_snapshots (role_id, archived_unix, snapshot) "
                        + "VALUES (?, ?, CAST(? AS JSON))")) {
            b.ps().setLong(1, roleId);
            b.ps().setLong(2, archived);
            b.ps().setString(3, root.toJSONString());
            b.add();
        }
        System.out.println("  属性快照 1 行");
    }

    private static void stances(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("待机站姿.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("role_stances");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_stances (role_id, idle_stance) VALUES (?, ?)")) {
                rows = eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    try {
                        b.ps().setLong(1, id);
                        b.ps().setString(2, String.valueOf(v));
                        b.add();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  待机站姿 %d 行%n", rows);
    }

    private static void skillProgress(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("skills.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_skill_progress");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_skill_progress (role_id, skill_config_id, level, max_level, fill, total, extra) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    JSONArray skills = o != null ? o.getJSONArray("skills") : arr(v);
                    if (skills == null) {
                        return;
                    }
                    try {
                        for (int i = 0; i < skills.size(); i++) {
                            JSONObject s = skills.getJSONObject(i);
                            if (s.getIntValue("level") <= 0 && s.getLongValue("fill") <= 0) {
                                continue;
                            }
                            b.ps().setLong(1, id);
                            b.ps().setString(2, ConfigImporters.str(s, "config_id"));
                            b.ps().setInt(3, s.getIntValue("level"));
                            b.ps().setInt(4, s.getIntValue("max_level"));
                            b.ps().setLong(5, s.getLongValue("fill"));
                            b.ps().setLong(6, s.getLongValue("total"));
                            JSONObject extra = new JSONObject(true);
                            for (String k : new String[]{"static_data", "item_type", "pause_time",
                                    "max_distance", "cool_down_time", "inner_force"}) {
                                if (s.containsKey(k)) {
                                    extra.put(k, s.get(k));
                                }
                            }
                            b.ps().setString(7, extra.toJSONString());
                            b.add();
                            rows[0]++;
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  技能修炼 %d 行%n", rows[0]);
    }

    private static void originProgress(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("origin/身份进度.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("role_origin_progress");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_origin_progress (role_id, activated) VALUES (?, CAST(? AS JSON))")) {
                rows = eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    try {
                        b.ps().setLong(1, id);
                        ConfigImporters.setJson(b, 2, o.getJSONObject("activated"));
                        b.add();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.printf("  身份进度 %d 行%n", rows);
    }

    private static void repute(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("repute/声望进度.json");
        int[] rows = counter();
        if (root != null) {
            ctx.clearTable("role_repute");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO role_repute (role_id, repute_id, repute_value) VALUES (?, ?, ?)")) {
                eachRole(ctx, root.getJSONObject("roles"), (id, v) -> {
                    JSONObject o = obj(v);
                    if (o == null) {
                        return;
                    }
                    try {
                        for (Map.Entry<String, Object> e : o.entrySet()) {
                            b.ps().setLong(1, id);
                            b.ps().setString(2, e.getKey());
                            b.ps().setLong(3, Long.parseLong(String.valueOf(e.getValue())));
                            b.add();
                            rows[0]++;
                        }
                    } catch (SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
        System.out.printf("  声望 %d 行%n", rows[0]);
    }
}
