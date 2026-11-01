package com.nineyin.server.script;

/**
 * 伤害公式 Java 兜底，逐行对齐 scripts/combat/formula.lua（CombatFormulas.java calc
 * 的逐行移植注释即本契约）：外防对数减伤、命中/闪避、暴击、招架穿透、平减、保底 1 点。
 *
 * <p>Lua 引擎不可用/脚本缺失/执行异常时由 {@link LuaScriptService} 回退到这里；
 * 掷骰随机数由调用方预掷传入（Java 侧与 Lua 侧同输入必同输出，
 * {@code LuaFormulaParityTest} 网格对照钉死两边一致）。
 * 掷骰顺序：未命中 → 暴击（招架不免疫暴击）→ 招架穿透 → 平减 → 保底。
 */
public final class CombatFormulas {

    /** 系数常量区（与 formula.lua 一一对应，调参改 Lua、此处同步）。 */
    private static final double DEFENSE_SLOPE = 20.0;
    private static final double DEFENSE_INTERCEPT = -44.0;
    private static final double DEFENSE_LOG_SCALE = 100.0;
    private static final double MAX_REDUCTION = 0.75;
    private static final double CRIT_BASE = 0.05;
    private static final double CRIT_SLOPE = 0.10;
    private static final double MAX_BASE_CRIT = 0.15;
    private static final double BASE_CRIT_DAMAGE = 1.0;
    private static final double MISS_BASE = 0.10;
    private static final double MISS_SLOPE = 0.15;
    private static final double MAX_DODGE = 0.24;
    private static final double REDUCTION_PER_DAMAGE = 10;

    /** 公式输入（全部基本类型，Lua 侧同名字段按只读 table 预填）。 */
    public record Input(
            double attack, double defense, double pierce, double internalConversion,
            double accuracy, double evasion, double hitRateBonus, double dodgeRateBonus,
            double critValue, double resilience, double critRateBonus, double critRateReduction,
            double critDamageBonus, double critDamageReduction,
            double damageReduction, double blockRating,
            double ratio, boolean parrying, double missRoll, double critRoll) {
    }

    /** 公式输出（对齐 Lua 返回 {damage, miss, crit, parried, reduction_pct}）。 */
    public record Result(long damage, boolean miss, boolean crit, boolean parried, double reductionPct) {

        static final Result MISS_ZERO = new Result(0, true, false, false, 0);
    }

    public static Result calc(Input c) {
        double miss = missChance(c);
        if (miss > 0 && c.missRoll() < miss) {
            return new Result(0, true, false, false, defenseReduction(c.defense()));
        }
        double reduction = defenseReduction(c.defense());
        double raw = (c.attack() * (1 - reduction) + c.pierce())
                * (c.ratio() <= 0 ? 1.0 : c.ratio());
        boolean crit = false;
        double chance = critChance(c);
        if (chance > 0 && c.critRoll() < chance) {
            raw *= critMultiplier(c.critDamageBonus(), c.critDamageReduction());
            crit = true;
        }
        boolean parried = false;
        double parryMultiplier = parryMultiplier(c.internalConversion(), c.blockRating());
        if (c.parrying()) {
            raw *= parryMultiplier;
            parried = true;
        }
        long flat = (long) Math.floor(Math.max(0, c.damageReduction()) / REDUCTION_PER_DAMAGE);
        long damage = (long) Math.floor(raw) - flat;
        if (parried && parryMultiplier == 0) {
            damage = 0; // 完全招架：无穿透伤害（也不吃保底 1 点）
        } else {
            damage = Math.max(1, damage);
        }
        return new Result(damage, false, crit, parried, reduction);
    }

    /** 外防 → 减伤比例 [0, 0.75]；外防 ≤ 0 时不减伤。 */
    static double defenseReduction(double defense) {
        if (defense <= 0) {
            return 0;
        }
        double pct = (DEFENSE_SLOPE * Math.log(defense / DEFENSE_LOG_SCALE) + DEFENSE_INTERCEPT) / 100.0;
        return clamp(pct, 0, MAX_REDUCTION);
    }

    /** 未命中率：10% + 15%×(闪避−命中)/(闪避+1)，合计夹 24% 上限后抵扣命中率%。 */
    static double missChance(Input c) {
        double ratio = (c.evasion() - c.accuracy()) / (c.evasion() + 1);
        double dodge = Math.min(MAX_DODGE, MISS_BASE + MISS_SLOPE * ratio + c.dodgeRateBonus());
        return clamp(dodge - c.hitRateBonus(), 0, 1);
    }

    /** 暴击率：基础 5% + 10%×(暴击值−坚韧)/(暴击值+1)，加算 % 加成后夹 [0,1]。 */
    static double critChance(Input c) {
        double ratio = (c.critValue() - c.resilience()) / (c.critValue() + 1);
        double base = clamp(CRIT_BASE + CRIT_SLOPE * ratio, 0, MAX_BASE_CRIT);
        return clamp(base + c.critRateBonus() - c.critRateReduction(), 0, 1);
    }

    /** 暴击倍数：1 + max(0, 基础 100% + 暴伤% − 对手暴伤减免%)。 */
    static double critMultiplier(double critDamageBonus, double critDamageReduction) {
        double bonus = Math.max(0, BASE_CRIT_DAMAGE + critDamageBonus - critDamageReduction);
        return 1 + bonus;
    }

    /** 招架穿透比 = 1 − 格挡值/化劲，夹 [0,1]；化劲 ≤ 格挡值时完全挡下（0）。 */
    static double parryMultiplier(double internalConversion, double blockRating) {
        if (internalConversion <= 0 || internalConversion <= blockRating) {
            return 0;
        }
        return clamp(1 - blockRating / internalConversion, 0, 1);
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private CombatFormulas() {
    }
}
