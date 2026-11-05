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
 * GM 常用传送预设表：142 键（107 基础 + 踢馆 35）覆盖 gm-web console.html 全部按钮；
 * 抽样值对齐 Go gm_scene_presets.go（主城/门派）、menu2 exe 提取表（scene/adv/
 * schoolwar）与踢馆入口（exe 表 27 项 + 参考服 creator 推导 8 项）；免费格式解析
 * （switch_scene/teleport）数值域与失败文案。
 */
class GmScenePresetsTest {

    @Test
    void tableCoversAllConsoleHtmlPresetKeys() {
        // console.html 的 preset() slug 全集（2026-11 提取 107 个 + 踢馆 35 个）——
        // 数量下限 + 关键抽样，避免 HTML 变更时静默漂移。
        assertTrue(GmScenePresets.keys().size() >= 142, "预设表至少覆盖面板全部按钮");
        for (String key : new String[]{
                "yanjing", "suzhou", "jimingyi", "erengu", "jinyiwei", "wudang", "shaolin",
                "yihuagong", "taohuadao", "wugenmen", "xujia", "tianlun", "qingyi",
                "xuedao", "huashan", "gumu", "damo", "shenshui", "changfeng", "nianluo",
                "wuxian", "shenji", "xingmiao", "tianya", "wanghui", "wuxu", "shenjihui",
                "scene01", "scene04b", "scene33", "adv011", "adv199", "schoolwar5",
                "schoolwar22", "worldwar01", "fight05", "prison", "loading01",
                "tianlundb", "groupscene045", "mingjiao", "tianshanpai", "kunlunpai",
                "xingtianbao", "tiguan_1", "tiguan_19", "tiguan_35"}) {
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

    /**
     * 踢馆 35 键（console.html「踢馆传送」区块）：全部可解析、label 前缀一致；
     * 抽样官方 exe 表原值（tiguan_1/tiguan_19）与参考服 creator 入口 NPC 推导值
     * （tiguan_32，EventNpc_lzy_tg_tlj001 位置）。
     */
    @Test
    void tiguanPresetsCoverAll35Entrances() {
        for (int i = 1; i <= 35; i++) {
            GmScenePresets.Destination dest = GmScenePresets.preset("tiguan_" + i);
            assertNotNull(dest, "踢馆预设缺失: tiguan_" + i);
            assertTrue(dest.label().startsWith("踢馆-"), "tiguan_" + i + " label 应带踢馆前缀");
            assertTrue(dest.scene().config().startsWith("ini\\scene\\"),
                    "tiguan_" + i + " config 应为场景配置");
        }

        GmScenePresets.Destination huanhua = GmScenePresets.preset("tiguan_1");
        assertEquals("踢馆-浣花剑派(成都)", huanhua.label());
        assertEquals("ini\\scene\\city05_ChengDu", huanhua.scene().config());
        assertEquals("city05", huanhua.scene().resource());
        assertEquals(1164.707f, huanhua.position().x(), 1e-4f);
        assertEquals(694.912f, huanhua.position().z(), 1e-4f);

        GmScenePresets.Destination murong = GmScenePresets.preset("tiguan_19");
        assertEquals("ini\\scene\\city02_SuZhou", murong.scene().config());
        assertEquals(2163.734f, murong.position().z(), 1e-4f);

        GmScenePresets.Destination tianluo = GmScenePresets.preset("tiguan_32");
        assertEquals("踢馆-天罗教(塞外大漠)", tianluo.label());
        assertEquals("ini\\scene\\scene08_SaiWaiDaMo", tianluo.scene().config());
        assertEquals(1013.373f, tianluo.position().x(), 1e-4f);
        assertEquals(922.035f, tianluo.position().z(), 1e-4f);
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
