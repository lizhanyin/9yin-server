package com.nineyin.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** View 43 内功帧（DB 书本列表重载）：容器容量、9 属性行布局、门控过滤与槽位连续性。 */
class NeiGongViewFramesTest {

    private static BootstrapPackets.NeiGongBook book(String id, int level, int total) {
        return new BootstrapPackets.NeiGongBook(id, 5, 1002, level, 36, total, 120, 120,
                "", 0, 0, 0, "ng_jh", 0, 0, 0, 0, 0, 0);
    }

    @Test
    void customBookListBuildsCapacityAndSixteenPropRows() {
        List<BootstrapPackets.NeiGongBook> books = List.of(
                book("ng_jh_001", 7, 48000), book("ng_jh_201", 13, 3072000));
        List<byte[]> frames = BootstrapPackets.neigongViewFrames(books, id -> true);
        assertEquals(3, frames.size(), "container + 2 rows");
        assertArrayEquals(SkillPackets.createView(SkillPackets.VIEW_NEIGONG, 2), frames.get(0),
                "capacity equals book count");
        byte[] expectedRow1 = SkillPackets.viewAddHeader(SkillPackets.VIEW_NEIGONG, 1, 16)
                .u16(SkillPackets.V_CONFIG_ID).string("ng_jh_001")
                .u16(PropertyId.STATIC_DATA).i32(5)
                .u16(PropertyId.ITEM_TYPE).i32(1002)
                .u16(PropertyId.SCHOOL).string("ng_jh")
                .u16(PropertyId.LEVEL).u8(7)
                .u16(PropertyId.MAX_LEVEL).i32(36)
                .u16(PropertyId.STR_ADD).i32(0)
                .u16(PropertyId.DEX_ADD).i32(0)
                .u16(PropertyId.ING_ADD).i32(0)
                .u16(PropertyId.SPI_ADD).i32(0)
                .u16(PropertyId.STA_ADD).i32(0)
                .u16(PropertyId.CUR_FILL_VALUE).i32(0)
                .u16(PropertyId.TOTAL_FILL_VALUE).i32(48000)
                .u16(PropertyId.POWER_VALUE).i32(120)
                .u16(PropertyId.MAX_POWER_VALUE).i32(120)
                .u16(PropertyId.WU_XING).i32(0)
                .toByteArray();
        assertArrayEquals(expectedRow1, frames.get(1));
    }

    @Test
    void gateFiltersRowsAndKeepsSlotsContiguous() {
        List<BootstrapPackets.NeiGongBook> books = List.of(
                book("ng_a", 1, 100), book("ng_locked", 1, 100), book("ng_b", 2, 300));
        List<byte[]> frames = BootstrapPackets.neigongViewFrames(books,
                id -> !"ng_locked".equals(id));
        assertEquals(3, frames.size(), "container + 2 open rows");
        // 0x18 头：op@0 + u16 view@1 + u16 index@3 + u16 属性数@5
        byte[] row1 = frames.get(1);
        byte[] row2 = frames.get(2);
        assertEquals(0x18, row1[0] & 0xFF);
        assertEquals(1, row1[3] | (row1[4] << 8), "open rows keep contiguous slots");
        assertEquals(2, row2[3] | (row2[4] << 8));
        assertEquals(16, row2[5] | (row2[6] << 8), "sixteen props per row");
        // 旧入口（starter 集合）不受影响
        assertEquals(BootstrapPackets.STARTER_BOOKS.size() + 1,
                BootstrapPackets.neigongViewFrames(id -> true).size());
    }
}
