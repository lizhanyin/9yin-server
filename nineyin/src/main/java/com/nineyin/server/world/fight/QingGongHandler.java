package com.nineyin.server.world.fight;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.util.ServerPaths;

/**
 * C2S msg216（CLIENT_CUSTOMMSG_USE_QINGGONG，引擎直发）：[216, 轻功ConfigID, flag]。
 * 52795 抓包实证：qinggong_9 flag=1 按住开始缓降 / flag=0 松开结束（qinggong_8 等
 * 冲刺类恒 flag=0）。三段跳（qinggong_5）的存在 buff（buf_DHadd_3rd）与起跳物理
 * 全在客户端引擎本地，服务端只受理 qinggong_9 的缓降图标链：
 * 挂 = msg169 九值(add=0, buf_qg_light, Category=2048, 到期 ms) + BufferInfoN 槽帧；
 * 摘 = msg169(add=1) + 空槽帧；每秒 CCQGP=10 扣轻功值（0x10 单帧），耗尽/到期强摘。
 *
 * <p>2026-11-03 轻功值经济补全（Go handleQingGongCustom + 玩家官方资料）：
 * qgdefine.ini 的 Consume 在 begin 一并扣（踏波行/上天梯开局 10 点、三段跳/凌空 15 点；
 * end 再扣=一次按放双倍耗）；UseType=1 持续型（踏波行/上天梯/壁虎游墙）挂秒耗租约
 * （BufferID→buff_varprop CCQGP：drift/climb=-7/s），秒耗与回复互斥。
 */
public final class QingGongHandler {

    private static final Logger LOG = Logger.getLogger(QingGongHandler.class.getName());

    public static final int USE_QING_GONG = 216;
    /** qgdefine [qinggong_9]：Type=9 轻身（下落阶段按住跳跃键发动，缓慢下落）。 */
    static final String LIGHT_QING_GONG = "qinggong_9";
    /** qgdefine [qinggong_9] Consume=10：缓降开局费（此后每秒 CCQGP 10 点）。 */
    static final int GLIDE_BEGIN_COST = 10;

    private QingGongHandler() {
    }

    public static boolean handles(int messageId) {
        return messageId == USE_QING_GONG;
    }

    /** qgdefine.ini 一节（Go internal/qinggong.Definition 同字段集）。 */
    public record Definition(int consume, int useType, String bufferId) {
    }

    /** qgdefine 目录（serverRoot 变化自动换表；缺文件→空目录，216 只走旧缓降链）。 */
    static Map<String, Definition> catalog() {
        Path file = ServerPaths.projectPath("resources", "modern", "share", "skill",
                "qinggong", "qgdefine.ini");
        return ResourceCache.of("qgdefine:" + file, () -> {
            Map<String, Definition> catalog = new HashMap<>();
            ResourceCache.ini(file, StandardCharsets.ISO_8859_1, (section, key, value) -> {
                Definition current = catalog.get(section);
                Definition updated = current == null ? new Definition(0, 0, "")
                        : current;
                switch (key.toLowerCase(java.util.Locale.ROOT)) {
                    case "consume" -> updated = new Definition(parseOr(value),
                            updated.useType(), updated.bufferId());
                    case "usetype" -> updated = new Definition(updated.consume(),
                            parseOr(value), updated.bufferId());
                    case "bufferid" -> updated = new Definition(updated.consume(),
                            updated.useType(), value);
                    default -> {
                    }
                }
                catalog.put(section, updated);
            });
            return catalog;
        });
    }

    private static int parseOr(String value) {
        try {
            return (int) Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        Definition definition = catalog().get(qingGongId == null ? "" : qingGongId);
        if (definition == null) {
            LOG.fine("qinggong use id=" + qingGongId + " flag=" + flag
                    + " (unknown id, local physics only)");
            return;
        }
        if (flag == 0) {
            // end：持续型租约摘除（点数不再扣——begin 已付全程开局费；双扣=一次按放双倍耗）。
            // 冲刺类引擎每次使用都发 flag=0（52795 实证），只有真持有租约时才有摘除意义。
            if (definition.useType() == 1 && player.drifting()
                    && qingGongId != null && qingGongId.equals(player.driftQingGongId())
                    && player.releaseDrift()) {
                LOG.fine("qinggong hold end id=" + qingGongId);
            }
            return;
        }
        // begin。2026-11-03 真机反馈「按住 Shift 轻功条秒清」根因：客户端按住冲刺时
        // 引擎逐帧重发 begin——①持续型：同 id 续约不重复扣开局费（Go "ignore duplicate
        // qinggong start" 同义）；②即时型：250ms 重报闸拦引擎重发（人手 ~4/s 以下）。
        boolean renewal = definition.useType() == 1 && player.drifting()
                && qingGongId != null && qingGongId.equals(player.driftQingGongId());
        boolean instantRepeat = definition.useType() != 1
                && !player.instantQingGongRechargeable(qingGongId, player.clock().instant());
        if (renewal || instantRepeat) {
            LOG.fine("qinggong begin repeat id=" + qingGongId + " suppressed (renewal=" + renewal
                    + " instant_guard=" + instantRepeat + ")");
            return;
        }
        // 扣开局 Consume（不足则拒——客户端本地物理不受影响，仅点数闸门）。
        if (definition.consume() > 0) {
            if (player.spendQingGong(definition.consume()) < 0) {
                LOG.info("reject qinggong id=" + qingGongId + " cost=" + definition.consume()
                        + " point=" + player.actorState().qingGongPoint());
                return;
            }
            connection.writeFrame(player.qingGongPointFrame());
        }
        // 持续型（UseType=1，非缓降）：挂秒耗租约（CCQGP 每秒扣，回复暂停；表缺
        // CCQGP 时 drain=0 仍挂租约保续约/暂停语义）。异 id 新 begin 先摘旧租约
        //（踏波行→上天梯切换不叠加双份秒耗）。
        if (definition.useType() == 1) {
            int drain = CombatSkillCatalog.load()
                    .qingGongDrainPerSecond(definition.bufferId());
            player.releaseDrift();
            player.engageDrift(qingGongId, drain);
            LOG.info("qinggong hold begin id=" + qingGongId + " consume=" + definition.consume()
                    + " drain/s=" + drain + " point=" + player.actorState().qingGongPoint());
        } else if (definition.consume() > 0) {
            LOG.info("qinggong instant id=" + qingGongId + " consume=" + definition.consume()
                    + " point=" + player.actorState().qingGongPoint());
        }
    }

    /** 挂缓降：先扣 qgdefine Consume=10（Go UseQingGong 同口径）再挂 msg169(add) + 槽帧；重复 begin 幂等不再扣。 */
    private static void engageGlide(Connection connection, PlayerActor player) {
        Instant now = player.clock().instant();
        if (player.gliding()) {
            return; // 按住缓降引擎重发 begin：租约已在，不重复扣开局费（58698 抓包 1100.. 序列实证）
        }
        if (player.spendQingGong(GLIDE_BEGIN_COST) < 0) {
            LOG.info("reject glide begin: point=" + player.actorState().qingGongPoint()
                    + " < " + GLIDE_BEGIN_COST + "（本地物理不受影响）");
            return;
        }
        connection.writeFrame(player.qingGongPointFrame());
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

    /**
     * 1s ticker 的持续轻功秒耗（踏波行/上天梯/壁虎游墙租约，与 glideTick 同拍）：
     * 每秒按 CCQGP 扣点并推单帧；耗尽强摘（客户端物理本地收势）。
     *
     * @return 是否仍持有租约
     */
    public static boolean driftTick(Connection connection, PlayerActor player) {
        if (!player.drifting()) {
            return false;
        }
        if (!player.drainDriftPoint()) {
            player.releaseDrift();
            connection.writeFrame(player.qingGongPointFrame());
            LOG.fine("qinggong hold lease ended: point exhausted");
            return false;
        }
        connection.writeFrame(player.qingGongPointFrame());
        return true;
    }
}
