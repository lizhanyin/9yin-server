package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.nineyin.server.game.item.SkillBookCatalog;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * msg31 秘籍使用全链（script=SkillBook/SkillPage/SkillScroll）：真实 MySQL +
 * go-server1 share（tool_item.ini LearnSkillPack + item_learn_skill_static.ini
 * TragetID + rule/condition.ini 门槛），专用临时角色 990035 测后清理。验：
 * 套路书学会（role_skills 落库 + View40 整视图重开帧组）→ 书消耗；全已学
 * 重复使用不消耗；第X页上限门（cap∈[min,max]）先拒后放；内功篇章书建书带上限。
 */
class SkillBookFlowTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final long ROLE = 990_035L;
    private static final long ROLE_SOURCE = 618L;
    /** 瞬劈直砍(单刀)：ItemType=23 LearnSkillPack=3837 → CS_base_pgd01（game_skills wuxue 行，无分层字段）。 */
    private static final String WUXUE_BOOK = "book_CS_base_pgd01";
    private static final String WUXUE_TARGET = "CS_base_pgd01";
    /** 梵心降魔第二页：SkillMaxLevel=2 UseCondition=12193（CS_em_jdmz01 上限须=1）。 */
    private static final String PAGE2_BOOK = "book_CS_em_jdmz0102";
    private static final String PAGE_TARGET = "CS_em_jdmz01";
    /** 晦明日月鉴(入门)：SkillMaxLevel=9 无门槛 → ng_jh_219 建书上限 9。 */
    private static final String NEIGONG_ENTRY_BOOK = "ng_book_jh_219_rm";
    private static final String NEIGONG_TARGET = "ng_jh_219";

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private RoleInventoryStore store;
    private java.sql.Connection jdbc;
    private Path previousRoot;
    private boolean levelStoreExisted;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "item", "tool_item.ini"))),
                "go-server1 share 资源不可用，跳过秘籍使用全链");
        assumeTrue(dsn().isPresent(), "MySQL DSN 不可用，跳过秘籍使用全链");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn().orElseThrow());
        org.junit.jupiter.api.Assumptions.assumeTrue(m.matches(), "DSN 形态不符");
        jdbc = DriverManager.getConnection(
                "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                        + "?useSSL=false&connectTimeout=3000&socketTimeout=3000&allowPublicKeyRetrieval=true",
                m.group(1), m.group(2));
        org.junit.jupiter.api.Assumptions.assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过秘籍使用全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(dsn().orElseThrow());
        levelStoreExisted = Files.isRegularFile(
                GO_ROOT.resolve(Path.of("data", "武学等级.json")));

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("秘籍测试", (int) ROLE, Clock.systemUTC());
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "秘籍测试", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
        // 模拟登录授予后的已学基线（五件套首技能）与行号表。
        session.learnedSkills.put("zs_normal_02", 1);
        session.skillRows.put("zs_normal_02", 1);
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
        // 武学等级档（data/武学等级.json 在 GO_ROOT）：移除本测试角色写入的档值，
        // 文件本不存在则删（skillLevelStore 只存 >1 级，页书测试会写 2 级档）。
        Path levelStore = GO_ROOT.resolve(Path.of("data", "武学等级.json"));
        if (Files.isRegularFile(levelStore)) {
            String text = Files.readString(levelStore, java.nio.charset.StandardCharsets.UTF_8);
            com.alibaba.fastjson2.JSONObject document = com.alibaba.fastjson2.JSON.parseObject(text);
            com.alibaba.fastjson2.JSONObject roles = document.getJSONObject("roles");
            if (roles != null) {
                roles.remove(String.valueOf(ROLE));
                if (roles.isEmpty()) {
                    document.remove("roles");
                }
            }
            if (levelStoreExisted || !document.isEmpty()) {
                Files.writeString(levelStore, document.toJSONString(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } else if (!levelStoreExisted) {
                Files.deleteIfExists(levelStore);
            }
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

    /** 临时角色行（借 role 618 的 account_id；slot=903 避开 990033/990036 唯一键）。 */
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
            ps.setString(3, "秘籍测试" + ROLE);
            ps.executeUpdate();
        }
    }

    private void cleanScratch() throws Exception {
        // role_skills 无 roles 外键（不随 CASCADE 清理），显式删行。
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "DELETE FROM role_skills WHERE role_id = ?")) {
            ps.setLong(1, ROLE);
            ps.executeUpdate();
        }
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "DELETE FROM roles WHERE role_id = ?")) {
            ps.setLong(1, ROLE);
            ps.executeUpdate();
        }
    }

    /** 背包 slot=1 放 1 本秘籍（script=SkillBook，item_type=23, view_id=1 → 通用页）。 */
    private void insertBookRow(String configId, int itemType) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
                        + "view_id, name, script) VALUES (?, 1, 1, ?, ?, 1, 1, '', 'SkillBook')")) {
            ps.setLong(1, ROLE);
            ps.setString(2, configId);
            ps.setInt(3, itemType);
            ps.executeUpdate();
        }
    }

    private int countBagRows(String configId) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "SELECT COUNT(*) FROM role_bag_items WHERE role_id = ? AND config_id = ?")) {
            ps.setLong(1, ROLE);
            ps.setString(2, configId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int countSkillRows(String skillId) throws Exception {
        try (java.sql.PreparedStatement ps = jdbc.prepareStatement(
                "SELECT COUNT(*) FROM role_skills WHERE role_id = ? AND skill_id = ?")) {
            ps.setLong(1, ROLE);
            ps.setString(2, skillId);
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

    private static long countOp(List<byte[]> frames, int op) {
        return frames.stream().filter(f -> f.length > 0 && (f[0] & 0xFF) == op).count();
    }

    @Test
    void wuxueBookLearnsSkillAndConsumesBook() throws Exception {
        assumeTrue(SkillBookCatalog.loadDefault().resolve(WUXUE_BOOK) != null,
                "share 缺 SkillBook 目录数据，先跑数据步");
        insertBookRow(WUXUE_BOOK, 23);
        useSlot1();
        List<byte[]> frames = drain();
        // View40 整视图重开：0x16 删视图 + 0x15 容器 + ≥2 行 0x18（旧基线 1 行 + 新学 1 行）
        assertTrue(countOp(frames, 0x16) >= 1, "expected view delete, got ops="
                + frames.stream().map(f -> String.format("0x%02X", f[0] & 0xFF)).toList());
        assertTrue(countOp(frames, 0x15) >= 1, "expected view create");
        assertTrue(countOp(frames, 0x18) >= 2, "expected 2+ view rows (baseline + learned)");
        // 已学表/行号表追加（基线 1 行 → 新学接 2 号行）
        assertEquals(2, session.skillRows.getOrDefault(WUXUE_TARGET, 0).intValue());
        assertEquals(1, session.learnedSkills.getOrDefault(WUXUE_TARGET, 0).intValue());
        // role_skills 落库 + 书消耗
        assertEquals(1, countSkillRows(WUXUE_TARGET));
        assertEquals(0, countBagRows(WUXUE_BOOK));
    }

    @Test
    void alreadyLearnedBookDoesNotConsume() throws Exception {
        assumeTrue(SkillBookCatalog.loadDefault().resolve(WUXUE_BOOK) != null,
                "share 缺 SkillBook 目录数据，先跑数据步");
        session.learnedSkills.put(WUXUE_TARGET, 1);
        session.skillRows.put(WUXUE_TARGET, 2);
        insertBookRow(WUXUE_BOOK, 23);
        useSlot1();
        List<byte[]> frames = drain();
        // 全部目标已学：无视图重开帧、书保留
        assertEquals(0, countOp(frames, 0x16));
        assertEquals(1, countBagRows(WUXUE_BOOK));
    }

    @Test
    void unknownScriptStillFallsBackToUseChain() throws Exception {
        // 未知 config + 无 itemType 映射（9999）：维持 use-chain not implemented 口径，不消耗。
        // （注：书类 ItemType 21/23 等在 item_use_skill_static 有 yaoshihp 撞型映射，
        // 走 useItemEffect 会被当回血药消耗——SkillBook 分支必须在 useItemEffect 之前。）
        insertBookRow("not_a_book_xyz", 9999);
        useSlot1();
        assertEquals(1, countBagRows("not_a_book_xyz"));
        assertEquals(0, countSkillRows(WUXUE_TARGET));
        assertFalse(session.learnedSkills.containsKey(WUXUE_TARGET));
    }

    /** 第X页上限门（UseCondition=12193：CS_em_jdmz01 修炼上限须=1）：未学先拒（不消耗），学到 1 层后放行直升 2 层。 */
    @Test
    void pageBookGatesOnTrainedCapThenUnlocks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                SkillBookCatalog.loadDefault().resolve(PAGE2_BOOK) != null
                        && SkillBookCatalog.loadDefault().resolve(PAGE2_BOOK).useCondition() == 12193,
                "share 缺第二页书/门槛数据，先跑数据步");
        insertBookRow(PAGE2_BOOK, 2017);
        // ① 未学（cap=0 ∉ [1,1]）：拒绝且不消耗、无视图帧
        useSlot1();
        List<byte[]> frames = drain();
        assertEquals(0, countOp(frames, 0x16));
        assertEquals(1, countBagRows(PAGE2_BOOK));
        assertEquals(0, countSkillRows(PAGE_TARGET));
        // ② 已学第一页（cap=1）：放行 → 直升 2 层 + 落库 + 消耗
        session.learnedSkills.put(PAGE_TARGET, 1);
        session.skillRows.put(PAGE_TARGET, 2);
        useSlot1();
        frames = drain();
        assertTrue(countOp(frames, 0x16) >= 1, "expected view delete after page2 learn");
        assertEquals(2, session.learnedSkills.getOrDefault(PAGE_TARGET, 0).intValue());
        assertEquals(1, countSkillRows(PAGE_TARGET));
        assertEquals(0, countBagRows(PAGE2_BOOK));
        // ③ 重复用第二页（cap=2 ∉ [1,1]）：门槛拒绝不再消耗
        session.learnedSkills.put(PAGE_TARGET, 2);
        useSlot1();
        assertEquals(0, countOp(drain(), 0x16));
    }

    /** 内功篇章书：入门篇（SkillMaxLevel=9 无门槛）建书=上限 9（不是 varprop 全档）；starter 已有的内功（cap 36）用进阶篇被门槛拒。 */
    @Test
    void neigongBookLearnsWithCapAndGateBlocks() throws Exception {
        var def = com.nineyin.server.game.avatar.neigong.NeiGongCatalog.load()
                .book(NEIGONG_TARGET);
        org.junit.jupiter.api.Assumptions.assumeTrue(def != null,
                "neigong 目录缺 " + NEIGONG_TARGET + "，先跑数据步");
        insertBookRow(NEIGONG_ENTRY_BOOK, 21);
        useSlot1();
        List<byte[]> frames = drain();
        assertTrue(countOp(frames, 0x16) >= 1, "expected View43 rebuild frames");
        var learned = session.player.progress().book(NEIGONG_TARGET);
        assertNotNull(learned, "内功书应已收录");
        assertEquals(9, learned.maxLevel, "入门篇建书上限=9（非 varprop 全档）");
        assertEquals(1, learned.level);
        assertEquals(0, countBagRows(NEIGONG_ENTRY_BOOK));
        // starter 种子内功 ng_em_001（cap=36）用「进阶第1篇」（cond 上限须=9）→ 门槛拒
        org.junit.jupiter.api.Assumptions.assumeTrue(
                SkillBookCatalog.loadDefault().resolve("ng_book_em_00110") != null,
                "share 缺气桩功进阶第1篇");
        insertBookRow("ng_book_em_00110", 21);
        useSlot1();
        assertEquals(0, countOp(drain(), 0x16));
        assertEquals(1, countBagRows("ng_book_em_00110"));
        assertEquals(36, session.player.progress().book("ng_em_001").maxLevel,
                "种子内功上限不被篇章书破坏");
    }
}
