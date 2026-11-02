package com.nineyin.server.game.avatar.jingmai;

import java.util.List;
import java.util.logging.Logger;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;

/**
 * C2S msg150（CLIENT_CUSTOMMSG_JINGMAI_WUJI，客户端 custom_jingmai_wuji_msg）：
 * sub=1 开始/停止修炼（参数=经脉 ConfigID，空串=停止——form_wuxue_jingmai.lua:336-384
 * 点"修炼"按钮）；sub=2 修炼信息浮窗打开时的真气刷新请求（无状态变化，回属性帧重绘）。
 * （sub 21-28 是同协议号复用的武技(无极)族，暂不受理。）
 *
 * <p>回帧：玩家经脉属性 0x10（CurJingMai/LastJingMai/JingMaiTotalLevel/ZQ 族）；
 * 首次设置修炼时补 active_jingmai_rec 行（jmActCount=1，激活位=当前修炼经脉，
 * F 盘抓包 CurJingMai 与 active 行同为 jm_dantian 的形态）。
 */
public final class JingMaiCustomHandler {

    private static final Logger LOG = Logger.getLogger(JingMaiCustomHandler.class.getName());

    public static final int JINGMAI_WUJI_MSG = 150;
    static final int SUB_SET_JINGMAI = 1;
    static final int SUB_UPDATE_ZQ = 2;

    private JingMaiCustomHandler() {
    }

    public static boolean handles(int messageId) {
        return messageId == JINGMAI_WUJI_MSG;
    }

    public static void handle(Connection connection, PlayerActor player, CustomC2S.Message message) {
        if (player == null) {
            throw new IllegalStateException("character has not entered a scene");
        }
        List<CustomC2S.Value> values = message.values;
        if (values.size() < 2 || values.get(1).tag != 2) {
            throw new IllegalStateException("150 without int subcommand");
        }
        int sub = values.get(1).exactInt32();
        switch (sub) {
            case SUB_SET_JINGMAI -> setCultivating(connection, player, values);
            case SUB_UPDATE_ZQ -> {
                // 真气刷新：无状态迁移，回一帧属性强制 UI binder 重读。
                connection.writeFrame(JingMaiMessages.playerPropsFrame(player.meridian()));
            }
            default -> LOG.info("jingmai msg 150 sub=" + sub + " not handled (wuji family reserved)");
        }
    }

    /** 150/1：V2=经脉 ConfigID（空串=停止）。未学经脉静默拒绝（客户端不弹错）。 */
    private static void setCultivating(Connection connection, PlayerActor player,
                                       List<CustomC2S.Value> values) {
        String configId = values.size() > 2 ? values.get(2).textOrNull() : "";
        if (configId == null) {
            configId = "";
        }
        PlayerMeridian state = player.meridian();
        String previous = state.curJingMai();
        if (!state.setCultivating(configId)) {
            LOG.info("reject cultivate unlearned meridian id=" + configId);
            return;
        }
        // 首次开始修炼且无激活行：补 active_jingmai_rec（激活位=修炼经脉）。
        if (previous.isEmpty() && !state.curJingMai().isEmpty() && !player.meridianActivated()) {
            connection.writeFrame(JingMaiMessages.activeRecordRow(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    state.curJingMai()));
            player.noteMeridianActivated();
        }
        connection.writeFrame(JingMaiMessages.playerPropsFrame(state));
        LOG.info("cultivate set '" + previous + "' -> '" + state.curJingMai() + "' totalLevel="
                + state.totalLevel());
    }
}
