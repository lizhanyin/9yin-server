package com.nineyin.game;

import com.nineyin.auth.AccountKeys;
import com.nineyin.db.JsonRoleStore;
import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景同步全链路：登录→建角(book1 千灯镇 fixture)→进场景→NPC 下发/巡逻/打坐/快捷键/移动。 */
class SceneFlowTest {

    private static final Charset GBK = Charset.forName("GBK");
    private final FxKey key = FxKey.initial();

    @TempDir
    Path tempDir;

    private RoleStore roleStore;
    private EmbeddedChannel channel;

    @BeforeEach
    void setup() throws Exception {
        Path share = buildShareRoot();
        roleStore = new JsonRoleStore(tempDir.resolve("roles.json"));
        FxOpcodeRouter router = new FxOpcodeRouter();
        LoginFlow loginFlow = new LoginFlow(roleStore);
        SceneFlow sceneFlow = new SceneFlow(new SceneManager(share), roleStore, new QinggongCatalog());
        loginFlow.addReadyHook(sceneFlow::sendSceneNpcs);
        loginFlow.register(router);
        sceneFlow.register(router);
        FxConnection connection = new FxConnection();
        channel = new EmbeddedChannel(
                new FxFrameDecoder(connection),
                new FxFrameEncoder(connection),
                new FxServerHandler(router, connection));
    }

    /** book1 出生点 (874.9, -32.4, 746.7) 附近的 fixture 场景。 */
    private Path buildShareRoot() throws Exception {
        Path share = tempDir.resolve("share");
        Path npcconfig = share.resolve("npc/npcconfig/worldnpc");
        Files.createDirectories(npcconfig);
        Files.writeString(npcconfig.resolve("city_commonnpc.txt"),
                "编号\t脚本\t名字\t资源\nID\tscript\tName\tResource\n"
                        + "npc_qd_001\tcommonnpc\t张铁匠\tworldnpc\\qd_tiejiang\n"
                        + "npc_qd_002\tcommonnpc\t李药商\tworldnpc\\qd_yaoshang\n"
                        + "mon_test_001\tattacknpc\t山贼\tworldnpc\\qd_shanzei\n"
                        + "hd_act_001\tcommonnpc\t活动怪\tworldnpc\\act\n",
                GBK);
        Path creator = share.resolve("creator/npc_creator/born04_qiandengzhen");
        Files.createDirectories(creator);
        Files.writeString(creator.resolve("file.ini"), "1=FuncNpc.xml\n", GBK);
        Files.writeString(creator.resolve("FuncNpc.xml"), """
                <root>
                  <item x="874" y="1" z="746" ay="0" id="npc_qd_001" BornAction="talk"/>
                  <item x="880" y="1" z="750" id="npc_qd_002" PathId="AI_qd_001"/>
                  <item x="900" y="1" z="770" id="mon_test_001" PathId="AI_qd_001"/>
                  <item x="875" y="1" z="747" id="hd_act_001"/>
                </root>
                """, GBK);
        Path mappath = share.resolve("creator/mappath");
        Files.createDirectories(mappath);
        Files.writeString(mappath.resolve("born04_npc.ini"), """
                [AI_qd_001]
                r=,880,1,750,0,200
                r=,880.6,1,750.6,0,200
                r=,881.2,1,751.2,0,200
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

    private static byte[] loginPacket() {
        byte[] blob = new byte[32];
        PacketWriter w = new PacketWriter().u8(0x02);
        while (w.size() < 0x15) {
            w.u8(0);
        }
        return w.u32(32).raw(blob).toByteArray();
    }

    private static byte[] createRolePacket() {
        PacketWriter w = new PacketWriter().u8(0x05).u32(0);
        w.raw("行走江湖".getBytes(StandardCharsets.UTF_16LE)).u16(0);
        while (w.size() < 0x49) {
            w.u8(0);
        }
        for (String s : List.of("book1")) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            w.u8(6).u32(b.length + 1).raw(b).u8(0);
        }
        return w.toByteArray();
    }

    /** 67 字节移动位置包：0x26 起连续 4 组 (类型 4 + f32)。 */
    private static byte[] movePacket(float x, float y, float z, float orient) {
        PacketWriter w = new PacketWriter().u8(0x0A);
        while (w.size() < 0x26) {
            w.u8(0);
        }
        w.u8(4).f32(x).u8(4).f32(y).u8(4).f32(z).u8(4).f32(orient);
        while (w.size() < 67) {
            w.u8(0);
        }
        return w.toByteArray();
    }

    /** 0x0A 自定义消息（21 字节头 + 类型化值）。 */
    private static PacketWriter custom() {
        PacketWriter w = new PacketWriter().u8(0x0A);
        while (w.size() < 21) {
            w.u8(0);
        }
        return w;
    }

    private static byte[] sitCmd(int cmd) {
        return custom().u16(2).u8(2).i32(240).u8(2).i32(cmd).toByteArray();
    }

    private static byte[] shortcutSet(int idx, String kind, String id) {
        byte[] k = kind.getBytes(StandardCharsets.UTF_8);
        byte[] i = id.getBytes(StandardCharsets.UTF_8);
        return custom().u16(4)
                .u8(2).i32(200)
                .u8(2).i32(idx)
                .u8(6).u32(k.length + 1).raw(k).u8(0)
                .u8(6).u32(i.length + 1).raw(i).u8(0)
                .toByteArray();
    }

    @Test
    void fullSceneFlow() throws Exception {
        // 登录 → 建角 → 选角 → Ready
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();

        send(new byte[]{0x09});
        List<byte[]> ready = drainOutbound();
        // NPC 下发：3 个 NPC（活动怪被滤除），每个 AddObject(0x0D) + Location(0x1F)
        List<byte[]> adds = ready.stream().filter(f -> op(f) == 0x0D).toList();
        List<byte[]> locs = ready.stream().filter(f -> op(f) == 0x1F).toList();
        assertEquals(3, adds.size());
        assertEquals(3, locs.size());
        // 第一个 AddObject：oid=10001，Type 属性（181）=4
        byte[] first = adds.get(0);
        assertEquals(10001, leU32(first, 1));
        // 属性区从 0x3F 起：u16 181 + byte 4
        int p = 0x3F;
        assertEquals(181, (first[p] & 0xFF) | ((first[p + 1] & 0xFF) << 8));
        assertEquals(4, first[p + 2]);
        // 活动怪不在下发列表
        for (byte[] a : adds) {
            int oid = leU32(a, 1);
            assertTrue(oid <= 10003, "hd_act_001 must be filtered, got oid " + oid);
        }

        // 打坐：240 cmd 1 → LogicState 102 + State "sitdown"；同一条 0x0A 还触发首轮巡逻广播 0x20×2
        send(sitCmd(1));
        List<byte[]> sitBatch = drainOutbound();
        List<byte[]> sit = sitBatch.stream().filter(f -> op(f) == 0x10).toList();
        assertEquals(2, sit.size());
        assertEquals(462, (sit.get(0)[12] & 0xFF) | ((sit.get(0)[13] & 0xFF) << 8));
        assertEquals(102, sit.get(0)[14]);
        assertEquals(183, (sit.get(1)[12] & 0xFF) | ((sit.get(1)[13] & 0xFF) << 8));
        assertTrue(containsUtf8(sit.get(1), "sitdown"));
        assertEquals(2, sitBatch.stream().filter(f -> op(f) == 0x20).count(),
                "两个巡逻 NPC 各广播一条 0x20");

        // 移动：取消打坐（LogicState 0 + stand）+ 位置落库（500ms 节流内不再广播巡逻）
        send(movePacket(875.5f, -31f, 748f, 0.5f));
        List<byte[]> afterMove = drainOutbound();
        assertTrue(afterMove.stream().anyMatch(f -> op(f) == 0x10
                && f[12] == (byte) 0xCE && f[13] == 0x01 && f[14] == 0), "sit must be cancelled");
        String accountKey = AccountKeys.parseLoginAccountKey(loginPacket());
        assertEquals(875.5f, roleStore.get(accountKey).getX(), 0.001f, "position must persist");

        // 到达确认：等腿到达后（单腿 travel 下限 700ms + wait 200ms）任意 0x0A 触发 0x1F
        Thread.sleep(1100);
        send(sitCmd(0)); // 任意 0x0A
        List<byte[]> afterArrive = drainOutbound();
        assertTrue(afterArrive.stream().anyMatch(f -> op(f) == 0x1F),
                "arrive confirm 0x1F expected");

        // 快捷键：200 设 idx 5 → 0x11 增行（权威行在 actor 上：打坐 1 + 心佛掌 7 + 太极 8 + 本次 1）
        send(shortcutSet(5, "skill", "CS_jh_xfz01"));
        List<byte[]> sc = drainOutbound();
        assertTrue(sc.stream().anyMatch(f -> op(f) == 0x11 && containsUtf8(f, "CS_jh_xfz01")),
                "shortcut add row expected");
        LoginSession login = channel.attr(LoginFlow.SESSION).get();
        assertEquals(17, login.actor.shortcuts().size());
    }

    @Test
    void termsSign819MarksAgreedAndPushesProperties() throws Exception {
        // 登录 → 建角 → 选角 → Ready（819 在进场景后由 dispatchCustom 处理）
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();

        String accountKey = AccountKeys.parseLoginAccountKey(loginPacket());
        assertEquals(0, roleStore.termsState(accountKey)[0], "登录初始应为未同意");

        // 首次 819：落库 agreed=1 + at>0，并回推属性 269（签署时间）/270（签署标志）
        send(custom().u16(1).u8(2).i32(819).toByteArray());
        List<byte[]> out = drainOutbound();
        long[] t = roleStore.termsState(accountKey);
        assertEquals(1, t[0]);
        assertTrue(t[1] > 0, "同意时间必须记录");
        assertTrue(out.stream().anyMatch(f -> op(f) == 0x10
                && f[12] == 0x0D && f[13] == 0x01), "应回推属性 269 签署时间");
        assertTrue(out.stream().anyMatch(f -> op(f) == 0x10
                && f[12] == 0x0E && f[13] == 0x01), "应回推属性 270 签署标志");

        // 重复 819（客户端每分钟重弹再点）：幂等，时间不刷新
        send(custom().u16(1).u8(2).i32(819).toByteArray());
        drainOutbound();
        assertEquals(t[1], roleStore.termsState(accountKey)[1], "重复同意不得刷新时间");
    }

    private static boolean containsUtf8(byte[] frame, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= frame.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (frame[i + j] != b[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
