package com.nineyin.game;

import java.util.Random;

/**
 * 伤害公式骨架（社区实测逆向结论的工程化，官方从未公开原公式）：
 * <ul>
 *   <li>防御减免：减伤% = 20·ln(外防/100) − 44，2000–7000 实测区间对数拟合；
 *       1942 外防 ≈ 15.4%（2012 实测 16%）、1.1 万 ≈ 50%、13.4 万 ≈ 100%，上限夹 75%。</li>
 *   <li>伤害减免（Reduction）：平减值，每 10 点减免 1 点伤害（Wiki：1 per 10）。</li>
 *   <li>招架：穿透比 = 1 − 格挡值/化劲（夹 [0,1]，化劲 ≤ 格挡值时完全挡下），
 *       基础格挡值 50 / 化劲近似 100 → 招架减半；招架不免疫暴击；
 *       招架耐力按攻击力消耗、耗尽后招架失效至恢复（耐力在 PlayerActor 层）。</li>
 *   <li>未命中率：10% + 15% × (闪避 − 命中)/(闪避 + 1)，命中=闪避时 10%；
 *       闪避概率（含闪避率%加成）合计上限 24%，命中率%加成直接抵扣未命中率。</li>
 *   <li>暴击率：基础 5% + 10% × (暴击值 − 坚韧)/(暴击值 + 1)（等值含全 0 时 5%），
 *       暴击率%加成无上限（可到 100%），被暴击率降低%整体压低，合计夹 [0, 100%]。</li>
 *   <li>暴击伤害：基础暴伤 100%（双倍），暴伤%/暴伤减免%加算叠加后括号内下限 0
 *       （0% 时暴击等于普伤）。</li>
 *   <li>总伤害 = (攻击 × (1 − 减伤%) + 破防) × 技能倍率，逐段向下取整，保底 1 点。</li>
 * </ul>
 * 来源：17173《九阴真经伤害计算：暴伤与暴免》《暴击率与暴击值》（2024-11）、
 * 《坑爹的外功防御》对数拟合（2013-01）、NGA 伤害框架帖（2023-07）、
 * Age of Wushu Wiki「Accuracy &amp; Evasion」条目（命中/闪避公式与 24% 上限）。
 * 系数集中在常量区，等官方表（技能红/蓝/绿参数、装备攻防）接入后再标定。
 */
public final class CombatFormulas {

    /** 减伤曲线斜率：每 e 倍外防 +20% 减伤（社区拟合值）。 */
    static final double DEFENSE_SLOPE = 20.0;
    /** 减伤曲线截距：20·ln(外防/100) − 44，即约 900 外防以下减伤为 0。 */
    static final double DEFENSE_INTERCEPT = -44.0;
    /** 减伤外防的缩放单位（公式按每 100 外防取对数）。 */
    static final double DEFENSE_LOG_SCALE = 100.0;
    /** 减伤上限（服务端兜底，防高防目标近似免伤）。 */
    static final double MAX_REDUCTION = 0.75;

    /** 基础暴击率与斜率：5% + 10% × (暴击值−坚韧)/(暴击值+1)，Wiki 实测条目（等值时 5%）。 */
    static final double CRIT_BASE = 0.05;
    static final double CRIT_SLOPE = 0.10;
    /** 基础暴击率渐近上限（公式自然上界 15%）；暴击率%加成另计、不受此限。 */
    static final double MAX_BASE_CRIT = 0.15;
    /** 基础暴击伤害 100%（双倍；暴伤%/减免%做加算结算）。 */
    static final double BASE_CRIT_DAMAGE = 1.0;

    /** 基础未命中率与斜率：10% + 15% × (闪避−命中)/(闪避+1)，Wiki 实测条目。 */
    static final double MISS_BASE = 0.10;
    static final double MISS_SLOPE = 0.15;
    /** 闪避概率上限（Wiki：Evasion cannot exceed 24%，闪避率%加成同样受限）。 */
    static final double MAX_DODGE = 0.24;

    /** 伤害减免换算：每 10 点减免 1 点伤害（Wiki：1 per 10 Reduction）。 */
    static final int REDUCTION_PER_DAMAGE = 10;

    private CombatFormulas() {
    }

    /** 单次结算结果：最终伤害（未命中/完全招架为 0）、是否未命中/暴击/被招架、防御减免比例。 */
    public record DamageResult(int damage, boolean miss, boolean crit, boolean parried, double reductionPct) {
    }

    /** 外防 → 减伤比例 [0, 0.75]。外防 ≤ 0 或低于 ~900 时不减伤。 */
    public static double defenseReduction(int defense) {
        if (defense <= 0) {
            return 0;
        }
        double pct = (DEFENSE_SLOPE * Math.log(defense / DEFENSE_LOG_SCALE) + DEFENSE_INTERCEPT) / 100.0;
        return Math.max(0, Math.min(MAX_REDUCTION, pct));
    }

    /** 攻击 × (1 − 减伤) + 破防（破防不吃防御减免）。 */
    public static double effectiveAttack(int attack, int defense, int pierce) {
        return attack * (1 - defenseReduction(defense)) + pierce;
    }

    /**
     * 暴击率：基础 5% + 10% × (暴击值 − 坚韧)/(暴击值 + 1)（等值含双方全 0 时 5%），
     * 暴击率%加成加在其上且无上限（可到 100%），被暴击率降低%整体压低，合计夹 [0, 100%]。
     */
    public static double critChance(int critValue, int resilience,
                                    float critRateBonus, float critRateReduction) {
        double ratio = (critValue - (double) resilience) / (critValue + 1);
        double base = Math.max(0, Math.min(MAX_BASE_CRIT, CRIT_BASE + CRIT_SLOPE * ratio));
        return Math.max(0, Math.min(1, base + critRateBonus - critRateReduction));
    }

    /** 暴击伤害倍数：1 + (基础 100% + 暴伤% − 对手暴伤减免%)，括号内负数取 0（0% 时等于普伤）。 */
    public static double critMultiplier(float critDamageBonus, float critDamageReduction) {
        double bonus = Math.max(0, BASE_CRIT_DAMAGE + critDamageBonus - critDamageReduction);
        return 1 + bonus;
    }

    /** 伤害减免平减值：每 10 点减免属性折 1 点伤害。 */
    public static int flatDamageReduction(int damageReduction) {
        return Math.max(0, damageReduction) / REDUCTION_PER_DAMAGE;
    }

    /** 招架穿透比 = 1 − 格挡值/化劲，夹 [0,1]；化劲 ≤ 0 或 ≤ 格挡值时完全挡下（乘数 0）。 */
    public static double parryMultiplier(int internalConversion, int blockRating) {
        if (internalConversion <= 0 || internalConversion <= blockRating) {
            return 0;
        }
        return Math.max(0, Math.min(1, 1 - (double) blockRating / internalConversion));
    }

    /**
     * 未命中率：10% + 15% × (闪避 − 命中)/(闪避 + 1)，命中=闪避（含双方全 0）时为 10%；
     * 闪避率%加成并入后合计夹 24% 上限，命中率%加成直接抵扣，下限 0（wiki 未给下限，取保底必中）。
     */
    public static double missChance(int accuracy, int evasion,
                                    float hitRateBonus, float dodgeRateBonus) {
        double ratio = (evasion - (double) accuracy) / (evasion + 1);
        double dodge = Math.min(MAX_DODGE, MISS_BASE + MISS_SLOPE * ratio + dodgeRateBonus);
        return Math.max(0, Math.min(1, dodge - hitRateBonus));
    }

    /**
     * 主公式：先掷未命中（闪避方概率 > 0 时消耗一次随机数），命中后掷暴击
     * （概率 > 0 时消耗一次随机数，招架状态不影响暴击）；受击方招架时乘穿透比
     * （完全挡下则伤害 0），最后扣平减值。顺序：
     * floor((攻击×(1−减伤)+破防) × 技能倍率 × 暴击倍数 × 招架穿透比) − 平减，
     * 保底 1 点（未命中/完全招架为 0）。招架耐力消耗在调用方（按攻击力计）。
     */
    public static DamageResult calc(CombatStats attacker, CombatStats defender,
                                    double skillRatio, Random rng, boolean defenderParrying) {
        double miss = missChance(attacker.accuracy(), defender.evasion(),
                attacker.hitRateBonus(), defender.dodgeRateBonus());
        if (miss > 0 && rng.nextDouble() < miss) {
            return new DamageResult(0, true, false, false, defenseReduction(defender.defense()));
        }
        double reduction = defenseReduction(defender.defense());
        double raw = effectiveAttack(attacker.attack(), defender.defense(), attacker.pierce())
                * (skillRatio <= 0 ? 1.0 : skillRatio);
        boolean crit = false;
        double chance = critChance(attacker.critValue(), defender.resilience(),
                attacker.critRateBonus(), defender.critRateReduction());
        if (chance > 0 && rng.nextDouble() < chance) {
            raw *= critMultiplier(attacker.critDamageBonus(), defender.critDamageReduction());
            crit = true;
        }
        boolean parried = false;
        if (defenderParrying) {
            raw *= parryMultiplier(attacker.internalConversion(), defender.blockRating());
            parried = true;
        }
        int damage = (int) Math.floor(raw) - flatDamageReduction(defender.damageReduction());
        if (parried && parryMultiplier(attacker.internalConversion(), defender.blockRating()) == 0) {
            damage = 0;   // 完全招架：无穿透伤害（也不吃保底 1 点）
        } else {
            damage = Math.max(1, damage);
        }
        return new DamageResult(damage, false, crit, parried, reduction);
    }

    /**
     * 玩家技能倍率骨架：默认 1.0。211 实际结算已由 SkillCatalog 接管——
     * 倍率 = damage_calculate.ini 编译伤害 / 100（SceneFlow.settleSkillHit），
     * 本方法仅保留给未编译技能的兜底路径。
     */
    public static double skillRatio(String skillId) {
        return 1.0;
    }
}
