package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * bug.txt:45-49 新增链路：
 * ① 圈定范围技能（HitShape 11 → GROUND_AREA）：免选中目标，伤害以 211 长格式
 *    落点（idx 11-13 目标坐标）为圆心结算；落点超宽容射程静默拒；无落点受理但零伤害。
 * ② 冲锋技能（skill_static.CanRush）：单体冲锋仍是 SELECTED 模式，受理=技能真实
 *    射程 TSP2（八轮用户拍板，RushRange/15m/60m 放宽全废）——射程内受理（c61 冲刺
 *    +msg19 锁定帧），超射程静默拒。
 * ③ 怒招怒气闭环（wx_wd_tjj08 = 扣剑平抹杀，AConsumeSP=10）：SP &lt; 消耗静默拒且不扣；
 *    受理扣 SP；每次落血命中 +2 回怒并发单属性 SP 帧（msg24 计数做确定性校验）。
 * ④ 冲锋位移（out2 官方时间线 2026-11-06 定谳）：锁定/冲锋族出招走 **msg19 锁定帧**
 *    （24 值：LockSrcTime/LockTargetTime/双方坐标/BindDistance/绑定点），距目标超绑定
 *    距离先发 **msg24 c1=61 冲刺指令**（LockGRushAction 动画名+终点）+location 终点锚；
 *    自增益招（IsDamage=0）=msg20 带施法者自己、其余伤害技=msg20 空对象 obj(0,0)。
 */
class GroundChargeAndRageTest {

    private static final Path GO_SERVER_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");

    @BeforeAll
    static void needsSharedSkillResources() {
        Assumptions.assumeTrue(Files.isDirectory(
                        GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过圈定/冲锋/怒气链路");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    /** 任务只记录，测试手动全部执行（多段 tick/CanUse 恢复/收尾）。 */
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

        Harness(int npcMaxHp, float npcX, float npcZ) {
            world.begin("ini\\scene\\scene01", "scene01");
            world.registerNpc(2, SkillCastHandlerFlowTest.combatNpc(
                    "monv030101", npcMaxHp, npcX, 20f, npcZ));
            // 换装无 buff 钩子的书：本文件用「帧数推 SP」，而默认书 buf_ng_wd_001 的
            // 受击护盾 proc 会静默吸收被击帧（吸收=格挡式零帧，但被击回怒照 +1）——
            // 怒气/冲锋语义与内功 proc 解耦，proc 行为由 NeiGongEffectsFlowTest 专测。
            player.equipNeiGong("ng_wd_002");
        }

        void cast(String skillId, float x, float y, float z) {
            assertTrue(fight.selectTarget(world, 2, 1), "combat NPC selectable");
            handle(SkillCastHandlerFlowTest.useSkillMessage(skillId, x, y, z, 1.0f));
        }

        void handle(CustomC2S.Message message) {
            SkillCastHandler.handle(context(), message);
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

    // ------------------------------------------------------------------ 用例

    @Test
    void groundAreaSkillSettlesAtCastPointWithoutSelection() {
        CombatSkill skill = firstCompiled(List.of("wx_jz_ff05", "wx_jh_jwdf06", "zs_default_05"));
        assumeTrue(skill != null, "无 GROUND_AREA 技能可测");
        assertEquals(CombatSkill.TargetMode.GROUND_AREA, skill.targetMode(),
                "HitShape 11 → GROUND_AREA");
        assertFalse(skill.requiresTarget(), "圈定技能免选中目标");
        // 三候选全挂武器限制（UseLimit 2/4/28 → 102/104/117）：测试角色配对应武器过闸
        int weapon = skill.weaponItemTypes().isEmpty() ? 0 : skill.weaponItemTypes().iterator().next();

        // 不选中目标：落点（idx 11-13）即圆心，圈内 NPC 被结算
        Harness harness = new Harness(100_000, 100f, 100f);
        harness.player.noteEquippedWeaponItemType(weapon);
        harness.learned.put(skill.id(), 1);
        harness.handle(useSkillLongMessage(skill.id(), 100f, 20f, 100f, 1.0f, 100f, 20f, 100f));
        harness.scheduler.runAll();
        List<byte[]> frames = harness.drain();
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11),
                "无选中目标也受理出招（CD 行下发）");
        assertTrue(msg24Count(frames) >= 1, "以落点为圆心结算伤害");

        // 落点 200 米外：超宽容射程（30m）静默拒
        Harness far = new Harness(1000, 100f, 100f);
        far.player.noteEquippedWeaponItemType(weapon);
        far.learned.put(skill.id(), 1);
        far.handle(useSkillLongMessage(skill.id(), 100f, 20f, 100f, 1.0f, 100f, 20f, 300f));
        assertNull(far.channel.readOutbound(), "落点超宽容射程静默拒（零帧）");

        // 短格式无落点且无选中：受理但伤害空集（Go "no center" 同款）
        Harness noPoint = new Harness(1000, 100f, 100f);
        noPoint.player.noteEquippedWeaponItemType(weapon);
        noPoint.learned.put(skill.id(), 1);
        noPoint.handle(SkillCastHandlerFlowTest.useSkillMessage(skill.id(), 100f, 20f, 100f, 1.0f));
        noPoint.scheduler.runAll();
        List<byte[]> none = noPoint.drain();
        assertTrue(none.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "无落点仍受理出招");
        assertEquals(0, msg24Count(none), "无中心线索：伤害空集不结算");
    }

    @Test
    void chargeSkillRangeFollowsTargetShapePara2() {
        CombatSkill rush = firstCompiled(List.of("CS_jh_cqgf01", "CS_wd_tjj01", "CS_sl_djgq07"));
        assumeTrue(rush != null, "无 CanRush 技能可测");
        assertTrue(rush.canRush(), "skill_static.CanRush 编译进定义");
        assertTrue(rush.requiresTarget(), "单体冲锋仍是 SELECTED 模式");
        CombatSkillCatalog.SkillRowData row =
                CombatSkillCatalog.load().skillRowData(rush.id(), rush.level());
        assumeTrue(row != null && row.targetShapePara2() > 0, "冲锋族 TSP2 可解析");

        CombatSkill plain = null;
        for (String id : List.of("wx_tm_fg06", "CS_wd_tjq04", "CS_jh_yydf04_hide")) {
            CombatSkill candidate = CombatSkillCatalog.load().definition(id, 1);
            if (candidate != null && candidate.requiresTarget() && !candidate.canRush()) {
                plain = candidate;
                break;
            }
        }
        assumeTrue(plain != null, "无普通单体技能做射程对比");

        // 12 米：非冲锋技能超距静默拒（不进暂存链——msg25 只属显式开启的回退链）
        Harness plainFar = new Harness(1000, 12f, 0f);
        plainFar.learned.put(plain.id(), 1);
        plainFar.cast(plain.id(), 0f, 20f, 0f);
        assertNull(plainFar.channel.readOutbound(), "非冲锋技能 12m 超距静默拒");

        // 冲锋受理=技能真实射程 TSP2（八轮，cqgf01 族 TSP2=3）：12m/5m 超射程一律
        // 静默拒（零帧）——RushRange/15m 回落不再放宽；2m 射程内受理（msg19+c61）
        Harness rushOut = new Harness(1000, 12f, 0f);
        rushOut.learned.put(rush.id(), 1);
        rushOut.cast(rush.id(), 0f, 20f, 0f);
        assertNull(rushOut.channel.readOutbound(), "冲锋 12m 超射程静默拒（零帧）");

        Harness rushMid = new Harness(1000, 5f, 0f);
        rushMid.learned.put(rush.id(), 1);
        rushMid.cast(rush.id(), 0f, 20f, 0f);
        assertNull(rushMid.channel.readOutbound(), "冲锋 5m 超射程静默拒（TSP2=3）");

        Harness rushIn = new Harness(1000, 2f, 0f);
        rushIn.learned.put(rush.id(), 1);
        rushIn.cast(rush.id(), 0f, 20f, 0f);
        List<byte[]> accepted = rushIn.drain();
        assertTrue(accepted.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "射程内立即受理（CD 行）");
        assertTrue(hasCustomMsg(accepted, 19), "射程内受理发 msg19 锁定帧");
        assertFalse(hasCustomMsg(accepted, 25), "射程内无 skill_pause");
        assertFalse(rushIn.player.rushInFlight(), "受理即出招（无 rush in flight）");
    }

    @Test
    void rageSkillGateSpendsAndAward() {
        CombatSkill rage = firstCompiled(List.of("wx_sl_tzcq08", "wx_tm_fg06", "wx_wd_tjj08"));
        assumeTrue(rage != null, "无怒招（AConsumeSP>0）可测");
        assertTrue(rage.spCost() > 0, "AConsumeSP 编译进 spCost");

        // 怒气不足：静默拒（零帧）且不扣减
        Harness low = new Harness(100_000, 100f, 100f);
        low.learned.put(rage.id(), 1);
        low.player.setSP(rage.spCost() - 1);
        low.cast(rage.id(), 100f, 20f, 100f);
        assertNull(low.channel.readOutbound(), "SP 不足静默拒（零帧）");
        assertEquals(rage.spCost() - 1, low.player.sp(), "SP 不被扣减");

        // 怒气足够：受理 → 扣 SP → 每次落血 +2 回怒 + 被击 +1（NPC 反击 AI 接线后计入）
        Harness ok = new Harness(100_000, 100f, 100f);
        ok.learned.put(rage.id(), 1);
        int before = rage.spCost() + 10;
        ok.player.setSP(before);
        ok.cast(rage.id(), 100f, 20f, 100f);
        ok.scheduler.runAll();
        List<byte[]> frames = ok.drain();
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "怒气足够受理出招");
        int[] tally = rageTally(frames, rage.id());
        int landed = tally[0];
        int expected = before - rage.spCost()
                + landed * SkillCastHandler.RAGE_PER_HIT_DEALT
                + tally[1] * SkillCastHandler.RAGE_TAKEN_UNPARRIED;
        assertEquals(expected, ok.player.sp(), "扣 SP 后每次命中 +2、被击 +1（多段按段计）");
        if (landed > 0) {
            final int sp = expected;
            assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x10 && f[1] == 0
                            && Integer.valueOf(sp).equals(
                            SkillCastHandlerFlowTest.properties(f).get("SP"))),
                    "回怒单属性 SP 帧下发（区别于出招 vital 面的 SP=" + (before - rage.spCost()) + "）");
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 冲锋位移闭环（官服 57378/52795 抓包）：冲锋招受理时 msg20 动作帧第三参=
     * 目标对象——客户端据此本地执行冲锋（修复前恒空对象 → 「伤害出了人没过去」）；
     * 同距非冲锋单体技仍空对象；自增益招（IsDamage=0）目标=施法者自己。
     */
    @Test
    void chargeSkillMsg20CarriesTargetObject() {
        CombatSkill rush = firstCompiled(List.of("CS_jh_cqgf01", "CS_wd_tjj01", "CS_sl_djgq07"));
        assumeTrue(rush != null, "无 CanRush 技能可测");
        assertTrue(rush.lockSkill(), "CanRush 编译进 lockSkill");

        CombatSkill plain = null;
        for (String id : List.of("CS_jh_yydf04_hide", "CS_wd_tjq08", "wx_tm_fg06")) {
            CombatSkill candidate = CombatSkillCatalog.load().definition(id, 1);
            // 官服形态三分：锁定族（含非冲锋 SkillLock 如 gxfhj02/05）带目标、
            // IsDamage=0 带自己、其余 SkillNormal 伤害技空对象——对照组取第三类
            if (candidate != null && candidate.requiresTarget() && !candidate.lockSkill()
                    && candidate.isDamage()) {
                plain = candidate;
                break;
            }
        }
        CombatSkill self = firstCompiled(List.of("CS_jh_yydf05", "CS_jhgp_gxfhj04", "CS_jh_jysg06"));

        long npcKey = (2 & 0xFFFFFFFFL) | (1L << 32);
        int weapon = rush.weaponItemTypes().isEmpty() ? 0 : rush.weaponItemTypes().iterator().next();

        // 冲锋技贴身（2m ≤ TSP2）直接受理：msg19 目标=选中 NPC（无 211 目标对象
        // 时回落选中目标）。超射程静默拒（见 chargeSkillRangeFollowsTargetShapePara2）。
        Harness rushIn = new Harness(1000, 2f, 0f);
        rushIn.player.noteEquippedWeaponItemType(weapon);
        rushIn.learned.put(rush.id(), 1);
        rushIn.cast(rush.id(), 0f, 20f, 0f);
        assertEquals(npcKey, msg19Target(rushIn.drain(), rush.id()),
                "锁定冲锋招 msg19 目标对象=选中 NPC");

        // 211 长格式带目标对象：msg20 动作目标随 aim 对象（官服每发均带）
        Harness aim = new Harness(1000, 2f, 0f);
        aim.player.noteEquippedWeaponItemType(weapon);
        aim.learned.put(rush.id(), 1);
        assertTrue(aim.fight.selectTarget(aim.world, 2, 1), "combat NPC selectable");
        aim.handle(useSkillAimMessage(rush.id(), 0f, 20f, 0f, 1.0f, 2, 1, 2f, 20f, 0f));
        assertEquals(npcKey, msg19Target(aim.drain(), rush.id()),
                "211 目标对象作为 msg19 锁定目标");

        if (plain != null) {
            int plainWeapon = plain.weaponItemTypes().isEmpty() ? 0
                    : plain.weaponItemTypes().iterator().next();
            Harness plainIn = new Harness(1000, 5f, 0f);
            plainIn.player.noteEquippedWeaponItemType(plainWeapon);
            plainIn.learned.put(plain.id(), 1);
            plainIn.cast(plain.id(), 0f, 20f, 0f);
            assertEquals(0L, msg20Target(plainIn.drain(), plain.id()),
                    "非锁定伤害技 msg20 目标仍为空对象");
        }

        assumeTrue(self != null && !self.isDamage(), "无自增益招（IsDamage=0）可测");
        int selfWeapon = self.weaponItemTypes().isEmpty() ? 0
                : self.weaponItemTypes().iterator().next();
        Harness selfIn = new Harness(1000, 5f, 0f);
        selfIn.player.noteEquippedWeaponItemType(selfWeapon);
        selfIn.learned.put(self.id(), 1);
        selfIn.cast(self.id(), 0f, 20f, 0f);
        assertEquals((PlayerActor.PLAYER_OBJECT_ID & 0xFFFFFFFFL)
                        | ((long) PlayerActor.PLAYER_OWNER_ID << 32),
                msg20Target(selfIn.drain(), self.id()),
                "自增益招 msg20 目标=施法者自己");
    }

    /** msg19 锁定帧目标对象（[19, obj 施法者, obj 目标, 技能串, ...]；无匹配返回 null）。 */
    private static Long msg19Target(List<byte[]> frames, String skillId) {
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed == null || parsed.values.isEmpty()
                    || parsed.values.get(0).exactInt32() != 19 || parsed.values.size() < 4) {
                continue;
            }
            if (skillId.equals(parsed.values.get(3).textOrNull())) {
                CustomC2S.Value target = parsed.values.get(2);
                return target.tag == 8 ? target.int64 : null;
            }
        }
        return null;
    }

    /** msg20 动作帧目标对象（tag8 int64；无匹配帧返回 null，空对象=0）。 */
    private static Long msg20Target(List<byte[]> frames, String skillId) {
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed == null || parsed.values.isEmpty()
                    || parsed.values.get(0).exactInt32() != 20 || parsed.values.size() < 4) {
                continue;
            }
            if (skillId.equals(parsed.values.get(2).textOrNull())) {
                CustomC2S.Value target = parsed.values.get(3);
                return target.tag == 8 ? target.int64 : null;
            }
        }
        return null;
    }

    /**
     * 冲锋受理/超距拒双态（八轮：受理=技能真实射程 TSP2；RushStageFlowTest 因生产
     * 资源盘不可达跳过时的本域覆盖）：cqgf01 TSP2=3——2m 受理（msg19）；12m 超射程
     * 静默拒（零帧）。暂存链到位续招仅 -Dnineyin.rush.stage=true 显式开启时存在。
     */
    @Test
    void chargeArrivalRadiusGatesActualCast() {
        CombatSkill rush = firstCompiled(List.of("CS_jh_cqgf01", "CS_wd_tjj01", "CS_sl_djgq07"));
        assumeTrue(rush != null, "无 CanRush 技能可测");
        int weapon = rush.weaponItemTypes().isEmpty() ? 0 : rush.weaponItemTypes().iterator().next();

        // 2m（TSP2=3 内）：立即受理（msg19 锁定帧）
        Harness in = new Harness(1000, 2f, 0f);
        in.player.noteEquippedWeaponItemType(weapon);
        in.learned.put(rush.id(), 1);
        in.cast(rush.id(), 0f, 20f, 0f);
        List<byte[]> accepted = in.drain();
        assertTrue(accepted.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "2m 立即受理（CD 行）");
        assertTrue(hasCustomMsg(accepted, 19), "2m 受理发 msg19 锁定帧");
        assertFalse(hasCustomMsg(accepted, 25), "射程内不暂存");

        // 12m（超 TSP2）：静默拒（零帧）
        Harness h = new Harness(1000, 12f, 0f);
        h.player.noteEquippedWeaponItemType(weapon);
        h.learned.put(rush.id(), 1);
        h.cast(rush.id(), 0f, 20f, 0f);
        assertNull(h.channel.readOutbound(), "12m 超射程静默拒（零帧）");
    }

    /**
     * 官方 CanUse 语义（52795 取证：11730 帧中仅 QGP 门控行出 0，怒招行 SP=26
     * 低于消耗 50 仍恒推 1；SP 跨阈值无任何行帧）：出招置 0、个人 CD 尽置 1
     * （不看 SP）；SP 变化（消耗/回怒）不推兄弟行——资源灰由客户端按 SP+行内
     * AConsumeSP 自算（bug.txt「怒招不够怒气快捷栏不灰」由此闭环）。
     */
    @Test
    void rageShortcutAvailabilityFollowsOfficialSemantics() {
        CombatSkill rage = firstCompiled(List.of("wx_sl_tzcq08", "wx_tm_fg06", "wx_wd_tjj08"));
        assumeTrue(rage != null && rage.spCost() > 0, "无可放怒招可测");
        String sibling = "wx_wd_tjj08".equals(rage.id()) ? "wx_sl_tzcq08" : "wx_wd_tjj08";
        CombatSkillCatalog.SkillRowData siblingRow =
                CombatSkillCatalog.load().skillRowData(sibling, 1);
        assumeTrue(siblingRow != null && siblingRow.aConsumeSP() > 0, "无第二怒招做兄弟行");
        assumeTrue(rage.spCost() != siblingRow.aConsumeSP(), "双怒招消耗需不同");

        Harness h = new Harness(100_000, 100f, 100f);
        h.learned.put(rage.id(), 1);
        h.learned.put(sibling, 1);
        h.skillRows.put(rage.id(), 1);
        h.skillRows.put(sibling, 2);

        int costBig = Math.max(rage.spCost(), siblingRow.aConsumeSP());
        int rowBig = costBig == rage.spCost() ? 1 : 2;
        int rowSmall = rowBig == 1 ? 2 : 1;

        // SP=costBig+5：出招（costBig 在手）→ SP=5：只有出招行置 0/CD 尽置 1；
        // 兄弟行全程不得出现 CanUse 帧（官方无 SP 维度行帧）
        h.player.setSP(costBig + 5);
        h.cast(rage.id(), 100f, 20f, 100f);
        h.scheduler.runAll();
        List<byte[]> frames = h.drain();
        List<int[]> pushes = canUsePushes(frames);
        assertTrue(pushes.stream().anyMatch(p -> p[0] == rowBig && p[1] == 0),
                "出招后大怒招行置 0，实际=" + pushes);
        assertFalse(pushes.stream().anyMatch(p -> p[0] == rowSmall),
                "SP 被压到小阈值下也不推兄弟行，实际=" + pushes);

        int[] tally = rageTally(frames, rage.id());
        int finalSp = h.player.sp();
        assertEquals(5 + tally[0] * SkillCastHandler.RAGE_PER_HIT_DEALT
                        + tally[1] * SkillCastHandler.RAGE_TAKEN_UNPARRIED, finalSp,
                "扣到 5 后按落血次数回怒（+被击 +1，NPC 反击 AI 已接线）");
        if (finalSp < costBig) {
            assertTrue(pushes.stream().anyMatch(p -> p[0] == rowBig && p[1] == 1),
                    "CD 尽置 1（不看 SP 是否够），实际=" + pushes);
        }
    }

    private static CombatSkill firstCompiled(List<String> ids) {
        for (String id : ids) {
            CombatSkill skill = CombatSkillCatalog.load().definition(id, 1);
            if (skill != null) {
                return skill;
            }
        }
        return null;
    }

    /** 玩家伤害飘字（msg24）帧计数 = 实际落血次数。 */
    private static int msg24Count(List<byte[]> frames) {
        int count = 0;
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed != null && !parsed.values.isEmpty()
                    && parsed.values.get(0).exactInt32() == 24) {
                count++;
            }
        }
        return count;
    }

    /** 是否存在指定 id 的 custom 帧（0x1E）。 */
    private static boolean hasCustomMsg(List<byte[]> frames, int msgId) {
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed != null && !parsed.values.isEmpty()
                    && parsed.values.get(0).exactInt32() == msgId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 怒气收支口径（NpcCombatAi 反击介入后）：[玩家技能形态落血次数, 被击次数]。
     * msg24 只计 11 值技能形态（c1=17 且技能 id 匹配）——NPC 反击飘字（c1=7 十值
     * 形态）与 c61 疾跑帧不计入落血；被击次数按 NPC 反击帧计（未格挡 +1/次）。
     */
    private static int[] rageTally(List<byte[]> frames, String skillId) {
        int landed = 0;
        int taken = 0;
        for (byte[] frame : frames) {
            CustomC2S.Message parsed = CustomC2S.parseDirect(frame);
            if (parsed == null || parsed.values.isEmpty()
                    || parsed.values.get(0).exactInt32() != 24 || parsed.values.size() < 7) {
                continue;
            }
            if (parsed.values.get(2).tag == 2 && parsed.values.get(2).exactInt32() == 17
                    && skillId.equals(parsed.values.get(6).textOrNull())) {
                landed++;
            } else if (parsed.values.get(2).tag == 2
                    && parsed.values.get(2).exactInt32() == 7) {
                taken++; // NPC→玩家反击飘字（c1=7 形态）
            }
        }
        return new int[]{landed, taken};
    }

    /** View40 CanUse 推送序列（0x10 isView=1 15B 形态 → [row,val] 列表）。 */
    private static List<int[]> canUsePushes(List<byte[]> frames) {
        List<int[]> pushes = new ArrayList<>();
        for (byte[] f : frames) {
            if (f == null || f.length != 15 || (f[0] & 0xFF) != 0x10 || f[1] != 1) {
                continue;
            }
            if (u32le(f, 2) != SkillCastHandler.SKILL_VIEW_ID) {
                continue;
            }
            pushes.add(new int[]{u32le(f, 6), f[14]});
        }
        return pushes;
    }

    private static int u32le(byte[] f, int off) {
        return (f[off] & 0xFF) | ((f[off + 1] & 0xFF) << 8)
                | ((f[off + 2] & 0xFF) << 16) | ((f[off + 3] & 0xFF) << 24);
    }

    /**
     * 0x1E 直达 211 长格式（docs/combat-skill-211.md §1 的 111B 形态）：
     * [211, 技能, x,y,z, 朝向, tx=0,ty=0,tz=0, 空串, obj(0,0), ax,ay,az]——
     * 目标坐标（idx 11-13）承载圈定落点。
     */
    static CustomC2S.Message useSkillLongMessage(String skillId, float x, float y, float z,
                                                 float orient, float ax, float ay, float az) {
        PacketWriter writer = PacketWriter.ofCapacity(128);
        writer.writeU8(0x1E).writeU16(14);
        writer.writeU8(2).writeU32(211);
        byte[] id = skillId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(id.length + 1).writeBytes(id).writeU8(0);
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(x));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(y));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(z));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(orient));
        writer.writeU8(4).writeU32(0);
        writer.writeU8(4).writeU32(0);
        writer.writeU8(4).writeU32(0);
        writer.writeU8(6).writeU32(1).writeU8(0);
        writer.writeU8(8).writeU32(0).writeU32(0);
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(ax));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(ay));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(az));
        return CustomC2S.parseDirect(writer.toBytes());
    }

    /**
     * 0x1E 直达 211 长格式带目标对象（idx 10 = obj(aimObjId,aimOwner)，idx 11-13 =
     * 目标坐标）：冲锋招客户端实发形态（官服抓包每发均携带）。
     */
    static CustomC2S.Message useSkillAimMessage(String skillId, float x, float y, float z,
                                                float orient, int aimObjId, int aimOwner,
                                                float ax, float ay, float az) {
        PacketWriter writer = PacketWriter.ofCapacity(128);
        writer.writeU8(0x1E).writeU16(14);
        writer.writeU8(2).writeU32(211);
        byte[] id = skillId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(id.length + 1).writeBytes(id).writeU8(0);
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(x));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(y));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(z));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(orient));
        writer.writeU8(4).writeU32(0);
        writer.writeU8(4).writeU32(0);
        writer.writeU8(4).writeU32(0);
        writer.writeU8(6).writeU32(1).writeU8(0);
        writer.writeU8(8).writeU32(aimObjId).writeU32(aimOwner);
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(ax));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(ay));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(az));
        return CustomC2S.parseDirect(writer.toBytes());
    }
}
