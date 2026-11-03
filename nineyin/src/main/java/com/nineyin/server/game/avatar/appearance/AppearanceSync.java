package com.nineyin.server.game.avatar.appearance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.world.db.RoleInventoryStore;

/**
 * 装备外观挂载（V2「角色武器装备外观实现挂载」）：已穿戴装备推导人物外观
 * 部位（Cloth/Pants/Shoes/Hat/WeaponMode），登录归档与 GM 穿戴后实时 0x10 推送。
 *
 * <p>槽位口径：1 帽/3 衣/4 裤/6 腕/7 腿/8 鞋/22 武器（role_equip_items 实测，
 * {@link RoleInventoryStore.Equip} 注释钉死）；身份装/时装 16-19、新面板镜像
 * 40-43（GM 面板「时装/身份装发放」口径）。饰品（项链/耳环/戒指）槽位未证，
 * v1 不穿戴只入背包。
 */
public final class AppearanceSync {

    public static final int SLOT_HAT = 1;
    public static final int SLOT_CLOTH = 3;
    public static final int SLOT_PANTS = 4;
    public static final int SLOT_WRIST = 6;
    public static final int SLOT_LEG = 7;
    public static final int SLOT_SHOES = 8;
    public static final int SLOT_SUIT = 16;
    public static final int SLOT_FASHION_COAT = 17;
    public static final int SLOT_FASHION_HAT = 18;
    public static final int SLOT_FASHION_SHOES = 19;
    public static final int SLOT_WEAPON = 22;
    /** 身份装/时装新面板镜像槽基（16-19 → 40-43）。 */
    public static final int FASHION_MIRROR_BASE = 40;

    private static final Map<String, Integer> SLOTS = Map.ofEntries(
            Map.entry("Hat", SLOT_HAT),
            Map.entry("Cloth", SLOT_CLOTH),
            Map.entry("Pants", SLOT_PANTS),
            Map.entry("Wrist", SLOT_WRIST),
            Map.entry("Leg", SLOT_LEG),
            Map.entry("Shoes", SLOT_SHOES),
            Map.entry("Suit", SLOT_SUIT),
            Map.entry("NewSuit", SLOT_SUIT),
            Map.entry("FashionCoat", SLOT_FASHION_COAT),
            Map.entry("NewFashionCoat", SLOT_FASHION_COAT),
            Map.entry("FashionHat", SLOT_FASHION_HAT),
            Map.entry("FashionShoes", SLOT_FASHION_SHOES),
            Map.entry("Weapon", SLOT_WEAPON),
            Map.entry("ShotWeapon", SLOT_WEAPON),
            Map.entry("InnerWeapon", SLOT_WEAPON));

    private AppearanceSync() {
    }

    /** EquipType → 纸娃娃槽位；未知/饰品返回 0（调用方按不可穿戴处理）。 */
    public static int slotOfEquipType(String equipType) {
        if (equipType == null || equipType.isEmpty()) {
            return 0;
        }
        return SLOTS.getOrDefault(equipType, 0);
    }

    /** 时装/身份装槽（16-19）→ 新面板镜像槽（40-43）；非时装槽原样返回。 */
    public static int mirrorSlot(int slot) {
        if (slot >= SLOT_SUIT && slot <= SLOT_FASHION_SHOES) {
            return FASHION_MIRROR_BASE + (slot - SLOT_SUIT);
        }
        return slot;
    }

    /**
     * 登录外观合并：已穿戴 Hat/Cloth/Pants/Shoes 装备的模型路径覆盖
     * {@link RoleVisual} 对应部位（无装备保持创角初始装）——重登外观不再
     * 回落「初始屌丝装」。
     */
    public static RoleVisual mergeVisual(RoleVisual base, int sex,
                                         List<RoleInventoryStore.Equip> equips,
                                         EquipmentResolver resolver) {
        Map<Integer, String> models = wornModels(sex, equips, resolver);
        return new RoleVisual(base.sex(), base.photo(), base.face(), base.hair(),
                models.getOrDefault(SLOT_CLOTH, base.cloth()),
                models.getOrDefault(SLOT_PANTS, base.pants()),
                models.getOrDefault(SLOT_SHOES, base.shoes()),
                base.actionSet());
    }

    /** 已穿装备按槽位 → 模型路径（后穿覆盖先穿，同槽只留一件）。 */
    public static Map<Integer, String> wornModels(int sex,
                                                  List<RoleInventoryStore.Equip> equips,
                                                  EquipmentResolver resolver) {
        Map<Integer, String> models = new HashMap<>();
        for (RoleInventoryStore.Equip equip : equips) {
            int slot = equip.slot();
            if (slot != SLOT_HAT && slot != SLOT_CLOTH && slot != SLOT_PANTS && slot != SLOT_SHOES
                    && slot != SLOT_FASHION_COAT && slot != SLOT_FASHION_HAT && slot != SLOT_FASHION_SHOES) {
                continue; // 只有衣裤鞋帽/时装改变模型；武器走 WeaponMode 通道
            }
            String model = resolver.resolve(equip.configId(), sex);
            if (model != null && !model.isEmpty()) {
                models.put(slot, model);
            }
        }
        return models;
    }

    /**
     * 外观属性帧（0x10）：衣/裤/鞋/帽（含时装镜像通道 FashionCoat/Hat/Shoes）
     * + 武器 WeaponMode。登录期 addObject 后补帧、GM 穿戴后实时推送共用。
     */
    public static byte[] appearanceFrame(int objectId, int ownerId, int sex,
                                         List<RoleInventoryStore.Equip> equips,
                                         EquipmentResolver resolver) {
        Map<Integer, String> models = wornModels(sex, equips, resolver);
        List<PropertyValues.Indexed> properties = new ArrayList<>(6);
        addString(properties, "Cloth", models.get(SLOT_CLOTH));
        addString(properties, "Pants", models.get(SLOT_PANTS));
        addString(properties, "Shoes", models.get(SLOT_SHOES));
        addString(properties, "Hat", models.get(SLOT_HAT));
        addString(properties, "FashionCoat", models.get(SLOT_FASHION_COAT));
        addString(properties, "FashionHat", models.get(SLOT_FASHION_HAT));
        addString(properties, "FashionShoes", models.get(SLOT_FASHION_SHOES));
        String weaponMode = InventoryMessages.weaponModeOf(sex, equips, resolver);
        if (!weaponMode.isEmpty()) {
            addString(properties, "WeaponMode", weaponMode);
        }
        return SceneMessages.objectProperties(objectId, ownerId, 0, properties);
    }

    private static void addString(List<PropertyValues.Indexed> properties, String name, String value) {
        if (value == null || value.isEmpty()) {
            return; // 无装备部位保持客户端当前值（登录归档已带初始装）
        }
        Props.add(properties, name, PropertyValues.Value.ofString(value));
    }
}
