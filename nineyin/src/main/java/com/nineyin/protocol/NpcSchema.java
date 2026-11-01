package com.nineyin.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 0x09 可见属性表数据源，对齐 Go clientdata.VisibleNPCModernV1（99 基础字段，
 * 现代修正：index 34 NpcType 为 INT32）+ cmd/protocol-probe/main.go
 * sceneVisiblePropertyFields 的完整扩展（0x09 表共 228 项）。
 *
 * <p>基础 99 项由 Go 源提取生成；golden property_fields.json 单测兜底逐项一致。
 */
public final class NpcSchema {

    public static final int BUFFER_LIST_PROPERTY_INDEX = 143;
    public static final int BUFFER_INFO_FIRST_PROPERTY_INDEX = BUFFER_LIST_PROPERTY_INDEX + 1;
    public static final int BUFFER_INFO_SLOT_COUNT = 24;

    public record FieldSpec(int index, String name, WireType type) {
    }

    private static FieldSpec f(int index, String name, WireType type) {
        return new FieldSpec(index, name, type);
    }

    private static final List<FieldSpec> VISIBLE_NPC_MODERN_V1 = List.of(
            f(0, "Type", WireType.BYTE),
            f(1, "Sex", WireType.BYTE),
            f(2, "Photo", WireType.STRING),
            f(3, "Camp", WireType.INT32),
            f(4, "Race", WireType.INT32),
            f(5, "Job", WireType.INT32),
            f(6, "Level", WireType.INT32),
            f(7, "ConfigID", WireType.STRING),
            f(8, "Resource", WireType.STRING),
            f(9, "PosiX", WireType.FLOAT32),
            f(10, "PosiY", WireType.FLOAT32),
            f(11, "PosiZ", WireType.FLOAT32),
            f(12, "Orient", WireType.FLOAT32),
            f(13, "Hair", WireType.STRING),
            f(14, "Face", WireType.STRING),
            f(15, "Cloth", WireType.STRING),
            f(16, "Pants", WireType.STRING),
            f(17, "Shoes", WireType.STRING),
            f(18, "ActionSet", WireType.STRING),
            f(19, "State", WireType.STRING),
            f(20, "FloatingState", WireType.INT32),
            f(21, "LogicState", WireType.BYTE),
            f(22, "JumpSpeed", WireType.FLOAT32),
            f(23, "MoveSpeed", WireType.FLOAT32),
            f(24, "WalkSpeed", WireType.FLOAT32),
            f(25, "RunSpeed", WireType.FLOAT32),
            f(26, "SpeedRatio", WireType.INT32),
            f(27, "CantJump", WireType.BYTE),
            f(28, "HPRatio", WireType.INT32),
            f(29, "MPRatio", WireType.INT32),
            f(30, "MaxHP", WireType.INT32),
            f(31, "MaxMP", WireType.INT32),
            f(32, "HP", WireType.INT32),
            f(33, "MP", WireType.INT32),
            f(34, "NpcType", WireType.INT32),
            f(35, "Hat", WireType.STRING),
            f(36, "Name", WireType.WIDE_STRING),
            f(37, "Scale", WireType.STRING),
            f(38, "SelectState", WireType.BYTE),
            f(39, "CollideType", WireType.INT32),
            f(40, "CantShowHeadInfo", WireType.BYTE),
            f(41, "NPCCanSelect", WireType.WORD),
            f(42, "WeaponMode", WireType.STRING),
            f(43, "RotatePara", WireType.STRING),
            f(44, "NeedRotate", WireType.INT32),
            f(45, "Collide", WireType.INT32),
            f(46, "CursorShape", WireType.BYTE),
            f(47, "CollideRadius", WireType.FLOAT32),
            f(48, "LuaScript", WireType.STRING),
            f(49, "AITemplate", WireType.INT32),
            f(50, "PatrolMode", WireType.INT32),
            f(51, "PatrolRange", WireType.FLOAT32),
            f(52, "SpringRange", WireType.FLOAT32),
            f(53, "School", WireType.STRING),
            f(54, "Hometown", WireType.STRING),
            f(55, "OriginPara1", WireType.INT32),
            f(56, "OriginPara2", WireType.INT32),
            f(57, "OriginPara3", WireType.INT32),
            f(58, "NpcTalkRule", WireType.STRING),
            f(59, "CanQiecuo", WireType.INT32),
            f(60, "PowerNo", WireType.INT32),
            f(61, "BossLevel", WireType.INT32),
            f(62, "NotPositive", WireType.INT32),
            f(63, "CantAttack", WireType.INT32),
            f(64, "CantBeAttack", WireType.INT32),
            f(65, "CantBeKilled", WireType.INT32),
            f(66, "AIFightFreq", WireType.INT32),
            f(67, "ChaseRange", WireType.FLOAT32),
            f(68, "SkillHoldTime", WireType.INT32),
            f(69, "SelfForceID", WireType.INT32),
            f(70, "SummonIDBeginFight", WireType.INT32),
            f(71, "StopTime", WireType.INT32),
            f(72, "Character", WireType.INT32),
            f(73, "KarmaLevel", WireType.INT32),
            f(74, "Force", WireType.INT32),
            f(75, "MotionNoRotate", WireType.BYTE),
            f(76, "CanPick", WireType.BYTE),
            f(77, "VisType", WireType.INT32),
            f(78, "VisDataInt", WireType.INT32),
            f(79, "VisDataStr", WireType.WIDE_STRING),
            f(80, "HeadEffect", WireType.INT32),
            f(81, "NpcNickName", WireType.WIDE_STRING),
            f(82, "ExtraInfo", WireType.STRING),
            f(83, "RoleHide", WireType.INT32),
            f(84, "CKindID", WireType.INT32),
            f(85, "EscortFlag", WireType.INT32),
            f(86, "SpringEffect", WireType.STRING),
            f(87, "Material", WireType.STRING),
            f(88, "WeaponName", WireType.STRING),
            f(89, "BornAction", WireType.STRING),
            f(90, "ActionSoundMode", WireType.INT32),
            f(91, "Fixed", WireType.INT32),
            f(92, "FixedBeHitAct", WireType.STRING),
            f(93, "RefreshTime", WireType.INT32),
            f(94, "DirectSpring", WireType.INT32),
            f(95, "NpcTalkType", WireType.INT32),
            f(96, "PreLoad", WireType.INT32),
            f(97, "OnlyCareDistance", WireType.INT32),
            f(98, "RefreshAtOnce", WireType.INT32)
    );

    /** Go clientdata.VisibleNPCModernV1()：record 不可变，直接返回。 */
    public static List<FieldSpec> visibleNpcModernV1() {
        return VISIBLE_NPC_MODERN_V1;
    }

    /** Go sceneVisiblePropertyFields：99 基础 + 扩展到 228 项（顺序即协商顺序）。 */
    public static List<FieldSpec> sceneVisiblePropertyFields() {
        List<FieldSpec> fields = new ArrayList<>(VISIBLE_NPC_MODERN_V1);
        fields.add(f(99, "MaxVisuals", WireType.INT32));
        fields.add(f(100, "LastObject", WireType.OBJECT));
        // View 归档共用协商表：form_shop.lua createview 分支直接读取 101-111。
        fields.add(f(101, "ShopID", WireType.STRING));
        fields.add(f(102, "PageNum", WireType.INT32));
        fields.add(f(103, "PageCount", WireType.INT32));
        fields.add(f(104, "ShopType", WireType.INT32));
        fields.add(f(105, "ConfigID", WireType.STRING));
        fields.add(f(106, "Amount", WireType.INT32));
        fields.add(f(107, "SellPrice0", WireType.INT32));
        fields.add(f(108, "SellPrice1", WireType.INT32));
        fields.add(f(109, "SellPrice2", WireType.INT32));
        fields.add(f(110, "MaxAmount", WireType.INT32));
        fields.add(f(111, "CapitalType1", WireType.INT32));
        fields.add(f(112, "HitHP", WireType.INT32));
        fields.add(f(113, "HitHPRatio", WireType.INT32));
        fields.add(f(114, "QingGongPoint", WireType.INT32));
        fields.add(f(115, "MaxQingGongPoint", WireType.INT32));
        fields.add(f(116, "MaxQingGongPointAdd", WireType.INT32));
        fields.add(f(117, "RunSpeedAdd", WireType.FLOAT32));
        fields.add(f(118, "JumpSpeedAdd", WireType.FLOAT32));
        fields.add(f(119, "DriftSpeed", WireType.FLOAT32));
        fields.add(f(120, "DriftSpeedAdd", WireType.FLOAT32));
        fields.add(f(121, "DriftJumpSpeed", WireType.FLOAT32));
        fields.add(f(122, "DriftJumpSpeedAdd", WireType.FLOAT32));
        fields.add(f(123, "SndJumpSpeed", WireType.FLOAT32));
        fields.add(f(124, "SndJumpSpeedAdd", WireType.FLOAT32));
        fields.add(f(125, "ThdJumpSpeed", WireType.FLOAT32));
        fields.add(f(126, "ThdJumpSpeedAdd", WireType.FLOAT32));
        fields.add(f(127, "AirRushSpeed", WireType.FLOAT32));
        fields.add(f(128, "AirRushSpeedAdd", WireType.FLOAT32));
        fields.add(f(129, "AirRushDist", WireType.FLOAT32));
        fields.add(f(130, "AirRushDistAdd", WireType.FLOAT32));
        fields.add(f(131, "LandRushSpeed", WireType.FLOAT32));
        fields.add(f(132, "LandRushSpeedAdd", WireType.FLOAT32));
        fields.add(f(133, "LandRushDist", WireType.FLOAT32));
        fields.add(f(134, "LandRushDistAdd", WireType.FLOAT32));
        fields.add(f(135, "ClimbSpeed", WireType.FLOAT32));
        fields.add(f(136, "ClimbSpeedAdd", WireType.FLOAT32));
        fields.add(f(137, "GRushRange", WireType.FLOAT32));
        fields.add(f(138, "GRushRangeAdd", WireType.FLOAT32));
        fields.add(f(139, "ARushRange", WireType.FLOAT32));
        fields.add(f(140, "ARushRangeAdd", WireType.FLOAT32));
        fields.add(f(141, "WRushRange", WireType.FLOAT32));
        fields.add(f(142, "WRushRangeAdd", WireType.FLOAT32));
        fields.add(f(143, "BufferListStr", WireType.STRING));
        // 原生 BuffStore：BufferInfo1..24。
        for (int slot = 1; slot <= BUFFER_INFO_SLOT_COUNT; slot++) {
            fields.add(f(bufferInfoPropertyIndex(slot), bufferInfoPropertyName(slot), WireType.STRING));
        }
        // 基础跳跃物理（原生玩家动作对象读取，非轻功包派生）。
        fields.add(f(168, "Gravity", WireType.FLOAT32));
        fields.add(f(169, "GravityAdd", WireType.FLOAT32));
        fields.add(f(170, "DropHeightPub", WireType.FLOAT32));
        // 内功与角色面板字段（playerProgressFields，171..207）。
        fields.addAll(playerProgressFields());
        // 211 预发送门与门派选择的协商字段（208..227）。
        fields.add(f(PropertyIndex.NEIGONG_PK_STATUS, "NeigongPKStatus", WireType.INT32));
        fields.add(f(PropertyIndex.DEAD, "Dead", WireType.INT32));
        fields.add(f(PropertyIndex.CANT_USE_SKILL, "CantUseSkill", WireType.INT32));
        fields.add(f(PropertyIndex.CUR_SKILL_ID, "CurSkillID", WireType.STRING));
        fields.add(f(PropertyIndex.PAUSE_TIME, "PauseTime", WireType.FLOAT32));
        fields.add(f(PropertyIndex.HP_HEART_SPEED, "HPHeartSpeed", WireType.INT32));
        fields.add(f(PropertyIndex.HP_HEART_SPEED_ADD, "HPHeartSpeedAdd", WireType.INT32));
        fields.add(f(PropertyIndex.MP_HEART_SPEED, "MPHeartSpeed", WireType.INT32));
        fields.add(f(PropertyIndex.MP_HEART_SPEED_ADD, "MPHeartSpeedAdd", WireType.INT32));
        fields.add(f(PropertyIndex.HP_UP_SPEED, "HPUpSpeed", WireType.INT32));
        fields.add(f(PropertyIndex.HP_UP_SPEED_ADD, "HPUpSpeedAdd", WireType.INT32));
        fields.add(f(PropertyIndex.MP_UP_SPEED, "MPUpSpeed", WireType.INT32));
        fields.add(f(PropertyIndex.MP_UP_SPEED_ADD, "MPUpSpeedAdd", WireType.INT32));
        fields.add(f(PropertyIndex.CUR_SKILL_EFFECT_ID, "CurSkillEffectID", WireType.STRING));
        fields.add(f(PropertyIndex.CUR_SKILL_LEVEL, "CurSkillLevel", WireType.INT32));
        fields.add(f(PropertyIndex.CUR_SKILL_TARGET, "CurSkillTarget", WireType.OBJECT));
        fields.add(f(PropertyIndex.MODIFY_SKILL_LOCK_TIME, "ModifySkillLockTime", WireType.INT32));
        fields.add(f(PropertyIndex.SKILL_CAN_USE, "CanUse", WireType.BYTE));
        fields.add(f(PropertyIndex.FORCE, "Force", WireType.STRING));
        fields.add(f(PropertyIndex.NEW_SCHOOL, "NewSchool", WireType.STRING));
        return fields;
    }

    /** Go player_progress.go playerProgressFields（iota 171 起 37 项）。 */
    public static List<FieldSpec> playerProgressFields() {
        return List.of(
                f(PropertyIndex.CUR_NEIGONG, "CurNeiGong", WireType.STRING),
                f(PropertyIndex.FACULTY, "Faculty", WireType.INT32),
                f(PropertyIndex.FACULTY_STATE, "FacultyState", WireType.INT32),
                f(PropertyIndex.FACULTY_STYLE, "FacultyStyle", WireType.INT32),
                f(PropertyIndex.FACULTY_NAME, "FacultyName", WireType.STRING),
                f(PropertyIndex.CUR_FILL_VALUE, "CurFillValue", WireType.INT32),
                f(PropertyIndex.TOTAL_FILL_VALUE, "TotalFillValue", WireType.INT32),
                f(PropertyIndex.CUR_LEVEL, "CurLevel", WireType.INT32),
                f(PropertyIndex.FILL_SPEED, "FillSpeed", WireType.INT32),
                f(PropertyIndex.POWER_VALUE, "PowerValue", WireType.INT32),
                f(PropertyIndex.MAX_POWER_VALUE, "MaxPowerValue", WireType.INT32),
                f(PropertyIndex.SP, "SP", WireType.INT32),
                f(PropertyIndex.STR, "Str", WireType.INT32),
                f(PropertyIndex.STA, "Sta", WireType.INT32),
                f(PropertyIndex.DEX, "Dex", WireType.INT32),
                f(PropertyIndex.ING, "Ing", WireType.INT32),
                f(PropertyIndex.SPI, "Spi", WireType.INT32),
                f(PropertyIndex.MELEE_POWER, "MeleePower", WireType.INT32),
                f(PropertyIndex.MAGIC_POWER, "MagicPower", WireType.INT32),
                f(PropertyIndex.PHY_HIT, "PhyHit", WireType.INT32),
                f(PropertyIndex.MAGIC_HIT, "MagicHit", WireType.INT32),
                f(PropertyIndex.DODGE, "Dodge", WireType.INT32),
                f(PropertyIndex.MAX_PARRY, "MaxParry", WireType.INT32),
                f(PropertyIndex.PHY_DEF, "PhyDef", WireType.INT32),
                f(PropertyIndex.STI_DEF, "StiDef", WireType.INT32),
                f(PropertyIndex.NEG_DEF, "NegDef", WireType.INT32),
                f(PropertyIndex.MAS_DEF, "MasDef", WireType.INT32),
                f(PropertyIndex.JUJ_DEF, "JujDef", WireType.INT32),
                f(PropertyIndex.MIN_MELEE_DAMAGE, "MinMeleeDamage", WireType.INT32),
                f(PropertyIndex.MAX_MELEE_DAMAGE, "MaxMeleeDamage", WireType.INT32),
                f(PropertyIndex.MAX_HP_ADD, "MaxHPAdd", WireType.INT32),
                f(PropertyIndex.MAX_MP_ADD, "MaxMPAdd", WireType.INT32),
                f(PropertyIndex.MAX_LEVEL, "MaxLevel", WireType.INT32),
                f(PropertyIndex.STATIC_DATA, "StaticData", WireType.INT32),
                f(PropertyIndex.ITEM_TYPE, "ItemType", WireType.INT32),
                f(PropertyIndex.MAX_PARRY_ADD, "MaxParryAdd", WireType.INT32),
                f(PropertyIndex.MIN_MAGIC_DEF_ADD, "MinMagicDefAdd", WireType.INT32));
    }

    public static int bufferInfoPropertyIndex(int slot) {
        if (slot == 0 || slot > BUFFER_INFO_SLOT_COUNT) {
            throw new IllegalArgumentException("invalid BufferInfo slot " + slot);
        }
        return BUFFER_INFO_FIRST_PROPERTY_INDEX + slot - 1;
    }

    public static String bufferInfoPropertyName(int slot) {
        if (slot == 0 || slot > BUFFER_INFO_SLOT_COUNT) {
            throw new IllegalArgumentException("invalid BufferInfo slot " + slot);
        }
        return "BufferInfo" + slot;
    }

    private NpcSchema() {
    }
}
