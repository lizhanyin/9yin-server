package com.nineyin.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 修炼状态机：种子、215 切换、1006 子命令迁移、整分钟结算与快照托管键。 */
class FacultyStateTest {

    /** 迷你曲线：ng_a 升 2/3/4 级分别需 100/300/600（末级 3 → 数据侧上限 4）。 */
    private static WuxueLevelInfo miniCurves() {
        WuxueLevelInfo curves = new WuxueLevelInfo();
        curves.load(java.nio.file.Path.of(
                "src/test/resources/nineyin/faculty/wuxuelevelinfo-mini.ini"));
        return curves;
    }

    private static FacultyState state(int facultyPoints, String... bookIds) {
        FacultyState state = new FacultyState();
        for (String id : bookIds) {
            state.books.add(book(id));
        }
        state.curNeigong = bookIds[0];
        state.faculty = facultyPoints;
        return state;
    }

    private static FacultyState.Book book(String id) {
        return new FacultyState.Book(id, 5, 1002, 1, 4, 0, 100, 120, 120);
    }

    @Test
    void bootstrapSeedMatchesLegacyConstants() {
        FacultyState seed = FacultyState.bootstrap();
        assertEquals(6, seed.books.size());
        FacultyState.Book taiji = seed.book("ng_wd_001");
        assertEquals(30, taiji.level);
        assertEquals(273200, taiji.total);
        assertEquals(4550, taiji.power);
        assertEquals("ng_wd_001", seed.curNeigong);
        assertEquals(FacultyState.STATE_CONVERT, seed.facultyState);
        assertEquals(FacultyState.STYLE_NORMAL, seed.facultyStyle);
        assertEquals(FacultyState.NORMAL_FILL_PER_MINUTE, seed.fillSpeed);
    }

    @Test
    void equipRejectsUnlearnedAndSwitchesLearned() {
        FacultyState state = state(1000, "ng_a", "ng_b");
        assertFalse(state.equip("ng_missing"));
        assertTrue(state.equip("ng_b"));
        assertEquals("ng_b", state.curNeigong);
        assertTrue(state.persistDirty);
    }

    @Test
    void facultyReadyBeginExitTransitions() {
        FacultyState state = state(1000, "ng_a", "ng_b");
        assertEquals(0, state.facultyReady("ng_missing"), "unlearned ready rejected");
        int slot = state.facultyReady("ng_b");
        assertEquals(2, slot);
        assertEquals(FacultyState.STATE_NONE, state.facultyState);
        assertEquals(FacultyState.STYLE_NORMAL, state.facultyStyle);
        assertEquals(0, state.fillSpeed);

        assertEquals(2, state.facultyBegin(FacultyState.STYLE_NORMAL, Instant.now()));
        assertEquals(FacultyState.STATE_CONVERT, state.facultyState);
        assertEquals(FacultyState.NORMAL_FILL_PER_MINUTE, state.fillSpeed);

        // 演武：fillSpeed 置 0（选卡结算后才发修炼度）
        state.facultyBegin(FacultyState.STYLE_ACT, Instant.now());
        assertEquals(FacultyState.STYLE_ACT, state.facultyStyle);
        assertEquals(0, state.fillSpeed);

        state.facultyExit();
        assertEquals(FacultyState.STATE_NONE, state.facultyState);
        assertEquals(0, state.fillSpeed);
    }

    @Test
    void beginWithoutSelectionRejected() {
        FacultyState state = state(1000, "ng_a");
        state.facultyName = "ng_missing";
        assertEquals(0, state.facultyBegin(FacultyState.STYLE_NORMAL, Instant.now()));
    }

    @Test
    void advanceSettlesWholeMinutesWithCurveLevelUps() {
        WuxueLevelInfo curves = miniCurves();
        FacultyState state = state(1_000_000, "ng_a");
        state.facultyReady("ng_a");
        state.facultyBegin(FacultyState.STYLE_NORMAL,
                Instant.parse("2026-10-31T00:00:00Z"));
        // 10 分钟 × 200/分 = 2000 点：1→2（扣 100）后余 1900，2→3（扣 300）余 1600，3→4（扣 600）余 1000，满级
        int slot = state.advanceFaculty(Instant.parse("2026-10-31T00:10:00Z"), curves);
        assertEquals(1, slot);
        FacultyState.Book book = state.book("ng_a");
        assertEquals(4, book.level, "multi-level jump to cap");
        assertEquals(0, book.fill, "fill cleared at cap");
        assertEquals(800, book.total, "level 4 beyond curve rows falls back +200");
        assertEquals(180, book.power, "+20 power per level");
        assertEquals(FacultyState.STATE_NONE, state.facultyState, "cap stops cultivation");
        // 修为点数消耗：fill=2000 → cost=1000
        assertEquals(1_000_000 - 1000, state.faculty);
        // 部分分钟不结算：lastAdvance 保持整分
        Instant next = Instant.parse("2026-10-31T00:10:30Z");
        assertEquals(0, state.advanceFaculty(next, curves));
    }

    @Test
    void advanceClampsToFacultyPoints() {
        WuxueLevelInfo curves = miniCurves();
        FacultyState state = state(100, "ng_a");   // 100 点 → 最多换 200 修炼度
        state.facultyReady("ng_a");
        state.facultyBegin(FacultyState.STYLE_NORMAL, Instant.parse("2026-10-31T00:00:00Z"));
        int slot = state.advanceFaculty(Instant.parse("2026-10-31T00:10:00Z"), curves);
        assertEquals(1, slot);
        assertEquals(0, state.faculty);
        // 200 修炼度先满足 1→2 级（100），余 100 挂在 2 级
        FacultyState.Book book = state.book("ng_a");
        assertEquals(2, book.level);
        assertEquals(100, book.fill);
        assertEquals(300, book.total, "curve total at level 2");
    }

    @Test
    void advanceIgnoredWhenNotConverting() {
        FacultyState state = state(1000, "ng_a");
        state.facultyReady("ng_a");   // STATE_NONE
        assertEquals(0, state.advanceFaculty(Instant.parse("2026-10-31T00:10:00Z"), miniCurves()));
    }

    @Test
    void awardActiveFacultyCardAndStalePaySwallowed() {
        WuxueLevelInfo curves = miniCurves();
        FacultyState state = state(1000, "ng_a");
        // 过期 PAY（未进演武态）：静默吞
        FacultyState.ActiveAward stale = state.awardActiveFaculty(curves);
        assertEquals(0, stale.slot());

        state.facultyReady("ng_a");
        state.facultyBegin(FacultyState.STYLE_ACT, Instant.now());
        FacultyState.ActiveAward award = state.awardActiveFaculty(curves);
        assertEquals(1, award.slot());
        assertTrue(award.awarded());
        assertTrue(award.levelUp(), "20000 covers 100+300+600 → level 4");
        assertTrue(award.maxLevel());
        assertEquals(4, state.book("ng_a").level);
        assertEquals(0, state.book("ng_a").fill);
    }

    @Test
    void snapshotManagedKeysRoundTrip() {
        JSONObject snapshot = JSON.parseObject("""
                {
                  "books": [
                    {"config_id": "ng_jh_001", "level": 7, "fill": 100, "total": 13950,
                     "power": 240, "max_power": 240},
                    {"config_id": "ng_jh_201", "level": 13, "fill": 500, "total": 307300,
                     "power": 360, "max_power": 360}
                  ],
                  "cur_neigong": "ng_jh_201", "faculty": 4655707,
                  "faculty_state": 2, "faculty_style": 1, "faculty_name": "ng_jh_201",
                  "fill_speed": 516, "last_advance_utc": "2026-09-18T20:45:56.9695428Z",
                  "skill_levels": {"CS_wd_tjq07": 5}, "title_id": 4102
                }
                """);
        WuxueLevelInfo curves = miniCurves();
        FacultyState state = FacultyState.fromSnapshotJson(snapshot, curves);
        assertNotNull(state);
        assertEquals(2, state.books.size());
        assertEquals("ng_jh_201", state.curNeigong);
        assertEquals(4655707, state.faculty);
        assertEquals(FacultyState.STATE_CONVERT, state.facultyState);
        assertEquals(FacultyState.STYLE_NORMAL, state.facultyStyle);
        assertEquals(516, state.fillSpeed);
        assertEquals(Instant.parse("2026-09-18T20:45:56.969542800Z"), state.lastAdvance);
        // 曲线定级：ng_a 数据侧上限 4；ng_jh_201 无曲线回退默认 36
        assertEquals(36, state.book("ng_jh_001").maxLevel);
        assertEquals(36, state.book("ng_jh_201").maxLevel);
        // 托管键回写：不携带 skill_levels/title_id 扩展键
        JSONObject managed = state.toManagedJson();
        assertEquals(2, managed.getJSONArray("books").size());
        assertEquals("ng_jh_201", managed.getString("cur_neigong"));
        assertFalse(managed.containsKey("skill_levels"));
        assertFalse(managed.containsKey("title_id"));
    }

    @Test
    void snapshotRejectsBadRowsAndEmptyDocs() {
        JSONObject snapshot = JSON.parseObject("""
                {
                  "books": [
                    {"config_id": "", "level": 1, "fill": 0, "total": 750, "power": 120, "max_power": 120},
                    {"config_id": "ng_ok", "level": 0, "fill": 0, "total": 750, "power": 120, "max_power": 120},
                    {"config_id": "ng_ok", "level": 1, "fill": -1, "total": 750, "power": 120, "max_power": 120},
                    {"config_id": "ng_ok", "level": 1, "fill": 0, "total": 0, "power": 120, "max_power": 120},
                    {"config_id": "ng_ok", "level": 1, "fill": 0, "total": 750, "power": 120, "max_power": 120}
                  ]
                }
                """);
        FacultyState state = FacultyState.fromSnapshotJson(snapshot, null);
        assertNotNull(state);
        assertEquals(1, state.books.size());
        assertNull(FacultyState.fromSnapshotJson(JSON.parseObject("{\"books\":[]}"), null));
        assertNull(FacultyState.fromSnapshotJson(null, null));
    }

    @Test
    void viewBooksMatchBookList() {
        FacultyState state = FacultyState.bootstrap();
        List<com.nineyin.protocol.BootstrapPackets.NeiGongBook> view = state.viewBooks();
        assertEquals(state.books.size(), view.size());
        assertEquals("ng_wd_001", view.get(0).configId());
        assertEquals(30, view.get(0).level());
    }
}
