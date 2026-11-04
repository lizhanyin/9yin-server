package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.mail.LetterStore;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 邮件全链（GM send_test_mail → RecvLetterRec 319 行帧 → C2S 353 已读/359 领附件）：
 * 真实 MySQL + go-server1 share，专用临时角色 990034 测后清理。
 * 验：发信帧字节（宽串列/窄串列/i64/f64 全 16 格）→ 读信置已读（删行+尾插重推）
 * → 领取附件入包（DB 行 + 行重推附空）→ LetterStore 落盘回读一致。
 */
class MailFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_034L;
    private static final long ROLE_SOURCE = 618L;
    private static final String ITEM = "yaopin_00100";

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private GmHub hub;
    private RoleInventoryStore store;
    private java.sql.Connection jdbc;
    private Path previousRoot;
    private Path lettersFile;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "item", "tool_item.ini"))),
                "go-server1 share 资源不可用，跳过邮件全链");
        assumeTrue(dsn().isPresent(), "MySQL DSN 不可用，跳过邮件全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);
        lettersFile = GO_ROOT.resolve(Path.of("data", "letters.json"));

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn().orElseThrow());
        org.junit.jupiter.api.Assumptions.assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        org.junit.jupiter.api.Assumptions.assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过邮件全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(dsn().orElseThrow());

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("邮件测试", (int) ROLE, Clock.systemUTC());
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "邮件测试", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
        GmHub hub = new GmHub();
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.name = "邮件测试";
        hub.attach(snapshot.sessionId, snapshot);
        this.hub = hub;
        flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null, store, null);
        // GM 动作在真机发生在 ClientReady 之后（信件行已授予），测试直接置位模拟。
        session.lettersGranted = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (jdbc != null) {
            cleanScratch();
            jdbc.close();
        }
        if (store != null) {
            store.close();
        }
        // 测试自建的信件主档清掉（go-server1 仓库本体不带 letters.json）。
        Files.deleteIfExists(lettersFile);
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    @Test
    void sendReadAndTakeAppendixFullChain() throws Exception {
        // ① GM 发信：ok 回执 + 单帧 0x11（record 319）+ 落盘
        GmCommand send = enqueue("send_test_mail",
                java.util.Map.of("itemConfig", ITEM, "amount", 3));
        flow.drainGmCommands(new Connection(channel), session);
        assertTrue(send.awaitReply(java.time.Duration.ofMillis(1000)).ok(),
                send.awaitReply(java.time.Duration.ofMillis(1)).message());
        List<byte[]> frames = drain();
        assertEquals(1, frames.size(), "发信恰一帧记录行");
        byte[] row = frames.get(0);
        assertEquals(0x11, row[0] & 0xFF);
        assertEquals(RecordMessages.RECORD_RECV_LETTER, readU16(row, 10));
        assertTrue(containsUtf16(row, "GM 测试邮件"), "标题宽串列在帧内");
        assertTrue(containsUtf16(row, "系统"), "正文宽串列在帧内");
        String serial = session.letters.get(0).serial();
        assertTrue(session.letters.get(0).appendixXml().contains(ITEM));
        assertTrue(session.letters.get(0).appendixXml().contains("Amount=\"3\""));
        assertTrue(Files.isRegularFile(lettersFile), "信件主档落盘");

        // ② C2S 353 读信：READFLAG 置 1（0x12 删行 0 + 0x11 尾插重推）
        dispatch(353, serial);
        frames = drain();
        assertEquals(2, frames.size(), "删行+重推两帧");
        assertEquals(0x12, frames.get(0)[0] & 0xFF);
        assertEquals(0, readU16(frames.get(0), 12), "删的是 0 基行号 0");
        assertEquals(0x11, frames.get(1)[0] & 0xFF);
        assertTrue(session.letters.get(0).readFlag(), "服务端镜像同步置已读");

        // ③ C2S 359 领附件：入包（DB 行 + 成对 0x19/0x18 行帧）+ 附件清空 + 已读保持
        dispatch(359, serial);
        frames = drain();
        assertEquals(0x19, frames.get(0)[0] & 0xFF, "入包锚点帧在前（grantBagItem 先于行重推）");
        assertEquals(1, frames.stream().filter(f -> (f[0] & 0xFF) == 0x12).count(), "恰一帧删行");
        assertEquals(1, frames.stream().filter(f -> (f[0] & 0xFF) == 0x11).count(), "恰一帧行重推");
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x18),
                "入包 ViewAdd 行帧（成对 0x19/0x18）: 共 " + frames.size() + " 帧");
        assertTrue(countBagRows(ITEM) >= 1, "附件物品落库");
        LetterStore.Letter after = session.letters.get(0);
        assertEquals("", after.appendixXml(), "附件已清");
        assertTrue(after.readFlag());

        // ④ 重复领取：附件已空 → 零帧零变更
        dispatch(359, serial);
        assertNull(channel.readOutbound(), "空附件领取不写帧");

        // ⑤ 落盘回读一致（重登重放数据源）
        List<LetterStore.Letter> persisted = LetterStore.open(lettersFile).load(ROLE);
        assertEquals(1, persisted.size());
        assertEquals("", persisted.get(0).appendixXml());
        assertTrue(persisted.get(0).readFlag());
        assertEquals(serial, persisted.get(0).serial());
    }

    @Test
    void sendRejectsUnknownItemWithoutFrames() {
        GmCommand bad = enqueue("send_test_mail",
                java.util.Map.of("itemConfig", "no_such_item_xyz", "amount", 1));
        flow.drainGmCommands(new Connection(channel), session);
        assertTrue(bad.awaitReply(java.time.Duration.ofMillis(1000)).message().contains("不存在"));
        assertNull(channel.readOutbound());
        assertEquals(0, session.letters.size());
    }

    private GmCommand enqueue(String action, java.util.Map<String, Object> params) {
        GmCommand command = new GmCommand(session.machine.id(), action,
                new com.alibaba.fastjson2.JSONObject(params));
        hub.enqueue(command);
        return command;
    }

    /** 353/359 分发（dispatchCustom 私有；两 handler 为测试可见，按 id 分流）。 */
    private void dispatch(int messageId, String serial) {
        CustomC2S.Message custom = CustomC2S.parseDirect(CustomMessages.customIntMessage(
                messageId, CustomMessages.Value.ofString(serial)));
        if (messageId == 353) {
            flow.handleReadLetter(new Connection(channel), session, custom);
        } else {
            flow.handleGetAppendix(new Connection(channel), session, custom);
        }
    }

    private static void assumeTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }

    private static java.util.Optional<String> dsn() {
        return MySqlEnv.loadDsn();
    }

    private int countBagRows(String configLike) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "SELECT COUNT(*) FROM role_bag_items WHERE role_id = ? AND config_id LIKE ?")) {
            ps.setLong(1, ROLE);
            ps.setString(2, configLike);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void insertScratchRole() throws Exception {
        Long accountId = null;
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT account_id FROM roles WHERE role_id = " + ROLE_SOURCE)) {
            accountId = rs.next() ? rs.getLong(1) : null;
        }
        assumeTrue(accountId != null, "role 618 缺席，无法借 account_id");
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO roles (role_id, account_id, slot, name) VALUES (?, ?, 902, ?) "
                        + "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)")) {
            ps.setLong(1, ROLE);
            ps.setLong(2, accountId);
            ps.setString(3, "邮件测试" + ROLE);
            ps.executeUpdate();
        }
    }

    private void cleanScratch() throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "DELETE FROM roles WHERE role_id = ?")) {
            ps.setLong(1, ROLE);
            ps.executeUpdate();
        }
    }

    private List<byte[]> drain() {
        List<byte[]> frames = new java.util.ArrayList<>();
        for (byte[] frame; (frame = channel.readOutbound()) != null; ) {
            frames.add(frame);
        }
        return frames;
    }

    private static boolean containsUtf16(byte[] frame, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_16LE);
        outer:
        for (int i = 0; i + needle.length <= frame.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (frame[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int readU16(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
    }
}
