package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.protocol.PacketWriter;
import com.nineyin.scene.SceneManager;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxServerHandler;
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 0x07 选中系统：选 NPC / ESC 清除 / func 离开 / 移动 28m 超时清除。 */
class SelectFlowTest {

    private static final Charset GBK = Charset.forName("GBK");
    private final FxKey key = FxKey.initial();

    @TempDir
    Path tempDir;

    private EmbeddedChannel channel;

    @BeforeEach
    void setup() throws Exception {
        Path share = buildShareRoot();
        RoleStore roleStore = new RoleStore(tempDir.resolve("roles.json"));
        FxOpcodeRouter router = new FxOpcodeRouter();
        SceneManager sceneManager = new SceneManager(share);
        LoginFlow loginFlow = new LoginFlow(roleStore);
        SceneFlow sceneFlow = new SceneFlow(sceneManager, roleStore);
        loginFlow.addReadyHook(sceneFlow::sendSceneNpcs);
        loginFlow.register(router);
        sceneFlow.register(router);
        new SelectFlow(sceneManager).register(router);
        FxConnection connection = new FxConnection();
        channel = new EmbeddedChannel(
                new FxFrameDecoder(connection),
                new FxFrameEncoder(connection),
                new FxServerHandler(router, connection));
    }

    /** 完整登录到 Ready（移动同步依赖 LoginSession 与 ready 后的 world 引用）。 */
    private void loginUntilReady() {
        PacketWriter login = new PacketWriter().u8(0x02);
        while (login.size() < 0x15) {
            login.u8(0);
        }
        send(login.u32(32).raw(new byte[32]).toByteArray());
        drainOutbound();
        PacketWriter create = new PacketWriter().u8(0x05).u32(0);
        create.raw("测试侠".getBytes(StandardCharsets.UTF_16LE)).u16(0);
        while (create.size() < 0x49) {
            create.u8(0);
        }
        byte[] book = "book1".getBytes(StandardCharsets.UTF_8);
        create.u8(6).u32(book.length + 1).raw(book).u8(0);
        send(create.toByteArray());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();
    }

    private Path buildShareRoot() throws Exception {
        Path share = tempDir.resolve("share");
        Path npcconfig = share.resolve("npc/npcconfig/worldnpc");
        Files.createDirectories(npcconfig);
        Files.writeString(npcconfig.resolve("city_commonnpc.txt"),
                "编号\t脚本\t名字\t资源\nID\tscript\tName\tResource\n"
                        + "npc_qd_001\tcommonnpc\t张铁匠\tworldnpc\\qd_tiejiang\n",
                GBK);
        Path creator = share.resolve("creator/npc_creator/born04_qiandengzhen");
        Files.createDirectories(creator);
        Files.writeString(creator.resolve("file.ini"), "1=FuncNpc.xml\n", GBK);
        Files.writeString(creator.resolve("FuncNpc.xml"), """
                <root>
                  <item x="874" y="1" z="746" ay="0" id="npc_qd_001" BornAction="talk"/>
                </root>
                """, GBK);
        return share;
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

    private static int op(byte[] f) {
        return f[0] & 0xFF;
    }

    /** 0x07 选中包：33 字节，seq@17 oid@21 owner@25 func@29。 */
    private static byte[] selectPacket(int seq, int oid, int owner, int func) {
        PacketWriter w = new PacketWriter().u8(0x07);
        while (w.size() < 17) {
            w.u8(0);
        }
        w.u32(seq).u32(oid).u32(owner).u32(func);
        return w.toByteArray();
    }

    private static byte[] movePacket(float x, float y, float z) {
        PacketWriter w = new PacketWriter().u8(0x0A);
        while (w.size() < 0x26) {
            w.u8(0);
        }
        w.u8(4).f32(x).u8(4).f32(y).u8(4).f32(z).u8(4).f32(0);
        while (w.size() < 67) {
            w.u8(0);
        }
        return w.toByteArray();
    }

    @Test
    void selectClearsAndReestablishes() {
        // 选中 NPC 10001：0x1D + LastObject + SelectState=1
        send(selectPacket(1, 10001, 1, 0));
        List<byte[]> sel = drainOutbound();
        assertEquals(3, sel.size());
        assertEquals(0x1D, op(sel.get(0)));
        assertEquals(0x10, op(sel.get(1)));
        assertEquals(413, leU16(sel.get(1), 12));
        assertEquals(10001, leU32(sel.get(1), 14));
        assertEquals(0x10, op(sel.get(2)));
        assertEquals(1559, leU16(sel.get(2), 12)); // SelectState
        assertEquals(1, sel.get(2)[14]);

        // ESC（oid=0）：清选区 5 帧（LastObject 对象形态 / AutoSelect / CurSkillTarget / SelectState / ClearMenu）
        send(selectPacket(2, 0, 0, 0));
        List<byte[]> cleared = drainOutbound();
        assertEquals(5, cleared.size());
        assertEquals(0x10, op(cleared.get(0)));
        assertEquals(413, leU16(cleared.get(0), 12));
        assertEquals(0, leU32(cleared.get(0), 14));
        assertEquals(0x1D, op(cleared.get(4)));
    }

    @Test
    void menuLeaveFuncClearsSelection() {
        send(selectPacket(1, 10001, 1, 0));
        drainOutbound();
        // func = 600000000（离开类菜单动作）→ 清 LastObject
        send(selectPacket(2, 10001, 1, 600000000));
        List<byte[]> out = drainOutbound();
        // 选中帧（0x1D + LastObject + SelectState）+ 离开清选（LastObject=0）
        assertEquals(4, out.size());
        assertEquals(0x10, op(out.get(3)));
        assertEquals(413, leU16(out.get(3), 12));
        assertEquals(0, leU32(out.get(3), 14));
    }

    @Test
    void walkAwayClearsSelection() {
        loginUntilReady();
        send(selectPacket(1, 10001, 1, 0));
        drainOutbound();
        // NPC 在 (874,746)，玩家走到 40m 外 → 清选区（5 帧）
        send(movePacket(874 + 40, 1, 746));
        List<byte[]> out = drainOutbound().stream().filter(f -> op(f) != 0x20).toList();
        assertEquals(5, out.size());
        assertEquals(0x1D, op(out.get(4)));
    }

    private static int leU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
