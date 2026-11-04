package com.nineyin.server.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.nineyin.server.protocol.RecordMessages.Cell;

/**
 * 记录行帧新列型字节级断言：type8 宽串（u32 字节长含尾宽 NUL + UTF-16LE + 0x0000，
 * bin64 fxnet2.dll RecvRecordRow case8 取证）与 type6 f64；载体 = 官方表
 * RecvLetterRec（idx 319，16 列恰好覆盖 8/7/3/4/6 全部新线型）。
 */
class RecordMessagesTest {

    private static final int RECV_LETTER = RecordMessages.RECORD_RECV_LETTER;

    @Test
    void recvLetterIndexFollowsOfficialDialect() {
        assertEquals(319, RECV_LETTER, "官方 447 表帧内下标（真机 0x0A 透传方言）");
        byte[] columnTypes = RecordSchemas.columnTypes(RECV_LETTER);
        assertEquals(16, columnTypes.length);
        assertArrayEquals(new byte[] {8, 7, 3, 8, 8, 7, 7, 7, 4, 3, 7, 7, 3, 4, 3, 6},
                columnTypes, "RecvLetterRec 官方列型（lua form_mail 15 常量 + 保留 f64 尾列）");
    }

    @Test
    void recordAddCellsEncodesWideStringDoubleAndFullRow() {
        byte[] frame = RecordMessages.recordAddCells(1, 1, RECV_LETTER,
                Cell.ofString("系统"),        // 0 宽
                Cell.ofString("gm"),          // 1 窄
                Cell.ofInt(101),              // 2
                Cell.ofString("GM 测试邮件"), // 3 宽
                Cell.ofString("正文"),        // 4 宽
                Cell.ofString("0"),           // 5 窄
                Cell.ofString("0"),           // 6 窄
                Cell.ofString("<Root/>"),     // 7 窄
                Cell.ofInt64(1_736_000_000L), // 8 i64
                Cell.ofInt(1),                // 9
                Cell.ofString("gm1-1"),       // 10 窄
                Cell.ofString("0"),           // 11 窄
                Cell.ofInt(0),                // 12
                Cell.ofInt64(0),              // 13
                Cell.ofInt(0),                // 14
                Cell.ofDouble(0.0));          // 15 f64

        // 帧头：0x11 | 0 | u32 obj | u32 owner | u16 record | u16 start | u16 count
        assertEquals(0x11, frame[0] & 0xFF);
        assertEquals(0, frame[1]);
        assertEquals(1, readU32(frame, 2));
        assertEquals(1, readU32(frame, 6));
        assertEquals(RECV_LETTER, readU16(frame, 10));
        assertEquals(0, readU16(frame, 12));
        assertEquals(1, readU16(frame, 14));

        int offset = 16;
        // 列 0（宽串）：u32 长度按字节计、含尾宽 NUL
        byte[] wide = "系统".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(wide.length + 2, readU32(frame, offset));
        offset += 4;
        assertArrayEquals(wide, java.util.Arrays.copyOfRange(frame, offset, offset + wide.length));
        offset += wide.length;
        assertEquals(0, readU16(frame, offset), "宽串尾 NUL（u16 0）");
        offset += 2;

        // 列 1（窄串）
        offset = expectNarrow(frame, offset, "gm");
        // 列 2（i32）
        assertEquals(101, readU32(frame, offset));
        offset += 4;
        // 列 3/4 宽串
        offset = expectWide(frame, offset, "GM 测试邮件");
        offset = expectWide(frame, offset, "正文");
        // 列 5/6/7 窄串
        offset = expectNarrow(frame, offset, "0");
        offset = expectNarrow(frame, offset, "0");
        offset = expectNarrow(frame, offset, "<Root/>");
        // 列 8（i64）
        assertEquals(1_736_000_000L, readI64(frame, offset));
        offset += 8;
        // 列 9（i32）
        assertEquals(1, readU32(frame, offset));
        offset += 4;
        // 列 10/11 窄串
        offset = expectNarrow(frame, offset, "gm1-1");
        offset = expectNarrow(frame, offset, "0");
        // 列 12 i32 / 13 i64 / 14 i32
        offset += 4 + 8 + 4;
        // 列 15（f64）：8 字节 IEEE-754 零
        assertEquals(8, frame.length - offset, "f64 尾列恰收尾整帧");
        for (int i = 0; i < 8; i++) {
            assertEquals(0, frame[offset + i], "f64 0.0 的 IEEE-754 位型");
        }
    }

    @Test
    void recordAddCellsFillsMissingTailsWithZeroedWideNul() {
        // 尾列缺省（窄于 16）：宽串补空串（u32 2 + 0x0000）、f64 补 0——与窄串补空同语义。
        byte[] frame = RecordMessages.recordAddCells(1, 1, RECV_LETTER, Cell.ofString(""));
        int offset = 16;
        assertEquals(2, readU32(frame, offset), "空宽串长度=仅尾 NUL 2 字节");
        assertEquals(0, readU16(frame, offset + 4));
        assertEquals(frame.length, offset + 6 + tailSizeOfColumns1to15());
    }

    /** 官方表列 1..15 的线宽合计（变长列按空串计：窄串 5B、宽串 6B）。 */
    private static int tailSizeOfColumns1to15() {
        return 5            // 1 窄空（u4 len + NUL）
                + 4         // 2 i32
                + 6 + 6     // 3/4 宽空（u4 len + 0x0000）
                + 5 * 3     // 5/6/7 窄空
                + 8 + 4     // 8 i64 / 9 i32
                + 5 * 2     // 10/11 窄空
                + 4 + 8 + 4 // 12 i32 / 13 i64 / 14 i32
                + 8;        // 15 f64
    }

    @Test
    void cellTypeMixingRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordMessages.recordAddCells(1, 1, RECV_LETTER, Cell.ofInt(1)),
                "宽串列给 int 应在编码期抛错");
        assertThrows(IllegalArgumentException.class,
                () -> RecordMessages.recordAddCells(1, 1, RECV_LETTER,
                        Cell.ofString(""), Cell.ofString(""), Cell.ofString("")),
                "i32 列给窄串应抛错");
    }

    private static int expectNarrow(byte[] frame, int offset, String expected) {
        byte[] narrow = expected.getBytes(StandardCharsets.UTF_8);
        assertEquals(narrow.length + 1, readU32(frame, offset));
        offset += 4;
        assertArrayEquals(narrow, java.util.Arrays.copyOfRange(frame, offset, offset + narrow.length));
        assertEquals(0, frame[offset + narrow.length]);
        return offset + narrow.length + 1;
    }

    private static int expectWide(byte[] frame, int offset, String expected) {
        byte[] wide = expected.getBytes(StandardCharsets.UTF_16LE);
        assertEquals(wide.length + 2, readU32(frame, offset));
        offset += 4;
        assertArrayEquals(wide, java.util.Arrays.copyOfRange(frame, offset, offset + wide.length));
        assertEquals(0, readU16(frame, offset + wide.length));
        return offset + wide.length + 2;
    }

    private static int readU16(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
    }

    private static int readU32(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
    }

    private static long readI64(byte[] frame, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = value << 8 | (frame[offset + i] & 0xFFL);
        }
        return value;
    }
}
