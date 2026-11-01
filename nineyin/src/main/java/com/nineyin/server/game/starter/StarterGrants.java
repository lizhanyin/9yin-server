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

    /** Go grantStarterProgress 闭包体（starter 回退）：View43 → View40 → 两组快捷栏 → 收尾 vital。 */
    public static void grantStarterProgress(Connection connection, PlayerActor player) {
        grantStarterProgress(connection, player, null, null);
    }

    /**
     * 三期 DB 模式：View43（role_faculty 快照恢复后的真书）→ View40（role_skills 十属性行，
     * cap=行数）→ 快捷栏（role_shortcuts 全行）→ 收尾 vital；任一 DB 列表为空回退 starter 表。
     */
    public static void grantStarterProgress(Connection connection, PlayerActor player,
                                            java.util.List<RoleInventoryStore.Skill> dbSkills,
                                            java.util.List<RoleInventoryStore.Shortcut> dbShortcuts) {
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
        if (dbShortcuts != null && !dbShortcuts.isEmpty()) {
            for (RoleInventoryStore.Shortcut shortcut : dbShortcuts) {
                connection.writeFrame(shortcutAddFrame(new PlayerActor.PlayerShortcut(
                        shortcut.shortcutIndex(), shortcut.kind(), shortcut.id())));
            }
        } else {
            grantHeartBuddhaPalmShortcuts(connection, player);
            grantTaiJiQuanGuPuShortcuts(connection, player);
        }
        connection.writeFrame(player.vitalUpdate());
    }

    /** DB 技能行（bak roleSkillViewAdd 十属性：等级 1/上限 1/修为 0/WuXing 0/前摇 1000/范围 50）。 */
    static byte[] dbSkillViewAdd(RoleInventoryStore.Skill skill, int slot) {
        List<SceneMessages.ViewProperty> props = new ArrayList<>(10);
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

    /** Go shortcutAddFrame：shortcut_rec 完整三列行。 */
    static byte[] shortcutAddFrame(PlayerActor.PlayerShortcut shortcut) {
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
