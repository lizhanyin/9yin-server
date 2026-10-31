package com.nineyin.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内功 Lua ↔ Java 数据桥（纯转换，无状态）：内功特效钩子
 * （scripts/neigong/&lt;buffId&gt;.lua 的 on_attack/on_victim/on_parry/on_tick/passive）
 * 的输入快照构造与输出指令解析，风格与 {@link SkillScriptBridge} 一致。
 *
 * <p>解析原则：指令白名单、字段强校验，非法条目丢弃 + WARN（单脚本错误不得
 * 打断战斗结算）。随机数由 Java 预掷传入 {@code ctx.rand.r_proc}（脚本不掷骰，
 * 与 formula.lua 同哲学，保证可测试）。
 */
public final class NeigongScriptBridge {

    private static final Logger log = LoggerFactory.getLogger(NeigongScriptBridge.class);

    /** 附加/反弹伤害元素系白名单（阳/阴/刚/柔/外功）。 */
    private static final Set<String> DAMAGE_KINDS = Set.of("yang", "yin", "gang", "rou", "external");

    private NeigongScriptBridge() {
    }

    // ---------- 指令记录 ----------

    /** 元素系伤害指令（amount &gt; 0 才有效）。 */
    public record DamageInstr(int amount, String kind) {
    }

    /** 招架额外平减（内功/外功两路，≥0）。 */
    public record ParryReduceInstr(int internal, int external) {
    }

    /**
     * 运行钩子输出（nil/非 table = 无效果）：
     * <pre>
     * return {
     *   reduce_pct       = 0.08,          -- 本次受击伤害减免比例（0..0.9，受击钩子）
     *   extra_damage     = {amount=120, kind="yang"},  -- 对当前目标附加伤害（攻击钩子）
     *   reflect          = {amount=35, kind="external"}, -- 反弹攻击者（受击钩子）
     *   heal             = 100,           -- 自身回血
     *   mp_restore       = 50,            -- 自身回内力
     *   shield           = {amount=250, life_ms=10000}, -- 自身护盾池
     *   parry_reduce     = {internal=15, external=12},  -- 本次招架额外平减（招架钩子）
     *   state            = {stacks=2, next_at=1730000000000}, -- 状态整体替换（叠层/计数/下次触发）
     * }
     * </pre>
     */
    public record Instructions(double reducePct, DamageInstr extraDamage, DamageInstr reflect,
                               int heal, int mpRestore,
                               int shieldAmount, long shieldLifeMs,
                               ParryReduceInstr parryReduce,
                               Map<String, Object> state) {

        static Instructions empty() {
            return new Instructions(0, null, null, 0, 0, 0, 0, null, null);
        }

        public boolean isEmpty() {
            return reducePct <= 0 && extraDamage == null && reflect == null
                    && heal <= 0 && mpRestore <= 0 && shieldAmount <= 0
                    && parryReduce == null && state == null;
        }
    }

    /** 常驻被动属性（M.passive(ctx) 输出；字段对齐 CombatStats + 有效上限层）。 */
    public record PassiveStats(int maxHpAdd, int maxMpAdd,
                               int attackAdd, int defenseAdd, int pierceAdd,
                               int accuracyAdd, int evasionAdd, int blockRatingAdd,
                               int damageReductionAdd,
                               float critRateBonus, float critDamageBonus) {

        static PassiveStats empty() {
            return new PassiveStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f);
        }

        public boolean isEmpty() {
            return maxHpAdd == 0 && maxMpAdd == 0 && attackAdd == 0 && defenseAdd == 0
                    && pierceAdd == 0 && accuracyAdd == 0 && evasionAdd == 0
                    && blockRatingAdd == 0 && damageReductionAdd == 0
                    && critRateBonus == 0f && critDamageBonus == 0f;
        }
    }

    // ---------- ctx 构造 ----------
    //  层数档位映射在脚本侧完成：ctx 提供 book_level/book_max_level（书本修炼等级），
    //  脚本按 M.meta.max_tier 自行换算 tier = ceil(book_level * max_tier / book_max_level)。

    /** 攻击命中钩子输入（玩家技能/普攻波次命中 NPC 结算后；self.attack = 面板攻击）。 */
    public static Map<String, Object> attackContext(int bookLevel, int bookMaxLevel,
                                                    int wave, int waves,
                                                    double damage, boolean crit, long now,
                                                    int hp, int maxHp, int mp, int maxMp, int attack,
                                                    long targetOid, String targetConfigId,
                                                    int targetHp, int targetMaxHp,
                                                    Map<String, Object> state, double rProc) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        putBook(ctx, bookLevel, bookMaxLevel);
        ctx.put("wave", wave);
        ctx.put("waves", waves);
        ctx.put("damage", damage);
        ctx.put("crit", crit);
        ctx.put("now", now);
        Map<String, Object> self = selfMap(hp, maxHp, mp, maxMp);
        self.put("attack", attack);
        ctx.put("self", self);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("oid", targetOid);
        target.put("config_id", targetConfigId == null ? "" : targetConfigId);
        target.put("hp", targetHp);
        target.put("max_hp", targetMaxHp);
        ctx.put("target", target);
        putStateAndRand(ctx, state, rProc);
        return ctx;
    }

    /** 受击钩子输入（NPC 攻击玩家、applyDamage 前；incoming = 公式结算后的来袭伤害）。 */
    public static Map<String, Object> victimContext(int bookLevel, int bookMaxLevel,
                                                    double incoming, boolean crit,
                                                    boolean parried, boolean controlled, long now,
                                                    int hp, int maxHp, int mp, int maxMp,
                                                    long attackerOid, String attackerConfigId,
                                                    Map<String, Object> state, double rProc) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        putBook(ctx, bookLevel, bookMaxLevel);
        ctx.put("incoming", incoming);
        ctx.put("controlled", controlled);
        ctx.put("crit", crit);
        ctx.put("parried", parried);
        ctx.put("now", now);
        ctx.put("self", selfMap(hp, maxHp, mp, maxMp));
        ctx.put("attacker", attackerMap(attackerOid, attackerConfigId));
        putStateAndRand(ctx, state, rProc);
        return ctx;
    }

    /** 招架钩子输入（玩家招架生效块内；parry_endurance 为招架前耐力）。 */
    public static Map<String, Object> parryContext(int bookLevel, int bookMaxLevel,
                                                   double incoming, boolean crit,
                                                   long now, int parryEndurance,
                                                   int hp, int maxHp, int mp, int maxMp,
                                                   long attackerOid, String attackerConfigId,
                                                   Map<String, Object> state, double rProc) {
        Map<String, Object> ctx = victimContext(bookLevel, bookMaxLevel, incoming, crit,
                false, false, now,
                hp, maxHp, mp, maxMp, attackerOid, attackerConfigId, state, rProc);
        ctx.put("parry_endurance", parryEndurance);
        return ctx;
    }

    /** 周期钩子输入（每秒 tick；since_last_ms = 距上次触发事件的毫秒数，首触发为 -1）。 */
    public static Map<String, Object> tickContext(int bookLevel, int bookMaxLevel,
                                                  long now, long sinceLastMs,
                                                  int hp, int maxHp, int mp, int maxMp,
                                                  Map<String, Object> state, double rProc) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        putBook(ctx, bookLevel, bookMaxLevel);
        ctx.put("now", now);
        ctx.put("since_last_ms", sinceLastMs);
        ctx.put("self", selfMap(hp, maxHp, mp, maxMp));
        putStateAndRand(ctx, state, rProc);
        return ctx;
    }

    /** 被动表输入（M.passive(ctx)；层数映射同脚本侧约定）。 */
    public static Map<String, Object> passiveContext(int bookLevel, int bookMaxLevel) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        putBook(ctx, bookLevel, bookMaxLevel);
        return ctx;
    }

    private static void putBook(Map<String, Object> ctx, int bookLevel, int bookMaxLevel) {
        ctx.put("book_level", Math.max(1, bookLevel));
        ctx.put("book_max_level", Math.max(1, bookMaxLevel));
    }

    // ---------- 输出解析 ----------

    /** 解析运行钩子返回的指令表（null/非 table/无有效字段 → null）。 */
    public static Instructions parseInstructions(String buffId, Object result) {
        Map<String, Object> table = asTable(result);
        if (table == null) {
            return null;
        }
        Builder b = new Builder();
        double reduce = optDouble(table, "reduce_pct", 0);
        if (reduce > 0) {
            b.reducePct(Math.min(0.9, reduce));
        } else if (reduce < 0) {
            warn(buffId, "negative reduce_pct ignored");
        }
        b.extraDamage(parseDamage(buffId, table.get("extra_damage")));
        b.reflect(parseDamage(buffId, table.get("reflect")));
        long heal = optLong(table, "heal", 0);
        if (heal > 0) {
            b.heal((int) Math.min(Integer.MAX_VALUE, heal));
        }
        long mp = optLong(table, "mp_restore", 0);
        if (mp > 0) {
            b.mpRestore((int) Math.min(Integer.MAX_VALUE, mp));
        }
        Map<String, Object> shield = asTable(table.get("shield"));
        if (shield != null) {
            long amount = optLong(shield, "amount", 0);
            long life = optLong(shield, "life_ms", 0);
            if (amount > 0 && life > 0) {
                b.shield((int) Math.min(Integer.MAX_VALUE, amount), life);
            } else {
                warn(buffId, "invalid shield (need amount>0, life_ms>0)");
            }
        }
        Map<String, Object> parry = asTable(table.get("parry_reduce"));
        if (parry != null) {
            b.parryReduce(new ParryReduceInstr(
                    (int) Math.max(0, optLong(parry, "internal", 0)),
                    (int) Math.max(0, optLong(parry, "external", 0))));
        }
        Map<String, Object> state = asTable(table.get("state"));
        if (state != null) {
            b.state(state);
        }
        Instructions built = b.build();
        return built.isEmpty() ? null : built;
    }

    /** 解析 M.passive 返回的被动属性表（null/非 table/全零 → null）。 */
    public static PassiveStats parsePassive(String buffId, Object result) {
        Map<String, Object> table = asTable(result);
        if (table == null) {
            return null;
        }
        PassiveStats stats = new PassiveStats(
                intOf(optLong(table, "max_hp_add", 0)),
                intOf(optLong(table, "max_mp_add", 0)),
                intOf(optLong(table, "attack_add", 0)),
                intOf(optLong(table, "defense_add", 0)),
                intOf(optLong(table, "pierce_add", 0)),
                intOf(optLong(table, "accuracy_add", 0)),
                intOf(optLong(table, "evasion_add", 0)),
                intOf(optLong(table, "block_rating_add", 0)),
                intOf(optLong(table, "damage_reduction_add", 0)),
                (float) clampUnit(optDouble(table, "crit_rate_bonus", 0)),
                (float) optDouble(table, "crit_damage_bonus", 0));
        return stats.isEmpty() ? null : stats;
    }

    // ---------- Builder（指令字段少，直接内联） ----------

    private static final class Builder {
        double reducePct;
        DamageInstr extraDamage;
        DamageInstr reflect;
        int heal;
        int mpRestore;
        int shieldAmount;
        long shieldLifeMs;
        ParryReduceInstr parryReduce;
        Map<String, Object> state;

        void reducePct(double v) {
            reducePct = v;
        }

        void extraDamage(DamageInstr d) {
            extraDamage = d;
        }

        void reflect(DamageInstr d) {
            reflect = d;
        }

        void heal(int v) {
            heal = v;
        }

        void mpRestore(int v) {
            mpRestore = v;
        }

        void shield(int amount, long lifeMs) {
            shieldAmount = amount;
            shieldLifeMs = lifeMs;
        }

        void parryReduce(ParryReduceInstr p) {
            parryReduce = p;
        }

        void state(Map<String, Object> s) {
            state = s;
        }

        Instructions build() {
            return new Instructions(reducePct, extraDamage, reflect, heal, mpRestore,
                    shieldAmount, shieldLifeMs, parryReduce, state);
        }
    }

    // ---------- 转换读取辅助 ----------

    private static DamageInstr parseDamage(String buffId, Object v) {
        Map<String, Object> table = asTable(v);
        if (table == null) {
            return null;
        }
        long amount = optLong(table, "amount", 0);
        String kind = optString(table, "kind", "external");
        if (amount <= 0) {
            warn(buffId, "invalid damage instr (need amount>0)");
            return null;
        }
        if (!DAMAGE_KINDS.contains(kind)) {
            warn(buffId, "unknown damage kind \"" + kind + "\" (expect yang/yin/gang/rou/external)");
            return null;
        }
        return new DamageInstr((int) Math.min(Integer.MAX_VALUE, amount), kind);
    }

    private static Map<String, Object> selfMap(int hp, int maxHp, int mp, int maxMp) {
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("hp", hp);
        self.put("max_hp", maxHp);
        self.put("mp", mp);
        self.put("max_mp", maxMp);
        return self;
    }

    private static Map<String, Object> attackerMap(long oid, String configId) {
        Map<String, Object> attacker = new LinkedHashMap<>();
        attacker.put("oid", oid);
        attacker.put("config_id", configId == null ? "" : configId);
        return attacker;
    }

    private static void putStateAndRand(Map<String, Object> ctx,
                                        Map<String, Object> state, double rProc) {
        ctx.put("state", state == null ? Map.of() : state);
        Map<String, Object> rand = new LinkedHashMap<>();
        rand.put("r_proc", rProc);
        ctx.put("rand", rand);
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

    static String optString(Map<String, Object> t, String key, String def) {
        Object v = t.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intOf(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private static double clampUnit(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static void warn(String buffId, String detail) {
        log.warn("neigong script {}: {}", buffId, detail);
    }
}
