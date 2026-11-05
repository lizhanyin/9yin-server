package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 冲锋链两态（2026-11-05 用户拍板对齐参考服 exe；61653 抓包钉死链路）：
 * 默认（参考服 exe 语义）：超射程冲锋（skilllock+CanRush，判定值=CanRush&&RushRange>0
 * ? RushRange : TargetShapePara2——myjf02 无 RushRange 用 TSP2=6，目标 6.73m 触发）
 * → msg25[30000,skill_pause]+msg24 c1=61 疾跑去目标，落地 212 → 337 回显+msg26+
 * 重组 211 续招。
 * -Dnineyin.rush.stage=false（官服语义关回）：超距冲锋直接受理——337 终点驱动
 * 客户端本地冲，无 msg25/msg24/212（op2 实证：两次不同出招点 337 终点同收敛）。
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
     * 官服语义（-Dnineyin.rush.stage=false 显式关回，staged 默认开）：超距冲锋直接受理；
     * 0x1F+337 双帧恒=上报原地，位移纯客户端。
     */
    @Test
    void rushOutOfRangeAcceptedAnchorsOriginNotDestination() {
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(PROD_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "生产 share/skill 资源不可用，跳过冲锋链");
        System.setProperty("nineyin.rush.stage", "false");
        try {
            rushOutOfRangeAcceptedBody();
        } finally {
            System.clearProperty("nineyin.rush.stage");
        }
    }

    private void rushOutOfRangeAcceptedBody() {
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill yqq02 = CombatSkillCatalog.load().definition("CS_jh_yqq02", 1);
        Assumptions.assumeTrue(yqq02 != null && yqq02.canRush() && yqq02.rushRange() > 0,
                "CS_jh_yqq02 不满足冲锋前提（CanRush/RushRange）");
        Harness harness = new Harness();
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");

        // 20 米外按下 yqq02（RushRange=15）：官服行为=直接受理（无射程闸）
        harness.cast("CS_jh_yqq02", 100f, 100f);
        List<byte[]> frames = harness.drain();
        String seq = describe(frames);
        assertTrue(seq.contains("+rec109"), "超距冲锋受理仍发 CD 行: " + seq);
        assertTrue(seq.contains("m20"), "超距冲锋受理仍发技能动作: " + seq);
        assertFalse(seq.contains("m25"), "官服冲锋无 skill_pause: " + seq);
        assertFalse(hasC61Dash(frames), "官服冲锋无 c1=61 疾跑帧（m24 伤害飘字不在此列）: " + seq);
        assertFalse(harness.player.rushInFlight(), "无暂存即无冲锋标记");
        // 0x1F 原点锚定 + 337 原地回显对帧（op2 字节级实证：两者 xyz 恒=211 上报原地，
        // 337 携终点会被客户端当权威位置贴坐标=瞬移无动画，11-05 真机两轮反馈）
        int locIdx = -1;
        int syncIdx = -1;
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (f[0] == 0x1F && locIdx < 0) {
                locIdx = i;
            } else if (f[0] == 0x1E && f.length > 4 && f[3] == 2 && int32(f, 4) == 337 && syncIdx < 0) {
                syncIdx = i;
            }
        }
        assertTrue(locIdx >= 0, "冲锋必发 0x1F 原点锚定: " + seq);
        assertTrue(syncIdx >= 0, "受理必发 337: " + seq);
        assertTrue(locIdx < syncIdx, "0x1F 锚点须在 337 之前: " + seq);
        List<Float> anchor = locationFloats(frames.get(locIdx));
        assertEquals(100f, anchor.get(0), 0.01f, "0x1F x=出招原地");
        assertEquals(0f, anchor.get(1), 0.01f, "0x1F y=出招原地");
        assertEquals(100f, anchor.get(2), 0.01f, "0x1F z=出招原地");
        assertEquals(1.0f, anchor.get(3), 0.01f, "0x1F orient=211 朝向");
        byte[] sync = firstMsg(frames, 337);
        assertTrue(sync != null, "受理必发 337: " + seq);
        List<Float> vals = floatValues(sync);
        assertEquals(100f, vals.get(0), 0.01f, "337 x=出招原地（非终点）");
        assertEquals(0f, vals.get(1), 0.01f, "337 y=出招原地");
        assertEquals(100f, vals.get(2), 0.01f, "337 z=出招原地（非终点）");
        assertEquals(1.0f, vals.get(3), 0.01f, "337 orient=211 朝向");
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
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill yqq02 = CombatSkillCatalog.load().definition("CS_jh_yqq02", 1);
        Assumptions.assumeTrue(yqq02 != null && yqq02.canRush() && yqq02.rushRange() > 0,
                "CS_jh_yqq02 不满足冲锋前提（CanRush/RushRange）");
        Harness harness = new Harness();
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");

        // 20m 外按下（RushRange=15）→ 暂存
        harness.cast("CS_jh_yqq02", 100f, 100f);
        List<byte[]> staged = harness.drain();
        assertTrue(describe(staged).contains("m25"), "暂存必发 skill_pause: " + describe(staged));
        assertTrue(harness.player.rushInFlight(), "rush in flight");

        // 疾跑过程 msg15 逐帧上报：距 NPC 16m / 10m（虽已进入 RushRange=15 阈值）
        // → 仍不续招——「到达阈值就释放」已废，标记阶段只跑不结算
        SkillCastHandler.tryRushArrival(harness.context(), 104f, 0f, 100f, 1.0f);
        assertFalse(describe(harness.drain()).contains("m20"), "距 16m 不续招");
        SkillCastHandler.tryRushArrival(harness.context(), 110f, 0f, 100f, 1.0f);
        List<byte[]> mid = harness.drain();
        assertFalse(describe(mid).contains("m20"), "距 10m（阈值内）仍不续招——伤害不到目标身位不发: " + describe(mid));
        assertTrue(harness.player.rushInFlight(), "标记阶段持续（rush in flight）");

        // msg15 上报到达目标身位（距 NPC 4m ≤ 到达半径 5m）→ 服务端主动落地续招（无 212）
        SkillCastHandler.tryRushArrival(harness.context(), 116f, 0f, 100f, 1.0f);
        List<byte[]> arrived = harness.drain();
        String seq = describe(arrived);
        assertTrue(seq.contains("m337"), "到位回显 337: " + seq);
        assertTrue(seq.contains("m26"), "到位解除 skill_pause msg26: " + seq);
        assertTrue(seq.contains("m20"), "到位续招发攻击动作 msg20（攻击目标帧簇）: " + seq);
        assertFalse(harness.player.rushInFlight(), "到位清冲锋标记");
    }

    /**
     * 真实射程闸（61653 抓包场景复刻）：myjf02（skilllock+CanRush=1 无 RushRange，
     * TSP2=6）目标 6.7m ——超 6m 触发 msg25[30000,skill_pause]+msg24 c1=61（int 前
     * 串后的值序）；目标 5m（TSP2 内）直接受理出招。
     */
    @Test
    void myjf02OutOfRangeStagesByTargetShapePara2() {
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(PROD_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "生产 share/skill 资源不可用，跳过冲锋链");
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill myjf02 = CombatSkillCatalog.load().definition("CS_jh_myjf02", 1);
        Assumptions.assumeTrue(myjf02 != null && myjf02.canRush() && myjf02.rushRange() == 0,
                "CS_jh_myjf02 不满足前提（CanRush 且无 RushRange，射程走 TSP2）");
        CombatSkillCatalog.SkillRowData row = CombatSkillCatalog.load().skillRowData("CS_jh_myjf02", 1);
        Assumptions.assumeTrue(row != null && row.targetShapePara2() == 6.0f,
                "myjf02 TargetShapePara2 应为 6（varprop 215381→targetshape 7）");

        // 场景 A：目标 6.7m（玩家 100,100 → NPC 106.7,100）——超 TSP2=6 触发暂存
        // myjf02 UseLimit=2 → 单手剑 102：测试角色配武器过武器闸
        Harness far = new Harness();
        far.player.noteEquippedWeaponItemType(102);
        far.world.registerNpc(3, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 106.7f, 0f, 100f));
        far.learned.put("CS_jh_myjf02", 1);
        far.skillRows.put("CS_jh_myjf02", 1);
        assertTrue(far.fight.selectTarget(far.world, 3, 1), "combat NPC selectable");
        far.cast("CS_jh_myjf02", 100f, 100f);
        List<byte[]> staged = far.drain();
        String seq = describe(staged);
        assertTrue(seq.contains("m25"), "超 TSP2 触发 skill_pause msg25: " + seq);
        assertTrue(hasC61Dash(staged), "超 TSP2 触发 msg24 c1=61 疾跑帧: " + seq);
        assertTrue(far.player.rushInFlight(), "暂存标记 rush in flight");
        // msg25 值序 = [25, 30000, 'skill_pause']（int 前、串后；61653 实测）
        byte[] pause = firstMsg(staged, 25);
        assertTrue(pause != null && (pause[8] & 0xFF) == 2, "msg25 第 1 值应为 i32=30000");
        assertEquals(30000, int32(pause, 9), "msg25 窗口 30000ms");

        // 场景 B：目标 5m（TSP2=6 内）——直接受理（m20 出招，无 m25）
        Harness near = new Harness();
        near.player.noteEquippedWeaponItemType(102);
        near.world.registerNpc(4, SkillCastHandlerFlowTest.combatNpc("monv030101", 1000, 105f, 0f, 100f));
        near.learned.put("CS_jh_myjf02", 1);
        near.skillRows.put("CS_jh_myjf02", 1);
        assertTrue(near.fight.selectTarget(near.world, 4, 1), "combat NPC selectable");
        near.cast("CS_jh_myjf02", 100f, 100f);
        List<byte[]> direct = near.drain();
        String directSeq = describe(direct);
        assertTrue(directSeq.contains("m20"), "TSP2 内直接受理出招: " + directSeq);
        assertFalse(directSeq.contains("m25"), "TSP2 内无 skill_pause: " + directSeq);
    }

    private void rushStageAndLandingResumeBody() {
        com.nineyin.util.ServerPaths.overrideServerRoot(PROD_ROOT);
        CombatSkill yqq02 = CombatSkillCatalog.load().definition("CS_jh_yqq02", 1);
        Assumptions.assumeTrue(yqq02 != null && yqq02.canRush() && yqq02.rushRange() > 0,
                "CS_jh_yqq02 不满足冲锋前提（CanRush/RushRange）");
        Harness harness = new Harness();
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");

        // 20 米外按下 yqq02（RushRange=15）：暂存而非出招
        harness.cast("CS_jh_yqq02", 100f, 100f);
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
        assertTrue(resumeSeq.contains("m20"), "续招发技能动作: " + resumeSeq);
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
        harness.cast("CS_jh_yqq02", 100f, 100f); // 重新暂存（个人 CD 6s 已过：时钟推进 31s）
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
