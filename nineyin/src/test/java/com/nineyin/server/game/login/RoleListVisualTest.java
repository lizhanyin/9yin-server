package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.appearance.AppearanceSync;
import com.nineyin.server.world.db.RoleInventoryStore;

/**
 * 选角列表外观（LoginFlow.roleListVisual）：装备显示类型 = 已穿衣/裤/鞋模型
 * 覆盖创角初始装、未穿部位保持创角外观（桩 ini 表钉死模型路径，不依赖参考服 share）。
 */
class RoleListVisualTest {

    @TempDir
    Path tempRoot;

    private Path previousRoot;

    @BeforeEach
    void setUp() throws Exception {
        Path itemDir = tempRoot.resolve(Path.of("resources", "modern", "share", "item"));
        Files.createDirectories(itemDir);
        // 桩表：cloth_b0002（已穿）→ obj\worn\cloth；创角初始装不在表内（回退原样 configId）。
        Files.writeString(itemDir.resolve("equipment.ini"), ""
                + "[cloth_b0002]\r\nArtPack=9\r\n\r\n");
        Files.writeString(itemDir.resolve("itemartstatic.ini"), ""
                + "[9]\r\nMaleModel=obj\\worn\\cloth\r\n\r\n");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
    }

    @AfterEach
    void tearDown() {
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    private static RoleInventoryStore.Equip wornCloth(String configId) {
        return new RoleInventoryStore.Equip(configId, 0, "Cloth", AppearanceSync.SLOT_CLOTH,
                100, 100, 0, "");
    }

    @Test
    void wornEquipmentOverridesCreationLook() {
        RoleVisual visual = LoginFlow.roleListVisual(List.of(), List.of(wornCloth("cloth_b0002")));
        assertEquals("obj\\worn\\cloth", visual.cloth(), "已穿衣服模型覆盖创角初始装");
        assertEquals("pants_b0001", visual.pants(), "未穿部位保持创角初始装");
        assertEquals("shoes_b0001", visual.shoes(), "未穿部位保持创角初始装");
        assertEquals("obj\\char\\b_hair\\b_hair1", visual.hair(), "发型不受装备显示影响");
    }

    @Test
    void noEquipsKeepsCreationLook() {
        RoleVisual visual = LoginFlow.roleListVisual(List.of(), List.of());
        assertEquals("cloth_b0001", visual.cloth());
        assertEquals("pants_b0001", visual.pants());
        assertEquals("shoes_b0001", visual.shoes());
    }
}
