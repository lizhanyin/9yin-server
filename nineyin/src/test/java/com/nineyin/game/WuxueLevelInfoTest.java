package com.nineyin.game;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** wuxuelevelinfo.ini 曲线解析：行格式 r=等级,该级修炼度,速度；末级行+1 为上限。 */
class WuxueLevelInfoTest {

    private static WuxueLevelInfo load() {
        WuxueLevelInfo curves = new WuxueLevelInfo();
        curves.load(Path.of("src/test/resources/nineyin/faculty/wuxuelevelinfo-mini.ini"));
        return curves;
    }

    @Test
    void parsesSectionsRowsAndMaxLevel() {
        WuxueLevelInfo curves = load();
        assertEquals(3, curves.size());
        assertEquals(100L, curves.total("ng_a", 1));
        assertEquals(300L, curves.total("ng_a", 2));
        assertEquals(600L, curves.total("ng_a", 3));
        assertEquals(4, curves.maxLevel("ng_a"));
        assertEquals(3, curves.maxLevel("wx_c"));
    }

    @Test
    void maxLevelIsLastRowLevelPlusOne() {
        // ng_b 末行 r=5 → 数据侧上限 6
        assertEquals(6, load().maxLevel("ng_b"));
    }

    @Test
    void unknownSectionOrLevelReturnsFallback() {
        WuxueLevelInfo curves = load();
        assertEquals(-1L, curves.total("ng_missing", 1));
        assertEquals(-1L, curves.total("ng_a", 4), "no r=4 row");
        assertEquals(-1L, curves.total("ng_a", 0));
        assertEquals(-1, curves.maxLevel("ng_missing"));
    }
}
