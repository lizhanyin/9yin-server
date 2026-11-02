package com.nineyin.server.game.avatar.neigong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameCodec;
import com.nineyin.server.transport.FrameEncoder;
import com.nineyin.server.transport.XorKey;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

/**
 * 历练层（LiveGroove/HoldFaculty/LiveSpeedPower + custom 235）行为：
 * 官方转化公式（form_faculty.lua:87-111：槽速 10/20/50/100/200 ×(1+power%)，
 * 非名俊 ×0.7，1 毫历练 = 1 修为）、100 秒累积、槽位上限、旧档兼容、
 * 潜能赎回 235 GETINFO/BUY 帧序与 FacultyStore 快照往返。
 */
class LivepointProgressTest {

    private static final Instant NOW = Instant.parse("2026-01-02T15:04:05Z");

    private static final class Env implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final PlayerActor player;

        Env() {
            channel.pipeline().addLast(new FrameEncoder(new Connection(null)));
            player = PlayerActor.create("李逍遥", 99999, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
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

    /** 客户端 0x1E 直达帧构造（同 FacultyProgressTest.direct）。 */
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

    // ---------------------------------------------------------------- 转化数学

    @Test
    void accrueFillsFirstGrooveEachStep() {
        PlayerProgress progress = newProgress();
        assertTrue(progress.livepointTick(NOW.plusSeconds(100), true), "槽空首步必有变化");
        assertEquals(1000 - 10, progress.liveGroove[0], "槽1 +1000 累积 -10 转化（VIP 无损）");
        assertEquals(0, progress.liveGroove[4]);
    }

    @Test
    void normalTierSplitsSevenToThree() {
        PlayerProgress progress = newProgress();
        progress.liveGroove[4] = 200_000; // 槽5：200 点历练
        int facultyBefore = progress.faculty;
        int holdBefore = progress.holdFaculty;
        assertTrue(progress.livepointTick(NOW.plusSeconds(100), false));
        // 槽1 累积 +1000、转化 10 → 990；槽5 转化 200：gain 140 修为 + 60 潜能。
        assertEquals(200_000 - 200, progress.liveGroove[4]);
        assertEquals(facultyBefore + 140 + 7, progress.faculty);
        assertEquals(holdBefore + 60 + 3, progress.holdFaculty);
    }

    @Test
    void vipTierConvertsLossless() {
        PlayerProgress progress = newProgress();
        progress.liveGroove[4] = 200_000;
        int facultyBefore = progress.faculty;
        progress.livepointTick(NOW.plusSeconds(100), true);
        assertEquals(facultyBefore + 200 + 10, progress.faculty, "名俊无损：槽5 全额 200 + 槽1 10");
        assertEquals(0, progress.holdFaculty, "名俊不产生潜能");
    }

    @Test
    void liveSpeedPowerDoublesGrooveSpeed() {
        PlayerProgress progress = newProgress();
        progress.setLiveSpeedPower(100); // 卦象加成：速度 ×2
        progress.liveGroove[4] = 200_000;
        int facultyBefore = progress.faculty;
        progress.livepointTick(NOW.plusSeconds(100), false);
        // 槽5 速度 200×200/100=400 → 普通档 gain 280 / 潜能 120。
        assertEquals(facultyBefore + 280 + 14, progress.faculty);
        assertEquals(120 + 6, progress.holdFaculty);
    }

    @Test
    void grooveCapAndTotalCapStopAccrual() {
        PlayerProgress progress = newProgress();
        for (int i = 0; i < PlayerProgress.GROOVE_COUNT; i++) {
            progress.liveGroove[i] = PlayerProgress.GROOVE_CAP;
        }
        long total = PlayerProgress.GROOVE_COUNT * (long) PlayerProgress.GROOVE_CAP;
        progress.livepointTick(NOW.plusSeconds(100), true);
        long after = 0;
        for (int i = 0; i < PlayerProgress.GROOVE_COUNT; i++) {
            assertEquals(PlayerProgress.GROOVE_CAP - PlayerProgress.GROOVE_BASE_SPEED[i],
                    progress.liveGroove[i], "全满时累积丢弃、转化照扣");
            after += progress.liveGroove[i];
        }
        assertTrue(after < total);
    }

    @Test
    void addLivepointFillsSequentiallyAndOverflows() {
        PlayerProgress progress = newProgress();
        assertEquals(5_000, progress.addLivepoint(5), "5 点 → 5000 毫全入槽1");
        assertEquals(5_000, progress.liveGroove[0]);
        progress.liveGroove[0] = PlayerProgress.GROOVE_CAP - 500;
        assertEquals(2_000, progress.addLivepoint(2), "槽1 差 500 补满，余 1500 进槽2");
        assertEquals(PlayerProgress.GROOVE_CAP, progress.liveGroove[0]);
        assertEquals(1_500, progress.liveGroove[1]);
    }

    @Test
    void subHundredSecondsDoesNotSettle() {
        PlayerProgress progress = newProgress();
        progress.liveGroove[4] = 200_000;
        assertFalse(progress.livepointTick(NOW.plusSeconds(99), false), "余数留在 lastLivepoint");
        assertEquals(200_000, progress.liveGroove[4]);
        progress.livepointTick(NOW.plusSeconds(299), false);
        assertEquals(200_000 - 2 * 200, progress.liveGroove[4],
                "两个整步各扣一次槽速（99 秒那次不计步）");
    }

    // ---------------------------------------------------------------- 旧档兼容

    @Test
    void legacySnapshotWithoutLivepointLayerFallsBackToDefaults() {
        PlayerActor player = PlayerActor.create("李逍遥", 99999,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        PlayerProgress.Snapshot legacy = new PlayerProgress.Snapshot(
                "ng_wd_001", 5000, PlayerProgress.FACULTY_STATE_CONVERT,
                PlayerProgress.FACULTY_STYLE_NORMAL, "ng_wd_001", 200, NOW, List.of());
        player.restoreFaculty(legacy, NOW);
        PlayerProgress progress = player.progress();
        for (int i = 0; i < PlayerProgress.GROOVE_COUNT; i++) {
            assertEquals(0, progress.liveGroove[i], "旧档历练槽归零");
        }
        assertEquals(0, progress.holdFaculty);
        assertEquals(0, progress.liveSpeedPower);
        assertEquals(5000, progress.faculty, "旧档修为存量原样保留");
    }

    // ---------------------------------------------------------------- custom 235

    @Test
    void getInfoRepliesPriceFrame() {
        try (Env env = new Env()) {
            env.handle(direct(235, CustomMessages.Value.ofInt32(1), CustomMessages.Value.ofInt32(0)));
            List<byte[]> frames = env.drain();
            assertEquals(1, frames.size());
            assertEquals(0x1E, frames.get(0)[0] & 0xFF);
            // 1E 03 00 | 02 EB000000(235) | 02 01000000(sub1) | 02 F4010000(500)
            assertEquals(235, leInt(frames.get(0), 4));
            assertEquals(1, leInt(frames.get(0), 9));
            assertEquals(500, leInt(frames.get(0), 14));
        }
    }

    @Test
    void buyExchangesGoldForHoldFaculty() {
        try (Env env = new Env()) {
            env.player.setGold(100);
            env.player.progress().setHoldFaculty(2_000);
            env.drain();
            int facultyBefore = env.player.progress().faculty;
            env.handle(direct(235, CustomMessages.Value.ofInt32(2), CustomMessages.Value.ofInt32(3)));
            List<byte[]> frames = env.drain();
            assertEquals(2, frames.size(), "[CapitalType0 帧, 0x10 vital]");
            assertEquals(0x10, frames.get(0)[0] & 0xFF);
            assertEquals(0x10, frames.get(1)[0] & 0xFF);
            assertEquals(97, env.player.gold(), "扣 3 黄金");
            assertEquals(500, env.player.progress().holdFaculty, "潜能 2000-3×500");
            assertEquals(facultyBefore + 1_500, env.player.progress().faculty);
        }
    }

    @Test
    void buyBeyondGoldOrHoldIsSilentlyIgnored() {
        try (Env env = new Env()) {
            env.player.setGold(2);
            env.player.progress().setHoldFaculty(10_000);
            env.drain();
            env.handle(direct(235, CustomMessages.Value.ofInt32(2), CustomMessages.Value.ofInt32(0)));
            assertTrue(env.drain().isEmpty(), "count=0 静默");
            env.handle(direct(235, CustomMessages.Value.ofInt32(2), CustomMessages.Value.ofInt32(3)));
            assertTrue(env.drain().isEmpty(), "黄金不足静默");
            assertEquals(2, env.player.gold());
            assertEquals(10_000, env.player.progress().holdFaculty);
        }
    }

    private static int leInt(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
    }

    // ---------------------------------------------------------------- 快照往返

    @Test
    void facultyStoreRoundTripsLivepointLayer() throws IOException {
        Path directory = Files.createTempDirectory("livepoint-store-");
        try {
            FacultyStore store = FacultyStore.open(directory.resolve("faculty.json"));
            PlayerActor player = PlayerActor.create("李逍遥", 99999,
                    Clock.fixed(NOW, java.time.ZoneOffset.UTC));
            PlayerProgress progress = player.progress();
            progress.addLivepoint(4);
            progress.setHoldFaculty(1_234);
            progress.setLiveSpeedPower(25);
            progress.livepointTick(NOW.plusSeconds(0), false); // 不结算，仅固定 lastLivepoint
            store.save(7L, progress.snapshot());

            PlayerProgress.Snapshot loaded = store.load(7L);
            assertEquals(progress.snapshot(), loaded, "快照逐字段往返（含历练层 8 字段）");
        } finally {
            recursiveDelete(directory);
        }
    }

    private static void recursiveDelete(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    recursiveDelete(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /** 独立 PlayerProgress：绕开 PlayerActor 种子，直接以 NOW 为基准。 */
    private static PlayerProgress newProgress() {
        PlayerProgress progress = PlayerProgress.create(
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        progress.lastLivepoint = NOW;
        return progress;
    }
}
