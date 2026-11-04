package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 道具丢弃全链（custom 34 custom_delete_item）：真实 MySQL + 临时角色 990033
 * 测后清理。覆盖部分丢弃堆叠刷新（0x19+0x18+0x19 镜像）、全量丢删行（双 0x19）、
 * 拒绝路径（非背包页/空格/超量/参数数不符）与跨页同 slot 隔离
 * （consumeBagItem 带 view_id 维度前的回归隐患）。
 */
class DeleteItemFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_033L;
    private static final long ROLE_SOURCE = 618L;
    /** DB view_id=2（装备页，客户端 view 121）；view 1=工具页（客户端 view 2）。 */
    private static final int VIEW_EQUIP = InventoryMessages.VIEW_EQUIP_BAG;
    private static final int VIEW_TOOL = InventoryMessages.VIEW_TOOL_PAGE;

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private RoleInventoryStore store;
    private java.sql.Connection jdbc;
    private Path previousRoot;
    private String goodsConfig;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "item", "equipment.ini"))),
                "go-server1 share 资源不可用，跳过丢弃全链");
        assumeTrue(dsn().isPresent(), "MySQL DSN 不可用，跳过丢弃全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn().orElseThrow());
        assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过丢弃全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(dsn().orElseThrow());
        goodsConfig = firstGoodsConfig();
        assumeTrue(goodsConfig != null, "game_items 无可用道具行");

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("丢弃测试", (int) ROLE, Clock.systemUTC());
        session.visual = com.nineyin.server.game.avatar.RoleVisual.resolve(
                List.of(), EquipmentResolver.load(
                        GO_ROOT.resolve(Path.of("resources", "modern", "share", "item"))));
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "丢弃测试", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
        flow = new LoginFlow(null, null, new GmHub(), Clock.systemUTC(), null, null, store, null);
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
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    private static java.util.Optional<String> dsn() {
        return MySqlEnv.loadDsn();
    }

    private String firstGoodsConfig() throws Exception {
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT item_id FROM game_items LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int seq = 1;

    private void insertBagRow(int slot, int viewId, int amount) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                        + "view_id, name, script) VALUES (?, ?, ?, ?, 0, ?, ?, '', '')")) {
            ps.setLong(1, ROLE);
            ps.setInt(2, seq++);
            ps.setInt(3, slot);
            ps.setString(4, goodsConfig);
            ps.setInt(5, amount);
            ps.setInt(6, viewId);
            ps.executeUpdate();
        }
    }

    /** 临时角色行（借 role 618 的 account_id，slot=900 避开 uq_roles_account_slot）。 */
    private void insertScratchRole() throws Exception {
        Long accountId = null;
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT account_id FROM roles WHERE role_id = " + ROLE_SOURCE)) {
            accountId = rs.next() ? rs.getLong(1) : null;
        }
        assumeTrue(accountId != null, "role 618 缺席，无法借 account_id");
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO roles (role_id, account_id, slot, name) VALUES (?, ?, 900, ?) "
                        + "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)")) {
            ps.setLong(1, ROLE);
            ps.setLong(2, accountId);
            ps.setString(3, "丢弃测试" + ROLE);
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

    private int amountInDb(int slot, int viewId) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "SELECT amount FROM role_bag_items WHERE role_id = ? AND slot = ? AND view_id = ?")) {
            ps.setLong(1, ROLE);
            ps.setInt(2, slot);
            ps.setInt(3, viewId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private List<byte[]> drain() {
        List<byte[]> frames = new java.util.ArrayList<>();
        for (byte[] frame; (frame = channel.readOutbound()) != null; ) {
            frames.add(frame);
        }
        return frames;
    }

    private void send(Object... values) {
        CustomMessages.Value[] args = new CustomMessages.Value[values.length];
        for (int i = 0; i < values.length; i++) {
            args[i] = CustomMessages.Value.ofInt32((Integer) values[i]);
        }
        CustomC2S.Message custom = CustomC2S.parseDirect(
                CustomMessages.customIntMessage(34, args));
        flow.handleDeleteItem(new Connection(channel), session, custom);
    }

    private static int op(byte[] frame) {
        return frame[0] & 0xFF;
    }

    private static int u16(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
    }

    private static boolean hasViewFrame(List<byte[]> frames, int op, int view, int index) {
        for (byte[] frame : frames) {
            if (op(frame) == op && u16(frame, 1) == view && u16(frame, 3) == index) {
                return true;
            }
        }
        return false;
    }

    @Test
    void partialThenFullDiscardChain() throws Exception {
        insertBagRow(5, 2, 5);
        int mirror = InventoryMessages.bagMirrorOf(VIEW_EQUIP);

        // --- 部分丢弃：[34,121,5,2] 堆叠 5→3，帧=0x19 清位+0x18 重推+0x19 镜像 ---
        send(VIEW_EQUIP, 5, 2);
        List<byte[]> partial = drain();
        assertEquals(3, amountInDb(5, 2), "堆叠 5 丢 2 剩 3");
        assertTrue(hasViewFrame(partial, 0x19, VIEW_EQUIP, 5), "0x19 清位（view121 index5）");
        assertTrue(hasViewFrame(partial, 0x18, VIEW_EQUIP, 5), "0x18 重推行（数量 3）");
        assertTrue(hasViewFrame(partial, 0x19, mirror, 5), "0x19 镜像清位");

        // --- 全量丢弃：[34,121,5,3] 剩余 3 全丢 → 删行双 0x19，无 0x18 ---
        send(VIEW_EQUIP, 5, 3);
        List<byte[]> full = drain();
        assertEquals(-1, amountInDb(5, 2), "行删除");
        assertTrue(hasViewFrame(full, 0x19, VIEW_EQUIP, 5), "0x19 删行");
        assertTrue(hasViewFrame(full, 0x19, mirror, 5), "0x19 镜像删行");
        assertFalse(full.stream().anyMatch(f -> op(f) == 0x18), "全删无 0x18 重推");
    }

    @Test
    void crossPageSameSlotIsolated() throws Exception {
        insertBagRow(7, 1, 4);
        insertBagRow(7, 2, 9);

        // 丢装备页（view 121）slot7 的 2 个：工具页（view 2）同 slot 行必须不动
        send(VIEW_EQUIP, 7, 2);
        List<byte[]> frames = drain();
        assertEquals(7, amountInDb(7, 2), "装备页 9 丢 2 剩 7");
        assertEquals(4, amountInDb(7, 1), "工具页同 slot 行未被误扣");
        assertTrue(hasViewFrame(frames, 0x19, VIEW_EQUIP, 7), "清位帧落在装备页");
        assertFalse(hasViewFrame(frames, 0x19, VIEW_TOOL, 7), "工具页无帧");
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        insertBagRow(3, 2, 2);

        // 非背包页（view 1 纸娃娃）
        send(1, 3, 1);
        assertTrue(drain().isEmpty(), "纸娃娃页拒绝");
        assertEquals(2, amountInDb(3, 2));

        // 空格
        send(VIEW_EQUIP, 9, 1);
        assertTrue(drain().isEmpty(), "空格拒绝");

        // 超量（amount > 堆叠）
        send(VIEW_EQUIP, 3, 3);
        assertTrue(drain().isEmpty(), "超量拒绝");
        assertEquals(2, amountInDb(3, 2), "堆叠未动");

        // amount=0
        send(VIEW_EQUIP, 3, 0);
        assertTrue(drain().isEmpty(), "amount=0 拒绝");

        // 参数数不符（2 值 / 4 值）
        send(VIEW_EQUIP, 3);
        assertTrue(drain().isEmpty(), "两值拒绝");
        send(VIEW_EQUIP, 3, 1, 9);
        assertTrue(drain().isEmpty(), "四值拒绝");
        assertEquals(2, amountInDb(3, 2), "拒绝路径行未动");

        // 扩展页归一：view 122（扩展装备页）等价主分类页 121 可丢
        send(122, 3, 2);
        List<byte[]> ext = drain();
        assertEquals(-1, amountInDb(3, 2), "扩展页全丢删行");
        assertTrue(hasViewFrame(ext, 0x19, VIEW_EQUIP, 3), "清位帧落主页 121");
    }
}
