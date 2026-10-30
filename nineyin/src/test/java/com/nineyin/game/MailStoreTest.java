package com.nineyin.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮箱存储：Go mail.json 同构读写 + 已读/领件/删件语义。 */
class MailStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void readsGoMailJson() throws Exception {
        Path file = tempDir.resolve("mail.json");
        Files.writeString(file, """
                {
                 "version": 1,
                 "roles": {
                  "2": [
                   {
                    "serial": "sys-2-1787904906863113800-0",
                    "created_at": 1787904906,
                    "read": true,
                    "sender_id": "ui_mail_war_order_source",
                    "title_id": "ui_mail_war_order_title",
                    "content_id": "ui_mail_xinmo_item_content",
                    "attachment": {
                     "config_id": "ynewbox_yhqt_04",
                     "amount": 1
                    }
                   },
                   {
                    "serial": "sys-2-1787675281379040900-0",
                    "created_at": 1787675281,
                    "read": true,
                    "sender_id": "ui_mail_war_order_source",
                    "title_id": "ui_mail_war_order_title",
                    "content_id": "ui_mail_xinmo_item_content"
                   }
                  ]
                 }
                }
                """, StandardCharsets.UTF_8);
        MailStore store = MailStore.load(tempDir);
        assertEquals(2, store.count(2));
        MailStore.Mail first = store.list(2).get(0);
        assertEquals("sys-2-1787904906863113800-0", first.serial);
        assertTrue(first.read);
        assertEquals("ynewbox_yhqt_04", first.attachment.configId());
        assertNull(store.list(2).get(1).attachment);
    }

    @Test
    void serialReadClaimDeleteLifecycle() {
        MailStore store = MailStore.inMemory();
        MailStore.Mail mail = store.sendTestMail(7, "yaopin_001", 2);
        assertTrue(mail.serial.startsWith("sys-7-"), "serial 形如 sys-{roleId}-{nano}-{seq}");
        assertEquals(0, store.indexOf(7, mail.serial));
        assertEquals(-1, store.indexOf(7, "sys-none"));

        // 已读：置位并返回行号；重复置位幂等
        assertEquals(0, store.setRead(7, mail.serial));
        assertTrue(store.list(7).get(0).read);

        // 领附件：一次性取走，二次领取为 null
        MailStore.Mail claimed = store.takeAttachment(7, mail.serial);
        assertEquals("yaopin_001", claimed.attachment.configId());
        assertEquals(2, claimed.attachment.amount());        assertNull(store.takeAttachment(7, mail.serial));

        // 回滚：附件恢复
        store.restoreAttachment(7, mail.serial, claimed.attachment);
        assertEquals("yaopin_001", store.list(7).get(0).attachment.configId());

        // 删除：命中移除
        assertEquals(0, store.delete(7, mail.serial));
        assertEquals(0, store.count(7));
        assertEquals(-1, store.delete(7, mail.serial));
    }

    @Test
    void persistsAcrossReload() throws Exception {
        MailStore store = MailStore.load(tempDir);
        store.sendTestMail(9, "yaopin_001", 5);
        store.setRead(9, store.list(9).get(0).serial);
        store.takeAttachment(9, store.list(9).get(0).serial);
        // 第二封未领取：附件原样落盘（Go 同构 config_id 字段）
        store.sendTestMail(9, "ynewbox_test", 1);

        MailStore reopened = MailStore.load(tempDir);
        MailStore.Mail mail = reopened.list(9).get(0);
        assertTrue(mail.read, "领附件隐含已读并落盘");
        assertFalse(mail.serial.isEmpty());
        assertNull(mail.attachment, "附件领取后清空落盘");
        assertEquals("ynewbox_test", reopened.list(9).get(1).attachment.configId());
        String json = Files.readString(tempDir.resolve("mail.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\"") && json.contains("\"roles\""));
        assertTrue(json.contains("config_id"), "Go 同构字段名");
    }
}
