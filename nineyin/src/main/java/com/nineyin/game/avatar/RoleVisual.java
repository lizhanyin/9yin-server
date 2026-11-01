package com.nineyin.game.avatar;

import com.nineyin.game.avatar.equipment.EquipmentResolver;

import java.util.List;

/**
 * 角色外观解析，对齐 Go cmd/protocol-probe/appearance.go resolveRoleVisual。
 * 当前创角抓包为男路径（sex=0）；cloth/pants/shoes 经装备表解析为模型路径。
 */
public record RoleVisual(int sex, String photo, String face, String hair,
                         String cloth, String pants, String shoes, String actionSet) {

    private static final String DEFAULT_PHOTO = "icon\\players\\boy01_create.png";
    /** 43 个 '3' + "X38" + 控制字符 0x01（Go 源同值）。 */
    private static final String DEFAULT_FACE =
            "333333333333333333333333333333333333333333" + "X38" + Character.toString(0x01);
    private static final String DEFAULT_HAIR = "obj\\char\\b_hair\\b_hair1";

    public static RoleVisual resolve(List<String> appearance, EquipmentResolver equipment) {
        return new RoleVisual(
                0, // 当前 create-role 抓包为男路径
                get(appearance, 1, DEFAULT_PHOTO),
                get(appearance, 2, DEFAULT_FACE),
                get(appearance, 4, DEFAULT_HAIR),
                equipment.resolve(get(appearance, 5, "cloth_b0001"), 0),
                equipment.resolve(get(appearance, 6, "pants_b0001"), 0),
                equipment.resolve(get(appearance, 7, "shoes_b0001"), 0),
                "0h");
    }

    private static String get(List<String> appearance, int index, String fallback) {
        if (index >= 0 && index < appearance.size() && !appearance.get(index).isEmpty()) {
            return appearance.get(index);
        }
        return fallback;
    }
}
