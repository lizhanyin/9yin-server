package com.nineyin.protocol;

/**
 * 技能容器（SkillContainer View=40）相关 S2C 包：
 * 0x15 CreateView / 0x18 ViewAdd（官服 21 属性布局）/ 0x1E 技能开关。
 */
public final class SkillPackets {

    public static final int VIEW_SKILL = 40;
    /** 内功容器（Go viewportNeiGong）。 */
    public static final int VIEW_NEIGONG = 43;
    /** 轻功容器（Go viewportQingGong）。 */
    public static final int VIEW_QINGGONG = 46;
    /** 商店容器（Go viewportShop）。 */
    public static final int VIEW_SHOP = 61;
    /** 仓库容器（Go viewportDepot）。 */
    public static final int VIEW_DEPOT = 4;

    // 官服抓包还原的 View 属性索引
    public static final int V_ITEM_TYPE = 1887;
    public static final int V_CONFIG_ID = 1439;
    public static final int V_CAN_USE = 2234;
    public static final int V_STATIC_DATA = 2235;
    public static final int V_USE_LIMIT = 2236;
    public static final int V_LEVEL = 1464;
    public static final int V_VAR_PROP_NO = 2237;
    public static final int V_MAX_LEVEL = 2081;
    public static final int V_QUALITY = 2117;
    public static final int V_WU_XING = 2241;
    public static final int V_COOL_DOWN_TIME = 2032;
    public static final int V_HP_HEAL_MUL = 2263;
    public static final int V_MP_HEAL_MUL = 2264;
    public static final int V_PHY_DAMAGE_MUL = 517;
    public static final int V_STI_DAMAGE_MUL = 518;
    public static final int V_JUJ_DAMAGE_MUL = 519;
    public static final int V_NEG_DAMAGE_MUL = 520;
    public static final int V_MAS_DAMAGE_MUL = 521;
    public static final int V_FLOW = 2265;
    public static final int V_LEAD_TIME = 2268;
    public static final int V_LEAD_SEP_TIME = 2269;

    /** 起始技能行（官服 View 40 抓包布局的字段来源）。 */
    public record StarterSkill(String configId, int staticData, int itemType,
                               int maxLevel, int cooldownMs, int varPropNo) {
    }

    /**
     * 内置起始学习集（23 技）：参考 exe 回退集（`using built-in starter set`），
     * ConfigID 均内嵌于 exe 字符串表。zs_default_01 为打坐前置（走 240 原生路由）；
     * 花神七式 / 心佛掌 / 太极拳(古谱) 三套套路。staticData/itemType 与
     * skill_init.ini 逐一核对一致；maxLevel 取 varprop 区间真实上限（skill_init 的
     * maxlevel 列恒为 1，仅占位）；cooldownMs 为官服抓包显示值。
     */
    public static final java.util.List<StarterSkill> STARTER_SKILLS = java.util.List.of(
            new StarterSkill("zs_default_01", 22, 1000, 1, 5000, 421),
            // 花神七式（CS_yhwq_hsqs）
            new StarterSkill("CS_yhwq_hsqs01", 12636, 1000, 11, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs02", 12637, 1001, 11, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs03", 12638, 1000, 11, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs04", 12639, 1000, 9, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs05", 12640, 1000, 4, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs06", 12641, 1000, 11, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs07", 12642, 1001, 11, 1500, 0),
            // 心佛掌（CS_jh_xfz）：03/04/07 单 varprop 行 → 一级封顶
            new StarterSkill("CS_jh_xfz01", 13189, 1001, 12, 1500, 0),
            new StarterSkill("CS_jh_xfz02", 13190, 1000, 12, 1500, 0),
            new StarterSkill("CS_jh_xfz03", 13205, 1000, 1, 1500, 0),
            new StarterSkill("CS_jh_xfz04", 13206, 1000, 1, 1500, 0),
            new StarterSkill("CS_jh_xfz05", 13207, 1000, 12, 1500, 0),
            new StarterSkill("CS_jh_xfz06", 13208, 1000, 12, 1500, 0),
            new StarterSkill("CS_jh_xfz07", 13203, 1000, 1, 1500, 0),
            // 太极拳(古谱)（CS_wd_tjq）：07 三级封顶，其余 varprop 19 行
            new StarterSkill("CS_wd_tjq01", 4057, 1000, 19, 1500, 0),
            new StarterSkill("CS_wd_tjq02", 4058, 1001, 19, 1500, 0),
            new StarterSkill("CS_wd_tjq03", 4238, 1000, 19, 1500, 0),
            new StarterSkill("CS_wd_tjq04", 4045, 1000, 19, 1500, 0),
            new StarterSkill("CS_wd_tjq05", 5451, 1000, 19, 1500, 0),
            new StarterSkill("CS_wd_tjq06", 5452, 1000, 19, 1500, 0),
            new StarterSkill("CS_wd_tjq07", 5453, 1000, 3, 1500, 0),
            new StarterSkill("CS_wd_tjq08", 2107, 1000, 19, 1500, 0));

    private SkillPackets() {
    }

    // ---------- 0x15 CreateView ----------
    // [0]=0x15 u16 view@1 u16 capacity@3，共 7 字节（尾 2 字节保留 0）

    public static byte[] createView(int viewId, int capacity) {
        return new PacketWriter()
                .u8(0x15)
                .u16(viewId)
                .u16(capacity)
                .u16(0)
                .toByteArray();
    }

    /** 带初始属性的 0x15（商店视图等）：view + capacity + u16 属性数 + 属性值。 */
    public static PacketWriter createViewHeader(int viewId, int capacity, int propCount) {
        return new PacketWriter()
                .u8(0x15)
                .u16(viewId)
                .u16(capacity)
                .u16(propCount);
    }

    /** 0x16 视图清除（3 字节：op + u16 view）——商店重开前清 View 61，§3.19 实机帧序。 */
    public static byte[] viewClear(int viewId) {
        return new PacketWriter().u8(0x16).u16(viewId).toByteArray();
    }

    /** 通用 0x18 ViewAdd 头：op + u16 view + u16 index + u16 属性数。 */
    public static PacketWriter viewAddHeader(int viewId, int index, int propCount) {
        return new PacketWriter()
                .u8(0x18)
                .u16(viewId)
                .u16(index)
                .u16(propCount);
    }

    /** View 46 轻功容器项（1 个属性：ConfigID string）。 */
    public static byte[] qinggongViewAdd(int viewId, int index, String configId) {
        return viewAddHeader(viewId, index, 1)
                .u16(V_CONFIG_ID)
                .string(configId)
                .toByteArray();
    }

    // ---------- 0x18 ViewAdd ----------
    // [0]=0x18 u16 view@1 u16 index@3 u16 属性数@5，随后 21 个 u16 索引 + 值

    public static byte[] skillViewAdd(int index, StarterSkill sk) {
        PacketWriter w = new PacketWriter()
                .u8(0x18)
                .u16(VIEW_SKILL)
                .u16(index)
                .u16(21);
        w.u16(V_ITEM_TYPE).i32(sk.itemType());
        w.u16(V_CONFIG_ID).string(sk.configId());
        w.u16(V_CAN_USE).u8(1);
        w.u16(V_STATIC_DATA).i32(sk.staticData());
        w.u16(V_USE_LIMIT).i32(9);
        w.u16(V_LEVEL).u8(1);
        w.u16(V_VAR_PROP_NO).i32(sk.varPropNo());
        w.u16(V_MAX_LEVEL).i32(sk.maxLevel());
        w.u16(V_QUALITY).u8(1);
        w.u16(V_WU_XING).i32(5);
        w.u16(V_COOL_DOWN_TIME).i32(sk.cooldownMs());
        for (int idx : new int[]{V_HP_HEAL_MUL, V_MP_HEAL_MUL, V_PHY_DAMAGE_MUL, V_STI_DAMAGE_MUL,
                V_JUJ_DAMAGE_MUL, V_NEG_DAMAGE_MUL, V_MAS_DAMAGE_MUL}) {
            w.u16(idx).f32(1.0f);
        }
        w.u16(V_FLOW).i32(5);
        w.u16(V_LEAD_TIME).i32(10000);
        w.u16(V_LEAD_SEP_TIME).i32(1500);
        return w.toByteArray();
    }

    /** View 40 容器 + 全部起始技能（ClientReady / 0x0C 重进后使用）。 */
    public static java.util.List<byte[]> starterSkillFrames() {
        java.util.List<byte[]> frames = new java.util.ArrayList<>(STARTER_SKILLS.size() + 1);
        frames.add(createView(VIEW_SKILL, 800));
        for (int i = 0; i < STARTER_SKILLS.size(); i++) {
            frames.add(skillViewAdd(i + 1, STARTER_SKILLS.get(i)));
        }
        return frames;
    }

    // ---------- 0x1E 技能开关（msg 414 "reset" / 1223） ----------

    public static byte[] skillActionSwitch() {
        PacketWriter w = new PacketWriter().u8(0x1E).u8(4).u8(0);
        w.u8(2).i32(414);
        byte[] reset = "reset".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        w.u8(6).u32(reset.length + 1).raw(reset).u8(0);
        w.u8(2).i32(1223);
        w.u8(2).i32(1);
        return w.toByteArray();
    }

    // ---------- 0x10 LastObject（对象类型值：u16 索引 + u32 oid + u32 owner） ----------

    public static byte[] lastObject(int objectId, int ownerId, int targetOid, int targetOwner) {
        return new PacketWriter()
                .u8(0x10)
                .u8(0)
                .u32(objectId)
                .u32(ownerId)
                .u16(1)
                .u16(PropertyId.LAST_OBJECT)
                .u32(targetOid)
                .u32(targetOwner)
                .toByteArray();
    }
}
