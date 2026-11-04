package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.server.world.npc.ShopCatalog;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 礼包商人货架全链：合成节 gm_giftbox（真正可开的礼包，免费）→ openShop View61
 * 头/行帧 → msg70 零价购买入包。真实 MySQL + go-server1 share，临时角色 990035
 * 测后清理。
 */
class GiftShopFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_035L;
    private static final long ROLE_SOURCE = 618L;

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
                "go-server1 share 资源不可用，跳过礼包货架全链");
        assumeTrue(dsn().isPresent(), "MySQL DSN 不可用，跳过礼包货架全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn().orElseThrow());
        org.junit.jupiter.api.Assumptions.assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        org.junit.jupiter.api.Assumptions.assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过礼包货架全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(dsn().orElseThrow());

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("货架测试", (int) ROLE, Clock.systemUTC());
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "货架测试", false, 1, 1,
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

    @Test
    void giftBoxSectionListsOnlyOpenableBoxesSortedAndFree() {
        ShopCatalog.ShopSection section = flow.shopSection(LoginFlow.GIFT_SHOP_ID);
        assumeTrue(section != null && !section.goods().isEmpty(),
                "drops_mvp.json 无可开礼包，先跑数据步");
        assertTrue(section.goods().size() >= 20, "参考服 drops_mvp 应有数十个可开礼包，实际 "
                + section.goods().size());
        List<String> ids = section.goods().stream().map(ShopCatalog.GoodsRow::itemId).toList();
        assertEquals(ids.stream().sorted().toList(), ids, "按 configId 排序");
        for (ShopCatalog.GoodsRow row : section.goods()) {
            assertEquals(0, row.sellPrice2(), "货架免费（SellPrice2=0）: " + row.itemId());
        }
        assertEquals(Math.max(1, (section.goods().size() + 9) / 10), section.pageCount(),
                "页数=ceil(商品/10)");
        // 每件商品都能在静态目录里解析（购买链 lookup 的前置）
        for (int i = 0; i < Math.min(5, section.goods().size()); i++) {
            String id = section.goods().get(i).itemId();
            assertTrue(com.nineyin.server.game.avatar.equipment.ToolItemCatalog
                    .loadDefault().lookup(id) != null, "商品可解析: " + id);
        }
    }

    @Test
    void openShopPushesView61HeaderAndGoodsRows() {
        ShopCatalog.ShopSection section = flow.shopSection(LoginFlow.GIFT_SHOP_ID);
        assumeTrue(section != null && !section.goods().isEmpty(), "无可开礼包，跳过帧断言");
        flow.openShop(new Connection(channel), session, LoginFlow.GIFT_SHOP_ID);
        List<byte[]> frames = drain();
        assertTrue(frames.size() >= 3, "至少 deleteView+createView+首商品行");
        assertEquals(0x16, frames.get(0)[0] & 0xFF, "先删旧 View61");
        assertEquals(0x15, frames.get(1)[0] & 0xFF, "再建新 View61（带 ShopID/PageNum/PageCount）");
        long goodsRows = frames.stream().filter(f -> (f[0] & 0xFF) == 0x18).count();
        assertEquals(section.goods().size(), goodsRows, "每商品一行 ViewAdd");
        assertEquals(LoginFlow.GIFT_SHOP_ID, session.dialog.openShopId(),
                "购买链路由态登记");
    }

    @Test
    void buyGiftBoxFreeOfChargeGrantsBagRow() throws Exception {
        ShopCatalog.ShopSection section = flow.shopSection(LoginFlow.GIFT_SHOP_ID);
        assumeTrue(section != null && !section.goods().isEmpty(), "无可开礼包，跳过购买断言");
        flow.openShop(new Connection(channel), session, LoginFlow.GIFT_SHOP_ID);
        drain();
        // 买第 1 页第 1 行 x2（0 价 → 不扣碎银）
        CustomC2S.Message buy = CustomC2S.parseDirect(CustomMessages.customIntMessage(70,
                CustomMessages.Value.ofString(LoginFlow.GIFT_SHOP_ID),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofInt32(1),
                CustomMessages.Value.ofInt32(2)));
        long silverBefore = session.player.silver();
        flow.handleBuyItem(new Connection(channel), session, buy);
        List<byte[]> frames = drain();
        assertTrue(frames.stream().anyMatch(f -> (f[0] & 0xFF) == 0x18),
                "购买入包写 ViewAdd 行帧");
        String first = section.goods().get(0).itemId();
        assertTrue(countBagRows(first) >= 1, "首商品落库: " + first);
        assertEquals(silverBefore, session.player.silver(), "零价购买不扣碎银");
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
                "INSERT INTO roles (role_id, account_id, slot, name) VALUES (?, ?, 903, ?) "
                        + "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)")) {
            ps.setLong(1, ROLE);
            ps.setLong(2, accountId);
            ps.setString(3, "货架测试" + ROLE);
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
}
