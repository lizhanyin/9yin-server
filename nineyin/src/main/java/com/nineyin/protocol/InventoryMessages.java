package com.nineyin.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.nineyin.game.avatar.equipment.EquipmentResolver;
import com.nineyin.world.db.RoleInventoryStore;

/**
 * 三期物品域帧（移植 bak InventoryPackets，官方方言按名写属性）：
 * 17 视图容器、扩容锚点行、背包行 0x19/0x18 成对（真实槽位，禁用计数器）、
 * View1 纸娃娃、WeaponMode 0x10。
 */
public final class InventoryMessages {

    public static final int VIEW_DOLL = 1;
    public static final int VIEW_TOOL_PAGE = 2;
    public static final int VIEW_EQUIP_BAG = 121;
    public static final int VIEW_MATERIAL_PAGE = 123;
    public static final int VIEW_TASK_PAGE = 125;
    public static final int BAG_SLOT_MAX = 90;
    public static final int DOLL_SLOT_MAX = 48;
    /** 扩容页号→扩展视图（bak 同：页1=道具/页2=装备/页3=材料/页4=任务）。 */
    static final Map<Integer, Integer> PAGE_EXT_VIEW = Map.of(1, 3, 2, 122, 3, 124, 4, 126);
    /** 时装镜像页。 */
    static final Map<Integer, Integer> FASHION_MIRROR = Map.of(
            2, 174, 3, 175, 121, 176, 122, 177, 123, 178, 124, 179, 125, 180, 126, 181);
    /** 主页顺序（bak bagViewCreateFrames：BaseCap=18 的 8 页）。 */
    static final List<Integer> MAIN_PAGES = List.of(121, 2, 123, 125, 176, 174, 178, 180);
    static final List<Integer> EXT_PAGES = List.of(122, 3, 124, 126, 177, 175, 179, 181);

    private InventoryMessages() {
    }

    /** bak uniqueId：MD5(roleId+":bag:/equip:"+slot) 前 8 字节 hex（16 字符，登录期稳定）。 */
    public static String uniqueId(long roleId, String kind, int slot) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((roleId + ":" + kind + ":" + slot).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 unavailable", e);
        }
    }

    /** 物品行按 viewId 路由：1→121（装备页）2→2 3→123 4→125；未知归 2。 */
    public static int bagViewOf(int viewId) {
        return switch (viewId) {
            case 1 -> VIEW_EQUIP_BAG;
            case 2 -> VIEW_TOOL_PAGE;
            case 3 -> VIEW_MATERIAL_PAGE;
            case 4 -> VIEW_TASK_PAGE;
            default -> VIEW_TOOL_PAGE;
        };
    }

    public static List<byte[]> bagViewCreateFrames() {
        List<byte[]> frames = new ArrayList<>(17);
        // 8 主页：cap=90 + BaseCap=18（真实客户端实测布局）。
        for (int viewId : MAIN_PAGES) {
            List<SceneMessages.ViewProperty> props = new ArrayList<>(1);
            Props.addView(props, "BaseCap", PropertyValues.Value.ofInt32(18));
            frames.add(SceneMessages.createViewWithProperties(
                    new SceneMessages.ViewSpec(viewId, BAG_SLOT_MAX), props));
        }
        for (int viewId : EXT_PAGES) {
            frames.add(SceneMessages.createView(new SceneMessages.ViewSpec(viewId, 2)));
        }
        frames.add(SceneMessages.createView(new SceneMessages.ViewSpec(VIEW_DOLL, DOLL_SLOT_MAX)));
        return frames;
    }

    /** 扩容锚点行：扩展页 0x18，index=锚点位；BeginPos=19+Σ前序 boost、EndPos=18+累计。 */
    public static List<byte[]> itemBagFrames(long roleId, List<RoleInventoryStore.Bagpack> bagpacks,
                                             java.time.Instant now) {
        List<byte[]> frames = new ArrayList<>(bagpacks.size());
        Map<Integer, Integer> cumulative = new java.util.HashMap<>();
        for (RoleInventoryStore.Bagpack pack : bagpacks) {
            if (pack.expiresAt() != null && pack.expiresAt().isBefore(now)) {
                continue; // 过期锚点不下发
            }
            Integer extView = PAGE_EXT_VIEW.get(pack.page());
            if (extView == null) {
                continue; // 未知页号不下发
            }
            int before = cumulative.getOrDefault(pack.page(), 0);
            int begin = 19 + before;
            int end = 18 + before + pack.boost();
            cumulative.put(pack.page(), before + pack.boost());
            List<SceneMessages.ViewProperty> props = new ArrayList<>(4);
            Props.addView(props, "ConfigID", PropertyValues.Value.ofString(pack.configId()));
            Props.addView(props, "BeginPos", PropertyValues.Value.ofInt32(begin));
            Props.addView(props, "EndPos", PropertyValues.Value.ofInt32(end));
            Props.addView(props, "Hardiness", PropertyValues.Value.ofInt32(
                    anchorHardiness(pack.expiresAt(), now)));
            frames.add(SceneMessages.viewAdd(extView, pack.anchor(), props));
        }
        return frames;
    }

    /** 永久锚点（DB 存 9999-12-31）=720 小时；否则剩余小时。 */
    static int anchorHardiness(java.time.Instant expiresAt, java.time.Instant now) {
        if (expiresAt == null || expiresAt.isAfter(java.time.Instant.parse("2999-12-31T00:00:00Z"))) {
            return 720;
        }
        long hours = java.time.Duration.between(now, expiresAt).toHours();
        return (int) Math.max(hours, 0);
    }

    /** 登录背包/纸娃娃整推（bak inventoryFrames 序）：纸娃娃行 → 背包成对行 → WeaponMode。 */
    public static List<byte[]> inventoryFrames(long roleId, int sex,
                                               List<RoleInventoryStore.Equip> equips,
                                               List<RoleInventoryStore.BagItem> items,
                                               EquipmentResolver resolver) {
        List<byte[]> frames = new ArrayList<>(equips.size() * 2 + items.size() * 3 + 1);
        for (RoleInventoryStore.Equip equip : equips) {
            frames.add(equipmentViewAdd(roleId, equip, resolver));
        }
        for (RoleInventoryStore.BagItem item : items) {
            if (item.slot() < 1 || item.slot() > BAG_SLOT_MAX) {
                continue; // 槽位 91+ 溢出：DB 保留、显示裁剪
            }
            int view = bagViewOf(item.viewId());
            int mirror = FASHION_MIRROR.get(view);
            // 成对 remove/add：同槽位预清 + 镜像页清位（计数器误删 add 行的防回归形态）。
            frames.add(SceneMessages.viewRemove(view, item.slot()));
            frames.add(bagViewAdd(roleId, item, resolver));
            frames.add(SceneMessages.viewRemove(mirror, item.slot()));
        }
        String weaponMode = weaponModeOf(sex, equips, resolver);
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "WeaponMode", PropertyValues.Value.ofString(weaponMode));
        frames.add(SceneMessages.objectProperties(SceneMessages.PLAYER_OBJECT_ID,
                SceneMessages.PLAYER_OWNER_ID, 0, properties));
        return frames;
    }

    /** 已装备武器（Weapon/ShotWeapon/InnerWeapon 优先级取首个）的模型路径；空手=""。 */
    static String weaponModeOf(int sex, List<RoleInventoryStore.Equip> equips,
                               EquipmentResolver resolver) {
        for (RoleInventoryStore.Equip equip : equips) {
            String type = equip.equipType() == null ? "" : equip.equipType();
            if (type.equals("Weapon") || type.equals("ShotWeapon") || type.equals("InnerWeapon")) {
                String config = equip.artPack() > 0
                        ? resolver.resolve(equip.configId(), sex)
                        : resolver.resolve(equip.configId(), sex);
                return config == null ? "" : config;
            }
        }
        return "";
    }

    /** View1 纸娃娃行（index=身体槽位）：ConfigID/数量/类型/EquipType(真值)/耐久/精炼/UniqueID/Photo。 */
    public static byte[] equipmentViewAdd(long roleId, RoleInventoryStore.Equip equip,
                                          EquipmentResolver resolver) {
        EquipmentModels.ItemStatic stat = EquipmentModels.itemStatic(equip.configId());
        List<SceneMessages.ViewProperty> props = new ArrayList<>(16);
        addCommon(props, equip.configId(), equip.itemType(), roleId, "equip", equip.slot());
        Props.addView(props, "EquipType", PropertyValues.Value.ofString(equip.equipType()));
        int maxBuild = stat != null ? stat.maxBuildCount() : 2;
        Props.addView(props, "MaxBuildCount", PropertyValues.Value.ofInt32(maxBuild));
        Props.addView(props, "CurBuildCount", PropertyValues.Value.ofInt32(maxBuild));
        Props.addView(props, "RecoverFlag", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "ColorLevel", PropertyValues.Value.ofInt32(
                stat != null ? stat.colorLevel() : 1));
        Props.addView(props, "Hardiness", PropertyValues.Value.ofInt32(equip.hardiness()));
        Props.addView(props, "MaxHardiness", PropertyValues.Value.ofInt32(equip.maxHardiness()));
        Props.addView(props, "Level", PropertyValues.Value.ofInt32(stat != null ? stat.level() : 0));
        // 精炼词缀（PropBuilt）与特效包（EffectPackID）互斥。
        if (stat != null && stat.effectPackId() != 0) {
            Props.addView(props, "EffectPackID", PropertyValues.Value.ofInt32(stat.effectPackId()));
        }
        if (stat != null && stat.photo() != null && !stat.photo().isEmpty()) {
            Props.addView(props, "Photo", PropertyValues.Value.ofString(stat.photo()));
        }
        return SceneMessages.viewAdd(VIEW_DOLL, equip.slot(), props);
    }

    /** 背包行：非装备 6 项；装备类补至 14-16 项（EquipType 必须真值否则不渲染）。 */
    public static byte[] bagViewAdd(long roleId, RoleInventoryStore.BagItem item,
                                    EquipmentResolver resolver) {
        int view = bagViewOf(item.viewId());
        EquipmentModels.ItemStatic stat = EquipmentModels.itemStatic(item.configId());
        List<SceneMessages.ViewProperty> props = new ArrayList<>(16);
        addCommon(props, item.configId(), item.itemType(), roleId, "bag", item.slot());
        if (stat != null || item.equipType() != null && !item.equipType().isEmpty()) {
            String equipType = stat != null && !stat.equipType().isEmpty()
                    ? stat.equipType()
                    : (item.equipType() == null ? "" : item.equipType());
            Props.addView(props, "EquipType", PropertyValues.Value.ofString(equipType));
            int maxBuild = stat != null ? stat.maxBuildCount() : 2;
            Props.addView(props, "MaxBuildCount", PropertyValues.Value.ofInt32(maxBuild));
            Props.addView(props, "CurBuildCount", PropertyValues.Value.ofInt32(maxBuild));
            Props.addView(props, "RecoverFlag", PropertyValues.Value.ofInt32(0));
            Props.addView(props, "ColorLevel", PropertyValues.Value.ofInt32(
                    stat != null ? stat.colorLevel() : 1));
            Props.addView(props, "Hardiness",
                    PropertyValues.Value.ofInt32(item.hardiness() > 0 ? item.hardiness() : 100));
            Props.addView(props, "MaxHardiness",
                    PropertyValues.Value.ofInt32(item.maxHardiness() > 0 ? item.maxHardiness() : 100));
            Props.addView(props, "Level", PropertyValues.Value.ofInt32(stat != null ? stat.level() : 0));
            if (stat != null && stat.effectPackId() != 0) {
                Props.addView(props, "EffectPackID", PropertyValues.Value.ofInt32(stat.effectPackId()));
            }
            if (stat != null && stat.photo() != null && !stat.photo().isEmpty()) {
                Props.addView(props, "Photo", PropertyValues.Value.ofString(stat.photo()));
            }
        }
        return SceneMessages.viewAdd(view, item.slot(), props);
    }

    /** 通用 6 项：ConfigID/Amount/MaxAmount=1/ItemType/BindStatus=0/UniqueID。 */
    private static void addCommon(List<SceneMessages.ViewProperty> props, String configId,
                                  int itemType, long roleId, String kind, int slot) {
        Props.addView(props, "ConfigID", PropertyValues.Value.ofString(configId));
        Props.addView(props, "Amount", PropertyValues.Value.ofInt32(1));
        Props.addView(props, "MaxAmount", PropertyValues.Value.ofInt32(1));
        Props.addView(props, "ItemType", PropertyValues.Value.ofInt32(itemType));
        Props.addView(props, "BindStatus", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "UniqueID",
                PropertyValues.Value.ofString(uniqueId(roleId, kind, slot)));
    }
}
