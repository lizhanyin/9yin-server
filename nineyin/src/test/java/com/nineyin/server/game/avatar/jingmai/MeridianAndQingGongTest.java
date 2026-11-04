package com.nineyin.server.game.avatar.jingmai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.fight.QingGongHandler;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 经脉修炼系统 + 三段跳缓降（qinggong_9）链路测试：
 * 目录解析（jingmai_static 区间宽=MaxLevel）→ 状态机（整分钟真气/升级/总等级）→
 * View45 帧布局（52795 抓包 6 属性）→ C2S 150 修炼 → C2S 216 缓降挂/摘/点耗。
 */
class MeridianAndQingGongTest {

    /** 运行时 share 根（9yin_server1 的 resources/modern/share 含 jingmai 全套表）。 */
    private static final Path SHARE_ROOT =
            Path.of("E:/Workspace/project/game/9yin_server1");

    @BeforeAll
    static void needsShareResources() {
        Assumptions.assumeTrue(Files.isDirectory(
                        SHARE_ROOT.resolve(Path.of("resources", "modern", "share", "skill", "jingmai"))),
                "runtime share jingmai 资源不可用，跳过经脉链路");
        com.nineyin.util.ServerPaths.overrideServerRoot(SHARE_ROOT);
    }

    /** 可推进的固定时钟。 */
    static final class SettableClock extends Clock {
        private long epochMillis = 1_793_532_000_000L;

        void advanceMillis(long millis) {
            epochMillis += millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final class Harness {
        final SettableClock clock = new SettableClock();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final PlayerActor player = PlayerActor.create("紫泉", 0, clock);
        final MeridianCatalog catalog = MeridianCatalog.load();

        List<byte[]> drain() {
            List<byte[]> frames = new ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    // ------------------------------------------------------------------ 目录

    @Test
    void catalogParsesMaxLevelFromVarPropRange() {
        Harness harness = new Harness();
        MeridianCatalog.Definition dantian = harness.catalog.definition("jm_dantian");
        assertNotNull(dantian);
        assertEquals(41, dantian.staticData(), "F 盘抓包 View45 dantian StaticData=41");
        assertEquals(1003, dantian.itemType());
        assertEquals(180, dantian.maxLevel(), "jingmai_static[41] 141001..141180 → 180 周天");
        assertEquals(216, harness.catalog.definition("jm_zuyangming").maxLevel(), "正经 216");
        assertEquals(750, harness.catalog.totalFillPerLevel("jm_dantian"), "F 端平曲线 750");
        assertEquals(43, harness.catalog.zhenQiPerMinute("jm_dantian"), "wuxuelevelinfo 第 3 列");
        assertEquals(14, MeridianCatalog.STARTER_MERIDIANS.size(), "抓包 View45 = 14 行");
    }

    // ------------------------------------------------------------------ 状态机

    @Test
    void cultivationTicksPerMinuteAndLevelsUpAtFlatCurve() {
        Harness harness = new Harness();
        PlayerMeridian state = harness.player.meridian();
        assertEquals(14, state.learned().size(), "初始 14 条经脉（等级 1）");
        assertEquals(14, state.totalLevel(), "经脉总等级初始 14");

        assertTrue(state.setCultivating("jm_dantian"));
        assertFalse(state.setCultivating("jm_bu_cun_zai"), "未学经脉拒绝");

        // 未满整分钟：静默。
        harness.clock.advanceMillis(59_000);
        assertEquals(PlayerMeridian.TickResult.NONE.changed(), state.tick().changed());

        // 满 1 分钟：+43 真气；打满 750（43*17=731 + 战斗 50）→ 周天 2、总等级 15。
        harness.clock.advanceMillis(1_000);
        PlayerMeridian.TickResult tick = state.tick();
        assertTrue(tick.changed());
        assertEquals(43, state.meridian("jm_dantian").fill());

        for (int i = 0; i < 16; i++) {
            harness.clock.advanceMillis(60_000);
            state.tick();
        }
        assertEquals(43 * 17, state.meridian("jm_dantian").fill(), "17 分钟累计 731");

        PlayerMeridian.TickResult combat = state.addCombatZhenQi(500);
        assertTrue(combat.changed());
        assertEquals(List.of("jm_dantian"), combat.leveledUp(), "731+50 ≥ 750 → 周天 2");
        assertEquals(2, state.meridian("jm_dantian").level());
        assertEquals(31, state.meridian("jm_dantian").fill(), "溢出结转");
        assertEquals(15, state.totalLevel(), "总等级 14+1");
        assertEquals(43 * 17 + 50, state.plValue(), "今日已获真气（被动 731 + 战斗 500×0.1=50）");
    }

    @Test
    void stopCultivationMovesCurrentToLast() {
        Harness harness = new Harness();
        PlayerMeridian state = harness.player.meridian();
        assertTrue(state.setCultivating("jm_dantian"));
        assertTrue(state.setCultivating(""));
        assertEquals("", state.curJingMai());
        assertEquals("jm_dantian", state.lastJingMai());
        assertEquals(PlayerMeridian.TickResult.NONE.changed(), state.tick().changed(), "停修不结算");
    }

    @Test
    void dailyCapGatesZhenQiWhenEnabled() {
        Harness harness = new Harness();
        PlayerMeridian state = harness.player.meridian();
        state.setCultivating("jm_dantian");

        // 关闸（默认）：不限量，ZhenQiDayValue 回落官方缺省 500（战斗真气 damage×0.1）。
        assertFalse(state.capEnabled());
        assertEquals(500, state.zhenQiDayValue());
        state.addCombatZhenQi(2000);
        assertEquals(200, state.plValue());

        // 开闸（阈值 250）：剩余额度 50，本笔 200 截断为 50；再入账被硬闸拦截。
        state.setDailyCap(true, 250);
        assertTrue(state.capEnabled());
        assertEquals(250, state.zhenQiDayValue(), "开闸后 ZhenQiDayValue=阈值");
        PlayerMeridian.TickResult partial = state.addCombatZhenQi(2000);
        assertTrue(partial.changed(), "剩余 50 额度内部分入账");
        assertEquals(250, state.plValue(), "截断到阈值");
        assertEquals(250, state.meridian("jm_dantian").fill(), "进度 = 200 + 截断的 50");
        PlayerMeridian.TickResult blocked = state.addCombatZhenQi(5000);
        assertFalse(blocked.changed(), "额度用尽后硬闸拦截（零帧）");
        assertEquals(250, state.meridian("jm_dantian").fill(), "进度停在闸口");

        // 日切后额度重置；关闸恢复不限量，阈值仍保存、重开沿用。
        harness.clock.advanceMillis(24 * 60 * 60 * 1000L + 1000);
        assertEquals(0, state.plValue(), "日切清零今日已获（plValue 惰性滚动日切）");
        state.setDailyCap(false, 250);
        assertFalse(state.capEnabled());
        assertEquals(500, state.zhenQiDayValue());
        PlayerMeridian.Snapshot snapshot = state.snapshot();
        assertFalse(snapshot.capEnabled());
        assertEquals(250, snapshot.capValue());
        PlayerMeridian restored = PlayerMeridian.create(harness.catalog, harness.clock);
        restored.restore(snapshot, harness.clock.instant());
        assertEquals(250, restored.capValue(), "阈值随快照持久化");
        assertFalse(restored.capEnabled());
    }

    @Test
    void snapshotRestoreKeepsStarterRows() {
        Harness harness = new Harness();
        PlayerMeridian state = harness.player.meridian();
        state.setCultivating("jm_zuyangming");
        PlayerMeridian.Snapshot snapshot = state.snapshot();

        PlayerMeridian restored = PlayerMeridian.create(harness.catalog, harness.clock);
        restored.restore(null, harness.clock.instant());
        restored.restore(snapshot, harness.clock.instant());
        assertEquals("jm_zuyangming", restored.curJingMai());
        assertEquals(14, restored.learned().size(), "恢复缺行以 14 条初始经脉补齐");
        assertEquals(14, restored.totalLevel());
    }

    // ------------------------------------------------------------------ 帧

    @Test
    void view45FramesMatchCaptureLayout() {
        Harness harness = new Harness();
        List<byte[]> frames = JingMaiMessages.viewFrames(harness.player.meridian(), harness.catalog);
        assertEquals(15, frames.size(), "0x15 容器 + 14 行");
        assertEquals(0x15, frames.get(0)[0]);
        assertEquals(45, frames.get(0)[1] | frames.get(0)[2] << 8);

        byte[] row = frames.get(1);
        assertEquals(0x18, row[0]);
        assertEquals(45, row[1] | row[2] << 8);
        assertEquals(1, row[3] | row[4] << 8, "行号从 1 起");
        assertEquals(6, row[5] | row[6] << 8, "抓包 View45 行 = 6 属性");
        // 视图属性 = u16 索引 + 裸值（线型由协商属性表按索引决定）：
        // ConfigID 1440(str) / ItemType 1889(i32) / StaticData 2237(i32) /
        // Level 1465(byte) / MaxLevel 2083(i32) / TotalFillValue 765(i32)。
        int off = 7;
        int[] indexes = new int[6];
        int[] wireTypes = {7, 3, 3, 1, 3, 3};
        for (int i = 0; i < 6; i++) {
            indexes[i] = (row[off] & 0xFF) | row[off + 1] << 8;
            off += 2;
            switch (wireTypes[i]) {
                case 1 -> off += 1;
                case 3 -> off += 4;
                case 7 -> {
                    int n = (row[off] & 0xFF) | (row[off + 1] & 0xFF) << 8
                            | (row[off + 2] & 0xFF) << 16 | (row[off + 3] & 0xFF) << 24;
                    off += 4 + n;
                }
                default -> throw new AssertionError("wire type " + wireTypes[i]);
            }
        }
        assertArrayEquals(new int[]{1440, 1889, 2237, 1465, 2083, 765}, indexes,
                "抓包 View45 属性索引序");
        assertEquals(row.length, off, "行帧无剩余字节");
    }

    @Test
    void playerPropsFrameCarriesJingMaiProperties() {
        Harness harness = new Harness();
        harness.player.meridian().setCultivating("jm_dantian");
        byte[] frame = JingMaiMessages.playerPropsFrame(harness.player.meridian());
        assertEquals(0x10, frame[0]);
        int count = frame[10] | frame[11] << 8;
        assertEquals(8, count, "CurJingMai/LastJingMai/Total/jmActCount/ZhenQiDay/ZQPL/ZQActl/ZQUnUsed");
        String text = new String(frame, StandardCharsets.UTF_8);
        assertTrue(text.contains("jm_dantian"), "CurJingMai 串值可见");
    }

    @Test
    void activeRecordRowUsesOfficialIndex162() {
        byte[] frame = JingMaiMessages.activeRecordRow(1, 1, "jm_dantian");
        assertEquals(0x11, frame[0]);
        assertEquals(162, (frame[10] & 0xFF) | frame[11] << 8, "active_jingmai_rec 官方下标");
        assertEquals(RecordMessages.RECORD_ACTIVE_JINGMAI, 162);
        byte[] same = RecordMessages.recordAddString(1, 1, 162, "jm_dantian");
        assertArrayEquals(same, frame);
    }

    // ------------------------------------------------------------------ C2S 150

    @Test
    void custom150SetsCultivationAndEmitsPropsAndActiveRow() {
        Harness harness = new Harness();
        JingMaiCustomHandler.handle(harness.connection, harness.player,
                jingmaiMessage(1, "jm_dantian"));
        List<byte[]> frames = harness.drain();
        assertEquals(2, frames.size(), "active_jingmai_rec 行 + 玩家属性帧");
        assertEquals(0x11, frames.get(0)[0]);
        assertEquals(162, (frames.get(0)[10] & 0xFF) | frames.get(0)[11] << 8);
        assertEquals(0x10, frames.get(1)[0]);
        assertEquals("jm_dantian", harness.player.meridian().curJingMai());
        assertTrue(harness.player.meridianActivated());

        // 停止修炼：不再发 active 行（已激活），只回属性帧。
        JingMaiCustomHandler.handle(harness.connection, harness.player,
                jingmaiMessage(1, ""));
        frames = harness.drain();
        assertEquals(1, frames.size());
        assertEquals(0x10, frames.get(0)[0]);
        assertEquals("", harness.player.meridian().curJingMai());

        // sub=2 真气刷新：无状态迁移，仅属性帧。
        JingMaiCustomHandler.handle(harness.connection, harness.player, jingmaiMessage(2, null));
        assertEquals(1, harness.drain().size());
    }

    // ------------------------------------------------------------------ C2S 216

    @Test
    void qinggong216EngageAndReleaseGlideBuff() {
        Harness harness = new Harness();
        QingGongHandler.handle(harness.connection, harness.player,
                customMessage(216, "qinggong_9", 1));
        List<byte[]> frames = harness.drain();
        // 2026-11-03 轻功值经济：begin 先扣 Consume=10（Go UseQingGong 同口径）
        // 并推 QG 点数帧，再挂 buff 链。
        assertEquals(3, frames.size(), "QG 点数帧 + msg169(挂) + BufferInfoN 槽帧");
        assertEquals(0x10, frames.get(0)[0], "开局费扣点单帧");
        assertEquals(140, harness.player.actorState().qingGongPoint());
        byte[] buff = frames.get(1);
        assertEquals(0x1E, buff[0]);
        // [169, 0, 'buf_qg_light', obj, obj, 宽名×2, i64 到期, i32 2048] 九值。
        int args = buff[1] | buff[2] << 8;
        assertEquals(9, args);
        String text = new String(buff, StandardCharsets.UTF_8);
        assertTrue(text.contains("buf_qg_light"));
        assertTrue(harness.player.gliding());

        // 1s tick 扣 10 轻功值（140 → 130）。
        assertTrue(QingGongHandler.glideTick(harness.connection, harness.player));
        harness.drain();
        assertEquals(130, harness.player.actorState().qingGongPoint());

        QingGongHandler.handle(harness.connection, harness.player,
                customMessage(216, "qinggong_9", 0));
        frames = harness.drain();
        assertEquals(2, frames.size(), "msg169(摘) + 空槽帧");
        assertFalse(harness.player.gliding());
        assertFalse(QingGongHandler.glideTick(harness.connection, harness.player), "停持后 tick 静默");
    }

    @Test
    void glideExpiresWhenPointsExhausted() {
        Harness harness = new Harness();
        QingGongHandler.handle(harness.connection, harness.player,
                customMessage(216, "qinggong_9", 1));
        assertTrue(harness.player.gliding());
        harness.drain();
        // begin 先扣 10（150→140），此后每秒 10：14 拍扣完，第 15 拍付不起强摘。
        for (int i = 0; i < 14 && harness.player.gliding(); i++) {
            harness.clock.advanceMillis(1_000);
            QingGongHandler.glideTick(harness.connection, harness.player);
            harness.drain();
        }
        assertTrue(harness.player.gliding(), "14 秒后仍持有租约（点数恰扣完）");
        assertEquals(0, harness.player.actorState().qingGongPoint());
        harness.clock.advanceMillis(1_000);
        boolean still = QingGongHandler.glideTick(harness.connection, harness.player);
        assertFalse(still, "点耗尽强摘");
        assertFalse(harness.player.gliding());
    }

    @Test
    void qinggongOtherTypesAreLocalOnly() {
        Harness harness = new Harness();
        QingGongHandler.handle(harness.connection, harness.player,
                customMessage(216, "qinggong_8", 0));
        assertEquals(0, harness.drain().size(), "冲刺/跳跃物理在客户端本地，零帧");
        assertFalse(harness.player.gliding());
    }

    // ------------------------------------------------------------------ 构造

    /** 0x1E 直达 custom 帧：[msgId(int), args...]（窄串 + 可选 int）。 */
    static CustomC2S.Message customMessage(int messageId, String text) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(2);
        writer.writeU8(2).writeU32(messageId);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        return CustomC2S.parseDirect(writer.toBytes());
    }

    static CustomC2S.Message customMessage(int messageId, String text, int flag) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(3);
        writer.writeU8(2).writeU32(messageId);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        writer.writeU8(2).writeU32(flag);
        return CustomC2S.parseDirect(writer.toBytes());
    }

    /** 150 经脉家族：[150, sub(int), 经脉ConfigID(串，可空省)]。 */
    static CustomC2S.Message jingmaiMessage(int sub, String configId) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(configId == null ? 2 : 3);
        writer.writeU8(2).writeU32(150);
        writer.writeU8(2).writeU32(sub);
        if (configId != null) {
            byte[] data = configId.getBytes(StandardCharsets.UTF_8);
            writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        }
        return CustomC2S.parseDirect(writer.toBytes());
    }
}
