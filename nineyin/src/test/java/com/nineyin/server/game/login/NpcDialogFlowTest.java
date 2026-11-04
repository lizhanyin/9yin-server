package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.server.world.fight.FightState;
import com.nineyin.server.world.fight.SkillCastHandlerFlowTest;
import com.nineyin.server.world.npc.NpcResolver;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.scene.SceneLifecycle;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 四期 NPC 交互接线：0x07 两段式（FuncID=0 点击 / 非 0 菜单 mark）→
 * 0x10 LastObject + 0x1C 对话菜单 → optiontalk 分发 → 商店 View61 帧组
 * （0x16/0x15/0x18）→ msg70 购买（碎银扣款 + 入包 + 0x10 碎银帧）。
 * 资源基准 9yin_server1（真链：FuncNpc00009 钱渊来 → DialogID=69 →
 * shop|biaoti00007|Shop_special_002，商品 taskitem_01040 李白精选集 @500）。
 */
class NpcDialogFlowTest {

    private static final Path SERVER_ROOT = Path.of("E:/Workspace/project/game/9yin_server1");
    /** 与 MoveItemFlowTest 990032 错开的专用临时角色。 */
    private static final long BUY_ROLE = 990_033L;
    private static final long ROLE_SOURCE = 618L;

    @BeforeAll
    static void needsSharedResources() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SERVER_ROOT.resolve(
                        Path.of("resources", "modern", "share", "rule", "optiontalk_npc.ini"))),
                "optiontalk 资源不可用，跳过 NPC 交互测试");
        Assumptions.assumeTrue(Files.isRegularFile(SERVER_ROOT.resolve(
                        Path.of("resources", "modern", "share", "item", "tool_item.ini"))),
                "tool_item.ini 不可用，跳过 NPC 交互测试");
        com.nineyin.util.ServerPaths.overrideServerRoot(SERVER_ROOT);
    }

    /** 不跑定时任务的调度器（对话/开商店路径无 tick 依赖）。 */
    static final class NoopScheduler extends SkillCastHandlerFlowTest.LoginFlowTestSchedulerAdapter {

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay,
                                                                java.util.concurrent.TimeUnit unit) {
            return null;
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long period, java.util.concurrent.TimeUnit unit) {
            return null;
        }
    }

    private static final class Wiring {

        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final LoginFlow flow;
        final LoginSession session = new LoginSession();

        Wiring() throws Exception {
            this(null);
        }

        Wiring(RoleInventoryStore store) throws Exception {
            java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("npc-dialog");
            flow = new LoginFlow(new RoleStoreFacade(JsonRoleRepository.open(temp.resolve("roles.json"))),
                    FacultyStore.open(temp.resolve("faculty.json")), new GmHub(),
                    Clock.systemUTC(), null, new NoopScheduler(), store, null);
            session.player = PlayerActor.create("对话测试", 99999, Clock.systemUTC());
            session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                    store == null ? 990_031L : BUY_ROLE, 0, "对话测试", false, 1, 1,
                    new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                    new com.nineyin.server.db.role.Roles.Location(
                            new com.nineyin.server.db.role.Roles.Scene("born04", "born04"),
                            new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
            session.world = new SceneLifecycle(connection, new NoopScheduler());
            session.world.begin("ini\\scene\\born04", "born04");
        }

        void registerDialogNpc(int objectId, String configId) {
            NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                    "wiring-" + configId, configId, "commonnpc", null);
            resolved.properties.put("Name", PropertyValues.Value.ofWideString(configId));
            NpcSpawn npc = new NpcSpawn(resolved, 700f, 0f, 700f, 0f);
            session.world.registerNpc(objectId, npc.cloneSpawn());
            List<NpcSpawn> catalog = new ArrayList<>(session.sceneNpcs);
            while (catalog.size() < objectId - 1) {
                catalog.add(null); // 目录下标 = 对象号-2 补位
            }
            catalog.add(objectId - 2, npc);
            session.sceneNpcs = java.util.Collections.unmodifiableList(catalog);
            session.activeNpcs.add(objectId - 2);
        }

        void click(int objectId, int funcId) {
            byte[] frame = new byte[33];
            frame[0] = 0x07;
            writeU32(frame, 17, 1);
            writeU32(frame, 21, objectId);
            writeU32(frame, 25, 1);
            writeU32(frame, 29, funcId);
            assertTrue(flow.dispatch(connection, session, frame));
        }

        void sendCustom70(String shopId, int page, int pos, int count) {
            PacketWriter writer = PacketWriter.ofCapacity(48);
            writer.writeU8(0x1E).writeU16(5);
            writer.writeU8(2).writeU32(70);
            byte[] id = shopId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writer.writeU8(6).writeU32(id.length + 1).writeBytes(id).writeU8(0);
            writer.writeU8(2).writeU32(page);
            writer.writeU8(2).writeU32(pos);
            writer.writeU8(2).writeU32(count);
            assertTrue(flow.dispatch(connection, session, writer.toBytes()));
        }

        List<byte[]> drain() {
            List<byte[]> frames = new ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    /** 解析 0x1C：返回每项 {mark, text}。 */
    private static List<int[]> menuMarks(byte[] frame, List<String> texts) {
        assertEquals(0x1C, frame[0] & 0xFF);
        int count = u16(frame, 9);
        List<int[]> marks = new ArrayList<>();
        int offset = 11;
        for (int i = 0; i < count; i++) {
            int type = frame[offset] & 0xFF;
            int mark = u16(frame, offset + 1);
            int byteLen = u32(frame, offset + 3);
            String text = new String(frame, offset + 7, byteLen - 2,
                    java.nio.charset.StandardCharsets.UTF_16LE);
            marks.add(new int[]{type, mark});
            texts.add(text);
            offset += 7 + byteLen;
        }
        return marks;
    }

    /** 解码 0x15/0x18 视图属性帧（按期望线型顺序：s=str, i=int32；indexBase=首属性号）。 */
    private static List<Object> viewProps(byte[] frame, String kinds, int indexBase) {
        int offset = 7; // op + viewId + row/cap + count
        List<Object> values = new ArrayList<>();
        for (char kind : kinds.toCharArray()) {
            int index = u16(frame, offset);
            offset += 2;
            assertEquals(indexBase + values.size(), index, "属性号顺序");
            if (kind == 's') {
                int len = u32(frame, offset);
                offset += 4;
                values.add(new String(frame, offset, len - 1,
                        java.nio.charset.StandardCharsets.UTF_8));
                offset += len;
            } else {
                values.add(u32(frame, offset));
                offset += 4;
            }
        }
        return values;
    }

    @Test
    void clickOpensDialogMenuWithStoryContinueAndShopOption() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerDialogNpc(2, "FuncNpc00009");
        wiring.click(2, 0);

        List<byte[]> frames = wiring.drain();
        assertEquals(2, frames.size(), "LastObject + 菜单两帧");
        assertEquals(0x10, frames.get(0)[0] & 0xFF);
        assertEquals(414, u16(frames.get(0), 12), "LastObject idx414（玩家域）");
        assertEquals(2, u32(frames.get(0), 14), "选中 NPC 对象号");

        List<String> texts = new ArrayList<>();
        List<int[]> marks = menuMarks(frames.get(1), texts);
        assertEquals(4, marks.size(), "双剧情链 + shop 选项 + 告辞");
        assertEquals(2, u32(frames.get(1), 1), "菜单绑定 NPC 对象");
        assertEquals(1, u32(frames.get(1), 5), "owner");
        assertEquals(List.of("biaot00002", "biaot00003", "biaoti00007", "ui_form_talk_leave"),
                texts, "节点 69：下页 MenuID×2 + shop 台词 + 告辞");
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, marks.get(i)[1], "mark 从 1 顺排");
            assertEquals(0, marks.get(i)[0], "type 字节统一 0（真机待验）");
        }
    }

    @Test
    void storyMarkOpensNextPageMenu() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerDialogNpc(2, "FuncNpc00009");
        wiring.click(2, 0);
        List<byte[]> first = wiring.drain();
        List<String> firstTexts = new ArrayList<>();
        List<int[]> firstMarks = menuMarks(first.get(1), firstTexts);
        int firstMenuLastMark = firstMarks.getLast()[1];

        wiring.click(2, 1); // mark1 → NextPage(70)
        List<byte[]> frames = wiring.drain();
        assertEquals(1, frames.size(), "仅下一页菜单");
        List<String> texts = new ArrayList<>();
        List<int[]> marks = menuMarks(frames.get(0), texts);
        assertTrue(texts.size() >= 3, "节点 70：双 task 选项（+可交任务项）+ 告辞");
        assertEquals("title_10199", texts.get(0));
        assertEquals("title_10200", texts.get(1));
        assertEquals("ui_form_talk_leave", texts.getLast(), "告辞恒为末项");
        int expected = firstMenuLastMark + 1;
        for (int[] mark : marks) {
            assertEquals(expected++, mark[1], "翻页后 mark 在对话级计数器上续排");
        }
    }

    @Test
    void shopOptionOpensView61Frames() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerDialogNpc(2, "FuncNpc00009");
        wiring.click(2, 0);
        wiring.drain();

        wiring.click(2, 3); // mark3 → shop|biaoti00007|Shop_special_002
        List<byte[]> frames = wiring.drain();
        assertEquals(4, frames.size(), "0x1D + 0x16 + 0x15 + 单商品 0x18");
        assertEquals(0x1D, frames.get(0)[0] & 0xFF);
        assertEquals(1, frames.get(0).length, "on_clear_menu 整帧 1 字节");

        byte[] clear = frames.get(1);
        assertEquals(0x16, clear[0] & 0xFF);
        assertEquals(3, clear.length, "ServerDeleteView 恰 3 字节（layout_proven）");
        assertEquals(61, u16(clear, 1), "清 View61");

        byte[] create = frames.get(2);
        assertEquals(0x15, create[0] & 0xFF);
        assertEquals(61, u16(create, 1));
        assertEquals(100, u16(create, 3), "容量 100（F 服抓包）");
        assertEquals(4, u16(create, 5), "头四属性 101-104");
        assertEquals(List.of("Shop_special_002", 10, 1, 0),
                viewProps(create, "siii", 101),
                "ShopID/PageNum=10/PageCount/ShopType=0（抓包口径）");

        byte[] goods = frames.get(3);
        assertEquals(0x18, goods[0] & 0xFF);
        assertEquals(61, u16(goods, 1));
        assertEquals(1, u16(goods, 3), "首行行号 1");
        assertEquals(6, u16(goods, 5), "行六属性 105-110");
        assertEquals(List.of("taskitem_01040", 1, 0, 0, 500, 1),
                viewProps(goods, "siiiii", 105),
                "ConfigID/Amount/SellPrice0-2/MaxAmount=物品堆叠（tool_item.ini MaxAmount=1）");
    }

    @Test
    void leaveAndUnknownMarksClearMenu() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerDialogNpc(2, "FuncNpc00009");
        wiring.click(2, 0);
        wiring.drain();

        wiring.click(2, 4); // mark4 → 告辞
        List<byte[]> frames = wiring.drain();
        assertEquals(1, frames.size());
        assertEquals(0x1D, frames.get(0)[0] & 0xFF);

        wiring.click(2, 99); // 过期/未知 mark
        frames = wiring.drain();
        assertEquals(1, frames.size(), "未知 mark 也只清菜单");
        assertEquals(0x1D, frames.get(0)[0] & 0xFF);
    }

    @Test
    void npcWithoutDialogOnlyRepliesLastObject() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerDialogNpc(2, "FuncNpc99999");
        wiring.click(2, 0);
        List<byte[]> frames = wiring.drain();
        assertEquals(1, frames.size(), "无对话数据：只回选中帧不开菜单");
        assertEquals(0x10, frames.get(0)[0] & 0xFF);
    }

    @Test
    void combatNpcStillRoutesToFightSelection() throws Exception {
        Wiring wiring = new Wiring();
        wiring.session.fight = new FightState();
        NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                "wiring-mon-1", "monv030101", "attacknpc", null);
        resolved.properties.put("MaxHP", PropertyValues.Value.ofInt32(1000));
        resolved.properties.put("HP", PropertyValues.Value.ofInt32(1000));
        resolved.properties.put("Resource", PropertyValues.Value.ofString("monv030101"));
        wiring.session.world.registerNpc(2, new NpcSpawn(resolved, 700f, 0f, 700f, 0f));
        wiring.session.sceneNpcs = List.of(new NpcSpawn(resolved, 700f, 0f, 700f, 0f));
        wiring.session.activeNpcs.add(0);

        wiring.click(2, 0);
        List<byte[]> frames = wiring.drain();
        assertEquals(1, frames.size(), "combat 路径不变：仅 LastObject");
        assertEquals(0x10, frames.get(0)[0] & 0xFF);
        assertTrue(wiring.session.fight.livingSelectedTarget(wiring.session.world) != null,
                "选中已记录");
    }

    /** msg70 购买全链（需 MySQL）：开店 → 买 1 件 → 背包成对帧 + 碎银扣减 + DB 行。 */
    @Test
    void buyItemViaMsg70() throws Exception {
        assumeTrue(MySqlEnv.loadDsn().isPresent(), "MySQL DSN 不可用，跳过购买全链");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$")
                .matcher(MySqlEnv.loadDsn().orElseThrow());
        assumeTrue(m.matches(), "DSN 形态不符");
        try (java.sql.Connection jdbc = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2))) {
            assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过购买全链");
            insertScratchRole(jdbc);
            try (RoleInventoryStore store = RoleInventoryStore.open(MySqlEnv.loadDsn().orElseThrow())) {
                Wiring wiring = new Wiring(store);
                wiring.registerDialogNpc(2, "FuncNpc00009");
                wiring.click(2, 0);
                wiring.drain();
                wiring.click(2, 3);
                wiring.drain();
                assertEquals("Shop_special_002", wiring.session.dialog.openShopId(), "开店态就绪");

                wiring.sendCustom70("Shop_special_002", 0, 1, 1);
                List<byte[]> frames = wiring.drain();
                assertEquals(99499, wiring.session.player.silver(),
                        "碎银 99999-500（taskitem_01040 SellPrice2=500）");

                List<Integer> ops = frames.stream().map(f -> f[0] & 0xFF).toList();
                assertTrue(ops.contains(0x19), "背包摘帧: " + ops);
                assertTrue(ops.contains(0x18), "背包落帧: " + ops);
                assertEquals(0x10, ops.getLast(), "末帧碎银刷新");
                boolean bagRowCarriesItem = frames.stream()
                        .filter(f -> (f[0] & 0xFF) == 0x18)
                        .anyMatch(f -> containsUtf8(f, "taskitem_01040"));
                assertTrue(bagRowCarriesItem, "背包行携带商品 ConfigID");

                try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                        "SELECT COUNT(*) FROM role_bag_items WHERE role_id = ? AND config_id = ?")) {
                    ps.setLong(1, BUY_ROLE);
                    ps.setString(2, "taskitem_01040");
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next() && rs.getInt(1) >= 1, "DB 背包行已落");
                    }
                }
            } finally {
                cleanScratch(jdbc);
            }
        }
    }

    private static void insertScratchRole(java.sql.Connection jdbc) throws Exception {
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
            ps.setLong(1, BUY_ROLE);
            ps.setLong(2, accountId);
            ps.setString(3, "对话购买测试" + BUY_ROLE);
            ps.executeUpdate();
        }
    }

    private static void cleanScratch(java.sql.Connection jdbc) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "DELETE FROM roles WHERE role_id = ?")) {
            ps.setLong(1, BUY_ROLE);
            ps.executeUpdate();
        }
    }

    private static boolean containsUtf8(byte[] frame, String needle) {
        byte[] bytes = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= frame.length - bytes.length; i++) {
            for (int j = 0; j < bytes.length; j++) {
                if (frame[i + j] != bytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    static void writeU32(byte[] frame, int off, int value) {
        frame[off] = (byte) value;
        frame[off + 1] = (byte) (value >>> 8);
        frame[off + 2] = (byte) (value >>> 16);
        frame[off + 3] = (byte) (value >>> 24);
    }

    static int u16(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
    }

    static int u32(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8
                | (frame[off + 2] & 0xFF) << 16 | (frame[off + 3] & 0xFF) << 24;
    }
}
