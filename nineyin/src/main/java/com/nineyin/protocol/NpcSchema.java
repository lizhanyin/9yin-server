package com.nineyin.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 对象属性 schema 的名字清单（三期官方表方言）：保留 Go 复刻方言的
 * 「哪些字段进对象」语义顺序，索引/线型全部由 {@link PropertyTable}
 * （官方 2424 项表）按名解析；官方表没有的名字（PosiX/Orient/BufferListStr/
 * Race/AITemplate 等 ~36 项）从线上剔除——官方从未把它们作为属性下发
 * （位置走 0x0D 头 + 0x1F，Buff 走 BufferInfo 槽串）。
 */
public final class NpcSchema {

    public static final int BUFFER_INFO_SLOT_COUNT = 24;

    public record FieldSpec(int index, String name, WireType type) {
    }

    /** VisibleNPCModernV1 的 99 个语义名（Go 顺序）。 */
    private static final List<String> VISIBLE_NPC_V1_NAMES = List.of(
            "Type", "Sex", "Photo", "Camp", "Race", "Job", "Level", "ConfigID", "Resource",
            "PosiX", "PosiY", "PosiZ", "Orient", "Hair", "Face", "Cloth", "Pants", "Shoes",
            "ActionSet", "State", "FloatingState", "LogicState", "JumpSpeed", "MoveSpeed",
            "WalkSpeed", "RunSpeed", "SpeedRatio", "CantJump", "HPRatio", "MPRatio", "MaxHP",
            "MaxMP", "HP", "MP", "NpcType", "Hat", "Name", "Scale", "SelectState", "CollideType",
            "CantShowHeadInfo", "NPCCanSelect", "WeaponMode", "RotatePara", "NeedRotate", "Collide",
            "CursorShape", "CollideRadius", "LuaScript", "AITemplate", "PatrolMode", "PatrolRange",
            "SpringRange", "School", "Hometown", "OriginPara1", "OriginPara2", "OriginPara3",
            "NpcTalkRule", "CanQiecuo", "PowerNo", "BossLevel", "NotPositive", "CantAttack",
            "CantBeAttack", "CantBeKilled", "AIFightFreq", "ChaseRange", "SkillHoldTime",
            "SelfForceID", "SummonIDBeginFight", "StopTime", "Character", "KarmaLevel", "Force",
            "MotionNoRotate", "CanPick", "VisType", "VisDataInt", "VisDataStr", "HeadEffect",
            "NpcNickName", "ExtraInfo", "RoleHide", "CKindID", "EscortFlag", "SpringEffect",
            "Material", "WeaponName", "BornAction", "ActionSoundMode", "Fixed", "FixedBeHitAct",
            "RefreshTime", "DirectSpring", "NpcTalkType", "PreLoad", "OnlyCareDistance",
            "RefreshAtOnce");

    /** sceneVisiblePropertyFields 的玩家扩展名（Go 99..227 语义序）。 */
    private static final List<String> SCENE_EXTENSION_NAMES = List.of(
            "MaxVisuals", "LastObject",
            // View 归档共用协商表：form_shop.lua createview 分支直接读取。
            "ShopID", "PageNum", "PageCount", "ShopType", "ConfigID", "Amount", "SellPrice0",
            "SellPrice1", "SellPrice2", "MaxAmount", "CapitalType1",
            "HitHP", "HitHPRatio", "QingGongPoint", "MaxQingGongPoint", "MaxQingGongPointAdd",
            "RunSpeedAdd", "JumpSpeedAdd", "DriftSpeed", "DriftSpeedAdd", "DriftJumpSpeed",
            "DriftJumpSpeedAdd", "SndJumpSpeed", "SndJumpSpeedAdd", "ThdJumpSpeed",
            "ThdJumpSpeedAdd", "AirRushSpeed", "AirRushSpeedAdd", "AirRushDist", "AirRushDistAdd",
            "LandRushSpeed", "LandRushSpeedAdd", "LandRushDist", "LandRushDistAdd", "ClimbSpeed",
            "ClimbSpeedAdd", "GRushRange", "GRushRangeAdd", "ARushRange", "ARushRangeAdd",
            "WRushRange", "WRushRangeAdd", "BufferListStr",
            // BufferInfo1..24 由 build 追加（名字存在于官方表）。
            "Gravity", "GravityAdd", "DropHeightPub",
            // 208..227：预发送门与门派选择字段。
            "NeigongPKStatus", "Dead", "CantUseSkill", "CurSkillID", "PauseTime", "HPHeartSpeed",
            "HPHeartSpeedAdd", "MPHeartSpeed", "MPHeartSpeedAdd", "HPUpSpeed", "HPUpSpeedAdd",
            "MPUpSpeed", "MPUpSpeedAdd", "CurSkillEffectID", "CurSkillLevel", "CurSkillTarget",
            "ModifySkillLockTime", "CanUse", "Force", "NewSchool");

    private static final List<FieldSpec> VISIBLE_NPC_MODERN_V1 = build(VISIBLE_NPC_V1_NAMES);
    private static final List<FieldSpec> SCENE_FIELDS = buildSceneFields();

    /** 按名解析；缺名剔除（官方方言不下发这些属性）。 */
    private static List<FieldSpec> build(List<String> names) {
        List<FieldSpec> fields = new ArrayList<>(names.size());
        for (String name : names) {
            PropertyTable.Entry entry = PropertyTable.byName(name);
            WireType type = entry == null ? null : PropertyTable.wireType(name);
            if (type == null) {
                continue;
            }
            fields.add(new FieldSpec(entry.index(), name, type));
        }
        return List.copyOf(fields);
    }

    private static List<FieldSpec> buildSceneFields() {
        List<String> names = new ArrayList<>(VISIBLE_NPC_V1_NAMES);
        // BufferInfo1..24 插在扩展表 BufferListStr 的语义位置（其本身缺名剔除）。
        for (String name : SCENE_EXTENSION_NAMES) {
            if (name.equals("BufferListStr")) {
                for (int slot = 1; slot <= BUFFER_INFO_SLOT_COUNT; slot++) {
                    names.add(bufferInfoPropertyName(slot));
                }
                continue;
            }
            names.add(name);
        }
        return build(names);
    }

    /** NPC 对象 schema（解析校验 + OrderedProperties 顺序）。 */
    public static List<FieldSpec> visibleNpcModernV1() {
        return VISIBLE_NPC_MODERN_V1;
    }

    /** 场景对象 schema（99 基础 + 玩家扩展 + BufferInfo 槽，官方索引/线型）。 */
    public static List<FieldSpec> sceneVisiblePropertyFields() {
        return SCENE_FIELDS;
    }

    /** Go playerProgressFields（37 项内功/面板名，iota 171 起的语义序）。 */
    public static List<FieldSpec> playerProgressFields() {
        return build(List.of(
                "CurNeiGong", "Faculty", "FacultyState", "FacultyStyle", "FacultyName",
                "CurFillValue", "TotalFillValue", "CurLevel", "FillSpeed", "PowerValue",
                "MaxPowerValue", "SP", "Str", "Sta", "Dex", "Ing", "Spi", "MeleePower",
                "MagicPower", "PhyHit", "MagicHit", "Dodge", "MaxParry", "PhyDef", "StiDef",
                "NegDef", "MasDef", "JujDef", "MinMeleeDamage", "MaxMeleeDamage", "MaxHPAdd",
                "MaxMPAdd", "MaxLevel", "StaticData", "ItemType", "MaxParryAdd",
                "MinMagicDefAdd"));
    }

    public static int bufferInfoPropertyIndex(int slot) {
        if (slot == 0 || slot > BUFFER_INFO_SLOT_COUNT) {
            throw new IllegalArgumentException("invalid BufferInfo slot " + slot);
        }
        return PropertyTable.idx("BufferInfo" + slot);
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
