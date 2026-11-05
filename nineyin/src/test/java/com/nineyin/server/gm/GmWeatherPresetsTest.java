package com.nineyin.server.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * GM 场景天气闭名单（客户端 share/rule/weather/sceneparastore/ 目录 49 键同表）：
 * 面板键 = 天气渲染包目录名；未知/空键拒绝，大小写与空白容错。
 */
class GmWeatherPresetsTest {

    /** 2026-11-05 提取自 9yin-go-server1/resources/modern/share/rule/weather/sceneparastore 全量目录。 */
    private static final Set<String> SCENEPARASTORE_DIRS = Set.of(
            "born04_time1", "born04_time2", "born04_time3", "born04_time4", "born04_time5", "born04_time6",
            "clone003_time1", "clone003_time2", "clone003_time3", "clone003_time4",
            "groupscene008_time1", "groupscene008_time2", "groupscene008_time3", "groupscene008_time4",
            "groupscene023", "groupscene023_02", "groupscene039",
            "heavy_fog_001", "heavy_fog_501", "heavy_night_time4", "heavy_ocean_rain",
            "heavy_rain_001", "heavy_rain_501", "heavy_sandstorm_001", "heavy_sandstorm_501",
            "heavy_snow_001", "heavy_snow_501", "heavy_snow_huodong01",
            "light_fog_001", "light_fog_501", "light_rain_001", "light_rain_501",
            "light_sandstorm_001", "light_sandstorm_501", "light_snow_001", "light_snow_501",
            "lunar_eclipse_001", "middling_fog_001", "middling_fog_501",
            "middling_rain_001", "middling_rain_501",
            "middling_sandstorm_001", "middling_sandstorm_501",
            "middling_snow_001", "middling_snow_501",
            "moon_night_time4", "scene10", "solar_eclipse_001", "xianglong_show");

    @Test
    void tableMirrorsSceneParaStoreDirectories() {
        assertEquals(49, GmWeatherPresets.keys().size(), "天气预设应与 sceneparastore 49 目录一致");
        assertEquals(SCENEPARASTORE_DIRS, GmWeatherPresets.keys(), "键集须逐目录对齐（新目录须同步加表）");
        // 天气族抽样（官方 tianqi_* 文案口径）+ 昼/夜变体。
        assertPreset("light_rain_001", "小雨（昼）");
        assertPreset("light_rain_501", "小雨（夜）");
        assertPreset("heavy_rain_501", "大雨（夜）");
        assertPreset("heavy_snow_001", "暴雪（昼）");
        assertPreset("heavy_fog_501", "大雾（夜）");
        assertPreset("heavy_sandstorm_001", "强沙暴（昼）");
        assertPreset("lunar_eclipse_001", "月食（天狗吃月）");
        assertPreset("solar_eclipse_001", "日食（天狗食日）");
        // 场景时段族抽样。
        assertPreset("born04_time1", "出生场景·时段1");
        assertPreset("groupscene023_02", "群战023·时段二");
        assertPreset("xianglong_show", "降龙演出");
        // 全表规约：键全小写字母数字下划线，标签非空。
        for (String key : GmWeatherPresets.keys()) {
            assertTrue(key.matches("[a-z0-9_]+"), "键规约违例: " + key);
            assertTrue(!GmWeatherPresets.preset(key).label().isBlank(), "标签缺失: " + key);
        }
    }

    @Test
    void unknownOrBlankKeysRejectedWithCaseAndTrimTolerance() {
        assertNull(GmWeatherPresets.preset("definitely_not_a_weather"));
        // 闭名单外的路径形态也拒（必须走面板键，不收 share 相对路径/任意字符串）。
        assertNull(GmWeatherPresets.preset("rule/weather/sceneparastore/heavy_rain_501"));
        assertNull(GmWeatherPresets.preset(""));
        assertNull(GmWeatherPresets.preset(null));
        assertEquals("heavy_rain_501",
                GmWeatherPresets.preset(" Heavy_Rain_501 ").key(), "大小写/空白容错");
    }

    private static void assertPreset(String key, String label) {
        GmWeatherPresets.Weather preset = GmWeatherPresets.preset(key);
        assertNotNull(preset, "预设缺失: " + key);
        assertEquals(label, preset.label(), key + " 标签");
    }
}
