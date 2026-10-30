package com.nineyin.protocol;

/**
 * 邮件行帧（记录表 17 RecvLetterRec），布局 2026-10-30 实机抓包逐字节钉死（§3.23）：
 * 0x11 行 = 15 列 [sender_id(str), "", 101(u8), title_id(str), content_id(str),
 * "0", "0", 附件XML(str，无附件=""), created_at(i64), read(u8), serial(str),
 * "0", 0(u8), 2592000(i64 有效期30天), 0(u8)]；
 * 0x13 单元格更新（serverRecordGridCellsTarget 反汇编钉死）=
 * op + u8(0) + oid + owner + u16(表17) + u16(单元格数) + (u16 行号 + u8 列号 + 按列型值)*
 * （353 已读只更 col9=1；355 领附件更 col7="" + col9=1）；
 * 0x12 删行复用 S2CPackets.recordDelRow(17, rowNo)。
 */
public final class MailPackets {

    private MailPackets() {
    }

    /** 附件 XML（实机格式：Mail/Object/Property/Records 空标签对）。 */
    public static String attachmentXml(String configId, int amount) {
        return "<Mail><Object Config=\"" + configId + "\">"
                + "<Property Amount=\"" + amount + "\"></Property>"
                + "<Records></Records></Object></Mail>";
    }

    /** 0x11 RecvLetterRec 行（rowNo = 邮箱列表序号；attachmentXml 传 "" 表示无附件）。 */
    public static byte[] mailRow(int rowNo, String senderId, String titleId, String contentId,
                                 String attachmentXml, long createdAt, boolean read, String serial) {
        PacketWriter w = new PacketWriter()
                .u8(0x11).u8(0)
                .u32(S2CPackets.PLAYER_OBJECT_ID)
                .u32(S2CPackets.PLAYER_OWNER_ID)
                .u16(RecordId.RECV_LETTER_REC)
                .u16(rowNo)
                .u16(1);
        w.string(senderId == null ? "" : senderId)        // col0 发件人 UI id
                .string("")                               // col1
                .u8(101)                                  // col2 类型（战报 101）
                .string(titleId == null ? "" : titleId)   // col3 标题 UI id
                .string(contentId == null ? "" : contentId) // col4 正文 UI id
                .string("0")                              // col5
                .string("0")                              // col6
                .string(attachmentXml == null ? "" : attachmentXml) // col7
                .i64(createdAt)                           // col8 created_at
                .u8(read ? 1 : 0)                         // col9 已读
                .string(serial)                           // col10 serial
                .string("0")                              // col11
                .u8(0)                                    // col12
                .i64(2_592_000L)                          // col13 有效期 30 天
                .u8(0);                                   // col14
        return w.toByteArray();
    }

    /**
     * 0x13 已读更新（单元格数 1：col9=1；实测 `…1100 0100 0000 09 01`）。
     * 0x13 布局（serverRecordGridCellsTarget 反汇编钉死，§3.23）：
     * op + u8 + oid + owner + u16 表 + u16 单元格数 + (u16 行号 + u8 列号 + 按列型值)*。
     */
    public static byte[] readFlag(int rowNo) {
        return new PacketWriter()
                .u8(0x13).u8(0)
                .u32(S2CPackets.PLAYER_OBJECT_ID)
                .u32(S2CPackets.PLAYER_OWNER_ID)
                .u16(RecordId.RECV_LETTER_REC)
                .u16(1)               // 单元格数
                .u16(rowNo).u8(9).u8(1) // 行号 + col9 read = 1
                .toByteArray();
    }

    /** 0x13 附件已领取（单元格数 2：col7="" + col9=1；实测 26B 金样逐字节一致）。 */
    public static byte[] attachmentClaimed(int rowNo) {
        return new PacketWriter()
                .u8(0x13).u8(0)
                .u32(S2CPackets.PLAYER_OBJECT_ID)
                .u32(S2CPackets.PLAYER_OWNER_ID)
                .u16(RecordId.RECV_LETTER_REC)
                .u16(2)                     // 单元格数
                .u16(rowNo).u8(7).string("") // 行号 + col7 附件清空
                .u16(rowNo).u8(9).u8(1)      // 行号 + col9 read = 1
                .toByteArray();
    }

    /** 0x12 删行。 */
    public static byte[] deleteRow(int rowNo) {
        return S2CPackets.recordDelRow(RecordId.RECV_LETTER_REC, rowNo);
    }
}
