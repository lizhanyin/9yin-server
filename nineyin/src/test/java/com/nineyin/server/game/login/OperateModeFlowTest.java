package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * C2S 99 操作模式链路（CLIENT_CUSTOMMSG_SET_OPERATEMODE）：
 * `[99, mode]`（0=3D WASD / 1=2.5D 点击）→ 回推 0x10 OperateMode（索引 223，
 * type3 int32）+ role_keybinds.operate_mode 落库（store 缺席仅跳过持久化）；
 * 登录 post-spawn pushSavedOperateMode 回放（无存档零帧）。
 */
class OperateModeFlowTest {

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

    /** 0x1E 直达 [99, mode]。 */
    private static CustomC2S.Message message(int mode) {
        PacketWriter writer = PacketWriter.ofCapacity(16);
        writer.writeU8(0x1E).writeU16(2);
        writer.writeU8(2).writeU32(99);
        writer.writeU8(2).writeU32(mode);
        return CustomC2S.parseDirect(writer.toBytes());
    }

    @Test
    void frameShapeMatchesOfficialTable() {
        byte[] frame = LoginFlow.operateModeFrame(1);
        // 0x10 | u8 isView=0 | u32 obj=1 | u32 owner=1 | u16 count=1 | u16 idx=223 | u32 mode
        assertEquals(0x10, frame[0] & 0xFF);
        assertEquals(0, frame[1] & 0xFF);
        assertEquals(1, (frame[2] & 0xFF) | (frame[3] & 0xFF) << 8
                | (frame[4] & 0xFF) << 16 | (frame[5] & 0xFF) << 24);
        assertEquals(1, (frame[6] & 0xFF) | (frame[7] & 0xFF) << 8
                | (frame[8] & 0xFF) << 16 | (frame[9] & 0xFF) << 24);
        assertEquals(1, (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8);
        assertEquals(223, (frame[12] & 0xFF) | (frame[13] & 0xFF) << 8, "官服 0x09 表索引 223");
        assertEquals(1, frame[14], "mode=1 低字节");
        assertEquals(0, frame[15], "高位零");
        assertEquals(0, frame[16]);
        assertEquals(0, frame[17]);
        assertEquals(18, frame.length);
    }

    @Test
    void setModeEchoesFrameAndInvalidRejected() {
        Env env = new Env();

        // 合法 0：一帧 0x10，idx223 值 0。
        env.flow.handleSetOperateMode(new Connection(env.channel), env.session, message(0));
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x10, frames.get(0)[0] & 0xFF);
        int idx = (frames.get(0)[12] & 0xFF) | (frames.get(0)[13] & 0xFF) << 8;
        assertEquals(223, idx);
        int value = (frames.get(0)[14] & 0xFF) | (frames.get(0)[15] & 0xFF) << 8
                | (frames.get(0)[16] & 0xFF) << 16 | (frames.get(0)[17] & 0xFF) << 24;
        assertEquals(0, value);

        // 合法 1：同样一帧，值 1。
        env.flow.handleSetOperateMode(new Connection(env.channel), env.session, message(1));
        frames = env.drain();
        assertEquals(1, frames.size());
        value = (frames.get(0)[14] & 0xFF) | (frames.get(0)[15] & 0xFF) << 8
                | (frames.get(0)[16] & 0xFF) << 16 | (frames.get(0)[17] & 0xFF) << 24;
        assertEquals(1, value);

        // 非法 2：静默拒（零帧）。
        env.flow.handleSetOperateMode(new Connection(env.channel), env.session, message(2));
        assertNull(env.channel.readOutbound(), "非法模式零帧");

        // 缺参：零帧。
        PacketWriter writer = PacketWriter.ofCapacity(8);
        writer.writeU8(0x1E).writeU16(1).writeU8(2).writeU32(99);
        env.flow.handleSetOperateMode(new Connection(env.channel), env.session,
                CustomC2S.parseDirect(writer.toBytes()));
        assertNull(env.channel.readOutbound(), "缺参零帧");
    }

    @Test
    void pushWithoutSavedModeIsSilent() {
        Env env = new Env();
        // inventoryStore 缺席（单测无 MySQL）：loadOperateMode 回 null → 零帧。
        env.flow.pushSavedOperateMode(new Connection(env.channel), env.session);
        assertNull(env.channel.readOutbound());
    }
}
