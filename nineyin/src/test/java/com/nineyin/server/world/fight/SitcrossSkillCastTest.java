package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * bug.txt「打坐」：zs_default_01 三断点闭环——
 * ① zhaoshi_player* 无节（动作客户端原生）：编译不再按 missing_player_action 拒；
 * ② HitShape=0→SELECTED 但 IsDamage=0：免选中目标（52795 jysg06 短格式受理同族实证）；
 * ③ 受理即切 LogicState=102/0（toggle）；UseLimit=9 战斗中静默拒。
 * Go 真机捕获：按已学快捷键客户端走 custom 240（LoginFlow.handleSitcross 已对齐），
 * 技能面板/他路 211 由本链路受理。
 */
class SitcrossSkillCastTest {

    private static final Path GO_SERVER_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");

    @BeforeAll
    static void needsSharedSkillResources() {
        Assumptions.assumeTrue(Files.isDirectory(
                        GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过打坐链路");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    /** 任务只记录，测试手动全部执行（收尾清行等）。 */
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

        Harness() {
            world.begin("ini\\scene\\scene01", "scene01");
            learned.put(CombatSkillCatalog.SITCROSS_SKILL_ID, 1);
        }

        void cast(float x, float y, float z) {
            SkillCastHandler.handle(context(),
                    SkillCastHandlerFlowTest.useSkillMessage(
                            CombatSkillCatalog.SITCROSS_SKILL_ID, x, y, z, 1.0f));
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

    @Test
    void sitcrossSkillCompilesWithoutPlayerAction() {
        CombatSkill skill = CombatSkillCatalog.load().definition(
                CombatSkillCatalog.SITCROSS_SKILL_ID, 1);
        assertNotNull(skill, "打坐编译放行（动作客户端原生，zhaoshi 无节不拒）");
        assertFalse(skill.isDamage(), "IsDamage=0（自增益族）");
        assertFalse(skill.requiresTarget(), "无伤害技免选中目标");
        assertFalse(skill.lockSkill(), "非锁定技：msg20 目标=自己");
    }

    @Test
    void castTogglesSitcrossStateWithoutSelectedTarget() {
        Harness harness = new Harness();
        // 无选中目标（登录态）：短格式 211 受理，LogicState 0→102
        harness.cast(100f, 20f, 100f);
        assertTrue(harness.player.sitting(), "受理后进入打坐态（LogicState=102）");
        List<byte[]> frames = harness.drain();
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "CD 行下发（受理证据）");
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x10 && f[1] == 0
                        && Integer.valueOf(Actor.LOGIC_STATE_SITCROSS).equals(
                        SkillCastHandlerFlowTest.properties(f).get("LogicState"))),
                "出招面板携带 LogicState=102");
        assertEquals((PlayerActor.PLAYER_OBJECT_ID & 0xFFFFFFFFL)
                        | ((long) PlayerActor.PLAYER_OWNER_ID << 32),
                msg20Target(frames), "IsDamage=0：msg20 动作目标=施法者自己");

        // 个人 CD（5s）尽后再出：toggle 退出打坐
        harness.clock.advanceMillis(6000);
        harness.cast(100f, 20f, 100f);
        harness.scheduler.runAll();
        assertFalse(harness.player.sitting(), "再出打坐=退出（LogicState 回 0）");
    }

    /**
     * 真机 63296 实证场景（2026-11-04）：打坐中出任意武法必须先起立——客户端此路
     * 不发 240/2，服务端不退坐则 LogicState=102 随出招面板回发、sitcrossTick 继续
     * 结算，武法被坐姿压住不出招。起立回全量 vital（参考服 52905 抓包：坐/立迁移
     * 恒走 188 属性全量面板，LogicState 为首属性）。
     */
    @Test
    void castingMartialSkillWhileSittingStandsUp() {
        Harness harness = new Harness();
        harness.learned.put("CS_jh_sfgp01", 1);
        assertTrue(harness.player.applySitcross(true), "先进入打坐态");

        SkillCastHandler.handle(harness.context(),
                SkillCastHandlerFlowTest.useSkillMessage("CS_jh_sfgp01", 100f, 20f, 100f, 1.0f));
        assertFalse(harness.player.sitting(), "武法受理即退出打坐态（LogicState 回 0）");
        List<byte[]> frames = harness.drain();
        assertTrue(frames.stream().anyMatch(f -> f.length >= 2 && (f[0] & 0xFF) == 0x10 && f[1] == 0
                        && Integer.valueOf(Actor.LOGIC_STATE_NORMAL).equals(
                        SkillCastHandlerFlowTest.properties(f).get("LogicState"))
                        && SkillCastHandlerFlowTest.properties(f).containsKey("Mount")),
                "起立全量 vital（含 Mount 等登录域属性）携带 LogicState=0");
        assertTrue(frames.stream().anyMatch(f -> f.length >= 2 && (f[0] & 0xFF) == 0x10 && f[1] == 0
                        && Integer.valueOf(Actor.LOGIC_STATE_NORMAL).equals(
                        SkillCastHandlerFlowTest.properties(f).get("LogicState"))),
                "出招面板携带起立后的 LogicState=0");
    }

    @Test
    void castRejectedWhileFighting() {
        Harness harness = new Harness();
        assertTrue(harness.player.enterCombat(Instant.now()), "进入战斗态");
        harness.cast(100f, 20f, 100f);
        assertNull(harness.channel.readOutbound(), "UseLimit=9：战斗中静默拒（零帧）");
        assertFalse(harness.player.sitting(), "战斗中不落打坐态");
    }

    /** msg20 动作帧目标对象（tag8 int64；空对象=0）。 */
    private static Long msg20Target(List<byte[]> frames) {
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed == null || parsed.values.isEmpty()
                    || parsed.values.get(0).exactInt32() != 20 || parsed.values.size() < 4) {
                continue;
            }
            if (CombatSkillCatalog.SITCROSS_SKILL_ID.equals(parsed.values.get(2).textOrNull())) {
                CustomC2S.Value target = parsed.values.get(3);
                return target.tag == 8 ? target.int64 : null;
            }
        }
        return null;
    }
}
