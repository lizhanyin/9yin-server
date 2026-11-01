package com.nineyin.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * GmCatalogDb 集成测试：直连本机 nineyin 库验证 7 个目录来源与
 * GmCatalog.useDatabase 接线。无 DSN（环境变量 NINEYIN_MYSQL_DSN 或根目录 mysql.env）
 * 或库不可用时整体跳过。
 */
class GmCatalogDbIntegrationTest {

    private static GmCatalogDb db;

    @BeforeAll
    static void setUp() {
        String dsn = System.getenv("NINEYIN_MYSQL_DSN");
        if (dsn == null || dsn.isBlank()) {
            dsn = GmEnv.loadDsn().orElse(null);
        }
        assumeTrue(dsn != null && !dsn.isBlank(), "未配置 MySQL DSN，跳过集成测试");
        try {
            db = GmCatalogDb.open(dsn);
        } catch (RuntimeException e) {
            assumeTrue(false, "本机 MySQL 不可用：" + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void cardsShapeAndOrder() {
        JSONArray cards = db.cards();
        assertNotNull(cards);
        assertTrue(cards.size() > 1000, "卡牌应有全量数据: " + cards.size());
        JSONObject first = cards.getJSONObject(0);
        assertEquals(1, first.getIntValue("id"));
        assertEquals("屠龙刀鞘", first.getString("name"));
        assertEquals(14400, first.getIntValue("config_id"));
        assertEquals("Weapon", first.getString("category"));
    }

    @Test
    void hairCardsNonEmpty() {
        JSONArray hair = db.hairCards();
        assertNotNull(hair);
        assertFalse(hair.isEmpty(), "模型含 head 的发型卡至少 1 张");
    }

    @Test
    void identitySuitsShape() {
        JSONArray suits = db.identitySuits();
        assertNotNull(suits);
        assertTrue(suits.size() > 100, "身份装应有全量数据: " + suits.size());
        JSONObject first = suits.getJSONObject(0);
        assertFalse(first.getString("configId").isEmpty());
        assertNotNull(first.getString("name"));
        assertTrue(first.containsKey("needSex"));
    }

    @Test
    void giftBoxIdsNonEmpty() {
        assertNotNull(db.giftBoxIds());
        assertFalse(db.giftBoxIds().isEmpty());
        assertTrue(db.giftBoxIds().stream().noneMatch(String::isBlank));
    }

    @Test
    void meridiansShape() {
        JSONArray meridians = db.meridians();
        assertNotNull(meridians);
        assertTrue(meridians.size() > 10, "经脉应有全量数据: " + meridians.size());
        JSONObject first = meridians.getJSONObject(0);
        assertTrue(first.getString("id").startsWith("jm_"));
        assertNotNull(first.getString("name"));
        assertTrue(first.getIntValue("defaultMax") > 0);
    }

    @Test
    void titlesShapeAndOrder() {
        JSONArray titles = db.titles();
        assertNotNull(titles);
        assertTrue(titles.size() > 1000, "称号应有全量数据: " + titles.size());
        JSONObject first = titles.getJSONObject(0);
        assertEquals(1, first.getIntValue("id"));
        assertEquals("小沙弥", first.getString("name"));
        assertTrue(first.getIntValue("type") > 0);
    }

    @Test
    void botsShape() {
        JSONArray bots = db.bots();
        assertNotNull(bots);
        if (bots.isEmpty()) {
            return; // 库里允许无假人
        }
        JSONObject bot = bots.getJSONObject(0);
        assertTrue(bot.getLongValue("id") > 0);
        assertFalse(bot.getString("key").isEmpty());
        assertNotNull(bot.getString("name"));
        assertNotNull(bot.get("scene"));
        assertNotNull(bot.get("position"));
    }

    @Test
    void originCountPositive() {
        Long count = db.originCount();
        assertNotNull(count);
        assertTrue(count > 1000, "出身应有全量数据: " + count);
    }

    @Test
    void catalogWiredToDatabase() {
        // 不调 catalog.close()：共享 db 池由 tearDown 统一关闭，避免影响其他用例。
        GmCatalog catalog = GmCatalog.createForTest(null, null);
        catalog.useDatabase(db);
        assertTrue(catalog.titles().size() > 1000);
        assertTrue(catalog.cards().size() > 1000);
        assertTrue(catalog.meridians().size() > 10);
        assertTrue(catalog.originStatus().getBooleanValue("loaded"));
        assertEquals(db.originCount().longValue(), catalog.originStatus().getLongValue("total"));
        assertTrue(catalog.fashionCatalog().size() >= 1);
    }
}
