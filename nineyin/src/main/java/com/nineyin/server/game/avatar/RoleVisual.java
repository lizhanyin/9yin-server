package com.nineyin.server.game.avatar;

import com.nineyin.server.game.avatar.equipment.EquipmentResolver;

import java.util.List;

/**
 * 角色外观解析，对齐 Go cmd/protocol-probe/appearance.go resolveRoleVisual。
 * Go 侧 sex 硬编码 0（当年抓包只有男路径）；Java 侧按外观串前缀推断性别——
 * DB 无独立性别列，男女只体现在 role_appearances.appearance 的字符串里：
 * photo=girl|boy、hair 与套装路径段 g_|b_ 前缀、衣裤鞋配置 _g0xxx|_b0xxx
 * （线上实证 role 665=girl01/g_hair9/cloth_g0003）。
 */
public record RoleVisual(int sex, String photo, String face, String hair,
                         String cloth, String pants, String shoes, String actionSet) {

    private static final String DEFAULT_PHOTO = "icon\\players\\boy01_create.png";
    private static final String DEFAULT_PHOTO_FEMALE = "icon\\players\\girl01_create.png";
    /** 43 个 '3' + "X38" + 控制字符 0x01（Go 源同值）。女性脸型串无抓包基准，空值回退男默认。 */
    private static final String DEFAULT_FACE =
            "333333333333333333333333333333333333333333" + "X38" + Character.toString(0x01);
    private static final String DEFAULT_HAIR = "obj\\char\\b_hair\\b_hair1";
    private static final String DEFAULT_HAIR_FEMALE = "obj\\char\\g_hair\\g_hair1";

    public static RoleVisual resolve(List<String> appearance, EquipmentResolver equipment) {
        int sex = inferSex(appearance);
        String clothDefault = sex == 1 ? "cloth_g0001" : "cloth_b0001";
        String pantsDefault = sex == 1 ? "pants_g0001" : "pants_b0001";
        String shoesDefault = sex == 1 ? "shoes_g0001" : "shoes_b0001";
        return new RoleVisual(
                sex,
                get(appearance, 1, sex == 1 ? DEFAULT_PHOTO_FEMALE : DEFAULT_PHOTO),
                get(appearance, 2, DEFAULT_FACE),
                get(appearance, 4, sex == 1 ? DEFAULT_HAIR_FEMALE : DEFAULT_HAIR),
                equipment.resolve(get(appearance, 5, clothDefault), sex),
                equipment.resolve(get(appearance, 6, pantsDefault), sex),
                equipment.resolve(get(appearance, 7, shoesDefault), sex),
                "0h");
    }

    /**
     * 性别推断：photo（girl/boy）> hair（g_/b_ 路径段）> 衣裤鞋（*_g0/*_b0 或 g_/b_ 路径段）。
     * 全空/无证据 → 0（男，与历史行为一致）。
     */
    static int inferSex(List<String> appearance) {
        int byPhoto = photoSex(get(appearance, 1, ""));
        if (byPhoto >= 0) {
            return byPhoto;
        }
        int byHair = pathSex(get(appearance, 4, ""));
        if (byHair >= 0) {
            return byHair;
        }
        for (int index = 5; index <= 7; index++) {
            String value = get(appearance, index, "");
            int byCloth = pathSex(value);
            if (byCloth < 0 && (value.startsWith("cloth_") || value.startsWith("pants_") || value.startsWith("shoes_"))) {
                byCloth = value.contains("_g") ? 1 : value.contains("_b") ? 0 : -1;
            }
            if (byCloth >= 0) {
                return byCloth;
            }
        }
        return 0;
    }

    /** photo=icon\players\boy01_create.png / girl01_create.png。 */
    private static int photoSex(String photo) {
        String name = photo.toLowerCase();
        if (name.contains("girl")) {
            return 1;
        }
        if (name.contains("boy")) {
            return 0;
        }
        return -1;
    }

    /** 模型路径=obj\char\g_hair\g_hair9（末段前缀 g_/b_）；裸配置名=cloth_g0003 不含段也兜底。 */
    private static int pathSex(String value) {
        String lower = value.toLowerCase();
        if (lower.isEmpty()) {
            return -1;
        }
        int slash = lower.lastIndexOf('\\');
        String last = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (last.startsWith("g_")) {
            return 1;
        }
        if (last.startsWith("b_")) {
            return 0;
        }
        // 路径中段 g_*/b_*（obj\char\b_festival_1_106\b_cloth_1_106）：任一段命中即判
        for (String segment : lower.split("\\\\", -1)) {
            if (segment.startsWith("g_")) {
                return 1;
            }
            if (segment.startsWith("b_")) {
                return 0;
            }
        }
        return -1;
    }

    private static String get(List<String> appearance, int index, String fallback) {
        if (index >= 0 && index < appearance.size() && !appearance.get(index).isEmpty()) {
            return appearance.get(index);
        }
        return fallback;
    }
}
