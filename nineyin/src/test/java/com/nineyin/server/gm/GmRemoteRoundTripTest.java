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

        // command：碎银非负校验（Go silver must be non-negative）。
        Map<String, Object> negSilver = Map.of(
                "sessionId", 7,
                "action", "set_silver",
                "params", Map.of("silver", -1));
        String neg = remote.syncRemote("GmRemote", "command", negSilver, String.class);
        assertFalse(JSON.parseObject(neg).getBooleanValue("ok"), "负碎银应被拒: " + neg);

        // command：经脉每日硬闸参数校验（enabled∈{0,1} + value 1..10⁷）。
        Map<String, Object> badCapEnabled = Map.of(
                "sessionId", 7,
                "action", "set_zhenqi_cap",
                "params", Map.of("enabled", 2, "value", 500));
        String capEnabledRejected = remote.syncRemote("GmRemote", "command", badCapEnabled, String.class);
        assertFalse(JSON.parseObject(capEnabledRejected).getBooleanValue("ok"), "enabled=2 应被拒");
        Map<String, Object> badCapValue = Map.of(
                "sessionId", 7,
                "action", "set_zhenqi_cap",
                "params", Map.of("enabled", 1, "value", 0));
        String capValueRejected = remote.syncRemote("GmRemote", "command", badCapValue, String.class);
        assertFalse(JSON.parseObject(capValueRejected).getBooleanValue("ok"), "value=0 应被拒");

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

        // command：修为动作数值域（set_faculty 非负、set_fill_speed base/multiplier 域）入口即拒。
        Map<String, Object> negFaculty = Map.of(
                "sessionId", 7,
                "action", "set_faculty",
                "params", Map.of("value", -1));
        String negFacultyRejected = remote.syncRemote("GmRemote", "command", negFaculty, String.class);
        assertFalse(JSON.parseObject(negFacultyRejected).getBooleanValue("ok"), "负修为应被拒: " + negFacultyRejected);
        Map<String, Object> badSpeedBase = Map.of(
                "sessionId", 7,
                "action", "set_fill_speed",
                "params", Map.of("base", 0, "multiplier", 1));
        String badBaseRejected = remote.syncRemote("GmRemote", "command", badSpeedBase, String.class);
        assertFalse(JSON.parseObject(badBaseRejected).getBooleanValue("ok"), "base=0 应被拒");
        Map<String, Object> badSpeedMultiplier = Map.of(
                "sessionId", 7,
                "action", "set_fill_speed",
                "params", Map.of("base", 200, "multiplier", 0));
        String badMultiplierRejected = remote.syncRemote("GmRemote", "command", badSpeedMultiplier, String.class);
        assertFalse(JSON.parseObject(badMultiplierRejected).getBooleanValue("ok"), "multiplier=0 应被拒");

        // command：修为三动作白名单透传 + 游戏侧回执回环（consumer 按 action 分发）。
        Thread facultyConsumer = new Thread(() -> {
            int served = 0;
            for (int i = 0; i < 400 && served < 2; i++) {
                GmCommand command = HUB.tryPop(7);
                if (command == null) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                switch (command.action()) {
                    case "set_faculty" -> {
                        assertEquals(9_999_999, command.params().getIntValue("value"));
                        command.succeed("修为设为 9999999");
                    }
                    case "fill_current_neigong" -> command.succeed("内功 ng_wd_001 修炼度已填满，升至第 35 层");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-faculty-consumer");
        facultyConsumer.start();
        Map<String, Object> facultyData = Map.of(
                "sessionId", 7,
                "action", "set_faculty",
                "params", Map.of("value", 9_999_999));
        String facultyQueued = remote.syncRemote("GmRemote", "command", facultyData, String.class);
        Map<String, Object> fillData = Map.of(
                "sessionId", 7,
                "action", "fill_current_neigong",
                "params", Map.of());
        String fillQueued = remote.syncRemote("GmRemote", "command", fillData, String.class);
        facultyConsumer.join(5000);
        assertTrue(JSON.parseObject(facultyQueued).getBooleanValue("ok"), "set_faculty 应回 ok: " + facultyQueued);
        assertEquals("修为设为 9999999", JSON.parseObject(facultyQueued).getString("message"));
        assertTrue(JSON.parseObject(fillQueued).getBooleanValue("ok"), "fill_current_neigong 应回 ok: " + fillQueued);

        // command：模板生成护腕（无参白名单）与洗练动作（slot 域）透传 + 游戏侧回执回环。
        Thread generateConsumer = new Thread(() -> {
            int served = 0;
            for (int i = 0; i < 400 && served < 2; i++) {
                GmCommand command = HUB.tryPop(7);
                if (command == null) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                switch (command.action()) {
                    case "gm_generate_bracer" ->
                            command.succeed("已生成并穿戴护腕 wrist_60501（词条 6 条）外观与加成已同步");
                    case "recast_slot_prop" -> command.succeed("随机洗练完成：sword_xiyu_60501 槽22 重掷 6 条");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-generate-consumer");
        generateConsumer.start();
        Map<String, Object> bracerData = Map.of(
                "sessionId", 7,
                "action", "gm_generate_bracer",
                "params", Map.of());
        String bracerQueued = remote.syncRemote("GmRemote", "command", bracerData, String.class);
        Map<String, Object> recastData = Map.of(
                "sessionId", 7,
                "action", "recast_slot_prop",
                "params", Map.of("slot", 22));
        String recastQueued = remote.syncRemote("GmRemote", "command", recastData, String.class);
        generateConsumer.join(5000);
        assertTrue(JSON.parseObject(bracerQueued).getBooleanValue("ok"),
                "gm_generate_bracer 应白名单透传: " + bracerQueued);
        assertTrue(JSON.parseObject(recastQueued).getBooleanValue("ok"),
                "recast_slot_prop 应白名单透传（此前漏挂白名单）: " + recastQueued);

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
