package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 个性配置（按键/操作习惯）持久化链路（52795 抓包钉死的 107 上传 / 705 下发）：
 * ClientReady 推送存档 → 会话首个上传遇存档跳过（防默认键位覆盖玩家配置）→
 * 后续上传落档并回显。存档 JSON 主档（data/customizing.json 同构）。
 */
class CustomizingFlowTest {

    /** 抓包同款配置串形状：控制字节 + ASCII（144B 样本截取语义等价的短串）。 */
    private static final String CONFIG_SAVED = "\u0001e\u0002\u0001\u0001$\u0001\u0002\u0002saved";
    private static final String CONFIG_DEFAULT_DUMP = "\u0001e\u0002\u0001\u0001$\u0001\u0002\u0002default";
    private static final String CONFIG_CHANGED = "\u0001e\u0002\u0001\u0001$\u0001\u0002\u0002changed";

    @TempDir
    Path tempDir;

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final LoginSession session = new LoginSession();
        final LoginFlow flow;
        final com.nineyin.server.game.avatar.customizing.CustomizingStore store;

        Env(Path tempDir, String... seeds) throws Exception {
            java.nio.file.Path storePath = tempDir.resolve("customizing.json");
            if (seeds.length == 2) {
                java.nio.file.Files.writeString(storePath,
                        "{\"version\":1,\"roles\":{\"" + seeds[0] + "\":{\"config\":"
                                + renderConfig(seeds[1]) + "}}}",
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            store = com.nineyin.server.game.avatar.customizing.CustomizingStore.open(storePath);
            flow = new LoginFlow(null, null, new com.nineyin.server.gm.GmHub(),
                    Clock.systemUTC(), null, null);
            // LoginFlow 自持 store 指向运行根 data/customizing.json（测试不可写）——
            // 反射换成本地 store，仅测试注入（生产装配不动）。
            injectStore(flow, store);
            session.player = PlayerActor.create("紫泉", 0, Clock.systemUTC());
            session.selected = new Roles.RoleSnapshot(66L, 1, "紫泉", false, 0, 1,
                    new Roles.Appearance(1, List.of()), null);
        }

        List<byte[]> drain() {
            java.util.List<byte[]> frames = new java.util.ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    private static String renderConfig(String config) {
        return com.alibaba.fastjson2.JSON.toJSONString(config);
    }

    private static void injectStore(LoginFlow flow,
                                    com.nineyin.server.game.avatar.customizing.CustomizingStore store)
            throws Exception {
        java.lang.reflect.Field field = LoginFlow.class.getDeclaredField("customizingStore");
        field.setAccessible(true);
        field.set(flow, store);
    }

    /** 0x1E 直达 [107, configStr]。 */
    private static CustomC2S.Message upload(String config) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        byte[] data = config.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(0x1E).writeU16(2);
        writer.writeU8(2).writeU32(107);
        writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        return CustomC2S.parseDirect(writer.toBytes());
    }

    private static String firstStringArg(byte[] frame) {
        // 0x1E | u16 n | tag2 id | tag6 str
        int off = 3 + 5;
        org.junit.jupiter.api.Assertions.assertEquals(6, frame[off] & 0xFF, "第二个值应为窄串");
        int n = (frame[off + 1] & 0xFF) | (frame[off + 2] & 0xFF) << 8
                | (frame[off + 3] & 0xFF) << 16 | (frame[off + 4] & 0xFF) << 24;
        return new String(frame, off + 5, n - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void savedConfigPushedAtClientReadyAndDefaultDumpSkipped() throws Exception {
        Env env = new Env(tempDir, "66", CONFIG_SAVED);

        // ClientReady 推送：一帧 705，载荷 = 存档串。
        env.flow.pushSavedCustomizing(new Connection(env.channel), env.session);
        List<byte[]> pushed = env.drain();
        assertEquals(1, pushed.size());
        byte[] frame = pushed.get(0);
        assertEquals(0x1E, frame[0] & 0xFF);
        assertEquals(705, (frame[4] & 0xFF) | (frame[5] & 0xFF) << 8 | (frame[6] & 0xFF) << 16
                | (frame[7] & 0xFF) << 24);
        assertEquals(CONFIG_SAVED, firstStringArg(frame));
        assertTrue(env.session.customizingPushed);

        // 会话首个 107（登录期默认键位转储）：跳过落库，回发存档再确认。
        env.flow.handleCustomizingUpload(new Connection(env.channel), env.session,
                upload(CONFIG_DEFAULT_DUMP));
        List<byte[]> echo = env.drain();
        assertEquals(1, echo.size());
        assertEquals(CONFIG_SAVED, firstStringArg(echo.get(0)), "回发存档而非默认转储");
        assertEquals(CONFIG_SAVED, env.store.load(66L), "存档未被默认覆盖");

        // 后续上传（玩家改键）：落库 + 回显新串。
        env.flow.handleCustomizingUpload(new Connection(env.channel), env.session,
                upload(CONFIG_CHANGED));
        echo = env.drain();
        assertEquals(1, echo.size());
        assertEquals(CONFIG_CHANGED, firstStringArg(echo.get(0)));
        assertEquals(CONFIG_CHANGED, env.store.load(66L));
    }

    @Test
    void noSavedConfigFirstUploadBecomesBaseline() throws Exception {
        Env env = new Env(tempDir);

        // 无存档：ClientReady 零帧。
        env.flow.pushSavedCustomizing(new Connection(env.channel), env.session);
        assertNull(env.channel.readOutbound());

        // 首个上传作为基线落档并回显。
        env.flow.handleCustomizingUpload(new Connection(env.channel), env.session,
                upload(CONFIG_DEFAULT_DUMP));
        List<byte[]> echo = env.drain();
        assertEquals(1, echo.size());
        assertEquals(CONFIG_DEFAULT_DUMP, firstStringArg(echo.get(0)));
        assertEquals(CONFIG_DEFAULT_DUMP, env.store.load(66L));
    }

    @Test
    void frameShapeMatchesCaptureLayout() {
        byte[] frame = LoginFlow.customizingFrame(CONFIG_SAVED);
        // 期望逐字节等于通用 custom 编码器输出（0x1E 壳 + [705, str]）。
        byte[] expected = CustomMessages.customIntMessage(705,
                CustomMessages.Value.ofString(CONFIG_SAVED));
        assertArrayEquals(expected, frame);
        assertEquals(705, (frame[4] & 0xFF) | (frame[5] & 0xFF) << 8
                | (frame[6] & 0xFF) << 16 | (frame[7] & 0xFF) << 24);
    }
}
