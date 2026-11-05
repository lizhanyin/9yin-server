package com.nineyin.server.game.avatar.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 声望等级表解析（合成 repute_level.ini：[Repute] 闭名单 / [Level] 默认曲线 /
 * [repute_max_value] 特例上限 / 阵营自定义段；生产文件 36 阵营同构）。
 */
class ReputeLevelTableTest {

    @Test
    void parseCampsCurvesAndMaxValues(@TempDir Path tempRoot) throws Exception {
        Path ruleDir = tempRoot.resolve("rule");
        Files.createDirectories(ruleDir);
        Files.writeString(ruleDir.resolve("repute_level.ini"), """
                [Level]
                1=0
                2=700
                3=5483
                16=200000000

                [Repute]
                r=repute_wudang
                r=repute_jianghu
                r=repute_yihua

                [repute_max_value]
                repute_yihua=400000

                [repute_yihua]
                1=0
                2=1800
                3=3000
                """);
        ReputeLevelTable table = ReputeLevelTable.load(ruleDir);

        assertTrue(table.present());
        assertEquals(java.util.List.of("repute_wudang", "repute_jianghu", "repute_yihua"),
                table.camps(), "[Repute] 出现序");
        assertTrue(table.isCamp("repute_wudang"));
        assertFalse(table.isCamp("repute_nosuch"));
        assertFalse(table.isCamp(null));

        // 默认上限 = [Level] 最大；特例阵营按 [repute_max_value]。
        assertEquals(200_000_000L, table.maxValue("repute_wudang"));
        assertEquals(400_000L, table.maxValue("repute_yihua"));

        // 默认曲线：0→1 级、699→1、700→2、5483→3；级号 = INI 键（16 档不重排为 4）。
        assertEquals(1, table.levelOf("repute_wudang", 0));
        assertEquals(1, table.levelOf("repute_wudang", 699));
        assertEquals(2, table.levelOf("repute_wudang", 700));
        assertEquals(3, table.levelOf("repute_wudang", 5483));
        assertEquals(16, table.levelOf("repute_wudang", 200_000_000));
        // 自定义段优先：repute_yihua 1800 即 2 级（默认曲线要 5483 才 3 级）。
        assertEquals(1, table.levelOf("repute_yihua", 1799));
        assertEquals(2, table.levelOf("repute_yihua", 1800));
        assertEquals(3, table.levelOf("repute_yihua", 3000));
        // 无自定义段的阵营走默认曲线。
        assertEquals(3, table.levelOf("repute_jianghu", 999_999));
    }

    @Test
    void missingFileYieldsAbsentTable(@TempDir Path tempRoot) {
        ReputeLevelTable table = ReputeLevelTable.load(tempRoot.resolve("rule"));
        assertFalse(table.present());
        assertTrue(table.camps().isEmpty());
        assertEquals(1, table.levelOf("repute_wudang", 100));
    }
}
