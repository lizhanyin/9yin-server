package com.nineyin.server.game.avatar.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 风物志皮肤协议帧测试：位图行分组（一位一皮肤，32/行，serial=皮肤号+1——与
 * Task_Completed/引擎 FxGameLogic 位判定同式）+ 帧头方言（0x11 记录行 /
 * 0x12 删行 / 0x10 属性）。
 */
class SkinMessagesTest {

    @Test
    void officialRecordIndexesResolved() {
        // 官方 447 schema 表（0x0A 帧）必须含 CardRec/UseCardRec/FashionSuitRec。
        assertTrue(SkinMessages.RECORD_CARD >= 0);
        assertTrue(SkinMessages.RECORD_USE_CARD >= 0);
        assertTrue(com.nineyin.server.protocol.RecordSchemas
                .indexByName("FashionSuitRec") >= 0);
    }

    @Test
    void bitmapRowsGroupThirtyTwoSkinsPerRow() {
        // 空 → 单行 0（建立通道）
        assertEquals(1, SkinMessages.bitmapRows(List.of()).length);
        assertEquals(0L, SkinMessages.bitmapRows(List.of())[0]);
        // serial=皮肤号+1：1→行0位1；31→行0位31；32→行1位0；33→行1位1
        long[] rows = SkinMessages.bitmapRows(List.of(1, 31, 32, 33));
        assertEquals(2, rows.length);
        assertEquals((1L << 1) | (1L << 31), rows[0]);
        assertEquals((1L << 0) | (1L << 1), rows[1]);
        // 同行多位共存：皮肤 1、2 → 行 0 位 1、2（0b110）
        assertEquals(0b110, SkinMessages.bitmapRows(List.of(1, 2))[0]);
        // 皮肤 0 → serial 1 → 行 0 位 0（引擎口径 id0 合法位）；负数忽略
        assertEquals(1L << 0, SkinMessages.bitmapRows(List.of(0))[0]);
        assertEquals(1, SkinMessages.bitmapRows(List.of(-5)).length);
        assertEquals(0L, SkinMessages.bitmapRows(List.of(-5))[0]);
    }

    @Test
    void collectedFramesFollowRecordDialect() {
        List<byte[]> frames = SkinMessages.collectedFrames(1, 1, List.of(1, 33));
        assertEquals(2, frames.size());
        for (byte[] frame : frames) {
            assertEquals(0x11, frame[0] & 0xFF);
        }
        // 显式行号：第二帧 startRow=1（+0x0C/+0x0E 是源行起始/数量）
        assertEquals(1, (frames.get(1)[12] & 0xFF) | ((frames.get(1)[13] & 0xFF) << 8));
        // 皮肤 33 → 行 1，共 2 行
        assertEquals(2, SkinMessages.collectedRowCount(List.of(1, 33)));
    }

    @Test
    void collectedClearFramesDeleteDescending() {
        List<byte[]> frames = SkinMessages.collectedClearFrames(1, 1, 3);
        assertEquals(3, frames.size());
        for (byte[] frame : frames) {
            assertEquals(0x12, frame[0] & 0xFF);
        }
        // 行号倒序：2,1,0（正序删会即时重排剩余行号）；recordDelRow 末位 u16 是行号
        assertEquals(2, (frames.get(0)[12] & 0xFF) | ((frames.get(0)[13] & 0xFF) << 8));
        assertEquals(0, (frames.get(2)[12] & 0xFF) | ((frames.get(2)[13] & 0xFF) << 8));
    }

    @Test
    void mountedFramesAreU16Pairs() {
        List<byte[]> frames = SkinMessages.mountedFrames(1, 1, List.of(7));
        assertEquals(1, frames.size());
        assertEquals(0x11, frames.get(0)[0] & 0xFF);
        // UseCardRec[2,2]：16 字节帧头（含 count=列数 2）后首列 u16 = 皮肤号
        int firstColumn = (frames.get(0)[16] & 0xFF) | ((frames.get(0)[17] & 0xFF) << 8);
        assertEquals(7, firstColumn);
    }

    @Test
    void showEquipTypeFrameDialect() {
        byte[] propertyFrame = SkinMessages.showEquipTypeFrame(1, 1,
                SkinMessages.SHOW_EQUIP_TYPE_SKIN, 9);
        assertEquals(0x10, propertyFrame[0] & 0xFF);
        // S2C custom 180 是子操作分发（开窗/回执），收录刷新不发——见类注释。
    }
}
