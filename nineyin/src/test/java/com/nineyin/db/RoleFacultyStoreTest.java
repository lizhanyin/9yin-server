package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nineyin.game.FacultyState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * role_faculty 快照解析/合并（纯 JSON，无 MySQL）。
 * 夹具取自 live 库 role 618 真实行（48 本江湖内功 + F 版 exe 扩展键）的裁剪版。
 */
class RoleFacultyStoreTest {

    /** live role 618 快照裁剪版：保留首尾两本书 + 全部扩展键族。 */
    private static final String LIVE_SNAPSHOT = """
            {
              "books": [
                {"fill": 44254, "level": 7, "power": 240, "total": 48000, "config_id": "ng_jh_001", "max_power": 240},
                {"fill": 0, "level": 1, "power": 120, "total": 750, "config_id": "ng_jh_105", "max_power": 120},
                {"fill": 2370542, "level": 13, "power": 360, "total": 3072000, "config_id": "ng_jh_201", "max_power": 360}
              ],
              "faculty": 4655707, "title_id": 4102, "qg_levels": null, "fill_speed": 516,
              "skill_caps": {"CS_jh_yydf01": 3},
              "cur_neigong": "ng_jh_201", "faculty_name": "ng_jh_201",
              "owned_titles": [4332, 4303, 4102],
              "skill_levels": {"CS_wd_tjq07": 5, "CS_wd_tjq08": 12},
              "faculty_state": 2, "faculty_style": 1, "fill_progress": 0,
              "character_evil": 32652,
              "last_advance_utc": "2026-09-18T20:45:56.9695428Z",
              "character_justice": 0, "character_freebase": 43521
            }
            """;

    @Test
    void parsesLiveShapedSnapshotWithManagedKeys() {
        FacultyState state = FacultyState.fromSnapshotJson(
                JSON.parseObject(LIVE_SNAPSHOT), null);
        assertNotNull(state);
        assertEquals(3, state.books.size());
        assertEquals("ng_jh_201", state.curNeigong);
        assertEquals("ng_jh_201", state.facultyName);
        assertEquals(4655707, state.faculty);
        assertEquals(FacultyState.STATE_CONVERT, state.facultyState);
        assertEquals(FacultyState.STYLE_NORMAL, state.facultyStyle);
        assertEquals(516, state.fillSpeed);
        FacultyState.Book first = state.book("ng_jh_001");
        assertEquals(7, first.level);
        assertEquals(44254, first.fill);
        assertEquals(48000, first.total);
        // 目录未补齐前的种子值：staticData=0/itemType=1002，等 game_skills enrich
        assertEquals(0, first.staticData);
        assertEquals(1002, first.itemType);
    }

    @Test
    void mergePreservesExeExtensionKeysAndOverwritesManaged() {
        FacultyState state = FacultyState.fromSnapshotJson(
                JSON.parseObject(LIVE_SNAPSHOT), null);
        assertNotNull(state);
        state.equip("ng_jh_001");
        state.book("ng_jh_001").applyCatalog(1, 1002);

        String merged = RoleFacultyStore.mergeSnapshot(LIVE_SNAPSHOT, state);
        JSONObject doc = JSON.parseObject(merged);
        // 托管键已覆盖
        assertEquals("ng_jh_001", doc.getString("cur_neigong"));
        JSONArray books = doc.getJSONArray("books");
        assertEquals(3, books.size());
        assertEquals(7, books.getJSONObject(0).getIntValue("level"));
        // F 版 exe 扩展键原样保留
        assertEquals(4102, doc.getIntValue("title_id"));
        assertEquals(0, doc.getIntValue("fill_progress"));
        assertEquals(32652, doc.getIntValue("character_evil"));
        assertEquals(43521, doc.getIntValue("character_freebase"));
        JSONObject skillLevels = doc.getJSONObject("skill_levels");
        assertEquals(5, skillLevels.getIntValue("CS_wd_tjq07"));
        assertEquals(3, doc.getJSONArray("owned_titles").size());
    }

    @Test
    void mergeCreatesFreshDocumentWhenExistingMissingOrBroken() {
        FacultyState state = FacultyState.fromSnapshotJson(
                JSON.parseObject(LIVE_SNAPSHOT), null);
        assertNotNull(state);
        JSONObject fresh = JSON.parseObject(RoleFacultyStore.mergeSnapshot(null, state));
        assertTrue(fresh.containsKey("books"));
        assertFalse(fresh.containsKey("skill_levels"), "fresh doc has no exe keys");

        JSONObject broken = JSON.parseObject(
                RoleFacultyStore.mergeSnapshot("not-json{{{", state));
        assertTrue(broken.containsKey("books"), "broken doc replaced by managed keys");
    }

    @Test
    void nullStoreOnJsonBackend() {
        assertNull(RoleFacultyStore.from(new com.nineyin.db.JsonRoleStore(java.nio.file.Path.of("data/roles.json"))));
    }
}
