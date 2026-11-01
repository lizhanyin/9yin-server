package com.nineyin.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.game.avatar.neigong.FacultyStore;
import com.nineyin.game.ServerPaths;
import com.nineyin.role.JsonRoleRepository;
import com.nineyin.role.RoleStoreFacade;
import com.nineyin.transport.Connection;
import com.nineyin.transport.FrameCodec;
import com.nineyin.transport.FrameDecoder;
import com.nineyin.transport.FrameEncoder;
import com.nineyin.transport.XorKey;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 真实管线（FrameDecoder/FrameEncoder/GameServerHandler + LoginFlow）的登录全链路：
 * 登录 → 世界信息 → 创角 → 选角下发序列 → ready/activity，与 Go main.go 的帧序一致。
 */
class LoginFlowTest {

    private static final java.nio.file.Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    @BeforeAll
    static void needsGoResources() {
        Assumptions.assumeTrue(java.nio.file.Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/item")),
                "Go 仓库 share 资源不可用，跳过全链路（外观解析需要装备表）");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = LoginFlowTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    private static EmbeddedChannel newChannel(RoleStoreFacade store, FacultyStore faculty) {
        return newChannel(store, faculty, new com.nineyin.gm.GmHub());
    }

    private static EmbeddedChannel newChannel(RoleStoreFacade store, FacultyStore faculty, com.nineyin.gm.GmHub hub) {
        LoginFlow flow = new LoginFlow(store, faculty, hub, Clock.systemDefaultZone());
        Connection connection = new Connection(null);
        EmbeddedChannel channel = new EmbeddedChannel();
        // EmbeddedChannel 没有真 socket：Connection 持有的 channel 在 handler 写出侧由 pipeline 承担。
        channel.pipeline().addLast(new FrameDecoder(new Connection(channel)));
        channel.pipeline().addLast(new FrameEncoder(connection));
        channel.pipeline().addLast(new GameServerHandler(flow, new Connection(channel)));
        return channel;
    }

    private static void send(EmbeddedChannel channel, byte[] plain) {
        channel.writeInbound(Unpooled.wrappedBuffer(FrameCodec.encode(plain, XorKey.initial())));
    }

    /** 读出全部 wire 帧，解码为明文。 */
    private static List<byte[]> drain(EmbeddedChannel channel) {
        List<byte[]> frames = new ArrayList<>();
        ByteBuf buffer;
        while ((buffer = channel.readOutbound()) != null) {
            byte[] wire = new byte[buffer.readableBytes()];
            buffer.readBytes(wire);
            buffer.release();
            frames.add(FrameCodec.decode(wire, XorKey.initial()));
        }
        return frames;
    }

    private static byte[] loginFrame() throws Exception {
        return golden("login_first_flight_plain.bin");
    }

    private static byte[] createRoleFrame(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
        int appearanceOffset = 0x49;
        byte[] cloth = "cloth_b0001".getBytes(StandardCharsets.UTF_8);
        int frameSize = appearanceOffset + 1 + 4 + cloth.length + 1 + 16;
        byte[] frame = new byte[frameSize];
        frame[0] = 0x05;
        System.arraycopy(nameBytes, 0, frame, 5, Math.min(nameBytes.length, 72 - 2));
        int offset = appearanceOffset;
        frame[offset++] = 0x06;
        int size = cloth.length + 1;
        frame[offset++] = (byte) size;
        frame[offset++] = (byte) (size >>> 8);
        frame[offset++] = (byte) (size >>> 16);
        frame[offset++] = (byte) (size >>> 24);
        System.arraycopy(cloth, 0, frame, offset, cloth.length);
        offset += cloth.length;
        frame[offset] = 0;
        return frame;
    }

    @Test
    void fullCreateChooseReadyFlow(@TempDir Path tempDir) throws Exception {
        JsonRoleRepository repository = JsonRoleRepository.open(tempDir.resolve("roles.json"));
        RoleStoreFacade store = new RoleStoreFacade(repository);
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));
        EmbeddedChannel channel = newChannel(store, faculty);
        assertTrue(channel.isActive());

        // 0x02 登录（抓包明文）→ 无角色 0x04。
        send(channel, loginFrame());
        List<byte[]> replies = drain(channel);
        assertEquals(1, replies.size());
        assertEquals(0x04, replies.get(0)[0] & 0xFF);
        assertEquals(0x29, replies.get(0).length); // 无角色定长帧

        // 0x03 世界信息 → 0x05 worldInfo（golden 逐字节）。
        send(channel, new byte[]{0x03});
        replies = drain(channel);
        assertEquals(1, replies.size());
        assertArrayEquals(golden("world_info.bin"), replies.get(0));

        // 0x05 创角 → 0x04 带角色。
        send(channel, createRoleFrame("金镶玉"));
        replies = drain(channel);
        assertEquals(1, replies.size());
        assertEquals(0x04, replies.get(0)[0] & 0xFF);
        assertTrue(replies.get(0).length > 0x29);

        // 0x04 选角 → 下发序列（Go main.go:665-760 顺序）。
        byte[] choose = new byte[0x54];
        choose[0] = 0x04;
        send(channel, choose);
        replies = drain(channel);
        int i = 0;
        assertArrayEquals(golden("property_table.bin"), replies.get(i++));
        assertArrayEquals(golden("record_table.bin"), replies.get(i++));
        for (int viewId : new int[]{121, 2, 123, 125, 122, 3, 124, 126}) {
            assertArrayEquals(golden("view_create_" + viewId + ".bin"), replies.get(i++));
        }
        assertArrayEquals(golden("entry_scene.bin"), replies.get(i++));
        assertEquals(0x0D, replies.get(i)[0] & 0xFF);
        int addObjectIndex = i++;
        assertEquals(0x1F, replies.get(i)[0] & 0xFF);
        assertArrayEquals(golden("server_location.bin"), replies.get(i++));
        assertEquals(0x10, replies.get(i++)[0] & 0xFF);
        assertEquals(i, replies.size());
        // 0x0D：objectID/ownerID/坐标首部与 golden 相同（faculty 为运行时钟，buff 字符串不同）。
        byte[] addObject = replies.get(addObjectIndex);
        byte[] goldenObject = golden("player_add_object_default.bin");
        for (int offset = 0; offset < 0x3f; offset++) {
            assertEquals(goldenObject[offset], addObject[offset], "0x0D header@" + Integer.toHexString(offset));
        }

        // 0x09 ready → vital（一期 starter 链略，回 0x10）。
        send(channel, new byte[]{0x09});
        replies = drain(channel);
        assertEquals(1, replies.size());
        assertEquals(0x10, replies.get(0)[0] & 0xFF);

        // 0x0A activity → 无回包，状态机进 InWorld。
        send(channel, new byte[]{0x0A, 0x00});
        replies = drain(channel);
        assertTrue(replies.isEmpty());
        assertTrue(channel.isActive());

        // 非法转移（重复登录）→ 断连。
        send(channel, loginFrame());
        assertFalse(channel.isActive());

        // faculty.json 已初始化默认进度。
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("faculty.json")));
        // GM 面板会话：选角后挂快照，断连后摘除。
        com.nineyin.gm.GmHub hub = new com.nineyin.gm.GmHub();
        EmbeddedChannel gmChannel = newChannel(store, faculty, hub);
        send(gmChannel, loginFrame());
        drain(gmChannel);
        send(gmChannel, new byte[]{0x03});
        drain(gmChannel);
        byte[] chooseAgain = new byte[0x54];
        chooseAgain[0] = 0x04;
        send(gmChannel, chooseAgain);
        drain(gmChannel);
        assertEquals(1, hub.onlineCount());
        assertEquals("金镶玉", hub.snapshots().get(0).name);
        gmChannel.close();
        assertEquals(0, hub.onlineCount());
    }

    @Test
    void secondLoginFindsCreatedRole(@TempDir Path tempDir) throws Exception {
        RoleStoreFacade store = new RoleStoreFacade(JsonRoleRepository.open(tempDir.resolve("roles.json")));
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));

        EmbeddedChannel first = newChannel(store, faculty);
        send(first, loginFrame());
        drain(first);
        send(first, new byte[]{0x03});
        drain(first);
        send(first, createRoleFrame("沙涛"));
        drain(first);
        first.close();

        EmbeddedChannel second = newChannel(store, faculty);
        send(second, loginFrame());
        List<byte[]> replies = drain(second);
        assertEquals(1, replies.size());
        assertEquals(0x04, replies.get(0)[0] & 0xFF);
        assertTrue(replies.get(0).length > 0x29, "已有角色应回带角色 0x04");
        // 名字 UTF16 区段（记录第 3 段 u8(7)+UCS2）包含"沙涛"。
        String text = new String(replies.get(0), StandardCharsets.UTF_16LE);
        assertTrue(text.contains("沙涛"));
        second.close();
    }
}
