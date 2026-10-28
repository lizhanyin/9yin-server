package com.nineyin.protocol;

import java.util.List;

/**
 * 建角外观数组（appearance list）解析与角色视觉（RoleVisual）推导，
 * 对齐 C++ resolve_role_visual：性别只信显式女性标记，装备 config 若无法
 * 解析为模型路径则回退默认模型（equipment.ini 解析在场景资源阶段接入）。
 */
public final class Appearance {

    public record RoleVisual(byte sex, String photo, String face, String hair,
                             String cloth, String pants, String shoes, String actionSet) {
    }

    private static final String DEFAULT_FACE = "333333333333333333333333333333333333333333X38\u0001";

    private Appearance() {
    }

    public static String get(List<String> a, int i, String fallback) {
        if (i < a.size() && !a.get(i).isEmpty()) {
            return a.get(i);
        }
        return fallback;
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
                    fallbackModel(get(appearance, 5, "cloth_g0001"), "obj\\char\\g_cloth\\g_cloth_001"),
                    fallbackModel(get(appearance, 6, "pants_g0001"), "obj\\char\\g_pants\\g_pants_001"),
                    fallbackModel(get(appearance, 7, "shoes_g0001"), "obj\\char\\g_shoes\\g_shoes_001"),
                    "0h");
        }
        return new RoleVisual(sex,
                get(appearance, 1, "icon\\players\\boy01_create.png"),
                get(appearance, 2, DEFAULT_FACE),
                get(appearance, 4, "obj\\char\\b_hair\\b_hair1"),
                fallbackModel(get(appearance, 5, "cloth_b0001"), "obj\\char\\b_cloth\\b_cloth_001"),
                fallbackModel(get(appearance, 6, "pants_b0001"), "obj\\char\\b_pants\\b_pants_001"),
                fallbackModel(get(appearance, 7, "shoes_b0001"), "obj\\char\\b_shoes\\b_shoes_001"),
                "0h");
    }

    /** 已是路径直接用；裸 config id（equipment.ini 未接入前的形态）回退默认模型。 */
    private static String fallbackModel(String config, String defaultModel) {
        if (config.contains("\\") || config.contains("/")) {
            return config;
        }
        return defaultModel;
    }

    private static boolean containsIgnoreCase(String s, String sub) {
        return s.toLowerCase().contains(sub);
    }

    public static String bookDisplayName(String book) {
        return switch (book) {
            case "book1", "book3" -> "千灯镇起";
            case "book2" -> "天山洗剑录";
            case "book4" -> "成都风云";
            case "book5" -> "鸡鸣驿起";
            case "book6" -> "洛阳风云";
            case "book8" -> "恶人谷起";
            default -> book;
        };
    }

    public static String sceneDisplayName(String book) {
        return switch (book) {
            case "book1", "book3" -> "千灯镇";
            case "book2" -> "烟雨庄";
            case "book4" -> "成都";
            case "book5" -> "鸡鸣驿";
            case "book6" -> "洛阳";
            case "book8" -> "恶人谷";
            default -> "千灯镇";
        };
    }
}
