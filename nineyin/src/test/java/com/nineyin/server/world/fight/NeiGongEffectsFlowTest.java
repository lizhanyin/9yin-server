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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 内功特效战斗管线接线流测（NeiGongEffects 分发 + PlayerActor 护盾/state）：
 * 受击护盾吸收、attacker 附伤互斥窗口、周期 ticker 拉起、reflect 反弹击杀。
 * Lua 契约层由 {@link com.nineyin.server.script.LuaNeigongHookTest} 覆盖。
 */
class NeiGongEffectsFlowTest {

    private static final Path GO_SERVER_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");
    private static Path combinedRoot;

    /**
     * 单根拼装：share 资源在 GO 仓、lua 脚本在本仓——临时根 junction 两棵树
     * （resources→GO 仓 / scripts→本仓），ServerPaths 指向拼装根后重建 Lua 引擎。
     * AfterAll 还原仓库根并 reload，避免污染后续测试类的根定位。
     */
    @BeforeAll
    static void combineResourceAndScriptRoots() throws Exception {
        Path scriptsRoot = Path.of("").toAbsolutePath().normalize().getParent();
        Assumptions.assumeTrue(Files.isDirectory(
                        GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过内功战斗接线流测");
        Assumptions.assumeTrue(Files.isDirectory(scriptsRoot.resolve(Path.of("scripts", "neigong"))),
                "scripts/neigong 不可用，跳过内功战斗接线流测");
        combinedRoot = Files.createTempDirectory("neigong-flow-root");
        junction(combinedRoot.resolve("resources"), GO_SERVER_ROOT.resolve("resources"));
        junction(combinedRoot.resolve("scripts"), scriptsRoot.resolve("scripts"));
        com.nineyin.util.ServerPaths.overrideServerRoot(combinedRoot);
        com.nineyin.server.script.LuaScriptService.getInstance().reload();
    }

    @AfterAll
    static void restoreRepoRoot() {
        if (combinedRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(
                    Path.of("").toAbsolutePath().normalize().getParent());
            com.nineyin.server.script.LuaScriptService.getInstance().reload();
        }
    }

    /** Windows 目录 junction（免管理员）；失败则跳过本类。 */
    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).inheritIO().start();
        Assumptions.assumeTrue(process.waitFor() == 0, "mklink /J 不可用，跳过（link=" + link + "）");
    }

    /** 任务只记录，测试手动执行。 */
    static final class RecordingScheduler extends SkillCastHandlerFlowTest.LoginFlowTestSchedulerAdapter {

        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
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
        final FightState.NpcCombat combat;

        Harness(int npcMaxHp, Random random) {
            world.begin("ini\\scene\\scene01", "scene01");
            world.registerNpc(2, SkillCastHandlerFlowTest.combatNpc(
                    "monv030101", npcMaxHp, 100f, 20f, 100f));
            combat = new FightState.NpcCombat(2, 1, npcMaxHp);
        }

        SkillCastHandler.CastContext context(Random random) {
            return new SkillCastHandler.CastContext(connection, player, world, fight,
                    learned, skillRows, scheduler, clock, random);
        }

        void cast(String skillId, Random random) {
            assertTrue(fight.selectTarget(world, 2, 1), "combat NPC selectable");
            SkillCastHandler.handle(context(random),
                    SkillCastHandlerFlowTest.useSkillMessage(skillId, 100f, 20f, 100f, 1.0f));
        }
    }

    /** 暴力找首 double 落在 [0,t) 的种子（ctx.rand.r_proc 可控且确定）。 */
    private static long seedWithFirstDoubleBelow(double threshold) {
        for (long seed = 0; seed < 100_000; seed++) {
            if (new Random(seed).nextDouble() < threshold) {
                return seed;
            }
        }
        throw new IllegalStateException("no seed found");
    }

    private static long seedWithFirstDoubleAtLeast(double threshold) {
        for (long seed = 0; seed < 100_000; seed++) {
            if (new Random(seed).nextDouble() >= threshold) {
                return seed;
            }
        }
        throw new IllegalStateException("no seed found");
    }

    /** 默认书 ng_wd_001（level 30/36 → 档3，九阴志阶段1 帽3）：proc 吸收 210 点 / 12s。 */
    @Test
    void victimProcShieldAbsorbsNpcDamage() {
        Harness h = new Harness(100_000, null);
        SkillCastHandler.CastContext ctx = h.context(
                new Random(seedWithFirstDoubleBelow(0.10)));
        NeiGongEffects.VictimOutcome outcome = NeiGongEffects.onVictim(
                ctx, h.world.combatNpcInfo(2), h.combat, 1, false);
        assertEquals(1, outcome.applied(), "护盾不改本击待落血值（吸收在 applyNpcDamage 内）");
        assertEquals(0, outcome.reflect());
        assertEquals(210, h.player.neigongShieldTotal(), "层3 激发真气吸收 210");
        int hpBefore = h.player.actorState().hp();
        assertEquals(0, h.player.applyNpcDamage(1), "首击全吸收（静默，对齐格挡形态）");
        assertEquals(209, h.player.neigongShieldTotal());
        assertEquals(hpBefore, h.player.actorState().hp());
        assertEquals(0, h.player.applyNpcDamage(1), "盾池续吸收");
        assertEquals(208, h.player.neigongShieldTotal());
    }

    @Test
    void victimNoProcPassesDamageThrough() {
        Harness h = new Harness(100_000, null);
        SkillCastHandler.CastContext ctx = h.context(
                new Random(seedWithFirstDoubleAtLeast(0.10)));
        NeiGongEffects.VictimOutcome outcome = NeiGongEffects.onVictim(
                ctx, h.world.combatNpcInfo(2), h.combat, 1, false);
        assertEquals(1, outcome.applied());
        assertEquals(0, h.player.neigongShieldTotal());
        int hpBefore = h.player.actorState().hp();
        assertEquals(1, h.player.applyNpcDamage(1), "无盾实伤 1 落血");
        assertEquals(hpBefore - 1, h.player.actorState().hp());
    }

    /**
     * bh_001 天时窗（书 ng_wd_002 level 1/36 → 档1：附阳伤 30 / 窗 6s）：
     * proc 触发 → 窗口 state 存 PlayerActor；窗内免掷续伤；窗过回落。
     */
    @Test
    void attackerExtraDamageWithMutexWindow() {
        Harness h = new Harness(100_000, null);
        h.player.equipNeiGong("ng_wd_002");
        h.player.progress().book("ng_wd_002").buffId = "buf_ng_bh_001";
        FightState.HitTarget target =
                new FightState.HitTarget(h.world.combatNpcInfo(2), h.combat);

        int extra = NeiGongEffects.onAttack(h.context(
                        new Random(seedWithFirstDoubleBelow(0.20))), target, 100, false);
        assertEquals(30, extra, "proc 掷中：层1 附阳伤 30");
        assertEquals("tianshi", h.player.neigongStateOf("ng_wd_002").get("mode"),
                "互斥窗口 state 落 PlayerActor");

        // 窗口内：r_proc 必不中仍稳定附伤（state 回喂免掷）
        assertEquals(30, NeiGongEffects.onAttack(
                h.context(new Random(seedWithFirstDoubleAtLeast(0.20))), target, 100, false));

        // 窗过（+8s > 6s）且不掷中 → 0
        h.clock.advanceMillis(8_000);
        assertEquals(0, NeiGongEffects.onAttack(
                h.context(new Random(seedWithFirstDoubleAtLeast(0.20))), target, 100, false));
    }

    /** em_002 周期门（书同上 6/36 → 档1：吸收 120 / 20s）：ticker 拉起即触发一次。 */
    @Test
    void periodicTickerFiresWithHookBookOnly() {
        CombatSkill skill = null;
        for (String id : List.of("CS_wd_tjq08", "wx_sl_tzcq08", "CS_jh_yydf04_hide")) {
            skill = CombatSkillCatalog.load().definition(id, 1);
            if (skill != null) {
                break;
            }
        }
        Assumptions.assumeTrue(skill != null, "无可测伤害技");

        // 对照组：无钩子书 → ticker 不拉起
        Harness plain = new Harness(100_000, null);
        plain.learned.put(skill.id(), 1);
        plain.cast(skill.id(), new Random(42));
        assertFalse(plain.fight.neigongTickerStarted().get(), "无周期钩子书不建 ticker（零调度污染）");

        // 换挂 em_002 on_tick：cast → ensureTicker 建任务 → runAll 触发护盾 + 自续
        Harness hooked = new Harness(100_000, null);
        hooked.player.equipNeiGong("ng_wd_002");
        hooked.player.progress().book("ng_wd_002").buffId = "buf_ng_em_002";
        hooked.learned.put(skill.id(), 1);
        int tasksBefore = hooked.scheduler.tasks.size();
        hooked.cast(skill.id(), new Random(42));
        assertTrue(hooked.fight.neigongTickerStarted().get(), "周期书 ticker 已拉起");
        hooked.scheduler.runAll();
        assertEquals(120, hooked.player.neigongShieldTotal(), "on_tick 首跳（since=-1）护盾 120");
        assertTrue(hooked.scheduler.tasks.size() > tasksBefore, "脱战前 ticker 自续重排");
    }

    /** reflect 反弹落 NPC 血、击杀走 onNpcKilled 常规链（重生任务排期）。 */
    @Test
    void reflectDamagesNpcAndKillGoesThroughOnNpcKilled() {
        Harness h = new Harness(5, null);
        SkillCastHandler.CastContext ctx = h.context(new Random(42));
        NeiGongEffects.applyReflect(ctx, h.world.combatNpcInfo(2), h.combat, 3);
        assertEquals(2, h.combat.state().hp(), "反弹 3 落 NPC 血");
        int tasksBefore = h.scheduler.tasks.size();
        NeiGongEffects.applyReflect(ctx, h.world.combatNpcInfo(2), h.combat, 5);
        assertEquals(0, h.combat.state().hp(), "反弹致死");
        assertTrue(h.scheduler.tasks.size() > tasksBefore, "击杀走 onNpcKilled（重生任务排期）");
        // 死亡后不再反弹
        NeiGongEffects.applyReflect(ctx, h.world.combatNpcInfo(2), h.combat, 1);
        assertEquals(0, h.combat.state().hp());
    }

    /** msg24 c1=7 被击飘字回归：无 proc 时 NPC 反击实伤 1 正常落血发帧（applyNpcDamage 返回值修复锚点）。 */
    @Test
    void npcRetaliationStillLandsDamageFrameWithoutProc() {
        Harness h = new Harness(100_000, null);
        CombatSkill skill = null;
        for (String id : List.of("CS_wd_tjq08", "wx_sl_tzcq08", "CS_jh_yydf04_hide")) {
            skill = CombatSkillCatalog.load().definition(id, 1);
            if (skill != null) {
                break;
            }
        }
        Assumptions.assumeTrue(skill != null, "无可测伤害技");
        h.learned.put(skill.id(), 1);
        // 种子使 NPC tick 的 r_proc（cast 掷骰后的下一 double）≥0.10：不 proc，走实伤路径
        long seed = -1;
        int[] span = h.player.meleeDamageRange();
        for (long candidate = 0; candidate < 100_000; candidate++) {
            Random probe = new Random(candidate);
            probe.nextDouble();
            probe.nextDouble();
            if (span[1] > span[0]) {
                probe.nextInt(span[1] - span[0] + 1);
            }
            if (probe.nextDouble() >= 0.10) {
                seed = candidate;
                break;
            }
        }
        Assumptions.assumeTrue(seed >= 0, "未找到不 proc 种子");
        Random castRandom = new Random(seed);
        h.cast(skill.id(), castRandom);
        int hpBefore = h.player.actorState().hp();
        h.scheduler.runAll();
        boolean takenFrame = false;
        Object outbound;
        while ((outbound = h.channel.readOutbound()) != null) {
            CustomC2S.Message parsed = CustomC2S.parseDirect((byte[]) outbound);
            if (parsed != null && parsed.values.size() >= 7
                    && parsed.values.get(0).exactInt32() == 24
                    && parsed.values.get(2).tag == 2
                    && parsed.values.get(2).exactInt32() == 7) {
                assertEquals(1, parsed.values.get(3).exactInt32(), "被击飘字实伤 1");
                takenFrame = true;
            }
        }
        assertTrue(takenFrame, "无 proc 时被击 msg24 c1=7 帧在发（静默吸收才是例外）");
        assertEquals(hpBefore - 1, h.player.actorState().hp());
    }
}
