package com.nineyin.server.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 战斗一期 S2C 帧族（docs/combat-skill-211.md wire 规格，62433 技能抓包逐字节对齐）：
 * cooldown 双行 / active_skill_rec 增删 / msg337 施法者位置 / msg20 技能动作 /
 * msg24 伤害飘字 / msg18 受击动作 / msg205 命中确认 / msg169 技能 buff /
 * 0x2D 受击特效 / 0x13 fight_relation 威胁格 / NPC 战斗属性帧。
 */
public final class FightMessages {

    /** msg24 技能形态第 3 值（抓包恒 17；伤害类型旗标，语义未完全钉死）。 */
    public static final int DAMAGE_FLAG_SKILL = 17;
    /** 0x2D 常量（抓包全程不变：u16@11=2、u16@13=28、u16@19=112）。 */
    static final int HIT_EFFECT_A = 2;
    static final int HIT_EFFECT_B = 28;
    static final int HIT_EFFECT_C = 112;

    // ------------------------------------------------------------------ 记录行

    /** cooldown_rec 行：[category, start(unixms), end, team(u16)]（个人行 team=0xFFFF）。 */
    public static byte[] cooldownRow(int objectId, int ownerId, int category,
                                     long startMs, long endMs, int team) {
        return RecordMessages.recordAddCells(objectId, ownerId, RecordMessages.RECORD_COOLDOWN,
                RecordMessages.Cell.ofInt(category),
                RecordMessages.Cell.ofInt64(startMs),
                RecordMessages.Cell.ofInt64(endMs),
                RecordMessages.Cell.ofInt(team));
    }

    /** active_skill_rec 增行（当前施放技能，单字符串列）。 */
    public static byte[] activeSkillRow(int objectId, int ownerId, String skillId) {
        return RecordMessages.recordAddString(objectId, ownerId,
                RecordMessages.RECORD_ACTIVE_SKILL, skillId);
    }

    /** active_skill_rec 删行（动作结束，行号恒 0）。 */
    public static byte[] activeSkillRowRemove(int objectId, int ownerId) {
        return RecordMessages.recordDelRow(objectId, ownerId,
                RecordMessages.RECORD_ACTIVE_SKILL, 0);
    }

    /**
     * fight_relation_rec 威胁格（21B 字面复刻）：
     * {@code 13 | u8 0 | u32 obj | u32 owner | u16 rec | u16 1 | u16 1 | u8 0 | u32 累计伤害}。
     */
    public static byte[] fightRelationThreat(int objectId, int ownerId, long threat) {
        PacketWriter writer = PacketWriter.ofCapacity(21);
        writer.writeU8(0x13).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(RecordMessages.RECORD_FIGHT_RELATION)
                .writeU16(1).writeU16(1)
                .writeU8(0)
                .writeU32(threat & 0xFFFFFFFFL);
        return writer.toBytes();
    }

    // ------------------------------------------------------------------ custom

    /** msg337：施法者位置/朝向同步（[obj, x, y, z, orient, 0]，值取 211 请求）。 */
    public static byte[] casterTransformSync(int objectId, int ownerId,
                                             float x, float y, float z, float orient) {
        return CustomMessages.customIntMessage(337,
                CustomMessages.Value.ofObject(objectId, ownerId),
                CustomMessages.Value.ofFloat32(x),
                CustomMessages.Value.ofFloat32(y),
                CustomMessages.Value.ofFloat32(z),
                CustomMessages.Value.ofFloat32(orient),
                CustomMessages.Value.ofInt32(0));
    }

    /**
     * msg20：技能动作。[obj(施法者), 技能串, obj(动作目标), 0, 0.0]。动作目标
     * （57378/52795 官服抓包实证）：锁定/冲锋技（SkillLock/CanRush）=211 目标对象
     * ——客户端据此本地执行冲锋位移，服务端无位移帧；自增益招（IsDamage=0）=施法者
     * 自己；其余伤害技恒空对象 obj(0,0)。目标缺席（id=0）即空对象。
     */
    public static byte[] skillAction(int casterId, int casterOwner, String skillId,
                                     int targetId, int targetOwner) {
        return CustomMessages.customIntMessage(20,
                CustomMessages.Value.ofObject(casterId, casterOwner),
                CustomMessages.Value.ofString(skillId),
                targetId != 0 ? CustomMessages.Value.ofObject(targetId, targetOwner)
                        : CustomMessages.Value.ofEmptyObject(),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofFloat32(0));
    }

    /** msg24 技能伤害飘字（11 值形态）。 */
    public static byte[] skillDamageFloat(int victimId, int victimOwner, int damage,
                                          String skillId, String script, String victimModel,
                                          String attackerName) {
        return CustomMessages.customIntMessage(24,
                CustomMessages.Value.ofObject(victimId, victimOwner),
                CustomMessages.Value.ofInt32(DAMAGE_FLAG_SKILL),
                CustomMessages.Value.ofInt32(damage),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofString(skillId),
                CustomMessages.Value.ofString(script),
                CustomMessages.Value.ofString(victimModel),
                CustomMessages.Value.ofWideString(attackerName),
                CustomMessages.Value.ofInt32(0));
    }

    /** msg18：通用动作命令（受击 "hurt_half"）。 */
    public static byte[] hurtAction(int victimId, int victimOwner, int attackerId, int attackerOwner) {
        return CustomMessages.customIntMessage(18,
                CustomMessages.Value.ofObject(victimId, victimOwner),
                CustomMessages.Value.ofString("hurt_half"),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofObject(attackerId, attackerOwner));
    }

    /** msg205：命中确认（[obj(施法者), 技能串, obj(目标)]，逐目标一条）。 */
    public static byte[] hitConfirm(int casterId, int casterOwner, String skillId,
                                    int targetId, int targetOwner) {
        return CustomMessages.customIntMessage(205,
                CustomMessages.Value.ofObject(casterId, casterOwner),
                CustomMessages.Value.ofString(skillId),
                CustomMessages.Value.ofObject(targetId, targetOwner));
    }

    /** msg169 技能 buff 六值形态（[0, buff串, obj(施法者), obj(承载者), 宽名, 宽名, 0, 0]）。 */
    public static byte[] skillBuff(int casterId, int casterOwner, int bearerId, int bearerOwner,
                                   String buffConfigId, String playerName) {
        return CustomMessages.customIntMessage(169,
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofString(buffConfigId),
                CustomMessages.Value.ofObject(casterId, casterOwner),
                CustomMessages.Value.ofObject(bearerId, bearerOwner),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofInt32(0));
    }

    /**
     * msg169 九值时间形态（52795 抓包 buf_qg_light 实证）：v1=0 挂/1 摘、
     * v2=buff 串、v3/v4=施法者/承载者 obj、v5/v6=宽名×2、v7=到期 unix 毫秒(i64)、
     * v8=buff_static Category（buf_qg_light=2048）。格挡 BuffInParry 同形态（v7 口径
     * 为当时刻，此处对齐抓包用到期时刻）。
     */
    public static byte[] timedBuff(int addRemove, String buffConfigId, int category, long endEpochMs,
                                   int bearerId, int bearerOwner, String playerName) {
        return CustomMessages.customIntMessage(169,
                CustomMessages.Value.ofInt32(addRemove),
                CustomMessages.Value.ofString(buffConfigId),
                CustomMessages.Value.ofObject(bearerId, bearerOwner),
                CustomMessages.Value.ofObject(bearerId, bearerOwner),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofInt64(endEpochMs),
                CustomMessages.Value.ofInt32(category));
    }

    // ------------------------------------------------------------------ 二进制

    /**
     * 0x2D 受击特效（25B）：{@code 2D 01 00 | u32 受害者 | u32 owner | u16 2 | u16 28
     * | u32 受害者HPRatio | u16 112 | u32 受害者HPRatio}（HPRatio 出现两次）。
     */
    public static byte[] hitEffect(int victimId, int victimOwner, int hpRatio) {
        PacketWriter writer = PacketWriter.ofCapacity(25);
        writer.writeU8(0x2D).writeU8(1).writeU8(0)
                .writeU32(victimId).writeU32(victimOwner)
                .writeU16(HIT_EFFECT_A).writeU16(HIT_EFFECT_B)
                .writeU32(hpRatio)
                .writeU16(HIT_EFFECT_C)
                .writeU32(hpRatio);
        return writer.toBytes();
    }

    /** NPC 战斗属性帧（0x10）：HP/MaxHP/HPRatio/LogicState/Dead。 */
    public static byte[] npcCombatFrame(int npcId, int ownerId, int hp, int maxHp,
                                        int logicState, boolean dead) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(5);
        Props.add(properties, "HP", PropertyValues.Value.ofInt32(hp));
        Props.add(properties, "MaxHP", PropertyValues.Value.ofInt32(maxHp));
        Props.add(properties, "HPRatio", PropertyValues.Value.ofInt32(maxHp <= 0 ? 0 : hp * 100 / maxHp));
        Props.add(properties, "LogicState", PropertyValues.Value.ofByte(logicState));
        Props.add(properties, "Dead", PropertyValues.Value.ofInt32(dead ? 1 : 0));
        return SceneMessages.objectProperties(npcId, ownerId, 0, properties);
    }

    /**
     * View40 技能行可用性帧（62433 抓包实证 2159 帧）：{@code 0x10 | isView=1 |
     * u32 viewId(40) | u32 行号 | u16 1 | u16 CanUse(2236) | u8 0/1}。
     * 不发则客户端技能图标恒为不可用（真机反馈：显示不可用但能释放）。
     */
    public static byte[] skillRowCanUse(int viewId, int row, boolean canUse) {
        PropertyValues.Indexed property = PropertyValues.Indexed.of(
                PropertyTable.playerIdx("CanUse"), "CanUse",
                PropertyValues.Value.ofByte(canUse ? 1 : 0));
        return SceneMessages.objectProperties(viewId, row, 1, List.of(property));
    }

    private FightMessages() {
    }
}
