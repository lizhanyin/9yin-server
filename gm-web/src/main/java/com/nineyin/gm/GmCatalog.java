package com.nineyin.gm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * GM 面板目录数据服务：懒加载并缓存各下拉框数据源。
 * data/*.json 来源（卡牌/身份装/礼包/经脉/称号/假人/出身）在注入 {@link #useDatabase}
 * 后优先读 MySQL（GmCatalogDb），查询失败或未注入回退 data/ 文件；
 * ini/idres 来源仍从部署目录（share 根）读取。所有加载器对缺失/损坏均优雅降级。
 *
 * <p>数据源（编码已核实）：equipment.ini / tool_item.ini / cloneinfo.ini /
 * repute_level.ini / school_taolu.ini / scenes.ini 为 GBK；天气下拉为 sceneparastore/
 * 目录列举（非 ini）；stringname.idres / interface.idres 为 UTF-8+BOM；
 * b_action_index.ini、senior_*.ini、data/*.json 为 UTF-8。idres 与部分 ini 位于 share 根的
 * 同级目录（resources/modern/ini、resources/modern/text/chineses）。
 */
public final class GmCatalog {

    private static final Logger log = LoggerFactory.getLogger(GmCatalog.class);
    private static final Charset GBK = Charset.forName("GBK");
    private static final int ITEM_SEARCH_LIMIT = 300;

    private record ItemRow(String configId, String name, int viewId, boolean wearable,
                           String equipType, String script, int maxAmount, int colorLevel,
                           int needSex, int artPack) {
    }

    private final Path shareRoot;
    private final Path modernRoot;   // shareRoot 的上一级（resources/modern）
    private final Path dataDir;

    private volatile List<ItemRow> itemRows;
    private volatile Map<String, String> stringNames;
    private volatile Map<String, String> interfaceNames;
    private volatile JSONArray cards;
    private volatile JSONArray hairCards;
    private volatile JSONArray clones;
    private volatile JSONArray scenes;
    private volatile JSONArray taolus;
    private volatile JSONArray fashionCatalog;
    private volatile JSONArray giftBoxes;
    private volatile JSONArray idleStances;
    private volatile JSONArray meridians;
    private volatile JSONArray mounts;
    private volatile JSONObject reputeStatus;
    private volatile JSONArray titles;
    /** 称号目录外部数据源（MySQL game_titles；返回 null 表示不可用，回退 data/称号数据.json）。 */
    private volatile java.util.function.Supplier<JSONArray> titlesSource;
    private volatile JSONArray weather;
    private volatile JSONArray bots;
    private volatile JSONObject originStatus;
    private volatile JSONObject recastAffixes;
    /** MySQL 数据源（data/*.json 对应配置表）；null 表示未注入，全部走文件。 */
    private volatile GmCatalogDb db;

    private GmCatalog(Path shareRoot, Path dataDir) {
        this.shareRoot = shareRoot;
        this.modernRoot = shareRoot != null ? shareRoot.getParent() : null;
        this.dataDir = dataDir;
    }

    /** 无数据根的空目录（share 根缺失时面板仍可用，目录接口返回空）。 */
    public static GmCatalog empty() {
        return new GmCatalog(null, null);
    }

    public static GmCatalog create(Path shareRoot) {
        return new GmCatalog(shareRoot, resolveDataDir(shareRoot));
    }

    /** 直接指定 share/data 目录（测试用；data 自动解析链不适用）。 */
    static GmCatalog createForTest(Path shareRoot, Path dataDir) {
        return new GmCatalog(shareRoot, dataDir);
    }

    /** 注入 MySQL 目录数据源；注入即失效 data/ 来源缓存（ini 来源缓存不受影响）。 */
    public void useDatabase(GmCatalogDb database) {
        this.db = database;
        this.cards = null;
        this.hairCards = null;
        this.fashionCatalog = null;
        this.giftBoxes = null;
        this.meridians = null;
        this.titles = null;
        this.bots = null;
        this.originStatus = null;
    }

    /** 释放目录数据源连接池（如有）。 */
    public void close() {
        GmCatalogDb database = db;
        if (database != null) {
            database.close();
        }
    }

    /** data/ 解析链：CWD/data → NINEYIN_SERVER_ROOT/data → share 根上溯三级/data。 */
    private static Path resolveDataDir(Path shareRoot) {
        Path cwdData = Path.of("data");
        if (Files.isDirectory(cwdData)) {
            return cwdData;
        }
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        if (env != null && !env.isBlank()) {
            Path envData = Path.of(env, "data");
            if (Files.isDirectory(envData)) {
                return envData;
            }
        }
        if (shareRoot != null) {
            Path root = shareRoot.getParent();
            if (root != null) {
                root = root.getParent();
            }
            if (root != null) {
                root = root.getParent();
            }
            if (root != null && Files.isDirectory(root.resolve("data"))) {
                return root.resolve("data");
            }
        }
        return cwdData;
    }

    // ------------------------------------------------------------------
    // /api/items —— equipment.ini + tool_item.ini（GBK，一次解析缓存）
    // ------------------------------------------------------------------

    public JSONArray items(String query, int view) {
        List<ItemRow> rows = itemRows();
        JSONArray arr = new JSONArray();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (ItemRow r : rows) {
            if (!q.isEmpty()) {
                boolean hit = r.name().toLowerCase().contains(q)
                        || r.configId().toLowerCase().contains(q);
                if (!hit) {
                    continue;
                }
            }
            if (view > 0 && r.viewId() != view) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("configId", r.configId());
            o.put("name", r.name());
            o.put("viewId", r.viewId());
            o.put("wearable", r.wearable());
            o.put("equipType", r.equipType());
            o.put("script", r.script());
            o.put("maxAmount", r.maxAmount());
            o.put("colorLevel", r.colorLevel());
            o.put("needSex", r.needSex());
            arr.add(o);
            if (arr.size() >= ITEM_SEARCH_LIMIT) {
                break;
            }
        }
        return arr;
    }

    private List<ItemRow> itemRows() {
        List<ItemRow> rows = itemRows;
        if (rows != null) {
            return rows;
        }
        synchronized (this) {
            if (itemRows == null) {
                List<ItemRow> all = new ArrayList<>();
                loadItemIni(shareRoot == null ? null : shareRoot.resolve("item").resolve("equipment.ini"), true, all);
                loadItemIni(shareRoot == null ? null : shareRoot.resolve("item").resolve("tool_item.ini"), false, all);
                itemRows = all;
                log.info("GM catalog items loaded: {} rows", all.size());
            }
            return itemRows;
        }
    }

    private void loadItemIni(Path path, boolean wearable, List<ItemRow> out) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        IniFile ini = IniFile.load(path, GBK);
        for (Map.Entry<String, Map<String, List<String>>> e : ini.sections().entrySet()) {
            String configId = e.getKey();
            // 注释节（//包裹、//修炼药剂…）不进目录。
            if (configId.startsWith("//") || configId.startsWith(";")) {
                continue;
            }
            Map<String, List<String>> sec = e.getValue();
            int viewId = parseInt(first(sec, "viewid"), 1);
            // ViewID 11 = Inner* 身份装分件（InnerCloth/Hat/Pants/Shoes/Weapon/Necklace/
            // Mantle/Fingerring，270 行）：背包只有 1..4 页，发进包即成隐形行；
            // 身份装走「时装/身份装发放」的套装链，这里整组跳过。
            if (viewId < 1 || viewId > 4) {
                continue;
            }
            String script = first(sec, "script");
            String name = displayName(configId, first(sec, "name"));
            String equipType = wearable ? first(sec, "equiptype") : "";
            out.add(new ItemRow(configId, name,
                    viewId, wearable, equipType, script,
                    parseInt(first(sec, "maxamount"), 0), parseInt(first(sec, "colorlevel"), 0),
                    parseInt(first(sec, "needsex"), 2), parseInt(first(sec, "artpack"), 0)));
        }
    }

    /**
     * 展示名：stringname.idres 优先（客户端游戏内名字同源——INI {@code Name} 列存在
     * 抄错/占位：无妄神功 _9th~_18th 的篇章名全错或缺失、基础书名抄成"冰玄劲"、
     * 武器名抄成 xt_50301 占位、SkillPage 名为"书"；2026-11-05 实测两有效名并存的
     * 76,378 行中 47,146 行不一致且 idres 全面更优）；idres 无键再回落有效 INI Name
     * （非空且非纯数字占位）；仍无则空（面板回落显示 ConfigID）。
     */
    private String displayName(String configId, String iniName) {
        String fromIdres = idresLookup(configId);
        if (fromIdres != null && !fromIdres.isEmpty()) {
            return fromIdres;
        }
        return iniName != null && !isNumericPlaceholder(iniName) ? iniName : "";
    }

    /** 纯数字占位名（"10101"/"8"——原表把编号写进了 Name 列）。 */
    private static boolean isNumericPlaceholder(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // /api/cards、/api/hair-cards —— data/card_models.json
    // ------------------------------------------------------------------

    public JSONArray cards() {
        JSONArray c = cards;
        if (c == null) {
            synchronized (this) {
                if (cards == null) {
                    JSONArray fromDb = db != null ? db.cards() : null;
                    cards = fromDb != null ? fromDb : loadCards();
                }
                return cards;
            }
        }
        return c;
    }

    public JSONArray hairCards() {
        JSONArray c = hairCards;
        if (c == null) {
            synchronized (this) {
                if (hairCards == null) {
                    JSONArray fromDb = db != null ? db.hairCards() : null;
                    if (fromDb != null) {
                        hairCards = fromDb;
                        return hairCards;
                    }
                    JSONArray all = cards();
                    JSONArray hair = new JSONArray();
                    for (Object o : all) {
                        JSONObject card = (JSONObject) o;
                        String model = card.getString("model");
                        String name = card.getString("name");
                        if ((model != null && model.toLowerCase().contains("head"))
                                || (name != null && (name.contains("发型") || name.toLowerCase().contains("hair")))) {
                            hair.add(card);
                        }
                    }
                    hairCards = hair;
                }
                return hairCards;
            }
        }
        return c;
    }

    private JSONArray loadCards() {
        JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("card_models.json"));
        JSONArray out = new JSONArray();
        if (root == null) {
            return out;
        }
        JSONObject cardsMap = root.getJSONObject("cards");
        if (cardsMap == null) {
            return out;
        }
        List<String> ids = new ArrayList<>(cardsMap.keySet());
        ids.sort(Comparator.comparingLong(GmCatalog::parseLongOrMax));
        for (String id : ids) {
            JSONObject c = cardsMap.getJSONObject(id);
            if (c == null) {
                continue;
            }
            String model = c.getString("female_model_data");
            if (model == null || model.isEmpty()) {
                model = c.getString("male_model_data");
            }
            JSONObject o = new JSONObject();
            o.put("id", parseLong(id, 0));
            o.put("name", orEmpty(c.getString("name")));
            o.put("config_id", c.getIntValue("config_id"));
            o.put("category", modelCategory(model));
            out.add(o);
        }
        return out;
    }

    private static String modelCategory(String modelData) {
        if (modelData == null || modelData.isEmpty()) {
            return "";
        }
        int comma = modelData.indexOf(',');
        return comma > 0 ? modelData.substring(0, comma) : modelData;
    }

    // ------------------------------------------------------------------
    // /api/clones —— share/clone/cloneinfo.ini（GBK）
    // ------------------------------------------------------------------

    public JSONArray clones() {
        JSONArray c = clones;
        if (c == null) {
            synchronized (this) {
                if (clones == null) {
                    JSONArray out = new JSONArray();
                    IniFile ini = IniFile.load(shareRoot == null ? null
                            : shareRoot.resolve("clone").resolve("cloneinfo.ini"), GBK);
                    for (Map.Entry<String, Map<String, List<String>>> e : ini.sections().entrySet()) {
                        JSONObject o = new JSONObject();
                        o.put("config", e.getKey());
                        o.put("description", orEmpty(first(e.getValue(), "desc")));
                        o.put("difficulties", new JSONArray(List.of(1, 2, 3)));
                        out.add(o);
                    }
                    clones = out;
                }
                return clones;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------
    // /api/scenes —— share/rule/scenes.ini（GBK）全场景表 + stringname.idres 中文名
    // ------------------------------------------------------------------

    /**
     * 场景目录（切图下拉）：纯数字 section + Config=ini\scene\xxx 才是场景
     * （语义镜像 nineyin SceneNames），同一 config 小写去重首见保留。
     * name 解析失败回退 resource 原值。
     */
    public JSONArray scenes() {
        JSONArray s = scenes;
        if (s == null) {
            synchronized (this) {
                if (scenes == null) {
                    JSONArray out = new JSONArray();
                    IniFile ini = IniFile.load(shareRoot == null ? null
                            : shareRoot.resolve("rule").resolve("scenes.ini"), GBK);
                    Set<String> seenConfigs = new HashSet<>();
                    for (Map.Entry<String, Map<String, List<String>>> e : ini.sections().entrySet()) {
                        String section = e.getKey();
                        if (!section.chars().allMatch(Character::isDigit)) {
                            continue;
                        }
                        String config = first(e.getValue(), "config");
                        if (config == null || config.isBlank()
                                || !seenConfigs.add(config.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        int id;
                        try {
                            id = Integer.parseInt(section);
                        } catch (NumberFormatException ex) {
                            continue;
                        }
                        int slash = Math.max(config.lastIndexOf('\\'), config.lastIndexOf('/'));
                        String resource = slash >= 0 ? config.substring(slash + 1) : config;
                        JSONObject o = new JSONObject();
                        o.put("id", id);
                        o.put("config", config);
                        o.put("resource", resource);
                        o.put("name", sceneDisplayName(config, resource));
                        out.add(o);
                    }
                    scenes = out;
                }
                return scenes;
            }
        }
        return s;
    }

    /** 场景中文名：stringname.idres 键 = ini\scene\xxx 或资源名（大小写不定，多级尝试）。 */
    private String sceneDisplayName(String config, String resource) {
        for (String key : new String[] {config, config.toLowerCase(Locale.ROOT),
                resource, resource.toLowerCase(Locale.ROOT)}) {
            String name = idresLookup(key);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return resource;
    }

    // ------------------------------------------------------------------
    // /api/combat-dummy/taolus —— ini/ui/wuxue/school_taolu.ini + ini/shijia/taolu_info.ini
    // ------------------------------------------------------------------

    public JSONArray taolus() {
        JSONArray t = taolus;
        if (t == null) {
            synchronized (this) {
                if (taolus == null) {
                    JSONArray out = new JSONArray();
                    loadTaoluIni(modernRoot == null ? null
                            : modernRoot.resolve("ini").resolve("ui").resolve("wuxue").resolve("school_taolu.ini"), out);
                    loadTaoluIni(modernRoot == null ? null
                            : modernRoot.resolve("ini").resolve("shijia").resolve("taolu_info.ini"), out);
                    taolus = out;
                }
                return taolus;
            }
        }
        return t;
    }

    private void loadTaoluIni(Path path, JSONArray out) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        IniFile ini = IniFile.load(path, GBK);
        for (Map.Entry<String, Map<String, List<String>>> e : ini.sections().entrySet()) {
            String skills = first(e.getValue(), "skill");
            if (skills == null || skills.isEmpty()) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("key", e.getKey());
            String label = idresLookup(e.getKey());
            if (label == null) {
                label = idresLookup("book_" + e.getKey());
            }
            o.put("label", label != null ? label : e.getKey());
            JSONArray list = new JSONArray();
            for (String s : skills.split(",")) {
                if (!s.isBlank()) {
                    list.add(s.trim());
                }
            }
            o.put("skills", list);
            out.add(o);
        }
    }

    private String idresLookup(String key) {
        return stringNames().get(key);
    }

    private Map<String, String> stringNames() {
        Map<String, String> names = stringNames;
        if (names == null) {
            synchronized (this) {
                if (stringNames == null) {
                    stringNames = loadIdres(modernRoot == null ? null
                            : modernRoot.resolve("text").resolve("chineses").resolve("stringname.idres"));
                }
                return stringNames;
            }
        }
        return names;
    }

    private Map<String, String> interfaceNames() {
        Map<String, String> names = interfaceNames;
        if (names == null) {
            synchronized (this) {
                if (interfaceNames == null) {
                    interfaceNames = loadIdres(modernRoot == null ? null
                            : modernRoot.resolve("text").resolve("chineses").resolve("interface.idres"));
                }
                return interfaceNames;
            }
        }
        return names;
    }

    private static Map<String, String> loadIdres(Path path) {
        Map<String, String> map = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path)) {
            return map;
        }
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                map.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            log.warn("idres load failed: {} ({})", path, e.getMessage());
        }
        return map;
    }

    // ------------------------------------------------------------------
    // /api/fashion-catalog —— data/身份装数据.json，按适用性别分组
    // ------------------------------------------------------------------

    public JSONArray fashionCatalog() {
        JSONArray f = fashionCatalog;
        if (f == null) {
            synchronized (this) {
                if (fashionCatalog == null) {
                    JSONArray rows = db != null ? db.identitySuits() : null;
                    if (rows == null) {
                        rows = loadIdentitySuitsFromFile();
                    }
                    fashionCatalog = buildFashionCatalog(rows);
                }
                return fashionCatalog;
            }
        }
        return f;
    }

    /**
     * 时装目录：EquipType 分件分类（equipment.ini/tool_item.ini——时装帽子/衣服/鞋子
     * 分件与 FashionCoat 时装套装）在前，身份装套装（数据表 548 套，按适用性别）在后。
     */
    private JSONArray buildFashionCatalog(JSONArray suitRows) {
        JSONArray out = new JSONArray();
        appendEquipTypeCategory(out, "fashion-coat", "时装衣（FashionCoat 套装）",
                java.util.Set.of("fashioncoat", "newfashioncoat"));
        appendEquipTypeCategory(out, "fashion-hat", "时装帽子（分件）",
                java.util.Set.of("fashionhat"));
        appendEquipTypeCategory(out, "fashion-shoes", "时装鞋子（分件）",
                java.util.Set.of("fashionshoes"));
        out.addAll(groupIdentitySuits(suitRows));
        return out;
    }

    /** 按EquipType 抽装备目录成分类（名称空行跳过；configId 原序保留）。 */
    private void appendEquipTypeCategory(JSONArray out, String key, String label,
                                         java.util.Set<String> equipTypes) {
        JSONArray items = new JSONArray();
        for (ItemRow row : itemRows()) {
            if (!row.wearable() || row.equipType() == null
                    || !equipTypes.contains(row.equipType().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            String name = orEmpty(row.name());
            if (name.isEmpty()) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("configId", row.configId());
            items.add(item);
        }
        if (items.isEmpty()) {
            return;
        }
        JSONObject cat = new JSONObject();
        cat.put("key", key);
        cat.put("label", label);
        cat.put("items", items);
        out.add(cat);
    }

    /** data/身份装数据.json → [{configId,name,needSex}]（与 DB 行形状对齐）。 */
    private JSONArray loadIdentitySuitsFromFile() {
        JSONArray rows = new JSONArray();
        JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("身份装数据.json"));
        JSONObject suits = root != null ? root.getJSONObject("suits") : null;
        if (suits == null) {
            return rows;
        }
        for (Map.Entry<String, Object> e : suits.entrySet()) {
            if (!(e.getValue() instanceof JSONObject suit)) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("configId", e.getKey());
            o.put("name", orEmpty(suit.getString("name")));
            o.put("needSex", suit.getIntValue("need_sex"));
            rows.add(o);
        }
        return rows;
    }

    /** [{configId,name,needSex}] 按适用性别分组（0男装/1女装/其余通用，组间按 sex 升序）。 */
    private static JSONArray groupIdentitySuits(JSONArray rows) {
        JSONArray out = new JSONArray();
        Map<Integer, JSONArray> groups = new TreeMap<>();
        for (Object o : rows) {
            if (!(o instanceof JSONObject row)) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("name", orEmpty(row.getString("name")));
            item.put("configId", row.getString("configId"));
            groups.computeIfAbsent(row.getIntValue("needSex"), k -> new JSONArray()).add(item);
        }
        for (Map.Entry<Integer, JSONArray> g : groups.entrySet()) {
            JSONObject cat = new JSONObject();
            cat.put("key", "sex" + g.getKey());
            cat.put("label", switch (g.getKey()) {
                case 0 -> "男装";
                case 1 -> "女装";
                default -> "通用";
            });
            cat.put("items", g.getValue());
            out.add(cat);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // /api/giftboxes —— tool_item.ini(script=BoxItem) ∪ data/giftbox.json
    // ------------------------------------------------------------------

    public JSONArray giftBoxes() {
        JSONArray g = giftBoxes;
        if (g == null) {
            synchronized (this) {
                if (giftBoxes == null) {
                    // 有内容配置的礼包 ID：MySQL game_gift_boxes 优先，回退 data/giftbox.json。
                    Set<String> contentIds = db != null ? db.giftBoxIds() : null;
                    JSONObject boxes = null;
                    if (contentIds == null) {
                        JSONObject giftJson = readJson(dataDir == null ? null : dataDir.resolve("giftbox.json"));
                        boxes = giftJson != null ? giftJson.getJSONObject("boxes") : null;
                        contentIds = boxes != null ? boxes.keySet() : Set.of();
                    }
                    IniFile giftboxIni = IniFile.load(shareRoot == null ? null
                            : shareRoot.resolve("item").resolve("giftbox.ini"), StandardCharsets.UTF_8);
                    JSONArray out = new JSONArray();
                    for (ItemRow r : itemRows()) {
                        if (!"BoxItem".equalsIgnoreCase(r.script())) {
                            continue;
                        }
                        String name = r.name();
                        if (name.isEmpty()) {
                            name = orEmpty(giftboxIni.value(r.configId(), "name"));
                        }
                        JSONObject o = new JSONObject();
                        o.put("config", r.configId());
                        o.put("hasContent", contentIds.contains(r.configId()));
                        o.put("name", name);
                        out.add(o);
                    }
                    for (String config : contentIds) {
                        boolean exists = false;
                        for (int i = 0; i < out.size(); i++) {
                            if (config.equals(out.getJSONObject(i).getString("config"))) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            JSONObject o = new JSONObject();
                            o.put("config", config);
                            o.put("hasContent", true);
                            o.put("name", orEmpty(giftboxIni.value(config, "name")));
                            out.add(o);
                        }
                    }
                    giftBoxes = out;
                }
                return giftBoxes;
            }
        }
        return g;
    }

    // ------------------------------------------------------------------
    // /api/idle-stances —— ini/b_action_index.ini 节名含 stand
    // ------------------------------------------------------------------

    public JSONArray idleStances() {
        JSONArray s = idleStances;
        if (s == null) {
            synchronized (this) {
                if (idleStances == null) {
                    JSONArray out = new JSONArray();
                    IniFile ini = IniFile.load(modernRoot == null ? null
                            : modernRoot.resolve("ini").resolve("b_action_index.ini"), StandardCharsets.UTF_8);
                    for (Map.Entry<String, Map<String, List<String>>> e : ini.sections().entrySet()) {
                        if (!e.getKey().toLowerCase().contains("stand")) {
                            continue;
                        }
                        JSONObject o = new JSONObject();
                        o.put("action", e.getKey());
                        o.put("skeleton", orEmpty(first(e.getValue(), "skeleton")));
                        out.add(o);
                    }
                    idleStances = out;
                }
                return idleStances;
            }
        }
        return s;
    }

    // ------------------------------------------------------------------
    // /api/meridians —— data/经脉.json
    // ------------------------------------------------------------------

    public JSONArray meridians() {
        JSONArray m = meridians;
        if (m == null) {
            synchronized (this) {
                if (meridians == null) {
                    JSONArray fromDb = db != null ? db.meridians() : null;
                    if (fromDb != null) {
                        meridians = fromDb;
                        return meridians;
                    }
                    JSONArray out = new JSONArray();
                    JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("经脉.json"));
                    JSONArray list = root != null ? root.getJSONArray("meridians") : null;
                    if (list != null) {
                        for (Object o : list) {
                            if (!(o instanceof JSONObject jm)) {
                                continue;
                            }
                            JSONObject r = new JSONObject();
                            r.put("id", orEmpty(jm.getString("id")));
                            r.put("name", orEmpty(jm.getString("name")));
                            r.put("defaultMax", jm.getIntValue("maxLevel"));
                            out.add(r);
                        }
                    }
                    meridians = out;
                }
                return meridians;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------
    // /api/mounts —— tool_item.ini(script=Mount)
    // ------------------------------------------------------------------

    public JSONArray mounts() {
        JSONArray m = mounts;
        if (m == null) {
            synchronized (this) {
                if (mounts == null) {
                    JSONArray out = new JSONArray();
                    for (ItemRow r : itemRows()) {
                        if (!"Mount".equalsIgnoreCase(r.script())) {
                            continue;
                        }
                        JSONObject o = new JSONObject();
                        o.put("configID", r.configId());
                        o.put("name", r.name());
                        o.put("artPack", r.artPack());
                        out.add(o);
                    }
                    mounts = out;
                }
                return mounts;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------
    // /api/repute/status —— share/rule/repute_level.ini + interface.idres
    // ------------------------------------------------------------------

    public JSONObject reputeStatus() {
        JSONObject r = reputeStatus;
        if (r == null) {
            synchronized (this) {
                if (reputeStatus == null) {
                    JSONObject out = new JSONObject();
                    IniFile ini = IniFile.load(shareRoot == null ? null
                            : shareRoot.resolve("rule").resolve("repute_level.ini"), GBK);
                    Map<String, List<String>> reputeSec = ini.sections().get("Repute");
                    JSONArray reputes = new JSONArray();
                    if (reputeSec != null) {
                        long maxValue = 0;
                        Map<String, List<String>> levelSec = ini.sections().get("Level");
                        if (levelSec != null) {
                            for (List<String> vals : levelSec.values()) {
                                for (String v : vals) {
                                    maxValue = Math.max(maxValue, parseLong(v, 0));
                                }
                            }
                        }
                        Map<String, String> display = interfaceNames();
                        for (List<String> vals : reputeSec.values()) {
                            for (String v : vals) {
                                for (String name : v.split(",")) {
                                    name = name.trim();
                                    if (name.isEmpty()) {
                                        continue;
                                    }
                                    JSONObject o = new JSONObject();
                                    o.put("name", name);
                                    String d = display.get(name);
                                    if (d != null) {
                                        d = d.replaceAll("[：:]$", "");
                                    }
                                    o.put("display", d != null ? d : name);
                                    o.put("maxValue", maxValue);
                                    reputes.add(o);
                                }
                            }
                        }
                    }
                    out.put("loaded", !reputes.isEmpty());
                    out.put("reputes", reputes);
                    reputeStatus = out;
                }
                return reputeStatus;
            }
        }
        return r;
    }

    // ------------------------------------------------------------------
    // /api/titles —— MySQL game_titles（titlesSource 注入）→ 回退 data/称号数据.json
    // ------------------------------------------------------------------

    /** 注入 DB 数据源（NineyinServer 启动时接 TitleStore::catalogJson）；注入即失效旧缓存。 */
    public void setTitlesSource(java.util.function.Supplier<JSONArray> source) {
        this.titlesSource = source;
        this.titles = null;
    }

    public JSONArray titles() {
        JSONArray t = titles;
        if (t == null) {
            synchronized (this) {
                if (titles == null) {
                    JSONArray fromDb = db != null ? db.titles() : null;
                    if (fromDb != null) {
                        titles = fromDb;
                        return titles;
                    }
                    java.util.function.Supplier<JSONArray> src = titlesSource;
                    if (src != null) {
                        JSONArray injected = src.get();
                        if (injected != null) {
                            titles = injected;
                            return titles;
                        }
                    }
                    titles = readTitlesFromJson();
                }
                return titles;
            }
        }
        return t;
    }

    private JSONArray readTitlesFromJson() {
        JSONArray out = new JSONArray();
        JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("称号数据.json"));
        JSONObject catalog = root != null ? root.getJSONObject("catalog") : null;
        JSONArray list = catalog != null ? catalog.getJSONArray("titles") : null;
        if (list != null) {
            for (Object o : list) {
                if (o instanceof JSONObject title) {
                    JSONObject r = new JSONObject();
                    r.put("id", title.getIntValue("id"));
                    r.put("name", orEmpty(title.getString("name")));
                    r.put("type", title.getIntValue("type"));
                    out.add(r);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // /api/weather —— share/rule/weather/sceneparastore/ 目录（GM set_weather 值域）
    // ------------------------------------------------------------------

    /**
     * 天气下拉：客户端 SceneParaStore 天气渲染包目录列举（set_weather 闭名单同源，
     * 双端镜像 nineyin GmWeatherPresets 口径）。Key=目录名（即 GM 动作 weather 键），
     * Label=按命名模式派生中文（_001=昼/_501=夜，官方 tianqi_* 文案族），目录缺失
     * 优雅降级为空表（面板显示空下拉，动作入口仍会被游戏侧闭名单拒绝）。
     */
    public JSONArray weather() {
        JSONArray w = weather;
        if (w == null) {
            synchronized (this) {
                if (weather == null) {
                    JSONArray out = new JSONArray();
                    if (shareRoot != null) {
                        Path store = shareRoot.resolve("rule").resolve("weather").resolve("sceneparastore");
                        if (Files.isDirectory(store)) {
                            try (var dirs = Files.list(store)) {
                                dirs.filter(Files::isDirectory)
                                        .map(p -> p.getFileName().toString())
                                        .sorted()
                                        .forEach(name -> {
                                            JSONObject o = new JSONObject();
                                            o.put("Key", name);
                                            o.put("Label", weatherLabel(name));
                                            out.add(o);
                                        });
                            } catch (IOException e) {
                                log.warn("读取 sceneparastore 目录失败：{}", e.getMessage());
                            }
                        }
                    }
                    weather = out;
                }
                return weather;
            }
        }
        return w;
    }

    /** 天气目录名 → 面板中文标签（与 nineyin GmWeatherPresets 标签同口径派生）。 */
    static String weatherLabel(String key) {
        boolean night = key.endsWith("_501");
        String dayNight = night ? "（夜）" : key.endsWith("_001") ? "（昼）" : "";
        String base = key.endsWith("_001") || key.endsWith("_501")
                ? key.substring(0, key.length() - 4) : key;
        return switch (base) {
            case "light_rain" -> "小雨" + dayNight;
            case "middling_rain" -> "中雨" + dayNight;
            case "heavy_rain" -> "大雨" + dayNight;
            case "light_snow" -> "小雪" + dayNight;
            case "middling_snow" -> "中雪" + dayNight;
            case "heavy_snow" -> "暴雪" + dayNight;
            case "light_fog" -> "小雾" + dayNight;
            case "middling_fog" -> "中雾" + dayNight;
            case "heavy_fog" -> "大雾" + dayNight;
            case "light_sandstorm" -> "风沙" + dayNight;
            case "middling_sandstorm" -> "沙暴" + dayNight;
            case "heavy_sandstorm" -> "强沙暴" + dayNight;
            case "lunar_eclipse" -> "月食（天狗吃月）";
            case "solar_eclipse" -> "日食（天狗食日）";
            case "heavy_ocean_rain" -> "海上暴雨";
            case "heavy_snow_huodong01" -> "暴雪·活动";
            case "moon_night_time4" -> "月夜";
            case "heavy_night_time4" -> "深夜";
            default -> scenePeriodLabel(base);
        };
    }

    /** 场景时段族目录名 → 标签（出生/副本/群战等基础时段渲染包）。 */
    private static String scenePeriodLabel(String base) {
        if (base.matches("born04_time[1-6]")) {
            return "出生场景·时段" + base.substring(base.length() - 1);
        }
        if (base.matches("clone003_time[1-4]")) {
            return "副本003·时段" + base.substring(base.length() - 1);
        }
        if (base.matches("groupscene008_time[1-4]")) {
            return "群战008·时段" + base.substring(base.length() - 1);
        }
        return switch (base) {
            case "groupscene023" -> "群战023·时段";
            case "groupscene023_02" -> "群战023·时段二";
            case "groupscene039" -> "群战039·时段";
            case "scene10" -> "场景10·时段";
            case "xianglong_show" -> "降龙演出";
            default -> base;
        };
    }

    // ------------------------------------------------------------------
    // /api/bots —— data/bots.json
    // ------------------------------------------------------------------

    public JSONArray bots() {
        JSONArray b = bots;
        if (b == null) {
            synchronized (this) {
                if (bots == null) {
                    JSONArray fromDb = db != null ? db.bots() : null;
                    if (fromDb != null) {
                        bots = fromDb;
                        return bots;
                    }
                    JSONArray out = new JSONArray();
                    JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("bots.json"));
                    JSONArray list = root != null ? root.getJSONArray("bots") : null;
                    if (list != null) {
                        out.addAll(list);
                    }
                    bots = out;
                }
                return bots;
            }
        }
        return b;
    }

    // ------------------------------------------------------------------
    // /api/origin/status —— data/origin/origins.json
    // ------------------------------------------------------------------

    public JSONObject originStatus() {
        JSONObject o = originStatus;
        if (o == null) {
            synchronized (this) {
                if (originStatus == null) {
                    Long count = db != null ? db.originCount() : null;
                    if (count == null) {
                        JSONObject root = readJson(dataDir == null ? null
                                : dataDir.resolve("origin").resolve("origins.json"));
                        JSONArray origins = root != null ? root.getJSONArray("origins") : null;
                        count = origins == null ? null : (long) origins.size();
                    }
                    JSONObject out = new JSONObject();
                    out.put("loaded", count != null && count > 0);
                    out.put("total", count != null ? count : 0);
                    originStatus = out;
                }
                return originStatus;
            }
        }
        return o;
    }

    // ------------------------------------------------------------------
    // /api/recast-affixes —— senior_prop_dire.ini（普通词条）+ senior_weapon_*.ini（招式词条池）
    // senior_prop 行格式：r=<tj/yg/...>,<PropName>,<id>,<id>,…
    // senior_weapon 行格式：r=<PropName>,<id>,<id>,…
    // ------------------------------------------------------------------

    public JSONObject recastAffixes() {
        JSONObject r = recastAffixes;
        if (r == null) {
            synchronized (this) {
                if (recastAffixes == null) {
                    JSONObject out = new JSONObject();
                    Map<Long, JSONObject> prop = new LinkedHashMap<>();
                    IniFile propDire = IniFile.load(shareRoot == null ? null
                            : shareRoot.resolve("item").resolve("senior_prop_dire.ini"), StandardCharsets.UTF_8);
                    for (Map<String, List<String>> sec : propDire.sections().values()) {
                        for (String line : sec.getOrDefault("r", List.of())) {
                            String[] parts = line.split(",");
                            if (parts.length < 3) {
                                continue;
                            }
                            String propName = parts[1].trim();
                            for (int i = 2; i < parts.length; i++) {
                                long id = parseLong(parts[i], -1);
                                if (id > 0) {
                                    prop.putIfAbsent(id, affixEntry(id, propName, null));
                                }
                            }
                        }
                    }
                    Map<Long, JSONObject> skill = new LinkedHashMap<>();
                    for (String file : List.of("senior_weapon_dire.ini", "senior_weapon_rand.ini")) {
                        IniFile weaponIni = IniFile.load(shareRoot == null ? null
                                : shareRoot.resolve("item").resolve(file), StandardCharsets.UTF_8);
                        for (Map.Entry<String, Map<String, List<String>>> e : weaponIni.sections().entrySet()) {
                            String weapon = weaponFamily(e.getKey());
                            for (String line : e.getValue().getOrDefault("r", List.of())) {
                                String[] parts = line.split(",");
                                if (parts.length < 2) {
                                    continue;
                                }
                                String propName = parts[0].trim();
                                for (int i = 1; i < parts.length; i++) {
                                    long id = parseLong(parts[i], -1);
                                    if (id > 0) {
                                        skill.putIfAbsent(id, affixEntry(id, propName, weapon));
                                    }
                                }
                            }
                        }
                    }
                    out.put("loaded", !prop.isEmpty() || !skill.isEmpty());
                    out.put("prop", new JSONArray(new ArrayList<>(prop.values())));
                    out.put("skill", new JSONArray(new ArrayList<>(skill.values())));
                    recastAffixes = out;
                }
                return recastAffixes;
            }
        }
        return r;
    }

    private static JSONObject affixEntry(long id, String source, String weapon) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("source", source);
        if (weapon != null) {
            o.put("weapon", weapon);
        }
        return o;
    }

    private static String weaponFamily(String section) {
        int us = section.indexOf('_');
        String family = us > 0 ? section.substring(0, us) : section;
        return switch (family.toLowerCase()) {
            case "blade" -> "刀";
            case "sword" -> "剑";
            case "dagger" -> "刺";
            case "cudgel", "staff" -> "棍";
            case "spear" -> "枪";
            case "fist", "palm" -> "徒手";
            default -> family;
        };
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private JSONObject readJson(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return JSON.parseObject(text);
        } catch (Exception e) {
            log.warn("json load failed: {} ({})", path, e.getMessage());
            return null;
        }
    }

    private static String first(Map<String, List<String>> section, String key) {
        List<String> values = section.get(key);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    private static int parseInt(String v, int def) {
        return (int) parseLong(v, def);
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLongOrMax(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
