package com.nineyin.server.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.world.db.RoleInventoryStore;

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
        return md5Hex16(roleId + ":" + kind + ":" + slot);
    }

    /**
     * 实例稳定 UID（2026-11-06 定稿）：入包/入装时生成全局随机 16 hex（SecureRandom
     * 8 字节），与角色/槽位无关，穿脱跨表/同表换槽全程携带——对齐官服实例身份语义
     * （61114 抓包镜像双视图同 UID；槽位寻址 UID 在物品移位后会命中同槽位的其他
     * 物品，内功快捷栏图标漂移根因）。
     */
    public static String randomUid() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(16);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * 旧行（uid 列 NULL，0013 前写入/直插）读取时的表内稳定派生：MD5(role:kind:seq)
     * 前 8 字节 hex。seq 是表内主键永不变化（同表换槽 UPDATE 不动 PK），派生值随行
     * 稳定；跨表移动会显式携带/生成 uid，不走此路径。
     */
    public static String derivedUid(long roleId, String kind, long seq) {
        return md5Hex16(roleId + ":" + kind + ":" + seq);
    }

    /** 物品行下发/反解 UID 的统一口径：列值 > 旧行按 seq 派生 > 兜底槽位方言。 */
    public static String itemUid(long roleId, String kind, String uid, long seq, int slot) {
        if (uid != null && !uid.isEmpty()) {
            return uid;
        }
        if (seq > 0) {
            return derivedUid(roleId, kind, seq);
        }
        return uniqueId(roleId, kind, slot);
    }

    private static String md5Hex16(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 unavailable", e);
        }
    }

    /** 物品行按 viewId 路由（game_items.view_id 分类，bak 同：1道具 2装备 3材料 4任务）：
     * 1→2（道具页）2→121（装备页）3→123 4→125；未知归 2。 */
    public static int bagViewOf(int viewId) {
        return switch (viewId) {
            case 2 -> VIEW_EQUIP_BAG;
            case 3 -> VIEW_MATERIAL_PAGE;
            case 4 -> VIEW_TASK_PAGE;
            default -> VIEW_TOOL_PAGE;
        };
    }

    /** 背包页视图 → 时装镜像页视图（无镜像页返回原视图，成对清位防误删用）。 */
    public static int bagMirrorOf(int view) {
        Integer mirror = FASHION_MIRROR.get(view);
        return mirror != null ? mirror : view;
    }

    /**
     * 背包页视图 → game_items view_id 分类（{@link #bagViewOf} 互逆，扩展页归
     * 所属主页分类：3→1、122→2、124→3、126→4）；非背包页（View1 纸娃娃/镜像页/
     * 未知）返回 0。msg30 移动物品按源页分类限定 DB 行。
     */
    public static int bagCategoryOf(int view) {
        return switch (view) {
            case VIEW_TOOL_PAGE, 3 -> 1;
            case VIEW_EQUIP_BAG, 122 -> 2;
            case VIEW_MATERIAL_PAGE, 124 -> 3;
            case VIEW_TASK_PAGE, 126 -> 4;
            default -> 0;
        };
    }

    /** 扩展页视图（3/122/124/126）→ 页号 1-4；非扩展页返回 0。 */
    public static int pageOfExtView(int view) {
        for (Map.Entry<Integer, Integer> entry : PAGE_EXT_VIEW.entrySet()) {
            if (entry.getValue() == view) {
                return entry.getKey();
            }
        }
        return 0;
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

    /** 登录背包/纸娃娃整推（bak inventoryFrames 序）：纸娃娃行 → 背包成对行 → 武器外观。 */
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
        WeaponVisual weapon = weaponVisualOf(sex, equips, resolver);
        if (weapon.armed()) {
            List<PropertyValues.Indexed> properties = new ArrayList<>(3);
            Props.add(properties, "Weapon", PropertyValues.Value.ofString(weapon.weapon()));
            Props.add(properties, "ShotWeapon", PropertyValues.Value.ofString(weapon.shotWeapon()));
            Props.add(properties, "ActionSet", PropertyValues.Value.ofString(weapon.actionSet()));
            frames.add(SceneMessages.objectProperties(SceneMessages.PLAYER_OBJECT_ID,
                    SceneMessages.PLAYER_OWNER_ID, 0, properties));
        }
        return frames;
    }

    /**
     * 武器外观三属性（玩家属性 Weapon/ShotWeapon/ActionSet）。官服真机 0x10 帧实证
     * （capture-fxgame-op：Weapon="gun_017"/ActionSet="7h"/收武器推空串）：引擎按
     * Weapon 裸模型名挂 obj\weapon\<名>\<名>.xmod，ActionSet 驱动持械动画组。
     *
     * <ul>
     *   <li>weapon：主手（Weapon/InnerWeapon 首件）模型名；空手 = ""。</li>
     *   <li>shotWeapon：副手（ShotWeapon 首件，弓/箭匣类）模型名；无 = ""。</li>
     *   <li>actionSet：itemartstatic [ArtPack].ActionSet（如 2h）优先，表缺配置回退
     *       ItemType-100+"h"（数据面实证 101→1h..108→8h；主手优先于副手）。</li>
     * </ul>
     */
    public record WeaponVisual(String weapon, String shotWeapon, String actionSet) {
        public boolean armed() {
            return !weapon.isEmpty() || !shotWeapon.isEmpty();
        }

        public static WeaponVisual EMPTY = new WeaponVisual("", "", "");
    }

    /** 已穿武器 → Weapon/ShotWeapon 模型名 + ActionSet（空手全空串）。 */
    public static WeaponVisual weaponVisualOf(int sex, List<RoleInventoryStore.Equip> equips,
                                              EquipmentResolver resolver) {
        String weaponConfig = null;
        int weaponItemType = 0;
        String shotConfig = null;
        int shotItemType = 0;
        for (RoleInventoryStore.Equip equip : equips) {
            String type = equip.equipType() == null ? "" : equip.equipType();
            if (type.equals("Weapon") || type.equals("InnerWeapon")) {
                if (weaponConfig == null) {
                    weaponConfig = equip.configId();
                    weaponItemType = equip.itemType();
                }
            } else if (type.equals("ShotWeapon")) {
                if (shotConfig == null) {
                    shotConfig = equip.configId();
                    shotItemType = equip.itemType();
                }
            }
        }
        String weapon = weaponConfig == null ? "" : orEmpty(resolver.resolve(weaponConfig, sex));
        String shot = shotConfig == null ? "" : orEmpty(resolver.resolve(shotConfig, sex));
        String actionSet = actionSetFor(weaponConfig, weaponItemType, resolver);
        if (actionSet.isEmpty()) {
            actionSet = actionSetFor(shotConfig, shotItemType, resolver);
        }
        return new WeaponVisual(weapon, shot, actionSet);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** ActionSet：表值优先；表缺（Weapon 101-108/ShotWeapon 110-117 无表值）回退 ItemType-100+"h"。 */
    private static String actionSetFor(String config, int itemType, EquipmentResolver resolver) {
        if (config == null) {
            return "";
        }
        String fromTable = resolver.resolveActionSet(config);
        if (!fromTable.isEmpty()) {
            return fromTable;
        }
        return itemType >= 101 && itemType <= 108 ? (itemType - 100) + "h" : "";
    }

    /** View1 纸娃娃行（index=身体槽位）：ConfigID/数量/类型/EquipType(真值)/耐久/精炼/UniqueID/Photo。 */
    public static byte[] equipmentViewAdd(long roleId, RoleInventoryStore.Equip equip,
                                          EquipmentResolver resolver) {
        return equipmentViewAdd(VIEW_DOLL, roleId, equip, resolver);
    }

    /** 指定视图的装备行（VIEW_DOLL=1 纸娃娃；时装/身份装镜像新面板 40-43）。 */
    public static byte[] equipmentViewAdd(int viewId, long roleId, RoleInventoryStore.Equip equip,
                                          EquipmentResolver resolver) {
        EquipmentModels.ItemStatic stat = EquipmentModels.itemStatic(equip.configId());
        List<SceneMessages.ViewProperty> props = new ArrayList<>(16);
        addCommon(props, equip.configId(), equip.itemType(), roleId, "equip", equip.slot(),
                equip.uid(), equip.seq());
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
        // 精炼词缀（PropBuilt）与特效包（EffectPackID）互斥；词缀非空下发（四期装备词缀层）。
        if (equip.propBuilt() != null && !equip.propBuilt().isEmpty()) {
            Props.addView(props, "PropBuilt", PropertyValues.Value.ofString(equip.propBuilt()));
        } else if (stat != null && stat.effectPackId() != 0) {
            Props.addView(props, "EffectPackID", PropertyValues.Value.ofInt32(stat.effectPackId()));
        }
        if (stat != null && stat.photo() != null && !stat.photo().isEmpty()) {
            Props.addView(props, "Photo", PropertyValues.Value.ofString(stat.photo()));
        }
        return SceneMessages.viewAdd(viewId, equip.slot(), props);
    }

    /** 背包行：非装备 6 项；装备类补至 14-16 项（EquipType 必须真值否则不渲染）。 */
    public static byte[] bagViewAdd(long roleId, RoleInventoryStore.BagItem item,
                                    EquipmentResolver resolver) {
        int view = bagViewOf(item.viewId());
        EquipmentModels.ItemStatic stat = EquipmentModels.itemStatic(item.configId());
        List<SceneMessages.ViewProperty> props = new ArrayList<>(16);
        addCommon(props, item.configId(), item.itemType(), roleId, "bag", item.slot(),
                item.uid(), item.seq());
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

    /** 通用 6 项：ConfigID/Amount/MaxAmount=1/ItemType/BindStatus=0/UniqueID（实例稳定口径）。 */
    private static void addCommon(List<SceneMessages.ViewProperty> props, String configId,
                                  int itemType, long roleId, String kind, int slot,
                                  String uid, long seq) {
        Props.addView(props, "ConfigID", PropertyValues.Value.ofString(configId));
        Props.addView(props, "Amount", PropertyValues.Value.ofInt32(1));
        Props.addView(props, "MaxAmount", PropertyValues.Value.ofInt32(1));
        Props.addView(props, "ItemType", PropertyValues.Value.ofInt32(itemType));
        Props.addView(props, "BindStatus", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "UniqueID",
                PropertyValues.Value.ofString(itemUid(roleId, kind, uid, seq, slot)));
    }
}
