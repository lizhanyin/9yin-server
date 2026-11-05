package com.nineyin.server.gm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GM 门派切换闭名单（Go cmd/protocol-probe/gm_factions.go 34 项同表）：
 * 面板裸键（shaolin / yihuagong / huashan…）→ 中文标签 + 规范门派编码
 * （school_* / force_* / newschool_*，即 appearance[8] 与 0x10 School/Force/
 * NewSchool 属性的存储形式）。
 *
 * <p>编码集与 modern 客户端 form_main_player.ini [school_name] 段一致
 * （{@link FactionNames} 显示名同源）。刻意闭名单：GM 可改角色原生门派，
 * 但绝不接受任意字符串（Go gm_factions.go 注释同款约束）。
 */
public final class GmFactionPresets {

    /** 门派预设：面板键 + 中文标签 + 规范编码。 */
    public record Faction(String key, String label, String faction) {
    }

    private static final Map<String, Faction> PRESETS = buildPresets();

    private static Map<String, Faction> buildPresets() {
        Object[][] rows = {
            // 原始门派
            {"shaolin", "少林", "school_shaolin"},
            {"wudang", "武当", "school_wudang"},
            {"emei", "峨眉", "school_emei"},
            {"gaibang", "丐帮", "school_gaibang"},
            {"tangmen", "唐门", "school_tangmen"},
            {"junzitang", "君子堂", "school_junzitang"},
            {"jinyiwei", "锦衣卫", "school_jinyiwei"},
            {"jilegu", "极乐谷", "school_jilegu"},
            {"mingjiao", "明教", "school_mingjiao"},
            {"tianshan", "天山派", "school_tianshan"},
            {"kunlun", "昆仑派", "school_kunlun"},
            {"xingtianbao", "刑天城", "school_xingtianbao"},
            {"wuxu", "无虚门", "school_wuxu"},
            // 江湖势力
            {"yihuagong", "移花宫", "force_yihua"},
            {"taohuadao", "桃花岛", "force_taohua"},
            {"xujia", "徐家庄", "force_xujia"},
            {"wanshou", "万兽山庄", "force_wanshou"},
            {"jinzhen", "金针沈家", "force_jinzhen"},
            {"wugenmen", "无根门", "force_wugen"},
            {"tianlun", "天轮寺", "force_tianlun"},
            {"qingyi", "青衣阁", "force_qingyi"},
            // 宗门 / 新门派
            {"xuedao", "血刀门", "newschool_xuedao"},
            {"huashan", "华山派", "newschool_huashan"},
            {"gumu", "古墓派", "newschool_gumu"},
            {"damo", "达摩派", "newschool_damo"},
            {"shenshui", "神水宫", "newschool_shenshui"},
            {"changfeng", "长风镖局", "newschool_changfeng"},
            {"nianluo", "念萝坝", "newschool_nianluo"},
            {"wuxian", "无仙教", "newschool_wuxian"},
            {"shenji", "神机营", "newschool_shenji"},
            {"xingmiao", "星渺阁", "newschool_xingmiao"},
            {"tianya", "天涯海阁", "newschool_tianya"},
            {"wanghui", "王回洲", "newschool_wanghui"},
            {"shenjihui", "神机汇", "newschool_shenjihui"},
        };
        Map<String, Faction> presets = new HashMap<>();
        for (Object[] row : rows) {
            presets.put(((String) row[0]).toLowerCase(Locale.ROOT),
                    new Faction((String) row[0], (String) row[1], (String) row[2]));
        }
        return Map.copyOf(presets);
    }

    /** 面板键查预设；未收录返回 null（入口与游戏侧据此拒绝）。 */
    public static Faction preset(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return PRESETS.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** 全部面板键（测试覆盖度断言用）。 */
    public static Set<String> keys() {
        return PRESETS.keySet();
    }

    private GmFactionPresets() {
    }
}
