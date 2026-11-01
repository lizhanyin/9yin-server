package com.nineyin.game.avatar.neigong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.game.avatar.PlayerActor;
import com.nineyin.protocol.CustomC2S;
import com.nineyin.protocol.CustomMessages;
import com.nineyin.protocol.PacketWriter;
import com.nineyin.transport.Connection;
import com.nineyin.transport.FrameCodec;
import com.nineyin.transport.XorKey;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 内修/演武（215/1006 家族 + 1s ticker）逐帧行为，移植 Go player_progress_test.go
 * 关键向量：191 字节针、升级序 14→23（绝不出 24）、陈旧 23 吞掉、选书静默、
 * 双 vital 的 FacultyName 置空刷新。
 */
class FacultyProgressTest {

    private static final Instant NOW = Instant.parse("2026-01-02T15:04:05Z");

    private static final class Env implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PlayerActor player;

        Env() {
            channel.pipeline().addLast(new com.nineyin.transport.FrameEncoder(new Connection(null)));
            player = PlayerActor.create("李逍遥", 99999, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
            PlayerProgress.Snapshot snapshot = player.facultySnapshot();
            // 固定推进基线：NOW 前一小时，便于分钟结算确定性。
            snapshot = new PlayerProgress.Snapshot(snapshot.curNeiGong(), snapshot.faculty(),
                    snapshot.facultyState(), snapshot.facultyStyle(), snapshot.facultyName(),
                    snapshot.fillSpeed(), NOW.minusSeconds(3600), snapshot.books());
            player.restoreFaculty(snapshot, NOW);
        }

        List<byte[]> drain() {
            List<byte[]> frames = new ArrayList<>();
            ByteBuf buffer;
            while ((buffer = channel.readOutbound()) != null) {
                byte[] wire = new byte[buffer.readableBytes()];
                buffer.readBytes(wire);
                buffer.release();
                frames.add(FrameCodec.decode(wire, XorKey.initial()));
            }
            return frames;
        }

        void handle(CustomC2S.Message message) {
            FacultyCustomHandler.handle(new Connection(channel), player, message);
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    /** 客户端 0x1E 直达帧构造（C2S 无 [1,764] 限制，勿用 S2C 编码器）。 */
    private static CustomC2S.Message direct(int messageId, CustomMessages.Value... values) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(values.length + 1);
        appendClientValue(writer, CustomMessages.Value.ofInt32(messageId));
        for (CustomMessages.Value value : values) {
            appendClientValue(writer, value);
        }
        CustomC2S.Message parsed = CustomC2S.parseDirect(writer.toBytes());
        org.junit.jupiter.api.Assertions.assertNotNull(parsed, "direct frame must parse");
        return parsed;
    }

    private static void appendClientValue(PacketWriter writer, CustomMessages.Value value) {
        if (value.isText()) {
            byte[] data = value.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        } else if (value.isFloat64()) {
            writer.writeU8(5).writeU64(Double.doubleToRawLongBits(value.float64()));
        } else {
            writer.writeU8(2).writeU32(value.int32() & 0xFFFFFFFFL);
        }
    }

    private static CustomC2S.Message envelope(int messageId, CustomMessages.Value... values) {
        PacketWriter full = PacketWriter.ofCapacity(64);
        full.writeU8(0x0A);
        for (int i = 0; i < 20; i++) {
            full.writeU8(0); // 头共 21 字节含 opcode，列表起于 21
        }
        full.writeU16(values.length + 1);
        appendClientValue(full, CustomMessages.Value.ofInt32(messageId));
        for (CustomMessages.Value value : values) {
            appendClientValue(full, value);
        }
        CustomC2S.Message parsed = CustomC2S.parseActivityEnvelope(full.toBytes());
        org.junit.jupiter.api.Assertions.assertNotNull(parsed, "envelope must parse");
        return parsed;
    }

    @Test
    void useNeiGongRepliesVitalsOnly() {
        try (Env env = new Env()) {
            env.handle(direct(215, CustomMessages.Value.ofString("ng_yh_001")));
            List<byte[]> frames = env.drain();
            assertEquals(1, frames.size());
            assertEquals(0x10, frames.get(0)[0] & 0xFF);
            assertEquals("ng_yh_001", env.player.progress().curNeiGong);
        }
        try (Env env = new Env()) {
            assertThrows(IllegalStateException.class,
                    () -> env.handle(direct(215, CustomMessages.Value.ofString("ng_none"))));
        }
    }

    @Test
    void convertReadySilentOnUnknownBook() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(1), CustomMessages.Value.ofString("ng_none")));
            assertTrue(env.drain().isEmpty(), "未知书静默零帧");
            assertEquals("ng_wd_001", env.player.progress().facultyName, "未知书不改变选书");
            // 已知书：0x18 书帧在前（面板先重读等级）再 0x10。
            env.handle(direct(1006, CustomMessages.Value.ofInt32(1), CustomMessages.Value.ofString("ng_yh_001")));
            List<byte[]> frames = env.drain();
            assertEquals(2, frames.size());
            assertEquals(0x18, frames.get(0)[0] & 0xFF);
            assertEquals(0x10, frames.get(1)[0] & 0xFF);
            assertEquals("ng_yh_001", env.player.progress().facultyName);
            assertEquals(PlayerProgress.FACULTY_STATE_NONE, env.player.progress().facultyState);
        }
    }

    @Test
    void actBeginSendsVitalsThenFacultyMessage22() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(21)));
            List<byte[]> frames = env.drain();
            assertEquals(2, frames.size());
            assertEquals(0x10, frames.get(0)[0] & 0xFF);
            byte[] msg = frames.get(1);
            assertEquals(0x1E, msg[0] & 0xFF);
            // Go 测试针：`02 BF 00 00 00 | 02 16 00 00 00 | 02 00 00 00 00`
            assertArrayEquals(new byte[]{2, unchecked(0xBF), 0, 0, 0}, java.util.Arrays.copyOfRange(msg, 3, 8));
            assertArrayEquals(new byte[]{2, 22, 0, 0, 0}, java.util.Arrays.copyOfRange(msg, 8, 13));
            assertArrayEquals(new byte[]{2, 0, 0, 0, 0}, java.util.Arrays.copyOfRange(msg, 13, 18));
            // 演武态：fillSpeed=0、LogicState 覆写 108。
            assertEquals(0, env.player.progress().fillSpeed);
            assertEquals(PlayerProgress.LOGIC_STATE_FACULTY,
                    env.player.effectiveLogicState(env.player.actorState()));
        }
    }

    @Test
    void normalBeginNeverEntersFacultyLogicState() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(11)));
            List<byte[]> frames = env.drain();
            assertEquals(1, frames.size());
            assertEquals(PlayerProgress.NORMAL_FACULTY_FILL_PER_MINUTE, env.player.progress().fillSpeed);
            assertEquals(0, env.player.actorState().logicState());
        }
    }

    @Test
    void staleActPayIsSwallowedWithPlayMessage() {
        try (Env env = new Env()) {
            // 未进演武直接发 1006/23（陈旧 UI 请求）：0x10 + 191/24，不断连。
            env.handle(envelope(1006, CustomMessages.Value.ofInt32(23),
                    CustomMessages.Value.ofFloat64(0), CustomMessages.Value.ofFloat64(1)));
            List<byte[]> frames = env.drain();
            assertEquals(2, frames.size());
            assertEquals(0x10, frames.get(0)[0] & 0xFF);
            assertEquals(0x1E, frames.get(1)[0] & 0xFF);
            assertEquals(24, frames.get(1)[9] & 0xFF);
        }
    }

    @Test
    void actPayLevelUpSends14Then23Never24() {
        try (Env env = new Env()) {
            // 选低总量书（total=700）：20000 卡奖励连升到满级 → levelUp+maxLevel。
            env.handle(direct(1006, CustomMessages.Value.ofInt32(1), CustomMessages.Value.ofString("ng_wd_002")));
            env.drain();
            env.handle(direct(1006, CustomMessages.Value.ofInt32(21)));
            env.drain();
            env.handle(direct(1006, CustomMessages.Value.ofInt32(23),
                    CustomMessages.Value.ofFloat64(1), CustomMessages.Value.ofFloat64(2)));
            List<byte[]> frames = env.drain();
            // [0x18 书帧, 0x10, 191/14, 191/23]，绝无 191/24。
            assertEquals(4, frames.size());
            assertEquals(0x18, frames.get(0)[0] & 0xFF);
            assertEquals(0x10, frames.get(1)[0] & 0xFF);
            assertEquals(14, frames.get(2)[9] & 0xFF);
            assertEquals(0, frames.get(2)[10] & 0xFF, "arg2=满级标记（20000 卡升到 13/36 未满）");
            assertEquals(23, frames.get(3)[9] & 0xFF);
        }
    }

    @Test
    void actPayWithoutLevelUpPlaysAgain() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(21)));
            env.drain();
            env.handle(direct(1006, CustomMessages.Value.ofInt32(23),
                    CustomMessages.Value.ofFloat64(0), CustomMessages.Value.ofFloat64(4)));
            List<byte[]> frames = env.drain();
            assertEquals(3, frames.size());
            assertEquals(0x18, frames.get(0)[0] & 0xFF);
            assertEquals(0x10, frames.get(1)[0] & 0xFF);
            assertEquals(24, frames.get(2)[9] & 0xFF, "未升级继续开卡");
        }
    }

    @Test
    void formRefreshClearsFacultyNameThenVitals() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(1), CustomMessages.Value.ofString("ng_yh_001")));
            env.drain();
            env.handle(direct(1006, CustomMessages.Value.ofInt32(24)));
            List<byte[]> frames = env.drain();
            assertEquals(2, frames.size());
            assertEquals(0x10, frames.get(0)[0] & 0xFF);
            assertEquals(0x10, frames.get(1)[0] & 0xFF);
            assertTrue(frames.get(0).length < frames.get(1).length, "首帧是单属性置空帧");
        }
    }

    @Test
    void facultyTickSettlesWholeMinutesAndStaysQuietOtherwise() {
        try (Env env = new Env()) {
            // /11 开始普通内修（fillSpeed=200）。
            env.handle(direct(1006, CustomMessages.Value.ofInt32(11)));
            env.drain();
            Connection connection = new Connection(env.channel);
            // 静默秒：零帧。
            LoginTickHarness.tickOnce(connection, env.player, NOW.plusSeconds(1));
            assertTrue(env.drain().isEmpty());
            // 满 60s：[0x18 书帧, 0x10]。
            LoginTickHarness.tickOnce(connection, env.player, NOW.plusSeconds(61));
            List<byte[]> frames = env.drain();
            assertEquals(2, frames.size());
            assertEquals(0x18, frames.get(0)[0] & 0xFF);
            assertEquals(0x10, frames.get(1)[0] & 0xFF);
            // 结算数学：restore 基线(-1h)已入账 12000，本分钟再 +200。
            PlayerProgress.Book book = env.player.progress().book(env.player.progress().facultyName);
            assertEquals(12200, book.fill);
        }
    }

    /** ticker 单步（绕过 LoginFlow 直接驱动同一逻辑）。 */
    private static final class LoginTickHarness {
        static void tickOnce(Connection connection, PlayerActor player, Instant now) {
            int[] tick = player.facultyTick(now);
            if (tick[1] == 0) {
                return;
            }
            if (tick[0] > 0) {
                connection.writeFrame(player.progress().bookFrame(tick[0]));
            }
            connection.writeFrame(player.vitalUpdate());
        }
    }

    @Test
    void exitResetsStateAndLogicState() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(21)));
            env.drain();
            env.handle(direct(1006, CustomMessages.Value.ofInt32(22)));
            List<byte[]> frames = env.drain();
            assertEquals(1, frames.size());
            assertEquals(PlayerProgress.FACULTY_STATE_NONE, env.player.progress().facultyState);
            assertEquals(0, env.player.actorState().logicState());
        }
    }

    @Test
    void invalidActPayArgumentsAreSilentlyIgnored() {
        try (Env env = new Env()) {
            env.handle(direct(1006, CustomMessages.Value.ofInt32(21)));
            env.drain();
            env.handle(direct(1006, CustomMessages.Value.ofInt32(23),
                    CustomMessages.Value.ofInt32(9), CustomMessages.Value.ofInt32(1)));
            assertTrue(env.drain().isEmpty(), "card 越界静默");
        }
    }

    private static byte unchecked(int value) {
        return (byte) value;
    }
}
