package com.nineyin.server.world.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nineyin.server.db.role.Roles;
import com.nineyin.server.world.scene.GmScenePresets;

/**
 * GM 常用传送预设表：107 键覆盖 gm-web console.html 全部按钮；抽样值对齐
 * Go gm_scene_presets.go（主城/门派）与 menu2 exe 提取表（scene/adv/schoolwar）；
 * 免费格式解析（switch_scene/teleport）数值域与失败文案。
 */
class GmScenePresetsTest {

    @Test
    void tableCoversAllConsoleHtmlPresetKeys() {
        // console.html 的 preset() slug 全集（2026-11 提取 107 个）——数量下限 +
        // 关键抽样，避免 HTML 变更时静默漂移。
        assertTrue(GmScenePresets.keys().size() >= 107, "预设表至少覆盖面板全部按钮");
        for (String key : new String[]{
                "yanjing", "suzhou", "jimingyi", "erengu", "jinyiwei", "wudang", "shaolin",
                "yihuagong", "taohuadao", "wugenmen", "xujia", "tianlun", "qingyi",
                "xuedao", "huashan", "gumu", "damo", "shenshui", "changfeng", "nianluo",
                "wuxian", "shenji", "xingmiao", "tianya", "wanghui", "wuxu", "shenjihui",
                "scene01", "scene04b", "scene33", "adv011", "adv199", "schoolwar5",
                "schoolwar22", "worldwar01", "fight05", "prison", "loading01",
                "tianlundb", "groupscene045", "mingjiao", "tianshanpai", "kunlunpai",
                "xingtianbao"}) {
            assertNotNull(GmScenePresets.preset(key), "面板按钮预设缺失: " + key);
        }
        Set<String> keys = GmScenePresets.keys();
        for (String key : keys) {
            GmScenePresets.Destination dest = GmScenePresets.preset(key);
            assertNotNull(dest.label(), key + " 应有中文标签");
            assertTrue(dest.scene().config().startsWith("ini\\scene\\"), key + " config 应为场景配置");
            assertFalseEmptyResource(dest, key);
        }
    }

    private static void assertFalseEmptyResource(GmScenePresets.Destination dest, String key) {
        assertTrue(!dest.scene().resource().isEmpty(), key + " resource 非空");
    }

    /** 抽样：Go 表人工核定值（gm_scene_presets.go 原样）。 */
    @Test
    void spotChecksMatchGoCuratedTable() {
        GmScenePresets.Destination yanjing = GmScenePresets.preset("yanjing");
        assertEquals("燕京", yanjing.label());
        assertEquals("ini\\scene\\city01_YanJing", yanjing.scene().config());
        assertEquals("city01", yanjing.scene().resource());
        assertEquals(837.625f, yanjing.position().x(), 1e-4f);
        assertEquals(-750.860f, yanjing.position().z(), 1e-4f);

        GmScenePresets.Destination jimingyi = GmScenePresets.preset("jimingyi");
        assertEquals("ini\\scene\\born01_MoHeZhen", jimingyi.scene().config());
        assertEquals(-1.498f, jimingyi.position().orient(), 1e-4f);

        // normalizeClientScene 特例源头：scene09 塞北草原 config 全名。
        GmScenePresets.Destination saibei = GmScenePresets.preset("scene09");
        assertEquals("ini\\scene\\scene09_SaiBeiCaoYuan", saibei.scene().config());
    }

    /** 抽样：menu2 exe 48 字节表提取值。 */
    @Test
    void spotChecksMatchExeExtractedTable() {
        GmScenePresets.Destination adv011 = GmScenePresets.preset("adv011");
        assertEquals("玄冥洞", adv011.label());
        assertEquals(258.107f, adv011.position().x(), 1e-4f);

        GmScenePresets.Destination schoolwar5 = GmScenePresets.preset("schoolwar5");
        assertEquals("门派战-锦衣卫", schoolwar5.label());

        GmScenePresets.Destination mingjiao = GmScenePresets.preset("mingjiao");
        assertEquals("ini\\scene\\school20_mingjiao", mingjiao.scene().config());
        assertEquals(274.763f, mingjiao.position().x(), 1e-4f);
    }

    @Test
    void unknownPresetReturnsNull() {
        assertNull(GmScenePresets.preset("nonesuch"));
        assertNull(GmScenePresets.preset(""));
        assertNull(GmScenePresets.preset(null));
    }

    @Test
    void parseDestinationAcceptsValidInput() {
        Roles.Location location = GmScenePresets.parseDestination(
                " ini\\scene\\city04_LuoYang , city04 , 243.387,43.277,403.821,5.024 ");
        assertEquals("ini\\scene\\city04_LuoYang", location.scene().config());
        assertEquals("city04", location.scene().resource());
        assertEquals(243.387f, location.position().x(), 1e-4f);
        assertEquals(5.024f, location.position().orient(), 1e-4f);
        assertEquals(0L, location.version(), "免费格式无版本，由调用方沿用当前乐观版本");
    }

    @Test
    void parseDestinationRejectsBadInput() {
        assertEquals("应为 config,resource,x,y,z,orient",
                assertThrows(IllegalArgumentException.class,
                        () -> GmScenePresets.parseDestination("city04")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GmScenePresets.parseDestination("a,b,1,2,3,NaN"))
                .getMessage().contains("有限值"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GmScenePresets.parseDestination("a,b,x,2,3,4"))
                .getMessage().contains("不是数字"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GmScenePresets.parseDestination("a,b,99999,2,3,4"))
                .getMessage().contains("合理范围"));
        assertThrows(IllegalArgumentException.class, () -> GmScenePresets.parseDestination(null));
    }

    @Test
    void parsePositionAcceptsAndRejects() {
        Roles.Position position = GmScenePresets.parsePosition("100.5, 20, -300, 1.5");
        assertEquals(-300f, position.z(), 1e-4f);
        assertEquals("应为 x,y,z,orient",
                assertThrows(IllegalArgumentException.class,
                        () -> GmScenePresets.parsePosition("1,2,3")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GmScenePresets.parsePosition("0,5000,0,0"))
                .getMessage().contains("合理范围"));
    }
}
