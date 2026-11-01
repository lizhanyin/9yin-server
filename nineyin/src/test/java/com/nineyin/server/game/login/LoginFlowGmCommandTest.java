package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
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
}
