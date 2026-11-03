package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.nineyin.server.game.avatar.equipment.ToolItemCatalog;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 装备穿/脱全链（custom 30 移动 / 31 右键使用 / 886 一键穿）：真实 MySQL +
 * go-server1 share 资源（equipment.ini 的 EquipType 解析），专用临时角色 990032
 * 测后清理。帧方言（0x18/0x19 布局）由 InventoryMessagesTest 覆盖，此处验
 * 帧序存在性 + DB 行迁移 + 拒绝路径。
 */
class MoveItemFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_032L;
    private static final long ROLE_SOURCE = 618L;

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private RoleInventoryStore store;
    private java.sql.Connection jdbc;
    private Path previousRoot;
    private String weaponConfig;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "item", "equipment.ini"))),
                "go-server1 share 资源不可用，跳过穿脱全链");
        assumeTrue(dsn().isPresent(), "MySQL DSN 不可用，跳过穿脱全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn().orElseThrow());
        assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过穿脱全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(dsn().orElseThrow());
        weaponConfig = firstWeaponConfig();
        assumeTrue(weaponConfig != null, "game_items 无 Weapon 行");
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(weaponConfig);
        assumeTrue(info != null && "Weapon".equals(info.equipType()),
                "equipment.ini 解析不到 Weapon EquipType");

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("穿脱测试", (int) ROLE, Clock.systemUTC());
        session.visual = com.nineyin.server.game.avatar.RoleVisual.resolve(
                List.of(), EquipmentResolver.load(
                        GO_ROOT.resolve(Path.of("resources", "modern", "share", "item"))));
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "穿脱测试", false, 1, 1,
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

    private String firstWeaponConfig() throws Exception {
        try (java.sql.Statement statement = jdbc.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT item_id FROM game_items WHERE equip_type = 'Weapon' LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void insertBagRow(int slot) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                        + "view_id, name, script) VALUES (?, 1, ?, ?, 0, 1, 2, '', '')")) {
            ps.setLong(1, ROLE);
            ps.setInt(2, slot);
            ps.setString(3, weaponConfig);
            ps.executeUpdate();
        }
    }

    /** 临时角色行（role_bag_items 有 roles 外键；借 role 618 的 account_id，slot=900
     * 避开 uq_roles_account_slot 唯一键——slot=0 会撞既有行被 ON DUPLICATE 吞成空更新）。 */
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
            ps.setString(3, "穿脱测试" + ROLE);
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

    private int equipSlotInDb() throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "SELECT slot FROM role_equip_items WHERE role_id = ? AND slot = 22")) {
            ps.setLong(1, ROLE);
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

    private void send(int messageId, Object... values) {
        byte[] payload;
        if (messageId <= 764) {
            CustomMessages.Value[] args = new CustomMessages.Value[values.length];
            for (int i = 0; i < values.length; i++) {
                args[i] = values[i] instanceof String text
                        ? CustomMessages.Value.ofString(text)
                        : CustomMessages.Value.ofInt32((Integer) values[i]);
            }
            payload = CustomMessages.customIntMessage(messageId, args);
        } else {
            payload = rawCustomInt(messageId, values);
        }
        CustomC2S.Message custom = CustomC2S.parseDirect(payload);
        switch (messageId) {
            case 30 -> flow.handleMoveItem(new Connection(channel), session, custom);
            case 31 -> flow.handleBodyItemUse(new Connection(channel), session, custom);
            case 886 -> flow.handleOnestepEquip(new Connection(channel), session, custom);
            default -> throw new IllegalArgumentException("unsupported messageId " + messageId);
        }
    }

    /**
     * 886-889 一键穿的 C2S 载荷（同 CustomMessages 编码但不设 [1,764] S2C 域校验
     * ——该域是客户端 S2C 数字路由注册表，C2S 一键穿实测 886 越过它）。仅支持
     * int32/string 两种值（本测试用不到其他）。
     */
    private static byte[] rawCustomInt(int messageId, Object... values) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x1E);
        int count = values.length + 1;
        out.write(count & 0xFF);
        out.write(count >> 8 & 0xFF);
        out.write(2);
        for (int i = 0; i < 4; i++) {
            out.write(messageId >> (8 * i) & 0xFF);
        }
        for (Object value : values) {
            if (value instanceof String text) {
                byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(6);
                for (int i = 0; i < 4; i++) {
                    out.write((data.length + 1) >> (8 * i) & 0xFF);
                }
                out.writeBytes(data);
                out.write(0);
            } else {
                out.write(2);
                int v = (Integer) value;
                for (int i = 0; i < 4; i++) {
                    out.write(v >> (8 * i) & 0xFF);
                }
            }
        }
        return out.toByteArray();
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
    void wearUnequipRightClickAndOnestepChain() throws Exception {
        // --- msg30 穿上：[30,121,3,1,22] ---
        insertBagRow(3);
        send(30, 121, 3, 1, 22);
        List<byte[]> worn = drain();
        assertEquals(22, equipSlotInDb(), "穿上后 DB 槽 22 有行");
        assertTrue(hasViewFrame(worn, 0x18, 1, 22), "0x18 纸娃娃行（view1 index22）");
        assertTrue(hasViewFrame(worn, 0x19, 121, 3), "源格 0x19 清位");
        assertTrue(worn.stream().anyMatch(f -> op(f) == 0x10), "外观/词缀 0x10 帧随穿推送");

        // --- msg30 卸下：[30,1,22,121,0]（0=自动选格） ---
        send(30, 1, 22, 121, 0);
        List<byte[]> off = drain();
        assertEquals(-1, equipSlotInDb(), "卸下后 DB 槽 22 无行");
        assertTrue(hasViewFrame(off, 0x19, 1, 22), "纸娃娃 0x19 清位");
        assertTrue(off.stream().anyMatch(f -> op(f) == 0x18 && u16(f, 1) == 121),
                "背包 0x18 落位装备页");
        assertTrue(store.bagItems(ROLE).stream().anyMatch(
                item -> item.viewId() == 2 && item.slot() > 0), "背包 DB 出现装备页行");

        // --- msg31 右键穿：[31,121,slot,1]（恰好 4 值，count≥1） ---
        int bagSlot = store.bagItems(ROLE).stream()
                .filter(item -> item.viewId() == 2).findFirst().orElseThrow().slot();
        send(31, 121, bagSlot, 1);
        List<byte[]> rightClick = drain();
        assertEquals(22, equipSlotInDb(), "右键穿自动槽 22");
        assertTrue(hasViewFrame(rightClick, 0x18, 1, 22), "右键穿 0x18 纸娃娃行");

        // --- 换装让位：第二把武器穿同槽，旧武器落装备页首空格 ---
        insertBagRow(4);
        send(30, 121, 4, 1, 22);
        List<byte[]> swap = drain();
        assertEquals(22, equipSlotInDb(), "同槽换装后仍槽 22");
        assertEquals(1, store.equips(ROLE).size(), "同槽只一件");
        assertTrue(store.bagItems(ROLE).stream().anyMatch(item -> item.slot() == 1),
                "旧武器让位装备页格 1");
        assertTrue(hasViewFrame(swap, 0x18, 121, 1), "让位行 0x18 装备页格 1");

        // --- msg886 一键穿：uid 认领背包行（先卸下再一键穿回） ---
        send(30, 1, 22, 121, 0);
        drain();
        bagSlot = store.bagItems(ROLE).stream()
                .filter(item -> item.viewId() == 2 && item.slot() != 1).findFirst().orElseThrow().slot();
        String uid = InventoryMessages.uniqueId(ROLE, "bag", bagSlot);
        send(886, uid);
        List<byte[]> onestep = drain();
        assertEquals(22, equipSlotInDb(), "一键穿回槽 22");
        assertTrue(hasViewFrame(onestep, 0x18, 1, 22), "一键穿 0x18 纸娃娃行");

        // uid 未命中拒绝（无帧无 DB 变化）
        int equipsBefore = store.equips(ROLE).size();
        send(886, "deadbeefdeadbeef");
        assertTrue(drain().isEmpty(), "未知 uid 无帧");
        assertEquals(equipsBefore, store.equips(ROLE).size());
    }

    @Test
    void malformedPayloadsRejected() throws Exception {
        insertBagRow(3);
        // 30 参数不足（<4）
        send(30, 121, 3);
        assertTrue(drain().isEmpty(), "30 参数不足无帧");
        assertEquals(-1, equipSlotInDb(), "未穿戴");

        // 31 值数不符（3 值 / 5 值）与 count=0 均拒（menu2 `reject malformed native item use`）
        send(31, 121, 3);
        assertTrue(drain().isEmpty(), "31 三值拒绝");
        send(31, 121, 3, 0);
        assertTrue(drain().isEmpty(), "31 count=0 拒绝");
        send(31, 121, 3, 1, 9);
        assertTrue(drain().isEmpty(), "31 五值拒绝");
        assertEquals(-1, equipSlotInDb(), "31 拒绝路径不穿戴");

        // 空装备槽卸下：无帧无 DB 变化
        send(30, 1, 22, 121, 0);
        assertTrue(drain().isEmpty(), "空装备槽卸下无帧");
        assertEquals(0, store.equips(ROLE).size(), "装备栏始终为空");
        assertEquals(1, store.bagItems(ROLE).size(), "背包行未被误删");
    }
}
