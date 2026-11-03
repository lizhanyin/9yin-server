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
 * 装备外观挂载（V2「角色武器装备外观实现挂载」+ V4 三模式裁决）：已穿戴装备推导
 * 人物外观部位（Cloth/Pants/Shoes/Hat/WeaponMode），登录归档与穿戴后实时 0x10 推送。
 *
 * <p>三套外观（客户端属性 {@code ShowEquipType}：0=江湖装 1=身份装 2=时装，custom 61
 * 切换）：客户端引擎按服务端算好的最终模型属性渲染，本类
 * {@link #finalModels} 按显示类型裁决——身份装=槽 16 整套（ItemType 180，主
 * ArtPack=衣、Hat/Plants/ShoesArtPack 分件）；时装=槽 17-19（套装式 FashionCoat
 * 自带帽/鞋 ArtPack）；缺件回落已穿装备模型。
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

    /** 显示类型（玩家属性 ShowEquipType / custom 61）：0=江湖装 1=身份装 2=时装。 */
    public static final int SHOW_EQUIP_GEAR = 0;
    public static final int SHOW_EQUIP_IDENTITY = 1;
    public static final int SHOW_EQUIP_FASHION = 2;

    /** 部位名（0x10 属性与 {@link #finalModels} 键）。 */
    public static final String PART_CLOTH = "Cloth";
    public static final String PART_PANTS = "Pants";
    public static final String PART_SHOES = "Shoes";
    public static final String PART_HAT = "Hat";

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
                    && slot != SLOT_SUIT
                    && slot != SLOT_FASHION_COAT && slot != SLOT_FASHION_HAT && slot != SLOT_FASHION_SHOES) {
                continue; // 只有衣裤鞋帽/身份装/时装改变模型；武器走 WeaponMode 通道
            }
            String model = resolver.resolve(equip.configId(), sex);
            if (model != null && !model.isEmpty()) {
                models.put(slot, model);
            }
        }
        return models;
    }

    /** 指定槽位当前穿戴的 configId（后穿覆盖先穿；无返回 null）。 */
    public static String configAt(List<RoleInventoryStore.Equip> equips, int slot) {
        String config = null;
        for (RoleInventoryStore.Equip equip : equips) {
            if (equip.slot() == slot) {
                config = equip.configId();
            }
        }
        return config;
    }

    /**
     * 三模式最终模型裁决（Cloth/Pants/Shoes/Hat → 模型路径，缺件回落已穿装备）：
     * 江湖装=装备槽 1/3/4/8；身份装=槽 16 整套（主 ArtPack=衣、分件 ArtPack
     * 有则覆盖，无分件保留装备）；时装=槽 17-19（套装式 FashionCoat 的帽/鞋
     * ArtPack 先行，单件帽/鞋槽再覆盖）。
     */
    public static Map<String, String> finalModels(int showEquipType, int sex,
                                                  List<RoleInventoryStore.Equip> equips,
                                                  EquipmentResolver resolver) {
        Map<Integer, String> worn = wornModels(sex, equips, resolver);
        Map<String, String> finals = new HashMap<>();
        put(finals, PART_CLOTH, worn.get(SLOT_CLOTH));
        put(finals, PART_PANTS, worn.get(SLOT_PANTS));
        put(finals, PART_SHOES, worn.get(SLOT_SHOES));
        put(finals, PART_HAT, worn.get(SLOT_HAT));
        if (showEquipType == SHOW_EQUIP_IDENTITY) {
            String suit = configAt(equips, SLOT_SUIT);
            if (suit != null) {
                put(finals, PART_CLOTH, resolver.resolve(suit, sex));
                put(finals, PART_HAT, resolver.resolveHat(suit, sex));
                put(finals, PART_PANTS, resolver.resolvePants(suit, sex));
                put(finals, PART_SHOES, resolver.resolveShoes(suit, sex));
            }
        } else if (showEquipType == SHOW_EQUIP_FASHION) {
            String coat = configAt(equips, SLOT_FASHION_COAT);
            if (coat != null) {
                put(finals, PART_CLOTH, resolver.resolve(coat, sex));
                // 套装式时装自带帽/鞋包（如 FASHION_SUIT_G027_gui：HatArtPack/ShoesArtPack）。
                put(finals, PART_HAT, resolver.resolveHat(coat, sex));
                put(finals, PART_SHOES, resolver.resolveShoes(coat, sex));
            }
            put(finals, PART_HAT, resolveAt(equips, SLOT_FASHION_HAT, sex, resolver));
            put(finals, PART_SHOES, resolveAt(equips, SLOT_FASHION_SHOES, sex, resolver));
            // 时装无裤件（套装衣模型自带下身），Pants 保持装备/当前值。
        }
        return finals;
    }

    private static String resolveAt(List<RoleInventoryStore.Equip> equips, int slot, int sex,
                                    EquipmentResolver resolver) {
        String config = configAt(equips, slot);
        return config == null ? "" : resolver.resolve(config, sex);
    }

    private static void put(Map<String, String> finals, String part, String model) {
        if (model != null && !model.isEmpty()) {
            finals.put(part, model);
        }
    }

    /**
     * 外观属性帧（0x10）：显示开关 ShowEquipType + 三模式裁决后的衣/裤/鞋/帽
     * 主通道 + 时装镜像通道（FashionCoat/Hat/Shoes 原始模型）+ 武器 WeaponMode。
     * 登录期 addObject 后补帧、穿戴/切换后实时推送共用。空值部位跳过（客户端
     * 保持当前值）。
     */
    public static byte[] appearanceFrame(int objectId, int ownerId, int showEquipType, int sex,
                                         List<RoleInventoryStore.Equip> equips,
                                         EquipmentResolver resolver) {
        Map<Integer, String> models = wornModels(sex, equips, resolver);
        Map<String, String> finals = finalModels(showEquipType, sex, equips, resolver);
        List<PropertyValues.Indexed> properties = new ArrayList<>(9);
        Props.add(properties, "ShowEquipType", PropertyValues.Value.ofByte(showEquipType));
        addString(properties, PART_CLOTH, finals.get(PART_CLOTH));
        addString(properties, PART_PANTS, finals.get(PART_PANTS));
        addString(properties, PART_SHOES, finals.get(PART_SHOES));
        addString(properties, PART_HAT, finals.get(PART_HAT));
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
