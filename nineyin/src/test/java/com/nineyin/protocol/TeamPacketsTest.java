package com.nineyin.protocol;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 组队视图帧金样测试：字节逐位对照 2026-10-30 Go 服实机抓包（§3.24，
 * op=310 建队响应：0x15 view190 + 名为"队探54371"的队长成员行）。
 */
class TeamPacketsTest {

    @Test
    void teamViewCreateMatchesGoCapture() {
        // 实机：`15 be00 0100 0000`（view=190 容量=1）
        assertArrayEquals(HexFormat.of().parseHex("15be0001000000"),
                TeamPackets.teamViewCreate(1));
    }

    @Test
    void teamMemberRowMatchesGoCapture() {
        // 实机成员行（61B）：7 列 = 名字(裸 UTF-8, u32 长度含 NUL) + int0×5 + 队长位 1
        byte[] row = TeamPackets.teamMemberRow(1, "队探54571", true);
        assertArrayEquals(HexFormat.of().parseHex(
                "18be000100070001000c000000e9989fe68ea235343537310002000000000003"
                        + "0000000000040000000000050000000000060000000000070001000000"), row);
        // 非队长行末列 0
        byte[] member = TeamPackets.teamMemberRow(2, "member", false);
        assertEquals(0, member[member.length - 1]);
    }

    @Test
    void utf8NameNotGbkNarrow() {
        // 名字走裸 UTF-8（队=3 字节），非 GBK 窄串（2 字节）——实机互证
        byte[] row = TeamPackets.teamMemberRow(1, "队", true);
        int len = (row[9] & 0xFF) | ((row[10] & 0xFF) << 8) | ((row[11] & 0xFF) << 16)
                | ((row[12] & 0xFF) << 24);
        assertEquals(4, len, "UTF-8 队(3B)+NUL");
    }
}
