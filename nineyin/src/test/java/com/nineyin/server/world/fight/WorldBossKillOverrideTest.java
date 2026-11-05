package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 世界 BOSS 击杀接管（CastContext.npcKillOverride，worldboss_spawn 摆放口径）：
 * 谓词认领的目标被击杀 → 击杀分支回调谓词且不排 15s 重生任务（消失由会话层善后）；
 * 未认领目标仍走默认重生。E: 根与 MultiHitAndRangeTest 同款（CS_jhgp_gxfhj02
 * 多段技能，经多段击杀分支）；G: 镜像的单段主链见
 * SkillCastHandlerFlowTest.killMarksDeadAndRespawnsFull。
 */
class WorldBossKillOverrideTest {

    /** 与 MultiHitAndRangeTest 同款 E: 参考根（本机可用的 share/skill）。 */
    private static final Path GO_SERVER_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");

    /** 多段伤害技（4 段恒伤；E: 根上可稳定结算与击杀）。 */
    private static final String KILL_SKILL = "CS_jhgp_gxfhj02";

    @BeforeAll
    static void needsSharedSkillResources() {
        Assumptions.assumeTrue(Files.isDirectory(
                        GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过世界 BOSS 击杀接管断言");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    /** 任务只记录，测试手动全部执行（同 MultiHitAndRangeTest.RecordingScheduler）。 */
    static final class RecordingScheduler extends SkillCastHandlerFlowTest.LoginFlowTestSchedulerAdapter {

        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            return null;
        }

        void runAll() {
            for (Runnable task : List.copyOf(tasks)) {
                task.run();
            }
        }
    }

    private static final class Harness {

        final SkillCastHandlerFlowTest.SettableClock clock = new SkillCastHandlerFlowTest.SettableClock();
        final RecordingScheduler scheduler = new RecordingScheduler();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final PlayerActor player = PlayerActor.create("紫泉", 0, clock);
        final SceneLifecycle world = new SceneLifecycle(connection, scheduler);
        final FightState fight = new FightState();
        final Map<String, Integer> learned = new LinkedHashMap<>();
        final Map<String, Integer> skillRows = new LinkedHashMap<>();

        Harness(int npcMaxHp) {
            world.begin("ini\\scene\\scene01", "scene01");
            world.registerNpc(2, SkillCastHandlerFlowTest.combatNpc(
                    "monv030101", npcMaxHp, 100f, 20f, 100f));
            learned.put(KILL_SKILL, 1);
        }

        /** 出招并立即结算全部多段 tick（血量 1 → 首段即杀，后续段跳过）。 */
        void cast(IntPredicate killOverride) {
            assertTrue(fight.selectTarget(world, 2, 1), "combat NPC selectable");
            SkillCastHandler.handle(new SkillCastHandler.CastContext(
                    connection, player, world, fight, learned, skillRows,
                    scheduler, clock, new Random(42), killOverride),
                    SkillCastHandlerFlowTest.useSkillMessage(KILL_SKILL, 100f, 20f, 100f, 1.0f));
            scheduler.runAll();
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

    @Test
    void claimedNpcDiesWithoutRespawnUnclaimedRespawns() {
        CombatSkill skill = CombatSkillCatalog.load().definition(KILL_SKILL, 1);
        Assumptions.assumeTrue(skill != null, KILL_SKILL + " 未编译（资源缺失）");

        // 认领目标：击杀后无 15s 重生任务（时钟过 16s 也无满血 0x10）。
        Harness claimed = new Harness(1);
        java.util.concurrent.atomic.AtomicInteger claimedId = new java.util.concurrent.atomic.AtomicInteger();
        claimed.cast(id -> {
            claimedId.set(id);
            return id == 2;
        });
        claimed.drain();
        assertEquals(2, claimedId.get(), "击杀分支回调认领谓词");
        claimed.clock.advanceMillis(16_000);
        claimed.scheduler.runAll();
        assertFalse(fullHpFrame(claimed.drain(), 1), "认领目标不重生（世界 BOSS 消失由会话层善后）");

        // 未认领目标（谓词返回 false）：默认 15s 满血重生回归。
        Harness unclaimed = new Harness(1);
        unclaimed.cast(id -> false);
        unclaimed.drain();
        unclaimed.clock.advanceMillis(16_000);
        unclaimed.scheduler.runAll();
        assertTrue(fullHpFrame(unclaimed.drain(), 1), "未认领目标仍走默认 15s 满血重生");
    }

    /** NPC 满血 0x10 帧（Dead=0 且 HP=maxHp；重生任务回满血的判定证据）。 */
    private static boolean fullHpFrame(List<byte[]> frames, int maxHp) {
        for (byte[] frame : frames) {
            if ((frame[0] & 0xFF) == 0x10 && SkillCastHandlerFlowTest.u32(frame, 2) == 2) {
                Map<String, Object> props = SkillCastHandlerFlowTest.properties(frame);
                if (Integer.valueOf(maxHp).equals(props.get("HP"))
                        && Integer.valueOf(0).equals(props.get("Dead"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
