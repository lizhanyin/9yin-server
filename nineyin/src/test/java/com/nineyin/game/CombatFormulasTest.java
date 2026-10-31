package com.nineyin.game;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伤害公式骨架：防御对数曲线（社区拟合锚点：1942 防≈15.4%、1.1 万≈50%）、
 * 暴击（5% 基础率含全 0、暴击率%无上限、暴伤减法结算与 0% 下限）、
 * 命中/闪避（10% 基础未命中、24% 闪避上限、命中率%抵扣）、破防绕过减免、保底 1 点；
 * 外加 PlayerActor 瘀血/血量分层（25% 进瘀血，上限 30% 最大血量）。
 */
class CombatFormulasTest {

    private static final double EPS = 1e-9;

    @Test
    void defenseReductionFollowsCommunityCurve() {
        assertEquals(0, CombatFormulas.defenseReduction(0), EPS);
        assertEquals(0, CombatFormulas.defenseReduction(900), EPS, "曲线零点约在 900 外防");
        assertEquals(0.154, CombatFormulas.defenseReduction(1942), 0.005, "2012 实测：1942 防 ≈ 16%");
        assertEquals(0.50, CombatFormulas.defenseReduction(11000), 0.005, "社区锚点：1.1 万防 = 50%");
        assertEquals(0.75, CombatFormulas.defenseReduction(200000), EPS, "服务端兜底上限 75%");
    }

    @Test
    void critChanceBounds() {
        assertEquals(0.05, CombatFormulas.critChance(0, 0, 0f, 0f), EPS,
                "暴击值=坚韧（含双方全 0）→ 5% 基础暴击");
        assertEquals(0.05, CombatFormulas.critChance(500, 500, 0f, 0f), EPS);
        double mid = CombatFormulas.critChance(100, 0, 0f, 0f);
        assertTrue(mid > 0.05 && mid < 0.15, "基础段渐近 15%：" + mid);
        assertEquals(0.15, CombatFormulas.critChance(10000, 0, 0f, 0f), 0.001, "基础段上界 15%");
        assertEquals(0, CombatFormulas.critChance(100, 300, 0f, 0f), EPS, "坚韧压净为 0");
        assertEquals(0.35, CombatFormulas.critChance(0, 0, 0.30f, 0f), 1e-6,
                "暴击率%加成无上限（5% + 30% = 35%）");
        assertEquals(1.0, CombatFormulas.critChance(0, 0, 2.0f, 0f), 1e-6, "可堆到 100%");
        assertEquals(0.03, CombatFormulas.critChance(0, 0, 0f, 0.02f), 1e-6,
                "被暴击率降低%整体压低");
        assertEquals(0, CombatFormulas.critChance(0, 0, 0.04f, 0.10f), EPS, "压到 0 为止");
    }

    @Test
    void critMultiplierSubtractsReduction() {
        assertEquals(2.0, CombatFormulas.critMultiplier(0f, 0f), EPS, "基础暴伤 100%（双倍）");
        // 17173 示例：暴伤 +103%、暴免 +89% → 1000 → 2140（倍数 2.14）
        assertEquals(2.14, CombatFormulas.critMultiplier(1.03f, 0.89f), 1e-6);
        assertEquals(1.0, CombatFormulas.critMultiplier(0f, 1.5f), EPS,
                "暴伤减免压净 → 额外暴伤 0%，暴击等于普伤");
    }

    @Test
    void effectiveAttackAppliesReductionAndPierce() {
        assertEquals(150, CombatFormulas.effectiveAttack(150, 0, 0), EPS);
        assertEquals(127.0, CombatFormulas.effectiveAttack(150, 1942, 0), 0.1);
        assertEquals(147.0, CombatFormulas.effectiveAttack(150, 1942, 20), 0.1, "破防不吃减免");
        assertEquals(50, CombatFormulas.effectiveAttack(0, 200000, 50), EPS);
    }

    @Test
    void missChanceFollowsWikiCurve() {
        assertEquals(0.10, CombatFormulas.missChance(0, 0, 0f, 0f), EPS,
                "命中=闪避（含双方全 0）→ 10% 未命中");
        assertEquals(0.10, CombatFormulas.missChance(500, 500, 0f, 0f), EPS);
        assertEquals(0, CombatFormulas.missChance(100, 0, 0f, 0f), EPS,
                "命中压制闪避 → 必中（下限 0 为骨架假设，Wiki 未给下限）");
        assertEquals(0.24, CombatFormulas.missChance(0, 100, 0f, 0f), EPS,
                "10%+15%×100/101≈24.85% → 夹 24% 闪避上限");
        assertEquals(0.24, CombatFormulas.missChance(0, 0, 0f, 0.5f), EPS,
                "闪避率%加成与点数闪避合并后同受 24% 上限");
        assertEquals(0.05, CombatFormulas.missChance(0, 0, 0.05f, 0f), EPS,
                "命中率%加成直接抵扣未命中率");
    }

    @Test
    void calcRollsMissBeforeDamage() {
        Random alwaysMiss = new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
        CombatFormulas.DamageResult miss = CombatFormulas.calc(CombatStats.of(150, 0),
                CombatStats.defender(0, 0), 1.0, alwaysMiss, false);
        assertTrue(miss.miss());
        assertEquals(0, miss.damage(), "未命中伤害为 0");

        Random neverMiss = new Random() {
            @Override
            public double nextDouble() {
                return 0.99;
            }
        };
        CombatFormulas.DamageResult hit = CombatFormulas.calc(CombatStats.of(150, 0),
                CombatStats.defender(0, 0), 1.0, neverMiss, false);
        assertFalse(hit.miss());
        assertEquals(150, hit.damage());
    }

    @Test
    void flatReductionConvertsTenToOne() {
        assertEquals(0, CombatFormulas.flatDamageReduction(0));
        assertEquals(2, CombatFormulas.flatDamageReduction(25), "25 点减免 → 2 点平减");
        assertEquals(10, CombatFormulas.flatDamageReduction(100));
        assertEquals(0, CombatFormulas.flatDamageReduction(-30), "防御类属性不低于 0");
    }

    @Test
    void parryMultiplierAnchors() {
        assertEquals(0.5, CombatFormulas.parryMultiplier(100, 50), EPS,
                "基础：化劲 100 / 格挡值 50 → 招架减半");
        assertEquals(0.75, CombatFormulas.parryMultiplier(200, 50), EPS);
        assertEquals(0, CombatFormulas.parryMultiplier(50, 50), EPS, "化劲 ≤ 格挡值 → 完全挡下");
        assertEquals(0, CombatFormulas.parryMultiplier(0, 50), EPS, "化劲 0 → 完全挡下");
        assertEquals(1.0, CombatFormulas.parryMultiplier(1000, 0), EPS, "格挡值 0 → 全额穿透");
    }

    @Test
    void calcAppliesParryAndFlatReduction() {
        Random plain = new Random() {
            @Override
            public double nextDouble() {
                return 0.99;
            }
        };
        CombatStats attacker = new CombatStats(150, 0, 0, 0f, 0f, 0f, 0f,
                0, 0, 0f, 0f, 0, 0, 100, 0, 0);   // 化劲 100（玩家/BOSS 基础）
        CombatStats parrier = new CombatStats(0, 0, 0, 0f, 0f, 0f, 0f,
                0, 0, 0f, 0f, 25, 50, 0, 0, 0);
        CombatFormulas.DamageResult r = CombatFormulas.calc(attacker, parrier, 1.0, plain, true);
        assertTrue(r.parried());
        assertEquals(73, r.damage(), "150 × 0.5 招架穿透 = 75，再扣平减 2");
        assertEquals(148, CombatFormulas.calc(attacker, parrier, 1.0, plain, false).damage(),
                "不招架：150 − 平减 2");
        // 攻方化劲 0：完全招架 0 伤害（不吃保底 1 点）
        CombatStats noConversion = new CombatStats(150, 0, 0, 0f, 0f, 0f, 0f,
                0, 0, 0f, 0f, 0, 0, 0, 0, 0);
        CombatFormulas.DamageResult full = CombatFormulas.calc(noConversion, parrier, 1.0, plain, true);
        assertTrue(full.parried());
        assertEquals(0, full.damage(), "完全招架 0 伤害");
    }

    @Test
    void calcFloorsAndGuaranteesOneDamage() {
        Random never = new Random() {
            @Override
            public double nextDouble() {
                return 0.99;   // 未命中 10%、暴击 5% 均不触发
            }
        };
        CombatFormulas.DamageResult hit = CombatFormulas.calc(
                CombatStats.of(150, 0), CombatStats.defender(0, 0), 1.0, never, false);
        assertEquals(150, hit.damage());
        assertFalse(hit.crit());
        assertEquals(127, CombatFormulas.calc(CombatStats.of(150, 0),
                CombatStats.defender(1942, 0), 1.0, never, false).damage());
        assertEquals(300, CombatFormulas.calc(CombatStats.of(150, 0),
                CombatStats.defender(0, 0), 2.0, never, false).damage(), "技能倍率乘算");
        assertEquals(1, CombatFormulas.calc(CombatStats.of(1, 0),
                CombatStats.defender(200000, 0), 1.0, never, false).damage(), "保底 1 点");
    }

    @Test
    void calcDoublesOnCrit() {
        // 第一掷（未命中判定）0.99 → 命中；第二掷（暴击）0.0 → 必暴击
        Random critSeq = new Random() {
            boolean first = true;

            @Override
            public double nextDouble() {
                if (first) {
                    first = false;
                    return 0.99;
                }
                return 0.0;
            }
        };
        CombatFormulas.DamageResult hit = CombatFormulas.calc(
                new CombatStats(150, 0, 0, 0f, 0f, 0f, 0f, 0, 0, 0f, 0f, 0, 0, 0, 500, 0),
                CombatStats.defender(0, 0), 1.0, critSeq, false);
        assertTrue(hit.crit());
        assertEquals(300, hit.damage());
    }

    @Test
    void playerDamageSplitsIntoBruiseAndHp() {
        PlayerActor actor = new PlayerActor();
        actor.hp = 1000;
        assertFalse(actor.applyDamage(100));
        assertEquals(925, actor.hp, "75% 进血量（100 伤扣 75）");
        assertEquals(25, actor.hitHp, "25% 进瘀血");
        assertFalse(actor.applyDamage(0), "0 伤害直接返回");
    }

    @Test
    void bruiseOverflowCapGoesToHp() {
        PlayerActor actor = new PlayerActor();
        actor.hp = 1000;
        // 上限 = maxHp() × 30%；五维换算后 maxHp = 1000 + 7×30 + 2×30 = 1270 → 上限 381
        actor.hitHp = 375;
        actor.applyDamage(100);
        assertEquals(381, actor.hitHp, "瘀血封顶 381（五维换算后的 30%）");
        assertEquals(906, actor.hp, "超出上限的 6 点转入血量");
    }

    @Test
    void lethalHitReturnsJustDied() {
        PlayerActor actor = new PlayerActor();
        actor.hp = 10;
        assertTrue(actor.applyDamage(1000));
        assertEquals(0, actor.hp);
        assertTrue(actor.hitHp <= 300, "瘀血不超过上限");
    }

    @Test
    void parryEnduranceDrainsAndRecovers() {
        PlayerActor actor = new PlayerActor();
        assertEquals(100, actor.parryEndurance, "骨架默认耐力 100");
        assertFalse(actor.drainParry(60), "100 → 40 未破");
        assertEquals(40, actor.parryEndurance);
        assertTrue(actor.drainParry(40), "40 → 0 刚被打破");
        assertEquals(0, actor.parryEndurance);
        actor.tick(System.currentTimeMillis());
        assertEquals(5, actor.parryEndurance, "每秒被动恢复 5");
    }

    @Test
    void combatStatsCarriesAllFields() {
        PlayerActor actor = new PlayerActor();
        actor.attack = 300;
        actor.defense = 1942;
        actor.pierce = 40;
        actor.critValue = 500;
        actor.resilience = 20;
        actor.critDamageBonus = 0.3f;
        actor.critDamageReduction = 0.1f;
        actor.critRateBonus = 0.2f;
        actor.critRateReduction = 0.05f;
        actor.accuracy = 88;
        actor.evasion = 66;
        actor.hitRateBonus = 0.05f;
        actor.dodgeRateBonus = 0.03f;
        CombatStats stats = actor.combatStats();
        assertEquals(300, stats.attack());
        assertEquals(1942, stats.defense());
        assertEquals(40, stats.pierce());
        assertEquals(500, stats.critValue());
        assertEquals(20, stats.resilience());
        assertEquals(0.3f, stats.critDamageBonus());
        assertEquals(0.1f, stats.critDamageReduction());
        assertEquals(0.2f, stats.critRateBonus());
        assertEquals(0.05f, stats.critRateReduction());
        assertEquals(88, stats.accuracy());
        assertEquals(66, stats.evasion());
        assertEquals(0.05f, stats.hitRateBonus());
        assertEquals(0.03f, stats.dodgeRateBonus());
    }
}
