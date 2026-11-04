package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 套路武器限制（skill_new.UseLimit → skill_use_limit 的 ItemType 规则）：
 * 武器套路不持对应武器静默拒（官方式，客户端本地图标按武器置灰）；拳/腿套路
 * 无 ItemType 条件不闸，空手可出。2026-11-04「套路武器限制」待办落地。
 */
class WeaponLimitGateTest {

    private static final Path SHARE_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");

    @BeforeAll
    static void needsShareResources() {
        assumeTrue(Files.isDirectory(
                SHARE_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "runtime share 技能资源不可用，跳过武器门控链路");
        com.nineyin.util.ServerPaths.overrideServerRoot(SHARE_ROOT);
    }

    /** 含玉剑法（UseLimit=2 必须使用单手剑 102）；太极拳（无 UseLimit，拳套）。 */
    private static final String SWORD_SKILL = "CS_jh_hyjf01";
    private static final String FIST_SKILL = "CS_wd_tjq01";

    @Test
    void catalogCompilesWeaponRuleFromUseLimit() {
        CombatSkill sword = CombatSkillCatalog.load().definition(SWORD_SKILL, 1);
        assumeTrue(sword != null, "含玉剑法 L1 可编译（资源表驱动）");
        assertEquals(java.util.Set.of(102), sword.weaponItemTypes(), "UseLimit=2 → 单手剑 102");
        CombatSkill fist = CombatSkillCatalog.load().definition(FIST_SKILL, 1);
        assertTrue(fist != null && fist.weaponItemTypes().isEmpty(), "太极拳无武器条件");
    }

    @Test
    void weaponSkillRejectedBareHandedAndAllowedWithWeapon() {
        Harness harness = new Harness();
        harness.learned.put(SWORD_SKILL, 1);

        // 空手（equippedWeaponItemType=0）：静默拒——无 CD 行/msg20/active_skill_rec
        harness.cast(SWORD_SKILL);
        List<byte[]> bare = harness.drain();
        assertTrue(bare.isEmpty(), "空手武器套路静默零帧，实际=" + bare.size() + " 帧");

        // 换上单手剑（102）：同一招放行（msg20 技能动作在场）
        harness.player.noteEquippedWeaponItemType(102);
        harness.cast(SWORD_SKILL);
        assertTrue(hasCustom(harness.drain(), 20), "持单手剑 102 后含玉剑法可出招");
    }

    @Test
    void fistSkillCastableBareHanded() {
        Harness harness = new Harness();
        harness.learned.put(FIST_SKILL, 1);
        harness.select();
        SkillCastHandler.handle(harness.context(),
                SkillCastHandlerFlowTest.useSkillMessage(FIST_SKILL, 0f, 20f, 0f, 1.0f));
        assertTrue(hasCustom(harness.drain(), 20), "拳套路空手可出（无 ItemType 条件不闸）");
    }

    @Test
    void wrongWeaponTypeStillRejected() {
        Harness harness = new Harness();
        harness.learned.put(SWORD_SKILL, 1);
        harness.player.noteEquippedWeaponItemType(101); // 持刀 ≠ 单手剑
        harness.cast(SWORD_SKILL);
        assertTrue(harness.drain().isEmpty(), "持刀出剑法：静默零帧");
    }

    private static boolean hasCustom(List<byte[]> frames, int messageId) {
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed != null && !parsed.values.isEmpty()
                    && parsed.values.get(0).exactInt32() == messageId) {
                return true;
            }
        }
        return false;
    }

    /** 最小出招环境（对齐 MultiHitAndRangeTest.Harness，无 NPC 目标也够验出招受理）。 */
    private static final class Harness {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final PlayerActor player = PlayerActor.create("武器侠", 0, new FixedClock());
        final SceneLifecycle world = new SceneLifecycle(connection, new NoopScheduler());
        final FightState fight = new FightState();
        final Map<String, Integer> learned = new LinkedHashMap<>();

        Harness() {
            world.begin("ini\\scene\\scene01", "scene01");
            world.registerNpc(2, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 0f, 20f, 0f));
        }

        void select() {
            assertTrue(fight.selectTarget(world, 2, 1), "combat NPC selectable");
        }

        void cast(String skillId) {
            select();
            SkillCastHandler.handle(context(),
                    SkillCastHandlerFlowTest.useSkillMessage(skillId, 0f, 20f, 0f, 1.0f));
        }

        SkillCastHandler.CastContext context() {
            return new SkillCastHandler.CastContext(connection, player, world, fight,
                    learned, Map.of(), new NoopScheduler(), new FixedClock(), new Random(42));
        }

        List<byte[]> drain() {
            List<byte[]> frames = new ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    /** 固定时刻时钟（CombatScene 语义即可）。 */
    private static final class FixedClock extends Clock {

        private final Instant now = Instant.ofEpochMilli(1_793_600_000_000L);

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 只记录不执行的调度器（出招受理不依赖 tick 执行）。 */
    private static final class NoopScheduler
            extends SkillCastHandlerFlowTest.LoginFlowTestSchedulerAdapter {

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return null;
        }
    }
}
