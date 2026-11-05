package com.nineyin.server.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * 0x38 heartbeatReply 字节级断言：payload 取 2026-11-05 真机 ping
 * （60760 c2s 首条 {@code 18 b3 3a 74 00 00 51 0f 00 00 00 00}，客户端 uptime/seq 续前会话）。
 * 布局 = 旧仓 SceneFlow.onPing 真机验证版（menu2 exe main.heartbeatReply 逐字节移植）。
 */
class HeartbeatMessagesTest {

    @Test
    void replyEchoesTickSeqTailAndCarriesServerClock() {
        byte[] ping = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x18)
                .putInt(0x00743AB3)  // 客户端 uptime ms（线序 little-endian）
                .put((byte) 0)
                .putShort((short) 0x0F51)
                .putInt(0)
                .array();
        byte[] reply = HeartbeatMessages.heartbeatReply(ping, 0x0000018BF2C3D4E5L);

        assertEquals(30, reply.length);
        // [0]=0x38 [1..5)=tick 回显 [5]=ping[5] [6]=seq 低位 [7]=0（客户端容忍不回显高位）
        assertEquals(0x38, reply[0] & 0xFF);
        assertArrayEquals(new byte[] {(byte) 0xB3, 0x3A, 0x74, 0x00},
                new byte[] {reply[1], reply[2], reply[3], reply[4]});
        assertEquals(0x51, reply[6] & 0xFF, "seq 低位回显 = HUD RTT 配对键");
        assertEquals(0x00, reply[7]);
        // [8..12) 尾段回显（ping 全零）+ [12] u8 回显
        assertEquals(0x00, reply[12]);
        // [13..21) 与 [22..30) = 服务器 ms 时钟 u64 ×2（官服 pcap ms 基准）
        long clock = u64(reply, 13);
        assertEquals(0x0000018BF2C3D4E5L, clock);
        assertEquals(1, reply[21] & 0xFF);
        assertEquals(clock, u64(reply, 22));
    }

    @Test
    void shortPingStillReplies30Bytes() {
        byte[] reply = HeartbeatMessages.heartbeatReply(new byte[] {0x18, 1, 2}, 42L);
        assertEquals(30, reply.length);
        assertEquals(0x38, reply[0] & 0xFF);
        // 不足 7B：无字段可回显，固定 30B 骨架（时钟段置零）
        assertEquals(0, u64(reply, 13));
    }

    private static long u64(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }
}
