package com.nineyin.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.nineyin.server.gm.FactionNames;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.util.ServerPaths;

/** GM 面板门派显示名映射：form_main_player.ini [school_name] + interface.idres（需 Go 仓库资源）。 */
class FactionNamesTest {

    @BeforeAll
    static void needsGoResources() {
        Path root = Path.of("G:/pojie/ai/9yin-go-server1");
        assumeTrue(Files.isRegularFile(root.resolve("resources/modern/ini/form_main_player.ini")),
                "Go 仓库 form_main_player.ini 不可用，跳过门派显示名测试");
        ServerPaths.overrideServerRoot(root);
    }

    @Test
    void resolvesForceSchoolAndNewSchoolCodes() {
        assertEquals("移花宫", FactionNames.displayName("force_yihua"));
        assertEquals("武当", FactionNames.displayName("school_wudang"));
        assertEquals("华山派", FactionNames.displayName("newschool_huashan"));
    }

    @Test
    void unknownOrBlankFallsBack() {
        assertEquals("force_unknown", FactionNames.displayName("force_unknown"));
        assertEquals("", FactionNames.displayName(""));
        assertEquals("", FactionNames.displayName(null));
    }
}
