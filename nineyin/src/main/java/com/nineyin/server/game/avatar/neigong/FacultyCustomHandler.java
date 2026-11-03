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
    /** 客户端 CLIENT_CUSTOMMSG_LIVEPOINT_INFO（client_custom_define.lua:145）：历练/潜能赎回。 */
    public static final int LIVEPOINT_INFO = 235;
    /** 潜能修为赎回价：N 黄金 → N×price 潜能修为转正式修为（-Dnineyin.livepoint.price 可配）。 */
    public static final int HOLD_FACULTY_PRICE =
            Integer.getInteger("nineyin.livepoint.price", 500);

    // custom 235 子命令（form_holdfaculty_buy.lua:4-8）。
    static final int LIVEPOINT_SUB_GETINFO = 1;
    static final int LIVEPOINT_SUB_BUY = 2;
    static final int LIVEPOINT_SUB_EXCHANGE = 3;

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
        return messageId == USE_NEIGONG || messageId == FACULTY_MSG || messageId == LIVEPOINT_INFO;
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
        if (messageId == LIVEPOINT_INFO) {
            handleLivepoint(connection, player, values);
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

    /**
     * 235（历练/潜能赎回，custom_holdfaculty_msg）：sub1=GETINFO 回赎回价；
     * sub2=BUY count 花黄金赎回潜能修为（count ≤ 黄金且 count×price ≤ 潜能，越界静默防刷）；
     * sub3=修为兑换（form_faculty_exchange，一期受理不实现）。成功回 [CapitalType0 帧, 0x10]。
     */
    private static void handleLivepoint(Connection connection, PlayerActor player,
                                        List<CustomC2S.Value> values) {
        if (values.size() < 2 || values.get(1).tag != 2) {
            throw new IllegalStateException("235 without int subcommand");
        }
        int sub = values.get(1).exactInt32();
        switch (sub) {
            case LIVEPOINT_SUB_GETINFO -> connection.writeFrame(CustomMessages.customIntMessage(
                    LIVEPOINT_INFO,
                    CustomMessages.Value.ofInt32(LIVEPOINT_SUB_GETINFO),
                    CustomMessages.Value.ofInt32(HOLD_FACULTY_PRICE)));
            case LIVEPOINT_SUB_BUY -> buyHoldFaculty(connection, player, values);
            case LIVEPOINT_SUB_EXCHANGE -> { /* 修为兑换：受理不实现（后续接 form_faculty_exchange） */ }
            default -> throw new IllegalStateException("unknown 235 subcommand " + sub);
        }
    }

    /** 235/2：N 黄金 → N×price 潜能修为转正式修为（form_holdfaculty_buy.lua 客户端同校验）。 */
    private static void buyHoldFaculty(Connection connection, PlayerActor player,
                                       List<CustomC2S.Value> values) {
        if (values.size() < 3 || values.get(2).tag != 2) {
            throw new IllegalStateException("235/2 without int count");
        }
        int count = values.get(2).exactInt32();
        long price = HOLD_FACULTY_PRICE;
        long amount = (long) count * price;
        if (count <= 0 || count > player.gold() || amount > player.progress().holdFaculty()) {
            return; // 越界静默（客户端同校验，双保险防刷）
        }
        connection.writeFrame(player.setGold(player.gold() - count));
        player.progress().setHoldFaculty((int) (player.progress().holdFaculty() - amount));
        player.progress().addFaculty((int) amount);
        connection.writeFrame(player.vitalUpdate());
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

    // 1006 全家族的 0x10 一律走 186 名面板瘦集（castVitalUpdate）：F 参考 61579 的
    // 24/21/23 响应帧（#2190/#2194/#2203）逐帧钉死为同一套 186 名集，与出招瘦集
    // 完全重合；不含 Mount/CurRideMount/ActDay*/TeamFaculty* 等登录帧属性。整推
    // 218 项登录全集会在帧尾重推 Mount=''/CurRideMount=0 清客户端视觉状态，把刚
    // 下发的 LogicState=108（LS_FACULTY 演武待机动作）立即打回普通待机——与
    // 2026-11-03 空中位置重置同类根因（真机 55512 会话实锤：三帧 LS=108 全部
    // 被 218 项帧吞掉动画）。
    private static void writePanelUpdate(Connection connection, PlayerActor player) {
        connection.writeFrame(player.castVitalUpdate());
    }

    /** 1006/1：选书（未知 id 静默零帧）。回 [0x18 书帧, 0x10 面板帧]。 */
    private static void convertReady(Connection connection, PlayerActor player,
                                    List<CustomC2S.Value> values, Instant now) {
        String configId = values.size() > 2 ? values.get(2).textOrNull() : "";
        int slot = player.progress().facultyReady(configId, now);
        if (slot <= 0) {
            return;
        }
        connection.writeFrame(player.progress().bookFrame(slot));
        writePanelUpdate(connection, player);
    }

    /** 1006/11 普通(LogicState 恒 0)；1006/21 演武(LogicState=108 + 191/22)。 */
    private static void begin(Connection connection, PlayerActor player, Instant now, boolean actStyle) {
        player.progress().facultyBegin(actStyle, now);
        player.setLogicStateOverride(actStyle ? PlayerProgress.LOGIC_STATE_FACULTY : 0);
        writePanelUpdate(connection, player);
        if (actStyle) {
            connection.writeFrame(facultyMessage(SUB_ACT_BEGIN, 0));
        }
    }

    /** 1006/22 与 /12：停修。回 [0x10 面板帧]。 */
    private static void exit(Connection connection, PlayerActor player, Instant now) {
        player.progress().facultyExit(now);
        player.setLogicStateOverride(0);
        writePanelUpdate(connection, player);
    }

    /** 1006/23 演武卡：card∈[0,2] pay∈{1,2,4} 否则静默；陈旧请求也吞。
     * 碎银支付（pay=1）按 actfacultyinfo.ini 卡基价（文）扣 CapitalType1 并入账今日额度
     * （ActCostSuiYin/ActDayValueUsed 随 0x10 下发；有限额模式超限静默拒绝）；
     * 官银（2）/银票（4）本服未建档，暂免扣只发奖。 */
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
        if (player.progress().facultyBookSlot() <= 0) {
            return; // 无修炼书：陈旧请求，吞掉
        }
        int costWen = 0;
        if (payment == 1) {
            costWen = ActFacultyCatalog.load().cardBaseWen(
                    player.progress().facultyName(), player.progress().actBookLevel(), card);
            if (player.silver() < costWen
                    || !player.progress().tryChargeActSuiYin(costWen)) {
                return; // 碎银不足/超每日上限：静默拒绝
            }
        }
        int[] award = player.progress().awardActiveFaculty();
        if (payment == 1 && costWen > 0) {
            player.setSilver(player.silver() - costWen);
            connection.writeFrame(player.silverUpdate());
        }
        if (award[1] == 1) {
            // 书帧先行：面板在表单打开期间需重读等级。
            connection.writeFrame(player.progress().bookFrame(award[0]));
        }
        writePanelUpdate(connection, player);
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
        writePanelUpdate(connection, player);
    }
}
