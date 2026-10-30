package com.nineyin.protocol;

import com.nineyin.db.RoleInventoryStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 库存下发帧（2026-10-30 对齐 F 盘新版参考 exe 抓包 ref_capture.bin 重写）：
 * - 17 个视图：8 主页（cap 90 + BaseCap=18）+ 8 扩展页（cap 2，前两页含 ITEMBAG_006 扩展包对象）
 *   + View 1 装备纸娃娃（cap 48，对象索引 = 身体槽位）
 * - 装备对象 16 属性：ConfigID/Amount/MaxAmount/ItemType/EquipType/BindStatus/
 *   MaxBuildCount/CurBuildCount/RecoverFlag/ColorLevel/Hardiness/MaxHardiness/
 *   Level/PropBuilt(词缀)/EffectPackID/UniqueID/Photo(icon 路径)
 * - 背包对象：装备类 16 属性落 View 121，其余 6 属性按分类落 View 2/123/125
 * - 每件物品前后发 0x19 视图版本帧（页视图与镜像视图成对，seq 递增）
 * 属性索引为本服 0x09 官方属性表（2424 条），与参考 exe 的 726 条表同名不同位，
 * 由双方各自的属性表协商对齐。
 */
public final class InventoryPackets {

    // ---- 视图 ID（share/view_define.lua + 参考抓包） ----
    public static final int VIEW_EQUIP_DOLL = 1;     // 装备纸娃娃
    public static final int VIEW_TOOL_PAGE = 2;      // 道具页
    public static final int VIEW_MATERIAL_PAGE = 123;
    public static final int VIEW_TASK_PAGE = 125;
    public static final int VIEW_EQUIP_BAG = 121;    // 装备背包页（未穿戴装备）
    /** 背包主页对象槽位上限（createView cap 90：1-18 基础格 + 19-90 ITEMBAG_006 扩展格）。 */
    public static final int BAG_SLOT_MAX = 90;

    /** 主页 → 时装镜像页（0x19 版本帧成对下发）。 */
    private static final Map<Integer, Integer> MIRROR = Map.of(
            2, 174, 3, 175, 121, 176, 122, 177, 123, 178, 124, 179, 125, 180, 126, 181);

    // ---- 官方属性表索引（official_property_table.txt） ----
    private static final int P_PHOTO = 184;          // string
    private static final int P_CUR_FILL = 763;
    private static final int P_TOTAL_FILL = 764;
    private static final int P_CONFIG_ID = 1439;     // string
    private static final int P_LEVEL = 1464;         // u8
    private static final int P_ITEM_TYPE = 1887;
    private static final int P_COLOR_LEVEL = 1890;
    private static final int P_UNIQUE_ID = 1891;     // string
    private static final int P_AMOUNT = 1892;
    private static final int P_MAX_AMOUNT = 1893;
    private static final int P_BIND_STATUS = 1896;
    private static final int P_HARDINESS = 1933;
    private static final int P_MAX_HARDINESS = 1934;
    private static final int P_EQUIP_TYPE = 1936;    // string
    private static final int P_MAX_BUILD = 1948;
    private static final int P_CUR_BUILD = 1949;
    private static final int P_PROP_BUILT = 1952;    // string
    private static final int P_EFFECT_PACK = 1957;
    private static final int P_RECOVER_FLAG = 1969;  // u8
    private static final int P_MAX_LEVEL = 2081;
    private static final int P_BEGIN_POS = 2011;     // i16
    private static final int P_END_POS = 2012;       // i16
    private static final int P_BASE_CAP = 2213;
    private static final int P_STATIC_DATA = 2235;
    private static final int P_WU_XING = 2241;
    private static final int P_PREPARE_TIME = 2266;
    private static final int P_TARGET_SHAPE2 = 2258; // f32

    private InventoryPackets() {
    }

    /** game_items.view_id 分类 → 背包页视图（1道具 2装备 3材料 4任务）。 */
    public static int bagViewOf(int category) {
        return switch (category) {
            case 2 -> VIEW_EQUIP_BAG;
            case 3 -> VIEW_MATERIAL_PAGE;
            case 4 -> VIEW_TASK_PAGE;
            default -> VIEW_TOOL_PAGE;
        };
    }

    // ---- 视图创建（选角链，0x0B 之前） ----

    /** 17 个视图创建帧：8 主页(BaseCap=18) + 8 扩展页 + 装备纸娃娃。 */
    public static List<byte[]> bagViewCreateFrames() {
        int[][] pages = {{121, 122}, {2, 3}, {123, 124}, {125, 126},
                {176, 177}, {174, 175}, {178, 179}, {180, 181}};
        List<byte[]> frames = new ArrayList<>(17);
        for (int[] page : pages) {
            frames.add(SkillPackets.createViewHeader(page[0], 90, 1)
                    .u16(P_BASE_CAP).i32(18)
                    .toByteArray());
            frames.add(SkillPackets.createView(page[1], 2));
        }
        frames.add(SkillPackets.createView(VIEW_EQUIP_DOLL, 48));
        return frames;
    }

    /** 扩展包对象：道具/装备扩展页各一个 ITEMBAG_006（19-54 / 55-90 格）。 */
    public static List<byte[]> itemBagFrames() {
        List<byte[]> frames = new ArrayList<>(4);
        for (int view : new int[]{3, 122}) {
            frames.add(itemBagAdd(view, 1, 19, 54));
            frames.add(itemBagAdd(view, 2, 55, 90));
        }
        return frames;
    }

    private static byte[] itemBagAdd(int view, int obj, int begin, int end) {
        return SkillPackets.viewAddHeader(view, obj, 4)
                .u16(P_CONFIG_ID).string("ITEMBAG_006")
                .u16(P_BEGIN_POS).u16(begin)
                .u16(P_END_POS).u16(end)
                .u16(P_HARDINESS).i32(720)
                .toByteArray();
    }

    // ---- 0x19 ServerViewRemove ----

    /** 0x19：按对象索引移除视图对象（spec/opcodes.json ServerViewRemove）。 */
    public static byte[] viewRemove(int view, int objIndex) {
        return new PacketWriter()
                .u8(0x19)
                .u16(view)
                .u16(objIndex)
                .toByteArray();
    }

    /** 主页 → 时装镜像页（移动物品时成对清除）。 */
    public static int mirrorOf(int view) {
        return MIRROR.getOrDefault(view, 0);
    }

    // ---- 物品对象 ----

    /**
     * 全套库存下发帧（0x0B 之后调用）：装备纸娃娃 View 1 + 各背包页物品。
     * 背包行按真实槽位成对 remove+add（2026-10-30 wire 重放实锤：remove 用顺序
     * 计数器会删掉前一件刚 add 的行，105 行装备登录后只剩 12 行）。
     * 同时缓存于 LoginSession 供 0x0C 重发；末尾附武器模型属性帧
     * （WeaponMode=1597），重进场景时角色手持武器一并恢复。
     */
    public static List<byte[]> inventoryFrames(long roleId,
                                               List<RoleInventoryStore.Equipped> equipped,
                                               List<RoleInventoryStore.Bagged> bagged,
                                               byte sex) {
        List<byte[]> frames = new ArrayList<>(32 + equipped.size() * 3 + bagged.size() * 3);
        for (RoleInventoryStore.Equipped item : equipped) {
            frames.add(equipmentViewAdd(item, uniqueId(roleId, "equip", item.slot())));
        }
        for (RoleInventoryStore.Bagged item : bagged) {
            if (item.slot() < 1 || item.slot() > BAG_SLOT_MAX) {
                continue;   // 超 cap 90 的历史行（数据恢复叠加产生的 91-111）不推
            }
            int view = bagViewOf(item.viewId());
            frames.add(viewRemove(view, item.slot()));
            frames.add(bagViewAdd(item, uniqueId(roleId, "bag", item.slot()), view));
            int mirror = mirrorOf(view);
            if (mirror != 0) {
                frames.add(viewRemove(mirror, item.slot()));
            }
        }
        frames.add(weaponModeFrame(equipped, sex));
        return frames;
    }


    /**
     * 武器模型属性帧（0x10 WeaponMode=1597，对齐参考服 pushGearVisual）：
     * 装备栏含武器（EquipType=Weapon/ShotWeapon/InnerWeapon）时取第一个的模型路径，
     * 无武器为空串。模型解析走 EquipmentModels（equipment.ini→itemartstatic.ini），
     * 链路缺失回退 config 原值（与建角外观同一回退策略）。
     */
    public static byte[] weaponModeFrame(List<RoleInventoryStore.Equipped> equipped, byte sex) {
        String model = "";
        for (RoleInventoryStore.Equipped item : equipped) {
            if (isWeaponEquipType(item.equipType())) {
                model = EquipmentModels.resolve(item.configId(), sex);
                break;
            }
        }
        return S2CPackets.objectPropertyString(PropertyId.WEAPON_MODE, model);
    }

    /** 武器类 EquipType（equipment.ini 词表：Weapon 外功武器 / ShotWeapon 暗器 / InnerWeapon 内功武器）。 */
    static boolean isWeaponEquipType(String equipType) {
        if (equipType == null) {
            return false;
        }
        return switch (equipType.toLowerCase(java.util.Locale.ROOT)) {
            case "weapon", "shotweapon", "innerweapon" -> true;
            default -> false;
        };
    }

    /** 装备对象（View 1，索引 = 身体槽位，对齐参考抓包：14 基础属性 + 词缀/特效二选一 + UID + 图标）。 */
    public static byte[] equipmentViewAdd(RoleInventoryStore.Equipped item, String uid) {
        EquipmentModels.ItemStatic st = EquipmentModels.itemStatic(item.configId());
        int colorLevel = st != null ? st.colorLevel() : 1;
        int level = st != null ? st.level() : 0;
        int maxBuild = st != null ? st.maxBuildCount() : 2;
        int effectPack = st != null ? st.effectPackId() : 0;
        String photo = st != null && st.photo() != null ? st.photo() : "";
        boolean hasPropBuilt = !item.propBuilt().isEmpty();
        boolean hasEffect = !hasPropBuilt && effectPack != 0;
        boolean hasPhoto = !photo.isEmpty();
        int count = 14 + (hasPropBuilt || hasEffect ? 1 : 0) + (hasPhoto ? 1 : 0);
        PacketWriter w = SkillPackets.viewAddHeader(VIEW_EQUIP_DOLL, item.slot(), count);
        w.u16(P_CONFIG_ID).string(item.configId());
        w.u16(P_AMOUNT).i32(1);
        w.u16(P_MAX_AMOUNT).i32(1);
        w.u16(P_ITEM_TYPE).i32(item.itemType());
        w.u16(P_EQUIP_TYPE).string(item.equipType());
        w.u16(P_BIND_STATUS).i32(0);
        w.u16(P_MAX_BUILD).i32(maxBuild);
        w.u16(P_CUR_BUILD).i32(maxBuild);
        w.u16(P_RECOVER_FLAG).u8(0);
        w.u16(P_COLOR_LEVEL).i32(colorLevel);
        w.u16(P_HARDINESS).i32(item.hardiness());
        w.u16(P_MAX_HARDINESS).i32(item.maxHardiness());
        w.u16(P_LEVEL).u8(Math.max(0, Math.min(255, level)));
        if (hasPropBuilt) {
            w.u16(P_PROP_BUILT).string(item.propBuilt());
        } else if (hasEffect) {
            w.u16(P_EFFECT_PACK).i32(effectPack);
        }
        w.u16(P_UNIQUE_ID).string(uid);
        if (hasPhoto) {
            w.u16(P_PHOTO).string(photo);
        }
        return w.toByteArray();
    }

    /** 背包对象：装备类对齐装备 16 属性（落 View 121），其余 6 属性（索引 = 背包格位）。 */
    public static byte[] bagViewAdd(RoleInventoryStore.Bagged item, String uid, int view) {
        EquipmentModels.ItemStatic st = view == VIEW_EQUIP_BAG
                ? EquipmentModels.itemStatic(item.configId()) : null;
        if (st == null) {
            return SkillPackets.viewAddHeader(view, item.slot(), 6)
                    .u16(P_CONFIG_ID).string(item.configId())
                    .u16(P_AMOUNT).i32(item.amount())
                    .u16(P_MAX_AMOUNT).i32(1)
                    .u16(P_ITEM_TYPE).i32(item.itemType())
                    .u16(P_BIND_STATUS).i32(0)
                    .u16(P_UNIQUE_ID).string(uid)
                    .toByteArray();
        }
        boolean hasEffect = st.effectPackId() != 0;
        boolean hasPhoto = st.photo() != null;
        int count = 14 + (hasEffect ? 1 : 0) + (hasPhoto ? 1 : 0);
        PacketWriter w = SkillPackets.viewAddHeader(view, item.slot(), count);
        w.u16(P_CONFIG_ID).string(item.configId());
        w.u16(P_AMOUNT).i32(item.amount());
        w.u16(P_MAX_AMOUNT).i32(1);
        w.u16(P_ITEM_TYPE).i32(item.itemType());
        w.u16(P_BIND_STATUS).i32(0);
        // equipment.ini 的 EquipType（参考帧为 "Weapon"/"ClothesH" 等真实值，空串装备页不渲染）
        w.u16(P_EQUIP_TYPE).string(st.equipType());
        w.u16(P_MAX_BUILD).i32(st.maxBuildCount());
        w.u16(P_CUR_BUILD).i32(st.maxBuildCount());
        w.u16(P_RECOVER_FLAG).u8(0);
        w.u16(P_COLOR_LEVEL).i32(st.colorLevel());
        w.u16(P_HARDINESS).i32(100);
        w.u16(P_MAX_HARDINESS).i32(100);
        w.u16(P_LEVEL).u8(Math.max(0, Math.min(255, st.level())));
        if (hasEffect) {
            w.u16(P_EFFECT_PACK).i32(st.effectPackId());
        }
        w.u16(P_UNIQUE_ID).string(uid);
        if (hasPhoto) {
            w.u16(P_PHOTO).string(st.photo());
        }
        return w.toByteArray();
    }

    // ---- 技能容器（参考抓包的 10 属性布局） ----

    /** View 40 目录容器容量（参考 exe choose 整推实测 0x15 capacity=17905）。 */
    public static final int SKILL_CATALOG_CAPACITY = 17905;
    /** 目录行数护栏（u16 对象索引上限，对齐参考 `exceeds u16 view capacity`）。 */
    public static final int MAX_SKILL_CATALOG_ROWS = 65530;

    /** 角色技能容器（View 40）+ 全部技能，属性集对齐参考抓包。 */
    public static List<byte[]> roleSkillFrames(List<RoleInventoryStore.SkillRow> skills) {
        List<byte[]> frames = new ArrayList<>(skills.size() + 1);
        frames.add(SkillPackets.createView(SkillPackets.VIEW_SKILL, skills.size()));
        for (int i = 0; i < skills.size(); i++) {
            frames.add(roleSkillViewAdd(i + 1, skills.get(i)));
        }
        return frames;
    }

    /**
     * 技能目录行（choose 阶段整推）：与 roleSkillViewAdd 同 10 属性布局，但
     * Level/MaxLevel 固定 12（参考实测目录展示值——skill_init 的 level 列恒 1，
     * 与账号无关，新号抓包同样 12/12）；TotalFill 取文件 total 列（恒 156550）。
     */
    public static byte[] skillCatalogRow(int index, String configId, int staticData,
                                         int itemType, int totalFill) {
        PacketWriter w = SkillPackets.viewAddHeader(SkillPackets.VIEW_SKILL, index, 10);
        w.u16(SkillPackets.V_CONFIG_ID).string(configId);
        w.u16(P_STATIC_DATA).i32(staticData);
        w.u16(SkillPackets.V_ITEM_TYPE).i32(itemType == 0 ? 1000 : itemType);
        w.u16(P_LEVEL).u8(12);
        w.u16(P_MAX_LEVEL).i32(12);
        w.u16(P_CUR_FILL).i32(0);
        w.u16(P_TOTAL_FILL).i32(Math.max(0, totalFill));
        w.u16(P_WU_XING).i32(0);
        w.u16(P_PREPARE_TIME).i32(1000);
        w.u16(P_TARGET_SHAPE2).f32(50.0f);
        return w.toByteArray();
    }

    private static byte[] roleSkillViewAdd(int index, RoleInventoryStore.SkillRow sk) {
        PacketWriter w = SkillPackets.viewAddHeader(SkillPackets.VIEW_SKILL, index, 10);
        w.u16(SkillPackets.V_CONFIG_ID).string(sk.configId());
        w.u16(P_STATIC_DATA).i32(sk.staticData());
        w.u16(SkillPackets.V_ITEM_TYPE).i32(sk.itemType() == 0 ? 1000 : sk.itemType());
        w.u16(P_LEVEL).u8(Math.max(1, Math.min(255, sk.level())));
        w.u16(P_MAX_LEVEL).i32(sk.maxLevel() <= 0 ? 1 : sk.maxLevel());
        w.u16(P_CUR_FILL).i32((int) Math.min(Integer.MAX_VALUE, sk.fill()));
        w.u16(P_TOTAL_FILL).i32((int) Math.min(Integer.MAX_VALUE, sk.total()));
        w.u16(P_WU_XING).i32(0);
        w.u16(P_PREPARE_TIME).i32(1000);
        w.u16(P_TARGET_SHAPE2).f32(50.0f);
        return w.toByteArray();
    }

    /** 稳定物品 UID（16 hex，跨登录一致，客户端以 UID 追踪物品实例）。 */
    public static String uniqueId(long roleId, String kind, int slot) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((roleId + ":" + kind + ":" + slot).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
