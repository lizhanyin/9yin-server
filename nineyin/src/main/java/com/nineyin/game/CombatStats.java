package com.nineyin.game;

/**
 * 战斗属性档案（攻防双方的统一输入，玩家与 NPC 共用一套公式）。
 * 字段对齐 Age of Wushu Wiki 属性条目（社区逆向，括号内为中文名）：
 * 攻击/防御/破防/伤害减免 + 命中/闪避（Accuracy/Evasion，含命中率%/闪避率%修正）
 * + 暴击值（Critical Hit）/坚韧（Resilience，参与暴击率公式）
 * + 暴击率%加成（无上限）与被暴击率降低% + 暴伤%加成与暴伤减免%
 * + 招架三件套（化劲 InternalConversion 攻方 / 格挡值 BlockRating 守方 / 伤害减免 Reduction）；
 * 内功防御四系（刚/柔/阴/阳）与内功命中/暴击后续接表时再扩展。
 * 0 = 无该属性（注意：暴击/闪避公式在双方全 0 时仍有 5%/10% 基础概率）。
 * Wiki：防御类属性不会低于 0（减益最多压到 0）；NPC 通常没有防御类属性。
 */
public record CombatStats(
        int attack,          // 外功攻击（面板）
        int defense,         // 外功防御（百分比减免，走对数曲线）
        int pierce,          // 破防：无视防御减免，直接计入有效攻击
        float critDamageBonus,     // 暴伤%加成（基础 100% 之上加算，0.15 = +15%）
        float critDamageReduction, // 暴伤减免%（受击方：削减来袭暴击的额外伤害）
        float critRateBonus,       // 暴击率%加成（加在基础暴击率之上，无上限，可到 100%）
        float critRateReduction,   // 被暴击率降低%（受击方：整体压低被暴击概率）
        int accuracy,        // 命中值（当前骨架仅外功命中；内功命中 ← 罡气，接内功伤害时再加）
        int evasion,         // 闪避值
        float hitRateBonus,     // 命中率加成（0.05 = +5%，直接抵扣未命中率）
        float dodgeRateBonus,   // 闪避率加成（0.05 = +5%，与闪避值概率合并后受 24% 上限）
        int damageReduction,     // 外功伤害减免（受击方平减：每 10 点减 1 点伤害）
        int blockRating,         // 格挡值（受击方招架时：与攻方化劲比对，基础 50）
        int internalConversion,  // 化劲（攻方：对招架目标的穿透比，基础近似 100 → 招架减半）
        int critValue,       // 暴击值（Critical Hit；外功暴击 ← 臂力 1:1，内功暴击待内功伤害接入）
        int resilience) {    // 坚韧（Resilience，社区名封禁值）：压低攻击方基础暴击率

    /** 仅攻防的极简档案（其余全 0；注意仍有 5% 基础暴击、10% 基础未命中）。 */
    public static CombatStats of(int attack, int defense) {
        return new CombatStats(attack, defense, 0, 0f, 0f, 0f, 0f, 0, 0, 0f, 0f, 0, 0, 0, 0, 0);
    }

    /** 作为受击方：防御与闪避参与（其余字段对防御方无意义）。 */
    public static CombatStats defender(int defense, int evasion) {
        return new CombatStats(0, defense, 0, 0f, 0f, 0f, 0f, 0, evasion, 0f, 0f, 0, 0, 0, 0, 0);
    }
}
