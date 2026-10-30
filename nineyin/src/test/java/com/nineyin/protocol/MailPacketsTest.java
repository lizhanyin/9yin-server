package com.nineyin.protocol;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 邮件行帧金样测试：字节逐位对照 2026-10-30 Go 服实机抓包（§3.23，
 * GM send_test_mail → yaopin_001 ×2 的完整行 + 353/355/356 响应帧）。
 */
class MailPacketsTest {

    /** 实机 gm-mail 推送的 RecvLetterRec 行（285B，serial sys-8-…，逐字节取自抓包）。 */
    private static final String GOLDEN_ROW =
            "110001000000010000001100000001001900000075695f6d61696c5f7761725f6f726465725f736f"
                    + "75726365000100000000651800000075695f6d61696c5f7761725f6f726465725f7469746c65001b"
                    + "00000075695f6d61696c5f78696e6d6f5f6974656d5f636f6e74656e740002000000300002000000"
                    + "3000660000003c4d61696c3e3c4f626a65637420436f6e6669673d2279616f70696e5f303031223e"
                    + "3c50726f706572747920416d6f756e743d2232223e3c2f50726f70657274793e3c5265636f726473"
                    + "3e3c2f5265636f7264733e3c2f4f626a6563743e3c2f4d61696c3e000460e46a00000000001c0000"
                    + "007379732d382d313739333335313638343036303935343530302d300002000000300000008d2700"
                    + "0000000000";

    @Test
    void mailRowMatchesGoCapture() {
        byte[] frame = MailPackets.mailRow(0,
                "ui_mail_war_order_source", "ui_mail_war_order_title", "ui_mail_xinmo_item_content",
                MailPackets.attachmentXml("yaopin_001", 2),
                0x6AE46004L /* 1793351684 */, false, "sys-8-1793351684060954500-0");
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN_ROW), frame);
    }

    @Test
    void attachmentXmlMatchesGoFormat() {
        assertEquals("<Mail><Object Config=\"yaopin_001\">"
                + "<Property Amount=\"2\"></Property>"
                + "<Records></Records></Object></Mail>",
                MailPackets.attachmentXml("yaopin_001", 2));
    }

    @Test
    void readFlagMatchesGoCapture() {
        // 实机 353 已读响应：`13 00 oid owner 0011 0001(cellCount) 0000(row) 09 01`
        assertArrayEquals(HexFormat.of().parseHex("130001000000010000001100010000000901"),
                MailPackets.readFlag(0));
    }

    @Test
    void attachmentClaimedMatchesGoCapture() {
        // 实机 355 领附件响应：col7="" + col9=1
        assertArrayEquals(
                HexFormat.of().parseHex("1300010000000100000011000200000007010000000000000901"),
                MailPackets.attachmentClaimed(0));
    }

    @Test
    void deleteRowMatchesGoCapture() {
        // 实机 356 删除响应：0x12 记录 17 行 0
        assertArrayEquals(HexFormat.of().parseHex("1200010000000100000011000000"),
                MailPackets.deleteRow(0));
    }
}
