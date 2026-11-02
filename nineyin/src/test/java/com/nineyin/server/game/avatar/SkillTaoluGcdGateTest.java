package com.nineyin.server.game.avatar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.server.world.fight.CombatSkill;

/**
 * 公共 GCD 套路口径（bug.txt 官方规则）：同套路连招各走个人 CD、互不触发 7s；
 * 切套路才被 GCD 拦到 7s。2026-11-02 修复前按 CoolDownTeam 做 GCD 门，
 * 同队（=同套路）互拦，cd&lt;7s 的技能被堆到 7s。
 */
class SkillTaoluGcdGateTest {

    private static CombatSkill skill(String id, String taolu, long personalCdMs) {
        return new CombatSkill(id, 1, "SkillNormal", 1,
                1, 50369, taolu, personalCdMs, 7000,
                0, 0, 0,
                100, 1,
                CombatSkill.TargetMode.SELECTED, 5f, 0f, 0f, 0f,
                "act", 500L, List.of(), List.of(), List.of());
    }

    @Test
    void sameTaoluChainUsesPersonalCdOnly() {
        PlayerActor player = PlayerActor.create("tester", 1, Clock.systemUTC());
        Instant t0 = Instant.now();
        assertTrue(player.beginSkillUse(skill("A1", "TL_a", 2000), 0, t0).allowed(),
                "首招放开");

        // 同套路另一技能：不等 GCD（个人 CD 独立）即可出
        assertTrue(player.beginSkillUse(skill("A2", "TL_a", 2000), 0, t0.plusMillis(1500))
                .allowed(), "同套路不触发 7s 公共 CD");

        // 同技能复读只受个人 CD 管
        assertFalse(player.beginSkillUse(skill("A1", "TL_a", 2000), 0, t0.plusMillis(1900))
                .allowed(), "个人 CD 内仍拦");
        assertTrue(player.beginSkillUse(skill("A1", "TL_a", 2000), 0, t0.plusMillis(2100))
                .allowed(), "个人 CD 尽即放（不堆到 7s）");
    }

    @Test
    void crossTaoluPaysPublicCd() {
        PlayerActor player = PlayerActor.create("tester", 1, Clock.systemUTC());
        Instant t0 = Instant.now();
        assertTrue(player.beginSkillUse(skill("A1", "TL_a", 2000), 0, t0).allowed());

        assertFalse(player.beginSkillUse(skill("B1", "TL_b", 2000), 0, t0.plusMillis(2500))
                .allowed(), "切套路 7s 内拦");
        assertTrue(player.beginSkillUse(skill("B1", "TL_b", 2000), 0, t0.plusMillis(7100))
                .allowed(), "GCD 尽放行");

        // B 出招后 GCD 窗口与发起套路刷新为 B 的套路
        assertFalse(player.beginSkillUse(skill("A1", "TL_a", 2000), 0, t0.plusMillis(7300))
                .allowed(), "再切回 A 套路也要付 GCD");
    }
}
