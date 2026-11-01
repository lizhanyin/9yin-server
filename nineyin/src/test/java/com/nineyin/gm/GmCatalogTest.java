package com.nineyin.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import com.nineyin.server.gm.GmCatalog;
import com.nineyin.server.gm.GmCatalogDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GmCatalog 数据源选择：注入 GmCatalogDb 后 data/*.json 来源走 DB，
 * DB 不可用（返回 null）回退文件，useDatabase 失效旧缓存。用桩 DB，不连库。
 */
class GmCatalogTest {

    @TempDir
    Path dataDir;

    private com.nineyin.server.gm.GmCatalog catalog;

    @BeforeEach
    void setUp() throws IOException {
        write("card_models.json", "{\"cards\":{\"3\":{\"name\":\"文件卡\",\"config_id\":7,"
                + "\"female_model_data\":\"Weapon,blade_1\",\"male_model_data\":\"Weapon,blade_1\"}}}");
        write("经脉.json", "{\"meridians\":[{\"id\":\"jm_file\",\"name\":\"文件脉\",\"maxLevel\":12}]}");
        write("称号数据.json", "{\"catalog\":{\"titles\":[{\"id\":1,\"name\":\"文件称号\",\"type\":2}]}}");
        write("bots.json", "{\"bots\":[{\"id\":9,\"key\":\"file-bot\",\"name\":\"文件假人\",\"enabled\":true}]}");
        write("身份装数据.json", "{\"suits\":{\"b_file\":{\"name\":\"文件装\",\"need_sex\":1}}}");
        write("giftbox.json", "{\"boxes\":{\"box_file\":{\"items\":[]}}}");
        Files.createDirectories(dataDir.resolve("origin"));
        write("origin/origins.json", "{\"origins\":[{\"id\":1},{\"id\":2}]}");
        catalog = GmCatalog.createForTest(null, dataDir);
    }

    @Test
    void fileSourcesWhenNoDatabase() {
        assertEquals(1, catalog.cards().size());
        assertEquals("文件卡", catalog.cards().getJSONObject(0).getString("name"));
        assertEquals("jm_file", catalog.meridians().getJSONObject(0).getString("id"));
        assertEquals(12, catalog.meridians().getJSONObject(0).getIntValue("defaultMax"));
        assertEquals("文件称号", catalog.titles().getJSONObject(0).getString("name"));
        assertEquals("file-bot", catalog.bots().getJSONObject(0).getString("key"));
        assertEquals("box_file", catalog.giftBoxes().getJSONObject(0).getString("config"));
        assertTrue(catalog.giftBoxes().getJSONObject(0).getBooleanValue("hasContent"));
        assertTrue(catalog.originStatus().getBooleanValue("loaded"));
        assertEquals(2, catalog.originStatus().getIntValue("total"));
        JSONObject fashion = catalog.fashionCatalog().getJSONObject(0);
        assertEquals("sex1", fashion.getString("key"));
        assertEquals("女装", fashion.getString("label"));
        assertEquals("文件装", fashion.getJSONArray("items").getJSONObject(0).getString("name"));
    }

    @Test
    void databaseTakesPrecedenceAndInvalidatesCache() {
        // 先走一遍文件源，缓存文件结果。
        assertEquals("文件称号", catalog.titles().getJSONObject(0).getString("name"));
        assertEquals(2, catalog.originStatus().getIntValue("total"));

        catalog.useDatabase(new StubDb());
        assertEquals("DB称号", catalog.titles().getJSONObject(0).getString("name"));
        assertEquals(5, catalog.titles().getJSONObject(0).getIntValue("id"));
        assertEquals("DB卡", catalog.cards().getJSONObject(0).getString("name"));
        assertEquals("Weapon", catalog.cards().getJSONObject(0).getString("category"));
        assertEquals("jm_db", catalog.meridians().getJSONObject(0).getString("id"));
        assertEquals("db-bot", catalog.bots().getJSONObject(0).getString("key"));
        assertEquals(7, catalog.originStatus().getIntValue("total"));
        JSONObject fashion = catalog.fashionCatalog().getJSONObject(0);
        assertEquals("sex0", fashion.getString("key"));
        assertEquals("男装", fashion.getString("label"));
        assertTrue(catalog.giftBoxes().getJSONObject(0).getBooleanValue("hasContent"));
        assertEquals("box_db", catalog.giftBoxes().getJSONObject(0).getString("config"));
    }

    @Test
    void fallsBackToFileWhenDatabaseSourceUnavailable() {
        // 桩未覆写的方法返回 null → 逐源回退文件。
        catalog.useDatabase(new GmCatalogDb(null));
        assertEquals("文件卡", catalog.cards().getJSONObject(0).getString("name"));
        assertEquals("文件称号", catalog.titles().getJSONObject(0).getString("name"));
        assertEquals("文件假人", catalog.bots().getJSONObject(0).getString("name"));
        assertFalse(catalog.giftBoxes().isEmpty());
        assertTrue(catalog.originStatus().getBooleanValue("loaded"));
    }

    @Test
    void hairCardsFilteredOnDatabaseSource() {
        catalog.useDatabase(new StubDb());
        JSONArray hair = catalog.hairCards();
        assertEquals(1, hair.size());
        assertEquals("Head", hair.getJSONObject(0).getString("category"));
    }

    private void write(String relative, String json) throws IOException {
        Path file = dataDir.resolve(relative);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /** 只覆写部分来源的桩；其余走基类 null（模拟该表不可用）。 */
    private static final class StubDb extends GmCatalogDb {
        StubDb() {
            super(null);
        }

        @Override
        public JSONArray cards() {
            JSONObject o = new JSONObject(true);
            o.put("id", 3L);
            o.put("name", "DB卡");
            o.put("config_id", 7);
            o.put("category", "Weapon");
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public JSONArray hairCards() {
            JSONObject o = new JSONObject(true);
            o.put("id", 4L);
            o.put("name", "DB发型卡");
            o.put("config_id", 8);
            o.put("category", "Head");
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public JSONArray identitySuits() {
            JSONObject o = new JSONObject(true);
            o.put("configId", "b_db");
            o.put("name", "DB装");
            o.put("needSex", 0);
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public Set<String> giftBoxIds() {
            return new LinkedHashSet<>(java.util.List.of("box_db"));
        }

        @Override
        public JSONArray meridians() {
            JSONObject o = new JSONObject(true);
            o.put("id", "jm_db");
            o.put("name", "DB脉");
            o.put("defaultMax", 180);
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public JSONArray titles() {
            JSONObject o = new JSONObject(true);
            o.put("id", 5);
            o.put("name", "DB称号");
            o.put("type", 1);
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public JSONArray bots() {
            JSONObject o = new JSONObject(true);
            o.put("id", 1L);
            o.put("key", "db-bot");
            o.put("name", "DB假人");
            o.put("enabled", true);
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public Long originCount() {
            return 7L;
        }
    }
}
