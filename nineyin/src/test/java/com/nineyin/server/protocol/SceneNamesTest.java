package com.nineyin.server.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.nineyin.server.protocol.LoginMessages;
import com.nineyin.server.protocol.SceneNames;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.util.ServerPaths;

/** 选角显示名映射：scenes.ini + desc.idres 数据驱动（需 Go 仓库 share 资源）。 */
class SceneNamesTest {

    @BeforeAll
    static void needsGoResources() {
        Path root = Path.of("G:/pojie/ai/9yin-go-server1");
        assumeTrue(Files.isDirectory(root.resolve("resources/modern/share/rule")),
                "Go 仓库 share 资源不可用，跳过显示名映射测试");
        ServerPaths.overrideServerRoot(root);
    }

    @Test
    void sceneDisplayNameResolvesFromTables() {
        assertEquals("成都", SceneNames.sceneDisplayName("ini\\scene\\city05_ChengDu", "city05"));
        assertEquals("移花宫", SceneNames.sceneDisplayName("ini\\scene\\school09_YiHuaGong", "school09"));
        // 仅 resource（config 缺失）也能命中。
        assertEquals("成都", SceneNames.sceneDisplayName("", "city05"));
    }

    @Test
    void unknownSceneFallsBackToKey() {
        assertEquals("scene_name_017", SceneNames.sceneDisplayName("", ""));
    }

    @Test
    void bookIntroKeys() {
        assertEquals("introduction_qdz01_title", SceneNames.bookIntroKey("book1"));
        assertEquals("introduction_jm01_title", SceneNames.bookIntroKey("book5"));
        assertEquals("introduction_wmp01_title", SceneNames.bookIntroKey("book8"));
        assertEquals("bookX", SceneNames.bookIntroKey("bookX"));
    }

    @Test
    void roleListTitleTiers() {
        assertEquals("title001", LoginMessages.roleListTitle(1));
        assertEquals("title001", LoginMessages.roleListTitle(5));
        assertEquals("title024", LoginMessages.roleListTitle(120));
        assertEquals("title025", LoginMessages.roleListTitle(130));
        assertEquals("title026", LoginMessages.roleListTitle(150));
        assertEquals("title027", LoginMessages.roleListTitle(151));
    }
}
