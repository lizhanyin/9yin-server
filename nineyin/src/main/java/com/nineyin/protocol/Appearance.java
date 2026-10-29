package com.nineyin.protocol;

import java.util.List;

/**
 * 建角外观数组（appearance list）解析与角色视觉（RoleVisual）推导，
 * 对齐 Go resolve_role_visual + equipmentResolver：性别只信显式女性标记，
 * 装备 config 经 equipment.ini/itemartstatic.ini 解析成真实模型路径
 * （cloth_b0001 → obj\char\b_jianghu001\b_cloth001），链路缺失回退 config 原值。
 */
public final class Appearance {

    public record RoleVisual(byte sex, String photo, String face, String hair,
                             String cloth, String pants, String shoes, String actionSet) {
    }

    /** Go defaultRoleFaction：门派固定缺省（移花宫线）。 */
    public static final String DEFAULT_FACTION = "force_yihua";

    private static final String DEFAULT_FACE = "333333333333333333333333333333333333333333X38\u0001";

    private Appearance() {
    }

    public static String get(List<String> a, int i, String fallback) {
        if (i < a.size() && !a.get(i).isEmpty()) {
            return a.get(i);
        }
        return fallback;
    }

    /** 门派：appearance[8]，缺省 force_yihua（对齐 Go roleFaction/defaultRoleAppearance）。 */
    public static String faction(List<String> appearance) {
        return get(appearance, 8, DEFAULT_FACTION);
    }

    public static RoleVisual resolveVisual(List<String> appearance) {
        String photo = get(appearance, 1, "");
        String hair = get(appearance, 4, "");
        String cloth = get(appearance, 5, "");
        String pants = get(appearance, 6, "");
        String shoes = get(appearance, 7, "");
        boolean female = containsIgnoreCase(hair, "g_hair")
                || containsIgnoreCase(cloth, "cloth_g")
                || containsIgnoreCase(pants, "pants_g")
                || containsIgnoreCase(shoes, "shoes_g")
                || containsIgnoreCase(photo, "girl")
                || (containsIgnoreCase(photo, "players") && containsIgnoreCase(photo, "g_"));
        byte sex = (byte) (female ? 1 : 0);
        if (female) {
            return new RoleVisual(sex,
                    get(appearance, 1, "icon\\players\\girl01_create.png"),
                    get(appearance, 2, DEFAULT_FACE),
                    get(appearance, 4, "obj\\char\\g_hair\\g_hair1"),
                    EquipmentModels.resolve(get(appearance, 5, "cloth_g0001"), sex),
                    EquipmentModels.resolve(get(appearance, 6, "pants_g0001"), sex),
                    EquipmentModels.resolve(get(appearance, 7, "shoes_g0001"), sex),
                    "0h");
        }
        return new RoleVisual(sex,
                get(appearance, 1, "icon\\players\\boy01_create.png"),
                get(appearance, 2, DEFAULT_FACE),
                get(appearance, 4, "obj\\char\\b_hair\\b_hair1"),
                EquipmentModels.resolve(get(appearance, 5, "cloth_b0001"), sex),
                EquipmentModels.resolve(get(appearance, 6, "pants_b0001"), sex),
                EquipmentModels.resolve(get(appearance, 7, "shoes_b0001"), sex),
                "0h");
    }

    private static boolean containsIgnoreCase(String s, String sub) {
        return s.toLowerCase().contains(sub);
    }
}
