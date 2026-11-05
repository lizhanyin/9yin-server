package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 冲锋链两态（2026-11-06 五轮定稿）：
 * 默认：受理上限=max(RushRange,15m 回落)=客户端原生冲刺位移上限（op2 yqq02 实证：
 * RushRange=15/TSP2=4，13.2m 距离受理后客户端自冲到位——受理口径是 RushRange 族，
 * 参考服 61653 按 TSP2 暂存属其方言，真机四轮实证暂存链重组簇客户端不执行位移）。
 * 射程内按下=立即受理（msg20 带目标 → 客户端本地冲刺）；超上限（真超距）才走
 * 参考服暂存链：msg25[30000,skill_pause]+msg24 c1=61 疾跑去目标，msg15/212 进入
 * 到达半径（5m）→ 337 回显+msg26+重组 211 续招。
 * -Dnineyin.rush.stage=false（官服语义关回）：锁定/冲锋族 60m 兜底直接受理。
 */
class RushStageFlowTest {

    private static final Path PROD_ROOT = Path.of("F:/Game/Online/9yin/9yin_server_new");

    static final class SettableClock extends Clock {
        private long epochMillis = 1_793_600_000_000L;

        void advanceMillis(long millis) {
            epochMillis += millis;
        }

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
        final Connection connection = new Connection(channel);
        final PlayerActor player = PlayerActor.create("紫泉", 99999, clock);
        final SceneLifecycle world = new SceneLifecycle(connection, scheduler);
        final FightState fight = new FightState();
        final Map<String, Integer> learned = new LinkedHashMap<>();
        final Map<String, Integer> skillRows = new LinkedHashMap<>();

        Harness() {
            world.begin("ini\\scene\\scene01", "scene01");
            // NPC 放在 20 米外（RushRange=15 之外）
            world.registerNpc(2, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 120f, 0f, 100f));
            learned.put("CS_jh_yqq02", 1);
            skillRows.put("CS_jh_yqq02", 1);
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

        void cast(String skillId, float px, float pz) {
            SkillCastHandler.handle(context(), useSkill(skillId, px, pz));
        }


        void land(float px, float pz) {
            CustomC2S.Message message = CustomC2S.Message.of(List.of(
                    CustomC2S.Value.ofInt(212),
                    CustomC2S.Value.ofFloat(px), CustomC2S.Value.ofFloat(0f),
                    CustomC2S.Value.ofFloat(pz), CustomC2S.Value.ofFloat(0f)));
            SkillCastHandler.handleDashLanding(context(), message);
        }
    }

    private static CustomC2S.Message useSkill(String skillId, float px, float pz) {
        return CustomC2S.Message.of(List.of(
                CustomC2S.Value.ofInt(211),
                CustomC2S.Value.ofText(skillId),
                CustomC2S.Value.ofFloat(px), CustomC2S.Value.ofFloat(0f), CustomC2S.Value.ofFloat(pz),
                CustomC2S.Value.ofFloat(1.0f)));
    }

    private static String describe(List<byte[]> frames) {
        StringBuilder out = new StringBuilder();
        for (byte[] frame : frames) {
            if (frame[0] == 0x1E) {
                int msgId = frame[3] == 2 ? int32(frame, 4) : -1; // 0x1E|u16 count|tag|值…
                out.append("m").append(msgId).append(' ');
            } else if (frame[0] == 0x10) {
                out.append("0x10(n=").append(u16(frame, 10)).append(") ");
            } else if (frame[0] == 0x11) {
                out.append("+rec").append(u16(frame, 10)).append(' ');
            } else if (frame[0] == 0x1F) {
                out.append("loc ");
            }
        }
        return out.toString().trim();
    }

    /** 0x1F 位置锚定帧的 f32 坐标（x,y,z,orient @ 偏移 9/13/17/21）。 */
    private static List<Float> locationFloats(byte[] frame) {
        return List.of(Float.intBitsToFloat(int32(frame, 9)),
                Float.intBitsToFloat(int32(frame, 13)),
                Float.intBitsToFloat(int32(frame, 17)),
                Float.intBitsToFloat(int32(frame, 21)));
    }

    /** 0x1E 帧里指定 msgId 的 f32 值序列（tag4；跳过 tag8 对象 8B）。 */
    private static List<Float> floatValues(byte[] frame) {
        List<Float> out = new ArrayList<>();
        int off = 3; // opcode + u16 count
        while (off < frame.length) {
            int tag = frame[off++] & 0xFF;
            if (tag == 2) {
                off += 4;
            } else if (tag == 3 || tag == 5) {
                off += 8;
            } else if (tag == 4) {
                out.add(Float.intBitsToFloat(int32(frame, off)));
                off += 4;
            } else if (tag == 8) {
                off += 8;
            } else {
                break; // 文本/未知值：本测试帧不含
            }
        }
        return out;
    }

    /** 是否存在 msg24 且第 2 值为 61（c1=61 疾跑帧；区别于 msg24 伤害飘字 11 值形态）。 */
    private static boolean hasC61Dash(List<byte[]> frames) {
        for (byte[] frame : frames) {
            if (frame[0] != 0x1E || frame.length < 9 || frame[3] != 2 || int32(frame, 4) != 24) {
                continue;
            }
            int off = 8; // 跳过 tag2 msgId（frame[3] 起 1+4）
            if ((frame[off] & 0xFF) == 8) { // 第 1 值=对象（8B）
                off += 9;
                if (off + 5 <= frame.length && (frame[off] & 0xFF) == 2
                        && int32(frame, off + 1) == 61) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] firstMsg(List<byte[]> frames, int msgId) {
        for (byte[] frame : frames) {
            if (frame[0] == 0x1E && frame.length > 4 && frame[3] == 2 && int32(frame, 4) == msgId) {
                return frame;
            }
        }
        return null;
    }

    private static int int32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | (b[off + 3] << 24);
    }

    private static int u16(byte[] b, int off) {
        return (b[off] | (b[off + 1] << 8)) & 0xFFFF;
    }

    /**
     * 九轮三段式（用户拍板终版）：≤TSP2（yqq02=4）立即受理（c61 冲刺+msg19）；
     * TSP2 < 距离 ≤ 15m 暂存疾跑（msg25+c61 档2）；>15m 静默拒（零帧）。
     */
    @Test
    void rushThreeBandRangeModel() {
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(PROD_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "生产 share/skill 资源不可用，跳过冲锋链");
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill yqq02 = CombatSkillCatalog.load().definition("CS_jh_yqq02", 1);
        Assumptions.assumeTrue(yqq02 != null && yqq02.canRush() && yqq02.rushRange() > 0,
                "CS_jh_yqq02 不满足冲锋前提（CanRush/RushRange）");

        // 3m（射程内）：受理簇 = 0x1F 原地 + 337 原地 + c61 冲刺 + 0x1F 终点 + msg19
        Harness harness = new Harness();
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");
        // NPC 在 (120,0,100)：玩家在 (117,0,100) → 距 3m（> 绑定 2.5m → 有冲刺）
        harness.cast("CS_jh_yqq02", 117f, 100f);
        List<byte[]> frames = harness.drain();
        String seq = describe(frames);
        assertTrue(seq.contains("+rec109"), "射程内受理发 CD 行: " + seq);
        assertTrue(seq.contains("m19"), "受理发 msg19 锁定帧: " + seq);
        assertTrue(hasC61Dash(frames), "受理发 msg24 c1=61 冲刺指令（out2 官方序列）: " + seq);
        assertFalse(seq.contains("m25"), "射程内无 skill_pause: " + seq);
        assertFalse(harness.player.rushInFlight(), "无暂存标记");
        // 0x1F 双帧：原地锚（=211 上报）+ 终点锚；337 恒原地（op2 字节级实证）
        int locCount = 0;
        float locFirstX = 0;
        for (byte[] f : frames) {
            if (f[0] == 0x1F && locCount == 0) {
                locFirstX = Float.intBitsToFloat(int32(f, 9));
            }
            if (f[0] == 0x1F) {
                locCount++;
            }
        }
        assertTrue(locCount >= 2, "0x1F 原地锚+终点锚两帧: " + seq);
        assertEquals(117f, locFirstX, 0.01f, "首帧 0x1F=出招原地（非终点）");
        byte[] sync = firstMsg(frames, 337);
        assertTrue(sync != null, "受理必发 337: " + seq);
        List<Float> vals = floatValues(sync);
        assertEquals(117f, vals.get(0), 0.01f, "337 x=出招原地（非终点）");
        assertEquals(100f, vals.get(2), 0.01f, "337 z=出招原地（非终点）");

        // 8m（TSP2=4 < d ≤ 15）：暂存疾跑（msg25+c61 档2，进射程转冲刺见 msg15 用例）
        Harness mid = new Harness();
        assertTrue(mid.fight.selectTarget(mid.world, 2, 1), "combat NPC selectable");
        mid.cast("CS_jh_yqq02", 112f, 100f);
        List<byte[]> stagedBand = mid.drain();
        String midSeq = describe(stagedBand);
        assertTrue(midSeq.contains("m25"), "暂存带发 skill_pause: " + midSeq);
        assertTrue(hasC61Dash(stagedBand), "暂存带发 c61 疾跑（档2）: " + midSeq);
        assertTrue(mid.player.rushInFlight(), "暂存标记 rush in flight");

        // 20m（> 15m 暂存带）：静默拒（零帧）
        Harness far = new Harness();
        assertTrue(far.fight.selectTarget(far.world, 2, 1), "combat NPC selectable");
        far.cast("CS_jh_yqq02", 100f, 100f);
        assertNull(far.channel.readOutbound(), "超 15m 暂存带静默拒（零帧）");
    }

    /** 参考服 exe 复刻（默认开；显式 setProperty 保持自包含）：暂存 + 落地 212 续招。 */
    @Test
    void rushStageAndLandingResume() {
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(PROD_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "生产 share/skill 资源不可用，跳过冲锋链");
        System.setProperty("nineyin.rush.stage", "true");
        try {
            rushStageAndLandingResumeBody();
        } finally {
            System.clearProperty("nineyin.rush.stage");
        }
    }

    /**
     * msg15 到位自动续招（2026-11-05 用户需求）：客户端疾跑到位不必然发 212（61653
     * 实证玩家到过目标 1m 内仍未发）——服务端在移动上报探测，进入射程即主动落地
     * 出招（337+msg26+重组 211 → m20 攻击帧簇）。
     */
    @Test
    void msg15ArrivalTriggersRushResumeWithout212() {
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(PROD_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "生产 share/skill 资源不可用，跳过冲锋链");
        System.setProperty("nineyin.rush.stage", "true");
        try {
            msg15ArrivalBody();
        } finally {
            System.clearProperty("nineyin.rush.stage");
        }
    }

    private void msg15ArrivalBody() {
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill yqq02 = CombatSkillCatalog.load().definition("CS_jh_yqq02", 1);
        Assumptions.assumeTrue(yqq02 != null && yqq02.canRush() && yqq02.rushRange() > 0,
                "CS_jh_yqq02 不满足冲锋前提（CanRush/RushRange）");
        Harness harness = new Harness();
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");

        // 8m（暂存带）按下 → 暂存疾跑
        harness.cast("CS_jh_yqq02", 112f, 100f);
        List<byte[]> staged = harness.drain();
        assertTrue(describe(staged).contains("m25"), "暂存必发 skill_pause: " + describe(staged));
        assertTrue(harness.player.rushInFlight(), "rush in flight");

        // msg15 上报 6m（> TSP2=4）：仍在疾跑带，不转冲刺不释放
        SkillCastHandler.tryRushArrival(harness.context(), 114f, 0f, 100f, 1.0f);
        List<byte[]> far1 = harness.drain();
        assertFalse(describe(far1).contains("m19"), "距 6m（>TSP2）不释放");
        assertFalse(harness.player.rushDashIssued(), "未进射程不转冲刺");

        // msg15 上报 3.5m（≤ TSP2=4，> 到达半径 3m）→ 转 c61 冲刺（档1+LockGRushAction）
        SkillCastHandler.tryRushArrival(harness.context(), 116.5f, 0f, 100f, 1.0f);
        List<byte[]> dash = harness.drain();
        String dashSeq = describe(dash);
        assertTrue(hasC61Dash(dash), "进射程转 c61 冲刺指令: " + dashSeq);
        assertTrue(harness.player.rushDashIssued(), "冲刺转换标记");
        assertFalse(dashSeq.contains("m19"), "冲刺途中未到面前不释放");
        assertTrue(harness.player.rushInFlight(), "暂存保持（未到面前）");

        // msg15 上报 2m（≤ 到达半径 3m=目标面前）→ 落地释放（337+msg26+msg19）
        SkillCastHandler.tryRushArrival(harness.context(), 118f, 0f, 100f, 1.0f);
        List<byte[]> arrived = harness.drain();
        String seq = describe(arrived);
        assertTrue(seq.contains("m337"), "到位回显 337: " + seq);
        assertTrue(seq.contains("m26"), "到位解除 skill_pause msg26: " + seq);
        assertTrue(seq.contains("m19"), "到位释放 msg19 锁定帧（攻击目标帧簇）: " + seq);
        assertFalse(harness.player.rushInFlight(), "到位清冲锋标记");
    }

    /**
     * 冲锋三段式（九轮）：myjf02（skilllock+CanRush=1，TSP2=6）——5m（射程内）直接
     * 受理 msg19+c61 冲刺；8m（暂存带）暂存疾跑；17m（>15m）静默拒。
     */
    @Test
    void myjf02ThreeBandRangeModel() {
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(PROD_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "生产 share/skill 资源不可用，跳过冲锋链");
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill myjf02 = CombatSkillCatalog.load().definition("CS_jh_myjf02", 1);
        Assumptions.assumeTrue(myjf02 != null && myjf02.canRush() && myjf02.rushRange() == 0,
                "CS_jh_myjf02 不满足前提（CanRush 且无 RushRange，回落 15m 受理）");
        CombatSkillCatalog.SkillRowData row = CombatSkillCatalog.load().skillRowData("CS_jh_myjf02", 1);
        Assumptions.assumeTrue(row != null && row.targetShapePara2() == 6.0f,
                "myjf02 TargetShapePara2 应为 6（varprop 215381→targetshape 7）");

        // 场景 A：目标 5m（玩家 100,100 → NPC 105,100，TSP2=6 内）→ 直接受理
        // msg19+c61 冲刺。myjf02 UseLimit=2 → 单手剑 102：测试角色配武器过武器闸
        Harness near = new Harness();
        near.player.noteEquippedWeaponItemType(102);
        near.world.registerNpc(3, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 105f, 0f, 100f));
        near.learned.put("CS_jh_myjf02", 1);
        near.skillRows.put("CS_jh_myjf02", 1);
        assertTrue(near.fight.selectTarget(near.world, 3, 1), "combat NPC selectable");
        near.cast("CS_jh_myjf02", 100f, 100f);
        List<byte[]> direct = near.drain();
        String directSeq = describe(direct);
        assertTrue(directSeq.contains("m19"), "TSP2 内直接受理出招（msg19）: " + directSeq);
        assertFalse(directSeq.contains("m25"), "射程内无 skill_pause: " + directSeq);
        assertFalse(near.player.rushInFlight(), "射程内不暂存");

        // 场景 B：目标 8m（TSP2=6 < d ≤ 15）——九轮暂存带：暂存疾跑（msg25+c61 档2）
        Harness far = new Harness();
        far.player.noteEquippedWeaponItemType(102);
        far.world.registerNpc(4, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 108f, 0f, 100f));
        far.learned.put("CS_jh_myjf02", 1);
        far.skillRows.put("CS_jh_myjf02", 1);
        assertTrue(far.fight.selectTarget(far.world, 4, 1), "combat NPC selectable");
        far.cast("CS_jh_myjf02", 100f, 100f);
        List<byte[]> stagedBand = far.drain();
        String bandSeq = describe(stagedBand);
        assertTrue(bandSeq.contains("m25"), "暂存带发 skill_pause: " + bandSeq);
        assertTrue(far.player.rushInFlight(), "暂存标记 rush in flight");

        // 场景 C：目标 17m（> 15m 暂存带）——静默拒（零帧）
        Harness beyond = new Harness();
        beyond.player.noteEquippedWeaponItemType(102);
        beyond.world.registerNpc(5, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 117f, 0f, 100f));
        beyond.learned.put("CS_jh_myjf02", 1);
        beyond.skillRows.put("CS_jh_myjf02", 1);
        assertTrue(beyond.fight.selectTarget(beyond.world, 5, 1), "combat NPC selectable");
        beyond.cast("CS_jh_myjf02", 100f, 100f);
        assertNull(beyond.channel.readOutbound(), "超 15m 暂存带静默拒（零帧）");
    }

    private void rushStageAndLandingResumeBody() {
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill yqq02 = CombatSkillCatalog.load().definition("CS_jh_yqq02", 1);
        Assumptions.assumeTrue(yqq02 != null && yqq02.canRush() && yqq02.rushRange() > 0,
                "CS_jh_yqq02 不满足冲锋前提（CanRush/RushRange）");
        Harness harness = new Harness();
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");

        // 8m（TSP2=4 < d ≤ 15 暂存带）按下 yqq02：暂存而非出招
        harness.cast("CS_jh_yqq02", 112f, 100f);
        List<byte[]> staged = harness.drain();
        String stageSeq = describe(staged);
        assertTrue(stageSeq.contains("m25"), "暂存必发 skill_pause msg25: " + stageSeq);
        assertTrue(stageSeq.contains("m24"), "暂存必发冲锋位移 msg24 c1=61: " + stageSeq);
        assertTrue(stageSeq.contains("0x10(n=1)"), "暂存必发单属性 CurSkillID 帧: " + stageSeq);
        assertFalse(stageSeq.contains("+rec109"), "暂存不发 CD 行: " + stageSeq);
        assertFalse(stageSeq.contains("m20"), "暂存不发技能动作: " + stageSeq);
        assertTrue(harness.player.rushInFlight(), "rush in flight 标记");

        // 落地（冲到 NPC 旁）→ 续招：CD 行 + 面板 + 337 + msg20（带 aim）
        harness.land(118f, 100f);
        List<byte[]> resumed = harness.drain();
        String resumeSeq = describe(resumed);
        assertTrue(resumeSeq.contains("m337"), "落地回显 337: " + resumeSeq);
        assertTrue(resumeSeq.contains("m26"), "落地解除 skill_pause msg26: " + resumeSeq);
        assertTrue(resumeSeq.contains("+rec109"), "续招发 CD 行: " + resumeSeq);
        assertTrue(resumeSeq.contains("m19"), "续招发 msg19 锁定帧: " + resumeSeq);
        assertFalse(harness.player.rushInFlight(), "落地清冲锋标记");
        // 落地链含两帧 337：handleDashLanding 回显落地坐标 + 续招出招再发（=续招原地，
        // 即 211 重组上报的落地坐标——官服语义 337 恒原地不携终点）
        List<byte[]> syncs = resumed.stream()
                .filter(f -> f[0] == 0x1E && f.length > 4 && f[3] == 2 && int32(f, 4) == 337)
                .toList();
        assertEquals(2, syncs.size(), "落地回显 + 续招 337 两帧: " + resumeSeq);
        List<Float> landingEcho = floatValues(syncs.get(0));
        assertEquals(118f, landingEcho.get(0), 0.01f, "首帧 337=落地回显 x");
        List<Float> castOrigin = floatValues(syncs.get(1));
        assertEquals(118f, castOrigin.get(0), 0.01f, "续招 337 x=续招原地（211 上报）");
        assertEquals(100f, castOrigin.get(2), 0.01f, "续招 337 z=续招原地（211 上报）");

        // 30s 窗口过期后落地：作废（无 msg26 无续招）
        harness.clock.advanceMillis(SkillCastHandler.RUSH_PENDING_WINDOW_MS + 1000);
        harness.cast("CS_jh_yqq02", 112f, 100f); // 重新暂存（个人 CD 6s 已过：时钟推进 31s）
        harness.drain();
        harness.clock.advanceMillis(SkillCastHandler.RUSH_PENDING_WINDOW_MS + 1000);
        harness.land(118f, 100f);
        List<byte[]> expired = harness.drain();
        String expiredSeq = describe(expired);
        assertTrue(expiredSeq.contains("m337"), "过期落地仍回显 337");
        assertFalse(expiredSeq.contains("m26"), "过期落地不发 msg26: " + expiredSeq);
        assertFalse(expiredSeq.contains("+rec109"), "过期落地不续招: " + expiredSeq);
    }
}
