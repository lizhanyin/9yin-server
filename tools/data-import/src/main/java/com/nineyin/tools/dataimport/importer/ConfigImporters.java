package com.nineyin.tools.dataimport.importer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 配置表导入器集合：game_* / server_* / teams / gm_permissions（无外键，全量重建）。 */
public final class ConfigImporters {

    private ConfigImporters() {
    }

    public static List<Importer> all() {
        List<Importer> list = new ArrayList<>();
        list.add(simple("shops", "NPC商店数据.json → game_shops/game_shop_items/game_npc_shops",
                ConfigImporters::shops));
        list.add(simple("giftboxes", "giftbox.json → game_gift_boxes", ConfigImporters::giftBoxes));
        list.add(simple("item-effects", "道具配置.json → game_item_effects", ConfigImporters::itemEffects));
        list.add(simple("switches", "功能开关.json → game_feature_switches", ConfigImporters::switches));
        list.add(simple("cards", "card_models.json → game_cards", ConfigImporters::cards));
        list.add(simple("meridians", "经脉.json → game_meridians", ConfigImporters::meridians));
        list.add(simple("title-catalog", "称号数据.json catalog → game_titles", ConfigImporters::titleCatalog));
        list.add(simple("identity-suits", "身份装数据.json → game_identity_suits", ConfigImporters::identitySuits));
        list.add(simple("skill-reactions", "skill_hurt_reaction.json → game_skill_reactions",
                ConfigImporters::skillReactions));
        list.add(simple("stat-defaults", "属性.json → game_stat_defaults", ConfigImporters::statDefaults));
        list.add(simple("origins", "origin/origins.json → game_origins", ConfigImporters::origins));
        list.add(simple("origin-conditions", "origin/conditions.json → game_origin_conditions",
                ConfigImporters::originConditions));
        list.add(simple("ninjiuzhi-overrides", "ninjiuzhi_requirements.json → game_ninjiuzhi_overrides + server_config",
                ConfigImporters::ninjiuzhiOverrides));
        list.add(simple("ninjiuzhi", "ninjiuzhi.json → server_ninjiuzhi", ConfigImporters::ninjiuzhi));
        list.add(simple("bots", "bots.json → server_bots + server_config(next_id)", ConfigImporters::bots));
        list.add(simple("dummy-players", "dummy_players.json → server_dummy_players", ConfigImporters::dummyPlayers));
        list.add(simple("teams", "teams.json → teams + server_config(next_id)", ConfigImporters::teams));
        list.add(simple("gm-permissions", "gm权限.json → gm_permissions + server_config(default_level)",
                ConfigImporters::gmPermissions));
        return list;
    }

    interface Body {
        void run(ImportContext ctx) throws SQLException;
    }

    static Importer simple(String name, String description, Body body) {
        return new Importer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public void run(ImportContext ctx) throws SQLException {
                body.run(ctx);
            }
        };
    }

    // ------------------------------------------------------------------

    private static void shops(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("NPC商店数据.json");
        int shops = 0;
        int items = 0;
        int npcs = 0;
        if (root != null) {
            ctx.clearTable("game_shop_items");
            ctx.clearTable("game_npc_shops");
            ctx.clearTable("game_shops");
            JSONObject shopMap = root.getJSONObject("shops");
            if (shopMap != null) {
                try (ImportContext.Batcher bs = ctx.batch(
                        "INSERT INTO game_shops (shop_id, shop_name) VALUES (?, ?)");
                     ImportContext.Batcher bi = ctx.batch(
                             "INSERT INTO game_shop_items (shop_id, seq, config_id, amount, capital_type, price) "
                                     + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, Object> e : shopMap.entrySet()) {
                        JSONObject shop = shopMap.getJSONObject(e.getKey());
                        bs.ps().setString(1, e.getKey());
                        bs.ps().setString(2, str(shop, "name"));
                        bs.add();
                        shops++;
                        JSONArray its = shop != null ? shop.getJSONArray("items") : null;
                        if (its != null) {
                            for (int i = 0; i < its.size(); i++) {
                                JSONObject it = its.getJSONObject(i);
                                bi.ps().setString(1, e.getKey());
                                bi.ps().setInt(2, i + 1);
                                bi.ps().setString(3, str(it, "configID"));
                                bi.ps().setInt(4, it != null ? it.getIntValue("amount") : 1);
                                bi.ps().setInt(5, it != null ? it.getIntValue("capitalType") : 0);
                                bi.ps().setInt(6, it != null ? it.getIntValue("price") : 0);
                                bi.add();
                                items++;
                            }
                        }
                    }
                }
            }
            JSONObject npcMap = root.getJSONObject("npcs");
            if (npcMap != null) {
                try (ImportContext.Batcher bn = ctx.batch(
                        "INSERT INTO game_npc_shops (npc_config_id, npc_name, shop_id) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, Object> e : npcMap.entrySet()) {
                        JSONObject npc = npcMap.getJSONObject(e.getKey());
                        bn.ps().setString(1, e.getKey());
                        bn.ps().setString(2, str(npc, "npcName"));
                        bn.ps().setString(3, str(npc, "shopID"));
                        bn.add();
                        npcs++;
                    }
                }
            }
        }
        System.out.printf("  商店 %d，货架 %d，NPC 绑定 %d%n", shops, items, npcs);
    }

    private static void giftBoxes(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("giftbox.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_gift_boxes");
            JSONObject boxes = root.getJSONObject("boxes");
            if (boxes != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_gift_boxes (box_id, seq, item_id, min_amount, max_amount, weight) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, Object> e : boxes.entrySet()) {
                        JSONArray entries = boxes.getJSONArray(e.getKey());
                        for (int i = 0; i < entries.size(); i++) {
                            JSONObject it = entries.getJSONObject(i);
                            b.ps().setString(1, e.getKey());
                            b.ps().setInt(2, i + 1);
                            b.ps().setString(3, str(it, "item"));
                            b.ps().setInt(4, it != null ? it.getIntValue("min") : 1);
                            b.ps().setInt(5, it != null ? it.getIntValue("max") : 1);
                            b.ps().setInt(6, it != null ? it.getIntValue("weight") : 0);
                            b.add();
                            rows++;
                        }
                    }
                }
            }
        }
        System.out.printf("  礼包条目 %d%n", rows);
    }

    private static void itemEffects(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("道具配置.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_item_effects");
            JSONObject items = root.getJSONObject("items");
            if (items != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_item_effects (config_id, name, heal_hp, heal_mp, consume, effects) "
                                + "VALUES (?, ?, ?, ?, ?, CAST(? AS JSON))")) {
                    for (Map.Entry<String, Object> e : items.entrySet()) {
                        JSONObject it = items.getJSONObject(e.getKey());
                        b.ps().setString(1, e.getKey());
                        b.ps().setString(2, str(it, "name"));
                        Integer hp = it != null ? it.getInteger("heal_hp") : null;
                        Integer mp = it != null ? it.getInteger("heal_mp") : null;
                        if (hp != null) {
                            b.ps().setInt(3, hp);
                        } else {
                            b.ps().setNull(3, java.sql.Types.INTEGER);
                        }
                        if (mp != null) {
                            b.ps().setInt(4, mp);
                        } else {
                            b.ps().setNull(4, java.sql.Types.INTEGER);
                        }
                        b.ps().setInt(5, it != null && it.getBooleanValue("consume") ? 1 : 0);
                        b.ps().setString(6, it != null ? it.toJSONString() : "{}");
                        b.add();
                        rows++;
                    }
                }
            }
            JSONObject defaults = root.getJSONObject("defaults");
            if (defaults != null) {
                upsertConfig(ctx, "item_effects.defaults", defaults);
            }
        }
        System.out.printf("  道具效果 %d%n", rows);
    }

    private static void switches(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("功能开关.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_feature_switches");
            JSONArray list = root.getJSONArray("switches");
            if (list != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_feature_switches (switch_id, desc_key, label, enabled) "
                                + "VALUES (?, ?, ?, ?)")) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject sw = list.getJSONObject(i);
                        b.ps().setInt(1, sw.getIntValue("id"));
                        b.ps().setString(2, str(sw, "desc_key"));
                        b.ps().setString(3, str(sw, "label"));
                        b.ps().setInt(4, sw.getBooleanValue("enabled") ? 1 : 0);
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  功能开关 %d%n", rows);
    }

    private static void cards(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("card_models.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_cards");
            JSONObject cards = root.getJSONObject("cards");
            if (cards != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_cards (card_id, name, config_id, item_type, level, main_type, "
                                + "sub_type, flag, female_model_data, male_model_data) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, Object> e : cards.entrySet()) {
                        JSONObject c = cards.getJSONObject(e.getKey());
                        b.ps().setLong(1, Long.parseLong(e.getKey()));
                        b.ps().setString(2, str(c, "name"));
                        b.ps().setInt(3, c.getIntValue("config_id"));
                        b.ps().setInt(4, c.getIntValue("item_type"));
                        b.ps().setInt(5, c.getIntValue("level"));
                        b.ps().setInt(6, c.getIntValue("main_type"));
                        b.ps().setInt(7, c.getIntValue("sub_type"));
                        b.ps().setInt(8, c.getIntValue("flag"));
                        b.ps().setString(9, str(c, "female_model_data"));
                        b.ps().setString(10, str(c, "male_model_data"));
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  卡牌 %d%n", rows);
    }

    private static void meridians(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("经脉.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_meridians");
            JSONArray list = root.getJSONArray("meridians");
            if (list != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_meridians (meridian_id, name, static_data, item_type, neigong, "
                                + "max_level, attrs, per_level) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))")) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject m = list.getJSONObject(i);
                        b.ps().setString(1, str(m, "id"));
                        b.ps().setString(2, str(m, "name"));
                        b.ps().setInt(3, m.getIntValue("staticData"));
                        b.ps().setInt(4, m.getIntValue("itemType"));
                        b.ps().setString(5, str(m, "neigong"));
                        b.ps().setInt(6, m.getIntValue("maxLevel"));
                        setJson(b, 7, m.get("attrs"));
                        setJson(b, 8, m.get("perLevel"));
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  经脉 %d%n", rows);
    }

    private static void titleCatalog(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("称号数据.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_titles");
            JSONObject catalog = root.getJSONObject("catalog");
            JSONArray list = catalog != null ? catalog.getJSONArray("titles") : null;
            if (list != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_titles (title_id, name, title_type) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject t = list.getJSONObject(i);
                        b.ps().setInt(1, t.getIntValue("id"));
                        b.ps().setString(2, str(t, "name"));
                        b.ps().setInt(3, t.getIntValue("type"));
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  称号 %d%n", rows);
    }

    private static void identitySuits(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("身份装数据.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_identity_suits");
            JSONObject suits = root.getJSONObject("suits");
            if (suits != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_identity_suits (suit_id, name, qname, item_type, need_sex, "
                                + "color_level, condition_id, parts) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))")) {
                    for (Map.Entry<String, Object> e : suits.entrySet()) {
                        JSONObject s = suits.getJSONObject(e.getKey());
                        b.ps().setString(1, e.getKey());
                        b.ps().setString(2, str(s, "name"));
                        b.ps().setString(3, str(s, "qname"));
                        b.ps().setInt(4, s.getIntValue("item_type"));
                        b.ps().setInt(5, s.getIntValue("need_sex"));
                        b.ps().setInt(6, s.getIntValue("color_level"));
                        b.ps().setInt(7, s.getIntValue("condition_id"));
                        JSONObject parts = new JSONObject(true);
                        for (String part : new String[]{"cloth", "hat", "pants", "shoes", "hair"}) {
                            parts.put(part, s.get(part));
                        }
                        b.ps().setString(8, parts.toJSONString());
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  身份装 %d%n", rows);
    }

    private static void skillReactions(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("skill_hurt_reaction.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_skill_reactions");
            JSONObject reactions = root.getJSONObject("reactions");
            JSONArray finalOnly = root.getJSONArray("final_hit_only");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO game_skill_reactions (skill_id, reaction, final_hit_only) VALUES (?, ?, ?)")) {
                if (reactions != null) {
                    for (Map.Entry<String, Object> e : reactions.entrySet()) {
                        b.ps().setString(1, e.getKey());
                        b.ps().setString(2, String.valueOf(e.getValue()));
                        b.ps().setInt(3, 0);
                        b.add();
                        rows++;
                    }
                }
                if (finalOnly != null) {
                    for (int i = 0; i < finalOnly.size(); i++) {
                        b.ps().setString(1, finalOnly.getString(i));
                        b.ps().setNull(2, java.sql.Types.VARCHAR);
                        b.ps().setInt(3, 1);
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  技能受击覆写 %d（当前源数据为空表属正常）%n", rows);
    }

    private static void statDefaults(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("属性.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_stat_defaults");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO game_stat_defaults (stat_key, stat_value) VALUES (?, ?)")) {
                for (Map.Entry<String, Object> e : root.entrySet()) {
                    if (e.getKey().startsWith("_") || !(e.getValue() instanceof Number)) {
                        continue;
                    }
                    b.ps().setString(1, e.getKey());
                    b.ps().setLong(2, ((Number) e.getValue()).longValue());
                    b.add();
                    rows++;
                }
            }
        }
        System.out.printf("  属性默认值 %d%n", rows);
    }

    private static void origins(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("origin/origins.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_origins");
            JSONArray list = root.getJSONArray("origins");
            if (list != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_origins (origin_id, name, category, sub_category, type1, type2, "
                                + "level, material, body_type, line, conditions) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))")) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject o = list.getJSONObject(i);
                        b.ps().setInt(1, o.getIntValue("id"));
                        b.ps().setString(2, str(o, "name"));
                        b.ps().setString(3, str(o, "category"));
                        b.ps().setString(4, str(o, "subCategory"));
                        b.ps().setInt(5, o.getIntValue("type1"));
                        b.ps().setString(6, str(o, "type2"));
                        b.ps().setInt(7, o.getIntValue("level"));
                        b.ps().setInt(8, o.getIntValue("material"));
                        b.ps().setInt(9, o.getIntValue("bodyType"));
                        setJson(b, 10, o.get("line"));
                        JSONObject cond = new JSONObject(true);
                        for (String k : new String[]{"activeCondIDs", "activeCondText", "getCondIDs", "getCondText"}) {
                            if (o.containsKey(k)) {
                                cond.put(k, o.get(k));
                            }
                        }
                        b.ps().setString(11, cond.toJSONString());
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  身份 %d%n", rows);
    }

    private static void originConditions(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("origin/conditions.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_origin_conditions");
            JSONObject cond = root.getJSONObject("conditions");
            if (cond != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO game_origin_conditions (condition_id, text, use_count, note) "
                                + "VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<String, Object> e : cond.entrySet()) {
                        JSONObject c = cond.getJSONObject(e.getKey());
                        b.ps().setInt(1, Integer.parseInt(e.getKey()));
                        b.ps().setString(2, str(c, "text"));
                        b.ps().setInt(3, c != null ? c.getIntValue("useCount") : 0);
                        b.ps().setString(4, str(c, "note"));
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  身份条件 %d%n", rows);
    }

    private static void ninjiuzhiOverrides(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("ninjiuzhi_requirements.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("game_ninjiuzhi_overrides");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO game_ninjiuzhi_overrides (category, item_key, required_stage) VALUES (?, ?, ?)")) {
                for (String category : new String[]{"features", "tasks", "npcs", "scenes", "inner_books"}) {
                    JSONObject map = root.getJSONObject(category);
                    if (map == null) {
                        continue;
                    }
                    for (Map.Entry<String, Object> e : map.entrySet()) {
                        b.ps().setString(1, category);
                        b.ps().setString(2, e.getKey());
                        b.ps().setInt(3, Integer.parseInt(String.valueOf(e.getValue())));
                        b.add();
                        rows++;
                    }
                }
            }
            Integer initial = root.getInteger("initial_stage");
            if (initial != null) {
                upsertConfig(ctx, "ninjiuzhi.initial_stage", initial);
            }
        }
        System.out.printf("  九阴志阶段覆写 %d%n", rows);
    }

    private static void ninjiuzhi(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("ninjiuzhi.json");
        if (root != null) {
            ctx.clearTable("server_ninjiuzhi");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO server_ninjiuzhi (id, stage, active, points, events, revision) "
                            + "VALUES (1, ?, ?, ?, CAST(? AS JSON), ?)")) {
                b.ps().setInt(1, root.getIntValue("stage"));
                b.ps().setInt(2, root.getIntValue("active"));
                b.ps().setLong(3, root.getLongValue("points"));
                setJson(b, 4, root.get("events"));
                b.ps().setLong(5, root.getLongValue("revision"));
                b.add();
            }
            System.out.println("  九阴志全局进度 1 行");
        }
    }

    private static void bots(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("bots.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("server_bots");
            JSONArray list = root.getJSONArray("bots");
            if (list != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO server_bots (bot_id, bot_key, name, enabled, scene, position, "
                                + "equipment, appearance, revision) "
                                + "VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), ?)")) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject bot = list.getJSONObject(i);
                        b.ps().setInt(1, bot.getIntValue("id"));
                        b.ps().setString(2, str(bot, "key"));
                        b.ps().setString(3, str(bot, "name"));
                        b.ps().setInt(4, bot.getBooleanValue("enabled") ? 1 : 0);
                        setJson(b, 5, bot.get("scene"));
                        setJson(b, 6, bot.get("position"));
                        setJson(b, 7, bot.get("equipment"));
                        setJson(b, 8, bot.get("appearance"));
                        b.ps().setLong(9, bot.getLongValue("revision"));
                        b.add();
                        rows++;
                    }
                }
            }
            Integer nextId = root.getInteger("next_id");
            if (nextId != null) {
                upsertConfig(ctx, "bots.next_id", nextId);
            }
        }
        System.out.printf("  机器人 %d%n", rows);
    }

    private static void dummyPlayers(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("dummy_players.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("server_dummy_players");
            JSONArray list = root.getJSONArray("dummies");
            if (list != null) {
                try (ImportContext.Batcher b = ctx.batch(
                        "INSERT INTO server_dummy_players (name, sex, school, level, power_level, "
                                + "character_type, scene, action, neigong_buff, position, appearance) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))")) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject d = list.getJSONObject(i);
                        b.ps().setString(1, str(d, "name"));
                        b.ps().setInt(2, d.getIntValue("sex"));
                        b.ps().setString(3, str(d, "school"));
                        b.ps().setInt(4, d.getIntValue("level"));
                        b.ps().setInt(5, d.getIntValue("powerLevel"));
                        b.ps().setInt(6, d.getIntValue("character"));
                        b.ps().setString(7, str(d, "scene"));
                        b.ps().setString(8, str(d, "action"));
                        b.ps().setString(9, str(d, "neiGongBuff"));
                        setJson(b, 10, d.get("position"));
                        setJson(b, 11, d.get("appearance"));
                        b.add();
                        rows++;
                    }
                }
            }
        }
        System.out.printf("  假人 %d%n", rows);
    }

    private static void teams(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("teams.json");
        if (root != null) {
            ctx.clearTable("team_members");
            ctx.clearTable("teams");
            Integer nextId = root.getInteger("next_id");
            if (nextId != null) {
                upsertConfig(ctx, "teams.next_id", nextId);
            }
            System.out.println("  队伍表已清空（当前源无队伍）");
        }
    }

    private static void gmPermissions(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("gm权限.json");
        int rows = 0;
        if (root != null) {
            ctx.clearTable("gm_permissions");
            try (ImportContext.Batcher b = ctx.batch(
                    "INSERT INTO gm_permissions (scope, subject_key, gm_level) VALUES (?, ?, ?)")) {
                JSONObject accounts = root.getJSONObject("accounts");
                if (accounts != null) {
                    for (Map.Entry<String, Object> e : accounts.entrySet()) {
                        b.ps().setString(1, "account");
                        b.ps().setString(2, e.getKey());
                        b.ps().setInt(3, Integer.parseInt(String.valueOf(e.getValue())));
                        b.add();
                        rows++;
                    }
                }
                JSONObject roles = root.getJSONObject("roles");
                if (roles != null) {
                    for (Map.Entry<String, Object> e : roles.entrySet()) {
                        b.ps().setString(1, "role");
                        b.ps().setString(2, e.getKey());
                        b.ps().setInt(3, Integer.parseInt(String.valueOf(e.getValue())));
                        b.add();
                        rows++;
                    }
                }
            }
            Integer def = root.getInteger("default_level");
            if (def != null) {
                upsertConfig(ctx, "gm.default_level", def);
            }
        }
        System.out.printf("  GM 权限 %d%n", rows);
    }

    // ------------------------------------------------------------------

    static void upsertConfig(ImportContext ctx, String key, Object value) throws SQLException {
        try (var del = ctx.conn.prepareStatement("DELETE FROM server_config WHERE config_key = ?");
             var ins = ctx.conn.prepareStatement(
                     "INSERT INTO server_config (config_key, config_value) VALUES (?, CAST(? AS JSON))")) {
            del.setString(1, key);
            del.executeUpdate();
            ins.setString(1, key);
            ins.setString(2, com.alibaba.fastjson.JSON.toJSONString(value));
            ins.executeUpdate();
        }
    }

    static void setJson(ImportContext.Batcher b, int index, Object value) throws SQLException {
        if (value == null) {
            b.ps().setNull(index, java.sql.Types.VARCHAR);
        } else if (value instanceof String s) {
            b.ps().setString(index, s);
        } else {
            b.ps().setString(index, com.alibaba.fastjson.JSON.toJSONString(value));
        }
    }

    static String str(JSONObject o, String key) {
        String v = o != null ? o.getString(key) : null;
        return v != null ? v : "";
    }
}
