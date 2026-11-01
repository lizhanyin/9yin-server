package com.nineyin.server.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.util.ServerPaths;
import com.nineyin.server.db.role.RoleStoreFacade;

/** 与 Go 侧 golden_dump_test.go 相同固定输入的逐字节一致性验证。 */
class ProtocolGoldenTest {

    private static final LocalDateTime GOLDEN_NOW = LocalDateTime.of(2026, 1, 2, 15, 4, 5);
    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    private static final List<String> CUSTOM_APPEARANCE = List.of(
            "book1", "icon\\players\\boy01_create.png",
            "333333333333333333333333333333333333333333X38\u0001", "",
            "obj\\char\\b_hair\\b_hair2", "cloth_b0002", "pants_b0002", "shoes_b0002",
            "force_wudang");

    private static EquipmentResolver equipment() {
        Assumptions.assumeTrue(Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/item")),
                "Go 仓库 share 资源不可用，跳过外观解析相关 golden");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
        return EquipmentResolver.loadDefault();
    }

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = ProtocolGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void noRoleLoginSuccess() throws Exception {
        assertArrayEquals(golden("login_no_role.bin"), LoginMessages.noRoleLoginSuccess(GOLDEN_NOW));
    }

    @Test
    void oneRoleLoginDefaultAppearance() throws Exception {
        List<String> appearance = RoleStoreFacade.defaultRoleAppearance(null);
        RoleVisual visual = RoleVisual.resolve(appearance, equipment());
        assertArrayEquals(golden("login_one_role_default.bin"),
                LoginMessages.oneRoleLoginSuccess(GOLDEN_NOW, "花无缺", appearance, "force_yihua",
                        visual.hair(), visual.cloth(), visual.pants(), visual.shoes()));
    }

    @Test
    void oneRoleLoginCustomAppearance() throws Exception {
        RoleVisual visual = RoleVisual.resolve(CUSTOM_APPEARANCE, equipment());
        assertArrayEquals(golden("login_one_role_custom.bin"),
                LoginMessages.oneRoleLoginSuccess(GOLDEN_NOW, "李逍遥", CUSTOM_APPEARANCE, "force_wudang",
                        visual.hair(), visual.cloth(), visual.pants(), visual.shoes()));
    }

    @Test
    void worldInfo() throws Exception {
        assertArrayEquals(golden("world_info.bin"),
                LoginMessages.worldInfo(0, "0,book1,0,book2,0,book4,0,book5,0,book8,0,0"));
    }

    @Test
    void propertyTableMatchesOfficialSnapshot() {
        byte[] frame = SceneMessages.propertyTableFrame();
        assertEquals(0x09, frame[0]);
        assertEquals(2370, frame[1] | frame[2] << 8, "真机 0x09 场景方言整表 verbatim（F 盘参考服捕获提取）");
        SnapshotGoldens.assertOrRegen("property_table_official.bin", frame);
    }

    @Test
    void propertyTableKnownEntries() {
        // 真机捕获表抽检（客户端按位置绑定，索引错位即渲染失败）；视图行=玩家域索引。
        assertEquals(7, PropertyTable.idx("ConfigID"), "场景域首现");
        assertEquals(1440, PropertyTable.playerIdx("ConfigID"), "视图/玩家域末现");
        assertEquals(WireType.STRING, PropertyTable.type("ConfigID"));
        assertEquals(1893, PropertyTable.idx("UniqueID"));
        assertEquals(1938, PropertyTable.idx("EquipType"));
        assertEquals(48, PropertyTable.idx("WeaponMode"), "场景域");
        assertEquals(1599, PropertyTable.playerIdx("WeaponMode"), "玩家域");
        assertEquals(WireType.STRING, PropertyTable.type("WeaponMode"));
        assertEquals(6, PropertyTable.idx("Level"), "NPC 域 Level=INT32");
        assertEquals(WireType.INT32, PropertyTable.type("Level"));
        assertEquals(1465, PropertyTable.playerIdx("Level"), "视图域 Level=BYTE");
        assertEquals(WireType.BYTE, PropertyTable.playerType("Level"));
        assertEquals(111, PropertyTable.idx("CapitalType1"), "商店/场景域=INT32");
        assertEquals(WireType.INT32, PropertyTable.type("CapitalType1"));
        assertEquals(417, PropertyTable.playerIdx("CapitalType1"), "玩家碎银=INT64");
        assertEquals(WireType.INT64, PropertyTable.playerType("CapitalType1"));
        assertEquals(773, PropertyTable.playerIdx("FillSpeed"), "内修修速（真机帧#20 实证）");
    }

    @Test
    void recordTableMatchesGolden() throws Exception {
        assertArrayEquals(golden("record_table.bin"),
                SceneMessages.recordTable(SceneMessages.QING_GONG_RECORD_SCHEMAS));
    }

    @Test
    void starterViewsMatchGolden() throws Exception {
        List<SceneMessages.ViewSpec> views = SceneMessages.starterBagViews();
        int[] expectedIds = {121, 2, 123, 125, 122, 3, 124, 126};
        for (int i = 0; i < expectedIds.length; i++) {
            assertEquals(expectedIds[i], views.get(i).id());
            assertArrayEquals(golden("view_create_" + expectedIds[i] + ".bin"),
                    SceneMessages.createView(views.get(i)), "view " + expectedIds[i]);
        }
    }

    @Test
    void entrySceneMatchesOfficialSnapshot() {
        SnapshotGoldens.assertOrRegen("entry_scene_official.bin",
                SceneMessages.entryScene(RoleStoreFacade.defaultRoleLocation().scene()));
    }

    @Test
    void serverLocationMatchesGolden() throws Exception {
        assertArrayEquals(golden("server_location.bin"),
                SceneMessages.serverLocation(1, 1, RoleStoreFacade.defaultRoleLocation().position()));
    }

    @Test
    void recordAddStringMatchesGolden() throws Exception {
        assertArrayEquals(golden("qinggong_record_add.bin"),
                RecordMessages.recordAddString(1, 1, RecordMessages.RECORD_QING_GONG, "qinggong_1"));
    }

    @Test
    void taskAcceptedAddMatchesGolden() throws Exception {
        assertArrayEquals(golden("task_accepted_add.bin"),
                RecordMessages.recordAddCells(1, 1, RecordMessages.RECORD_TASK_ACCEPTED,
                        RecordMessages.Cell.ofInt(1069), RecordMessages.Cell.ofInt(9), RecordMessages.Cell.ofInt(0),
                        RecordMessages.Cell.ofString(""), RecordMessages.Cell.ofString(""),
                        RecordMessages.Cell.ofString(""), RecordMessages.Cell.ofInt(0)));
    }

    @Test
    void taskCompletedAddMatchesGolden() throws Exception {
        assertArrayEquals(golden("task_completed_row_zero.bin"),
                RecordMessages.recordAddInt32(1, 1, RecordMessages.RECORD_TASK_COMPLETED, 0));
        // function_unlock_meta.json: final_row=1593 value=1<<26（task 51002 位）
        assertArrayEquals(golden("task_completed_row_final.bin"),
                RecordMessages.recordAddInt32(1, 1, RecordMessages.RECORD_TASK_COMPLETED, 1 << 26));
    }

    @Test
    void shortcutSitcrossAddMatchesGolden() throws Exception {
        assertArrayEquals(golden("shortcut_sitcross_add.bin"),
                RecordMessages.recordAddCells(1, 1, RecordMessages.RECORD_SHORTCUT,
                        RecordMessages.Cell.ofInt(9), RecordMessages.Cell.ofString("skill"),
                        RecordMessages.Cell.ofString("zs_default_01")));
    }

    @Test
    void skillSwitchMatchesGolden() throws Exception {
        assertArrayEquals(golden("skill_switch.bin"),
                CustomMessages.modernCustomIntMessage(414,
                        CustomMessages.Value.ofString("reset"),
                        CustomMessages.Value.ofInt32(1223),
                        CustomMessages.Value.ofInt32(1)));
    }

    @Test
    void customIntMessageRejectsOutOfRangeId() {
        assertThrows(IllegalArgumentException.class,
                () -> CustomMessages.modernCustomIntMessage(0, CustomMessages.Value.ofInt32(1)));
        assertThrows(IllegalArgumentException.class,
                () -> CustomMessages.customIntMessage(765, CustomMessages.Value.ofInt32(1)));
    }

    @Test
    void view46CreateAndAddsMatchSnapshots() throws Exception {
        assertArrayEquals(golden("view46_create.bin"),
                SceneMessages.createView(new SceneMessages.ViewSpec(46, 70)));
        List<SceneMessages.ViewProperty> first = new ArrayList<>(1);
        Props.addView(first, "ConfigID", PropertyValues.Value.ofString("qinggong_1"));
        SnapshotGoldens.assertOrRegen("view46_add_official_first.bin",
                SceneMessages.viewAdd(46, 1, first));
        List<SceneMessages.ViewProperty> last = new ArrayList<>(1);
        Props.addView(last, "ConfigID", PropertyValues.Value.ofString("QG_JH_gjskill_001"));
        SnapshotGoldens.assertOrRegen("view46_add_official_last.bin",
                SceneMessages.viewAdd(46, 70, last));
    }
}
