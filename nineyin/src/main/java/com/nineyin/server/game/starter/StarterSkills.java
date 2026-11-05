package com.nineyin.server.game.starter;

import java.util.ArrayList;
import java.util.List;

import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.world.db.RoleInventoryStore;

/**
 * 已学技能容器（View 40）starter 集，逐行移植 Go skill_view.go。
 * StaticData/ItemType 是 GoodsGrid 解析图标与技能呈现的必需字段。
 */
public final class StarterSkills {

    public static final int VIEWPORT_SKILL = 40;

    /** Go learnedSkillView：ConfigID + 静态数据 + 档位/等级/修为/暂停帧。 */
    record LearnedSkill(String configId, int staticData, int itemType, int level,
                        int maxLevel, int fill, int total, float pauseTime) {
    }

    /**
     * 默认分类「基础武学 → 三脚猫招式」五技能（bug.txt：官服全角色默认已学）：
     * 剑普攻 / 飞刀普攻 / 打坐调息 / 自断经脉 / 骑术精通。普攻族固定发剑+飞刀两件
     * （官方观测同款，不随门派变——其余武器普攻 zs_normal_ 与 hw_normal_ 族官方未授）。
     * staticData/itemType 与 game_skills 目录一致（zs_normal_02=15 / hw_normal_fd=626 /
     * zs_default_01=22 / zs_suicide=55 / riding_start=21515）。
     */
    static final List<LearnedSkill> DEFAULT_BASIC_SKILLS = List.of(
            new LearnedSkill("zs_normal_02", 15, 1000, 1, 1, 0, 0, 0),
            new LearnedSkill("hw_normal_fd", 626, 1000, 1, 1, 0, 0, 0),
            new LearnedSkill("zs_default_01", 22, 1000, 1, 1, 0, 0, 0),
            new LearnedSkill("zs_suicide", 55, 1000, 1, 1, 0, 0, 0),
            new LearnedSkill("riding_start", 21515, 1000, 1, 1, 0, 0, 0));

    /** 当前 starter 已学集：基础武学五件套 + 花神七式 + 心佛掌七招 + 太极拳(古谱)八式。 */
    static final List<LearnedSkill> STARTER_SKILL_VIEWS = starterSkillViews();

    private static List<LearnedSkill> starterSkillViews() {
        List<LearnedSkill> views = new ArrayList<>(DEFAULT_BASIC_SKILLS);
        views.addAll(List.of(
            new LearnedSkill("CS_yhwq_hsqs01", 12636, 1000, 1, 11, 0, 156550, pauseFrames(43)),
            new LearnedSkill("CS_yhwq_hsqs02", 12637, 1001, 1, 11, 0, 156550, pauseFrames(65)),
            new LearnedSkill("CS_yhwq_hsqs03", 12638, 1000, 1, 11, 0, 156550, pauseFrames(30)),
            new LearnedSkill("CS_yhwq_hsqs04", 12639, 1000, 1, 9, 0, 156550, pauseFrames(62)),
            new LearnedSkill("CS_yhwq_hsqs05", 12640, 1000, 1, 4, 0, 156550, pauseFrames(113)),
            new LearnedSkill("CS_yhwq_hsqs06", 12641, 1000, 1, 11, 0, 156550, pauseFrames(35)),
            new LearnedSkill("CS_yhwq_hsqs07", 12642, 1001, 1, 11, 0, 156550, pauseFrames(147)),
            // 心佛掌：03/04/07 单行 varprop，显示上限一级。
            new LearnedSkill("CS_jh_xfz01", 13189, 1001, 1, 12, 0, 0, 0),
            new LearnedSkill("CS_jh_xfz02", 13190, 1000, 1, 12, 0, 0, 0),
            new LearnedSkill("CS_jh_xfz03", 13205, 1000, 1, 1, 0, 0, 0),
            new LearnedSkill("CS_jh_xfz04", 13206, 1000, 1, 1, 0, 0, 0),
            new LearnedSkill("CS_jh_xfz05", 13207, 1000, 1, 12, 0, 0, 0),
            new LearnedSkill("CS_jh_xfz06", 13208, 1000, 1, 12, 0, 0, 0),
            new LearnedSkill("CS_jh_xfz07", 13203, 1000, 1, 1, 0, 0, 0),
            // 太极拳(古谱)：MaxLevel 对齐各静态记录 varprop 区间，PauseTime 为客户端动作毫秒。
            new LearnedSkill("CS_wd_tjq01", 4057, 1000, 1, 19, 0, 0, pauseFrames(52)),
            new LearnedSkill("CS_wd_tjq02", 4058, 1001, 1, 19, 0, 0, pauseFrames(50)),
            new LearnedSkill("CS_wd_tjq03", 4238, 1000, 1, 19, 0, 0, pauseFrames(35)),
            new LearnedSkill("CS_wd_tjq04", 4045, 1000, 1, 19, 0, 0, pauseFrames(45)),
            new LearnedSkill("CS_wd_tjq05", 5451, 1000, 1, 19, 0, 0, pauseFrames(29)),
            new LearnedSkill("CS_wd_tjq06", 5452, 1000, 1, 19, 0, 0, pauseFrames(61)),
            new LearnedSkill("CS_wd_tjq07", 5453, 1000, 1, 3, 0, 0, pauseFrames(102)),
            new LearnedSkill("CS_wd_tjq08", 2107, 1000, 1, 19, 0, 0, (float) (757.5 + 58 * 1000.0 / 30))));
        return List.copyOf(views);
    }

    /**
     * 登录全量下发补集（bug.txt 基础武学项）：DB 已学行缺默认五技能时按默认序补在
     * 队首——内存合成不落库，幂等可重放；空表原样返回（starter 回退集本就含五件套）。
     * staticData/itemType 取 DEFAULT_BASIC_SKILLS 内联值（与 game_skills 目录一致，
     * 客户端 GoodsGrid 图标解析不受影响）。
     */
    public static List<RoleInventoryStore.Skill> withDefaultBasics(
            List<RoleInventoryStore.Skill> dbSkills) {
        if (dbSkills == null || dbSkills.isEmpty()) {
            return dbSkills;
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        for (RoleInventoryStore.Skill skill : dbSkills) {
            present.add(skill.skillId());
        }
        List<RoleInventoryStore.Skill> missing = null;
        for (LearnedSkill basic : DEFAULT_BASIC_SKILLS) {
            if (present.contains(basic.configId())) {
                continue;
            }
            if (missing == null) {
                missing = new ArrayList<>(DEFAULT_BASIC_SKILLS.size());
            }
            missing.add(new RoleInventoryStore.Skill(
                    basic.configId(), "wuxue", basic.staticData(), basic.itemType()));
        }
        if (missing == null) {
            return dbSkills;
        }
        List<RoleInventoryStore.Skill> merged = new ArrayList<>(missing.size() + dbSkills.size());
        merged.addAll(missing);
        merged.addAll(dbSkills);
        return merged;
    }

    /** Go 常量语义：N*1000.0/30 按双精度求值再转 float32（float 逐位一致）。 */
    private static float pauseFrames(int frames) {
        return (float) (frames * 1000.0 / 30);
    }

    /** 已学表（msg211 校验用，无 DB 行时回退 starter 集；等级恒 1）。 */
    public static java.util.Map<String, Integer> starterLearnedLevels() {
        java.util.Map<String, Integer> learned = new java.util.LinkedHashMap<>();
        for (LearnedSkill skill : STARTER_SKILL_VIEWS) {
            learned.put(skill.configId(), 1);
        }
        return learned;
    }

    /** starter 已学 ID 有序表（View40 行号表与下发顺序同构）。 */
    public static java.util.List<String> starterSkillIds() {
        java.util.List<String> ids = new java.util.ArrayList<>(STARTER_SKILL_VIEWS.size());
        for (LearnedSkill skill : STARTER_SKILL_VIEWS) {
            ids.add(skill.configId());
        }
        return ids;
    }

    /** 五件套基础武学（forget_all_taolu 的 DB 模式基线集；与登录 withDefaultBasics 补齐语义同源）。 */
    public static java.util.List<RoleInventoryStore.Skill> defaultBasicSkills() {
        java.util.List<RoleInventoryStore.Skill> basics =
                new java.util.ArrayList<>(DEFAULT_BASIC_SKILLS.size());
        for (LearnedSkill basic : DEFAULT_BASIC_SKILLS) {
            basics.add(new RoleInventoryStore.Skill(
                    basic.configId(), "wuxue", basic.staticData(), basic.itemType()));
        }
        return basics;
    }

    /** 修炼总量显示值（View40 行 TotalFillValue）：skill_init 目录优先，starter 集回退内联 total；其余 0。 */
    public static int totalFillDisplay(String configId) {
        SkillInitTable.Row row = SkillInitTable.load().row(configId);
        if (row != null && row.totalFill() > 0) {
            return row.totalFill();
        }
        for (LearnedSkill skill : STARTER_SKILL_VIEWS) {
            if (skill.configId().equals(configId)) {
                return skill.total();
            }
        }
        return 0;
    }

    /** Go starterSkillViewFrames：View 40 容器 + 每技能一行（四期：skill_init 目录叠加 + 零值特效族）。 */
    public static List<byte[]> starterSkillViewFrames() {
        List<byte[]> frames = new ArrayList<>(STARTER_SKILL_VIEWS.size() + 1);
        frames.add(SceneMessages.createView(
                new SceneMessages.ViewSpec(VIEWPORT_SKILL, STARTER_SKILL_VIEWS.size())));
        for (int index = 0; index < STARTER_SKILL_VIEWS.size(); index++) {
            LearnedSkill skill = STARTER_SKILL_VIEWS.get(index);
            frames.add(SceneMessages.viewAdd(VIEWPORT_SKILL, index + 1,
                    skillRowProperties(skill, SkillInitTable.load().row(skill.configId()))));
        }
        return frames;
    }

    /**
     * bak SkillPackets.skillRow 属性面（真机验证语义值）：内联值 + skill_init 目录行覆盖
     * （staticData/itemType/等级/上限/total/coolDown）+ 属性族固定值——
     * Quality=1/WuXing（wuxuebaseinfo 目录值，缺省 5 太极）/HP·MP HealMul 与
     * 五系 DamageMul=1.0f/Flow=5/LeadTime=10000/LeadSepTime=1500（发 0 会导致面板显示空/零）。
     * 目录补集（CombatSkillCatalog.skillRowData，bug.txt「怒招的消耗没有下发」）：
     * VarPropNo=匹配 varprop 行号、UseLimit=skill_new 真实值（回落 9）、消耗族
     * AConsumeHP/MP/SP/QGP 仅 &gt;0 才发（官方行稀疏风格，怒招由此带出 AConsumeSP →
     * 客户端显示怒气消耗并在怒气不足时灰图标）、Target/HitShapePara1/2（地面圈指示）。
     * 官方行的 Type(0) 属名在 0x09 表多处出现（玩家域解析到 idx181 语义不明）跳过。
     */
    static List<SceneMessages.ViewProperty> skillRowProperties(LearnedSkill skill,
                                                               SkillInitTable.Row row) {
        List<SceneMessages.ViewProperty> properties = new ArrayList<>(32);
        int level = row != null ? row.level() : skill.level();
        com.nineyin.server.world.fight.CombatSkillCatalog.SkillRowData rowData =
                com.nineyin.server.world.fight.CombatSkillCatalog.load().skillRowData(
                        skill.configId(), level);
        Props.addView(properties, "ConfigID", PropertyValues.Value.ofString(skill.configId()));
        Props.addView(properties, "StaticData", PropertyValues.Value.ofInt32(
                row != null ? row.staticData() : skill.staticData()));
        Props.addView(properties, "ItemType", PropertyValues.Value.ofInt32(
                row != null && row.itemType() != 0 ? row.itemType() : skill.itemType()));
        Props.addView(properties, "Level", PropertyValues.Value.ofByte(level));
        Props.addView(properties, "MaxLevel", PropertyValues.Value.ofInt32(
                row != null && row.maxLevel() > 0 ? row.maxLevel() : skill.maxLevel()));
        Props.addView(properties, "CurFillValue", PropertyValues.Value.ofInt32(skill.fill()));
        Props.addView(properties, "TotalFillValue", PropertyValues.Value.ofInt32(
                row != null && row.totalFill() > 0 ? row.totalFill() : skill.total()));
        Props.addView(properties, "PauseTime", PropertyValues.Value.ofFloat32(skill.pauseTime()));
        Props.addView(properties, "CanUse", PropertyValues.Value.ofByte(1));
        Props.addView(properties, "UseLimit", PropertyValues.Value.ofInt32(
                rowData != null && rowData.useLimit() > 0 ? rowData.useLimit() : 9));
        Props.addView(properties, "VarPropNo", PropertyValues.Value.ofInt32(
                rowData != null ? rowData.varPropNo() : 0));
        Props.addView(properties, "Quality", PropertyValues.Value.ofByte(1));
        Props.addView(properties, "WuXing", PropertyValues.Value.ofInt32(skillWuXing(skill.configId())));
        // CoolDownTime 显示值：skill_init.ini cooldown 显式 >0 时作覆盖，否则取
        // varprop 按级 CoolDownTime（官方 View40 行同源，客户端技能 CD 文本读它；
        // 官方 skill_init 的 cooldown 全 0 → 生产恒走 varprop）。
        long cdMs = row != null && row.coolDownMs() > 0 ? row.coolDownMs()
                : com.nineyin.server.world.fight.CombatSkillCatalog.load().personalCdMs(
                        skill.configId(), level);
        Props.addView(properties, "CoolDownTime", PropertyValues.Value.ofInt32((int) cdMs));
        if (rowData != null && rowData.aConsumeHP() > 0) {
            Props.addView(properties, "AConsumeHP", PropertyValues.Value.ofInt32(rowData.aConsumeHP()));
        }
        if (rowData != null && rowData.aConsumeMP() > 0) {
            Props.addView(properties, "AConsumeMP", PropertyValues.Value.ofInt32(rowData.aConsumeMP()));
        }
        if (rowData != null && rowData.aConsumeSP() > 0) {
            Props.addView(properties, "AConsumeSP", PropertyValues.Value.ofInt32(rowData.aConsumeSP()));
        }
        if (rowData != null && rowData.aConsumeQGP() > 0) {
            Props.addView(properties, "AConsumeQGP", PropertyValues.Value.ofInt32(rowData.aConsumeQGP()));
        }
        if (rowData != null) {
            // TargetShapePara1 官方从不发（参考服 207 行 0 次，2026-11-05 对拍），不发；
            // 其余形状/消耗族仅 >0 才发（官方稀疏风格）。
            if (rowData.targetShapePara2() > 0) {
                Props.addView(properties, "TargetShapePara2", PropertyValues.Value.ofFloat32(
                        rowData.targetShapePara2()));
            }
            if (rowData.hitShapePara1() > 0) {
                Props.addView(properties, "HitShapePara1", PropertyValues.Value.ofFloat32(
                        rowData.hitShapePara1()));
            }
            if (rowData.hitShapePara2() > 0) {
                Props.addView(properties, "HitShapePara2", PropertyValues.Value.ofFloat32(
                        rowData.hitShapePara2()));
            }
        }
        Props.addView(properties, "HPHealMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "MPHealMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "PhyDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "StiDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "JujDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "NegDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "MasDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(properties, "Flow", PropertyValues.Value.ofInt32(5));
        Props.addView(properties, "LeadTime", PropertyValues.Value.ofInt32(10000));
        Props.addView(properties, "LeadSepTime", PropertyValues.Value.ofInt32(1500));
        return properties;
    }

    /** 技能五行/属性：wuxuebaseinfo 目录值（刚1/柔2/阴3/阳4），缺省 5=太极（bak starter 同款）。 */
    private static int skillWuXing(String configId) {
        int value = com.nineyin.server.game.avatar.neigong.NeiGongCatalog.load().wuXingOf(configId);
        return value != 0 ? value : com.nineyin.server.game.avatar.neigong.NeiGongCatalog.WUXING_TAIJI;
    }

    private StarterSkills() {
    }
}
