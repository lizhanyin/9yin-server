package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.nineyin.server.game.avatar.equipment.RecastCatalog;
import com.nineyin.server.game.avatar.equipment.ToolItemCatalog;
import com.nineyin.server.game.avatar.panel.EquipAffixCatalog;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.util.MySqlEnv;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 模板生成三动作全链（真实 MySQL + 参考服 share）：模板取 60501 家族基座、
 * 武器词条全来自 senior 官方洗练池（厨师 6/铁匠 3+3）、防具与饰品固定 5 条红词条
 * （外防+46/+47、体魄+29、内息+39、臂力+35）、护腕=3 臂力+3 招式（按身上武器）。
 * 专用临时角色 990033 测后清理（MoveItemFlowTest 同方言；帧布局由
 * InventoryMessagesTest 覆盖，此处验帧序）。
 */
class GenerateEquipFlowTest {

    private static final Path GO_ROOT = Path.of("E:/Workspace/project/game/9yin-go-server1");
    private static final long ROLE = 990_033L;
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
                "参考服 share 资源不可用，跳过模板生成全链");
        assumeTrue(MySqlEnv.loadDsn().isPresent(), "MySQL DSN 不可用，跳过模板生成全链");
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
        assumeTrue(jdbc.isValid(2), "MySQL 不可达，跳过模板生成全链");
        cleanScratch();
        insertScratchRole();
        store = RoleInventoryStore.open(MySqlEnv.loadDsn().orElseThrow());

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("生成测试", (int) ROLE, Clock.systemUTC());
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.name = "生成测试";
        hub.attach(snapshot.sessionId, snapshot);
        session.visual = com.nineyin.server.game.avatar.RoleVisual.resolve(
                List.of(), EquipmentResolver.load(
                        GO_ROOT.resolve(Path.of("resources", "modern", "share", "item"))));
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                ROLE, 0, "生成测试", false, 1, 1,
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

    /** 厨师武器：60501 家族基座 + 6 条词条全部来自 senior 官方洗练池 + 穿戴帧序。 */
    @Test
    void weaponCookGenerateUsesFamilyBaseAndOfficialPool() {
        GmCommand cook = enqueue("gm_generate_weapon", Map.of("weaponType", 102, "crafter", "cook"));
        drain();

        assertTrue(reply(cook).ok(), reply(cook).message());
        assertTrue(reply(cook).message().contains("_60501"), "模板应取 60501 家族: " + reply(cook).message());
        RoleInventoryStore.Equip weapon = equipByType("Weapon");
        assertNotNull(weapon, "武器应已穿戴落库");
        assertTrue(weapon.configId().endsWith("_60501"), "60501 家族基座: " + weapon.configId());
        List<Integer> packs = LoginFlow.parsePropBuiltPacks(weapon.propBuilt());
        assertEquals(6, packs.size(), "厨师 6 条词条: " + weapon.propBuilt());
        RecastCatalog recast = RecastCatalog.load();
        for (int pack : packs) {
            assertTrue(recast.classify(pack) != RecastCatalog.PoolKind.UNKNOWN,
                    "词条包 " + pack + " 应来自 senior 官方洗练池");
        }
        // 穿戴帧序（pushWearFrames）：0x19 移除旧行 → 0x18 装备行 → … → 0x10 vital。
        List<Byte> opcodes = drainOpcodes();
        assertEquals((byte) 0x19, opcodes.get(0), "首帧移除纸娃娃旧行");
        assertEquals((byte) 0x18, opcodes.get(1), "次帧装备行");
        assertEquals((byte) 0x10, opcodes.get(opcodes.size() - 1), "末帧属性同步");
    }

    /** 铁匠武器：3 属性 + 3 招式共 6 条，同样全部来自官方池。 */
    @Test
    void weaponSmithGenerateCombinesAttrAndSkillPools() {
        GmCommand smith = enqueue("gm_generate_weapon", Map.of("weaponType", 102, "crafter", "smith"));
        drain();

        assertTrue(reply(smith).ok(), reply(smith).message());
        RoleInventoryStore.Equip weapon = equipByType("Weapon");
        assertNotNull(weapon);
        assertTrue(weapon.configId().endsWith("_60501"));
        assertEquals(6, LoginFlow.parsePropBuiltPacks(weapon.propBuilt()).size(),
                "铁匠 3 属性 + 3 招式: " + weapon.propBuilt());
    }

    /** 防具：固定 5 条红词条，属性行恰覆盖外防+46/+47、体魄+29、内息+39、臂力+35。 */
    @Test
    void armorGenerateUsesFixedRedAffixes() {
        GmCommand cloth = enqueue("gm_generate_armor", Map.of("armorSlot", "cloth"));
        drain();

        assertTrue(reply(cloth).ok(), reply(cloth).message());
        RoleInventoryStore.Equip armor = equipByType("Cloth");
        assertNotNull(armor, "衣服应已穿戴落库");
        assertTrue(armor.configId().endsWith("_60501"), "60501 家族基座: " + armor.configId());
        List<Integer> packs = LoginFlow.parsePropBuiltPacks(armor.propBuilt());
        assertEquals(5, packs.size(), "固定 5 条红词条: " + armor.propBuilt());
        List<String> got = new ArrayList<>();
        for (int pack : packs) {
            for (EquipAffixCatalog.Mod mod : EquipAffixCatalog.load().modsOf(pack)) {
                got.add(mod.prop() + "+" + mod.value());
            }
        }
        for (String expected : List.of("PhyDefAdd+46", "PhyDefAdd+47", "StaAdd+29",
                "MaxMPAdd+39", "StrAdd+35")) {
            assertTrue(got.contains(expected), "缺红词条 " + expected + ": " + got);
        }
    }

    /** 项链（饰品也有穿戴槽）：与防具同款固定 5 条红词条直接穿戴。 */
    @Test
    void amuletGenerateWearsWithRedAffixes() {
        GmCommand amulet = enqueue("gm_generate_armor", Map.of("armorSlot", "amulet"));
        drain();

        assertTrue(reply(amulet).ok(), reply(amulet).message());
        assertTrue(reply(amulet).message().contains("已生成并穿戴"), reply(amulet).message());
        RoleInventoryStore.Equip necklace = equipByType("Necklace");
        assertNotNull(necklace, "项链应已穿戴落库");
        assertEquals(5, LoginFlow.parsePropBuiltPacks(necklace.propBuilt()).size(),
                "饰品同款 5 条红词条: " + necklace.propBuilt());
        List<Byte> opcodes = drainOpcodes();
        assertEquals((byte) 0x19, opcodes.get(0), "首帧移除纸娃娃旧行");
        assertEquals((byte) 0x18, opcodes.get(1), "次帧装备行");
        assertEquals((byte) 0x10, opcodes.get(opcodes.size() - 1), "末帧属性同步");
    }

    /** 护腕：3 条臂力（+33/+35/+35）+ 3 条招式词条（来自身上武器的官方池）。 */
    @Test
    void bracerGenerateCombinesStrAndWeaponPoolAffixes() {
        String weapon = "sword_xiyu_60501";
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(weapon);
        assumeTrue(info != null, "参考服缺 " + weapon + " 模板");
        store.upsertEquip(ROLE, new RoleInventoryStore.Equip(
                weapon, info.itemType(), info.equipType(),
                com.nineyin.server.game.avatar.appearance.AppearanceSync.slotOfEquipType(info.equipType()),
                100, 100, info.artPack(), ""));
        GmCommand bracer = enqueue("gm_generate_bracer", Map.of());
        drain();

        assertTrue(reply(bracer).ok(), reply(bracer).message());
        RoleInventoryStore.Equip wrist = equipByType("Wrist");
        assertNotNull(wrist, "护腕应已穿戴落库");
        assertTrue(wrist.configId().endsWith("_60501"), "60501 家族基座: " + wrist.configId());
        List<Integer> packs = LoginFlow.parsePropBuiltPacks(wrist.propBuilt());
        assertEquals(6, packs.size(), "3 臂力 + 3 招式: " + wrist.propBuilt());

        RecastCatalog recast = RecastCatalog.load();
        List<Integer> skillPool = recast.weaponPool(weapon);
        List<Integer> strValues = new ArrayList<>();
        int skillCount = 0;
        for (int pack : packs) {
            if (skillPool.contains(pack)) {
                skillCount++;
                continue;
            }
            for (EquipAffixCatalog.Mod mod : EquipAffixCatalog.load().modsOf(pack)) {
                if (mod.prop().equals("StrAdd")) {
                    strValues.add(mod.value());
                }
            }
        }
        assertEquals(3, skillCount, "3 条招式词条应来自武器官方池: " + wrist.propBuilt());
        strValues.sort(null);
        assertEquals(List.of(33, 35, 35), strValues, "臂力 +33/+35/+35");
    }

    /** 未穿武器生成护腕：臂力 3 条保留，招式词条省略并回执注明。 */
    @Test
    void bracerWithoutWeaponOmitsSkillAffixes() {
        GmCommand bracer = enqueue("gm_generate_bracer", Map.of());
        drain();

        assertTrue(reply(bracer).ok(), reply(bracer).message());
        assertTrue(reply(bracer).message().contains("未穿武器"), reply(bracer).message());
        RoleInventoryStore.Equip wrist = equipByType("Wrist");
        assertNotNull(wrist);
        assertEquals(3, LoginFlow.parsePropBuiltPacks(wrist.propBuilt()).size(),
                "仅 3 条臂力词条: " + wrist.propBuilt());
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

    private RoleInventoryStore.Equip equipByType(String equipType) {
        for (RoleInventoryStore.Equip equip : store.equips(ROLE)) {
            if (equipType.equals(equip.equipType())) {
                return equip;
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

    /** 临时角色行（role_bag_items 有 roles 外键；借 role 618 的 account_id，slot=900 避开唯一键）。 */
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
            ps.setString(3, "生成测试" + ROLE);
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
