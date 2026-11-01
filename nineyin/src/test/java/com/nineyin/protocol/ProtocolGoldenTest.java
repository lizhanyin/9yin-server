package com.nineyin.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.nineyin.game.avatar.equipment.EquipmentResolver;
import com.nineyin.game.avatar.RoleVisual;
import com.nineyin.game.ServerPaths;
import com.nineyin.role.RoleStoreFacade;

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
    void propertyTableMatchesGolden() throws Exception {
        assertArrayEquals(golden("property_table.bin"),
                SceneMessages.visiblePropertyTable(NpcSchema.sceneVisiblePropertyFields()));
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
    void entrySceneMatchesGolden() throws Exception {
        assertArrayEquals(golden("entry_scene.bin"),
                SceneMessages.entryScene(RoleStoreFacade.defaultRoleLocation().scene()));
    }

    @Test
    void serverLocationMatchesGolden() throws Exception {
        assertArrayEquals(golden("server_location.bin"),
                SceneMessages.serverLocation(1, 1, RoleStoreFacade.defaultRoleLocation().position()));
    }
}
