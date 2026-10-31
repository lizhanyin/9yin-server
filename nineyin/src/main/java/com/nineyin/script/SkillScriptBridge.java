package com.nineyin.script;

import com.nineyin.game.CombatFormulas;
import com.nineyin.game.CombatStats;
import com.nineyin.game.SkillCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lua ↔ Java 数据桥（纯转换，无状态）：compile/on_cast/on_hit/formula 钩子的
 * 输入快照构造（Java Map → Lua table，由 ScriptHost.callMap 深度转换）与
 * 输出指令解析（Lua 返回 table 经 toObject 转回 Map/List）。
 *
 * <p>解析原则：kind 白名单、字段强校验，非法条目丢弃 + WARN（单条脚本错误
 * 不得打断技能管线，也不能把非法值塞进效果结算）。
 */
public final class SkillScriptBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillScriptBridge.class);

    private SkillScriptBridge() {
    }

    // ---------- compile 钩子输入 ----------

    /**
     * 编译期快照（def 表）：技能 INI 编译出的权威参数 + 默认效果要素，
     * 脚本在此之上产出覆盖指令（scripts/skill/&lt;id&gt;.lua 的 M.compile(def)）。
     */
    public static Map<String, Object> definitionSnapshot(String id, int level, int staticData,
                                                         String script, String taoLu,
                                                         int baseDamage, int hitCount,
                                                         SkillCatalog.TargetMode targetMode,
                                                         float targetRangeMeters,
                                                         SkillCatalog.BuffDef exactBuff) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("id", id);
        def.put("level", level);
        def.put("static_data", staticData);
        def.put("script", script);
        def.put("tao_lu", taoLu);
        def.put("base_damage", baseDamage);
        def.put("hit_count", hitCount);
        def.put("target_mode", targetMode.name().toLowerCase(Locale.ROOT));
        def.put("target_range_meters", targetRangeMeters);
        if (exactBuff != null) {
            def.put("exact_buff", buffMap(exactBuff));
        }
        return def;
    }

    private static Map<String, Object> buffMap(SkillCatalog.BuffDef b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.configId());
        m.put("static_data", b.staticData());
        m.put("level", b.level());
        m.put("life_ms", b.lifetimeMs());
        m.put("slot", b.preferredSlot());
        return m;
    }

    // ---------- compile 钩子输出 → ScriptOverlay ----------

    /**
     * 解析 compile 返回的指令表（nil/非 table = 无覆盖）：
     * <pre>
     * return {
     *   self_buff_lease_ms = 35000,          -- 自身 Buff 租约覆盖（仅改 exact buff）
     *   self_buff_slot     = 5,              -- 自身 Buff 首选槽（0 = 不指定）
     *   red_armor          = true,           -- 追加红霸体效果
     *   stance_cycle       = { variants = { {id=..., static_data=..., level=..., life_ms=..., slot=...}, ... } },
     *   effects            = { ... },        -- 全量效果覆盖（逃生舱；提供时忽略默认编译效果）
     * }
     * </pre>
     */
    public static SkillCatalog.ScriptOverlay parseOverlay(String skillId, Object result) {
        Map<String, Object> table = asTable(result);
        if (table == null) {
            return null;
        }
        long leaseMs = optLong(table, "self_buff_lease_ms", 0);
        int slot = (int) optLong(table, "self_buff_slot", 0);
        boolean redArmor = optBool(table, "red_armor", false);
        List<SkillCatalog.BuffDef> stance = parseStanceVariants(skillId, table.get("stance_cycle"));
        List<SkillCatalog.Effect> effectsOverride = parseEffects(skillId, table.get("effects"));
        return new SkillCatalog.ScriptOverlay(leaseMs, slot, redArmor, stance, effectsOverride);
    }

    private static List<SkillCatalog.BuffDef> parseStanceVariants(String skillId, Object stance) {
        Map<String, Object> table = asTable(stance);
        if (table == null) {
            return null;
        }
        List<SkillCatalog.BuffDef> variants = parseBuffList(skillId, table.get("variants"));
        return variants != null && !variants.isEmpty() ? variants : null;
    }

    // ---------- 效果列表（全量覆盖 / on_cast 追加共用） ----------

    /**
     * 效果指令列表（数组 table → List），kind 白名单：
     * damage / player_buff / target_control / stance_cycle / red_armor。
     * 非法条目跳过 + WARN；null/非 table 返回 null。
     */
    public static List<SkillCatalog.Effect> parseEffects(String skillId, Object list) {
        if (!(list instanceof List<?> items) || items.isEmpty()) {
            return null;
        }
        List<SkillCatalog.Effect> effects = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Object raw = items.get(i);
            Map<String, Object> item = asTable(raw);
            if (item == null) {
                warn(skillId, "effect #" + (i + 1) + " is not a table");
                continue;
            }
            String kind = optString(item, "kind", "");
            switch (kind) {
                case "damage" -> {
                    long amount = optLong(item, "amount", 0);
                    if (amount > 0) {
                        effects.add(SkillCatalog.Effect.damage((int) Math.min(Integer.MAX_VALUE, amount)));
                    }
                }
                case "player_buff", "target_control" -> {
                    SkillCatalog.BuffDef buff = parseBuff(item.get("buff"));
                    if (buff != null) {
                        effects.add("player_buff".equals(kind)
                                ? SkillCatalog.Effect.buff(buff)
                                : SkillCatalog.Effect.control(buff));
                    } else {
                        warn(skillId, "effect #" + (i + 1) + " (" + kind + ") has invalid buff");
                    }
                }
                case "stance_cycle" -> {
                    List<SkillCatalog.BuffDef> variants = parseBuffList(skillId, item.get("variants"));
                    if (variants != null && !variants.isEmpty()) {
                        effects.add(SkillCatalog.Effect.stance(variants));
                    } else {
                        warn(skillId, "effect #" + (i + 1) + " (stance_cycle) has no valid variants");
                    }
                }
                case "red_armor" -> effects.add(SkillCatalog.Effect.redArmor());
                default -> warn(skillId, "effect #" + (i + 1) + " has unknown kind \"" + kind + "\"");
            }
        }
        return effects.isEmpty() ? null : effects;
    }

    private static SkillCatalog.BuffDef parseBuff(Object v) {
        Map<String, Object> table = asTable(v);
        if (table == null) {
            return null;
        }
        String id = optString(table, "id", "");
        long staticData = optLong(table, "static_data", 0);
        if (id.isEmpty() || staticData <= 0) {
            return null;
        }
        int level = (int) Math.max(1, optLong(table, "level", 1));
        long lifeMs = Math.max(1, optLong(table, "life_ms", 1));
        int slot = (int) optLong(table, "slot", 0);
        return new SkillCatalog.BuffDef(id, (int) staticData, level, lifeMs, slot);
    }

    private static List<SkillCatalog.BuffDef> parseBuffList(String skillId, Object list) {
        if (!(list instanceof List<?> items)) {
            return null;
        }
        List<SkillCatalog.BuffDef> buffs = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            SkillCatalog.BuffDef buff = parseBuff(items.get(i));
            if (buff == null) {
                warn(skillId, "buff variant #" + (i + 1) + " invalid (need id + static_data)");
                continue;
            }
            buffs.add(buff);
        }
        return buffs;
    }

    // ---------- 公式输入/输出 ----------

    /**
     * formula.lua M.calc(c) 输入：攻守双方全部公式参数 + 技能倍率 + 招架开关 +
     * Java 预掷随机数 rand={r_miss, r_crit}（脚本不掷骰，保证可测试/可对照）。
     */
    public static Map<String, Object> formulaInput(CombatStats attacker, CombatStats defender,
                                                   double ratio, double rMiss, double rCrit,
                                                   boolean defenderParrying) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("attack", (double) attacker.attack());
        c.put("defense", (double) defender.defense());
        c.put("pierce", (double) attacker.pierce());
        c.put("internal_conversion", (double) attacker.internalConversion());
        c.put("accuracy", (double) attacker.accuracy());
        c.put("evasion", (double) defender.evasion());
        c.put("hit_rate_bonus", (double) attacker.hitRateBonus());
        c.put("dodge_rate_bonus", (double) defender.dodgeRateBonus());
        c.put("crit_value", (double) attacker.critValue());
        c.put("resilience", (double) defender.resilience());
        c.put("crit_rate_bonus", (double) attacker.critRateBonus());
        c.put("crit_rate_reduction", (double) defender.critRateReduction());
        c.put("crit_damage_bonus", (double) attacker.critDamageBonus());
        c.put("crit_damage_reduction", (double) defender.critDamageReduction());
        c.put("damage_reduction", (double) defender.damageReduction());
        c.put("block_rating", (double) defender.blockRating());
        c.put("ratio", ratio);
        c.put("parrying", defenderParrying);
        c.put("rand", List.of(rMiss, rCrit));
        return c;
    }

    /**
     * 解析公式输出 {damage, miss, crit, parried, reduction_pct}
     * （缺省：miss/crit/parried=false、reduction_pct=0；damage 缺省回退 Java 公式）。
     */
    public static CombatFormulas.DamageResult parseDamageResult(Object result) {
        Map<String, Object> table = asTable(result);
        if (table == null) {
            return null;
        }
        Object damage = table.get("damage");
        if (!(damage instanceof Number number)) {
            return null;   // 无 damage 字段视为脚本未产出，回退 Java
        }
        return new CombatFormulas.DamageResult(
                (int) Math.max(0, Math.min(Integer.MAX_VALUE, number.longValue())),
                optBool(table, "miss", false),
                optBool(table, "crit", false),
                optBool(table, "parried", false),
                optDouble(table, "reduction_pct", 0));
    }

    // ---------- on_cast / on_hit ----------

    /** 起手钩子输入（ctx）：只读快照，字段精简到脚本调参所需。 */
    public static Map<String, Object> castContext(String skillId, int level,
                                                  int casterStanceStaticData,
                                                  int casterHp, int casterMaxHp, int casterMp,
                                                  Integer targetOid, String targetConfigId,
                                                  int targetHp, int targetMaxHp) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("skill_id", skillId);
        ctx.put("level", level);
        ctx.put("caster", casterMap(casterStanceStaticData, casterHp, casterMaxHp, casterMp));
        if (targetOid != null) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("oid", targetOid);
            target.put("config_id", targetConfigId == null ? "" : targetConfigId);
            target.put("hp", targetHp);
            target.put("max_hp", targetMaxHp);
            ctx.put("target", target);
        }
        return ctx;
    }

    /** 波次命中钩子输入（hit）：{skill_id, level, wave, waves, ratio, caster, target}。 */
    public static Map<String, Object> hitContext(String skillId, int level,
                                                 int wave, int waves, double ratio,
                                                 int casterStanceStaticData,
                                                 int casterHp, int casterMaxHp, int casterMp,
                                                 int targetOid, String targetConfigId,
                                                 int targetHp, int targetMaxHp) {
        Map<String, Object> ctx = castContext(skillId, level, casterStanceStaticData,
                casterHp, casterMaxHp, casterMp, targetOid, targetConfigId, targetHp, targetMaxHp);
        ctx.put("wave", wave);
        ctx.put("waves", waves);
        ctx.put("ratio", ratio);
        return ctx;
    }

    /** on_cast 返回 {add_effects={...}} → 追加效果（仅自buff/架势/红甲类，结算仍走 Java 管线）。 */
    public static List<SkillCatalog.Effect> parseCastAddEffects(String skillId, Object result) {
        Map<String, Object> table = asTable(result);
        if (table == null) {
            return null;
        }
        List<SkillCatalog.Effect> effects = parseEffects(skillId, table.get("add_effects"));
        if (effects == null) {
            return null;
        }
        // 运行期只开放自身向效果（伤害/控制必须过编译期管线，保证帧配对/落账不变）
        effects.removeIf(e -> e.kind() == SkillCatalog.EffectKind.DAMAGE
                || e.kind() == SkillCatalog.EffectKind.TARGET_CONTROL);
        return effects.isEmpty() ? null : effects;
    }

    /** on_hit 返回 {ratio=...} → 修正后倍率（null = 不修正）。 */
    public static Double parseHitRatio(Object result) {
        Map<String, Object> table = asTable(result);
        if (table == null) {
            return null;
        }
        if (!(table.get("ratio") instanceof Number number)) {
            return null;
        }
        double ratio = number.doubleValue();
        return ratio > 0 && ratio <= 1000 ? ratio : null;
    }

    // ---------- 转换读取辅助 ----------

    private static Map<String, Object> casterMap(int stanceStaticData, int hp, int maxHp, int mp) {
        Map<String, Object> caster = new LinkedHashMap<>();
        caster.put("stance_static_data", stanceStaticData);
        caster.put("hp", hp);
        caster.put("max_hp", maxHp);
        caster.put("mp", mp);
        return caster;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asTable(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    static long optLong(Map<String, Object> t, String key, long def) {
        return t.get(key) instanceof Number n ? n.longValue() : def;
    }

    static double optDouble(Map<String, Object> t, String key, double def) {
        return t.get(key) instanceof Number n ? n.doubleValue() : def;
    }

    static boolean optBool(Map<String, Object> t, String key, boolean def) {
        Object v = t.get(key);
        return v instanceof Boolean b ? b : def;
    }

    static String optString(Map<String, Object> t, String key, String def) {
        Object v = t.get(key);
        return v instanceof String s ? s : def;
    }

    private static void warn(String skillId, String detail) {
        log.warn("skill script {}: {}", skillId, detail);
    }
}
