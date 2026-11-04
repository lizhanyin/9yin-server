package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * msg31 使用效果全链（宝箱开包 INI 回退路径）：真实 MySQL + go-server1 share
 * （tool_item.ini 的 Box_qycdu_009=CompensateItem 双 ref + drops_mvp.json 的
 * qy_chengdu_009/box_qycdu_009 权重包），专用临时角色 990033 测后清理。
 * 验：锦盒消耗（行删除）→ 掉落物入包（DB 行 + 0x18 帧）→ 重复使用拒绝。
 */
class ItemUseFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_033L;
    private static final long ROLE_SOURCE = 618L;
    private static final String BOX = "Box_qycdu_009";

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private RoleInventoryStore store;
    private java.sql.Connection jdbc;
    private Path previousRoot;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "item", "tool_item.ini"))),
                "go-server1 share 资源不可用，跳过使用效果全链");
        assumeTrue(dsn().isPresent(), "MySQL DSN 不可用，跳过使用效果全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn().orElseThrow());
        org.junit.jupiter.api.Assumptions.assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        org.junit.jupiter.api.Assumptions.assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过使用效果全链");
        cleanScratch();
        insertScratchRole();
        insertBoxRow();
        store = RoleInventoryStore.open(dsn().orElseThrow());

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("开箱测试", (int) ROLE, Clock.systemUTC());
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "开箱测试", false, 1, 1,
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

    private static void assumeTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }

    private static java.util.Optional<String> dsn() {
        return MySqlEnv.loadDsn();
    }

    /** 背包 slot=1 放 1 个 Box_qycdu_009（item_type=2159, view_id=1 → 通用页）。 */
    private void insertBoxRow() throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                        + "view_id, name, script) VALUES (?, 1, 1, ?, 2159, 1, 1, '', '')")) {
            ps.setLong(1, ROLE);
            ps.setString(2, BOX);
            ps.executeUpdate();
        }
    }

    /** 临时角色行（借 role 618 的 account_id；slot=901 避开 990032 的 900 唯一键）。 */
    private void insertScratchRole() throws Exception {
        Long accountId = null;
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT account_id FROM roles WHERE role_id = " + ROLE_SOURCE)) {
            accountId = rs.next() ? rs.getLong(1) : null;
        }
        assumeTrue(accountId != null, "role 618 缺席，无法借 account_id");
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO roles (role_id, account_id, slot, name) VALUES (?, ?, 901, ?) "
                        + "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)")) {
            ps.setLong(1, ROLE);
            ps.setLong(2, accountId);
            ps.setString(3, "开箱测试" + ROLE);
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

    private List<byte[]> drain() {
        List<byte[]> frames = new java.util.ArrayList<>();
        for (byte[] frame; (frame = channel.readOutbound()) != null; ) {
            frames.add(frame);
        }
        return frames;
    }

    private void useSlot1() {
        CustomMessages.Value[] args = {
                CustomMessages.Value.ofInt32(InventoryMessages.bagViewOf(1)),
                CustomMessages.Value.ofInt32(1),
                CustomMessages.Value.ofInt32(1),
        };
        byte[] payload = CustomMessages.customIntMessage(31, args);
        CustomC2S.Message custom = CustomC2S.parseDirect(payload);
        flow.handleBodyItemUse(new Connection(channel), session, custom);
    }

    private static long countOp18(List<byte[]> frames) {
        return frames.stream().filter(f -> f.length > 0 && (f[0] & 0xFF) == 0x18).count();
    }

    @Test
    void boxOpenGrantsLootAndConsumesBox() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ItemUseCatalogPacksPresent(), "drops_mvp.json 缺 qy/box 包，先跑数据步");
        assertEquals(1, countBagRows(BOX));
        useSlot1();
        List<byte[]> frames = drain();
        // 掉落物入包：至少一条 0x18 ViewAdd（yaopin 行）
        assertTrue(countOp18(frames) >= 1, "expected granted bag row frames, got " + frames.size());
        // 锦盒消耗（amount=1 → 行删除）
        assertEquals(0, countBagRows(BOX));
        // 掉落物落库
        assertTrue(countBagRows("yaopin_%") >= 1, "expected yaopin loot rows in DB");
        // 二次使用（行已空/节流内）：不再产生 0x18
        useSlot1();
        assertEquals(0, countOp18(drain()));
        assertEquals(0, countBagRows(BOX));
    }

    /**
     * 2026-11-04 真机"使用无效果"根因回归：DropID 引用包不在 drops_mvp.json（16.9k 缺口）
     * 时旧逻辑 roll 空返 → 静默不消耗零反馈。现缺包走 FALLBACK_LADDER 兜底开包。
     */
    @Test
    void missingDropPackFallsBackToLadderAndStillOpens() throws Exception {
        // 临时角色背包换成缺包箱（mpwjb 同款：DropID=comd_drop_box_20220401_mpwjb_fc_001 无包体）。
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "UPDATE role_bag_items SET config_id = 'box_20220401_mpwjb_fc_001', item_type = 2102 "
                        + "WHERE role_id = ?")) {
            ps.setLong(1, ROLE);
            ps.executeUpdate();
        }
        useSlot1();
        List<byte[]> frames = drain();
        assertTrue(countOp18(frames) >= 1, "缺包箱兜底开包：掉落物 0x18 行帧");
        assertEquals(0, countBagRows("box_20220401_mpwjb_fc_001"), "缺包箱照常消耗");
        assertTrue(countBagRows("yaopin_%") >= 1, "兜底阶梯掉落 yaopin");
    }

    private boolean ItemUseCatalogPacksPresent() {
        com.nineyin.server.game.item.DropPacks packs =
                com.nineyin.server.game.item.DropPacks.loadDefault();
        return packs.has("qy_chengdu_009") && packs.has("box_qycdu_009");
    }
}
