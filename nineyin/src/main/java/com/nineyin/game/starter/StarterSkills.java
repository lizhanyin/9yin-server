package com.nineyin.game.starter;

import java.util.ArrayList;
import java.util.List;

import com.nineyin.protocol.Props;
import com.nineyin.protocol.PropertyValues;
import com.nineyin.protocol.SceneMessages;

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

    /** 当前 starter 已学集：打坐前置 + 花神七式 + 心佛掌七招 + 太极拳(古谱)八式。 */
    static final List<LearnedSkill> STARTER_SKILL_VIEWS = List.of(
            new LearnedSkill("zs_default_01", 22, 1000, 1, 1, 0, 0, 0),
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
            new LearnedSkill("CS_wd_tjq08", 2107, 1000, 1, 19, 0, 0, (float) (757.5 + 58 * 1000.0 / 30)));

    /** Go 常量语义：N*1000.0/30 按双精度求值再转 float32（float 逐位一致）。 */
    private static float pauseFrames(int frames) {
        return (float) (frames * 1000.0 / 30);
    }

    /** Go starterSkillViewFrames：View 40 容器 + 每技能一行。 */
    public static List<byte[]> starterSkillViewFrames() {
        List<byte[]> frames = new ArrayList<>(STARTER_SKILL_VIEWS.size() + 1);
        frames.add(SceneMessages.createView(
                new SceneMessages.ViewSpec(VIEWPORT_SKILL, STARTER_SKILL_VIEWS.size())));
        for (int index = 0; index < STARTER_SKILL_VIEWS.size(); index++) {
            LearnedSkill skill = STARTER_SKILL_VIEWS.get(index);
            List<SceneMessages.ViewProperty> properties = new ArrayList<>(9);
            Props.addView(properties, "ConfigID", PropertyValues.Value.ofString(skill.configId()));
            Props.addView(properties, "StaticData", PropertyValues.Value.ofInt32(skill.staticData()));
            Props.addView(properties, "ItemType", PropertyValues.Value.ofInt32(skill.itemType()));
            Props.addView(properties, "CurLevel", PropertyValues.Value.ofInt32(skill.level()));
            Props.addView(properties, "MaxLevel", PropertyValues.Value.ofInt32(skill.maxLevel()));
            Props.addView(properties, "CurFillValue", PropertyValues.Value.ofInt32(skill.fill()));
            Props.addView(properties, "TotalFillValue", PropertyValues.Value.ofInt32(skill.total()));
            Props.addView(properties, "PauseTime", PropertyValues.Value.ofFloat32(skill.pauseTime()));
            Props.addView(properties, "CanUse", PropertyValues.Value.ofByte(1));
            frames.add(SceneMessages.viewAdd(VIEWPORT_SKILL, index + 1, properties));
        }
        return frames;
    }

    private StarterSkills() {
    }
}
