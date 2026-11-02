package com.nineyin.server.world.fight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.server.script.LuaScriptService;
import com.nineyin.util.ServerPaths;

/**
 * 战斗技能目录编译器，逐行移植 Go cmd/protocol-probe/skill_catalog.go：
 * share/skill 的 9 张 ini + ini/action 的 zhaoshi_player*4 动作时间线 →
 * {@link CombatSkill}；启动全量编 1 级并缓存，按级惰性编译。
 *
 * <p>Go 的 verifiedSkillEffectOverlays 硬编码覆盖已迁移为 scripts/skill/&lt;ID&gt;.lua
 * 的 M.compile 契约（{@link LuaScriptService#skillOverlay}）：self_buff_lease_ms/
 * self_buff_slot/red_armor/stance_cycle.variants；无脚本技能沿用 INI 默认编译。
 * 编译字段语义与抓包 CD 映射见 docs/combat-skill-211.md。
 */
public final class CombatSkillCatalog {

    private static final Logger LOG = Logger.getLogger(CombatSkillCatalog.class.getName());

    /** buff 租约兜底（Go：lifetime<=0 → 24h）。 */
    public static final long DEFAULT_BUFF_LEASE_MS = 24L * 60 * 60 * 1000;

    /** Go redSuperArmor 常量：红霸体固定槽 1 / buf_fixed_red StaticData 8682。 */
    public static final int RED_ARMOR_BUFF_SLOT = 1;
    public static final long RED_ARMOR_STATIC_DATA = 8682;
    public static final String RED_ARMOR_BUFF_CONFIG = "buf_fixed_red";

    /** ini 节（键小写、保留重复键序：damage_calculate 的多 r 字段依赖全序列）。 */
    static final class Section {

        final List<String[]> fields = new ArrayList<>(8);

        void add(String key, String value) {
            fields.add(new String[]{key.toLowerCase(Locale.ROOT), value});
        }

        /** Go iniValue：同键多次出现取最后一个。 */
        String last(String key) {
            String found = "";
            String lower = key.toLowerCase(Locale.ROOT);
            for (String[] field : fields) {
                if (field[0].equals(lower)) {
                    found = field[1];
                }
            }
            return found;
        }

        List<String[]> all(String key) {
            String lower = key.toLowerCase(Locale.ROOT);
            List<String[]> matched = new ArrayList<>(2);
            for (String[] field : fields) {
                if (field[0].equals(lower)) {
                    matched.add(field);
                }
            }
            return matched;
        }

        int int32(String key) {
            return (int) parseLong(last(key));
        }

        float f32(String key) {
            try {
                return Float.parseFloat(last(key).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        boolean isEmpty() {
            return fields.isEmpty();
        }
    }

    /** 11 份源表（Go skillResourceTables）。 */
    private static final class Tables {
        Map<String, Section> skillNew = new HashMap<>();
        Map<String, Section> skillStatic = new HashMap<>();
        Map<String, Section> normalVar = new HashMap<>();
        Map<String, Section> lockVar = new HashMap<>();
        Map<String, Section> consume = new HashMap<>();
        Map<String, Section> damage = new HashMap<>();
        Map<String, Section> hitShape = new HashMap<>();
        Map<String, Section> actions = new HashMap<>();
        Map<String, Section> buffNew = new HashMap<>();
        Map<String, Section> buffStatic = new HashMap<>();
        Map<String, Section> buffVar = new HashMap<>();
    }

    private final Tables tables;
    private final Map<String, CombatSkill> levelOne = new TreeMap<>();
    private final Map<String, Boolean> indexed = new HashMap<>();
    private final Map<String, Integer> skipReasons = new TreeMap<>();
    private final ConcurrentHashMap<String, CombatSkill> cache = new ConcurrentHashMap<>();

    /** 目录级缓存（serverRoot 变化自动换表；skill 目录缺失 → 空目录，出招一律拒绝）。 */
    public static CombatSkillCatalog load() {
        Path shareRoot = ServerPaths.projectPath("resources", "modern", "share");
        return ResourceCache.of("combat-skill:" + shareRoot, () -> {
            CombatSkillCatalog catalog = new CombatSkillCatalog();
            catalog.compileLevelOne(shareRoot);
            return catalog;
        });
    }

    /** 技能定义：1 级全量已编；其他级按需编译并缓存（Go definition）。 */
    public CombatSkill definition(String id, int level) {
        if (id == null || level < 1) {
            return null;
        }
        String key = id + "@" + level;
        CombatSkill cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!indexed.containsKey(id)) {
            return null;
        }
        try {
            CombatSkill compiled = compile(id, level);
            if (compiled != null) {
                cache.putIfAbsent(key, compiled);
            }
            return compiled;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int indexedCount() {
        return indexed.size();
    }

    public int levelOneCount() {
        return levelOne.size();
    }

    public Map<String, Integer> skipReasons() {
        return skipReasons;
    }

    // ---------------------------------------------------------------- compile

    private void compileLevelOne(Path shareRoot) {
        try {
            loadTables(shareRoot);
        } catch (RuntimeException e) {
            LOG.warning("combat skill tables unavailable, catalog stays empty: " + e);
            return;
        }
        for (String id : new TreeMap<>(tables.skillNew).keySet()) {
            String script = tables.skillNew.get(id).last("script");
            if (!"skillnormal".equalsIgnoreCase(script) && !"skilllock".equalsIgnoreCase(script)) {
                skipReasons.merge("unsupported_script", 1, Integer::sum);
                continue;
            }
            indexed.put(id, Boolean.TRUE);
            try {
                CombatSkill compiled = compile(id, 1);
                if (compiled == null) {
                    continue;
                }
                levelOne.put(id, compiled);
                cache.putIfAbsent(id + "@1", compiled);
            } catch (SkipException e) {
                skipReasons.merge(e.reason, 1, Integer::sum);
            }
        }
        LOG.info("combat skill catalog ready indexed=" + indexed.size()
                + " executable_level1=" + levelOne.size() + " skipped=" + skipReasons);
    }

    private void loadTables(Path shareRoot) {
        Path skillRoot = shareRoot.resolve("skill");
        readIni(tables.skillNew, skillRoot.resolve("skill_new.ini"));
        readIni(tables.skillStatic, skillRoot.resolve("skill_static.ini"));
        readIni(tables.normalVar, skillRoot.resolve("skill_normal_varprop.ini"));
        readIni(tables.lockVar, skillRoot.resolve("skill_lock_varprop.ini"));
        readIni(tables.consume, skillRoot.resolve("skill_consume.ini"));
        readIni(tables.damage, skillRoot.resolve("damage_calculate.ini"));
        readIni(tables.hitShape, skillRoot.resolve("attack_hitshape.ini"));
        readIni(tables.buffNew, skillRoot.resolve("buff_new.ini"));
        readIni(tables.buffStatic, skillRoot.resolve("buff_static.ini"));
        readIni(tables.buffVar, skillRoot.resolve("buff_varprop.ini"));
        // zhaoshi_list.ini 逻辑包含四文件为单一命名空间：后文件整节覆盖同节
        Path actionRoot = ServerPaths.projectPath("resources", "modern", "ini", "action");
        for (String name : new String[]{"zhaoshi_player.ini", "zhaoshi_player_2.ini",
                "zhaoshi_player_dodge.ini", "zhaoshi_player_parry.ini"}) {
            readIni(tables.actions, actionRoot.resolve(name));
        }
    }

    private static void readIni(Map<String, Section> into, Path file) {
        ResourceCache.ini(file, StandardCharsets.ISO_8859_1, (section, key, value) ->
                into.computeIfAbsent(section, ignored -> new Section()).add(key, value));
    }

    static final class SkipException extends RuntimeException {

        final String reason;

        SkipException(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /** Go compileCombatSkill；不可编译抛 {@link SkipException}（levelOne 扫描计数用）。 */
    private CombatSkill compile(String skillId, int level) {
        Section newFields = tables.skillNew.get(skillId);
        if (newFields == null) {
            throw new SkipException("missing_static_id");
        }
        int staticData = newFields.int32("StaticData");
        if (staticData <= 0) {
            throw new SkipException("missing_static_id");
        }
        String script = newFields.last("script");
        if (!"skillnormal".equalsIgnoreCase(script) && !"skilllock".equalsIgnoreCase(script)) {
            throw new SkipException("unsupported_script");
        }
        Section staticFields = tables.skillStatic.get(Integer.toString(staticData));
        if (staticFields == null || staticFields.isEmpty()) {
            throw new SkipException("missing_static");
        }
        Section varProp = levelVarProp(staticFields, script, level);
        if (varProp == null) {
            throw new SkipException("missing_level_varprop");
        }
        Section consume = tables.consume.get(Integer.toString(varProp.int32("Consume")));
        int[] damage = compileSkillDamage(tables.damage.get(
                Integer.toString(varProp.int32("DamageCalculate"))));
        boolean isDamage = staticFields.int32("IsDamage") != 0;
        Section hitShape = tables.hitShape.get(Integer.toString(varProp.int32("HitShapePkg")));
        TargetShape shape = compileTargetMode(hitShape, isDamage);
        Actions actions = compileSkillActions(tables.actions.get(skillId), skillId);
        if (actions.actionName().isEmpty()) {
            throw new SkipException("missing_player_action");
        }

        long publicCd = varProp.int32("AddCoolDownTime");
        if (publicCd <= 0) {
            publicCd = newFields.int32("AddCoolDownTime");
        }
        long personalCd = varProp.int32("CoolDownTime");
        if (publicCd <= 0) {
            publicCd = personalCd;
        }
        int cooldownTeam = staticFields.int32("CoolDownTeam");

        List<CombatSkill.Effect> effects = new ArrayList<>(3);
        if (damage[0] > 0) {
            effects.add(CombatSkill.Effect.damage(damage[0]));
        }
        ExactBuff exact = compileExactSkillBuff(skillId, level);
        LuaScriptService.SkillOverlay overlay = LuaScriptService.getInstance()
                .skillOverlay(skillId, exact == null ? null
                        : new LuaScriptService.ExactBuff(exact.configId(), exact.staticData(),
                        exact.level(), exact.lifetimeMs()));
        if (exact != null) {
            long lease = exact.lifetimeMs();
            int slot = 0;
            if (overlay != null) {
                if (overlay.selfBuffLeaseMs() != null) {
                    lease = overlay.selfBuffLeaseMs();
                }
                if (overlay.selfBuffSlot() != null) {
                    slot = overlay.selfBuffSlot();
                }
            }
            CombatSkill.Buff buff = new CombatSkill.Buff(exact.configId(), exact.staticData(),
                    level, lease, slot);
            effects.add(CombatSkill.Effect.buff(exact.harmful()
                    ? CombatSkill.EffectKind.TARGET_CONTROL : CombatSkill.EffectKind.PLAYER_BUFF, buff));
        }
        if (overlay != null && overlay.stanceCycle() != null && !overlay.stanceCycle().isEmpty()) {
            List<CombatSkill.Buff> variants = new ArrayList<>(overlay.stanceCycle().size());
            for (LuaScriptService.StanceVariant variant : overlay.stanceCycle()) {
                variants.add(new CombatSkill.Buff(variant.id(), variant.staticData(),
                        Math.max(1, variant.level()), variant.lifeMs() > 0
                        ? variant.lifeMs() : DEFAULT_BUFF_LEASE_MS, variant.slot()));
            }
            effects.add(new CombatSkill.Effect(CombatSkill.EffectKind.STANCE_CYCLE, 0, null, variants));
        }
        if (overlay != null && Boolean.TRUE.equals(overlay.redArmor())) {
            effects.add(CombatSkill.Effect.buff(CombatSkill.EffectKind.RED_ARMOR, null));
        }

        return new CombatSkill(skillId, staticData, script, level,
                staticFields.int32("CoolDownCategory"), cooldownTeam,
                personalCd, publicCd,
                consume == null ? 0 : consume.int32("AConsumeMP"),
                consume == null ? 0 : consume.int32("AConsumeSP"),
                consume == null ? 0 : consume.int32("AConsumeQGP"),
                damage[0], damage[1],
                shape.mode(), shape.radius(), varProp.f32("HeightDiff"),
                shape.angle(), shape.width(),
                actions.actionName(), actions.durationMs(), actions.followups(), actions.hitFrameMs(),
                effects);
    }

    private record ExactBuff(String configId, long staticData, int level,
                             long lifetimeMs, boolean harmful) {
    }

    /** Go levelVarProp：static 的 [Min,Max]VarPropNo 区间内找 Level 匹配节。 */
    private Section levelVarProp(Section staticFields, String script, int level) {
        Map<String, Section> varTable = "skilllock".equalsIgnoreCase(script)
                ? tables.lockVar : tables.normalVar;
        int minimum = staticFields.int32("MinVarPropNo");
        int maximum = staticFields.int32("MaxVarPropNo");
        for (int id = Math.max(minimum, 1); id <= maximum; id++) {
            Section fields = varTable.get(Integer.toString(id));
            if (fields != null && fields.int32("Level") == level) {
                return fields;
            }
        }
        return null;
    }

    /** Go compileSkillDamage：r 字段（逗号分隔）第 3 段求和 → [编译伤害, 正段数]。 */
    private static int[] compileSkillDamage(Section fields) {
        if (fields == null) {
            return new int[]{0, 0};
        }
        double total = 0;
        int waves = 0;
        for (String[] field : fields.all("r")) {
            String[] parts = field[1].split(",");
            if (parts.length < 3) {
                continue;
            }
            try {
                double value = Double.parseDouble(parts[2].trim());
                if (value > 0) {
                    total += value;
                    waves++;
                }
            } catch (NumberFormatException ignored) {
                // Go 同样跳过不可解析段
            }
        }
        return new int[]{(int) Math.round(total), waves};
    }

    private record TargetShape(CombatSkill.TargetMode mode, float radius, float angle, float width) {

        static TargetShape of(CombatSkill.TargetMode mode) {
            return new TargetShape(mode, 0, 0, 0);
        }
    }

    /** Go compileTargetMode：HitShape → 目标模式（P2 半径/长度，P1 角度/宽度）。 */
    private static TargetShape compileTargetMode(Section fields, boolean isDamage) {
        if (!isDamage || fields == null) {
            return TargetShape.of(CombatSkill.TargetMode.SELF);
        }
        switch (fields.int32("HitShape")) {
            case 1:
                return new TargetShape(CombatSkill.TargetMode.CASTER_CIRCLE, fields.f32("HitShapePara2"), 0, 0);
            case 2, 11:
                return new TargetShape(CombatSkill.TargetMode.SELECTED_AREA, fields.f32("HitShapePara2"), 0, 0);
            case 3, 9, 10:
                return new TargetShape(CombatSkill.TargetMode.CASTER_SECTOR,
                        fields.f32("HitShapePara2"), fields.f32("HitShapePara1"), 0);
            case 4, 12:
                return new TargetShape(CombatSkill.TargetMode.CASTER_RECTANGLE,
                        fields.f32("HitShapePara2"), 0, fields.f32("HitShapePara1"));
            default:
                return TargetShape.of(CombatSkill.TargetMode.SELECTED);
        }
    }

    private record Actions(String actionName, long durationMs, List<CombatSkill.ActionSegment> followups,
                          List<Long> hitFrameMs) {
    }

    /**
     * Go compileSkillActions：zhaoshi 数值键=序号，值 `动作;?;endFrame;inBlend;?`（30fps）。
     * 多段判定（52795 抓包实证的过滤序）：`trigger_custom_N=段号;trigger_effect;帧号;效果名`
     * ①效果名含 `_hurt` 的帧=伤害段（gxfhj02 的 CS_jhgp_gxfhj_hurt×4=每招 4 段、shl03×4；
     * 目标中途死亡时观测段数偏少=服务端跳死目标，行为一致）；
     * ②无 hurt 时取效果名以技能核心名（尾段去数字，如 sfgp01→sfg）前 3 字母开头的帧
     * （shl08 的 shl08_NN×6=6 段）；刀光/fly/通用特效（如 mwcq_hurt_01 之外的 _01a 型）
     * 与技能全名施法帧均剔除。皆无 → 单段即时结算。
     */
    private static Actions compileSkillActions(Section fields, String skillId) {
        if (fields == null) {
            return new Actions("", 0, List.of(), List.of());
        }
        record Raw(int order, String action, double endFrame, double inBlend) {
        }
        List<Raw> raw = new ArrayList<>(3);
        List<Long> hurtFrames = new ArrayList<>(4);
        List<Long> coreFrames = new ArrayList<>(4);
        String corePrefix = skillCorePrefix(skillId);
        for (String[] field : fields.fields) {
            int order;
            try {
                order = Integer.parseInt(field[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String[] parts = field[1].split(";");
            if (parts.length < 5 || parts[0].trim().isEmpty()) {
                continue;
            }
            double endFrame = parseDoubleOr(parts[2], 0);
            double inBlend = parseDoubleOr(parts[3], 0);
            raw.add(new Raw(order, parts[0].trim(), endFrame, inBlend));
        }
        for (String[] field : fields.fields) {
            if (!field[0].startsWith("trigger_custom_")) {
                continue;
            }
            String[] parts = field[1].split(";");
            // 布局：段号;trigger_effect;帧号;效果名
            if (parts.length < 4 || !"trigger_effect".equals(parts[1].trim())) {
                continue;
            }
            String effectName = parts[3].trim();
            if (effectName.isEmpty() || effectName.equals(skillId)) {
                continue; // 施法特效帧（与技能同名）不是伤害段
            }
            double frame = parseDoubleOr(parts[2], -1);
            if (frame < 0) {
                continue;
            }
            if (effectName.contains("_hurt")) {
                hurtFrames.add(frameMs(frame));
            } else if (!corePrefix.isEmpty() && effectName.startsWith(corePrefix)) {
                coreFrames.add(frameMs(frame));
            }
        }
        List<Long> hitFrames = !hurtFrames.isEmpty() ? hurtFrames : coreFrames;
        hitFrames.sort(Long::compare);
        raw.sort((a, b) -> Integer.compare(a.order(), b.order()));
        if (raw.isEmpty()) {
            return new Actions("", 0, List.of(), hitFrames);
        }
        long duration = frameMs(raw.get(0).endFrame());
        long at = 0;
        List<CombatSkill.ActionSegment> followups = new ArrayList<>(raw.size() - 1);
        for (int i = 1; i < raw.size(); i++) {
            // 前段 endFrame 减去本段入Blend 重叠即本段起点（State 全资源播放，切不了片）
            at += frameMs(raw.get(i - 1).endFrame()) - (long) (Math.max(raw.get(i).inBlend(), 0) * 1000);
            followups.add(new CombatSkill.ActionSegment(raw.get(i).action(), at, frameMs(raw.get(i).endFrame())));
        }
        return new Actions(raw.get(0).action(), duration, followups, hitFrames);
    }

    private static long frameMs(double endFrame) {
        return (long) (endFrame / 30.0 * 1000);
    }

    /** 技能核心名前缀（CS_jh_sfgp01→"sfg"、CS_jhgp_gxfhj02→"gxf"、CS_xy_shl08→"shl"）。 */
    private static String skillCorePrefix(String skillId) {
        String[] segments = skillId.split("_");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (segment.matches(".*[A-Za-z].*\\d.*")) {
                String letters = segment.replaceAll("[^A-Za-z]", "");
                return letters.length() >= 3 ? letters.substring(0, 3) : letters;
            }
        }
        return "";
    }

    private static double parseDoubleOr(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    /** Go compileExactSkillBuff：buf_&lt;技能ID&gt;，租约 buffNew→varprop 级覆盖→24h 兜底。 */
    private ExactBuff compileExactSkillBuff(String skillId, int level) {
        String configId = "buf_" + skillId;
        Section buffNew = tables.buffNew.get(configId);
        if (buffNew == null) {
            return null;
        }
        long staticData = buffNew.int32("StaticData");
        if (staticData <= 0) {
            return null;
        }
        Section staticFields = tables.buffStatic.get(Long.toString(staticData));
        long lifetime = buffNew.int32("LifeTime");
        if (staticFields != null) {
            int minimum = staticFields.int32("MinVarPropNo");
            int maximum = staticFields.int32("MaxVarPropNo");
            for (int id = Math.max(minimum, 1); id <= maximum; id++) {
                Section fields = tables.buffVar.get(Integer.toString(id));
                if (fields == null || fields.int32("Level") != level) {
                    continue;
                }
                int milliseconds = fields.int32("LifeTime");
                if (milliseconds > 0) {
                    lifetime = milliseconds;
                }
                break;
            }
        }
        if (lifetime <= 0) {
            lifetime = DEFAULT_BUFF_LEASE_MS;
        }
        boolean harmful = staticFields != null && staticFields.int32("IsDamage") != 0;
        return new ExactBuff(configId, staticData, level, lifetime, harmful);
    }

    private static long parseLong(String text) {
        try {
            return (long) Double.parseDouble(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private CombatSkillCatalog() {
        tables = new Tables();
    }
}
