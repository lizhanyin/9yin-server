package com.nineyin.protocol;

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

import com.nineyin.server.protocol.*;
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
        assertEquals(2426, frame[1] | frame[2] << 8, "官方整表 verbatim（2426 行含 None 行，bak 同）");
        SnapshotGoldens.assertOrRegen("property_table_official.bin", frame);
    }

    @Test
    void propertyTableKnownEntries() {
        // bak 真机验证过的关键索引抽检（客户端按名绑定，索引错位即渲染失败）。
        assertEquals(1439, PropertyTable.idx("ConfigID"));
        assertEquals(WireType.STRING, PropertyTable.type("ConfigID"));
        assertEquals(1891, PropertyTable.idx("UniqueID"));
        assertEquals(1936, PropertyTable.idx("EquipType"));
        assertEquals(1597, PropertyTable.idx("WeaponMode"));
        assertEquals(WireType.STRING, PropertyTable.type("WeaponMode"));
        assertEquals(1464, PropertyTable.idx("Level"));
        assertEquals(WireType.BYTE, PropertyTable.type("Level"));
        assertEquals(416, PropertyTable.idx("CapitalType1"));
        assertEquals(WireType.FLOAT64, PropertyTable.type("CapitalType1"));
        assertEquals(772, PropertyTable.idx("FillSpeed"));
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
