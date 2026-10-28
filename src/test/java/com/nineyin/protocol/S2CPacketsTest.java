package com.nineyin.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 字节级对齐 C++ encode_player_* / make_* 系列的布局断言。 */
class S2CPacketsTest {

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    @Test
    void objectPropertyIntLayout() {
        // C++ encode_player_int_prop(383, 100)
        byte[] msg = S2CPackets.objectPropertyInt(PropertyId.MOVE_SPEED, 100);
        assertEquals(18, msg.length);
        byte[] expected = new byte[18];
        expected[0] = 0x10;
        expected[2] = 1;   // oid
        expected[6] = 1;   // owner
        expected[10] = 1;  // count
        expected[12] = 0x7F; // 383 = 0x017F LE
        expected[13] = 0x01;
        expected[14] = 100;  // value u32 LE
        assertArrayEquals(expected, msg, hex(msg));
    }

    @Test
    void objectPropertyByteLayout() {
        // C++ encode_player_byte_prop(462, 102)
        byte[] msg = S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, 102);
        assertEquals(15, msg.length);
        byte[] expected = new byte[15];
        expected[0] = 0x10;
        expected[2] = 1;
        expected[6] = 1;
        expected[10] = 1;
        expected[12] = (byte) 0xCE; // 462 = 0x01CE LE
        expected[13] = 0x01;
        expected[14] = 102;
        assertArrayEquals(expected, msg, hex(msg));
    }

    @Test
    void objectPropertyStringLayout() {
        byte[] msg = S2CPackets.objectPropertyString(PropertyId.STATE, "sitdown");
        // 头 12 + u16 索引 + u32 长度 + 7 字节 + NUL
        assertEquals(12 + 2 + 4 + 7 + 1, msg.length);
        assertEquals(0x10, msg[0]);
        assertEquals(0xB7, msg[12] & 0xFF); // 183 = 0x00B7 LE
        assertEquals(8, msg[14]); // 长度含 NUL
        assertArrayEquals("sitdown".getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOfRange(msg, 18, 25));
        assertEquals(0, msg[25]);
    }

    @Test
    void timeSyncLayout() {
        byte[] msg = S2CPackets.timeSync(0x11223344, 7, 0xAABBCCDDL);
        assertEquals(30, msg.length);
        assertEquals(0x38, msg[0]);
        assertEquals(0x44, msg[1]);
        assertEquals(0x33, msg[2]);
        assertEquals(0x22, msg[3]);
        assertEquals(0x11, msg[4]);
        assertEquals(0, msg[5]);
        assertEquals(7, msg[6]);
        assertEquals(0, msg[7]);
        assertEquals(4, msg[10]); // 官服抓包常量
        assertEquals(0xDD, msg[13] & 0xFF);
        assertEquals(0xCC, msg[14] & 0xFF);
        assertEquals(0xBB, msg[15] & 0xFF);
        assertEquals(0xAA, msg[16] & 0xFF);
        assertEquals(1, msg[21]);
        assertEquals(0xDD, msg[22] & 0xFF); // wall 副本
        assertEquals(0xAA, msg[25] & 0xFF);
    }

    @Test
    void serverLocationLayout() {
        byte[] msg = S2CPackets.serverLocation(1, 1, 1.5f, 2.5f, 3.5f, 0.25f);
        assertEquals(25, msg.length);
        assertEquals(0x1F, msg[0]);
        assertEquals(1, msg[1]);
        assertEquals(Float.floatToRawIntBits(1.5f), leU32(msg, 9));
        assertEquals(Float.floatToRawIntBits(2.5f), leU32(msg, 13));
        assertEquals(Float.floatToRawIntBits(3.5f), leU32(msg, 17));
        assertEquals(Float.floatToRawIntBits(0.25f), leU32(msg, 21));
    }

    @Test
    void serverMovingLayout() {
        byte[] msg = S2CPackets.serverMoving(10001, 0, 10f, 0f, -10f, 3.14f);
        assertEquals(45, msg.length);
        assertEquals(0x20, msg[0]);
        assertEquals(10001, leU32(msg, 1));
        for (int i = 25; i < 45; i++) {
            assertEquals(0, msg[i], "tail must be zero at " + i);
        }
    }

    @Test
    void recordDelRowLayout() {
        byte[] msg = S2CPackets.recordDelRow(RecordId.SHORTCUT_REC, 3);
        assertEquals(14, msg.length);
        assertEquals(0x12, msg[0]);
        assertEquals(1, leU32(msg, 2));
        assertEquals(1, leU32(msg, 6));
        assertEquals(4, msg[10]); // shortcut_rec = 4
        assertEquals(3, msg[12]);
    }

    @Test
    void shortcutAddLayout() {
        // C++ encode_shortcut_add({9, "skill", "zs_default_01"})
        byte[] msg = S2CPackets.shortcutAdd(9, "skill", "zs_default_01");
        int len = 16 + 4 + (4 + 5 + 1) + (4 + 13 + 1);
        assertEquals(len, msg.length);
        assertEquals(0x11, msg[0]);
        assertEquals(4, msg[10]);
        assertEquals(9, leU32(msg, 16)); // index 列
        assertEquals(6, leU32(msg, 20)); // kind 长度含 NUL
        assertEquals('s', msg[24]);
        assertEquals(14, leU32(msg, 30)); // config_id 长度含 NUL
    }

    @Test
    void recordTableEmptyLayout() {
        byte[] msg = S2CPackets.recordTableEmpty();
        assertEquals(116, msg.length); // 1 + 2 + Σ(name+1+2+ncols)：15+20+23+18+18+19+3
        assertEquals(0x0A, msg[0]);
        assertEquals(6, msg[1]); // 表数 u16 LE
        // 第一个表名 QingGongRec
        assertArrayEquals("QingGongRec".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOfRange(msg, 3, 14));
        assertEquals(0, msg[14]); // NUL
        assertEquals(1, msg[15]); // 列数 u16 LE
        assertEquals(RecordId.COLUMN_STRING, msg[17]);
    }

    private static int leU32(byte[] msg, int off) {
        return (msg[off] & 0xFF) | ((msg[off + 1] & 0xFF) << 8)
                | ((msg[off + 2] & 0xFF) << 16) | ((msg[off + 3] & 0xFF) << 24);
    }
}
