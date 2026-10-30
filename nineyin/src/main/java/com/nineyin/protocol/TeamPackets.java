package com.nineyin.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 组队视图帧（View 190），布局 2026-10-30 实机抓包逐字节钉死（§3.24，
 * op=310 建队响应）：0x15 建 view=190 容量=成员数；0x18 成员行 7 列
 * [1=名字(裸 UTF-8，u32 长度含 NUL), 2..6=int 0, 7=int 队长位 1/0]。
 * 注意：名字是裸 UTF-8 而非 GBK 窄串（实机 e9989f…=UTF-8"队探"互证）。
 */
public final class TeamPackets {

    private TeamPackets() {
    }

    /** 0x15 组队视图创建（容量 = 成员数，建队即 1）。 */
    public static byte[] teamViewCreate(int capacity) {
        return new PacketWriter()
                .u8(0x15).u16(190).u16(capacity).u16(0)
                .toByteArray();
    }

    /** 0x18 组队成员行（objIndex 从 1 起）。 */
    public static byte[] teamMemberRow(int objIndex, String name, boolean captain) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        PacketWriter w = new PacketWriter()
                .u8(0x18).u16(190).u16(objIndex).u16(7)
                .u16(1).u32(nameBytes.length + 1).raw(nameBytes).u8(0);
        for (int col = 2; col <= 6; col++) {
            w.u16(col).i32(0);
        }
        w.u16(7).i32(captain ? 1 : 0);
        return w.toByteArray();
    }
}
