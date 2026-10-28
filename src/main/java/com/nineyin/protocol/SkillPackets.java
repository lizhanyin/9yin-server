package com.nineyin.protocol;

/**
 * 技能容器（SkillContainer View=40）相关 S2C 包：
 * 0x15 CreateView / 0x18 ViewAdd（官服 21 属性布局）/ 0x1E 技能开关。
 */
public final class SkillPackets {

    public static final int VIEW_SKILL = 40;

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

    /** 起始技能表（官服建号抓包，8 项）。 */
    public record StarterSkill(String configId, int staticData, int itemType,
                               int maxLevel, int cooldownMs, int varPropNo) {
    }

    public static final java.util.List<StarterSkill> STARTER_SKILLS = java.util.List.of(
            new StarterSkill("zs_default_01", 22, 1000, 1, 5000, 421),
            new StarterSkill("CS_jh_xfz01", 13189, 1001, 12, 1500, 0),
            new StarterSkill("CS_jh_xfz02", 13190, 1000, 12, 1500, 0),
            new StarterSkill("CS_jh_xfz03", 13205, 1000, 1, 1500, 0),
            new StarterSkill("CS_jh_xfz04", 13206, 1000, 1, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs01", 12636, 1000, 11, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs02", 12637, 1001, 11, 1500, 0),
            new StarterSkill("CS_yhwq_hsqs03", 12638, 1000, 11, 1500, 0));

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
