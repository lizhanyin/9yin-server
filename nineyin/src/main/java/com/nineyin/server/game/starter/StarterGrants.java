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

    /**
     * 参考服 pushSwitchSync 的功能开关白名单（exe main.switchSyncIDs @0x140a2e6a0
     * 与 61653 抓包帧 #1815 逐值互验，203 项）：登录 ClientReady 收尾一次性
     * S2C 0x1E 414 ("sync", id×203) 批量开启客户端引擎 SwitchManager。
     * 2919 = ST_FUNCTION_NEW_C_FORM 在列——人物/装备栏"新旧界面切换"按钮的
     * 显示门槛（lua is_show_new_type = CheckSwitchEnable(2919) && 本地
     * c_form_style==0；switch_define.lua）。1223 不在此表，走 0x27 reset 单开。
     */
    public static final int[] SWITCH_SYNC_IDS = {
            0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 13,
            15, 21, 24, 26, 27, 30, 32, 39, 40, 41, 42, 46,
            47, 50, 52, 53, 70, 76, 77, 78, 92, 93, 94, 95,
            96, 97, 98, 102, 104, 106, 107, 109, 112, 113, 144, 148,
            151, 158, 161, 166, 167, 169, 171, 185, 210, 219, 232, 233,
            234, 235, 247, 249, 252, 253, 254, 256, 272, 304, 347, 362,
            363, 385, 387, 500, 501, 502, 503, 504, 505, 509, 520, 613,
            625, 637, 659, 661, 689, 690, 691, 692, 696, 758, 775, 779,
            783, 784, 789, 790, 791, 792, 793, 794, 818, 899, 930, 951,
            970, 976, 980, 981, 986, 997, 1000, 1001, 1002, 1003, 1004, 1005,
            1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1017, 1018, 1020,
            1022, 1027, 1031, 1038, 1045, 1046, 1082, 1086, 1090, 1102, 1112, 1116,
            1161, 1178, 1186, 1193, 1194, 1195, 1196, 1204, 1258, 1262, 1265, 1269,
            1271, 1293, 1301, 1325, 1327, 1344, 1345, 1347, 1348, 1351, 1352, 1353,
            1363, 1368, 1373, 1374, 1379, 1387, 1388, 1389, 2000, 2001, 2002, 2003,
            2004, 2019, 2090, 2091, 2092, 2111, 2146, 2861, 2873, 2880, 2881, 2895,
            2901, 2903, 2906, 2908, 2909, 2911, 2917, 2918, 2919, 2923, 2925,
    };

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
        grantStarterProgress(connection, player, dbSkills, starterShortcuts, java.util.Map.of());
    }

    /**
     * 带等级版：DB 行 Level 取 levels 档值（learn_all_taolu 满级落档
     * data/武学等级.json 后重登保持；starter 回退集行维持内联 level=1）。
     */
    public static void grantStarterProgress(Connection connection, PlayerActor player,
                                            java.util.List<RoleInventoryStore.Skill> dbSkills,
                                            boolean starterShortcuts,
                                            java.util.Map<String, Integer> levels) {
        for (byte[] frame : player.progress().viewFrames()) {
            connection.writeFrame(frame);
        }
        if (dbSkills != null && !dbSkills.isEmpty()) {
            connection.writeFrame(SceneMessages.createView(
                    new SceneMessages.ViewSpec(StarterSkills.VIEWPORT_SKILL, dbSkills.size())));
            for (int index = 0; index < dbSkills.size(); index++) {
                String skillId = dbSkills.get(index).skillId();
                connection.writeFrame(dbSkillViewAdd(dbSkills.get(index), index + 1,
                        Math.max(1, levels.getOrDefault(skillId, 1))));
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

    /**
     * GM forget_all_taolu 的 View40 重开下半段（0x16 整视图删除由调用方先发）：
     * 0x15 容器 + 逐行 0x18（等级取 levels 档值）。starter 回退集由调用方走
     * {@link StarterSkills#starterSkillViewFrames()}。
     */
    public static void grantWuxueSkillView(Connection connection,
                                           java.util.List<RoleInventoryStore.Skill> dbSkills,
                                           java.util.Map<String, Integer> levels) {
        connection.writeFrame(SceneMessages.createView(
                new SceneMessages.ViewSpec(StarterSkills.VIEWPORT_SKILL, dbSkills.size())));
        for (int index = 0; index < dbSkills.size(); index++) {
            String skillId = dbSkills.get(index).skillId();
            connection.writeFrame(dbSkillViewAdd(dbSkills.get(index), index + 1,
                    Math.max(1, levels.getOrDefault(skillId, 1))));
        }
    }

    /**
     * DB 技能行（官方抓包对齐 2026-11-05，参考服 207 行 View40 对拍：TargetShapePara2
     * 154 次真实射程 3~25m、HitShapePara2 80 次、HitShapePara1 24 次、TargetShapePara1
     * 从不发、VarPropNo/CanUse 每行必发、AConsume 族与 UseLimit 仅 &gt;0 稀疏发）。
     * 旧 bak 十属性的 PrepareTime=1000 与 TargetShapePara2=50 硬编码即真机
     * 「技能最大射程全是 50」根因，已删；UseLimit 恒 9（xhmdl01 官方=1）同批纠正。
     */
    static byte[] dbSkillViewAdd(RoleInventoryStore.Skill skill, int slot) {
        return dbSkillViewAdd(skill, slot, 1);
    }

    /**
     * 带等级 DB 技能行（learn_all_taolu 满级/登录档值合并）：Level=档级，
     * MaxLevel 与等级对齐（满级 N/N 自洽显示；level=1 时保持 1/1——官方对拍
     * 字节不变），修为显示 CurFill=TotalFill=totalFill（仅升级行发，
     * StarterSkills.totalFillDisplay 口径）；VarPropNo/消耗族按级取 varprop。
     */
    static byte[] dbSkillViewAdd(RoleInventoryStore.Skill skill, int slot, int level) {
        int effectiveLevel = Math.max(1, level);
        int totalFill = effectiveLevel > 1 ? StarterSkills.totalFillDisplay(skill.skillId()) : 0;
        List<SceneMessages.ViewProperty> props = new ArrayList<>(28);
        Props.addView(props, "ConfigID", PropertyValues.Value.ofString(skill.skillId()));
        Props.addView(props, "StaticData", PropertyValues.Value.ofInt32(skill.staticData()));
        Props.addView(props, "ItemType",
                PropertyValues.Value.ofInt32(skill.itemType() == 0 ? 1000 : skill.itemType()));
        Props.addView(props, "Level", PropertyValues.Value.ofInt32(effectiveLevel));
        Props.addView(props, "MaxLevel", PropertyValues.Value.ofInt32(effectiveLevel));
        Props.addView(props, "CurFillValue", PropertyValues.Value.ofInt32(totalFill));
        Props.addView(props, "TotalFillValue", PropertyValues.Value.ofInt32(totalFill));
        // 官方行补集（CombatSkillCatalog.skillRowData，与 StarterSkills 同源）：
        // VarPropNo 每行必发（客户端 varpkg 同值）；AConsume*/TargetShapePara2/
        // HitShapePara1/2/UseLimit 仅 >0 才发（官方稀疏风格——TargetShapePara2 即
        // 技能最大射程，客户端 tooltip/施法限距都读它；查不到链路整组缺省不发）。
        com.nineyin.server.world.fight.CombatSkillCatalog.SkillRowData rowData =
                com.nineyin.server.world.fight.CombatSkillCatalog.load()
                        .skillRowData(skill.skillId(), effectiveLevel);
        Props.addView(props, "VarPropNo", PropertyValues.Value.ofInt32(
                rowData != null ? rowData.varPropNo() : 0));
        if (rowData != null && rowData.useLimit() > 0) {
            Props.addView(props, "UseLimit", PropertyValues.Value.ofInt32(rowData.useLimit()));
        }
        if (rowData != null && rowData.aConsumeHP() > 0) {
            Props.addView(props, "AConsumeHP", PropertyValues.Value.ofInt32(rowData.aConsumeHP()));
        }
        if (rowData != null && rowData.aConsumeMP() > 0) {
            Props.addView(props, "AConsumeMP", PropertyValues.Value.ofInt32(rowData.aConsumeMP()));
        }
        if (rowData != null && rowData.aConsumeSP() > 0) {
            Props.addView(props, "AConsumeSP", PropertyValues.Value.ofInt32(rowData.aConsumeSP()));
        }
        if (rowData != null && rowData.aConsumeQGP() > 0) {
            Props.addView(props, "AConsumeQGP", PropertyValues.Value.ofInt32(rowData.aConsumeQGP()));
        }
        if (rowData != null && rowData.targetShapePara2() > 0) {
            Props.addView(props, "TargetShapePara2", PropertyValues.Value.ofFloat32(
                    rowData.targetShapePara2()));
        }
        if (rowData != null && rowData.hitShapePara1() > 0) {
            Props.addView(props, "HitShapePara1", PropertyValues.Value.ofFloat32(
                    rowData.hitShapePara1()));
        }
        if (rowData != null && rowData.hitShapePara2() > 0) {
            Props.addView(props, "HitShapePara2", PropertyValues.Value.ofFloat32(
                    rowData.hitShapePara2()));
        }
        Props.addView(props, "CanUse", PropertyValues.Value.ofByte(1));
        Props.addView(props, "Quality", PropertyValues.Value.ofByte(1));
        // WuXing 走 wuxuebaseinfo 目录缺省太极（发 0 会显示空/零）。
        int wuXing = com.nineyin.server.game.avatar.neigong.NeiGongCatalog.load()
                .wuXingOf(skill.skillId());
        Props.addView(props, "WuXing", PropertyValues.Value.ofInt32(
                wuXing != 0 ? wuXing : com.nineyin.server.game.avatar.neigong.NeiGongCatalog.WUXING_TAIJI));
        // CoolDownTime 显示值：skill 目录 cooldown 显式 >0 时作覆盖（SkillInitTable：
        // skill_new 优先/skill_init 兜底，官方两文件 cooldown 全 0），否则取
        // varprop 按级 CoolDownTime（官方 View40 行同源）。
        SkillInitTable.Row initRow = SkillInitTable.load().row(skill.skillId());
        long cdMs = initRow != null && initRow.coolDownMs() > 0 ? initRow.coolDownMs()
                : com.nineyin.server.world.fight.CombatSkillCatalog.load()
                        .personalCdMs(skill.skillId(), effectiveLevel);
        Props.addView(props, "CoolDownTime", PropertyValues.Value.ofInt32((int) cdMs));
        Props.addView(props, "HPHealMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "MPHealMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "PhyDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "StiDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "JujDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "NegDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "MasDamageMul", PropertyValues.Value.ofFloat32(1.0f));
        Props.addView(props, "Flow", PropertyValues.Value.ofInt32(5));
        // LeadTime/LeadSepTime：官方行同样从不发（bak 残留，删）。
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

    /** 参考服 pushSwitchSync：0x1E 414("sync", id×203) 批量开 SwitchManager；连接级一次。 */
    public static void pushSwitchSync(Connection connection) {
        connection.writeFrame(switchSyncFrame());
    }

    public static byte[] switchSyncFrame() {
        CustomMessages.Value[] values = new CustomMessages.Value[SWITCH_SYNC_IDS.length + 1];
        values[0] = CustomMessages.Value.ofString("sync");
        for (int i = 0; i < SWITCH_SYNC_IDS.length; i++) {
            values[i + 1] = CustomMessages.Value.ofInt32(SWITCH_SYNC_IDS[i]);
        }
        return CustomMessages.customIntMessage(SERVER_SWITCH_CONTROL_MESSAGE, values);
    }

    private StarterGrants() {
    }
}
