package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * C2S 200/201 快捷栏编辑链路（Go handleShortcutCustom 同款帧序）：
 * 200 替换 = 0x12 删旧行 + 0x11 增新行（幂等零帧）；201 按行号删 = 0x12；
 * 内存态与 role_shortcuts 回放态一致（重登持久化由 inventoryStore 落库）。
 */
class ShortcutCustomFlowTest {

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final LoginSession session = new LoginSession();
        final LoginFlow flow = new LoginFlow(null, null, new com.nineyin.server.gm.GmHub(),
                Clock.systemUTC(), null, null);

        Env() {
            session.player = PlayerActor.create("紫泉", 0, Clock.systemUTC());
            session.selected = new Roles.RoleSnapshot(66L, 1, "紫泉", false, 0, 1,
                    new Roles.Appearance(1, List.of()), null);
        }

        List<byte[]> drain() {
            List<byte[]> frames = new java.util.ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    /** 0x1E 直达 [msgId, int, str, str]（200）或 [msgId, int]（201）。 */
    private static CustomC2S.Message message(int msgId, Object... args) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(1 + args.length);
        writer.writeU8(2).writeU32(msgId);
        for (Object arg : args) {
            if (arg instanceof Integer value) {
                writer.writeU8(2).writeU32(value);
            } else {
                byte[] data = String.valueOf(arg).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
            }
        }
        return CustomC2S.parseDirect(writer.toBytes());
    }

    @Test
    void setShortcutReplacesRowAndRemoveDeletesIt() {
        Env env = new Env();
        // 首次设置 index=12：一帧 0x11（无旧行）。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp01"));
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x11, frames.get(0)[0]);
        assertEquals(RecordMessages.RECORD_SHORTCUT,
                (frames.get(0)[10] & 0xFF) | frames.get(0)[11] << 8, "官方下标 93");
        assertEquals(1, env.session.player.shortcutSnapshot().size());

        // 幂等重设同值：零帧。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp01"));
        assertNull(env.channel.readOutbound(), "幂等设置零帧");

        // 替换 index=12：0x12 删旧行(行0) + 0x11 增新行。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp04"));
        frames = env.drain();
        assertEquals(2, frames.size(), "替换 = 删旧 + 增新");
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(0x11, frames.get(1)[0]);
        var snapshot = env.session.player.shortcutSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals("CS_jh_sfgp04", snapshot.get(0).id());

        // 201 按行号删：一帧 0x12，内存清空；再删同行静默。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 0));
        frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x12, frames.get(0)[0]);
        assertTrue(env.session.player.shortcutSnapshot().isEmpty());
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 0));
        assertNull(env.channel.readOutbound(), "缺行删除零帧");
    }

    @Test
    void invalidSetShortcutRejectedWithoutFrames() {
        Env env = new Env();
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, -3, "skill", "CS_jh_sfgp01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "", "CS_jh_sfgp01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", ""));
        assertNull(env.channel.readOutbound(), "非法参数零帧");
        assertTrue(env.session.player.shortcutSnapshot().isEmpty());
    }
}
