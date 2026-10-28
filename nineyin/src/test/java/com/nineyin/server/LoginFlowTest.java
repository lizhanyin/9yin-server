package com.nineyin.server;

import com.nineyin.auth.AccountKeys;
import com.nineyin.db.JsonRoleStore;
import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.protocol.PacketWriter;
import com.nineyin.transport.FxConnection;
import com.nineyin.transport.FxFrame;
import com.nineyin.transport.FxFrameAccumulator;
import com.nineyin.transport.FxFrameDecoder;
import com.nineyin.transport.FxFrameEncoder;
import com.nineyin.transport.FxKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EmbeddedChannel 全链路测试：真实管线（帧编解码 + 路由 + 登录状态机）。 */
class LoginFlowTest {

    private final FxKey key = FxKey.initial();

    @TempDir
    Path tempDir;

    private RoleStore roleStore;
    private EmbeddedChannel channel;
    private FxConnection connection;

    @BeforeEach
    void setup() {
        roleStore = new JsonRoleStore(tempDir.resolve("roles.json"));
        FxOpcodeRouter router = new FxOpcodeRouter();
        new LoginFlow(roleStore).register(router);
        connection = new FxConnection();
        channel = new EmbeddedChannel(
                new FxFrameDecoder(connection),
                new FxFrameEncoder(connection),
                new FxServerHandler(router, connection));
    }

    private void send(byte[] plain) {
        channel.writeInbound(Unpooled.wrappedBuffer(FxFrame.encode(plain, key)));
    }

    private List<byte[]> drainOutbound() {
        List<byte[]> frames = new ArrayList<>();
        FxFrameAccumulator acc = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            byte[] wire = new byte[buf.readableBytes()];
            buf.readBytes(wire);
            int pos = 0;
            while (pos < wire.length) {
                pos += acc.feed(wire, pos, wire.length - pos);
                if (acc.hasCompleteFrame()) {
                    frames.add(acc.takeFrame(key));
                }
            }
        }
        return frames;
    }

    private static int op(byte[] frame) {
        return frame[0] & 0xFF;
    }

    private static byte[] loginPacket() {
        byte[] blob = new byte[32];
        blob[0] = 0x55;
        PacketWriter w = new PacketWriter().u8(0x02);
        while (w.size() < 0x15) {
            w.u8(0);
        }
        return w.u32(32).raw(blob).toByteArray();
    }

    private static byte[] createRolePacket(String name, List<String> appearance) {
        PacketWriter w = new PacketWriter().u8(0x05).u32(0);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
        w.raw(nameBytes).u16(0);
        while (w.size() < 0x49) {
            w.u8(0);
        }
        for (String s : appearance) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            w.u8(6).u32(b.length + 1).raw(b).u8(0);
        }
        return w.toByteArray();
    }

    @Test
    void loginWithNoRoleRepliesEmptyRoleList() {
        send(loginPacket());
        List<byte[]> out = drainOutbound();
        assertEquals(1, out.size());
        assertEquals(0x04, op(out.get(0)));
        assertEquals(0x29, out.get(0).length);
        assertEquals(0, out.get(0)[0x25], "role count must be 0");
    }

    @Test
    void worldInfoRepliesOpenBooks() {
        send(new byte[]{0x03});
        List<byte[]> out = drainOutbound();
        assertEquals(1, out.size());
        byte[] f = out.get(0);
        assertEquals(0x05, f[0]);
        assertEquals(0, f[1]);
        String text = new String(f, 3, f.length - 5, StandardCharsets.UTF_16LE);
        assertEquals("0,book1,0,book2,0,book4,0,book5,0,book8,0,0", text);
    }

    @Test
    void fullCreateChooseReadyFlow() {
        // 1) 登录（无角色）
        String accountKey = AccountKeys.parseLoginAccountKey(loginPacket());
        send(loginPacket());
        assertEquals(1, drainOutbound().size());

        // 2) 建角：book4（成都）
        List<String> appearance = List.of("book4",
                "icon\\players\\boy01_create.png", "3333", "0",
                "b_hair1", "cloth_b0001", "pants_b0001", "shoes_b0001");
        send(createRolePacket("测试侠客", appearance));
        List<byte[]> createOut = drainOutbound();
        assertEquals(1, createOut.size());
        assertEquals(0x04, op(createOut.get(0)));
        assertEquals(1, createOut.get(0)[0x25], "role count must be 1");

        // 角色已按账号键落库，出生点为成都 city05
        assertTrue(roleStore.exists(accountKey));
        assertEquals("测试侠客", roleStore.get(accountKey).getName());
        assertEquals("city05", roleStore.get(accountKey).getSceneResource());

        // 3) 选角 → 进场景链：0x09 属性表 / 0x0A 记录表 / 8 背包视图(0x15) / 0x0B / 0x0D / 0x1F / 权威 0x10
        send(new byte[]{0x04});
        List<byte[]> enter = drainOutbound();
        assertEquals(14, enter.size());
        assertEquals(0x09, op(enter.get(0)));
        assertEquals(2424, (enter.get(0)[1] & 0xFF) | ((enter.get(0)[2] & 0xFF) << 8),
                "official property table entry count");
        assertEquals(0x0A, op(enter.get(1)));
        assertEquals(116, enter.get(1).length);
        assertEquals(0x0B, op(enter.get(10)));
        byte[] entryScene = enter.get(10);
        // 属性 1439 ConfigID 从头 15 字节之后：u16 索引 + u32 长度 + 串
        assertEquals(1439, (entryScene[15] & 0xFF) | ((entryScene[16] & 0xFF) << 8));
        assertEquals(0x0D, op(enter.get(11)));
        assertTrue(enter.get(11).length > 0x3F, "AddObject must carry property body");
        assertEquals(0x1F, op(enter.get(12)));
        assertEquals(25, enter.get(12).length);
        assertEquals(0x10, op(enter.get(13)), "authoritative vital batch");
        // 8 个背包视图（121/2/123/125 容量 132；122/3/124/126 容量 16）
        List<byte[]> bagViews = enter.stream().filter(f -> op(f) == 0x15).toList();
        assertEquals(8, bagViews.size());

        // 4) ClientReady → 清菜单 + 清选区(0x10x4 + 0x1D) + UI bootstrap
        send(new byte[]{0x09});
        List<byte[]> ready = drainOutbound();
        assertTrue(ready.size() >= 1700, "expected menu-clear + selection-clear + bootstrap frames, got "
                + ready.size());
        assertEquals(0x1D, op(ready.get(0)));
        assertEquals(0x10, op(ready.get(1)));
        // bootstrap：2 个 0x38（时钟 + 技能容器后追加）；
        // 0x11 行 = 18 轻功分类 + 20 已接任务 + ~1594 Task_Completed 位图行 + 1 打坐 + 15 起始快捷键
        long timeSync = ready.stream().filter(f -> op(f) == 0x38).count();
        long recordRows = ready.stream().filter(f -> op(f) == 0x11).count();
        assertEquals(2, timeSync);
        assertTrue(recordRows >= 1600, "expected 1600+ record rows, got " + recordRows);
        // 视图：轻功 View46 + 内功 View43 + 技能 View40（0x18 = 70 轻功 + 6 内功 + 8 技能）+ 开关(0x1E)
        assertEquals(3, ready.stream().filter(f -> op(f) == 0x15).count());
        assertEquals(84, ready.stream().filter(f -> op(f) == 0x18).count());
        assertEquals(1, ready.stream().filter(f -> op(f) == 0x1E).count());
    }

    @Test
    void realClientFirstFlightDecodesAndParses() throws Exception {
        byte[] wire;
        try (InputStream in = getClass().getResourceAsStream("/protocol/connect-first-flight.bin")) {
            assertNotNull(in);
            wire = in.readAllBytes();
        }
        FxFrameAccumulator acc = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        List<byte[]> frames = new ArrayList<>();
        int pos = 0;
        while (pos < wire.length) {
            int consumed = acc.feed(wire, pos, wire.length - pos);
            if (consumed == 0) {
                break;
            }
            pos += consumed;
            if (acc.hasCompleteFrame()) {
                frames.add(acc.takeFrame(key));
            }
        }
        assertEquals(1, frames.size(), "capture should contain exactly one login frame");
        byte[] login = frames.get(0);
        assertEquals(0x02, login[0]);
        String accountKey = AccountKeys.parseLoginAccountKey(login);
        assertTrue(accountKey.startsWith("acct:v1:") && !AccountKeys.UNKNOWN.equals(accountKey),
                "real capture must yield a valid account key, got " + accountKey);
    }

    @Test
    void encodeDecodeRoundTripOnEmbeddedChannel() {
        // 出站编码验证：通过管线发一个已知包，读出的线上字节应能还原
        byte[] plain = com.nineyin.protocol.S2CPackets.timeSync(0x11223344, 7, 0xAABBCCDDL);
        channel.writeOutbound(plain);
        ByteBuf buf = channel.readOutbound();
        assertNotNull(buf);
        byte[] wire = new byte[buf.readableBytes()];
        buf.readBytes(wire);
        assertEquals((byte) 0xEE, wire[wire.length - 2]);
        assertEquals((byte) 0xEE, wire[wire.length - 1]);
        FxFrameAccumulator acc = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        acc.feed(wire);
        assertArrayEquals(plain, acc.takeFrame(key));
    }
}
