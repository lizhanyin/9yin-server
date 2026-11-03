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
 * ② 冲锋技能（skill_static.CanRush）：单体冲锋仍是 SELECTED 模式，射程闸放宽到
 *    冲锋射程（无 RushRange 回落 15 米）——12 米受理/25 米拒绝，同距非冲锋技能仍拒绝。
 * ③ 怒招怒气闭环（wx_wd_tjj08 = 扣剑平抹杀，AConsumeSP=10）：SP &lt; 消耗静默拒且不扣；
 *    受理扣 SP；每次落血命中 +2 回怒并发单属性 SP 帧（msg24 计数做确定性校验）。
 */
class GroundChargeAndRageTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

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

        // 不选中目标：落点（idx 11-13）即圆心，圈内 NPC 被结算
        Harness harness = new Harness(100_000, 100f, 100f);
        harness.learned.put(skill.id(), 1);
        harness.handle(useSkillLongMessage(skill.id(), 100f, 20f, 100f, 1.0f, 100f, 20f, 100f));
        harness.scheduler.runAll();
        List<byte[]> frames = harness.drain();
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11),
                "无选中目标也受理出招（CD 行下发）");
        assertTrue(msg24Count(frames) >= 1, "以落点为圆心结算伤害");

        // 落点 200 米外：超宽容射程（30m）静默拒
        Harness far = new Harness(1000, 100f, 100f);
        far.learned.put(skill.id(), 1);
        far.handle(useSkillLongMessage(skill.id(), 100f, 20f, 100f, 1.0f, 100f, 20f, 300f));
        assertNull(far.channel.readOutbound(), "落点超宽容射程静默拒（零帧）");

        // 短格式无落点且无选中：受理但伤害空集（Go "no center" 同款）
        Harness noPoint = new Harness(1000, 100f, 100f);
        noPoint.learned.put(skill.id(), 1);
        noPoint.handle(SkillCastHandlerFlowTest.useSkillMessage(skill.id(), 100f, 20f, 100f, 1.0f));
        noPoint.scheduler.runAll();
        List<byte[]> none = noPoint.drain();
        assertTrue(none.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "无落点仍受理出招");
        assertEquals(0, msg24Count(none), "无中心线索：伤害空集不结算");
    }

    @Test
    void chargeSkillRangeWidenedByCanRush() {
        CombatSkill rush = firstCompiled(List.of("CS_jh_cqgf01", "CS_wd_tjj01", "CS_sl_djgq07"));
        assumeTrue(rush != null, "无 CanRush 技能可测");
        assertTrue(rush.canRush(), "skill_static.CanRush 编译进定义");
        assertTrue(rush.requiresTarget(), "单体冲锋仍是 SELECTED 模式");

        CombatSkill plain = null;
        for (String id : List.of("wx_tm_fg06", "CS_wd_tjq04", "CS_jh_yydf04_hide")) {
            CombatSkill candidate = CombatSkillCatalog.load().definition(id, 1);
            if (candidate != null && candidate.requiresTarget() && !candidate.canRush()) {
                plain = candidate;
                break;
            }
        }
        assumeTrue(plain != null, "无普通单体技能做射程对比");

        // 12 米：非冲锋技能超 8m 兜底静默拒；冲锋技能在回落射程（15m）内受理
        Harness plainFar = new Harness(1000, 12f, 0f);
        plainFar.learned.put(plain.id(), 1);
        plainFar.cast(plain.id(), 0f, 20f, 0f);
        assertNull(plainFar.channel.readOutbound(), "非冲锋技能 12m 超距静默拒");

        Harness rushIn = new Harness(1000, 12f, 0f);
        rushIn.learned.put(rush.id(), 1);
        rushIn.cast(rush.id(), 0f, 20f, 0f);
        List<byte[]> inFrames = rushIn.drain();
        assertTrue(inFrames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11),
                "冲锋技能 12m 受理（射程闸放宽）");

        // 25 米：超回落射程拒绝
        Harness rushOut = new Harness(1000, 25f, 0f);
        rushOut.learned.put(rush.id(), 1);
        rushOut.cast(rush.id(), 0f, 20f, 0f);
        assertNull(rushOut.channel.readOutbound(), "冲锋技能 25m 超回落射程拒绝");
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

        // 怒气足够：受理 → 扣 SP → 每次落血 +2 回怒（msg24 计数确定性校验）
        Harness ok = new Harness(100_000, 100f, 100f);
        ok.learned.put(rage.id(), 1);
        int before = rage.spCost() + 10;
        ok.player.setSP(before);
        ok.cast(rage.id(), 100f, 20f, 100f);
        ok.scheduler.runAll();
        List<byte[]> frames = ok.drain();
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x11), "怒气足够受理出招");
        int landed = msg24Count(frames);
        int expected = before - rage.spCost() + landed * SkillCastHandler.RAGE_PER_HIT_DEALT;
        assertEquals(expected, ok.player.sp(), "扣 SP 后每次命中 +2（多段按段计）");
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
     * bug.txt「怒招不够怒气快捷栏不灰」：View40 行 CanUse 完全由服务端动态推
     * （官方 61114 登录批 196 帧/62433 战斗 2159 帧实证）。双怒招不同消耗：
     * 出招大消耗招把兄弟行也压灰；回怒跨过小阈值兄弟行 0→1 复亮（真机特殊动画
     * 触发源）；CD 尽回调 SP 不足时大行保持灰。
     */
    @Test
    void rageShortcutAvailabilityFollowsSpThresholds() {
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
        int costSmall = Math.min(rage.spCost(), siblingRow.aConsumeSP());
        int rowBig = costBig == rage.spCost() ? 1 : 2;
        int rowSmall = rowBig == 1 ? 2 : 1;

        // SP=costBig+5：出招（costBig 在手）→ SP=5 < costSmall：两行都应灰
        h.player.setSP(costBig + 5);
        h.cast(rage.id(), 100f, 20f, 100f);
        h.scheduler.runAll();
        List<byte[]> frames = h.drain();
        List<int[]> pushes = canUsePushes(frames);
        assertTrue(pushes.stream().anyMatch(p -> p[0] == rowBig && p[1] == 0),
                "出招后大怒招行置灰，实际=" + pushes);
        assertTrue(pushes.stream().anyMatch(p -> p[0] == rowSmall && p[1] == 0),
                "SP 被压到小阈值下兄弟行置灰，实际=" + pushes);

        int landed = msg24Count(frames);
        int finalSp = h.player.sp();
        assertEquals(5 + landed * SkillCastHandler.RAGE_PER_HIT_DEALT, finalSp,
                "扣到 5 后按落血次数回怒");
        if (finalSp >= costSmall) {
            assertTrue(pushes.stream().anyMatch(p -> p[0] == rowSmall && p[1] == 1),
                    "回怒跨过小阈值兄弟行 0→1 复亮，实际=" + pushes);
        }
        if (finalSp < costBig) {
            assertFalse(pushes.stream().anyMatch(p -> p[0] == rowBig && p[1] == 1),
                    "CD 尽但 SP 不足时大怒招行不得复亮，实际=" + pushes);
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
}
