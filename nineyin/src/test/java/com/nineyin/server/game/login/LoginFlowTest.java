package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.nineyin.server.gm.GmHub;
import com.nineyin.server.handler.GameServerHandler;
import com.nineyin.server.protocol.NpcSchema;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.SnapshotGoldens;
import com.nineyin.server.world.npc.SceneNpcRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.util.ServerPaths;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameCodec;
import com.nineyin.server.transport.FrameDecoder;
import com.nineyin.server.transport.FrameEncoder;
import com.nineyin.server.transport.XorKey;

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
        return newChannel(store, faculty, new GmHub(), null);
    }

    private static EmbeddedChannel newChannel(RoleStoreFacade store, FacultyStore faculty,
                                              GmHub hub, SceneNpcRegistry registry) {
        LoginFlow flow = new LoginFlow(store, faculty, hub, Clock.systemDefaultZone(),
                registry, new ManualScheduler());
        Connection connection = new Connection(null);
        EmbeddedChannel channel = new EmbeddedChannel();
        // EmbeddedChannel 没有真 socket：Connection 持有的 channel 在 handler 写出侧由 pipeline 承担。
        channel.pipeline().addLast(new FrameDecoder(new Connection(channel)));
        channel.pipeline().addLast(new FrameEncoder(connection));
        channel.pipeline().addLast(new GameServerHandler(flow, new Connection(channel)));
        return channel;
    }

    /** 记录任务不执行：重放/巡逻等定时帧不进 e2e 断言（与 Go 快照语义一致）。 */
    static final class ManualScheduler implements java.util.concurrent.ScheduledExecutorService {
        final List<Runnable> tasks = java.util.Collections.synchronizedList(new ArrayList<>());

        private static final java.util.concurrent.ScheduledFuture<?> NONE = new java.util.concurrent.ScheduledFuture<>() {
            @Override
            public long getDelay(java.util.concurrent.TimeUnit unit) {
                return 0;
            }

            @Override
            public int compareTo(java.util.concurrent.Delayed other) {
                return 0;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, java.util.concurrent.TimeUnit unit) {
                return null;
            }
        };

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, java.util.concurrent.TimeUnit unit) {
            tasks.add(command);
            return NONE;
        }

        @Override
        public <V> java.util.concurrent.ScheduledFuture<V> schedule(
                java.util.concurrent.Callable<V> callable, long delay, java.util.concurrent.TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, java.util.concurrent.TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, java.util.concurrent.TimeUnit unit) {
            // 内修 1s ticker：记录任务不自动跑（e2e 直接调 flow.facultyTickOnce）。
            tasks.add(command);
            return NONE;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return false;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, java.util.concurrent.TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                               long timeout, java.util.concurrent.TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
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

    private static int u16(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
    }

    private static int u32(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8
                | (frame[off + 2] & 0xFF) << 16 | (frame[off + 3] & 0xFF) << 24;
    }

    /** 从 0x04 帧记录区按结构解析 rolePara（整帧直接 UTF16 解码会因头部二进制错位）。 */
    private static String rolePara(byte[] frame) {
        int off = 0x29 + 2 + 1 + 4 + 1 + 4;     // u16(6),u8(2),u32, u8(2),u32
        off += 1 + 4;                           // u8(7) + name 长度
        off += u32(frame, off - 4);             // name 内容
        off += 1 + 4;                           // u8(7) + para 长度
        int paraLen = u32(frame, off - 4);
        return new String(frame, off, paraLen - 2, StandardCharsets.UTF_16LE);
    }

    /** ready_chain_manifest_official.json → (opcode,length) 序列（官方方言 0x09 帧序快照）。 */
    private static List<int[]> readyChainManifest() throws Exception {
        try (InputStream in = LoginFlowTest.class.getResourceAsStream("/golden/ready_chain_manifest_official.json")) {
            Objects.requireNonNull(in, "missing ready_chain_manifest.json");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            java.util.regex.Matcher opcode = java.util.regex.Pattern
                    .compile("\"opcode\":\\s*(\\d+)").matcher(json);
            java.util.regex.Matcher length = java.util.regex.Pattern
                    .compile("\"length\":\\s*(\\d+)").matcher(json);
            List<int[]> frames = new ArrayList<>();
            while (opcode.find() && length.find()) {
                frames.add(new int[]{Integer.parseInt(opcode.group(1)), Integer.parseInt(length.group(1))});
            }
            return frames;
        }
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

        // 0x05 创角 → 0x04 带角色（实力 title027 / 剧本介绍键 / 出生场景中文名）。
        send(channel, createRoleFrame("金镶玉"));
        replies = drain(channel);
        assertEquals(1, replies.size());
        assertEquals(0x04, replies.get(0)[0] & 0xFF);
        assertTrue(replies.get(0).length > 0x29);
        String[] fields = rolePara(replies.get(0)).split(",", -1);
        assertEquals("title027", fields[23], "实力称号键");
        // 测试创角包仅带 cloth_b0001（appearance[0]）：未知剧本按设计回退原值。
        assertEquals("cloth_b0001", fields[25], "剧本介绍键（未知剧本回退原值）");
        assertEquals("移花宫", fields[24], "所在场景中文名（stringname.idres）");

        // 0x04 选角 → 下发序列（Go main.go:665-760 顺序）。
        byte[] choose = new byte[0x54];
        choose[0] = 0x04;
        send(channel, choose);
        replies = drain(channel);
        int i = 0;
        SnapshotGoldens.assertOrRegen("property_table_official.bin", replies.get(i++));
        assertArrayEquals(golden("record_table.bin"), replies.get(i++));
        // 三期 17 视图容器：8 主页（cap90+BaseCap18，12 字节）+ 8 扩展页（cap2）+ View1 纸娃娃。
        int[] mainPages = {121, 2, 123, 125, 176, 174, 178, 180};
        for (int viewId : mainPages) {
            byte[] frame = replies.get(i++);
            assertEquals(0x15, frame[0] & 0xFF, "主页视图@" + viewId);
            assertEquals(viewId, u16(frame, 1));
            assertEquals(90, u16(frame, 3), "主页 cap=90");
            assertEquals(1, u16(frame, 5), "主页携带 BaseCap 属性");
        }
        int[] extPages = {122, 3, 124, 126, 177, 175, 179, 181};
        for (int viewId : extPages) {
            byte[] frame = replies.get(i++);
            assertEquals(0x15, frame[0] & 0xFF, "扩展页@" + viewId);
            assertEquals(viewId, u16(frame, 1));
            assertEquals(7, frame.length, "扩展页 7 字节空容器");
        }
        byte[] doll = replies.get(i++);
        assertEquals(0x15, doll[0] & 0xFF);
        assertEquals(1, u16(doll, 1), "View1 纸娃娃");
        assertEquals(48, u16(doll, 3));
        SnapshotGoldens.assertOrRegen("entry_scene_official.bin", replies.get(i++));
        assertEquals(0x0D, replies.get(i)[0] & 0xFF);
        int addObjectIndex = i++;
        assertEquals(0x1F, replies.get(i)[0] & 0xFF);
        assertArrayEquals(golden("server_location.bin"), replies.get(i++));
        assertEquals(0x10, replies.get(i++)[0] & 0xFF);
        assertEquals(i, replies.size());
        // 0x0D：objectID/ownerID/坐标首部与 golden 相同（faculty 为运行时钟，buff 字符串不同）。
        byte[] addObject = replies.get(addObjectIndex);
        byte[] goldenObject = golden("player_add_object_default.bin");
        // 头部 0x00..0x3c 与 golden 相同；@0x3d 属性数官方方言不同（Pos* 等缺名剔除）。
        for (int offset = 0; offset < 0x3d; offset++) {
            assertEquals(goldenObject[offset], addObject[offset], "0x0D header@" + Integer.toHexString(offset));
        }

        // 0x09 ready → 二期 starter 链：Go golden_dump_test.go ready 链（1748 帧）。
        send(channel, new byte[]{0x09});
        replies = drain(channel);
        List<int[]> manifest;
        if (SnapshotGoldens.regen()) {
            // 再生模式：清单来自实际帧（文件尚不存在），随后写盘。
            manifest = new ArrayList<>(replies.size());
            for (byte[] frame : replies) {
                manifest.add(new int[]{frame[0] & 0xFF, frame.length});
            }
            StringBuilder json = new StringBuilder("[");
            for (int j = 0; j < manifest.size(); j++) {
                if (j > 0) {
                    json.append(",");
                }
                json.append(System.lineSeparator()).append("  {\"i\": ").append(j)
                        .append(", \"opcode\": ").append(manifest.get(j)[0])
                        .append(", \"length\": ").append(manifest.get(j)[1]).append("}");
            }
            json.append(System.lineSeparator()).append("]");
            SnapshotGoldens.assertOrRegenText("ready_chain_manifest_official.json",
                    json.toString());
        } else {
            manifest = readyChainManifest();
        }
        assertEquals(manifest.size(), replies.size());
        for (int j = 0; j < manifest.size(); j++) {
            assertEquals(manifest.get(j)[0], replies.get(j)[0] & 0xFF, "opcode@" + j);
            // 0x10 vital 帧带 Buff 到期字符串：golden fixture 在墙钟下已过期（空串 820），
            // 运行时新角色的 24h Buff 仍活跃（890）——长度随时钟态变化，只比对 opcode。
            if (manifest.get(j)[0] != 0x10) {
                assertEquals(manifest.get(j)[1], replies.get(j).length, "length@" + j);
            }
        }
        // 状态无关样本帧逐字节：记录行/视图/快捷栏/0x27 开关。
        // 段界：0-17 轻功行 | 18 View46 | 19-88 条目 | 89-103 任务接受 | 104-1697 位图 | 1698 打坐
        // | 1699 View43 | 1700-1705 书 | 1706 View40 | 1707-1729 技能 | 1730-1744 快捷栏 | 1745 vital
        // | 1746-1768 View40 行可用性 CanUse=1 批（战斗一期）| 1769 switch | 1770 vital。
        assertArrayEquals(golden("qinggong_record_add.bin"), replies.get(0));
        assertArrayEquals(golden("view46_create.bin"), replies.get(18));
        SnapshotGoldens.assertOrRegen("view46_add_official_first.bin", replies.get(19));
        SnapshotGoldens.assertOrRegen("view46_add_official_last.bin", replies.get(88));
        assertArrayEquals(golden("task_accepted_add.bin"), replies.get(89));
        assertArrayEquals(golden("task_completed_row_zero.bin"), replies.get(104));
        assertArrayEquals(golden("task_completed_row_final.bin"), replies.get(1697));
        assertArrayEquals(golden("shortcut_sitcross_add.bin"), replies.get(1698));
        assertArrayEquals(golden("view43_create.bin"), replies.get(1699));
        assertArrayEquals(golden("view40_create.bin"), replies.get(1706));
        SnapshotGoldens.assertOrRegen("view40_add_official_first.bin", replies.get(1707));
        SnapshotGoldens.assertOrRegen("view40_add_official_last.bin", replies.get(1729));
        // CanUse=1 批首帧：0x10|isView=1|view40|行1|CanUse(2236)=1（62433 抓包 2159 帧同款）
        byte[] canUseFirst = replies.get(1746);
        assertEquals(0x10, canUseFirst[0] & 0xFF);
        assertEquals(1, canUseFirst[1]);
        assertEquals(40, u32(canUseFirst, 2));
        assertEquals(1, u32(canUseFirst, 6));
        assertEquals(15, canUseFirst.length);
        assertEquals(2236, u16(canUseFirst, 12));
        assertEquals(1, canUseFirst[14]);
        assertArrayEquals(golden("skill_switch.bin"), replies.get(1769));
        // 两帧 vital 自一致（buff 时钟随运行时钟，不比 golden；fixture 书 fill 与新角色不同，书帧只比长度）。
        assertArrayEquals(replies.get(1745), replies.get(1770));
        assertEquals(0x10, replies.get(1745)[0] & 0xFF);

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
        GmHub hub = new GmHub();
        EmbeddedChannel gmChannel = newChannel(store, faculty, hub, null);
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
        // 名字 + f23 实力 / f24 场景 / f25 剧本（按结构解析，避免整帧解码错位）。
        String text = new String(replies.get(0), StandardCharsets.UTF_16LE);
        assertTrue(text.contains("沙涛"));
        String[] fields = rolePara(replies.get(0)).split(",", -1);
        assertEquals("title027", fields[23], "实力称号键");
        assertEquals("移花宫", fields[24], "出生场景中文名");
        assertEquals("cloth_b0001", fields[25], "剧本介绍键（未知剧本回退原值）");
        second.close();
    }

    /** 走到选角完成（登录/世界信息/创角/选角），返回通道。 */
    private EmbeddedChannel drivenToChosen(Path tempDir) throws Exception {
        JsonRoleRepository repository = JsonRoleRepository.open(tempDir.resolve("roles.json"));
        RoleStoreFacade store = new RoleStoreFacade(repository);
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));
        EmbeddedChannel channel = newChannel(store, faculty);
        send(channel, loginFrame());
        drain(channel);
        send(channel, new byte[]{0x03});
        drain(channel);
        send(channel, createRoleFrame("金镶玉"));
        drain(channel);
        byte[] choose = new byte[0x54];
        choose[0] = 0x04;
        send(channel, choose);
        drain(channel);
        return channel;
    }

    @Test
    void duplicateClientReadyInWorldResendsOnlySwitch(@TempDir Path tempDir) throws Exception {
        EmbeddedChannel channel = drivenToChosen(tempDir);
        send(channel, new byte[]{0x09});
        drain(channel);
        send(channel, new byte[]{0x0A, 0x00});
        drain(channel);
        // 活动场景内重复 0x09：starter 链已授予（幂等跳过），仅重发 0x27 开关，不断连。
        send(channel, new byte[]{0x09});
        List<byte[]> replies = drain(channel);
        assertEquals(1, replies.size());
        assertArrayEquals(golden("skill_switch.bin"), replies.get(0));
        assertTrue(channel.isActive());
        channel.close();
    }

    @Test
    void firstActivityPromotesCompatibilityReady(@TempDir Path tempDir) throws Exception {
        EmbeddedChannel channel = drivenToChosen(tempDir);
        // 客户端省略 0x09 直发 0x0A：兼容提升 = vital + 0x27（不发 starter 链），相位进 InWorld。
        send(channel, new byte[]{0x0A, 0x00});
        List<byte[]> replies = drain(channel);
        assertEquals(2, replies.size());
        assertEquals(0x10, replies.get(0)[0] & 0xFF);
        assertArrayEquals(golden("skill_switch.bin"), replies.get(1));
        assertTrue(channel.isActive());
        // 后续重复 0x0A 不再产生提升帧。
        send(channel, new byte[]{0x0A, 0x00});
        replies = drain(channel);
        assertTrue(replies.isEmpty());
        channel.close();
    }

    @Test
    void parseMotionPositionAcceptsSixtySevenByteUpdate() {
        byte[] frame = new byte[67];
        frame[0] = 0x0A;
        int offset = 0x26;
        float[] values = {481.25f, 87.545f, 624.5f, 3.12f};
        for (int i = 0; i < 4; i++) {
            frame[offset + i * 5] = 4;
            int bits = Float.floatToRawIntBits(values[i]);
            frame[offset + 1 + i * 5] = (byte) bits;
            frame[offset + 2 + i * 5] = (byte) (bits >>> 8);
            frame[offset + 3 + i * 5] = (byte) (bits >>> 16);
            frame[offset + 4 + i * 5] = (byte) (bits >>> 24);
        }
        float[] parsed = LoginFlow.parseMotionPosition(frame);
        assertEquals(481.25f, parsed[0]);
        assertEquals(87.545f, parsed[1]);
        assertEquals(624.5f, parsed[2]);
        assertEquals(3.12f, parsed[3]);
        // 长目的地包不得当位置更新；越界坐标拒绝。
        assertNull(LoginFlow.parseMotionPosition(new byte[82]));
        byte[] far = frame.clone();
        int farBits = Float.floatToRawIntBits(20000f);
        far[0x27] = (byte) farBits;
        far[0x28] = (byte) (farBits >>> 8);
        far[0x29] = (byte) (farBits >>> 16);
        far[0x2A] = (byte) (farBits >>> 24);
        assertNull(LoginFlow.parseMotionPosition(far));
    }

    @Test
    void npcViewportMaterializesAfterActivity(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(java.nio.file.Files.isDirectory(
                GO_SERVER_ROOT.resolve("resources/modern/share/npc/npcconfig")),
                "Go 仓库 share 资源不可用，跳过 NPC 物化 e2e");
        SceneNpcRegistry registry = npcRegistry();
        JsonRoleRepository repository = JsonRoleRepository.open(tempDir.resolve("roles.json"));
        RoleStoreFacade store = new RoleStoreFacade(repository);
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));
        EmbeddedChannel channel = newChannel(store, faculty, new GmHub(), registry);
        send(channel, loginFrame());
        drain(channel);
        send(channel, new byte[]{0x03});
        drain(channel);
        send(channel, createRoleFrame("金镶玉"));
        drain(channel);
        byte[] choose = new byte[0x54];
        choose[0] = 0x04;
        send(channel, choose);
        drain(channel);
        send(channel, new byte[]{0x09});
        drain(channel);
        // 首个 0x0A：物化视口 NPC（0x0D+0x1F 成对、按对象 ID 升序）。
        send(channel, new byte[]{0x0A, 0x00});
        List<byte[]> replies = drain(channel);
        assertTrue(replies.size() >= 2, "school09 出生点 120 半径内应有可物化 NPC，实际帧数=" + replies.size());
        int previousObjectId = 1;
        int npcCount = 0;
        for (int i = 0; i + 1 < replies.size(); i += 2) {
            assertEquals(0x0D, replies.get(i)[0] & 0xFF, "偶数位 0x0D@" + i);
            assertEquals(0x1F, replies.get(i + 1)[0] & 0xFF, "奇数位 0x1F@" + (i + 1));
            int objectId = (int) u32(replies.get(i), 1);
            assertEquals(objectId, (int) u32(replies.get(i + 1), 1), "0x0D/0x1F 同对象");
            assertTrue(objectId > previousObjectId, "对象 ID 升序");
            assertTrue(replies.get(i).length > 0x3f, "0x0D 含属性归档");
            previousObjectId = objectId;
            npcCount++;
        }
        assertTrue(npcCount > 0);
        channel.close();
    }

    @Test
    void customDirect1006DrivesFacultyLoop(@TempDir Path tempDir) throws Exception {
        JsonRoleRepository repository = JsonRoleRepository.open(tempDir.resolve("roles.json"));
        RoleStoreFacade store = new RoleStoreFacade(repository);
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));
        EmbeddedChannel channel = newChannel(store, faculty, new GmHub(), null);
        send(channel, loginFrame());
        drain(channel);
        send(channel, new byte[]{0x03});
        drain(channel);
        send(channel, createRoleFrame("金镶玉"));
        drain(channel);
        byte[] choose = new byte[0x54];
        choose[0] = 0x04;
        send(channel, choose);
        drain(channel);
        send(channel, new byte[]{0x09});
        drain(channel);
        send(channel, new byte[]{0x0A, 0x00});
        drain(channel);

        // 0x1E 直达 1006/21 演武开始 → [0x10, 0x1E msg191/(22,0)]。
        byte[] actBegin = clientCustomFrame(1006, 21);
        send(channel, actBegin);
        List<byte[]> replies = drain(channel);
        assertEquals(2, replies.size());
        assertEquals(0x10, replies.get(0)[0] & 0xFF);
        assertEquals(0x1E, replies.get(1)[0] & 0xFF);
        assertEquals(22, replies.get(1)[9] & 0xFF);

        // 1006/22 停 → [0x10]。
        byte[] actExit = clientCustomFrame(1006, 22);
        send(channel, actExit);
        replies = drain(channel);
        assertEquals(1, replies.size());
        assertEquals(0x10, replies.get(0)[0] & 0xFF);
        assertTrue(channel.isActive());
        channel.close();
    }

    /** 客户端 0x1E 帧构造（C2S id 无 [1,764] S2C 限制）。 */
    private static byte[] clientCustomFrame(int messageId, int subcommand) {
        PacketWriter writer = PacketWriter.ofCapacity(16);
        writer.writeU8(0x1E).writeU16(2);
        writer.writeU8(2).writeU32(messageId & 0xFFFFFFFFL);
        writer.writeU8(2).writeU32(subcommand & 0xFFFFFFFFL);
        return writer.toBytes();
    }

    private static SceneNpcRegistry npcRegistry() throws Exception {
        java.nio.file.Path share = GO_SERVER_ROOT.resolve("resources/modern/share");
        return SceneNpcRegistry.open(share.resolve("npc/npcconfig"),
                share.resolve("creator/npc_creator"),
                NpcSchema.visibleNpcModernV1());
    }
}
