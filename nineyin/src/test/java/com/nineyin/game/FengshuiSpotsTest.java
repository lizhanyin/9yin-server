package com.nineyin.game;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 风水宝地加成链：标记 id 解析、官方数值表（新浪 2012-01-31：最宜 +20%/较宜 +10%）、
 * 整分钟结算按千分比放大修炼度获取、FILL_SPEED 显示同步。
 */
class FengshuiSpotsTest {

    @Test
    void typeOfParsesMarkerIds() {
        assertEquals("shan", FengshuiSpots.typeOf("Fsbd_shan_01"));
        assertEquals("lin", FengshuiSpots.typeOf("Fsbd_lin_01"));
        assertEquals("shui", FengshuiSpots.typeOf("Fsbd_shui_01"));
        assertEquals("dong", FengshuiSpots.typeOf("Fsbd_dong_01"));
        assertEquals("xue", FengshuiSpots.typeOf("Fsbd_xue_01"));
        assertNull(FengshuiSpots.typeOf("npc_qd_001"));
        assertNull(FengshuiSpots.typeOf("Fsbd_market_01"), "未知宝地类型不参与加成");
        assertNull(FengshuiSpots.typeOf(null));
    }

    @Test
    void bonusTableMatchesOfficialPercentages() {
        // 山：太极 +20%；刚柔阴阳 +10%；阴柔/阳刚无
        assertEquals(20, FengshuiSpots.bonusPercent("shan", 5));
        assertEquals(10, FengshuiSpots.bonusPercent("shan", 1));
        assertEquals(10, FengshuiSpots.bonusPercent("shan", 4));
        assertEquals(0, FengshuiSpots.bonusPercent("shan", 6));
        // 水：阴柔 +20%；阴/柔 +10%
        assertEquals(20, FengshuiSpots.bonusPercent("shui", 6));
        assertEquals(10, FengshuiSpots.bonusPercent("shui", 3));
        assertEquals(0, FengshuiSpots.bonusPercent("shui", 1));
        // 洞：刚/阴 +20%；太极/阴柔/阳刚 +10%
        assertEquals(20, FengshuiSpots.bonusPercent("dong", 1));
        assertEquals(20, FengshuiSpots.bonusPercent("dong", 3));
        assertEquals(10, FengshuiSpots.bonusPercent("dong", 7));
        assertEquals(0, FengshuiSpots.bonusPercent("dong", 2));
        // 林：阳刚 +20%；刚/阳 +10%
        assertEquals(20, FengshuiSpots.bonusPercent("lin", 7));
        assertEquals(10, FengshuiSpots.bonusPercent("lin", 1));
        assertEquals(0, FengshuiSpots.bonusPercent("lin", 2));
        // 雪：柔/阳 +20%；太极/阴柔/阳刚 +10%
        assertEquals(20, FengshuiSpots.bonusPercent("xue", 2));
        assertEquals(20, FengshuiSpots.bonusPercent("xue", 4));
        assertEquals(10, FengshuiSpots.bonusPercent("xue", 5));
        assertEquals(0, FengshuiSpots.bonusPercent("xue", 3));
        assertEquals(0, FengshuiSpots.bonusPercent(null, 5));
    }

    @Test
    void advanceFacultyAppliesFengshuiPermille() {
        WuxueLevelInfo curves = miniCurves();
        FacultyState state = new FacultyState();
        // 大 total + 高 maxLevel：排除升级消耗/满级退出干扰，修炼度=纯获取量
        state.books.add(new FacultyState.Book("ng_a", 5, 1002, 1, 99, 0, 1_000_000, 120, 120));
        state.curNeigong = "ng_a";
        state.facultyName = "ng_a";
        state.faculty = 100_000;
        state.facultyState = FacultyState.STATE_CONVERT;
        state.facultyStyle = FacultyState.STYLE_NORMAL;
        state.fillSpeed = FacultyState.NORMAL_FILL_PER_MINUTE; // 200/分钟
        Instant start = Instant.parse("2026-11-01T00:00:00Z");

        // 基线：2 分钟 × 200 = 400
        state.lastAdvance = start;
        state.advanceFaculty(start.plusSeconds(120), curves);
        assertEquals(400, state.books.get(0).fill);

        // 山 + 太极（+20%）：2 分钟 × 200 × 1.2 = 480
        state.books.get(0).fill = 0;
        state.lastAdvance = start;
        state.advanceFaculty(start.plusSeconds(120), curves, 200);
        assertEquals(480, state.books.get(0).fill, "最宜宝地修炼度应按 +20% 放大");

        // 显示速度同步：displayFillSpeed 在内修中按千分比放大
        state.fengshuiPermille = 100;
        assertEquals(220, state.displayFillSpeed());
        state.fengshuiPermille = 0;
        assertEquals(200, state.displayFillSpeed());
        // 非内修态不加成（退出时 facultyExit 会另行置 fillSpeed=0）
        state.facultyState = FacultyState.STATE_NONE;
        assertEquals(200, state.displayFillSpeed());
    }

    private static WuxueLevelInfo miniCurves() {
        WuxueLevelInfo curves = new WuxueLevelInfo();
        curves.load(java.nio.file.Path.of(
                "src/test/resources/nineyin/faculty/wuxuelevelinfo-mini.ini"));
        return curves;
    }
}
