package com.nineyin.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮箱存储：data/mail.json（与参考服 Go mailStore 同构，两端可共享同一数据文件）。
 * 结构：{"version":1,"roles":{"&lt;roleId&gt;":[mail,…]}}，mail =
 * {serial, created_at, read, sender_id, title_id, content_id, attachment?{config_id,amount}}。
 * serial 形如 "sys-{roleId}-{nanotime}-{seq}"（实机抓包钉死）。
 */
public final class MailStore {

    public record Attachment(String configId, int amount) {
    }

    /** 一封邮件；expires 恒 30 天（2592000s，行帧 col13），不入存档。 */
    public static final class Mail {
        public String serial;
        public long createdAt;
        public boolean read;
        public String senderId;
        public String titleId;
        public String contentId;
        public Attachment attachment;

        public Mail() {
        }

        public Mail(String serial, long createdAt, String senderId, String titleId,
                    String contentId, Attachment attachment) {
            this.serial = serial;
            this.createdAt = createdAt;
            this.senderId = senderId;
            this.titleId = titleId;
            this.contentId = contentId;
            this.attachment = attachment;
        }
    }

    /** GM 测试邮件的固定 UI 文本（实机抓包：war order 战报三件套）。 */
    public static final String TEST_SENDER_ID = "ui_mail_war_order_source";
    public static final String TEST_TITLE_ID = "ui_mail_war_order_title";
    public static final String TEST_CONTENT_ID = "ui_mail_xinmo_item_content";

    private static final Logger log = LoggerFactory.getLogger(MailStore.class);

    private final Path file;                                    // 可空 = 仅内存（测试）
    private final Map<String, List<Mail>> roles = new LinkedHashMap<>();
    private boolean loadFailed;

    private MailStore(Path file) {
        this.file = file;
    }

    /** 纯内存实例（测试 / 未接线路径）。 */
    public static MailStore inMemory() {
        return new MailStore(null);
    }

    /** 从 data 目录装载；文件缺失则从空邮箱开始。 */
    public static MailStore load(Path dataDir) {
        MailStore store = new MailStore(dataDir.resolve("mail.json"));
        store.readJson();
        int total = store.roles.values().stream().mapToInt(List::size).sum();
        log.info("mail store ready path={} roles={} mails={}", store.file, store.roles.size(), total);
        return store;
    }

    /** 350/365 打开/刷新：重读磁盘（对齐 Go Load；行帧不重推）。 */
    public synchronized void reload() {
        if (file != null) {
            readJson();
        }
    }

    public synchronized List<Mail> list(long roleId) {
        return new ArrayList<>(roles.getOrDefault(String.valueOf(roleId), List.of()));
    }

    /** 追加邮件（新邮件在列表尾，行号 = size-1）。 */
    public synchronized Mail add(long roleId, Mail mail) {
        List<Mail> list = roles.computeIfAbsent(String.valueOf(roleId), k -> new ArrayList<>());
        list.add(mail);
        save("add");
        return mail;
    }

    /** GM send_test_mail：固定战报三件套 + 物品附件（对齐 Go `GM send_test_mail config=%s amount=%d`）。 */
    public synchronized Mail sendTestMail(long roleId, String configId, int amount) {
        String serial = "sys-" + roleId + "-" + System.nanoTime() + "-0";
        return add(roleId, new Mail(serial, System.currentTimeMillis() / 1000,
                TEST_SENDER_ID, TEST_TITLE_ID, TEST_CONTENT_ID,
                new Attachment(configId, Math.max(1, amount))));
    }

    /** serial → 列表序号（记录行号）；未命中 -1。 */
    public synchronized int indexOf(long roleId, String serial) {
        List<Mail> list = roles.get(String.valueOf(roleId));
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).serial.equals(serial)) {
                return i;
            }
        }
        return -1;
    }

    /** 353 已读：命中置位并返回行号，未命中返回 -1（静默，对齐实机）。 */
    public synchronized int setRead(long roleId, String serial) {
        int idx = indexOf(roleId, serial);
        if (idx < 0) {
            return -1;
        }
        List<Mail> list = roles.get(String.valueOf(roleId));
        if (!list.get(idx).read) {
            list.get(idx).read = true;
            save("setRead");
        }
        return idx;
    }

    /** 355/359 领附件：取走并清空附件（一次性）；无邮件或无附件返回 null。 */
    public synchronized Mail takeAttachment(long roleId, String serial) {
        int idx = indexOf(roleId, serial);
        if (idx < 0) {
            return null;
        }
        Mail mail = roles.get(String.valueOf(roleId)).get(idx);
        if (mail.attachment == null) {
            return null;
        }
        mail.read = true;
        Mail claimed = new Mail(mail.serial, mail.createdAt, mail.senderId,
                mail.titleId, mail.contentId, mail.attachment);
        mail.attachment = null;
        save("claim");
        return claimed;
    }

    /** 入包失败回滚附件（addBagItem 拒绝时保持邮件原样）。 */
    public synchronized void restoreAttachment(long roleId, String serial, Attachment attachment) {
        int idx = indexOf(roleId, serial);
        if (idx < 0) {
            return;
        }
        Mail mail = roles.get(String.valueOf(roleId)).get(idx);
        mail.attachment = attachment;
        save("restore");
    }

    /** 356/361 删除：命中移除并返回行号，未命中 -1。 */
    public synchronized int delete(long roleId, String serial) {
        int idx = indexOf(roleId, serial);
        if (idx < 0) {
            return -1;
        }
        roles.get(String.valueOf(roleId)).remove(idx);
        save("delete");
        return idx;
    }

    public synchronized int count(long roleId) {
        return roles.getOrDefault(String.valueOf(roleId), List.of()).size();
    }

    // ---------- 持久化 ----------

    private void readJson() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            JSONObject root = JSON.parseObject(text);
            roles.clear();
            JSONObject roleMap = root.getJSONObject("roles");
            if (roleMap != null) {
                for (Map.Entry<String, Object> e : roleMap.entrySet()) {
                    List<Mail> mails = new ArrayList<>();
                    if (e.getValue() instanceof JSONArray arr) {
                        for (Object o : arr) {
                            JSONObject j = (JSONObject) o;
                            Mail m = new Mail();
                            m.serial = j.getString("serial");
                            m.createdAt = j.getLongValue("created_at");
                            m.read = j.getIntValue("read") != 0;
                            m.senderId = j.getString("sender_id");
                            m.titleId = j.getString("title_id");
                            m.contentId = j.getString("content_id");
                            JSONObject att = j.getJSONObject("attachment");
                            if (att != null) {
                                m.attachment = new Attachment(att.getString("config_id"),
                                        att.getIntValue("amount"));
                            }
                            mails.add(m);
                        }
                    }
                    roles.put(e.getKey(), mails);
                }
            }
            loadFailed = false;
        } catch (IOException | RuntimeException e) {
            loadFailed = true;
            log.error("mail store read failed: {}", file, e);
        }
    }

    private void save(String cause) {
        if (file == null) {
            return;
        }
        if (loadFailed) {
            log.error("mail store save skipped — load previously failed, refusing to overwrite {}", file);
            return;
        }
        JSONObject root = new JSONObject(true);
        root.put("version", 1);
        JSONObject roleMap = new JSONObject(true);
        for (Map.Entry<String, List<Mail>> e : roles.entrySet()) {
            JSONArray arr = new JSONArray();
            for (Mail m : e.getValue()) {
                JSONObject j = new JSONObject(true);
                j.put("serial", m.serial);
                j.put("created_at", m.createdAt);
                j.put("read", m.read);
                j.put("sender_id", m.senderId);
                j.put("title_id", m.titleId);
                j.put("content_id", m.contentId);
                if (m.attachment != null) {
                    JSONObject att = new JSONObject(true);
                    att.put("config_id", m.attachment.configId());
                    att.put("amount", m.attachment.amount());
                    j.put("attachment", att);
                }
                arr.add(j);
            }
            roleMap.put(e.getKey(), arr);
        }
        root.put("roles", roleMap);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(root, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("mail store save failed ({}): {}", cause, file, e);
        }
    }
}
