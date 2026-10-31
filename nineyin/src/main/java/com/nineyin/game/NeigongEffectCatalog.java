package com.nineyin.game;

import com.nineyin.scene.SceneManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内功 per-level 特效目录（移植参考服 Go neigong_effects.go）：
 * share/skill/neigong/neigong_static.ini（StaticData → varprop 记录号区间）+
 * neigong_varprop.ini（每级 BufferID/BufferLevel/@PropModifyPackRec/五维 Add）+
 * share/skill/buff_new.ini（BufferID → StaticData）+
 * share/modifypack/proppack.ini、skillpack.ini（r= 修正行）。
 * {@link #effect(int, int)} 供 {@link FacultyState.Book} 回填运功 Buff 与属性 Add；
 * 文件缺失保持空目录（书本保留种子值，Go refreshAllModernEffects 同语义）。
 */
public final class NeigongEffectCatalog {

    private static final Logger log = LoggerFactory.getLogger(NeigongEffectCatalog.class);

    /** proppack/skillpack 的 r= 修正行（proppack：target 空 + mode 0 才进 stats）。 */
    public record Modifier(String target, String name, double value, int mode) {
    }

    /** 单本内功当前等级的完整特效（直写五维 Add + 属性包叠加 + Buff 解析结果）。 */
    public record Effect(String buffId, int buffStaticData, int buffLevel,
                         Map<String, Integer> stats,
                         Map<String, List<Modifier>> skillModifiers) {

        /** stats 取值（缺项 0）。 */
        public int stat(String name) {
            Integer value = stats.get(name);
            return value == null ? 0 : value;
        }

        /** 千分数键（DamageMul 族）：varprop 存整数千分比，÷1000 为倍率（exe 同款换算）。 */
        public float statPerMille(String name) {
            Integer value = stats.get(name);
            return value == null ? 0f : value / 1000f;
        }
    }

    /** neigong_varprop.ini 单节（记录号 → 等级行）。 */
    private static final class VarProp {
        int level = -1;
        String buffId = "";
        int buffLevel;
        final Map<String, Integer> direct = new LinkedHashMap<>();
        final List<Integer> propPacks = new ArrayList<>();
        final List<Integer> skillPacks = new ArrayList<>();
    }

    private static volatile NeigongEffectCatalog instance;

    private final Map<Integer, int[]> ranges = new HashMap<>();
    private final Map<Integer, VarProp> varProps = new HashMap<>();
    private final Map<String, Integer> buffStatic = new HashMap<>();
    private final Map<Integer, List<Modifier>> propPacks = new HashMap<>();
    private final Map<Integer, List<Modifier>> skillPacks = new HashMap<>();
    // StaticData → BelongType（neigong_static.ini；ng_mp_tj/yg/yr → 太极/阳刚/阴柔）
    private final Map<Integer, String> belongTypes = new HashMap<>();
    // configId → School 门派代码（neigong.ini QName/School）
    private final Map<String, String> schools = new HashMap<>();
    // configId → nWuXing 五行（faculty/wuxuebaseinfo.ini）
    private final Map<String, Integer> wuXing = new HashMap<>();

    private NeigongEffectCatalog() {
    }

    /** 懒加载单例（share root 走 {@link SceneManager#locateShareRoot()}）。 */
    public static NeigongEffectCatalog instance() {
        NeigongEffectCatalog catalog = instance;
        if (catalog == null) {
            synchronized (NeigongEffectCatalog.class) {
                if (instance == null) {
                    instance = load(SceneManager.locateShareRoot());
                }
                catalog = instance;
            }
        }
        return catalog;
    }

    /** 解析四张表；缺文件保留已解析部分（空目录容忍，不抛异常）。 */
    static NeigongEffectCatalog load(Path shareRoot) {
        NeigongEffectCatalog catalog = new NeigongEffectCatalog();
        catalog.loadStaticTable(shareRoot.resolve("skill").resolve("neigong")
                .resolve("neigong_static.ini"));
        catalog.loadVarProps(shareRoot.resolve("skill").resolve("neigong")
                .resolve("neigong_varprop.ini"));
        catalog.loadBuffStatic(shareRoot.resolve("skill").resolve("buff_new.ini"));
        catalog.loadModifierPacks(shareRoot.resolve("modifypack").resolve("proppack.ini"),
                catalog.propPacks);
        catalog.loadModifierPacks(shareRoot.resolve("modifypack").resolve("skillpack.ini"),
                catalog.skillPacks);
        catalog.loadSchools(shareRoot.resolve("skill").resolve("neigong")
                .resolve("neigong.ini"));
        catalog.loadWuXing(shareRoot.resolve("faculty").resolve("wuxuebaseinfo.ini"));
        log.info("neigong effect catalog ready staticRanges={} varProps={} buffs={} propPacks={} "
                        + "skillPacks={} schools={} wuxing={} root={}",
                catalog.ranges.size(), catalog.varProps.size(), catalog.buffStatic.size(),
                catalog.propPacks.size(), catalog.skillPacks.size(),
                catalog.schools.size(), catalog.wuXing.size(), shareRoot);
        return catalog;
    }

    /** 内功属性名（applyModernEffect 同款推导）：BelongType ng_mp_tj→太极 / yg→阳刚 / yr→阴柔；
     *  移花（ng_yh_ 前缀）特判阴柔；未知归属返回空串。 */
    public String attributeOf(int staticData, String configId) {
        String belong = belongTypes.get(staticData);
        if (belong == null) {
            belong = "";
        }
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

    /** configId → nWuXing 五行 int（wuxuebaseinfo.ini；未收录 0；值语义见 WUXING_* 常量）。 */
    public int wuXingOf(String configId) {
        Integer value = configId == null ? null : wuXing.get(configId);
        return value == null ? 0 : value;
    }

    /** 是否解析到数据（全空 = 布置缺 share 目录，书本走种子值）。 */
    public boolean isEmpty() {
        return ranges.isEmpty() || varProps.isEmpty();
    }

    /**
     * 某本内功在某级的特效：static 区间内找 Level 匹配的 varprop 记录，
     * 直写五维 Add 与 @PropModifyPackRec 属性包叠加（target 空 + mode 0），
     * BufferID 经 buff_new.ini 解析 StaticData。缺行返回 null（调用方保留原值）。
     */
    public Effect effect(int staticData, int level) {
        int[] range = ranges.get(staticData);
        if (range == null || range[0] <= 0 || range[1] < range[0]) {
            return null;
        }
        VarProp record = null;
        for (int id = range[0]; id <= range[1]; id++) {
            VarProp candidate = varProps.get(id);
            if (candidate != null && candidate.level == level) {
                record = candidate;
                break;
            }
        }
        if (record == null) {
            return null;
        }
        Map<String, Integer> stats = new LinkedHashMap<>(record.direct);
        for (int packId : record.propPacks) {
            for (Modifier modifier : propPacks.getOrDefault(packId, List.of())) {
                if (modifier.target().isEmpty() && modifier.mode() == 0) {
                    stats.merge(modifier.name(), (int) modifier.value(), Integer::sum);
                }
            }
        }
        Map<String, List<Modifier>> skillModifiers = new LinkedHashMap<>();
        for (int packId : record.skillPacks) {
            for (Modifier modifier : skillPacks.getOrDefault(packId, List.of())) {
                if (!modifier.target().isEmpty()) {
                    skillModifiers.computeIfAbsent(modifier.target(),
                            key -> new ArrayList<>()).add(modifier);
                }
            }
        }
        int buffStaticData = record.buffId.isEmpty() ? 0
                : buffStatic.getOrDefault(record.buffId, 0);
        return new Effect(record.buffId, buffStaticData, record.buffLevel,
                stats, skillModifiers);
    }

    // ---- 解析（GBK ini；节号/键均为 ASCII，逐行 best-effort） ----

    private void loadStaticTable(Path ini) {
        forEachEntry(ini, (section, key, value) -> {
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            int[] range = ranges.computeIfAbsent(id, ignored -> new int[2]);
            switch (key) {
                case "MinVarPropNo" -> range[0] = parseIntOrDefault(value, 0);
                case "MaxVarPropNo" -> range[1] = parseIntOrDefault(value, 0);
                case "BelongType" -> belongTypes.put(id, value.trim());
                default -> {
                }
            }
        });
    }

    /** neigong.ini：QName（缺省节名）→ School 门派代码。 */
    private void loadSchools(Path ini) {
        Map<String, String> qnameToSection = new HashMap<>();
        Map<String, String> sectionSchool = new HashMap<>();
        forEachEntry(ini, (section, key, value) -> {
            if ("QName".equals(key)) {
                qnameToSection.put(section.trim(), value.trim());
            } else if ("School".equals(key)) {
                sectionSchool.put(section.trim(), value.trim());
            }
        });
        for (Map.Entry<String, String> e : sectionSchool.entrySet()) {
            String configId = qnameToSection.getOrDefault(e.getKey(), e.getKey());
            schools.put(configId, e.getValue());
        }
    }

    /** wuxuebaseinfo.ini：节名 = ConfigID → nWuXing。 */
    private void loadWuXing(Path ini) {
        forEachEntry(ini, (section, key, value) -> {
            if ("nWuXing".equals(key)) {
                Integer v = parseInt(value);
                if (v != null) {
                    wuXing.put(section.trim(), v);
                }
            }
        });
    }

    private void loadVarProps(Path ini) {
        forEachEntry(ini, (section, key, value) -> {
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            VarProp record = varProps.computeIfAbsent(id, ignored -> new VarProp());
            Integer parsed;
            switch (key) {
                case "Level" -> {
                    parsed = parseInt(value);
                    if (parsed != null) {
                        record.level = parsed;
                    }
                }
                case "BufferID" -> record.buffId = value.trim();
                case "BufferLevel" -> {
                    parsed = parseInt(value);
                    if (parsed != null) {
                        record.buffLevel = parsed;
                    }
                }
                case "@PropModifyPackRec" -> {
                    parsed = parseInt(value);
                    if (parsed != null) {
                        record.propPacks.add(parsed);
                    }
                }
                case "@SkillModifyPackRec" -> {
                    parsed = parseInt(value);
                    if (parsed != null) {
                        record.skillPacks.add(parsed);
                    }
                }
                case "StrAdd", "StaAdd", "DexAdd", "IngAdd", "SpiAdd" -> {
                    parsed = parseInt(value);
                    if (parsed != null) {
                        record.direct.put(key, parsed);
                    }
                }
                default -> {
                }
            }
        });
    }

    private void loadBuffStatic(Path ini) {
        forEachEntry(ini, (section, key, value) -> {
            if (!"StaticData".equals(key)) {
                return;
            }
            Integer staticData = parseInt(value);
            if (staticData != null) {
                buffStatic.put(section.trim(), staticData);
            }
        });
    }

    private void loadModifierPacks(Path ini, Map<Integer, List<Modifier>> destination) {
        forEachEntry(ini, (section, key, value) -> {
            if (!"r".equals(key)) {
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

    /** r= 行：proppack 三段（Name,Value,Mode）/ skillpack 四段（SkillID,Name,Value,Mode）。 */
    private static Modifier parseModifier(String value) {
        String[] parts = value.split(",");
        int start = 0;
        while (start < parts.length && parts[start].trim().isEmpty()) {
            start++;
        }
        if (parts.length - start < 3) {
            return null;
        }
        // 第二段可解析为数值 → proppack 形态（Name,Value,Mode）
        Double amount = tryParseDouble(parts[start + 1]);
        if (amount != null) {
            return new Modifier("", parts[start].trim(), amount,
                    parseIntOrDefault(parts[start + 2], 0));
        }
        if (parts.length - start < 4) {
            return null;
        }
        amount = tryParseDouble(parts[start + 2]);
        if (amount == null) {
            return null;
        }
        return new Modifier(parts[start].trim(), parts[start + 1].trim(), amount,
                parseIntOrDefault(parts[start + 3], 0));
    }

    private interface EntryConsumer {
        void accept(String section, String key, String value);
    }

    /** 逐节/键/值回调（节外键、注释、空行跳过；文件不可读仅告警）。 */
    private static void forEachEntry(Path ini, EntryConsumer consume) {
        if (!Files.isRegularFile(ini)) {
            log.warn("neigong effect table unavailable ({}): not a regular file", ini);
            return;
        }
        try {
            String data = new String(Files.readAllBytes(ini), Charset.forName("GBK"));
            String section = "";
            for (String raw : data.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0 || section.isEmpty()) {
                    continue;
                }
                consume.accept(section, line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception e) {
            log.warn("neigong effect table unreadable ({}): {}", ini, e.getMessage());
        }
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        Integer parsed = parseInt(value);
        return parsed == null ? fallback : parsed;
    }

    private static Double tryParseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
