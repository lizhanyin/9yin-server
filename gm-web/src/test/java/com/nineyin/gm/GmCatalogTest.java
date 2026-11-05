package com.nineyin.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

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

    private GmCatalog catalog;

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

    /** scenes.ini 全表 → {id,config,resource,name}；非数字节/无 config 键跳过，同 config 去重。 */
    @Test
    void scenesCatalogFromRuleIniAndIdres(@TempDir Path modern) throws IOException {
        Path share = Files.createDirectories(modern.resolve("share"));
        Files.write(Files.createDirectories(share.resolve("rule")).resolve("scenes.ini"), (
                "[11]\nConfig=ini\\scene\\city04_LuoYang\nMaxVisuals=100\n"
                        + "[setting]\nConfig=ini\\scene\\skip_me\n"
                        + "[17]\nconfig=ini\\scene\\school07_WuDang\n"
                        + "[18]\nConfig=ini\\scene\\city04_LuoYang\n"
                        + "[19]\nName=no_config_key\n").getBytes(Charset.forName("GBK")));
        Path chineses = Files.createDirectories(modern.resolve("text").resolve("chineses"));
        Files.writeString(chineses.resolve("stringname.idres"),
                "ini\\scene\\city04_luoyang=洛阳\nschool07_wudang=武当\n",
                StandardCharsets.UTF_8);
        GmCatalog fileCatalog = GmCatalog.createForTest(share, dataDir);

        JSONArray scenes = fileCatalog.scenes();
        // setting 节非数字、19 节无 config、18 节与 11 节同 config（首见保留）→ 只剩 11、17。
        assertEquals(2, scenes.size());
        JSONObject luoyang = scenes.getJSONObject(0);
        assertEquals(11, luoyang.getIntValue("id"));
        assertEquals("ini\\scene\\city04_LuoYang", luoyang.getString("config"));
        assertEquals("city04_LuoYang", luoyang.getString("resource"));
        assertEquals("洛阳", luoyang.getString("name"), "idres 键为小写 config，应经小写回退命中");
        JSONObject wudang = scenes.getJSONObject(1);
        assertEquals("武当", wudang.getString("name"), "资源名小写键命中");
        assertEquals("school07_WuDang", wudang.getString("resource"));
    }

    /**
     * 「所有道具」列表清理（2026-11-05 真机反馈列表有错）：注释节泄漏
     * （//修炼药剂）、**名字以 stringname.idres 为准**（INI Name 列有抄错/占位——
     * 无妄 _9th 篇章名抄成"精修"、武器名抄成 xt_50301、页书名抄成"书"；
     * 空名/数字占位名无 idres 时回落有效 INI Name）、身份装内件 ViewID=11 不进背包目录。
     */
    @Test
    void itemsSkipCommentsResolveNamesAndDropNonBagViews(@TempDir Path modern) throws IOException {
        Path share = Files.createDirectories(modern.resolve("share"));
        Files.write(Files.createDirectories(share.resolve("item")).resolve("equipment.ini"), (
                "[sword_01]\nName=久仙刀\nViewID=2\nEquipType=Weapon\nscript=Equipment\n"
                        + "[amulet_10101]\nName=10101\nViewID=2\nEquipType=Treasure\n"
                        + "[helm_b019]\nViewID=2\nEquipType=Hat\n"
                        + "[inner_cloth_01]\nName=内件\nViewID=11\nEquipType=InnerCloth\n"
                        + "[//注释节]\nName=修炼药剂\nViewID=1\n").getBytes(Charset.forName("GBK")));
        Files.write(share.resolve("item").resolve("tool_item.ini"), (
                "[//包裹]\nName=包裹\nViewID=1\n"
                        + "[potion_01]\nName=金疮药\nViewID=1\nscript=ToolItem\n"
                        + "[book_9th]\nName=神功(精修)\nViewID=1\nscript=SkillBook\n"
                        + "[orphan_01]\nViewID=1\nscript=ToolItem\n").getBytes(Charset.forName("GBK")));
        Path chineses = Files.createDirectories(modern.resolve("text").resolve("chineses"));
        Files.writeString(chineses.resolve("stringname.idres"),
                "amulet_10101=龙纹护符\nhelm_b019=铁盔\n"
                        + "book_9th=神功(拾遗篇·一)\npotion_01=金疮药(大)\n",
                StandardCharsets.UTF_8);
        GmCatalog fileCatalog = GmCatalog.createForTest(share, dataDir);

        JSONArray items = fileCatalog.items("", 0);
        java.util.Map<String, JSONObject> byId = new java.util.LinkedHashMap<>();
        for (Object o : items) {
            JSONObject row = (JSONObject) o;
            byId.put(row.getString("configId"), row);
        }
        // 注释节与 ViewID=11 内件不进目录
        assertFalse(byId.containsKey("//注释节"));
        assertFalse(byId.containsKey("//包裹"));
        assertFalse(byId.containsKey("inner_cloth_01"), "身份装内件（view 11）应跳过");
        // idres 优先（与游戏内客户端名字同源；INI 抄错的篇章名以此纠正）
        assertEquals("神功(拾遗篇·一)", byId.get("book_9th").getString("name"),
                "INI 抄错名应被 idres 覆盖");
        assertEquals("金疮药(大)", byId.get("potion_01").getString("name"),
                "两有效名并存时 idres 优先");
        assertEquals("龙纹护符", byId.get("amulet_10101").getString("name"),
                "数字占位名由 idres 纠正");
        assertEquals("铁盔", byId.get("helm_b019").getString("name"), "空名由 idres 补齐");
        // idres 无键：回落有效 INI Name；数字/空占位保持空
        assertEquals("久仙刀", byId.get("sword_01").getString("name"));
        assertTrue(byId.containsKey("orphan_01")
                        && byId.get("orphan_01").getString("name").isEmpty(),
                "idres 也没有的保持空名（面板显示 ConfigID）");
        // 分类过滤正常（view=2 只剩装备三件）
        assertEquals(3, fileCatalog.items("", 2).size());
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
            JSONObject o = new JSONObject();
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
            JSONObject o = new JSONObject();
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
            JSONObject o = new JSONObject();
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
            JSONObject o = new JSONObject();
            o.put("id", "jm_db");
            o.put("name", "DB脉");
            o.put("defaultMax", 180);
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public JSONArray titles() {
            JSONObject o = new JSONObject();
            o.put("id", 5);
            o.put("name", "DB称号");
            o.put("type", 1);
            JSONArray arr = new JSONArray();
            arr.add(o);
            return arr;
        }

        @Override
        public JSONArray bots() {
            JSONObject o = new JSONObject();
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
