package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 多段伤害（zhaoshi trigger_effect 帧位分次结算）与攻击距离闸门：
 * ① CS_jhgp_gxfhj02（_hurt×4）每招 4 段，每段一条 msg24 飘字、伤害值恒定（52795 抓包同构）；
 * ② 目标中途死亡后续段跳过（探针法：血量设为 2.5 段 → 恰 3 段飘字）；
 * ③ SELECTED 技能超距静默拒、射程内受理（bug.txt「攻击距离正常」项）。
 */
class MultiHitAndRangeTest {

    private static final Path SHARE_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");

    @BeforeAll
    static void needsShareResources() {
        assumeTrue(Files.isDirectory(
                        SHARE_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "runtime share 技能资源不可用，跳过多段/距离链路");
        com.nineyin.util.ServerPaths.overrideServerRoot(SHARE_ROOT);
    }

    static final class SettableClock extends Clock {
        private long epochMillis = 1_793_532_000_000L;

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis);
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

    /** 任务只记录，测试手动全部执行（多段 tick + CanUse 恢复 + 收尾）。 */
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
        final SettableClock clock = new SettableClock();
        final RecordingScheduler scheduler = new RecordingScheduler();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final com.nineyin.server.transport.Connection connection =
                new com.nineyin.server.transport.Connection(channel);
        final PlayerActor player = PlayerActor.create("紫泉", 0, clock);
        final SceneLifecycle world = new SceneLifecycle(connection, scheduler);
        final FightState fight = new FightState();
        final Map<String, Integer> learned = new LinkedHashMap<>();
        final Map<String, Integer> skillRows = new LinkedHashMap<>();

        Harness(int npcMaxHp, float npcX, float npcZ) {
            world.begin("ini\\scene\\scene01", "scene01");
            world.registerNpc(2, SkillCastHandlerFlowTest.combatNpc("monv030101", npcMaxHp, npcX, 20f, npcZ));
        }

        void cast(String skillId, float x, float y, float z) {
            assertTrue(fight.selectTarget(world, 2, 1), "combat NPC selectable");
            SkillCastHandler.handle(context(),
                    SkillCastHandlerFlowTest.useSkillMessage(skillId, x, y, z, 1.0f));
        }

        SkillCastHandler.CastContext context() {
            return new SkillCastHandler.CastContext(connection, player, world, fight,
                    learned, skillRows, scheduler, clock, new Random(42));
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

    /** player 形态 msg24（v2=17）的伤害值序列。 */
    private static List<Integer> playerDamageFloats(List<byte[]> frames) {
        List<Integer> damages = new ArrayList<>();
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed == null || parsed.values.isEmpty() || parsed.values.get(0).exactInt32() != 24) {
                continue;
            }
            if (parsed.values.size() > 3 && parsed.values.get(2).exactInt32() == 17) {
                damages.add(parsed.values.get(3).exactInt32());
            }
        }
        return damages;
    }

    @Test
    void multiHitSkillSettlesPerTriggerFrameWithConstantDamage() {
        CombatSkill skill = CombatSkillCatalog.load().definition("CS_jhgp_gxfhj02", 1);
        assumeTrue(skill != null, "gxfhj02 未编译（资源缺失）");
        assertEquals(List.of(233L, 600L, 900L, 1466L), skill.hitFrameMs(),
                "gxfhj02 的 4 个 _hurt 帧位（7/18/27/44 帧 ÷ 30fps）");

        Harness harness = new Harness(1_000_000, 100f, 100f);
        harness.learned.put("CS_jhgp_gxfhj02", 1);
        harness.cast("CS_jhgp_gxfhj02", 100f, 20f, 100f);
        assertTrue(playerDamageFloats(harness.drain()).isEmpty(), "多段技能不在出招瞬间结算");
        assertTrue(harness.scheduler.tasks.size() >= 4, "至少 4 个段任务（另有收尾/闲退）");

        harness.scheduler.runAll();
        List<Integer> damages = playerDamageFloats(harness.drain());
        assertEquals(4, damages.size(), "4 段各一条 msg24 飘字");
        assertEquals(1, damages.stream().distinct().count(), "同招各段伤害值恒定（抓包同构）");
        assertTrue(damages.get(0) > 0);
    }

    @Test
    void multiHitSkipsTicksAfterTargetDeath() {
        CombatSkill skill = CombatSkillCatalog.load().definition("CS_jhgp_gxfhj02", 1);
        assumeTrue(skill != null, "gxfhj02 未编译（资源缺失）");
        // 探针：大血量测出每段伤害，再以 2.5 段血量重放 → 第 3 段击杀、第 4 段跳过。
        Harness probe = new Harness(1_000_000, 100f, 100f);
        probe.learned.put("CS_jhgp_gxfhj02", 1);
        probe.cast("CS_jhgp_gxfhj02", 100f, 20f, 100f);
        probe.drain();
        probe.scheduler.runAll();
        int perTick = playerDamageFloats(probe.drain()).get(0);

        Harness harness = new Harness(perTick * 5 / 2, 100f, 100f);
        harness.learned.put("CS_jhgp_gxfhj02", 1);
        harness.cast("CS_jhgp_gxfhj02", 100f, 20f, 100f);
        harness.drain();
        harness.scheduler.runAll();
        List<Integer> damages = playerDamageFloats(harness.drain());
        int hp = perTick * 5 / 2;
        int expectedTicks = (hp + perTick - 1) / perTick; // 血量可承受的段数（向上取整）
        assertEquals(expectedTicks, damages.size(),
                "恰 " + expectedTicks + " 段击杀，后续段对死亡目标跳过（零帧）");
    }

    @Test
    void selectedSkillOutOfRangeIsSilentlyRejected() {
        // 找一个 SELECTED（单体需目标）模式的已编译技能做距离闸验证（default 分支即 SELECTED）。
        // 排除 CanRush 冲锋族——超距走暂存标记链（msg25/msg24，2026-11-05 两段时序），
        // 不再是静默拒。
        CombatSkillCatalog catalog = CombatSkillCatalog.load();
        CombatSkill selected = null;
        for (String id : List.of("CS_wd_tjq02", "CS_jh_sfgp01", "CS_jh_sfgp04", "CS_yhwq_hsqs04",
                "CS_wd_tjq04", "CS_wd_tjq07", "CS_jh_sfgp02", "CS_jh_yydf04_hide")) {
            CombatSkill skill = catalog.definition(id, 1);
            if (skill != null && skill.requiresTarget() && !skill.canRush()) {
                selected = skill;
                break;
            }
        }
        assumeTrue(selected != null, "无 SELECTED 模式技能可测");

        Harness far = new Harness(1000, 500f, 500f);
        far.learned.put(selected.id(), 1);
        far.cast(selected.id(), 0f, 20f, 0f); // 施法者离目标 ~700m
        assertNull(far.channel.readOutbound(), "超距静默拒（零帧，不进 CD/动作）");

        Harness near = new Harness(1000, 30f, 30f);
        near.learned.put(selected.id(), 1);
        near.cast(selected.id(), 30f, 20f, 30f); // 贴身
        List<byte[]> frames = near.drain();
        assertNotEquals(0, frames.size(), "射程内正常受理出招");
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11
                        || (f[0] & 0xFF) == 0x1E || (f[0] & 0xFF) == 0x10),
                "出招帧序正常下发");
    }
}
