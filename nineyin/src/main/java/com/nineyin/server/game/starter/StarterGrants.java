package com.nineyin.server.game.starter;

import java.util.ArrayList;
import java.util.List;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.*;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;

/**
 * ClientReady 后 starter 链编排，帧序照抄 Go main.go 显式 0x09 分支：
 * grantStarterQingGong（含功能解锁）→ grantSitcrossShortcut →
 * grantStarterProgress（View43/View40/战斗快捷栏/收尾 vital）→ 0x27 技能动作开关。
 */
public final class StarterGrants {

    // ---- 打坐快捷栏（Ctrl+0，避开两个 starter 战斗槽页） ----
    public static final int SITCROSS_SHORTCUT_INDEX = 9;
    static final String SITCROSS_SHORTCUT_KIND = "skill";
    static final String SITCROSS_SHORTCUT_ID = "zs_default_01";

    /** grid_shortcut_1 每页 10 格：第二页从 10 起；grid_shortcut_2 是上方 Ctrl 条（200 起）。 */
    static final int HEART_BUDDHA_PALM_SHORTCUT_BASE_INDEX = 10;
    static final int TAI_JI_QUAN_GU_PU_SHORTCUT_BASE_INDEX = 20;

    static final List<String> HEART_BUDDHA_PALM_SHORTCUT_IDS = List.of(
            "CS_jh_xfz01", "CS_jh_xfz02", "CS_jh_xfz03", "CS_jh_xfz04",
            "CS_jh_xfz05", "CS_jh_xfz06", "CS_jh_xfz07");

    static final List<String> TAI_JI_QUAN_GU_PU_SHORTCUT_IDS = List.of(
            "CS_wd_tjq01", "CS_wd_tjq02", "CS_wd_tjq03", "CS_wd_tjq04",
            "CS_wd_tjq05", "CS_wd_tjq06", "CS_wd_tjq07", "CS_wd_tjq08");

    /** 0x27 技能动作开关：注册表槽 843 → 消息 ID (843-15)/2=414；SwitchManager 能力 1223。 */
    public static final int SERVER_SWITCH_CONTROL_MESSAGE = 414;
    public static final int SKILL_ACTION_INPUT_SWITCH = 1223;

    /** Go grantStarterQingGong：轻功双真源 + 功能解锁（一次下发 18+1+70+15+1594 帧）。 */
    public static void grantStarterQingGong(Connection connection) {
        for (byte[] frame : StarterQingGong.qingGongFrames(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID)) {
            connection.writeFrame(frame);
        }
        for (byte[] frame : StarterQingGong.functionUnlockFrames(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID)) {
            connection.writeFrame(frame);
        }
    }

    /** Go grantSitcrossShortcut：索引 9 已占用则不发帧（幂等）。返回是否新下发。 */
    public static boolean grantSitcrossShortcut(Connection connection, PlayerActor player) {
        if (player == null) {
            return false;
        }
        synchronized (player) {
            if (player.hasShortcutIndex(SITCROSS_SHORTCUT_INDEX)) {
                return false;
            }
            player.setShortcut(SITCROSS_SHORTCUT_INDEX, SITCROSS_SHORTCUT_KIND, SITCROSS_SHORTCUT_ID);
        }
        connection.writeFrame(shortcutAddFrame(
                new PlayerActor.PlayerShortcut(SITCROSS_SHORTCUT_INDEX, SITCROSS_SHORTCUT_KIND, SITCROSS_SHORTCUT_ID)));
        return true;
    }

    /** starter 回退便捷重载：View43 → View40 → 两组快捷栏补授 → 收尾 vital。 */
    public static void grantStarterProgress(Connection connection, PlayerActor player) {
        grantStarterProgress(connection, player, null, true);
    }

    /**
     * 三期 DB 模式：View43（role_faculty 快照恢复后的真书）→ View40（role_skills 十属性行，
     * cap=行数）→ 收尾 vital。快捷栏不在本链下发：ExitScene 后客户端记录表清空，由
     * {@link #grantShortcutRows} 每场景代按内存快照全量重放（DB 基线登录时已入内存）。
     * starterShortcuts=true 时补授 starter 两套（unchanged 跳过，与登录首授同源）。
     */
    public static void grantStarterProgress(Connection connection, PlayerActor player,
                                            java.util.List<RoleInventoryStore.Skill> dbSkills,
                                            boolean starterShortcuts) {
        for (byte[] frame : player.progress().viewFrames()) {
            connection.writeFrame(frame);
        }
        if (dbSkills != null && !dbSkills.isEmpty()) {
            connection.writeFrame(SceneMessages.createView(
                    new SceneMessages.ViewSpec(StarterSkills.VIEWPORT_SKILL, dbSkills.size())));
            for (int index = 0; index < dbSkills.size(); index++) {
                connection.writeFrame(dbSkillViewAdd(dbSkills.get(index), index + 1));
            }
        } else {
            for (byte[] frame : StarterSkills.starterSkillViewFrames()) {
                connection.writeFrame(frame);
            }
        }
        if (starterShortcuts) {
            grantHeartBuddhaPalmShortcuts(connection, player);
            grantTaiJiQuanGuPuShortcuts(connection, player);
        }
        connection.writeFrame(player.vitalUpdate());
    }

    /** DB 技能行（bak roleSkillViewAdd 十属性 + 四期零值特效族/目录 CoolDownTime）。 */
    static byte[] dbSkillViewAdd(RoleInventoryStore.Skill skill, int slot) {
        List<SceneMessages.ViewProperty> props = new ArrayList<>(24);
        Props.addView(props, "ConfigID", PropertyValues.Value.ofString(skill.skillId()));
        Props.addView(props, "StaticData", PropertyValues.Value.ofInt32(skill.staticData()));
        Props.addView(props, "ItemType",
                PropertyValues.Value.ofInt32(skill.itemType() == 0 ? 1000 : skill.itemType()));
        Props.addView(props, "Level", PropertyValues.Value.ofInt32(1));
        Props.addView(props, "MaxLevel", PropertyValues.Value.ofInt32(1));
        Props.addView(props, "CurFillValue", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "TotalFillValue", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "WuXing", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "PrepareTime", PropertyValues.Value.ofInt32(1000));
        Props.addView(props, "TargetShapePara2", PropertyValues.Value.ofFloat32(50.0f));
        SkillInitTable.Row row = SkillInitTable.load().row(skill.skillId());
        // 属性族 bak 语义值（发 0 会显示空/零）：WuXing 走 wuxuebaseinfo 目录缺省太极。
        Props.addView(props, "UseLimit", PropertyValues.Value.ofInt32(9));
        Props.addView(props, "VarPropNo", PropertyValues.Value.ofInt32(0));
        Props.addView(props, "Quality", PropertyValues.Value.ofByte(1));
        int wuXing = com.nineyin.server.game.avatar.neigong.NeiGongCatalog.load()
                .wuXingOf(skill.skillId());
        Props.addView(props, "WuXing", PropertyValues.Value.ofInt32(
                wuXing != 0 ? wuXing : com.nineyin.server.game.avatar.neigong.NeiGongCatalog.WUXING_TAIJI));
        // CoolDownTime 显示值：skill_init.ini cooldown 显式 >0 时作覆盖，否则取
        // varprop 按级 CoolDownTime（官方 View40 行同源；官方 skill_init cooldown 全 0；
        // dbSkillViewAdd 等级恒 1）。
        long cdMs = row != null && row.coolDownMs() > 0 ? row.coolDownMs()
                : com.nineyin.server.world.fight.CombatSkillCatalog.load()
                        .personalCdMs(skill.skillId(), 1);
        Props.addView(props, "CoolDownTime", PropertyValues.Value.ofInt32((int) cdMs));
        Props.addView(props, "HPHealMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "MPHealMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "PhyDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "StiDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "JujDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "NegDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "MasDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "Flow", PropertyValues.Value.ofInt32(5));
        Props.addView(props, "LeadTime", PropertyValues.Value.ofInt32(10000));
        Props.addView(props, "LeadSepTime", PropertyValues.Value.ofInt32(1500));
        return SceneMessages.viewAdd(StarterSkills.VIEWPORT_SKILL, slot, props);
    }

    /** Go grantHeartBuddhaPalmShortcuts：数字条第二页 1..7 格填满心佛掌。 */
    static int grantHeartBuddhaPalmShortcuts(Connection connection, PlayerActor player) {
        return grantShortcutSet(connection, player,
                HEART_BUDDHA_PALM_SHORTCUT_BASE_INDEX, HEART_BUDDHA_PALM_SHORTCUT_IDS);
    }

    /** Go grantTaiJiQuanGuPuShortcuts：第三页 1..8 格填满太极拳(古谱)。 */
    static int grantTaiJiQuanGuPuShortcuts(Connection connection, PlayerActor player) {
        return grantShortcutSet(connection, player,
                TAI_JI_QUAN_GU_PU_SHORTCUT_BASE_INDEX, TAI_JI_QUAN_GU_PU_SHORTCUT_IDS);
    }

    private static int grantShortcutSet(Connection connection, PlayerActor player,
                                        int baseIndex, List<String> ids) {
        if (player == null) {
            return 0;
        }
        int granted = 0;
        for (int offset = 0; offset < ids.size(); offset++) {
            int index = baseIndex + offset;
            PlayerActor.ShortcutUpdate update;
            synchronized (player) {
                update = player.setShortcut(index, "skill", ids.get(offset));
            }
            if (update.unchanged()) {
                continue;
            }
            if (update.existed()) {
                connection.writeFrame(RecordMessages.recordDelRow(
                        SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                        RecordMessages.RECORD_SHORTCUT, update.oldRow()));
            }
            connection.writeFrame(shortcutAddFrame(
                    new PlayerActor.PlayerShortcut(index, "skill", ids.get(offset))));
            granted++;
        }
        return granted;
    }

    /**
     * Go grantShortcutRows：按内存快照全量重放 0x11 行。ExitScene 销毁原生玩家对象时
     * 清掉其挂接记录行（连接级 schema 存活），每个场景代必须整表重发；内存是唯一
     * 事实源（DB 基线登录装载 + 200/201 增量），行序与 0x12 删行号同口径。
     */
    public static void grantShortcutRows(Connection connection, PlayerActor player) {
        if (player == null) {
            return;
        }
        for (PlayerActor.PlayerShortcut shortcut : player.shortcutSnapshot()) {
            connection.writeFrame(shortcutAddFrame(shortcut));
        }
    }

    /** Go shortcutAddFrame：shortcut_rec 完整三列行。 */
    public static byte[] shortcutAddFrame(PlayerActor.PlayerShortcut shortcut) {
        if (shortcut.index() < 0) {
            throw new IllegalArgumentException("shortcut index " + shortcut.index() + " is negative");
        }
        return RecordMessages.recordAddCells(SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                RecordMessages.RECORD_SHORTCUT,
                RecordMessages.Cell.ofInt(shortcut.index()),
                RecordMessages.Cell.ofString(shortcut.kind()),
                RecordMessages.Cell.ofString(shortcut.id()));
    }

    /** Go enableSkillActionSwitch：现代 0x27 壳消息 414("reset",1223,1)；重复 0x09 也会重发。 */
    public static void enableSkillActionSwitch(Connection connection) {
        connection.writeFrame(skillActionSwitchFrame());
    }

    public static byte[] skillActionSwitchFrame() {
        return CustomMessages.modernCustomIntMessage(SERVER_SWITCH_CONTROL_MESSAGE,
                CustomMessages.Value.ofString("reset"),
                CustomMessages.Value.ofInt32(SKILL_ACTION_INPUT_SWITCH),
                CustomMessages.Value.ofInt32(1));
    }

    private StarterGrants() {
    }
}
