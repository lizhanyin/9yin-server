package com.nineyin.server.game.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 打坐回复/轻功分档回复/格挡值动态（2026-11-03 官方口径）：
 * <ul>
 *   <li>打坐：LS_SITCROSS 每秒 ceil(max/10)（10s 回满），死亡/非坐姿不恢复；</li>
 *   <li>轻功：101-150 每 3s +10 / 51-100 +5 / 0-50 +3（玩家资料 0→150=96s），
 *       一次扣费后不再 &lt;50 自动回满；</li>
 *   <li>格挡值：动态余量，按住格挡不回复、松开每秒 +4。</li>
 * </ul>
 */
class VitalRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    @Test
    void restoreWhileSitHealsOnlyInSitcrossAndNeverRevives() {
        Actor actor = new Actor(1, 1, "测试", 1000, 500);
        assertTrue(actor.applyDamage(600), "damage applies");
        assertTrue(actor.spendMp(400), "mp damaged");
        actor.setLogicState(Actor.LOGIC_STATE_SITCROSS);
        assertTrue(actor.restoreWhileSit(100, 50), "sit tick heals");
        assertEquals(500, actor.snapshot().hp());
        assertEquals(150, actor.snapshot().mp());
        assertTrue(actor.snapshot().hitHp() >= actor.snapshot().hp(), "bruise floor follows");

        actor.setLogicState(Actor.LOGIC_STATE_NORMAL);
        assertFalse(actor.restoreWhileSit(100, 50), "no heal outside sitcross");

        actor.setLogicState(Actor.LOGIC_STATE_SITCROSS);
        while (actor.snapshot().hp() < actor.snapshot().maxHp()
                || actor.snapshot().mp() < actor.snapshot().maxMp()) {
            assertTrue(actor.restoreWhileSit(100, 50), "fills within 10 ticks");
        }
        assertFalse(actor.restoreWhileSit(100, 50), "full: no further change");

        Actor dead = new Actor(2, 1, "亡", 1000, 500);
        dead.applyDamage(9999);
        dead.setLogicState(Actor.LOGIC_STATE_SITCROSS);
        assertFalse(dead.restoreWhileSit(100, 50), "dead actor never heals in sitcross");
    }

    @Test
    void qingGongTieredRecoveryBandsMatchOfficialData() {
        Actor actor = new Actor(1, 1, "轻功", 1000, 500);
        // 满值不回复
        assertFalse(actor.recoverQingGongTiered());
        // 150 - 25 = 125 → 101..150 档 +10 → 135 → 145 → 150(封顶) → 停
        actor.useQingGong(25);
        assertEquals(125, actor.snapshot().qingGongPoint());
        assertTrue(actor.recoverQingGongTiered());
        assertEquals(135, actor.snapshot().qingGongPoint());
        actor.recoverQingGongTiered();
        assertEquals(145, actor.snapshot().qingGongPoint());
        actor.recoverQingGongTiered();
        assertEquals(150, actor.snapshot().qingGongPoint(), "capped at max");
        assertFalse(actor.recoverQingGongTiered());

        // 50 → 0..50 档 +3 → 53 → 51..100 档 +5
        actor.useQingGong(100);
        assertEquals(50, actor.snapshot().qingGongPoint());
        actor.recoverQingGongTiered();
        assertEquals(53, actor.snapshot().qingGongPoint(), "0-50 band gains 3");
        actor.recoverQingGongTiered();
        assertEquals(58, actor.snapshot().qingGongPoint(), "51-100 band gains 5");

        // 一次大额扣费后余量 <50 不再自动回满（官方无瞬回）
        actor.useQingGong(48);
        assertEquals(10, actor.snapshot().qingGongPoint(), "no <50 auto refill");
    }

    @Test
    void playerSitcrossApplyAndTickDriveLogicStateAndHealing() {
        PlayerActor player = PlayerActor.create("打坐者", 100, Clock.systemUTC());
        assertFalse(player.sitting());
        player.actor().applyDamage(400);
        player.actor().setLogicState(Actor.LOGIC_STATE_NORMAL);
        assertTrue(player.applySitcross(true));
        assertTrue(player.sitting());
        assertFalse(player.applySitcross(true), "duplicate start is idempotent");

        Actor.State before = player.actorState();
        int step = (before.maxHp() + 9) / 10;
        assertTrue(player.sitcrossTick());
        assertEquals(before.hp() + step, player.actorState().hp());

        assertTrue(player.applySitcross(false));
        assertFalse(player.sitting());
        assertFalse(player.sitcrossTick(), "no heal after standing up");
    }

    @Test
    void parryValueRecoversOnlyWhenNotParrying() {
        PlayerActor player = PlayerActor.create("格挡者", 100, Clock.systemUTC());
        int max = player.parryValueMax();
        assertEquals(max, player.parryValue(), "starts full");
        assertFalse(player.parryRecoveryTick(), "full bar needs no recovery");

        int after = player.consumeParryValue(max);
        assertEquals(0, after, "parry pool drains to zero");
        player.engageParryBuff(NOW);
        assertFalse(player.parryRecoveryTick(), "holding parry never recovers");
        player.releaseParryBuff();
        assertTrue(player.parryRecoveryTick());
        assertEquals(PlayerActor.PARRY_UP_SPEED_BASE, player.parryValue());
        for (int i = 0; i < 1000; i++) {
            player.parryRecoveryTick();
        }
        assertEquals(max, player.parryValue(), "recovers up to max then stops");
    }

    @Test
    void qingGongRecoveryTickCadenceIsThreeSecondsAndPausesWhileDrifting() {
        PlayerActor player = PlayerActor.create("踏波", 100, Clock.systemUTC());
        player.spendQingGong(100);
        assertEquals(50, player.actorState().qingGongPoint());
        assertFalse(player.qingGongRecoveryTick(NOW), "1s: not yet");
        assertFalse(player.qingGongRecoveryTick(NOW), "2s: not yet");
        assertTrue(player.qingGongRecoveryTick(NOW), "3s: band applied");
        assertEquals(53, player.actorState().qingGongPoint(), "0-50 band +3 per 3s");

        player.engageDrift("qinggong_2", 7);
        assertFalse(player.qingGongRecoveryTick(NOW));
        assertFalse(player.qingGongRecoveryTick(NOW));
        assertFalse(player.qingGongRecoveryTick(NOW), "draining pauses recovery");
        assertEquals(53, player.actorState().qingGongPoint());
        assertTrue(player.releaseDrift());
        player.qingGongRecoveryTick(NOW);
        player.qingGongRecoveryTick(NOW);
        assertTrue(player.qingGongRecoveryTick(NOW), "resumes after lease released");
        assertEquals(58, player.actorState().qingGongPoint(), "53 -> 51+ band +5");
    }

    @Test
    void driftLeaseDrainsPerSecondAndExhausts() {
        PlayerActor player = PlayerActor.create("壁虎", 100, Clock.systemUTC());
        player.spendQingGong(140);
        assertEquals(10, player.actorState().qingGongPoint());
        player.engageDrift("qinggong_2", 7);
        assertTrue(player.drainDriftPoint());
        assertEquals(3, player.actorState().qingGongPoint());
        assertFalse(player.drainDriftPoint(), "cannot pay 7 from 3");
        assertTrue(player.releaseDrift());
    }
}
