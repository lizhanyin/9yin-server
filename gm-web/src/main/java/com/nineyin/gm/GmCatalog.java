package com.nineyin.gm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GM 面板目录数据服务：从部署目录（share 根 + data/）懒加载并缓存各下拉框数据源。
 * 所有加载器对文件缺失/损坏均优雅降级（空列表或 loaded=false）。
 *
 * <p>数据源（编码已核实）：equipment.ini / tool_item.ini / cloneinfo.ini / weathereffect.ini /
 * repute_level.ini / school_taolu.ini 为 GBK；stringname.idres / interface.idres 为 UTF-8+BOM；
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
            JSONObject o = new JSONObject(true);
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
            Map<String, List<String>> sec = e.getValue();
            String script = first(sec, "script");
            String name = first(sec, "name");
            String equipType = wearable ? first(sec, "equiptype") : "";
            out.add(new ItemRow(e.getKey(), name,
                    parseInt(first(sec, "viewid"), 1), wearable, equipType, script,
                    parseInt(first(sec, "maxamount"), 0), parseInt(first(sec, "colorlevel"), 0),
                    parseInt(first(sec, "needsex"), 2), parseInt(first(sec, "artpack"), 0)));
        }
    }

    // ------------------------------------------------------------------
    // /api/cards、/api/hair-cards —— data/card_models.json
    // ------------------------------------------------------------------

    public JSONArray cards() {
        JSONArray c = cards;
        if (c == null) {
            synchronized (this) {
                if (cards == null) {
                    cards = loadCards();
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
            JSONObject o = new JSONObject(true);
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
                        JSONObject o = new JSONObject(true);
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
            JSONObject o = new JSONObject(true);
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
                    JSONArray out = new JSONArray();
                    JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("身份装数据.json"));
                    JSONObject suits = root != null ? root.getJSONObject("suits") : null;
                    if (suits != null) {
                        Map<Integer, JSONArray> groups = new TreeMap<>();
                        for (Map.Entry<String, Object> e : suits.entrySet()) {
                            if (!(e.getValue() instanceof JSONObject suit)) {
                                continue;
                            }
                            int sex = suit.getIntValue("need_sex");
                            JSONObject item = new JSONObject(true);
                            item.put("name", orEmpty(suit.getString("name")));
                            item.put("configId", e.getKey());
                            groups.computeIfAbsent(sex, k -> new JSONArray()).add(item);
                        }
                        for (Map.Entry<Integer, JSONArray> g : groups.entrySet()) {
                            JSONObject cat = new JSONObject(true);
                            cat.put("key", "sex" + g.getKey());
                            cat.put("label", switch (g.getKey()) {
                                case 0 -> "男装";
                                case 1 -> "女装";
                                default -> "通用";
                            });
                            cat.put("items", g.getValue());
                            out.add(cat);
                        }
                    }
                    fashionCatalog = out;
                }
                return fashionCatalog;
            }
        }
        return f;
    }

    // ------------------------------------------------------------------
    // /api/giftboxes —— tool_item.ini(script=BoxItem) ∪ data/giftbox.json
    // ------------------------------------------------------------------

    public JSONArray giftBoxes() {
        JSONArray g = giftBoxes;
        if (g == null) {
            synchronized (this) {
                if (giftBoxes == null) {
                    JSONObject giftJson = readJson(dataDir == null ? null : dataDir.resolve("giftbox.json"));
                    JSONObject boxes = giftJson != null ? giftJson.getJSONObject("boxes") : null;
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
                        JSONObject o = new JSONObject(true);
                        o.put("config", r.configId());
                        o.put("hasContent", boxes != null && boxes.containsKey(r.configId()));
                        o.put("name", name);
                        out.add(o);
                    }
                    if (boxes != null) {
                        for (String config : boxes.keySet()) {
                            boolean exists = false;
                            for (int i = 0; i < out.size(); i++) {
                                if (config.equals(out.getJSONObject(i).getString("config"))) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                JSONObject o = new JSONObject(true);
                                o.put("config", config);
                                o.put("hasContent", true);
                                o.put("name", orEmpty(giftboxIni.value(config, "name")));
                                out.add(o);
                            }
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
                        JSONObject o = new JSONObject(true);
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
                    JSONArray out = new JSONArray();
                    JSONObject root = readJson(dataDir == null ? null : dataDir.resolve("经脉.json"));
                    JSONArray list = root != null ? root.getJSONArray("meridians") : null;
                    if (list != null) {
                        for (Object o : list) {
                            if (!(o instanceof JSONObject jm)) {
                                continue;
                            }
                            JSONObject r = new JSONObject(true);
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
                        JSONObject o = new JSONObject(true);
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
                    JSONObject out = new JSONObject(true);
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
                                    JSONObject o = new JSONObject(true);
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
                    java.util.function.Supplier<JSONArray> src = titlesSource;
                    if (src != null) {
                        JSONArray fromDb = src.get();
                        if (fromDb != null) {
                            titles = fromDb;
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
                    JSONObject r = new JSONObject(true);
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
    // /api/weather —— share/rule/weather/weathereffect.ini（GBK）
    // ------------------------------------------------------------------

    public JSONArray weather() {
        JSONArray w = weather;
        if (w == null) {
            synchronized (this) {
                if (weather == null) {
                    JSONArray out = new JSONArray();
                    IniFile ini = IniFile.load(shareRoot == null ? null
                            : shareRoot.resolve("rule").resolve("weather").resolve("weathereffect.ini"), GBK);
                    for (Map.Entry<String, Map<String, List<String>>> e : ini.sections().entrySet()) {
                        JSONObject o = new JSONObject(true);
                        o.put("Key", e.getKey());
                        o.put("Label", e.getKey());
                        o.put("TXID", orEmpty(first(e.getValue(), "configid")));
                        out.add(o);
                    }
                    weather = out;
                }
                return weather;
            }
        }
        return w;
    }

    // ------------------------------------------------------------------
    // /api/bots —— data/bots.json
    // ------------------------------------------------------------------

    public JSONArray bots() {
        JSONArray b = bots;
        if (b == null) {
            synchronized (this) {
                if (bots == null) {
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
                    JSONObject out = new JSONObject(true);
                    JSONObject root = readJson(dataDir == null ? null
                            : dataDir.resolve("origin").resolve("origins.json"));
                    JSONArray origins = root != null ? root.getJSONArray("origins") : null;
                    out.put("loaded", origins != null && !origins.isEmpty());
                    out.put("total", origins != null ? origins.size() : 0);
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
                    JSONObject out = new JSONObject(true);
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
        JSONObject o = new JSONObject(true);
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
