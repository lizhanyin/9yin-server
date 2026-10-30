package com.nineyin.game;

import com.nineyin.db.JsonRoleStore;
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
        RoleStore roleStore = new JsonRoleStore(tempDir.resolve("roles.json"));
        FxOpcodeRouter router = new FxOpcodeRouter();
        SceneManager sceneManager = new SceneManager(share);
        LoginFlow loginFlow = new LoginFlow(roleStore);
        SceneFlow sceneFlow = new SceneFlow(sceneManager, roleStore, new QinggongCatalog());
        loginFlow.addReadyHook(sceneFlow::sendSceneNpcs);
        loginFlow.register(router);
        sceneFlow.register(router);
        com.nineyin.shop.ShopRepository shops = new com.nineyin.shop.ShopRepository();
        Path shopIni = tempDir.resolve("shop.ini");
        Files.writeString(shopIni, """
                [shop_test]
                NameID=测试商店
                Type=0
                1=tool_gold_a,5,1,500,0,1,0,0,0,,
                2=silver_b,1,2,10000,0,1,1,0,0,,
                """, GBK);
        shops.load(shopIni);
        new SelectFlow(sceneManager, shops, sceneFlow.switcher())
                .register(router);
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
                "编号\t脚本\t名字\t资源\tShopID\nID\tscript\tName\tResource\tShopID\n"
                        + "npc_qd_001\tcommonnpc\t张铁匠\tworldnpc\\qd_tiejiang\t\n"
                        + "npc_qd_002\tcommonnpc\t掌柜\tworldnpc\\qd_zhanggui\tshop_test\n",
                GBK);
        Path creator = share.resolve("creator/npc_creator/born04_qiandengzhen");
        Files.createDirectories(creator);
        Files.writeString(creator.resolve("file.ini"), "1=FuncNpc.xml\n", GBK);
        Files.writeString(creator.resolve("FuncNpc.xml"), """
                <root>
                  <item x="874" y="1" z="746" ay="0" id="npc_qd_001" BornAction="talk"/>
                  <item x="876" y="1" z="748" ay="0" id="npc_qd_002" BornAction="talk"/>
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
    void selectClearsAndReestablishes() {        // 选中 NPC 10001：0x1D + LastObject + SelectState=1
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
        // func = 600000000（离开）→ 0x1E 帧对话关闭 + 清选区 5 帧
        send(selectPacket(2, 10001, 1, 600000000));
        List<byte[]> out = drainOutbound();
        assertEquals(6, out.size());
        assertEquals(0x1E, op(out.get(0)), "cinematic close frame");
        // 清选区：LastObject 对象形态清零 + AutoSelect + CurSkillTarget + SelectState + ClearMenu
        assertEquals(0x10, op(out.get(1)));
        assertEquals(413, leU16(out.get(1), 12));
        assertEquals(0, leU32(out.get(1), 14));
        assertEquals(0x1D, op(out.get(5)));
    }

    /** §3.19 实机帧序：0x16 清除 → 0x15 四头属性 → 0x18 行 6 属性（价格槽=107+capital、110=99）。 */
    @Test
    void shopOpenReferenceFrameSequence() {
        loginUntilReady();
        // 点开商店 NPC（npc_qd_002，oid=10002）：先出帧对话菜单
        send(selectPacket(1, 10002, 1, 0));
        drainOutbound();
        send(selectPacket(2, 10002, 1, 805000000));
        List<byte[]> out = drainOutbound();

        byte[] clear = out.stream().filter(f -> op(f) == 0x16).findFirst().orElse(null);
        byte[] create = out.stream().filter(f -> op(f) == 0x15).findFirst().orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(clear, "0x16 视图清除帧");
        org.junit.jupiter.api.Assertions.assertEquals(3, clear.length);
        assertEquals(61, leU16(clear, 1));
        org.junit.jupiter.api.Assertions.assertNotNull(create, "0x15 视图创建帧");
        org.junit.jupiter.api.Assertions.assertTrue(out.indexOf(clear) < out.indexOf(create),
                "0x16 在 0x15 之前");
        assertEquals(61, leU16(create, 1));
        assertEquals(100, leU16(create, 3));
        assertEquals(4, leU16(create, 5), "四头属性（ShopID/页大小/页数/类型）");

        List<byte[]> rows = out.stream().filter(
                f -> op(f) == 0x18 && leU16(f, 1) == 61).toList();
        assertEquals(2, rows.size());
        // 行 1：capital=1 金币品 → 价格在 108（SellPrice1）
        byte[] gold = rows.get(0);
        assertEquals(6, leU16(gold, 5), "6 属性行（三价格槽全量+MaxAmount）");
        assertEquals(1439, propAt(gold, 0), "首属性 ConfigID（Java 表 1439）");
        assertEquals(0, propVal(gold, 107));
        assertEquals(500, propVal(gold, 108), "capital1 价格落 SellPrice1");
        assertEquals(0, propVal(gold, 109));
        assertEquals(99, propVal(gold, 1893), "MaxAmount=99");
        // 行 2：capital=2 碎银品 → 价格在 109（SellPrice2）
        byte[] silver = rows.get(1);
        assertEquals(0, propVal(silver, 108));
        assertEquals(10000, propVal(silver, 109), "capital2 价格落 SellPrice2");
        assertEquals(99, propVal(silver, 1893));
    }

    /** 行内按属性索引线性找值（i32；字符串属性跳过）。 */
    private static int propVal(byte[] row, int want) {
        int p = 7;
        while (p + 2 <= row.length) {
            int idx = leU16(row, p);
            if (idx == want) {
                return leU32(row, p + 2);
            }
            p += 2;
            if (idx == 1439 || idx == 2217 || idx == 1891) {   // 字符串属性
                p += 4 + leU32(row, p);
            } else {
                p += 4;
            }
        }
        return -1;
    }

    private static int propAt(byte[] row, int n) {
        return leU16(row, 7 + n * 6);
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
