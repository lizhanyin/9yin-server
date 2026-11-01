package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 队列消费端（Go main.go processGMCommand 的移动参数分支）：入队命令按
 * action 应用到 PlayerActor、写单帧 0x10、更新面板 lastAction 并完成回执；
 * 未进场景/坏值/未实现动作明确失败且不写帧。
 */
class LoginFlowGmCommandTest {

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final GmHub hub = new GmHub();
        final LoginSession session = new LoginSession();
        final LoginFlow flow;

        Env() {
            session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null);
        }

        GmCommand enqueue(String action, Map<String, Object> params) {
            GmCommand command = new GmCommand(session.machine.id(), action, new JSONObject(params));
            hub.enqueue(command);
            return command;
        }
    }

    private static GmCommand.Reply reply(GmCommand command) {
        return command.awaitReply(Duration.ofMillis(1));
    }

    @Test
    void moveSpeedAndGravityAppliedWithFrameReplyAndLastAction() {
        Env env = new Env();
        GmCommand speed = env.enqueue("set_move_speed", Map.of("value", 30));
        GmCommand gravity = env.enqueue("set_gravity", Map.of("value", 5));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(speed).ok(), "合法移动速度应成功");
        assertEquals("移动速度设为 30.00", reply(speed).message());
        assertTrue(reply(gravity).ok(), "合法重力应成功");
        // motion 状态突变与帧布局由 PlayerActorMotionTest 覆盖；此处验证编排链。

        // 两条成功命令各写一帧普通 0x10 归档。
        byte[] first = env.channel.readOutbound();
        byte[] second = env.channel.readOutbound();
        assertTrue(first != null && second != null, "应写出两帧");
        assertNull(env.channel.readOutbound(), "不应有更多帧");
        assertEquals(0x10, first[0] & 0xFF);
        assertEquals(0x10, second[0] & 0xFF);

        // 面板 lastAction = 最后一次成功动作文案。
        assertEquals("下落重力设为 5.00", env.hub.get(env.session.machine.id()).lastAction);
    }

    @Test
    void badValueAndUnknownActionFailWithoutFrames() {
        Env env = new Env();
        GmCommand over = env.enqueue("set_move_speed", Map.of("value", 500));
        GmCommand unknown = env.enqueue("spawn_dummy", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(over).ok(), "超界值应失败");
        assertTrue(reply(over).message().contains("0.1"));
        assertFalse(reply(unknown).ok(), "未实现动作应失败");
        assertTrue(reply(unknown).message().contains("spawn_dummy"));
        assertNull(env.channel.readOutbound(), "失败命令不应写帧");
    }

    @Test
    void commandBeforeEnteringSceneFails() {
        Env env = new Env();
        env.session.player = null;
        GmCommand command = env.enqueue("set_move_speed", Map.of("value", 30));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(command).ok());
        assertEquals("角色尚未进入场景", reply(command).message());
    }

    /** 无命令时 drain 是静默空转（1s ticker 每秒调用）。 */
    @Test
    void drainIsNoopWithoutCommands() {
        Env env = new Env();
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertNull(env.channel.readOutbound());
    }

    // ------------------------------------------------------------- 四期面板动作

    @Test
    void powerLevelPushesLevelAndTitleFrames() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_power_level", Map.of("value", 137));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok());
        assertEquals(137, env.session.player.panel().powerLevel);
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "实力变更推单帧 0x10");
        // 0x10|isView|u32 oid|u32 owner|u16 count|条目：count=2 且含 PowerLevel=137
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(2, count, "PowerLevel + LevelTitle 两属性");
        assertTrue(containsInt32(frame, "PowerLevel", 137), "帧内 PowerLevel=137");
        assertNull(env.channel.readOutbound());
    }

    @Test
    void alignmentAppliesJusticeEvilFreeBase() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_alignment",
                Map.of("justice", 3200, "evil", 9, "freeBase", 0));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok());
        assertEquals(3200, env.session.player.panel().justice);
        assertEquals(9, env.session.player.panel().evil);
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10);
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(3, count, "Justice/Evil/FreeBase 三属性");
        GmCommand empty = env.enqueue("set_alignment", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertFalse(reply(empty).ok(), "全缺参数失败");
    }

    @Test
    void vitalsApplyAndRejectEmptyParams() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_vitals",
                Map.of("ene", 80, "maxEne", 100, "pkValue", 7, "drunkLevel", 3));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok());
        assertEquals(80, env.session.player.panel().ene);
        assertEquals(100, env.session.player.panel().maxEne);
        assertEquals(7, env.session.player.panel().pkValue);
        assertEquals(3, env.session.player.panel().drunkLevel);
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10);
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(4, count);
        GmCommand empty = env.enqueue("set_vitals", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertFalse(reply(empty).ok());
    }

    @Test
    void titleWearAndCancelPushTitleId() {
        Env env = new Env();
        GmCommand wear = env.enqueue("wear_title", Map.of("titleId", 12));
        GmCommand bad = env.enqueue("wear_title", Map.of("titleId", -1));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(wear).ok(), "无 DB store 时内存佩戴成功");
        assertEquals(12, env.session.player.panel().wornTitleId);
        assertTrue(containsInt32(env.channel.readOutbound(), "TitleID", 12));
        assertFalse(reply(bad).ok(), "非正 titleId 失败");

        GmCommand cancel = env.enqueue("cancel_title", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(cancel).ok());
        assertEquals(0, env.session.player.panel().wornTitleId);
        assertTrue(containsInt32(env.channel.readOutbound(), "TitleID", 0));
    }

    @Test
    void statePropertiesCarryDomainBlock() {
        Env env = new Env();
        env.session.player.panel().wornTitleId = 12;
        env.session.player.panel().maxBingLuLevel = 10;
        env.session.player.panel().prestigeExp = 55000;
        env.session.player.panel().mount = "mount_test_01";
        List<String> names = env.session.player.stateProperties().stream()
                .map(com.nineyin.server.protocol.PropertyValues.Indexed::name)
                .toList();
        assertTrue(names.contains("TitleID"));
        assertTrue(names.contains("Mount"));
        assertTrue(names.contains("CurRideMount"));
        assertTrue(names.contains("BingLuType"));
        assertTrue(names.contains("MaxBingLuLevel"));
        assertTrue(names.contains("PrestigeExp"));
    }

    /** 0x10 帧内按官方表索引找属性并比对 i32 值（条目=u16 idx + 线型值；INT32=4B）。 */
    private static boolean containsInt32(byte[] frame, String name, int expected) {
        if (frame == null || (frame[0] & 0xFF) != 0x10) {
            return false;
        }
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        int wanted = com.nineyin.server.protocol.PropertyTable.idx(name);
        int offset = 12;
        for (int i = 0; i < count; i++) {
            int index = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
            offset += 2;
            if (index == wanted
                    && com.nineyin.server.protocol.PropertyTable.wireType(name)
                    == com.nineyin.server.protocol.WireType.INT32) {
                int value = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                        | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
                return value == expected;
            }
            offset += 4; // 本测试用到的属性均为 INT32
        }
        return false;
    }

    // ------------------------------------------------------------- 货币/怒气

    @Test
    void currenciesApplyWithFramesSnapshotAndLastAction() {
        Env env = new Env();
        GmCommand silver = env.enqueue("set_silver", Map.of("silver", 555));
        GmCommand guanYin = env.enqueue("set_guanyin", Map.of("guanYin", 777));
        GmCommand gold = env.enqueue("set_gold", Map.of("gold", 9));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(silver).ok());
        assertEquals("碎银设为 555", reply(silver).message());
        assertTrue(reply(guanYin).ok());
        assertEquals("官银设为 777", reply(guanYin).message());
        assertTrue(reply(gold).ok());
        assertEquals("黄金设为 9", reply(gold).message());
        for (int i = 0; i < 3; i++) {
            byte[] frame = env.channel.readOutbound();
            assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "每条货币命令各写一帧 0x10");
        }
        assertNull(env.channel.readOutbound());

        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertEquals(555, snapshot.silver);
        assertEquals(777, snapshot.guanYin.intValue());
        assertEquals(9, snapshot.gold.intValue());
        assertEquals("黄金设为 9", snapshot.lastAction, "lastAction=最后一次成功动作");
    }

    @Test
    void spSetAddFillApplyWithSaturation() {
        Env env = new Env();
        GmCommand set = env.enqueue("set_sp", Map.of("sp", 30));
        GmCommand add = env.enqueue("add_sp", Map.of("sp", 90));
        GmCommand fill = env.enqueue("fill_sp", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(set).ok());
        assertEquals("怒气设为 30/100", reply(set).message());
        assertTrue(reply(add).ok());
        assertEquals("怒气设为 100/100", reply(add).message(), "30+90 饱和到 100");
        assertTrue(reply(fill).ok());
        assertEquals("怒气设为 100/100", reply(fill).message());
        for (int i = 0; i < 3; i++) {
            byte[] frame = env.channel.readOutbound();
            assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "每条怒气命令各写一帧 0x10");
        }
        assertNull(env.channel.readOutbound());
        assertEquals(100, env.session.player.sp());
        assertEquals(100, env.hub.get(env.session.machine.id()).sp);
    }

    @Test
    void negativeCurrencyRejectedWithoutFrames() {
        Env env = new Env();
        GmCommand silver = env.enqueue("set_silver", Map.of("silver", -1));
        GmCommand guanYin = env.enqueue("set_guanyin", Map.of("guanYin", -5));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(silver).ok());
        assertFalse(reply(guanYin).ok());
        assertNull(env.channel.readOutbound());
    }
}
