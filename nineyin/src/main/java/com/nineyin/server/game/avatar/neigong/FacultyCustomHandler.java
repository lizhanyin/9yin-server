package com.nineyin.server.game.avatar.neigong;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.transport.Connection;

/**
 * 内修/演武自定义消息（215 装备内功 + 1006 全子命令），逐行移植 Go
 * handlePlayerProgressCustom（player_progress.go:634-843）的回复帧序。
 *
 * <p>msg191 = 0x1E 消息 191(sub, arg2)（客户端槽 397 → custom_faculty_msg →
 * faculty.on_msg）。返回 false=非本家族；抛 IllegalStateException=结构错误（断连）。
 */
public final class FacultyCustomHandler {

    public static final int USE_NEIGONG = 215;
    public static final int FACULTY_MSG = 1006;
    /** Go S2CFacultyMessage。 */
    public static final int S2C_FACULTY_MESSAGE = 191;

    // msg191 子命令（Go player_progress.go 调用点）；SUB_LEVEL_UP 供 LoginFlow GM 填满回执复用。
    static final int SUB_ACT_BEGIN = 22;
    public static final int SUB_LEVEL_UP = 14;
    static final int SUB_ACT_EXIT = 23;
    static final int SUB_ACT_PLAY = 24;

    private FacultyCustomHandler() {
    }

    /** Go encodeFacultyMessage：`1E 03 00 | 02 BF000000 | 02 sub | 02 arg2`。 */
    public static byte[] facultyMessage(int subcommand, int arg2) {
        return CustomMessages.customIntMessage(S2C_FACULTY_MESSAGE,
                CustomMessages.Value.ofInt32(subcommand),
                CustomMessages.Value.ofInt32(arg2));
    }

    public static boolean handles(int messageId) {
        return messageId == USE_NEIGONG || messageId == FACULTY_MSG;
    }

    public static void handle(Connection connection, PlayerActor player, CustomC2S.Message message) {
        int messageId = message.messageId();
        if (!handles(messageId)) {
            return;
        }
        if (player == null) {
            throw new IllegalStateException("character has not entered a scene");
        }
        List<CustomC2S.Value> values = message.values;
        Instant now = player.clock().instant();
        if (messageId == USE_NEIGONG) {
            equipNeiGong(connection, player, values);
            return;
        }
        if (values.size() < 2 || values.get(1).tag != 2) {
            throw new IllegalStateException("1006 without int subcommand");
        }
        int sub = values.get(1).exactInt32();
        switch (sub) {
            case 1 -> convertReady(connection, player, values, now);
            case 11 -> begin(connection, player, now, false);
            case 21 -> begin(connection, player, now, true);
            case 22, 12 -> exit(connection, player, now);
            case 23 -> actPay(connection, player, values);
            case 24 -> formRefresh(connection, player);
            case 31, 32 -> connection.writeFrame(player.vitalUpdate()); // 经脉：受理不伪造业务
            default -> throw new IllegalStateException("unknown 1006 subcommand " + sub);
        }
    }

    /** 215：V1=ConfigID；未学会/缺失 → 断连。回 [0x10]。 */
    private static void equipNeiGong(Connection connection, PlayerActor player,
                                     List<CustomC2S.Value> values) {
        String configId = values.size() > 1 ? values.get(1).textOrNull() : null;
        if (configId == null || configId.isEmpty()) {
            throw new IllegalStateException("215 missing ConfigID");
        }
        player.equipNeiGong(configId);
        connection.writeFrame(player.vitalUpdate());
    }

    /** 1006/1：选书（未知 id 静默零帧）。回 [0x18 书帧, 0x10]。 */
    private static void convertReady(Connection connection, PlayerActor player,
                                     List<CustomC2S.Value> values, Instant now) {
        String configId = values.size() > 2 ? values.get(2).textOrNull() : "";
        int slot = player.progress().facultyReady(configId, now);
        if (slot <= 0) {
            return;
        }
        connection.writeFrame(player.progress().bookFrame(slot));
        connection.writeFrame(player.vitalUpdate());
    }

    /** 1006/11 普通(LogicState 恒 0)；1006/21 演武(LogicState=108 + 191/22)。 */
    private static void begin(Connection connection, PlayerActor player, Instant now, boolean actStyle) {
        player.progress().facultyBegin(actStyle, now);
        player.setLogicStateOverride(actStyle ? PlayerProgress.LOGIC_STATE_FACULTY : 0);
        connection.writeFrame(player.vitalUpdate());
        if (actStyle) {
            connection.writeFrame(facultyMessage(SUB_ACT_BEGIN, 0));
        }
    }

    /** 1006/22 与 /12：停修。回 [0x10]。 */
    private static void exit(Connection connection, PlayerActor player, Instant now) {
        player.progress().facultyExit(now);
        player.setLogicStateOverride(0);
        connection.writeFrame(player.vitalUpdate());
    }

    /** 1006/23 演武卡：card∈[0,2] pay∈{1,2,4} 否则静默；陈旧请求也吞。 */
    private static void actPay(Connection connection, PlayerActor player,
                               List<CustomC2S.Value> values) {
        if (values.size() < 4) {
            throw new IllegalStateException("1006/23 without card+payment");
        }
        int card = values.get(2).exactInt32();
        int payment = values.get(3).exactInt32();
        if (card < 0 || card > 2 || (payment != 1 && payment != 2 && payment != 4)) {
            return;
        }
        int[] award = player.progress().awardActiveFaculty();
        if (award[1] == 1) {
            // 书帧先行：面板在表单打开期间需重读等级。
            connection.writeFrame(player.progress().bookFrame(award[0]));
        }
        connection.writeFrame(player.vitalUpdate());
        if (award[2] == 1) {
            connection.writeFrame(facultyMessage(SUB_LEVEL_UP, award[3]));
        }
        if (award[2] == 1 || award[3] == 1) {
            connection.writeFrame(facultyMessage(SUB_ACT_EXIT, 0));
        } else {
            connection.writeFrame(facultyMessage(SUB_ACT_PLAY, 0));
        }
    }

    /** 1006/24 表单打开刷新：FacultyName 置空再回填，强制客户端 binder 重跑。 */
    private static void formRefresh(Connection connection, PlayerActor player) {
        String facultyName = player.progress().facultyName;
        if (facultyName != null && !facultyName.isEmpty()) {
            List<PropertyValues.Indexed> properties = new ArrayList<>(1);
            Props.add(properties, "FacultyName", PropertyValues.Value.ofString(""));
            connection.writeFrame(SceneMessages.objectProperties(
                    SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, 0, properties));
        }
        connection.writeFrame(player.vitalUpdate());
    }
}
