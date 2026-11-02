package com.nineyin.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.login.LoginFlow;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.PacketWriter;

/**
 * F 盘抓包钉死的两条 S2C custom 链（2026-11-01 深挖成果）：
 * ① msg294 时间广播（1s ticker，[uptime i32, 挂钟 ms i64, unix 秒 i64, 游戏日 f64]）；
 * ② msg169 格挡九值帧（169, end, "BuffInParry", ObjRef×2, 宽串名×2, ms i64, 0）+
 *    BufferInfo11 串与 54 参数口径（F 实证 78 发按值去重=50 沿=50 回帧）。
 */
final class ParryAndTimeSyncTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC);

    private static int u16(byte[] data, int off) {
        return (data[off] & 0xFF) | (data[off + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] data, int off) {
        long v = 0;
        for (int i = 3; i >= 0; i--) {
            v = v << 8 | data[off + i] & 0xFF;
        }
        return v;
    }

    private static long u64(byte[] data, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = v << 8 | data[off + i] & 0xFF;
        }
        return v;
    }

    @Test
    void timeSyncFrameMatchesCapturedLayout() {
        byte[] frame = LoginFlow.timeSyncFrame(CLOCK.instant());
        assertEquals(0x1E, frame[0] & 0xFF);
        assertEquals(5, u16(frame, 1), "抓包 msg294 = 5 值（含首参 msgId）");
        assertEquals(2, frame[3] & 0xFF, "msgId tag2");
        assertEquals(294, u32(frame, 4));
        assertEquals(2, frame[8] & 0xFF, "v1(uptime) tag2 int32");
        assertEquals(3, frame[13] & 0xFF, "v2(1601 纪元 ms) tag3 int64");
        // 52795 抓包实证：v2 = unix ms + 11644473600000（Windows FILETIME 基准毫秒）。
        assertEquals(1_790_000_000_000L + LoginFlow.WINDOWS_EPOCH_TO_UNIX_MS, u64(frame, 14));
        assertEquals(3, frame[22] & 0xFF, "v3(unix 秒) tag3 int64");
        assertEquals(1_790_000_000L, u64(frame, 23));
        assertEquals(5, frame[31] & 0xFF, "v4(游戏日) tag5 float64");
        double gameDay = Double.longBitsToDouble(u64(frame, 32));
        assertEquals(46327.464, gameDay, 1e-9, "v4 游戏日常数跟齐 F");
    }

    @Test
    void parryBuffEngageAndReleaseRoundTrip() {
        PlayerActor actor = PlayerActor.create("测试侠", 0, CLOCK);
        String engaged = actor.engageParryBuff(CLOCK.instant());
        assertTrue(actor.parrying());
        assertTrue(engaged.startsWith("11CE,1,1,1,"), "BufferInfo11 头 = StaticData 0x11CE(4558),对象,层级");
        assertTrue(engaged.endsWith(",1"), "EffectType 收尾");
        assertEquals("", actor.releaseParryBuff());
        assertTrue(!actor.parrying());
    }

    @Test
    void parryStateFrameCarriesIndexedProperties() {
        PlayerActor actor = PlayerActor.create("测试侠", 0, CLOCK);
        byte[] frame = actor.parryStateFrame(true, "11D6,1,1,1,1,1");
        assertEquals(0x10, frame[0] & 0xFF);
        assertEquals(3, u16(frame, 10), "InParry/InManualParry/BufferInfo11 三行");
        int firstIdx = u16(frame, 12);
        assertTrue(firstIdx > 0, "首行索引非零（名字已解析到官方表）");
    }

    @Test
    void customWideStringEncodesTag7WithUcs2() {
        byte[] frame = CustomMessages.customIntMessage(169,
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofString("BuffInParry"),
                CustomMessages.Value.ofObject(1, 1),
                CustomMessages.Value.ofObject(1, 1),
                CustomMessages.Value.ofWideString("侠"),
                CustomMessages.Value.ofWideString("侠"),
                CustomMessages.Value.ofInt64(42L),
                CustomMessages.Value.ofInt32(0));
        assertEquals(0x1E, frame[0] & 0xFF);
        assertEquals(9, u16(frame, 1), "抓包 msg169 = 9 值");
        assertEquals(169, u32(frame, 4));
        int off = 3 + 5 + 5 + (1 + 4 + 12) + 9 + 9; // 头 + v1 + v2 + "BuffInParry\0" + obj + obj
        assertEquals(7, frame[off] & 0xFF, "tag7 宽串");
        assertEquals(4, u32(frame, off + 1), "u32 长度 = UTF16 字节 + NUL2");
        assertEquals('侠', u16(frame, off + 5) & 0xFFFF, "UTF-16LE 字符");
        assertEquals(0, u16(frame, off + 7), "u16 NUL 终止");
    }

    @Test
    void activityEnvelopeParsesParryArgument() {
        // 0x0A 信封 + 21B 头 + TVarList(54, int64 0)：54 的参数位取值口径。
        PacketWriter writer = PacketWriter.ofCapacity(40);
        writer.writeU8(0x0A);
        for (int i = 0; i < 16; i++) {
            writer.writeU8(0);
        }
        writer.writeU32(7);
        writer.writeU16(2).writeU8(2).writeU32(54).writeU8(3).writeU64(0L);
        CustomC2S.Message message = CustomC2S.parseActivityEnvelope(writer.toBytes());
        assertNotNull(message);
        assertEquals(54, message.messageId());
        List<CustomC2S.Value> values = message.values;
        assertEquals(0, values.get(1).exactInt32(), "0=按下");
    }
}
