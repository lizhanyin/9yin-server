package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.game.avatar.equipment.ToolItemCatalog;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM grant_item 全链（真实 MySQL + 参考服 share）：入包行+成对帧、立即穿戴帧序、
 * **换装让位**（目标槽被占 → 旧装备 swapEquipSlot 回收进装备背包，不再覆盖丢失）、
 * 双槽饰品（耳环 11/12）优先空槽、不可穿戴 wearNow 降级入包。
 * 专用临时角色 990036 测后清理（GenerateEquipFlowTest 同方言；帧布局由
 * InventoryMessagesTest 覆盖，此处验帧序与落库状态）。
 */
class GrantItemFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_036L;
    private static final long ROLE_SOURCE = 618L;

    private final GmHub hub = new GmHub();
    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private RoleInventoryStore store;
    private java.sql.Connection jdbc;
    private Path previousRoot;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "item", "equipment.ini"))),
                "参考服 share 资源不可用，跳过道具发放全链");
        assumeTrue(MySqlEnv.loadDsn().isPresent(), "MySQL DSN 不可用，跳过道具发放全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$")
                .matcher(MySqlEnv.loadDsn().orElseThrow());
        assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过道具发放全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(MySqlEnv.loadDsn().orElseThrow());

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("发放测试", (int) ROLE, Clock.systemUTC());
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.name = "发放测试";
        hub.attach(snapshot.sessionId, snapshot);
        session.visual = com.nineyin.server.game.avatar.RoleVisual.resolve(
                List.of(), EquipmentResolver.load(
                        GO_ROOT.resolve(Path.of("resources", "modern", "share", "item"))));
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "发放测试", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
        flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null, store, null);
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

    /** wearNow=0：入包（role_bag_items 行 + 数量/页别 + 0x19/0x18 成对帧）。 */
    @Test
    void grantToBagInsertsRowAndPushesPairedFrames() {
        ToolItemCatalog.ItemInfo tool = firstNonWearable();
        assumeTrue(tool != null, "目录缺不可穿戴道具");
        GmCommand command = enqueue("grant_item",
                Map.of("itemConfig", tool.configId(), "amount", 5, "wearNow", 0));
        drain();

        assertTrue(reply(command).ok(), reply(command).message());
        assertTrue(reply(command).message().contains("已发放"), reply(command).message());
        RoleInventoryStore.BagItem row = bagRow(tool.configId());
        assertNotNull(row, "应落 role_bag_items");
        assertEquals(5, row.amount(), "数量按 amount 入库");
        int expectView = tool.viewId() > 0 ? tool.viewId() : 1;
        assertEquals(expectView, row.viewId(), "页别按 ViewID");
        List<Byte> opcodes = drainOpcodes();
        assertEquals((byte) 0x19, opcodes.get(0), "首帧清格");
        assertEquals((byte) 0x18, opcodes.get(1), "次帧背包行");
    }

    /** wearNow=1 穿衣帧序 + 换装让位：旧衣服回收进装备背包，新衣服占槽 3，旧件不再消失。 */
    @Test
    void wearNowWearsClothThenSwapRecyclesOld() {
        ToolItemCatalog.ItemInfo cloth1 = firstByEquipType("cloth");
        assumeTrue(cloth1 != null, "参考服缺 cloth 模板");
        ToolItemCatalog.ItemInfo cloth2 = nextByEquipType("cloth", cloth1.configId());
        assumeTrue(cloth2 != null, "参考服缺第二件 cloth 模板");

        GmCommand first = enqueue("grant_item",
                Map.of("itemConfig", cloth1.configId(), "amount", 1, "wearNow", 1));
        drain();
        assertTrue(reply(first).ok(), reply(first).message());
        assertTrue(reply(first).message().contains("已穿戴"), reply(first).message());
        RoleInventoryStore.Equip worn = equipAtSlot(3);
        assertNotNull(worn, "衣服应穿进槽 3");
        assertEquals(cloth1.configId(), worn.configId());
        assertNull(bagRow(cloth1.configId()), "立即穿戴不应同时入包");
        // pushWearFrames 帧序：0x19 移除纸娃娃旧行 → 0x18 装备行 → … → 0x10 vital。
        List<Byte> opcodes = drainOpcodes();
        assertEquals((byte) 0x19, opcodes.get(0), "首帧移除纸娃娃旧行");
        assertEquals((byte) 0x18, opcodes.get(1), "次帧装备行");
        assertEquals((byte) 0x10, opcodes.get(opcodes.size() - 1), "末帧属性同步");

        GmCommand second = enqueue("grant_item",
                Map.of("itemConfig", cloth2.configId(), "amount", 1, "wearNow", 1));
        drain();
        assertTrue(reply(second).ok(), reply(second).message());
        assertTrue(reply(second).message().contains("已回收至背包第"),
                "回执应注明旧装回收: " + reply(second).message());
        worn = equipAtSlot(3);
        assertNotNull(worn);
        assertEquals(cloth2.configId(), worn.configId(), "槽 3 应为新衣服");
        RoleInventoryStore.BagItem landed = bagRow(cloth1.configId());
        assertNotNull(landed, "旧衣服应回收进背包（此前版本会被覆盖丢失）");
        assertEquals(2, landed.viewId(), "旧装落装备页");
        assertEquals(1, landed.amount());
    }

    /** 双槽饰品：第二只耳环进空槽 12，不覆盖槽 11 的首只。 */
    @Test
    void dualSlotEarringsFillBothEars() {
        ToolItemCatalog.ItemInfo earring = firstByEquipType("earring");
        assumeTrue(earring != null, "参考服缺耳环模板");
        GmCommand one = enqueue("grant_item",
                Map.of("itemConfig", earring.configId(), "amount", 1, "wearNow", 1));
        drain();
        assertTrue(reply(one).ok(), reply(one).message());
        assertNotNull(equipAtSlot(11), "首只耳环应进槽 11");

        GmCommand two = enqueue("grant_item",
                Map.of("itemConfig", earring.configId(), "amount", 1, "wearNow", 1));
        drain();
        assertTrue(reply(two).ok(), reply(two).message());
        assertEquals(earring.configId(), equipAtSlot(11).configId(), "槽 11 首只不应被覆盖");
        assertNotNull(equipAtSlot(12), "第二只耳环应进空槽 12（此前版本恒覆盖首槽）");
    }

    /** 不可穿戴 wearNow=1：降级入包（回执「已发放」），不产生穿戴行。 */
    @Test
    void nonWearableWearNowDegradesToBag() {
        ToolItemCatalog.ItemInfo tool = firstNonWearable();
        assumeTrue(tool != null, "目录缺不可穿戴道具");
        GmCommand command = enqueue("grant_item",
                Map.of("itemConfig", tool.configId(), "amount", 1, "wearNow", 1));
        drain();
        assertTrue(reply(command).ok(), reply(command).message());
        assertTrue(reply(command).message().contains("已发放"),
                "不可穿戴应降级入包: " + reply(command).message());
        assertNotNull(bagRow(tool.configId()));
        assertTrue(store.equips(ROLE).isEmpty(), "不应有任何穿戴行");
    }

    // ------------------------------------------------------------- 夹具/helpers

    private GmCommand enqueue(String action, Map<String, Object> params) {
        GmCommand command = new GmCommand(session.machine.id(), action,
                new com.alibaba.fastjson2.JSONObject(params));
        hub.enqueue(command);
        return command;
    }

    private void drain() {
        flow.drainGmCommands(new Connection(channel), session);
    }

    private static GmCommand.Reply reply(GmCommand command) {
        return command.awaitReply(Duration.ofMillis(1000));
    }

    private static ToolItemCatalog.ItemInfo firstByEquipType(String equipType) {
        for (ToolItemCatalog.ItemInfo info : ToolItemCatalog.loadDefault().all()) {
            if (equipType.equalsIgnoreCase(info.equipType())) {
                return info;
            }
        }
        return null;
    }

    private static ToolItemCatalog.ItemInfo nextByEquipType(String equipType, String skipConfig) {
        for (ToolItemCatalog.ItemInfo info : ToolItemCatalog.loadDefault().all()) {
            if (equipType.equalsIgnoreCase(info.equipType())
                    && !info.configId().equals(skipConfig)) {
                return info;
            }
        }
        return null;
    }

    private static ToolItemCatalog.ItemInfo firstNonWearable() {
        for (ToolItemCatalog.ItemInfo info : ToolItemCatalog.loadDefault().all()) {
            if (info.equipType() == null || info.equipType().isEmpty()) {
                return info;
            }
        }
        return null;
    }

    private RoleInventoryStore.Equip equipAtSlot(int slot) {
        for (RoleInventoryStore.Equip equip : store.equips(ROLE)) {
            if (equip.slot() == slot) {
                return equip;
            }
        }
        return null;
    }

    private RoleInventoryStore.BagItem bagRow(String configId) {
        for (RoleInventoryStore.BagItem item : store.bagItems(ROLE)) {
            if (item.configId().equals(configId)) {
                return item;
            }
        }
        return null;
    }

    private List<Byte> drainOpcodes() {
        List<Byte> opcodes = new ArrayList<>();
        for (byte[] frame; (frame = channel.readOutbound()) != null; ) {
            opcodes.add(frame[0]);
        }
        return opcodes;
    }

    /** 临时角色行（role_bag_items 有 roles 外键；借 role 618 的 account_id，slot=904 避开唯一键）。 */
    private void insertScratchRole() throws Exception {
        Long accountId = null;
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT account_id FROM roles WHERE role_id = " + ROLE_SOURCE)) {
            accountId = rs.next() ? rs.getLong(1) : null;
        }
        assumeTrue(accountId != null, "role 618 缺席，无法借 account_id");
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO roles (role_id, account_id, slot, name) VALUES (?, ?, 904, ?) "
                        + "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id)")) {
            ps.setLong(1, ROLE);
            ps.setLong(2, accountId);
            ps.setString(3, "发放测试" + ROLE);
            ps.executeUpdate();
        }
    }

    private void cleanScratch() throws Exception {
        // roles 行删除按外键 ON DELETE CASCADE 连带清 role_bag_items/role_equip_items。
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "DELETE FROM roles WHERE role_id = ?")) {
            ps.setLong(1, ROLE);
            ps.executeUpdate();
        }
    }
}
