package com.nineyin.server.game.avatar.neigong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.nineyin.server.game.avatar.neigong.FacultyCustomHandler;
import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.transport.FrameEncoder;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameCodec;
import com.nineyin.server.transport.XorKey;

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
            channel.pipeline().addLast(new FrameEncoder(new Connection(null)));
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

    @Test
    void fillCurrentFacultyFillsLevelAndCarriesRemainderZero() {
        try (Env env = new Env()) {
            PlayerProgress progress = env.player.progress();
            PlayerProgress.Book book = progress.book(progress.facultyName);
            int beforeLevel = book.level;
            int beforeTotal = book.total;
            int beforeMaxPower = book.maxPower;
            int[] award = progress.fillCurrentFaculty();
            // 默认修炼书（远未满级）：[槽位, 发放=1, 升级=1, 满级=0]，恰好填满升一层余 0。
            assertEquals(1, award[1]);
            assertEquals(1, award[2]);
            assertEquals(0, award[3]);
            assertTrue(award[0] > 0);
            assertEquals(beforeLevel + 1, book.level);
            assertEquals(0, book.fill);
            assertEquals(beforeTotal + 200, book.total);
            assertEquals(beforeMaxPower + 20, book.maxPower);
            assertEquals(book.maxPower, book.power);
        }
    }

    @Test
    void fillCurrentFacultyOnMaxLevelBookReportsMaxedWithoutChange() {
        try (Env env = new Env()) {
            PlayerProgress progress = env.player.progress();
            PlayerProgress.Book book = progress.book("ng_wd_002");
            book.level = book.maxLevel;
            progress.facultyReady("ng_wd_002", NOW);
            int[] award = progress.fillCurrentFaculty();
            assertTrue(award[0] > 0, "已知书返回槽位");
            assertEquals(0, award[1], "满级书不再发放");
            assertEquals(1, award[3], "满级标记");
            assertEquals(book.maxLevel, book.level);
        }
    }

    @Test
    void fillCurrentFacultyWithoutTargetBookFails() {
        try (Env env = new Env()) {
            PlayerProgress progress = env.player.progress();
            progress.facultyName = "ng_none";
            assertArrayEquals(new int[]{-1, 0, 0, 0}, progress.fillCurrentFaculty());
        }
    }

    @Test
    void fillSpeedSplitAppliesImmediatelyAndSettlesByProduct() {
        try (Env env = new Env()) {
            PlayerProgress progress = env.player.progress();
            progress.facultyBegin(false, NOW);
            assertEquals(PlayerProgress.NORMAL_FACULTY_FILL_PER_MINUTE, progress.fillSpeed);
            // 普通内修态：base 400 × 2.0 → 800 即时生效。
            assertEquals(800, progress.setFillSpeed(400, 2.0f));
            assertEquals(800, progress.fillSpeed);
            // 快照携带拆分层。
            PlayerProgress.Snapshot snapshot = progress.snapshot();
            assertEquals(400, snapshot.fillSpeedBase());
            assertEquals(2.0f, snapshot.fillSpeedMultiplier(), 0f);
            // 1 分钟结算按乘积入账：fill +800、修炼点折半扣 400。
            PlayerProgress.Book book = progress.book(progress.facultyName);
            int fillBefore = book.fill;
            int facultyBefore = progress.snapshot().faculty();
            assertTrue(progress.advanceFaculty(NOW.plusSeconds(60)));
            assertEquals(fillBefore + 800, book.fill);
            assertEquals(facultyBefore - 400, progress.snapshot().faculty());
            // 停修态只记住参数：fillSpeed 保持 0，下次开修按乘积起修。
            progress.facultyExit(NOW);
            assertEquals(0, progress.setFillSpeed(300, 2.0f));
            assertEquals(0, progress.fillSpeed);
            progress.facultyBegin(false, NOW);
            assertEquals(600, progress.fillSpeed);
        }
    }

    @Test
    void legacySnapshotWithoutSpeedSplitFallsBackToDefaults() {
        try (Env env = new Env()) {
            // 旧契约 8 参构造：基础值/倍率缺省 → 200 × 1，restore 后开修仍 200/min。
            PlayerProgress.Snapshot legacy = new PlayerProgress.Snapshot(
                    "ng_yh_001", 1000, PlayerProgress.FACULTY_STATE_NONE, PlayerProgress.FACULTY_STYLE_NORMAL,
                    "ng_yh_001", 0, NOW, List.of());
            env.player.restoreFaculty(legacy, NOW);
            PlayerProgress progress = env.player.progress();
            assertEquals(PlayerProgress.NORMAL_FACULTY_FILL_PER_MINUTE, progress.fillSpeedBase);
            assertEquals(1.0f, progress.fillSpeedMultiplier, 0f);
            progress.facultyBegin(false, NOW);
            assertEquals(200, progress.fillSpeed);
        }
    }

    @Test
    void norFacultyAddFollowsBaseAndKeepsBaseInActStyle() {
        try (Env env = new Env()) {
            PlayerProgress progress = env.player.progress();
            progress.setFillSpeed(500, 2.0f);
            assertEquals(500, intProp(progress, "NorFacultyAdd"));
            assertEquals(1000, intProp(progress, "FillSpeed"));
            progress.facultyBegin(true, NOW);
            // 2026-11-03 对齐 F 抓包 #2194（演武帧 NorFacultyAdd 恒 200=基础值）：
            // 基准修速不再随演武置 0，仅 FillSpeed 归 0（面板显示"停止"档）。
            assertEquals(500, intProp(progress, "NorFacultyAdd"), "演武态基准修速仍发基础值");
            assertEquals(0, intProp(progress, "FillSpeed"), "演武态实际修速归 0");
        }
    }

    private static int intProp(PlayerProgress progress, String name) {
        for (com.nineyin.server.protocol.PropertyValues.Indexed property
                : progress.properties(PlayerProgress.EquipBonus.NONE)) {
            if (property.name().equals(name)) {
                return property.value().i32();
            }
        }
        throw new AssertionError("missing property " + name);
    }

    private static byte unchecked(int value) {
        return (byte) value;
    }
}
