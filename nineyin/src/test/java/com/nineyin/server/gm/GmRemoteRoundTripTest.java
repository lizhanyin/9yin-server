package com.nineyin.server.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import com.swingfrog.summer.client.ClientMgr;
import com.swingfrog.summer.client.ClientRemote;
import com.swingfrog.summer.config.ClientConfig;
import com.swingfrog.summer.server.ServerMgr;
import com.swingfrog.summer.task.TaskMgr;

/**
 * GmRemote RPC 全链门禁：进程内起真实 summer 服务（main + minor.gm 均 StringLine）+
 * ClientMgr.register 动态注册客户端，验证 GmRemote 四类方法的 String JSON 契约
 * 不被 fastjson 双重编码。Summer.hot 的 @Remote 注册是单例幂等敏感的——整个 JVM
 * 只允许一次 hot，因此合为单个测试方法。
 */
class GmRemoteRoundTripTest {

    private static final GmHub HUB = new GmHub();

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void stringLineRoundTripCoversAllGmRemoteMethods() throws Exception {
        int mainPort = freePort();
        int gmPort = freePort();
        String properties = writeServerProperties(mainPort, gmPort);
        Summer.addModuleNet();
        Summer.hot(SummerConfig.newBuilder()
                .app(new TestApp())
                .projectPackage("com.nineyin.server.gm")
                .serverProperties(properties)
                .build());

        // 动态注册（server.clientList 为空，模拟 gm-web 按管理页列表按需连接）。
        ClientConfig config = new ClientConfig();
        config.setCluster("Game");
        config.setServerName("gm");
        config.setAddress("127.0.0.1");
        config.setPort(gmPort);
        config.setProtocol("StringLine");
        config.setCharset("UTF-8");
        config.setPassword("");
        config.setMsgLength(1048576);
        config.setHeartSec(20);
        config.setReconnectMs(100);
        config.setSyncRemoteTimeOutMs(5000);
        config.setConnectNum(1);
        ClientMgr.get().register(config);
        ClientRemote remote = ClientMgr.get().getClientRemote("Game", "gm");
        assertTrue(remote != null, "注册后应能取到 ClientRemote");

        // status：返回 JSON 字符串（验证不双编码：首字符是 { 且可解析）。
        String status = remote.syncRemote("GmRemote", "status", new JSONObject(), String.class);
        assertTrue(status.trim().startsWith("{"), "status 应返回 JSON 对象文本: " + status);
        JSONObject statusJson = JSON.parseObject(status);
        assertTrue(statusJson.containsKey("uptime_sec"), "status 应含 uptime_sec: " + status);
        assertTrue(statusJson.containsKey("phase"), "status 应含 phase: " + status);

        // sessions：空列表 + 挂快照后可见（含 factionName 字段）。
        String empty = remote.syncRemote("GmRemote", "sessions", new JSONObject(), String.class);
        assertEquals(0, JSON.parseArray(empty).size(), "无会话时应返回空数组: " + empty);
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = 7;
        snapshot.name = "紫泉";
        snapshot.faction = "school_junzitang";
        snapshot.sceneConfig = "ini\\scene\\born03_YanYuZhuang";
        snapshot.sceneResource = "born03";
        HUB.attach(7, snapshot);
        String sessions = remote.syncRemote("GmRemote", "sessions", new JSONObject(), String.class);
        JSONArray arr = JSON.parseArray(sessions);
        assertEquals(1, arr.size(), "挂快照后 sessions 应有一条: " + sessions);
        assertEquals("紫泉", arr.getJSONObject(0).getString("name"));
        assertEquals("school_junzitang", arr.getJSONObject(0).getString("faction"));

        // command：未实现动作入口即拒（对齐 Go unsupported action），不入队。
        Map<String, Object> unsupported = Map.of(
                "sessionId", 7,
                "action", "kick",
                "params", Map.of("reason", "round-trip"));
        String rejected = remote.syncRemote("GmRemote", "command", unsupported, String.class);
        JSONObject rejectedJson = JSON.parseObject(rejected);
        assertFalse(rejectedJson.getBooleanValue("ok"), "未实现动作应 ok=false: " + rejected);
        assertTrue(rejectedJson.getString("message").contains("unsupported action"), rejected);
        assertNull(HUB.tryPop(7), "被拒命令不应入队");

        // command：移动参数数值域校验（Go gm_panel.go 0.1..100）。
        Map<String, Object> overRange = Map.of(
                "sessionId", 7,
                "action", "set_move_speed",
                "params", Map.of("value", 500));
        String over = remote.syncRemote("GmRemote", "command", overRange, String.class);
        assertFalse(JSON.parseObject(over).getBooleanValue("ok"), "超界值应被拒: " + over);

        // command：白名单动作入队后等游戏侧回执（消费线程 tryPop + succeed → 真实结果）。
        Thread consumer = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                GmCommand command = HUB.tryPop(7);
                if (command != null) {
                    assertEquals("set_move_speed", command.action());
                    assertEquals(25f, command.params().getFloat("value"), 0f);
                    command.succeed("移动速度设为 25.00");
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "gm-command-consumer");
        consumer.start();
        Map<String, Object> commandData = Map.of(
                "sessionId", 7,
                "action", "set_move_speed",
                "params", Map.of("value", 25));
        String queued = remote.syncRemote("GmRemote", "command", commandData, String.class);
        consumer.join(5000);
        JSONObject queuedJson = JSON.parseObject(queued);
        assertTrue(queuedJson.getBooleanValue("ok"), "消费成功应回 ok: " + queued);
        assertEquals("移动速度设为 25.00", queuedJson.getString("message"));

        // minimalLogin：GET/切换。
        String before = remote.syncRemote("GmRemote", "minimalLogin", new JSONObject(), String.class);
        assertFalse(JSON.parseObject(before).getBooleanValue("enabled"));
        String toggled = remote.syncRemote("GmRemote", "toggleMinimalLogin", new JSONObject(), String.class);
        assertTrue(JSON.parseObject(toggled).getBooleanValue("enabled"), "切换后应开启: " + toggled);
    }

    /** 最小 TestApp：仅负责在 app.init 阶段装配 GmRemote（扫描包由 builder 显式指定）。 */
    public static class TestApp implements SummerApp {

        @Override
        public void init() {
            GmRemote.attach(HUB);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }

    /** main + minor.gm 双 StringLine 端口（gm 端口 allowAddress 锁本机；键集对齐生产 server.properties，ConfigUtil 全字段必填）。 */
    private static String writeServerProperties(int mainPort, int gmPort) throws IOException {
        String content = """
                server.cluster=Game
                server.serverName=roundtrip_main
                server.address=127.0.0.1
                server.port=%d
                server.protocol=StringLine
                server.charset=UTF-8
                server.password=
                server.bossThread=0
                server.workerThread=0
                server.eventThread=0
                server.msgLength=1048576
                server.heartSec=40
                server.coldDownMs=10
                server.allowAddressEnable=false
                server.allowAddressList=
                server.optionSoBacklog=1024
                server.minorList=gm
                server.clientList=
                minor.gm.cluster=Game
                minor.gm.serverName=gm
                minor.gm.address=127.0.0.1
                minor.gm.port=%d
                minor.gm.protocol=StringLine
                minor.gm.charset=UTF-8
                minor.gm.password=
                minor.gm.bossThread=0
                minor.gm.workerThread=0
                minor.gm.eventThread=0
                minor.gm.msgLength=1048576
                minor.gm.heartSec=40
                minor.gm.coldDownMs=10
                minor.gm.allowAddressEnable=true
                minor.gm.allowAddressList=127.0.0.1
                minor.gm.useMainServerThreadPool=false
                minor.gm.optionSoBacklog=1024
                """.formatted(mainPort, gmPort);
        Path file = Files.createTempFile("gm-roundtrip", ".properties");
        file.toFile().deleteOnExit();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file.toString();
    }

    @AfterAll
    static void shutdownSummer() {
        ClientMgr.get().shutdown();
        ServerMgr.get().shutdown();
        TaskMgr.get().shutdown();
    }
}
