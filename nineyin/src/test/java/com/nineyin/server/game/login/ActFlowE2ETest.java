package com.nineyin.server.game.login;

import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.handler.GameServerHandler;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameCodec;
import com.nineyin.server.transport.FrameDecoder;
import com.nineyin.server.transport.FrameEncoder;
import com.nineyin.server.transport.XorKey;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.util.ServerPaths;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 临时诊断：真实 0x0A 信封路径的演武全链 E2E——打印服务端实际回帧的关键属性。 */
class ActFlowE2ETest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    /** 关注属性：索引 -> 名（0x09 官方表钉死）。 */
    private static final Map<Integer, String> WANTED = new HashMap<>();

    static {
        int[] idx = {463, 758, 759, 760, 764, 773, 774, 775, 777, 778, 790, 791, 795, 796, 797, 798};
        String[] names = {"LogicState", "FacultyState", "FacultyStyle", "FacultyName",
                "CurFillValue", "FillSpeed", "NorFacultyAdd", "TeamFacultyState",
                "TeamFacultyValue", "TeamFacultyLast", "ActDayValue", "ActCostSuiYin",
                "ActDayValueLast", "ActDayValueUsed", "ActDayValueMax", "Faculty"};
        for (int i = 0; i < idx.length; i++) {
            WANTED.put(idx[i], names[i]);
        }
    }

    @BeforeAll
    static void needsGoResources() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/item")),
                "Go 仓库 share 资源不可用，跳过");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    private static void send(EmbeddedChannel channel, byte[] plain) {
        channel.writeInbound(Unpooled.wrappedBuffer(FrameCodec.encode(plain, XorKey.initial())));
    }

    private static List<byte[]> drain(EmbeddedChannel channel) {
        List<byte[]> frames = new ArrayList<>();
        io.netty.buffer.ByteBuf buffer;
        while ((buffer = channel.readOutbound()) != null) {
            byte[] wire = new byte[buffer.readableBytes()];
            buffer.readBytes(wire);
            buffer.release();
            frames.add(FrameCodec.decode(wire, XorKey.initial()));
        }
        return frames;
    }

    private static byte[] loginFrame() {
        PacketWriter w = PacketWriter.ofCapacity(96);
        w.writeU8(0x02);
        for (int i = 0; i < 20; i++) {
            w.writeU8(0);
        }
        byte[] account = "probeactAAAAAAAA".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] secret = "probesecretAAAAA".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] login = "probeloginstrAAA".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        w.writeU32(account.length).writeBytes(account);
        w.writeU32(secret.length).writeBytes(secret);
        w.writeU32(1);
        w.writeU32(login.length).writeBytes(login);
        w.writeU32(1);
        return w.toBytes();
    }

    private static byte[] createRoleFrame(String name) {
        PacketWriter w = PacketWriter.ofCapacity(0x60);
        w.writeU8(0x05).writeU32(0);
        w.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        w.writeU16(0);
        while (w.length() < 0x49) {
            w.writeU8(0);
        }
        byte[] book = "book1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        w.writeU8(6).writeU32(book.length + 1).writeBytes(book).writeU8(0);
        return w.toBytes();
    }

    /** 0x0A 信封（真实客户端路径）：21B 头 + u16 个数 + int 值。 */
    private static byte[] activity(int messageId, int... intValues) {
        PacketWriter w = PacketWriter.ofCapacity(64);
        w.writeU8(0x0A);
        for (int i = 0; i < 20; i++) {
            w.writeU8(0);
        }
        w.writeU16(1 + intValues.length);
        w.writeU8(2).writeU32(messageId & 0xFFFFFFFFL);
        for (int v : intValues) {
            w.writeU8(2).writeU32(v & 0xFFFFFFFFL);
        }
        return w.toBytes();
    }

    /** 打卡真实形态：card/payment 为 f64。 */
    private static byte[] activityPay(int card, int payment) {
        PacketWriter w = PacketWriter.ofCapacity(64);
        w.writeU8(0x0A);
        for (int i = 0; i < 20; i++) {
            w.writeU8(0);
        }
        w.writeU16(4);
        w.writeU8(2).writeU32(1006);
        w.writeU8(2).writeU32(23);
        w.writeU8(5).writeU64(Double.doubleToRawLongBits((double) card));
        w.writeU8(5).writeU64(Double.doubleToRawLongBits((double) payment));
        return w.toBytes();
    }

    @Test
    void actFlowE2E(@TempDir Path tempDir) throws Exception {
        JsonRoleRepository repository = JsonRoleRepository.open(tempDir.resolve("roles.json"));
        RoleStoreFacade store = new RoleStoreFacade(repository);
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));
        LoginFlow flow = new LoginFlow(store, faculty, new GmHub(), java.time.Clock.systemDefaultZone(),
                null, new LoginFlowTest.ManualScheduler());
        Connection connection = new Connection(null);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(new FrameDecoder(new Connection(channel)));
        channel.pipeline().addLast(new FrameEncoder(connection));
        channel.pipeline().addLast(new GameServerHandler(flow, new Connection(channel)));

        send(channel, loginFrame());
        System.out.println("login -> " + drain(channel).size());
        send(channel, new byte[]{0x03});
        drain(channel);
        send(channel, createRoleFrame("金镶玉"));
        drain(channel);
        byte[] choose = new byte[0x54];
        choose[0] = 0x04;
        send(channel, choose);
        System.out.println("choose -> " + drain(channel).size());

        step(channel, "1006/24 refresh", activity(1006, 24));
        step(channel, "1006/21 begin", activity(1006, 21));
        step(channel, "1006/23 pay c0 suiyin", activityPay(0, 1));
        step(channel, "1006/22 exit", activity(1006, 22));
        channel.close();
    }

    private static void step(EmbeddedChannel channel, String label, byte[] plain) {
        send(channel, plain);
        List<byte[]> replies = drain(channel);
        System.out.println("=== " + label + " -> " + replies.size() + " frames");
        for (byte[] f : replies) {
            int op = f[0] & 0xFF;
            if (op == 0x10) {
                int n = (f[10] & 0xFF) | ((f[11] & 0xFF) << 8);
                System.out.printf("  0x10 len=%d n=%d %s%n", f.length, n, keyProps(f));
            } else if (op == 0x1E) {
                System.out.printf("  0x1E %s%n", hex(f, Math.min(f.length, 24)));
            } else if (op == 0x18) {
                System.out.printf("  0x18 view=%d len=%d%n",
                        (f[1] & 0xFF) | ((f[2] & 0xFF) << 8), f.length);
            } else {
                System.out.printf("  op=0x%02X len=%d%n", op, f.length);
            }
        }
    }

    private static String hex(byte[] f, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X ", f[i]));
        }
        return sb.toString();
    }

    /** 解 0x10 关注属性（全部 int32/byte 线型，按官方表类型跳越其余）。 */
    private static String keyProps(byte[] f) {
        Map<Integer, Integer> types = loadTypes();
        int n = (f[10] & 0xFF) | ((f[11] & 0xFF) << 8);
        int off = 12;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && off + 2 <= f.length; i++) {
            int idx = (f[off] & 0xFF) | ((f[off + 1] & 0xFF) << 8);
            off += 2;
            int type = types.getOrDefault(idx, 3);
            String value;
            switch (type) {
                case 1 -> {
                    value = String.valueOf(f[off]);
                    off += 1;
                }
                case 2 -> {
                    value = String.valueOf((f[off] & 0xFF) | ((f[off + 1] & 0xFF) << 8));
                    off += 2;
                }
                case 3 -> {
                    value = String.valueOf((f[off]) | ((f[off + 1] & 0xFF) << 8)
                            | ((f[off + 2] & 0xFF) << 16) | ((f[off + 3] & 0xFF) << 24));
                    off += 4;
                }
                case 4, 9 -> {
                    value = "(i64)";
                    off += 8;
                }
                case 5 -> {
                    value = "(f32)";
                    off += 4;
                }
                case 7, 8 -> {
                    int len = (f[off]) | ((f[off + 1] & 0xFF) << 8)
                            | ((f[off + 2] & 0xFF) << 16) | ((f[off + 3] & 0xFF) << 24);
                    off += 4;
                    value = "(str" + len + ")";
                    off += len;
                }
                default -> {
                    value = "(?)";
                    off += 4;
                }
            }
            if (WANTED.containsKey(idx)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(WANTED.get(idx)).append('=').append(value);
            }
        }
        return sb.toString();
    }

    private static Map<Integer, Integer> loadTypes() {
        Map<Integer, Integer> types = new HashMap<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(Path.of(
                    "src/main/resources/nineyin/protocol/official_property_table.txt"))) {
                String[] parts = line.trim().split("\t");
                if (parts.length == 3) {
                    types.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[2].trim()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return types;
    }
}
