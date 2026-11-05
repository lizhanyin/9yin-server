package com.nineyin.server.gm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GM 场景天气闭名单（set_weather 动作数据源）：面板键 = 客户端
 * {@code share/rule/weather/sceneparastore/} 目录名（完整天气渲染包：weather.ini/
 * rain.ini/cloud.ini/pp*.ini），共 49 键。
 *
 * <p>协议链（res1/lua64 取证）：S2C 0x27 命令名寻址 {@code on_weather_sky}
 * （custom_handler.lua:5055，签名 sub_type,...）；sub_type=4 = 切换天气效果。
 * 现代客户端原生模式（weatherconfig.ini [GlobleRule] IsLua=0）走
 * {@code weather_manager:ChangeWeatherEffect(arg1,arg2,arg3,4)}，Lua 模式
 * （weather_set.lua）同参数收敛：arg1=TTID（SceneParaStore 目录名/时段）、
 * arg2=过渡秒、arg3=TXID 特殊特效串（空=无）。参数语义钉死于
 * {@code start_weather(year,month,date,hour,minute,section,fadeintime,specific)}
 * 与 initialize_weather_data 读 GetWeatherTTID/GetWeatherTTLastTime/GetWeatherTXID。
 *
 * <p>命名规约：{@code _001}=白天变体、{@code _501}=夜晚变体（SunHeight 差异实证）；
 * 中文文案对齐官方 17 类（interface.idres tianqi_1..17）。场景对象的 Weather@1448
 * 属性仅载图时读取（stage_main.lua:1025，且 scene_weather.ini 仅 5 个主城夜景段），
 * 不适用于运行时切换——恢复默认走切图重进（客户端载图重初始化天气）。
 *
 * <p>刻意闭名单（与 {@link GmFactionPresets} 同款约束）：set_weather 的 weather 键
 * 必须命中本表，绝不接受任意目录/资源字符串——GM 可换天气包，不可注入路径。
 */
public final class GmWeatherPresets {

    /** 天气预设：key = SceneParaStore 目录名；label = 面板中文显示名。 */
    public record Weather(String key, String label) {
    }

    private static final Map<String, Weather> PRESETS = buildPresets();

    private static Map<String, Weather> buildPresets() {
        Map<String, Weather> presets = new HashMap<>();
        // —— 天气族（官方 tianqi_* 文案口径）——
        put(presets, "light_rain_001", "小雨（昼）");
        put(presets, "light_rain_501", "小雨（夜）");
        put(presets, "middling_rain_001", "中雨（昼）");
        put(presets, "middling_rain_501", "中雨（夜）");
        put(presets, "heavy_rain_001", "大雨（昼）");
        put(presets, "heavy_rain_501", "大雨（夜）");
        put(presets, "heavy_ocean_rain", "海上暴雨");
        put(presets, "light_snow_001", "小雪（昼）");
        put(presets, "light_snow_501", "小雪（夜）");
        put(presets, "middling_snow_001", "中雪（昼）");
        put(presets, "middling_snow_501", "中雪（夜）");
        put(presets, "heavy_snow_001", "暴雪（昼）");
        put(presets, "heavy_snow_501", "暴雪（夜）");
        put(presets, "heavy_snow_huodong01", "暴雪·活动");
        put(presets, "light_fog_001", "小雾（昼）");
        put(presets, "light_fog_501", "小雾（夜）");
        put(presets, "middling_fog_001", "中雾（昼）");
        put(presets, "middling_fog_501", "中雾（夜）");
        put(presets, "heavy_fog_001", "大雾（昼）");
        put(presets, "heavy_fog_501", "大雾（夜）");
        put(presets, "light_sandstorm_001", "风沙（昼）");
        put(presets, "light_sandstorm_501", "风沙（夜）");
        put(presets, "middling_sandstorm_001", "沙暴（昼）");
        put(presets, "middling_sandstorm_501", "沙暴（夜）");
        put(presets, "heavy_sandstorm_001", "强沙暴（昼）");
        put(presets, "heavy_sandstorm_501", "强沙暴（夜）");
        put(presets, "lunar_eclipse_001", "月食（天狗吃月）");
        put(presets, "solar_eclipse_001", "日食（天狗食日）");
        put(presets, "moon_night_time4", "月夜");
        put(presets, "heavy_night_time4", "深夜");
        // —— 场景时段族（各场景基础时段渲染包，测试/演出用）——
        put(presets, "born04_time1", "出生场景·时段1");
        put(presets, "born04_time2", "出生场景·时段2");
        put(presets, "born04_time3", "出生场景·时段3");
        put(presets, "born04_time4", "出生场景·时段4");
        put(presets, "born04_time5", "出生场景·时段5");
        put(presets, "born04_time6", "出生场景·时段6");
        put(presets, "clone003_time1", "副本003·时段1");
        put(presets, "clone003_time2", "副本003·时段2");
        put(presets, "clone003_time3", "副本003·时段3");
        put(presets, "clone003_time4", "副本003·时段4");
        put(presets, "groupscene008_time1", "群战008·时段1");
        put(presets, "groupscene008_time2", "群战008·时段2");
        put(presets, "groupscene008_time3", "群战008·时段3");
        put(presets, "groupscene008_time4", "群战008·时段4");
        put(presets, "groupscene023", "群战023·时段");
        put(presets, "groupscene023_02", "群战023·时段二");
        put(presets, "groupscene039", "群战039·时段");
        put(presets, "scene10", "场景10·时段");
        put(presets, "xianglong_show", "降龙演出");
        return Map.copyOf(presets);
    }

    private static void put(Map<String, Weather> presets, String key, String label) {
        presets.put(key, new Weather(key, label));
    }

    /** 面板键查预设；空/未收录返回 null（入口与游戏侧据此拒绝）。 */
    public static Weather preset(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return PRESETS.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** 全部面板键（测试覆盖度断言用）。 */
    public static Set<String> keys() {
        return PRESETS.keySet();
    }

    private GmWeatherPresets() {
    }
}
