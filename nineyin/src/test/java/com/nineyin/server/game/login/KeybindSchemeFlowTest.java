package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * C2S 195 键位方案上传链路（引擎 ShortcutKey:EndEdit，bak 抓包 12173/1578 双帧钉死）：
 * `[195, scheme, str]`（0=当前/1=方案一/2=方案二）→ 回推 0x10 属性帧
 * （ShortcutKey=198/ShortcutKey1=199/ShortcutKey2=200，type7，串尾补空格同官服）
 * + role_keybinds 对应列落库（store 缺席仅跳过持久化）；非法 scheme/串静默零帧。
 */
class KeybindSchemeFlowTest {

    /** 抓包同款串形（658B 样本截短）。 */
    private static final String BIND = "1,87;2,83;3,81;4,69;5,65;6,68;7,32;8,90;";

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

    /** 0x1E 直达 [195, scheme, str]。 */
    private static CustomC2S.Message message(int scheme, String bind) {
        PacketWriter writer = PacketWriter.ofCapacity(96);
        byte[] data = bind.getBytes(StandardCharsets.UTF_8);
        writer.writeU8(0x1E).writeU16(3);
        writer.writeU8(2).writeU32(195);
        writer.writeU8(2).writeU32(scheme);
        writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        return CustomC2S.parseDirect(writer.toBytes());
    }

    /** 从 0x10 单属性帧解 (idx, str)。 */
    private static Object[] propString(byte[] frame) {
        assertEquals(0x10, frame[0] & 0xFF);
        assertEquals(1, (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8, "单属性帧");
        int idx = (frame[12] & 0xFF) | (frame[13] & 0xFF) << 8;
        int n = (frame[14] & 0xFF) | (frame[15] & 0xFF) << 8
                | (frame[16] & 0xFF) << 16 | (frame[17] & 0xFF) << 24;
        String text = new String(frame, 18, n - 1, StandardCharsets.UTF_8);
        return new Object[]{idx, text};
    }

    @Test
    void frameShapeMatchesOfficialTable() {
        Object[] got = propString(LoginFlow.keybindSchemeFrame(1, BIND));
        assertEquals(199, got[0], "方案一 = ShortcutKey1 索引 199");
        assertEquals(BIND + " ", got[1], "串尾补空格（u32=len+1 同官服 643 形态）");
        got = propString(LoginFlow.keybindSchemeFrame(2, BIND));
        assertEquals(200, got[0]);
        got = propString(LoginFlow.keybindSchemeFrame(0, BIND));
        assertEquals(198, got[0], "scheme 0 = 当前启用集 ShortcutKey");
    }

    @Test
    void uploadEchoesEachSchemeSlot() {
        Env env = new Env();

        env.flow.handleKeybindUpload(new Connection(env.channel), env.session, message(1, BIND));
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        Object[] got = propString(frames.get(0));
        assertEquals(199, got[0]);
        assertEquals(BIND + " ", got[1]);

        env.flow.handleKeybindUpload(new Connection(env.channel), env.session, message(2, BIND));
        got = propString(env.drain().get(0));
        assertEquals(200, got[0]);

        env.flow.handleKeybindUpload(new Connection(env.channel), env.session, message(0, BIND));
        got = propString(env.drain().get(0));
        assertEquals(198, got[0], "当前集上传回推 198");
    }

    @Test
    void invalidUploadsRejectedSilently() {
        Env env = new Env();

        // scheme 3：非法槽位。
        env.flow.handleKeybindUpload(new Connection(env.channel), env.session, message(3, BIND));
        assertNull(env.channel.readOutbound(), "非法 scheme 零帧");

        // 非法字符串：含控制字符（可打印 ASCII 之外）。
        env.flow.handleKeybindUpload(new Connection(env.channel), env.session,
                message(1, "1,87;\u0001evil;"));
        assertNull(env.channel.readOutbound(), "控制字符零帧");

        // 缺参。
        PacketWriter writer = PacketWriter.ofCapacity(16);
        writer.writeU8(0x1E).writeU16(1).writeU8(2).writeU32(195);
        env.flow.handleKeybindUpload(new Connection(env.channel), env.session,
                CustomC2S.parseDirect(writer.toBytes()));
        assertNull(env.channel.readOutbound(), "缺参零帧");
    }

    @Test
    void archiveCarriesKeybindDomainForEngineRead() {
        // 0x0D 属性档内嵌 198（无尾空格，官服 642 形态）+ 223：引擎建对象时即读到，
        // post-ready 0x10 补推对模式太晚（2026-11-04 真机"模式没推"根因）。
        PlayerActor actor = PlayerActor.create("紫泉", 0, Clock.systemUTC());
        Roles.Position position = new Roles.Position(1f, 2f, 3f, 0f);
        RoleVisual visual = new RoleVisual(0, "photo", "face", "hair", "cloth", "pants",
                "shoes", "action");
        byte[] frame = actor.addObject(position, visual,
                new PlayerActor.KeybindArchive(BIND, 1));

        assertEquals(0x0D, frame[0] & 0xFF);
        // 属性区从 0x3f 起（u16 索引 + 按表线型）。字节搜索：198=STRING（u32 len+NUL
        // 结尾，无尾空格）、223=INT32（u32 值）。
        boolean foundKey = false;
        boolean foundMode = false;
        for (int off = 0x3f; off + 6 <= frame.length; off++) {
            int idx = (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
            if (idx == 198) {
                int n = (frame[off + 2] & 0xFF) | (frame[off + 3] & 0xFF) << 8
                        | (frame[off + 4] & 0xFF) << 16 | (frame[off + 5] & 0xFF) << 24;
                if (n == BIND.length() + 1
                        && new String(frame, off + 6, n - 1, StandardCharsets.UTF_8).equals(BIND)) {
                    foundKey = true;
                }
            }
            if (idx == 223) {
                int value = (frame[off + 2] & 0xFF) | (frame[off + 3] & 0xFF) << 8
                        | (frame[off + 4] & 0xFF) << 16 | (frame[off + 5] & 0xFF) << 24;
                if (value == 1) {
                    foundMode = true;
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(foundKey, "0x0D 档含 ShortcutKey(198) 原串");
        org.junit.jupiter.api.Assertions.assertTrue(foundMode, "0x0D 档含 OperateMode(223)=1");
    }

    @Test
    void pushWithoutSavedSchemesIsSilent() {
        Env env = new Env();
        // inventoryStore 缺席（单测无 MySQL）：零帧。
        env.flow.pushSavedKeybindSchemes(new Connection(env.channel), env.session);
        assertNull(env.channel.readOutbound());
    }
}
