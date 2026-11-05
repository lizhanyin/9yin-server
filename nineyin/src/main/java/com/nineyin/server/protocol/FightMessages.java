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

    /**
     * msg19：锁定/冲锋族出招锁定帧（官服 out2 时间线钉死 24 值布局，2026-11-06）：
     * {@code [19, obj 施法者, obj 目标, 技能串, i32 LockSrcTime, i32 LockTargetTime,
     * f32 施法者x/y/z, f32 施法者朝向, f32 0, f32 目标x/y/z, f32 目标朝向,
     * f32 BindDistance, str "0", f32 0, i32 0, i32 0, f32 0, f32 绑定点x/y/z]}
     * ——绑定点=目标朝施法者方向 BindDistance 处（官服两例实测 1.49/1.08=各自
     * BindDistance）。**官方锁定族（SkillLock/CanRush，含冲锋/闪避/招架变体）恒走
     * msg19 不走 msg20**；msg20 只属非锁定技——此前我方全族发 msg20 即客户端不出
     * 锁定/冲刺表现的一号根因。v18/v19（官服 603/725、75/25 语义未定）暂 0。
     */
    public static byte[] skillLockCast(int casterId, int casterOwner,
                                       int targetId, int targetOwner, String skillId,
                                       int srcLockMs, int targetLockMs,
                                       float srcX, float srcY, float srcZ, float srcOrient,
                                       float dstX, float dstY, float dstZ, float dstOrient,
                                       float bindDistance,
                                       float bindX, float bindY, float bindZ) {
        return CustomMessages.customIntMessage(19,
                CustomMessages.Value.ofObject(casterId, casterOwner),
                targetId != 0 ? CustomMessages.Value.ofObject(targetId, targetOwner)
                        : CustomMessages.Value.ofEmptyObject(),
                CustomMessages.Value.ofString(skillId),
                CustomMessages.Value.ofInt32(srcLockMs),
                CustomMessages.Value.ofInt32(targetLockMs),
                CustomMessages.Value.ofFloat32(srcX),
                CustomMessages.Value.ofFloat32(srcY),
                CustomMessages.Value.ofFloat32(srcZ),
                CustomMessages.Value.ofFloat32(srcOrient),
                CustomMessages.Value.ofFloat32(0),
                CustomMessages.Value.ofFloat32(dstX),
                CustomMessages.Value.ofFloat32(dstY),
                CustomMessages.Value.ofFloat32(dstZ),
                CustomMessages.Value.ofFloat32(dstOrient),
                CustomMessages.Value.ofFloat32(bindDistance),
                CustomMessages.Value.ofString("0"),
                CustomMessages.Value.ofFloat32(0),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofFloat32(0),
                CustomMessages.Value.ofFloat32(bindX),
                CustomMessages.Value.ofFloat32(bindY),
                CustomMessages.Value.ofFloat32(bindZ));
    }

    /**
     * msg24 NPC→玩家伤害飘字（10 值形态，c1=7；2026-11-04 参考服 55866 实证）：
     * {@code [obj(玩家受害), 7, 伤害, obj(NPC攻击者), 窄串NPC模型, 宽串玩家名, 宽串NPC名, 20]}。
     * 尾值 20=名义攻击力（参考服恒 20，实际扣血经防御折算为 1）。
     */
    public static byte[] npcDamageFloat(int playerId, int playerOwner, int damage,
                                        int npcId, int npcOwner, String npcModel,
                                        String playerName, String npcName) {
        return CustomMessages.customIntMessage(24,
                CustomMessages.Value.ofObject(playerId, playerOwner),
                CustomMessages.Value.ofInt32(7),
                CustomMessages.Value.ofInt32(damage),
                CustomMessages.Value.ofObject(npcId, npcOwner),
                CustomMessages.Value.ofString(npcModel),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofWideString(npcName),
                CustomMessages.Value.ofInt32(20));
    }

    /**
     * msg24 武技连击段（10 值形态，c1=71；参考服 55866 myjf08 实证）：
     * {@code [obj(受害), 71, obj(第二目标), '', 1, obj(攻击者), 30.0, 0.0, wuji技能名]}。
     * wujiName 为 "" 表示首段（base），其后 "&lt;LevelupRelevance&gt;_2/_3/_4"；30.0 常量含义未钉死（疑似段间隔秒）。
     */
    public static byte[] wujiComboFloat(int victimId, int victimOwner, int secondId, int secondOwner,
                                        int casterId, int casterOwner, String wujiName) {
        return CustomMessages.customIntMessage(24,
                CustomMessages.Value.ofObject(victimId, victimOwner),
                CustomMessages.Value.ofInt32(71),
                CustomMessages.Value.ofObject(secondId, secondOwner),
                CustomMessages.Value.ofString(""),
                CustomMessages.Value.ofInt32(1),
                CustomMessages.Value.ofObject(casterId, casterOwner),
                CustomMessages.Value.ofFloat64(30.0),
                CustomMessages.Value.ofFloat64(0.0),
                CustomMessages.Value.ofString(wujiName));
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
        return skillBuff(0, casterId, casterOwner, bearerId, bearerOwner, buffConfigId, playerName);
    }

    /** msg169 六值形态带 v0（0=摘/1=挂；参考服击杀后 [1, buf_fixed_red, …] 实证）。 */
    public static byte[] skillBuff(int addRemove, int casterId, int casterOwner,
                                   int bearerId, int bearerOwner,
                                   String buffConfigId, String playerName) {
        return CustomMessages.customIntMessage(169,
                CustomMessages.Value.ofInt32(addRemove),
                CustomMessages.Value.ofString(buffConfigId),
                CustomMessages.Value.ofObject(casterId, casterOwner),
                CustomMessages.Value.ofObject(bearerId, bearerOwner),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofWideString(playerName),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofInt32(0));
    }

    /**
     * msg169 目标 debuff 时间形态（参考服 55866 myjf08 每跳实证）：
     * {@code [1, buff串, obj(承载NPC), obj(施法者玩家), 空宽名, 宽串施法者名, 到期unix毫秒, 0]}
     * ——与 timedBuff 的差异：施法者/承载者是两个对象、v5 为空宽串。
     */
    public static byte[] targetDebuffTimed(String buffConfigId, int bearerId, int bearerOwner,
                                           int casterId, int casterOwner, String casterName,
                                           long endEpochMs) {
        return CustomMessages.customIntMessage(169,
                CustomMessages.Value.ofInt32(1),
                CustomMessages.Value.ofString(buffConfigId),
                CustomMessages.Value.ofObject(bearerId, bearerOwner),
                CustomMessages.Value.ofObject(casterId, casterOwner),
                CustomMessages.Value.ofWideString(""),
                CustomMessages.Value.ofWideString(casterName),
                CustomMessages.Value.ofInt64(endEpochMs),
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

    /**
     * msg25 技能暂停（冲锋暂存窗口）：{@code [25, 窗口毫秒, "skill_pause"]}——
     * 服务端暂存出招后下发，客户端进入冲锋等待（真机表现为技能倒计时），落地由
     * msg26 解除。窗口恒 30000ms。值序对齐参考服 61653 抓包（int 前、串后）。
     */
    public static byte[] skillPauseFrame(int windowMs) {
        return CustomMessages.customIntMessage(25,
                CustomMessages.Value.ofInt32(windowMs),
                CustomMessages.Value.ofString("skill_pause"));
    }

    /** msg26 解除技能暂停（空值；冲锋落地 consumePendingCast 成功后随 337 下发）。 */
    public static byte[] skillPauseClearFrame() {
        return CustomMessages.customIntMessage(26);
    }

    /**
     * msg24 c1=61 冲刺/疾跑指令（官服 out2 时间线 2026-11-06 再定谳）：{@code [24,
     * obj, 61, f32 destX, f32 destY, f32 destZ, 档位1|2, 动画名]}——**官服套路冲锋
     * 本人/他人同款广播**：动画名取 skill_lock_data 逐技能 LockGRushAction
     * （qg_fastrun_0h/2h/7h_01=地面冲刺）、LockARushAction（qg_fastruning_s_*=
     * 空中）等，终点=目标身前绑定距离处；官方序列=c61 → location(终点锚) → msg19
     * 锁定帧。档位 2+qg_fastruning_s_2h_01=参考服暂存链疾跑方言（61653）。
     */
    public static byte[] dashRushFrame(int playerId, int playerOwner,
                                       float x, float y, float z) {
        return dashRushFrame(playerId, playerOwner, x, y, z, 2, "qg_fastruning_s_2h_01");
    }

    /** msg24 c1=61 全参形态：档位（官服冲刺=1、参考服疾跑=2）+动画名（LockG/A/WRushAction）。 */
    public static byte[] dashRushFrame(int playerId, int playerOwner,
                                       float x, float y, float z, int level, String anim) {
        return CustomMessages.customIntMessage(24,
                CustomMessages.Value.ofObject(playerId, playerOwner),
                CustomMessages.Value.ofInt32(61),
                CustomMessages.Value.ofFloat32(x),
                CustomMessages.Value.ofFloat32(y),
                CustomMessages.Value.ofFloat32(z),
                CustomMessages.Value.ofInt32(level),
                CustomMessages.Value.ofString(anim));
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

    /**
     * View40 技能行等级帧（GM learn_all_taolu）：Level/MaxLevel/CurFillValue/
     * TotalFillValue 同一 0x10 行帧（isView=1，JingMaiMessages.levelUpFrame 同型；
     * Level/MaxLevel 为 int32——dbSkillViewAdd 行口径）。varPropNo 非空时随帧
     * 更新（按级 varprop 行号），null=保持现值。
     */
    public static byte[] skillRowLevelUp(int viewId, int row, int level, int maxLevel,
                                         int curFill, int totalFill, Integer varPropNo) {
        java.util.List<PropertyValues.Indexed> properties = new java.util.ArrayList<>(5);
        properties.add(PropertyValues.Indexed.of(PropertyTable.playerIdx("Level"), "Level",
                PropertyValues.Value.ofInt32(level)));
        properties.add(PropertyValues.Indexed.of(PropertyTable.playerIdx("MaxLevel"), "MaxLevel",
                PropertyValues.Value.ofInt32(maxLevel)));
        properties.add(PropertyValues.Indexed.of(
                PropertyTable.playerIdx("CurFillValue"), "CurFillValue",
                PropertyValues.Value.ofInt32(curFill)));
        properties.add(PropertyValues.Indexed.of(
                PropertyTable.playerIdx("TotalFillValue"), "TotalFillValue",
                PropertyValues.Value.ofInt32(totalFill)));
        if (varPropNo != null) {
            properties.add(PropertyValues.Indexed.of(
                    PropertyTable.playerIdx("VarPropNo"), "VarPropNo",
                    PropertyValues.Value.ofInt32(varPropNo)));
        }
        return SceneMessages.objectProperties(viewId, row, 1, properties);
    }

    private FightMessages() {
    }
}
