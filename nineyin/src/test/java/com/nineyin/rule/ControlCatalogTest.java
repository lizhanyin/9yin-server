package com.nineyin.rule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制效果目录：StateType 解析 + 眩晕族免疫位段（§3.25 数据考古）。 */
class ControlCatalogTest {

    @TempDir
    Path tempDir;

    private ControlCatalog loadFixture() throws Exception {
        Path skill = tempDir.resolve("skill");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("buff_static.ini"), """
                [-1]
                StateType=27

                [101]
                StateType=1
                Visible=0

                [102]
                StateType=5

                [103]
                Visible=0
                """);
        ControlCatalog catalog = new ControlCatalog();
        catalog.load(skill);
        return catalog;
    }

    @Test
    void parsesStateTypeColumn() throws Exception {
        ControlCatalog catalog = loadFixture();
        assertEquals(1, catalog.stateTypeOf(101));
        assertEquals(5, catalog.stateTypeOf(102));
        assertEquals(0, catalog.stateTypeOf(103), "无 StateType 行 = 非控制");
        assertEquals(0, catalog.stateTypeOf(999));
        assertTrue(catalog.isControl(101));
        assertFalse(catalog.isControl(103));
    }

    @Test
    void stunImmunityBitsBlockStateType1Only() throws Exception {
        ControlCatalog catalog = loadFixture();
        assertTrue(catalog.blocks(1, Set.of(33)), "eqp_ImmStun 一级位 33 挡眩晕族");
        assertTrue(catalog.blocks(1, Set.of(38)), "六级位 38 仍在眩晕位段");
        assertFalse(catalog.blocks(1, Set.of(32)));
        assertFalse(catalog.blocks(1, Set.of()));
        assertFalse(catalog.blocks(5, Set.of(33)), "其余控制类位段未映射不误伤");
    }

    @Test
    void realTableLoadsControls() {
        String root = System.getenv("NINEYIN_SERVER_ROOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null && !root.isEmpty(),
                "NINEYIN_SERVER_ROOT 未设置，跳过真实表");
        ControlCatalog catalog = new ControlCatalog();
        catalog.load(Path.of(root).resolve("share").resolve("skill"));
        // 实测：StateType 1..10 共 187 个控制 buff
        assertTrue(catalog.size() >= 180, "controls=" + catalog.size());
        assertTrue(catalog.size() <= 195, "controls=" + catalog.size());
    }
}
