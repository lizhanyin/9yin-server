package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.npc.NpcResolver;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * msg211 出招全链（EmbeddedChannel 直驱 {@link SkillCastHandler}，不走过登录协议）：
 * 帧序/CD 行内容/GCD 静默拒绝/击杀 Dead/重生/buff 帧（msg169+BufferInfoN）/收尾 0x12+stand
 * 全部对齐 docs/combat-skill-211.md 的 62433 抓包序列。
 */
public class SkillCastHandlerFlowTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    /** 可推进的固定时钟（技能 CD/租约判定全走它）。 */
    static final class SettableClock extends Clock {

        private long epochMillis = 1_793_532_000_000L;

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

    /** 任务只记录（0x12/stand、buff 到期、重生由测试手动触发）。 */
    static final class RecordingScheduler extends LoginFlowTestSchedulerAdapter {
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

    /** 占位基类：只实现 schedule（其余抛错），避免复刻整个接口样板（跨测试包复用）。 */
    public static class LoginFlowTestSchedulerAdapter implements ScheduledExecutorService {

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> callable,
                                               long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                      long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                          long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                               long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
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
        final Map<String, Integer> learned;
        final Map<String, Integer> skillRows;

        Harness(int npcMaxHp) {
            world.begin("ini\\scene\\scene01", "scene01");
            world.registerNpc(2, combatNpc("monv030101", npcMaxHp, 100, 20, 100));
            learned = new LinkedHashMap<>();
            learned.put("CS_jh_sfgp01", 1);
            learned.put("CS_jh_sfgp04", 1);
            learned.put("CS_yhwq_hsqs04", 1);
            skillRows = new LinkedHashMap<>();
            skillRows.put("CS_jh_sfgp01", 1);
            skillRows.put("CS_jh_sfgp04", 2);
            skillRows.put("CS_yhwq_hsqs04", 3);
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

        void cast(String skillId) {
            SkillCastHandler.handle(context(), useSkillMessage(skillId, 100f, 20f, 100f, 1.0f));
        }
    }

    @BeforeAll
    static void needsSharedSkillResources() {
        Assumptions.assumeTrue(java.nio.file.Files.isDirectory(
                GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过出招全链");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    @Test
    void acceptedCastEmitsCaptureFrameSequence() {
        Harness harness = new Harness(1000);
        assertTrue(harness.fight.selectTarget(harness.world, 2, 1), "combat NPC selectable");

        harness.cast("CS_jh_sfgp01");
        List<byte[]> frames = harness.drain();
        List<String> sequence = describe(frames);
        // 抓包序列：CD 行 ×3 → CanUse 行帧(置0) → 玩家全量 0x10 → 337 → 20 →
        // 命中才进的 LogicState 帧 → 每目标 [0x10, 0x13(21B), 24, 0x2D, 18]
        // →（有 buff 时 169+BufferInfo）→ 205。sfgp01 带自 buff（27299）。
        assertEquals(List.of("0x11:rec5", "0x11:rec6", "0x11:rec5", "0x10:1",
                "0x10:vital", "custom:337", "custom:20", "0x10:1",
                "0x10:5", "0x13:21", "custom:24", "0x2D",
                "custom:18", "custom:169", "0x10:1", "custom:205"), sequence,
                "capture frame order");

        // 个人 CD 行：cat 8837 / 8000ms / team 0xFFFF（ini+抓包映射表）
        byte[] personal = frames.get(0);
        assertEquals(38, personal.length, "cooldown row 38B (u16 team)");
        assertEquals(8837, s32(personal, 16));
        assertEquals(8000, s64(personal, 28) - s64(personal, 20));
        assertEquals(0xFFFF, u16(personal, 36));

        // GCD 行：cat 50000 / 7000ms / team 50369
        byte[] gcd = frames.get(2);
        assertEquals(50000, s32(gcd, 16));
        assertEquals(7000, s64(gcd, 28) - s64(gcd, 20));
        assertEquals(50369, u16(gcd, 36));

        // CanUse=0 行帧：isView=1, view=40, owner=行号, idx2236, 值 0
        byte[] canUse = frames.get(3);
        assertEquals(1, canUse[1], "isView");
        assertEquals(40, u32(canUse, 2), "view id");
        assertEquals(1, u32(canUse, 6), "row 1 (sfgp01)");
        assertEquals(15, canUse.length, "15B single byte prop");
        assertEquals(2236, u16(canUse, 12), "CanUse index (capture 2236)");
        assertEquals(0, canUse[14], "disabled during cooldown");

        // active_skill_rec：技能串
        byte[] active = frames.get(1);
        assertEquals(RecordMessagesRow.ACTIVE_SKILL, u16(active, 10));
        assertEquals("CS_jh_sfgp01", text(active, 16));

        // NPC 掉血（1000 → <1000）且未死
        FightState.SelectedTarget target = harness.fight.livingSelectedTarget(harness.world);
        assertTrue(target == null || target.combat().state().hp() < 1000,
                "npc damaged or dead by formula floor");

        // 玩家 vital 帧携带当前技能；出招不进战斗（62433：首招 vital LogicState 仍 0）
        Map<String, Object> vital = properties(frames.get(4));
        assertEquals("CS_jh_sfgp01", vital.get("CurSkillID"));
        assertEquals(0, vital.get("LogicState"), "cast alone must not enter combat");
        assertEquals(100, vital.get("MaxSP"), "MaxSP sent with vital (display cap)");
        // 命中后单帧 LogicState=1
        Map<String, Object> combat = properties(frames.get(7));
        assertEquals(1, combat.get("LogicState"), "hit enters combat");

        // 调度任务跑完：CanUse 回 1 + 战斗闲时退出回 0
        harness.scheduler.runAll();
        List<byte[]> tail = harness.drain();
        assertTrue(tail.stream().anyMatch(f -> (f[0] & 0xFF) == 0x10 && f[1] == 1
                        && u32(f, 2) == 40 && f[14] == 1),
                "CanUse restored to 1 after cooldown");
        assertTrue(harness.player.fighting() == false
                        || tail.stream().anyMatch(f -> (f[0] & 0xFF) == 0x10 && f[1] == 0),
                "combat exit frame scheduled (or already exited)");
    }

    @Test
    void gcdRejectsSilentlyAndRecoversAfterWindow() {
        Harness harness = new Harness(1000);
        harness.fight.selectTarget(harness.world, 2, 1);
        harness.cast("CS_jh_sfgp01");
        assertFalse(harness.drain().isEmpty(), "first cast emits frames");

        harness.cast("CS_jh_sfgp01");
        assertTrue(harness.drain().isEmpty(), "second cast inside GCD is silent (capture: no reply)");

        harness.clock.advanceMillis(8100); // 过个人 CD 8s 与 GCD 7s
        harness.cast("CS_jh_sfgp01");
        assertFalse(harness.drain().isEmpty(), "cast recovers after cooldown window");
    }

    @Test
    void killMarksDeadAndRespawnsFull() {
        Harness harness = new Harness(5); // 公式保底 1 点：5 击必杀（90% 命中，种子固定）
        harness.fight.selectTarget(harness.world, 2, 1);
        boolean killed = false;
        byte[] deadFrame = null;
        for (int cast = 0; cast < 30 && !killed; cast++) {
            harness.cast("CS_jh_sfgp01");
            for (byte[] frame : harness.drain()) {
                if ((frame[0] & 0xFF) == 0x10 && u32(frame, 2) == 2) {
                    Map<String, Object> props = properties(frame);
                    if (Integer.valueOf(1).equals(props.get("Dead"))) {
                        killed = true;
                        deadFrame = frame;
                    }
                }
            }
            harness.clock.advanceMillis(8100);
        }
        assertTrue(killed, "npc dies within 30 casts");
        assertEquals(0, properties(deadFrame).get("HP"), "dead frame HP=0");
        assertEquals(120, properties(deadFrame).get("LogicState"), "LogicState=Died(120)");
        // 死亡后不再结算：无 NPC 属性帧/伤害飘字/受击特效（非指向技能仍可空放出自 buff 帧）
        harness.cast("CS_jh_sfgp01");
        List<String> afterDeath = describe(harness.drain());
        assertFalse(afterDeath.contains("0x10:5"), "no NPC frame on dead target");
        assertFalse(afterDeath.contains("custom:24"), "no damage float on dead target");
        assertFalse(afterDeath.contains("0x2D"), "no hit effect on dead target");

        // 重生任务：时钟过 15s 后回满血（0x10 帧，无 0x0E 删除）
        harness.clock.advanceMillis(16_000);
        harness.scheduler.runAll();
        boolean respawnedFull = false;
        for (byte[] frame : harness.drain()) {
            if ((frame[0] & 0xFF) == 0x10 && u32(frame, 2) == 2) {
                Map<String, Object> props = properties(frame);
                if (Integer.valueOf(5).equals(props.get("HP"))
                        && Integer.valueOf(0).equals(props.get("Dead"))) {
                    respawnedFull = true;
                }
            }
        }
        assertTrue(respawnedFull, "respawn restores full HP via 0x10");
    }

    @Test
    void selfBuffSkillEmitsMsg169AndBufferInfo() {
        Harness harness = new Harness(1000);
        harness.fight.selectTarget(harness.world, 2, 1);
        harness.cast("CS_yhwq_hsqs04");
        List<byte[]> frames = harness.drain();
        boolean sawMsg169 = false;
        boolean sawBufferInfo = false;
        for (byte[] frame : frames) {
            if ((frame[0] & 0xFF) == 0x1E && customMessageId(frame) == 169) {
                // [169, 0, "buf_CS_yhwq_hsqs04", obj, obj, 宽名, 宽名, 0, 0]
                sawMsg169 = true;
            }
            if ((frame[0] & 0xFF) == 0x10 && u32(frame, 2) == 1) {
                Map<String, Object> props = properties(frame);
                if (props.containsKey("BufferInfo5")) {
                    sawBufferInfo = true;
                }
            }
        }
        assertTrue(sawMsg169, "msg169 buff frame present");
        assertTrue(sawBufferInfo, "BufferInfo5 slot frame present");
    }

    @Test
    void endOfActionRemovesActiveSkillRowAndRestoresStand() {
        Harness harness = new Harness(1000);
        harness.fight.selectTarget(harness.world, 2, 1);
        harness.cast("CS_jh_sfgp01");
        harness.drain();

        harness.scheduler.runAll();
        List<byte[]> tail = harness.drain();
        List<String> described = describe(tail);
        assertTrue(described.contains("0x12:rec" + RecordMessagesRow.ACTIVE_SKILL),
                "active_skill_rec row removed, got " + described);
        // State=stand 复位帧已停发（2026-11-02 真机反馈：空中出招被强制贴地）——
        // 收尾只清 active_skill_rec 行，不得再出现携带 State 属性的 0x10 帧。
        for (byte[] frame : tail) {
            if ((frame[0] & 0xFF) == 0x10) {
                Map<String, Object> props = properties(frame);
                assertFalse(props.containsKey("State"),
                        "State frame must not be sent on cast end, got " + described);
            }
        }
        assertTrue(harness.player.currentSkillId().isEmpty(), "current skill cleared");
    }

    // ------------------------------------------------------------------ helpers

    /** 战斗 NPC 摆放（scriptClass=attacknpc，MaxHP/HP/Resource 最小属性面）。 */
    static NpcSpawn combatNpc(String model, int maxHp, float x, float y, float z) {
        NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                "test-mon-1", model, "attacknpc", null);
        resolved.properties.put("MaxHP", PropertyValues.Value.ofInt32(maxHp));
        resolved.properties.put("HP", PropertyValues.Value.ofInt32(maxHp));
        resolved.properties.put("Resource", PropertyValues.Value.ofString(model));
        return new NpcSpawn(resolved, x, y, z, 0f);
    }

    static final class RecordMessagesRow {

        static final int ACTIVE_SKILL = 6;
    }

    /** 构造 0x1E 直达 USE_SKILL 消息（同客户端 [211, 技能, x, y, z, 朝向]）。 */
    static CustomC2S.Message useSkillMessage(String skillId, float x, float y, float z, float orient) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(6);
        writer.writeU8(2).writeU32(211);
        byte[] id = skillId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(id.length + 1).writeBytes(id).writeU8(0);
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(x));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(y));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(z));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(orient));
        return CustomC2S.parseDirect(writer.toBytes());
    }

    /** 帧序列摘要：记录行 → 0x11/0x12:recN、玩家对象 0x10 → 0x10:vital（数量随属性面）、custom → custom:msgId。 */
    static List<String> describe(List<byte[]> frames) {
        List<String> out = new ArrayList<>(frames.size());
        for (byte[] frame : frames) {
            switch (frame[0] & 0xFF) {
                case 0x11, 0x12 -> out.add(String.format("0x%02X:rec%d", frame[0] & 0xFF, u16(frame, 10)));
                case 0x10 -> out.add(u32(frame, 2) == 1 && u16(frame, 10) > 50
                        ? "0x10:vital" : "0x10:" + u16(frame, 10));
                case 0x1E, 0x27 -> out.add("custom:" + customMessageId(frame));
                case 0x13 -> out.add("0x13:" + frame.length);
                default -> out.add(String.format("0x%02X", frame[0] & 0xFF));
            }
        }
        return out;
    }

    static int customMessageId(byte[] frame) {
        // 0x1E | u16 count | tag u8 | i32
        assertEquals(2, frame[3] & 0xFF, "first custom value is int32");
        return s32(frame, 4);
    }

    /** 解析 0x10 属性帧（index→官方表线型）。 */
    static Map<String, Object> properties(byte[] frame) {
        Map<String, Object> out = new LinkedHashMap<>();
        int count = u16(frame, 10);
        int offset = 12;
        for (int i = 0; i < count; i++) {
            int index = u16(frame, offset);
            offset += 2;
            PropertyTable.Entry entry = PropertyTable.byIndex(index);
            if (entry == null) {
                break;
            }
            Object value;
            switch (PropertyTable.wireTypeOf(entry.typeCode())) {
                case BYTE -> {
                    value = frame[offset] & 0xFF;
                    offset += 1;
                }
                case WORD -> {
                    value = u16(frame, offset);
                    offset += 2;
                }
                case INT32 -> {
                    value = s32(frame, offset);
                    offset += 4;
                }
                case INT64 -> {
                    value = s64(frame, offset);
                    offset += 8;
                }
                case FLOAT32 -> {
                    value = Float.intBitsToFloat(s32(frame, offset));
                    offset += 4;
                }
                case STRING -> {
                    int len = s32(frame, offset);
                    offset += 4;
                    value = new String(frame, offset, Math.max(len - 1, 0),
                            java.nio.charset.StandardCharsets.UTF_8);
                    offset += len;
                }
                case WIDE_STRING -> {
                    int len = s32(frame, offset);
                    offset += 4;
                    value = new String(frame, offset, Math.max(len - 2, 0),
                            java.nio.charset.StandardCharsets.UTF_16LE);
                    offset += len;
                }
                case OBJECT -> {
                    value = s64(frame, offset);
                    offset += 8;
                }
                default -> throw new IllegalStateException("wire type of " + entry.name());
            }
            out.put(entry.name(), value);
        }
        return out;
    }

    static String text(byte[] frame, int offset) {
        int len = s32(frame, offset);
        return new String(frame, offset + 4, len - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    static int u16(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
    }

    static int u32(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8
                | (frame[off + 2] & 0xFF) << 16 | (frame[off + 3] & 0xFF) << 24;
    }

    static int s32(byte[] frame, int off) {
        return u32(frame, off);
    }

    static long s64(byte[] frame, int off) {
        return (u32(frame, off) & 0xFFFFFFFFL) | (u32(frame, off + 4) & 0xFFFFFFFFL) << 32;
    }
}
