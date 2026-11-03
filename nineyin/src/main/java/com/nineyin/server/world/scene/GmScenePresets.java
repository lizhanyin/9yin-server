package com.nineyin.server.world.scene;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.nineyin.server.db.role.Roles;

/**
 * GM 常用传送预设表（Go cmd/protocol-probe/gm_scene_presets.go 38 项 + menu2 exe
 * 48 字节步长 {key,label,dest} 数组提取的 105 项并集，共 107 键，覆盖
 * gm-web console.html 全部 preset() 按钮）。
 *
 * <p>坐标来源：主城/新手村/门派/隐世宗门为 Go 表人工核定（scenes.ini +
 * door_data.ini Target* / homepoint.ini Safe=1）；scene、adv、schoolwar、
 * worldwar、fight、prison 等为 exe 表原值（tools/smoke/extract_gm_presets.py
 * 可再生成，menu2_preset_full.tsv 为中间产物）。
 *
 * <p>免费格式输入（switch_scene 的 config,resource,x,y,z,orient 与 teleport 的
 * x,y,z,orient）解析共用 {@link #parseDestination}/{@link #parsePosition}，
 * 坐标域与 Go parseSceneDestination/parseScenePosition 对齐（有限值；|x|,|z|≤10000、
 * |y|≤3000、|朝向|≤20，同 motion 0x0A 包口径）。
 */
public final class GmScenePresets {

    /** 预设目的地：中文标签 + 场景 + 出生坐标。 */
    public record Destination(String label, Roles.Scene scene, Roles.Position position) {
    }

    private static final Map<String, Destination> PRESETS = buildPresets();

    private static Map<String, Destination> buildPresets() {
        Object[][] rows = {
            {"adv011", "玄冥洞", "ini\\scene\\adv011", "adv011", 258.107f, 10.000f, 258.423f, 4.125f},
            {"adv023", "千机阁", "ini\\scene\\adv023", "adv023", 301.790f, 17.010f, 613.737f, 1.530f},
            {"adv024", "万象门", "ini\\scene\\adv024", "adv024", 308.906f, 10.001f, 331.527f, 0.679f},
            {"adv032", "玄冰洞", "ini\\scene\\adv032", "adv032", 210.575f, 11.132f, 431.546f, 4.333f},
            {"adv033", "烈焰洞", "ini\\scene\\adv033", "adv033", 7.614f, 36.340f, -502.141f, -3.095f},
            {"adv041", "碧波潭", "ini\\scene\\adv041", "adv041", -209.739f, -79.761f, 17.464f, 0.105f},
            {"adv045", "桃花谷", "ini\\scene\\adv045", "adv045", -6.007f, 24.578f, -224.976f, 1.290f},
            {"adv052", "寒冰洞", "ini\\scene\\adv052", "adv052", 766.180f, -112.050f, -402.610f, 0.000f},
            {"adv053", "火焰洞", "ini\\scene\\adv053", "adv053", -52.686f, 9.973f, -57.596f, -0.330f},
            {"adv054", "白雾谷", "ini\\scene\\adv054", "adv054", 478.745f, 6.895f, 792.173f, -2.100f},
            {"adv055", "青竹谷", "ini\\scene\\adv055", "adv055", 580.811f, 10.305f, 359.653f, 0.000f},
            {"adv057", "迷踪林", "ini\\scene\\adv057", "adv057", 2108.121f, 38.526f, 1518.933f, -2.773f},
            {"adv100", "藏经阁", "ini\\scene\\adv100", "adv100", 537.653f, 10.000f, 741.037f, 0.000f},
            {"adv101", "天机阁", "ini\\scene\\adv101", "adv101", 770.951f, 136.078f, -63.570f, -1.285f},
            {"adv102", "天武阁", "ini\\scene\\adv102", "adv102", 36.161f, -15.976f, -26.962f, -0.130f},
            {"adv106", "望月台", "ini\\scene\\adv106", "adv106", -66.379f, 66.356f, -202.855f, -0.326f},
            {"adv120", "凌云台", "ini\\scene\\adv120", "adv120", 938.943f, 27.225f, -1794.840f, -1.570f},
            {"adv121", "千雪峰", "ini\\scene\\adv121", "adv121", -310.059f, 78.565f, 331.445f, 1.070f},
            {"adv122", "望月崖", "ini\\scene\\adv122", "adv122", 386.992f, 53.088f, -213.057f, 1.400f},
            {"adv123", "观星台", "ini\\scene\\adv123", "adv123", 134.270f, -5.834f, -42.644f, 0.000f},
            {"adv148", "明月(冥岳)", "ini\\scene\\adv148", "adv148", -190.451f, 22.572f, -244.972f, 0.000f},
            {"adv152", "五行天宫", "ini\\scene\\adv152", "adv152", 91.693f, 55.942f, -127.588f, 0.000f},
            {"adv153", "净天宫", "ini\\scene\\adv153", "adv153", 91.693f, 55.942f, -127.588f, 0.000f},
            {"adv199", "宝藏岛", "ini\\scene\\adv199", "adv199", 171.153f, 143.187f, 636.052f, -2.325f},
            {"changfeng", "长风镖局", "ini\\scene\\city03_JinLing", "city03", 1582.000f, 9.000f, 470.000f, 0.000f},
            {"chengdu", "成都", "ini\\scene\\city05_ChengDu", "city05", 911.073f, 31.192f, 537.350f, 1.742f},
            {"damo", "达摩派", "ini\\scene\\school18_damo", "school18", -373.021f, 276.227f, -573.989f, 0.000f},
            {"emei", "峨眉", "ini\\scene\\school06_EMei", "school06", 687.422f, 36.738f, 726.217f, 5.836f},
            {"erengu", "恶人谷", "ini\\scene\\born02_ERenGU", "born02", 905.048f, 10.810f, 196.980f, 0.000f},
            {"fight05", "比武场-五", "ini\\scene\\fight05", "fight05", -13.273f, -13.092f, -118.228f, 0.885f},
            {"fight06", "比武场-六", "ini\\scene\\fight06", "fight06", -220.572f, 66.528f, -132.986f, 0.000f},
            {"fight08", "比武场-八", "ini\\scene\\fight08", "fight08", -30.839f, 178.946f, 109.195f, 0.000f},
            {"gaibang", "丐帮", "ini\\scene\\school02_GaiBang", "school02", 522.804f, 10.774f, 694.848f, 2.056f},
            {"groupscene045", "天隐谷", "ini\\scene\\groupscene045", "groupscene045", -850.033f, 178.401f, 335.472f, 0.000f},
            {"gumu", "古墓派", "ini\\scene\\school14_gumupai", "school14", -277.595f, 355.876f, -325.481f, 0.000f},
            {"huashan", "华山派", "ini\\scene\\school17_huashanpai", "school17", 1563.800f, 133.500f, 694.700f, 0.000f},
            {"jilegu", "极乐谷", "ini\\scene\\school04_JiLeGu", "school04", 851.084f, 14.440f, 373.077f, 2.892f},
            {"jimingyi", "鸡鸣驿", "ini\\scene\\born01_MoHeZhen", "born01", 998.817f, 8.364f, 530.187f, -1.498f},
            {"jinling", "金陵", "ini\\scene\\city03_JinLing", "city03", 1675.078f, 10.726f, -101.141f, 0.335f},
            {"jinyiwei", "锦衣卫", "ini\\scene\\school01_JinYiWei", "school01", 480.046f, 49.653f, 473.986f, 0.621f},
            {"jinzhen", "金针沈家", "ini\\scene\\city05_ChengDu", "city05", 354.750f, 81.061f, 800.000f, 0.000f},
            {"junzitang", "君子堂", "ini\\scene\\school03_JunZiTang", "school03", 1168.737f, 9.443f, 515.075f, 4.740f},
            {"kunlunpai", "昆仑派", "ini\\scene\\school24_kunlun", "school24", -309.278f, 70.738f, -110.765f, 0.000f},
            {"loading01", "世界王场景", "ini\\scene\\loading01", "loading01", 500.000f, 50.000f, 500.000f, 0.000f},
            {"luoyang", "洛阳", "ini\\scene\\city04_LuoYang", "city04", 243.387f, 43.277f, 403.821f, 5.024f},
            {"mingjiao", "明教", "ini\\scene\\school20_mingjiao", "school20", 274.763f, 20.669f, -213.752f, 0.000f},
            {"nianluo", "念萝坝", "ini\\scene\\school15_nianluoba", "school15", 2133.586f, 108.031f, 1007.968f, 0.000f},
            {"prison", "监狱", "ini\\scene\\prison01", "prison01", 623.298f, 10.100f, 719.871f, 4.703f},
            {"qiandengzhen", "千灯镇", "ini\\scene\\born04_QianDengZhen", "born04", 883.000f, -29.000f, 664.000f, 4.680f},
            {"qingyi", "青衣阁", "ini\\scene\\adv158_qingyi", "adv158", 1316.718f, 21.183f, 1389.666f, 0.000f},
            {"scene01", "游云十六洲", "ini\\scene\\scene01_YouYunShiLiuZhou", "scene01", 970.337f, 55.148f, 168.989f, 0.000f},
            {"scene02", "蓬莱仙境", "ini\\scene\\scene02_PengLaiXianJing", "scene02", 382.540f, 52.455f, -95.517f, 0.000f},
            {"scene03", "泛黄平原", "ini\\scene\\scene03_FanHuangPingYuan", "scene03", 463.638f, -11.759f, 837.899f, 0.000f},
            {"scene04", "襄阳坡", "ini\\scene\\scene04_XiangYangPo", "scene04", 398.911f, -7.890f, 939.204f, 0.000f},
            {"scene04b", "向阳坡北", "ini\\scene\\scene04_2_XiangYangPoBei", "scene04_2", 128.369f, 77.600f, 291.588f, 0.420f},
            {"scene05", "念萝坝", "ini\\scene\\scene05_NianLuoBa", "scene05", 1589.168f, 9.070f, 1605.329f, 0.000f},
            {"scene06", "凌霄城", "ini\\scene\\scene06_LingXiaoCheng", "scene06", 543.386f, -5.012f, 1015.991f, 0.000f},
            {"scene07", "梅花岭", "ini\\scene\\scene07_MeiHuaLing", "scene07", 213.920f, 17.869f, 334.236f, 0.000f},
            {"scene08", "塞外大漠", "ini\\scene\\scene08_SaiWaiDaMo", "scene08", 387.267f, 6.243f, 679.848f, 0.000f},
            {"scene09", "塞北草原", "ini\\scene\\scene09_SaiBeiCaoYuan", "scene09", 1050.974f, 1.918f, 1525.646f, 0.000f},
            {"scene10", "华山论剑", "ini\\scene\\scene10_HuaShanLunJian", "scene10", 606.414f, 9.979f, 1999.919f, 2.910f},
            {"scene11", "快活岛", "ini\\scene\\scene11_KuaiHuoDao", "scene11", 123.343f, 142.224f, 363.031f, 0.360f},
            {"scene12", "场景12", "ini\\scene\\scene12", "scene12", 236.060f, 2.800f, 2307.560f, -2.558f},
            {"scene13", "场景13", "ini\\scene\\scene13", "scene13", 253.390f, 122.533f, 94.501f, -1.815f},
            {"scene14", "场景14", "ini\\scene\\scene14", "scene14", 26.705f, 66.846f, -177.505f, -1.045f},
            {"scene15", "场景15", "ini\\scene\\scene15", "scene15", -1101.704f, 65.021f, 1530.730f, 2.670f},
            {"scene16", "蓬莱岛(侠客岛北)", "ini\\scene\\scene16", "scene16", 557.225f, 371.279f, -408.049f, 0.000f},
            {"scene17", "侠客岛", "ini\\scene\\scene17", "scene17", 1513.167f, 54.542f, -901.780f, 0.000f},
            {"scene19", "无寂道", "ini\\scene\\scene19", "scene19", -187.085f, 109.788f, -102.474f, 0.000f},
            {"scene32", "永夜城", "ini\\scene\\scene32", "scene32", 1566.019f, 129.908f, 1402.802f, 0.000f},
            {"scene33", "鬼市", "ini\\scene\\scene33", "scene33", 1619.491f, -50.386f, 1139.963f, 0.000f},
            {"schoolwar10", "门派战-峨眉", "ini\\scene\\schoolwar10", "schoolwar10", 530.336f, 143.805f, 81.143f, 0.000f},
            {"schoolwar11", "门派战-武当", "ini\\scene\\schoolwar11", "schoolwar11", 740.706f, -4.578f, 531.873f, 0.000f},
            {"schoolwar12", "门派战-少林", "ini\\scene\\schoolwar12", "schoolwar12", 975.385f, 108.382f, 275.035f, 0.000f},
            {"schoolwar20", "门派战-明教", "ini\\scene\\schoolwar20", "schoolwar20", -92.102f, 54.636f, -257.540f, 0.000f},
            {"schoolwar22", "门派战-天山", "ini\\scene\\schoolwar22", "schoolwar22", 1245.243f, 183.172f, 998.789f, 0.000f},
            {"schoolwar5", "门派战-锦衣卫", "ini\\scene\\schoolwar5", "schoolwar5", 584.693f, 31.252f, 167.686f, 0.000f},
            {"schoolwar6", "门派战-丐帮", "ini\\scene\\schoolwar6", "schoolwar6", 403.524f, 12.555f, 375.692f, 0.000f},
            {"schoolwar7", "门派战-君子堂", "ini\\scene\\schoolwar7", "schoolwar7", 712.727f, 100.759f, 264.572f, 0.000f},
            {"schoolwar8", "门派战-极乐谷", "ini\\scene\\schoolwar8", "schoolwar8", 649.107f, 28.465f, 144.521f, 0.000f},
            {"schoolwar9", "门派战-唐门", "ini\\scene\\schoolwar9", "schoolwar9", 1093.788f, 100.665f, -315.992f, 0.000f},
            {"shaolin", "少林", "ini\\scene\\school08_ShaoLin", "school08", 517.669f, -52.775f, 670.125f, 2.537f},
            {"shenji", "神机营", "ini\\scene\\school21_shenjiying", "school21", -45.400f, 18.600f, -46.900f, 0.000f},
            {"shenjihui", "神机汇", "ini\\scene\\school31_shenjihui", "school31", -48.765f, -153.637f, -4770.149f, 0.000f},
            {"shenshui", "神水宫", "ini\\scene\\school19_shenshuigong", "school19", 2269.324f, 162.736f, -1379.467f, 0.000f},
            {"suzhou", "苏州", "ini\\scene\\city02_SuZhou", "city02", 438.140f, 0.800f, 1188.282f, -2.325f},
            {"tangmen", "唐门", "ini\\scene\\school05_TangMen", "school05", 716.682f, 6.774f, 495.249f, 5.705f},
            {"taohuadao", "桃花岛", "ini\\scene\\school10_taohuadao", "school10", -603.500f, 95.779f, -259.100f, 0.255f},
            {"tianlun", "天轮寺", "ini\\scene\\adv156_tianlun", "adv156", -45.777f, -34.792f, -243.900f, 0.000f},
            {"tianlundb", "活动-天伦夺宝", "ini\\scene\\adv034", "adv034", -298.927f, 151.821f, -195.300f, 1.970f},
            {"tianshanpai", "天山派", "ini\\scene\\school22_tianshan", "school22", 1245.243f, 183.172f, 998.789f, 0.000f},
            {"tianya", "天涯海阁", "ini\\scene\\school25_tianya", "school25", 1745.635f, 18.729f, 1066.640f, 0.000f},
            {"wanghui", "王回洲", "ini\\scene\\school28_wanghui", "school28", 119.267f, 12.734f, 3.625f, 0.000f},
            {"wanshou", "万兽山庄", "ini\\scene\\school02_GaiBang", "school02", 338.321f, 87.146f, 1016.193f, 2.927f},
            {"worldwar01", "世界战场(一)", "ini\\scene\\world_war_01", "world_war_01", 870.736f, 54.420f, 76.830f, 0.000f},
            {"worldwar02", "世界战场(二)", "ini\\scene\\world_war_02", "world_war_02", 917.684f, 9.802f, 341.101f, 0.340f},
            {"wudang", "武当", "ini\\scene\\school07_WuDang", "school07", 324.751f, -0.405f, 812.974f, 2.137f},
            {"wugenmen", "无根门", "ini\\scene\\school11_wugenmen", "school11", 34.200f, 4.600f, 245.500f, 1.500f},
            {"wuxian", "无仙教", "ini\\scene\\school12_wuxianjiao", "school12", 1451.899f, 49.252f, 799.405f, 0.000f},
            {"wuxu", "无虚门", "ini\\scene\\school30_wuxu", "school30", -3927.438f, 69.360f, -5407.769f, 0.000f},
            {"xingmiao", "星渺阁", "ini\\scene\\school23_xingmiaoge", "school23", 259.598f, 246.446f, 70.540f, 0.000f},
            {"xingtianbao", "刑天堡", "ini\\scene\\school26_xingtianbao", "school26", -384.018f, 68.752f, 302.409f, 0.000f},
            {"xuedao", "血刀门", "ini\\scene\\born02_ERenGU", "born02", 552.000f, 32.500f, 922.000f, 0.000f},
            {"xujia", "徐家庄", "ini\\scene\\city05_ChengDu", "city05", 569.750f, 39.045f, 272.750f, 0.000f},
            {"yanjing", "燕京", "ini\\scene\\city01_YanJing", "city01", 837.625f, -35.939f, -750.860f, 0.680f},
            {"yanyuzhuang", "烟雨庄", "ini\\scene\\born03_YanYuZhuang", "born03", 870.655f, 58.287f, 717.367f, -1.513f},
            {"yihuagong", "移花宫", "ini\\scene\\school09_YiHuaGong", "school09", 340.160f, 47.979f, 657.284f, 2.588f},
        };
        Map<String, Destination> out = new HashMap<>(rows.length * 2);
        for (Object[] row : rows) {
            out.put((String) row[0], new Destination((String) row[1],
                    new Roles.Scene((String) row[2], (String) row[3]),
                    new Roles.Position((Float) row[4], (Float) row[5],
                            (Float) row[6], (Float) row[7])));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private GmScenePresets() {
    }

    /** 预设键查找；未知键返回 null（调用方回 409 文案）。 */
    public static Destination preset(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return PRESETS.get(key);
    }

    /** 全部预设键（白名单校验/测试用）。 */
    public static Set<String> keys() {
        return PRESETS.keySet();
    }

    /**
     * 解析 config,resource,x,y,z,orient（GM 自定义切图输入）。
     * 失败抛 IllegalArgumentException（文案直接回面板）。
     */
    public static Roles.Location parseDestination(String value) {
        if (value == null) {
            throw new IllegalArgumentException("目的地为空");
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 6 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("应为 config,resource,x,y,z,orient");
        }
        float[] pos = parseFour(parts, 2, "坐标");
        return new Roles.Location(
                new Roles.Scene(parts[0].trim(), parts[1].trim()),
                new Roles.Position(pos[0], pos[1], pos[2], pos[3]),
                0L);
    }

    /** 解析 x,y,z,orient（场景内瞬移输入）。 */
    public static Roles.Position parsePosition(String value) {
        if (value == null) {
            throw new IllegalArgumentException("坐标为空");
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("应为 x,y,z,orient");
        }
        float[] pos = parseFour(parts, 0, "坐标");
        return new Roles.Position(pos[0], pos[1], pos[2], pos[3]);
    }

    private static float[] parseFour(String[] parts, int offset, String label) {
        float[] values = new float[4];
        for (int i = 0; i < 4; i++) {
            try {
                values[i] = Float.parseFloat(parts[offset + i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " 第 " + (i + 1) + " 段不是数字");
            }
            if (!Float.isFinite(values[i])) {
                throw new IllegalArgumentException(label + " 第 " + (i + 1) + " 段必须为有限值");
            }
        }
        if (Math.abs(values[0]) > 10_000 || Math.abs(values[2]) > 10_000
                || Math.abs(values[1]) > 3_000 || Math.abs(values[3]) > 20) {
            throw new IllegalArgumentException("坐标超出合理范围（|x|,|z|≤10000、|y|≤3000、|朝向|≤20）");
        }
        return values;
    }
}
