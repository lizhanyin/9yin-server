package com.nineyin.server.game.avatar.neigong;

import com.nineyin.util.ServerPaths;
import com.nineyin.commom.cache.ResourceCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 现代内功效果目录，逐行对齐 Go cmd/protocol-probe/neigong_effects.go：
 * skill/neigong 的 static/varprop + buff_new.ini + modifypack 两表 →
 * StaticData+level 的 Buff/属性加成/SkillModify。
 */
public final class NeiGongCatalog {

    public record Modifier(String target, String name, double value, int mode) {
    }

    private static final class VarProp {
        int level;
        String buffId = "";
        int buffLevel;
        final Map<String, Integer> direct = new HashMap<>();
        final List<Integer> propPacks = new ArrayList<>();
        final List<Integer> skillPacks = new ArrayList<>();
    }

    private record Range(int min, int max) {
    }

    public record Effect(int staticData, String buffId, int buffLevel,
                         Map<String, Integer> stats, Map<String, List<Modifier>> skillModifiers) {
    }

    private final Map<Integer, Range> ranges = new HashMap<>();
    private final Map<Integer, VarProp> varProps = new HashMap<>();
    private final Map<String, Integer> buffStatic = new HashMap<>();
    private final Map<Integer, List<Modifier>> propPacks = new HashMap<>();
    private final Map<Integer, List<Modifier>> skillPacks = new HashMap<>();
    // 内功属性三件套（四期，bak 同源）：StaticData→BelongType / configId→School / configId→nWuXing
    private final Map<Integer, String> belongTypes = new HashMap<>();
    private final Map<String, String> schools = new HashMap<>();
    private final Map<String, Integer> wuXing = new HashMap<>();
    // GM learn_all_neigong 全目录（neigong.ini 声明序）：QName→静态档/上限/首级曲线。
    private final Map<String, NeiGongDef> bookDefs = new LinkedHashMap<>();

    /** Go loadModernNeiGongCatalog 的 sync.Once 等价：目录级缓存（serverRoot 变化自动换表）。 */
    public static NeiGongCatalog load() {
        return ResourceCache.of("neigong-catalog:" + ServerPaths.projectPath("resources", "modern", "share"), () -> {
            NeiGongCatalog catalog = new NeiGongCatalog();
            catalog.loadAll(ServerPaths.projectPath("resources", "modern", "share"));
            return catalog;
        });
    }

    /** 整目录解析（测试注入临时 share 根用）。 */
    public static NeiGongCatalog loadShare(Path shareRoot) {
        NeiGongCatalog catalog = new NeiGongCatalog();
        catalog.loadAll(shareRoot);
        return catalog;
    }

    /**
     * WuXing 五行/属性枚举（客户端本地化键 ui_wuxue_prop_{N}，用户 2026-11-01 确认对照）：
     * 技能 1=刚 / 2=柔 / 3=阴 / 4=阳；内功 5=太极 / 6=阴柔 / 7=阳刚。
     * 全量数据交叉验证：BelongType ng_mp_tj→5 / ng_mp_yr→6 / ng_mp_yg→7（100% 吻合）。
     */
    public static final int WUXING_GANG = 1;      // 技能：刚
    public static final int WUXING_ROU = 2;       // 技能：柔
    public static final int WUXING_YIN = 3;       // 技能：阴
    public static final int WUXING_YANG = 4;      // 技能：阳
    public static final int WUXING_TAIJI = 5;     // 内功：太极
    public static final int WUXING_YINROU = 6;    // 内功：阴柔
    public static final int WUXING_YANGGANG = 7;  // 内功：阳刚

    /** 内功属性名（applyModernEffect 同款推导）：BelongType ng_mp_tj→太极 / yg→阳刚 / yr→阴柔；
     *  移花（ng_yh_ 前缀）特判阴柔；未知归属返回空串。 */
    public String attributeOf(int staticData, String configId) {
        String belong = belongTypes.getOrDefault(staticData, "");
        switch (belong) {
            case "ng_mp_tj":
                return "太极";
            case "ng_mp_yg":
                return "阳刚";
            case "ng_mp_yr":
                return "阴柔";
            default:
                break;
        }
        if (configId != null && configId.startsWith("ng_yh_")) {
            return "阴柔";
        }
        return "";
    }

    /** configId → 门派代码（neigong.ini School=，如 ng_wd；未收录返回空串）。 */
    public String schoolOf(String configId) {
        String school = configId == null ? null : schools.get(configId);
        return school == null ? "" : school;
    }

    /** configId → nWuXing 五行 int（wuxuebaseinfo.ini；未收录 0；值语义见 WUXING_* 常量）。 */
    public int wuXingOf(String configId) {
        Integer value = configId == null ? null : wuXing.get(configId);
        return value == null ? 0 : value;
    }

    private void loadAll(Path shareRoot) {
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "neigong", "neigong_static.ini")), java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            Range range = ranges.getOrDefault(id, new Range(0, 0));
            switch (key) {
                case "MinVarPropNo" -> ranges.put(id, new Range(parseIntOrDefault(value), range.max()));
                case "MaxVarPropNo" -> ranges.put(id, new Range(range.min(), parseIntOrDefault(value)));
                case "BelongType" -> belongTypes.put(id, value.trim());
                default -> {
                }
            }
        });
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "neigong", "neigong_varprop.ini")), java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            VarProp record = varProps.computeIfAbsent(id, ignored -> new VarProp());
            switch (key) {
                case "Level" -> record.level = parseIntOrDefault(value);
                case "BufferID" -> record.buffId = value.trim();
                case "BufferLevel" -> record.buffLevel = parseIntOrDefault(value);
                case "@PropModifyPackRec" -> record.propPacks.add(parseIntOrDefault(value));
                case "@SkillModifyPackRec" -> record.skillPacks.add(parseIntOrDefault(value));
                case "StrAdd", "StaAdd", "DexAdd", "IngAdd", "SpiAdd" -> record.direct.put(key, parseIntOrDefault(value));
                default -> {
                }
            }
        });
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "buff_new.ini")), java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
            if (!key.equals("StaticData")) {
                return;
            }
            try {
                buffStatic.put(section, Integer.parseUnsignedInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // Go 会报错；此处资源已知规整，跳过等价于失败装载。
            }
        });
        loadModifierPacks(shareRoot.resolve(Path.of("modifypack", "proppack.ini")), propPacks);
        loadModifierPacks(shareRoot.resolve(Path.of("modifypack", "skillpack.ini")), skillPacks);
        loadSchools(shareRoot.resolve(Path.of("skill", "neigong", "neigong.ini")));
        loadBookDefs(shareRoot);
        ResourceCache.ini(shareRoot.resolve(Path.of("faculty", "wuxuebaseinfo.ini")),
                java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if ("nWuXing".equals(key)) {
                        Integer parsed = parseInt(value);
                        if (parsed != null) {
                            wuXing.put(section.trim(), parsed);
                        }
                    }
                });
    }

    /** neigong.ini：QName（缺省节名）→ School 门派代码。 */
    private void loadSchools(Path ini) {
        Map<String, String> qnameToSection = new HashMap<>();
        Map<String, String> sectionSchool = new HashMap<>();
        ResourceCache.ini(ini, java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
            if ("QName".equals(key)) {
                qnameToSection.put(section.trim(), value.trim());
            } else if ("School".equals(key)) {
                sectionSchool.put(section.trim(), value.trim());
            }
        });
        for (Map.Entry<String, String> entry : sectionSchool.entrySet()) {
            String configId = qnameToSection.getOrDefault(entry.getKey(), entry.getKey());
            schools.put(configId, entry.getValue());
        }
    }

    /** neigong.ini 目录项（GM learn_all_neigong 补书源）：曲线缺档回退 starter 种子口径 700/120。 */
    public record NeiGongDef(String configId, int staticData, int itemType, String school,
                             int maxLevel, int firstTotal, int firstPower) {
    }

    /** 全部可收录内功（neigong.ini 声明序；仅收 QName+StaticData+varprop 区间齐全的条目）。 */
    public List<NeiGongDef> allBooks() {
        return new ArrayList<>(bookDefs.values());
    }

    /** 按 configId（ng_* QName）取内功目录项；未收录（无 varprop 区间/未知）返回 null。 */
    public NeiGongDef book(String configId) {
        return configId == null ? null : bookDefs.get(configId.trim());
    }

    /** neigong.ini 全量 + wuxuelevelinfo.ini 首级曲线（r=level,total,power；r=1 行）。 */
    private void loadBookDefs(Path shareRoot) {
        Map<String, int[]> firstLevel = new HashMap<>();
        ResourceCache.ini(shareRoot.resolve(Path.of("faculty", "wuxuelevelinfo.ini")),
                java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (!"r".equals(key) || !section.startsWith("ng_")) {
                        return;
                    }
                    String[] parts = value.split(",");
                    if (parts.length < 3 || !"1".equals(parts[0].trim())) {
                        return;
                    }
                    Integer total = parseInt(parts[1].trim());
                    Integer power = parseInt(parts[2].trim());
                    if (total != null && power != null) {
                        firstLevel.put(section.trim(), new int[]{total, power});
                    }
                });
        Map<String, String> qnames = new LinkedHashMap<>();
        Map<String, Integer> staticDatas = new HashMap<>();
        Map<String, Integer> itemTypes = new HashMap<>();
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "neigong", "neigong.ini")),
                java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (section.isBlank() || section.startsWith("-")) {
                        return;
                    }
                    switch (key) {
                        case "QName" -> qnames.put(section.trim(), value.trim());
                        case "StaticData" -> staticDatas.put(section.trim(), parseIntOrDefault(value));
                        case "ItemType" -> itemTypes.put(section.trim(), parseIntOrDefault(value));
                        default -> {
                        }
                    }
                });
        for (Map.Entry<String, String> entry : qnames.entrySet()) {
            String configId = entry.getValue();
            if (configId.isEmpty()) {
                continue;
            }
            int staticData = staticDatas.getOrDefault(entry.getKey(), 0);
            Range range = ranges.get(staticData);
            if (staticData <= 0 || range == null || range.min() <= 0 || range.max() < range.min()) {
                continue; // 无 varprop 区间：effect() 无从解析，不收录
            }
            int[] curve = firstLevel.get(configId);
            bookDefs.put(configId, new NeiGongDef(configId, staticData,
                    itemTypes.getOrDefault(entry.getKey(), 1002),
                    schools.getOrDefault(configId, ""),
                    range.max() - range.min() + 1,
                    curve != null ? curve[0] : 700,
                    curve != null ? curve[1] : 120));
        }
    }

    /** Go effect：按 StaticData 区间找 level 匹配 varprop，聚合直接加成 + PropPack。 */
    public Effect effect(int staticData, int level) {
        Range entry = ranges.get(staticData);
        if (entry == null || entry.min() <= 0 || entry.max() < entry.min()) {
            throw new IllegalStateException("missing modern neigong StaticData=" + staticData + " range");
        }
        VarProp record = null;
        for (int id = entry.min(); id <= entry.max(); id++) {
            VarProp candidate = varProps.get(id);
            if (candidate != null && candidate.level == level) {
                record = candidate;
                break;
            }
        }
        if (record == null) {
            throw new IllegalStateException("StaticData=" + staticData + " has no varprop at level=" + level);
        }
        Map<String, Integer> stats = new HashMap<>();
        Map<String, List<Modifier>> skillModifiers = new HashMap<>();
        for (Map.Entry<String, Integer> direct : record.direct.entrySet()) {
            stats.merge(direct.getKey(), direct.getValue(), Integer::sum);
        }
        int resolvedStatic = 0;
        if (!record.buffId.isEmpty()) {
            Integer mapped = buffStatic.get(record.buffId);
            if (mapped == null || mapped == 0) {
                throw new IllegalStateException("BufferID=" + record.buffId + " has no StaticData in buff_new.ini");
            }
            resolvedStatic = mapped;
        }
        for (int id : record.propPacks) {
            for (Modifier modifier : propPacks.getOrDefault(id, List.of())) {
                if (modifier.target().isEmpty() && modifier.mode() == 0) {
                    stats.merge(modifier.name(), (int) modifier.value(), Integer::sum);
                }
            }
        }
        for (int id : record.skillPacks) {
            for (Modifier modifier : skillPacks.getOrDefault(id, List.of())) {
                if (modifier.target().isEmpty()) {
                    continue;
                }
                skillModifiers.computeIfAbsent(modifier.target(), ignored -> new ArrayList<>()).add(modifier);
            }
        }
        return new Effect(resolvedStatic, record.buffId, record.buffLevel, stats, skillModifiers);
    }

    private static void loadModifierPacks(Path path, Map<Integer, List<Modifier>> destination) {
        ResourceCache.ini(path, java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
            if (!key.equals("r")) {
                return;
            }
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            Modifier modifier = parseModifier(value);
            if (modifier != null) {
                destination.computeIfAbsent(id, ignored -> new ArrayList<>()).add(modifier);
            }
        });
    }

    /** Go parseModernModifier：prop 包 3 元组 / skill 包 4 元组，第二元可否解析为数字区分。 */
    private static Modifier parseModifier(String value) {
        String[] parts = value.split(",", -1);
        int head = 0;
        while (head < parts.length && parts[head].isBlank()) {
            head++;
        }
        String[] tokens = new String[parts.length - head];
        for (int i = head; i < parts.length; i++) {
            tokens[i - head] = parts[i].trim();
        }
        if (tokens.length < 3) {
            return null;
        }
        Double second = tryParseDouble(tokens[1]);
        if (second != null) {
            return new Modifier("", tokens[0], second, (int) parseLongOrZero(tokens[2]));
        }
        if (tokens.length < 4) {
            return null;
        }
        Double third = tryParseDouble(tokens[2]);
        if (third == null) {
            return null;
        }
        return new Modifier(tokens[0], tokens[1], third, (int) parseLongOrZero(tokens[3]));
    }

    private static Double tryParseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLongOrZero(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // INI 逐行解析走 ResourceCache.ini（Latin-1 无损：GBK/UTF-8 混排资源的键值均为 ASCII），
    // 表按文件路径缓存，重复 load 不再重读。

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOrDefault(String text) {
        Integer parsed = parseInt(text);
        return parsed == null ? 0 : parsed;
    }
}
