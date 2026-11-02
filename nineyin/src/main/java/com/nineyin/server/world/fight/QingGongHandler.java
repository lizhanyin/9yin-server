package com.nineyin.server.world.fight;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.transport.Connection;

/**
 * C2S msg216（CLIENT_CUSTOMMSG_USE_QINGGONG，引擎直发）：[216, 轻功ConfigID, flag]。
 * 52795 抓包实证：qinggong_9 flag=1 按住开始缓降 / flag=0 松开结束（qinggong_8 等
 * 冲刺类恒 flag=0）。三段跳（qinggong_5）的存在 buff（buf_DHadd_3rd）与起跳物理
 * 全在客户端引擎本地，服务端只受理 qinggong_9 的缓降图标链：
 * 挂 = msg169 九值(add=0, buf_qg_light, Category=2048, 到期 ms) + BufferInfoN 槽帧；
 * 摘 = msg169(add=1) + 空槽帧；每秒 CCQGP=10 扣轻功值（0x10 单帧），耗尽/到期强摘。
 */
public final class QingGongHandler {

    private static final Logger LOG = Logger.getLogger(QingGongHandler.class.getName());

    public static final int USE_QING_GONG = 216;
    /** qgdefine [qinggong_9]：Type=9 轻身（下落阶段按住跳跃键发动，缓慢下落）。 */
    static final String LIGHT_QING_GONG = "qinggong_9";

    private QingGongHandler() {
    }

    public static boolean handles(int messageId) {
        return messageId == USE_QING_GONG;
    }

    public static void handle(Connection connection, PlayerActor player, CustomC2S.Message message) {
        if (player == null) {
            throw new IllegalStateException("character has not entered a scene");
        }
        List<CustomC2S.Value> values = message.values;
        if (values.size() < 3) {
            LOG.info("216 without qinggong id + flag");
            return;
        }
        String qingGongId = values.get(1).textOrNull();
        int flag = values.get(2).exactInt32();
        if (LIGHT_QING_GONG.equals(qingGongId)) {
            if (flag == 1) {
                engageGlide(connection, player);
            } else {
                releaseGlide(connection, player, "release");
            }
            return;
        }
        // 其余轻功（跳/冲/爬）物理在客户端本地，服务端只记事件（点数由客户端本地权威）。
        LOG.fine("qinggong use id=" + qingGongId + " flag=" + flag + " (local physics, no frames)");
    }

    /** 挂缓降：msg169(add) + 槽帧；无空闲 buff 槽静默放弃（客户端物理不受影响）。 */
    private static void engageGlide(Connection connection, PlayerActor player) {
        Instant now = player.clock().instant();
        int slot = player.freeBuffSlot();
        if (slot <= 0) {
            LOG.info("glide engage without free buff slot, keep local physics only");
            return;
        }
        Instant expiresAt = player.engageGlide(slot, now);
        if (expiresAt == null) {
            return;
        }
        String name = player.snapshotName();
        connection.writeFrame(FightMessages.timedBuff(0, PlayerActor.GLIDE_BUFF_CONFIG,
                PlayerActor.GLIDE_BUFF_CATEGORY, expiresAt.toEpochMilli(),
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, name));
        connection.writeFrame(player.buffSlotFrame(slot, player.bufferInfo(slot, now)));
        LOG.info("glide engage slot=" + slot + " expires=" + expiresAt);
    }

    /** 摘缓降：msg169(remove) + 空槽帧（游离 release 也回帧，客户端状态重同步）。 */
    private static void releaseGlide(Connection connection, PlayerActor player, String reason) {
        Instant endsAt = player.glideEndsAt();
        int slot = player.releaseGlide();
        String name = player.snapshotName();
        long endMs = endsAt == null ? player.clock().instant().toEpochMilli() : endsAt.toEpochMilli();
        connection.writeFrame(FightMessages.timedBuff(1, PlayerActor.GLIDE_BUFF_CONFIG,
                PlayerActor.GLIDE_BUFF_CATEGORY, endMs,
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, name));
        if (slot > 0) {
            connection.writeFrame(player.buffSlotFrame(slot, ""));
        }
        LOG.info("glide release (" + reason + ") slot=" + slot);
    }

    /**
     * 1s ticker 的缓降消耗（facultyTicker 同拍）：每秒扣 10 轻功值并推单帧；
     * 付不起或租约到期 → 强摘（客户端物理随之收势）。
     *
     * @return 是否仍持有缓降租约
     */
    public static boolean glideTick(Connection connection, PlayerActor player) {
        if (!player.gliding()) {
            return false;
        }
        Instant now = player.clock().instant();
        Instant endsAt = player.glideEndsAt();
        if (endsAt != null && !now.isBefore(endsAt)) {
            releaseGlide(connection, player, "lease-expired");
            return false;
        }
        if (!player.drainGlidePoint()) {
            releaseGlide(connection, player, "point-exhausted");
            return false;
        }
        connection.writeFrame(player.qingGongPointFrame());
        return true;
    }
}
