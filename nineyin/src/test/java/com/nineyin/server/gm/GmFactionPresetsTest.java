package com.nineyin.server.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * GM 门派闭名单（Go gm_factions.go 34 项同表）：面板键 → 规范编码
 * （school_/force_/newschool_，与 form_main_player.ini [school_name] 段一致）；
 * 未知/空键拒绝，大小写与空白容错。
 */
class GmFactionPresetsTest {

    @Test
    void tableMirrorsGoClosedList() {
        assertEquals(34, GmFactionPresets.keys().size(), "门派预设应为 Go 同款 34 项");
        // 三段各抽样：八大门派 / 后续门派 / 江湖势力 / 宗门。
        assertPreset("shaolin", "少林", "school_shaolin");
        assertPreset("jilegu", "极乐谷", "school_jilegu");
        assertPreset("mingjiao", "明教", "school_mingjiao");
        assertPreset("wuxu", "无虚门", "school_wuxu");
        assertPreset("yihuagong", "移花宫", "force_yihua");
        assertPreset("wugenmen", "无根门", "force_wugen");
        assertPreset("huashan", "华山派", "newschool_huashan");
        assertPreset("shenjihui", "神机汇", "newschool_shenjihui");
        // 全表前缀分布：13 school + 8 force + 13 newschool。
        Set<String> keys = GmFactionPresets.keys();
        long school = keys.stream().filter(k -> GmFactionPresets.preset(k).faction().startsWith("school_")).count();
        long force = keys.stream().filter(k -> GmFactionPresets.preset(k).faction().startsWith("force_")).count();
        long newschool = keys.stream().filter(k -> GmFactionPresets.preset(k).faction().startsWith("newschool_")).count();
        assertEquals(13, school);
        assertEquals(8, force);
        assertEquals(13, newschool);
    }

    /** console.html 门派下拉全部 option value 必须在闭名单内（2026-11 提取 34 项）。 */
    @Test
    void tableCoversAllConsoleHtmlOptions() {
        for (String key : new String[]{
                "shaolin", "wudang", "emei", "gaibang", "tangmen", "junzitang", "jinyiwei", "jilegu",
                "mingjiao", "tianshan", "kunlun", "xingtianbao", "wuxu",
                "yihuagong", "taohuadao", "wugenmen", "xujia", "wanshou", "jinzhen", "tianlun", "qingyi",
                "xuedao", "huashan", "gumu", "damo", "shenshui", "changfeng", "nianluo", "wuxian",
                "shenji", "xingmiao", "tianya", "wanghui", "shenjihui"}) {
            assertNotNull(GmFactionPresets.preset(key), "面板选项预设缺失: " + key);
        }
    }

    @Test
    void unknownOrBlankKeysRejectedWithCaseAndTrimTolerance() {
        assertNull(GmFactionPresets.preset("definitely_not_a_faction"));
        assertNull(GmFactionPresets.preset("school_少林"));
        assertNull(GmFactionPresets.preset(""));
        assertNull(GmFactionPresets.preset(null));
        // 闭名单外的规范编码也拒（必须走面板键，不收原始编码）。
        assertNull(GmFactionPresets.preset("school_wudang"));
        assertEquals("school_wudang", GmFactionPresets.preset(" WuDang ").faction(), "大小写/空白容错");
        assertTrue(GmFactionPresets.preset("wudang").label().contains("武当"));
    }

    private static void assertPreset(String key, String label, String faction) {
        GmFactionPresets.Faction preset = GmFactionPresets.preset(key);
        assertNotNull(preset, "预设缺失: " + key);
        assertEquals(faction, preset.faction(), key + " 编码");
        assertEquals(label, preset.label(), key + " 标签");
    }
}
