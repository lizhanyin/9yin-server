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

        // command：战斗假人参数域（超界 dummyHp / 超长名字入口即拒，不入队）。
        Map<String, Object> badDummyHp = Map.of(
                "sessionId", 7,
                "action", "spawn_combat_dummy",
                "params", Map.of("dummyHp", 100_000_001));
        String badDummyRejected = remote.syncRemote("GmRemote", "command", badDummyHp, String.class);
        assertFalse(JSON.parseObject(badDummyRejected).getBooleanValue("ok"),
                "超界假人血量应被拒: " + badDummyRejected);
        Map<String, Object> badDummyName = Map.of(
                "sessionId", 7,
                "action", "spawn_combat_dummy",
                "params", Map.of("dummyName", "x".repeat(65)));
        String badNameRejected = remote.syncRemote("GmRemote", "command", badDummyName, String.class);
        assertFalse(JSON.parseObject(badNameRejected).getBooleanValue("ok"),
                "超长假人名应被拒: " + badNameRejected);

        // command：战斗假人生成/清除白名单透传 + 游戏侧回执回环。
        Thread combatDummyConsumer = new Thread(() -> {
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
                    case "spawn_combat_dummy" -> {
                        assertEquals("木人甲", command.params().getString("dummyName"));
                        assertEquals(50000, command.params().getIntValue("dummyHp"));
                        command.succeed("已生成战斗假人（对象=900000 木人甲 血量=50000）");
                    }
                    case "clear_combat_dummies" -> command.succeed("已清除 1 只战斗假人");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-combat-dummy-consumer");
        combatDummyConsumer.start();
        Map<String, Object> combatDummyData = Map.of(
                "sessionId", 7,
                "action", "spawn_combat_dummy",
                "params", Map.of("dummyName", "木人甲", "dummyHp", 50000,
                        "dummyDamage", 0, "dummySkills", "CS_jh_sfgp01"));
        String combatDummyQueued = remote.syncRemote("GmRemote", "command", combatDummyData, String.class);
        Map<String, Object> clearDummiesData = Map.of(
                "sessionId", 7,
                "action", "clear_combat_dummies",
                "params", Map.of());
        String clearDummiesQueued = remote.syncRemote("GmRemote", "command", clearDummiesData, String.class);
        combatDummyConsumer.join(5000);
        assertTrue(JSON.parseObject(combatDummyQueued).getBooleanValue("ok"),
                "spawn_combat_dummy 应白名单透传: " + combatDummyQueued);
        assertTrue(JSON.parseObject(clearDummiesQueued).getBooleanValue("ok"),
                "clear_combat_dummies 应白名单透传: " + clearDummiesQueued);

        // command：伙伴 bot_* 参数域（非法预设键 / 越界 intervalSec / 越界 heal 入口即拒，不入队）。
        Map<String, Object> badBotKey = Map.of(
                "sessionId", 7,
                "action", "bot_ensure",
                "params", Map.of("bot", "no_such_bot"));
        String badBotRejected = remote.syncRemote("GmRemote", "command", badBotKey, String.class);
        assertFalse(JSON.parseObject(badBotRejected).getBooleanValue("ok"),
                "非法伙伴预设键应被拒: " + badBotRejected);
        Map<String, Object> badBotInterval = Map.of(
                "sessionId", 7,
                "action", "bot_support_configure",
                "params", Map.of("enabled", 1, "intervalSec", 61));
        String badIntervalRejected = remote.syncRemote("GmRemote", "command", badBotInterval, String.class);
        assertFalse(JSON.parseObject(badIntervalRejected).getBooleanValue("ok"),
                "越界辅助间隔应被拒: " + badIntervalRejected);
        Map<String, Object> badBotHeal = Map.of(
                "sessionId", 7,
                "action", "bot_support_configure",
                "params", Map.of("heal", 100_001));
        String badHealRejected = remote.syncRemote("GmRemote", "command", badBotHeal, String.class);
        assertFalse(JSON.parseObject(badHealRejected).getBooleanValue("ok"),
                "越界治疗量应被拒: " + badHealRejected);

        // command：伙伴六动作白名单透传 + 游戏侧回执回环（ensure 带预设键/辅助带参数域内值）。
        Thread botConsumer = new Thread(() -> {
            int served = 0;
            for (int i = 0; i < 400 && served < 6; i++) {
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
                    case "bot_ensure" -> {
                        assertEquals("qinghe", command.params().getString("bot"));
                        command.succeed("已生成伙伴（青禾 对象 900005 血 5000/5000 跟随中 辅助关）");
                    }
                    case "bot_pull_here" -> command.succeed("伙伴 青禾 已拉到身前（对象 900005）");
                    case "bot_follow_start" -> command.succeed("伙伴 青禾 开始跟随");
                    case "bot_follow_stop" -> command.succeed("伙伴 青禾 已停止跟随（原地驻足）");
                    case "bot_revive" -> command.succeed("伙伴 青禾 已复活回满血（5000/5000）");
                    case "bot_support_configure" -> {
                        assertEquals(1, command.params().getIntValue("enabled"));
                        assertEquals(5, command.params().getIntValue("intervalSec"));
                        assertEquals(500, command.params().getIntValue("heal"));
                        command.succeed("伙伴辅助已配置：开，每 5s 治疗玩家 500 点");
                    }
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-bot-consumer");
        botConsumer.start();
        Map<String, Object> botEnsureData = Map.of(
                "sessionId", 7, "action", "bot_ensure", "params", Map.of("bot", "qinghe"));
        Map<String, Object> botPullData = Map.of(
                "sessionId", 7, "action", "bot_pull_here", "params", Map.of());
        Map<String, Object> botFollowStartData = Map.of(
                "sessionId", 7, "action", "bot_follow_start", "params", Map.of());
        Map<String, Object> botFollowStopData = Map.of(
                "sessionId", 7, "action", "bot_follow_stop", "params", Map.of());
        Map<String, Object> botReviveData = Map.of(
                "sessionId", 7, "action", "bot_revive", "params", Map.of());
        Map<String, Object> botSupportData = Map.of(
                "sessionId", 7, "action", "bot_support_configure",
                "params", Map.of("enabled", 1, "intervalSec", 5, "heal", 500));
        String botEnsureQueued = remote.syncRemote("GmRemote", "command", botEnsureData, String.class);
        String botPullQueued = remote.syncRemote("GmRemote", "command", botPullData, String.class);
        String botFollowStartQueued = remote.syncRemote("GmRemote", "command", botFollowStartData, String.class);
        String botFollowStopQueued = remote.syncRemote("GmRemote", "command", botFollowStopData, String.class);
        String botReviveQueued = remote.syncRemote("GmRemote", "command", botReviveData, String.class);
        String botSupportQueued = remote.syncRemote("GmRemote", "command", botSupportData, String.class);
        botConsumer.join(5000);
        assertTrue(JSON.parseObject(botEnsureQueued).getBooleanValue("ok"),
                "bot_ensure 应白名单透传: " + botEnsureQueued);
        assertTrue(JSON.parseObject(botPullQueued).getBooleanValue("ok"),
                "bot_pull_here 应白名单透传: " + botPullQueued);
        assertTrue(JSON.parseObject(botFollowStartQueued).getBooleanValue("ok"),
                "bot_follow_start 应白名单透传: " + botFollowStartQueued);
        assertTrue(JSON.parseObject(botFollowStopQueued).getBooleanValue("ok"),
                "bot_follow_stop 应白名单透传: " + botFollowStopQueued);
        assertTrue(JSON.parseObject(botReviveQueued).getBooleanValue("ok"),
                "bot_revive 应白名单透传: " + botReviveQueued);
        assertTrue(JSON.parseObject(botSupportQueued).getBooleanValue("ok"),
                "bot_support_configure 应白名单透传: " + botSupportQueued);

        // command：门派声望参数域（坏 mode / 越界 value / 缺 name 入口即拒，不入队）。
        Map<String, Object> badReputeMode = Map.of(
                "sessionId", 7,
                "action", "repute_apply",
                "params", Map.of("reputeName", "repute_wudang",
                        "reputeMode", "mul", "reputeValue", 100));
        String badModeRejected = remote.syncRemote("GmRemote", "command", badReputeMode, String.class);
        assertFalse(JSON.parseObject(badModeRejected).getBooleanValue("ok"),
                "非法 reputeMode 应被拒: " + badModeRejected);
        Map<String, Object> badReputeValue = Map.of(
                "sessionId", 7,
                "action", "repute_apply",
                "params", Map.of("reputeName", "repute_wudang", "reputeValue", 10_000_000));
        String badValueRejected = remote.syncRemote("GmRemote", "command", badReputeValue, String.class);
        assertFalse(JSON.parseObject(badValueRejected).getBooleanValue("ok"),
                "越界 reputeValue 应被拒: " + badValueRejected);
        Map<String, Object> missingReputeName = Map.of(
                "sessionId", 7,
                "action", "repute_apply",
                "params", Map.of("reputeValue", 100));
        String missingNameRejected = remote.syncRemote("GmRemote", "command", missingReputeName, String.class);
        assertFalse(JSON.parseObject(missingNameRejected).getBooleanValue("ok"),
                "缺 reputeName 应被拒: " + missingNameRejected);

        // command：门派声望白名单透传 + 游戏侧回执回环（阵营闭名单在游戏侧按表校验）。
        Thread reputeConsumer = new Thread(() -> {
            for (int i = 0; i < 400; i++) {
                GmCommand command = HUB.tryPop(7);
                if (command == null) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                if ("repute_apply".equals(command.action())) {
                    assertEquals("repute_wudang", command.params().getString("reputeName"));
                    assertEquals("set", command.params().getString("reputeMode"));
                    assertEquals(5000, command.params().getIntValue("reputeValue"));
                    command.succeed("声望 repute_wudang 已设置至 5000（等级 2），总量 5000");
                    return;
                }
                command.fail("unexpected action " + command.action());
            }
        }, "gm-repute-consumer");
        reputeConsumer.start();
        Map<String, Object> reputeData = Map.of(
                "sessionId", 7,
                "action", "repute_apply",
                "params", Map.of("reputeName", "repute_wudang",
                        "reputeMode", "set", "reputeValue", 5000));
        String reputeQueued = remote.syncRemote("GmRemote", "command", reputeData, String.class);
        reputeConsumer.join(5000);
        assertTrue(JSON.parseObject(reputeQueued).getBooleanValue("ok"),
                "repute_apply 应白名单透传: " + reputeQueued);

        // command：世界 BOSS 三动作（无参）白名单透传 + 游戏侧回执回环。
        Thread worldBossConsumer = new Thread(() -> {
            int served = 0;
            for (int i = 0; i < 400 && served < 3; i++) {
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
                    case "worldboss_spawn" -> command.succeed("已强制生成 5 只世界 BOSS：…");
                    case "worldboss_clear" -> command.succeed("已清除 5 只世界 BOSS");
                    case "worldboss_list" -> command.succeed("在场世界 BOSS 5 只：…");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-worldboss-consumer");
        worldBossConsumer.start();
        Map<String, Object> bossSpawnData = Map.of(
                "sessionId", 7, "action", "worldboss_spawn", "params", Map.of());
        Map<String, Object> bossClearData = Map.of(
                "sessionId", 7, "action", "worldboss_clear", "params", Map.of());
        Map<String, Object> bossListData = Map.of(
                "sessionId", 7, "action", "worldboss_list", "params", Map.of());
        String bossSpawnQueued = remote.syncRemote("GmRemote", "command", bossSpawnData, String.class);
        String bossClearQueued = remote.syncRemote("GmRemote", "command", bossClearData, String.class);
        String bossListQueued = remote.syncRemote("GmRemote", "command", bossListData, String.class);
        worldBossConsumer.join(5000);
        assertTrue(JSON.parseObject(bossSpawnQueued).getBooleanValue("ok"),
                "worldboss_spawn 应白名单透传: " + bossSpawnQueued);
        assertTrue(JSON.parseObject(bossClearQueued).getBooleanValue("ok"),
                "worldboss_clear 应白名单透传: " + bossClearQueued);
        assertTrue(JSON.parseObject(bossListQueued).getBooleanValue("ok"),
                "worldboss_list 应白名单透传: " + bossListQueued);

        // command：副本三动作——enter_clone 参数域（空 config / 难度越界入口即拒，不入队）。
        Map<String, Object> noConfig = Map.of(
                "sessionId", 7, "action", "enter_clone", "params", Map.of("value", 1));
        String noConfigRejected = remote.syncRemote("GmRemote", "command", noConfig, String.class);
        assertFalse(JSON.parseObject(noConfigRejected).getBooleanValue("ok"),
                "空 cloneConfig 应被拒: " + noConfigRejected);
        assertNull(HUB.tryPop(7), "被拒副本命令不应入队");
        Map<String, Object> badFun = Map.of(
                "sessionId", 7, "action", "enter_clone",
                "params", Map.of("cloneConfig", "ini\\scene\\clone003", "value", 5));
        String badFunRejected = remote.syncRemote("GmRemote", "command", badFun, String.class);
        assertFalse(JSON.parseObject(badFunRejected).getBooleanValue("ok"),
                "难度档越界（5）应被拒: " + badFunRejected);

        // command：副本三动作白名单透传 + 游戏侧回执回环（cloneConfig/value 原样到达）。
        Thread cloneConsumer = new Thread(() -> {
            int served = 0;
            for (int i = 0; i < 400 && served < 3; i++) {
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
                    case "enter_clone" -> {
                        assertEquals("ini\\scene\\clone003", command.params().getString("cloneConfig"));
                        assertEquals(2, command.params().getIntValue("value"));
                        command.succeed("已发送切图到 副本「暮色之村」第 1/5 波(难度2)…");
                    }
                    case "reenter_clone" -> command.succeed("已在副本「暮色之村」第 1/5 波(难度2)");
                    case "destroy_clone" -> command.succeed("已发送切图到 原场景 city02…");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-clone-consumer");
        cloneConsumer.start();
        Map<String, Object> enterCloneData = Map.of(
                "sessionId", 7, "action", "enter_clone",
                "params", Map.of("cloneConfig", "ini\\scene\\clone003", "value", 2));
        Map<String, Object> reenterCloneData = Map.of(
                "sessionId", 7, "action", "reenter_clone", "params", Map.of());
        Map<String, Object> destroyCloneData = Map.of(
                "sessionId", 7, "action", "destroy_clone", "params", Map.of());
        String enterCloneQueued = remote.syncRemote("GmRemote", "command", enterCloneData, String.class);
        String reenterCloneQueued = remote.syncRemote("GmRemote", "command", reenterCloneData, String.class);
        String destroyCloneQueued = remote.syncRemote("GmRemote", "command", destroyCloneData, String.class);
        cloneConsumer.join(5000);
        assertTrue(JSON.parseObject(enterCloneQueued).getBooleanValue("ok"),
                "enter_clone 应白名单透传: " + enterCloneQueued);
        assertTrue(JSON.parseObject(reenterCloneQueued).getBooleanValue("ok"),
                "reenter_clone 应白名单透传: " + reenterCloneQueued);
        assertTrue(JSON.parseObject(destroyCloneQueued).getBooleanValue("ok"),
                "destroy_clone 应白名单透传: " + destroyCloneQueued);

        // command：武学套路两动作（无参）白名单透传 + 游戏侧回执回环。
        Thread taoluConsumer = new Thread(() -> {
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
                    case "learn_all_taolu" -> command.succeed("已把 27 门武学升至满级（其中 20 门升档）");
                    case "forget_all_taolu" -> command.succeed("已删除全部武学，重置为初始套路集（剩余 27 项）");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-taolu-consumer");
        taoluConsumer.start();
        Map<String, Object> learnTaoluData = Map.of(
                "sessionId", 7, "action", "learn_all_taolu", "params", Map.of());
        Map<String, Object> forgetTaoluData = Map.of(
                "sessionId", 7, "action", "forget_all_taolu", "params", Map.of());
        String learnTaoluQueued = remote.syncRemote("GmRemote", "command", learnTaoluData, String.class);
        String forgetTaoluQueued = remote.syncRemote("GmRemote", "command", forgetTaoluData, String.class);
        taoluConsumer.join(5000);
        assertTrue(JSON.parseObject(learnTaoluQueued).getBooleanValue("ok"),
                "learn_all_taolu 应白名单透传: " + learnTaoluQueued);
        assertTrue(JSON.parseObject(forgetTaoluQueued).getBooleanValue("ok"),
                "forget_all_taolu 应白名单透传: " + forgetTaoluQueued);

        // command：御风神水两动作（无参）白名单透传 + 游戏侧回执回环。
        Thread yufengConsumer = new Thread(() -> {
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
                    case "yufeng_add" -> command.succeed("御风状态已添加（槽 12，10 分钟内可施展大轻功）");
                    case "yufeng_remove" -> command.succeed("御风状态已取消（槽 12）");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-yufeng-consumer");
        yufengConsumer.start();
        Map<String, Object> yufengAddData = Map.of(
                "sessionId", 7, "action", "yufeng_add", "params", Map.of());
        Map<String, Object> yufengRemoveData = Map.of(
                "sessionId", 7, "action", "yufeng_remove", "params", Map.of());
        String yufengAddQueued = remote.syncRemote("GmRemote", "command", yufengAddData, String.class);
        String yufengRemoveQueued = remote.syncRemote("GmRemote", "command", yufengRemoveData, String.class);
        yufengConsumer.join(5000);
        assertTrue(JSON.parseObject(yufengAddQueued).getBooleanValue("ok"),
                "yufeng_add 应白名单透传: " + yufengAddQueued);
        assertTrue(JSON.parseObject(yufengRemoveQueued).getBooleanValue("ok"),
                "yufeng_remove 应白名单透传: " + yufengRemoveQueued);

        // command：身份系统一键激活（无参）白名单透传 + 游戏侧回执回环。
        Thread originConsumer = new Thread(() -> {
            for (int i = 0; i < 400; i++) {
                GmCommand command = HUB.tryPop(7);
                if (command == null) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                if ("origin_activate_all".equals(command.action())) {
                    command.succeed("已激活 1197 个身份（共 1197 个）；奖励在身份界面点领取（702）发放");
                    return;
                }
                command.fail("unexpected action " + command.action());
            }
        }, "gm-origin-consumer");
        originConsumer.start();
        Map<String, Object> originData = Map.of(
                "sessionId", 7, "action", "origin_activate_all", "params", Map.of());
        String originQueued = remote.syncRemote("GmRemote", "command", originData, String.class);
        originConsumer.join(5000);
        assertTrue(JSON.parseObject(originQueued).getBooleanValue("ok"),
                "origin_activate_all 应白名单透传: " + originQueued);

        // command：场景天气参数域（非法天气键 / 越界 fadeSec 入口即拒，不入队）。
        Map<String, Object> badWeatherKey = Map.of(
                "sessionId", 7,
                "action", "set_weather",
                "params", Map.of("weather", "rule/weather/evil"));
        String badWeatherRejected = remote.syncRemote("GmRemote", "command", badWeatherKey, String.class);
        assertFalse(JSON.parseObject(badWeatherRejected).getBooleanValue("ok"),
                "非法天气键应被拒: " + badWeatherRejected);
        Map<String, Object> badFade = Map.of(
                "sessionId", 7,
                "action", "set_weather",
                "params", Map.of("weather", "heavy_rain_501", "fadeSec", 601));
        String badFadeRejected = remote.syncRemote("GmRemote", "command", badFade, String.class);
        assertFalse(JSON.parseObject(badFadeRejected).getBooleanValue("ok"),
                "越界过渡秒应被拒: " + badFadeRejected);

        // command：场景天气 set_weather 白名单透传 + 游戏侧回执回环（合法键 + 过渡秒）。
        Thread weatherConsumer = new Thread(() -> {
            int served = 0;
            for (int i = 0; i < 400 && served < 1; i++) {
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
                    case "set_weather" -> command.succeed("已下发天气切换帧（大雨（夜），30 秒渐变）");
                    default -> command.fail("unexpected action " + command.action());
                }
                served++;
            }
        }, "gm-weather-consumer");
        weatherConsumer.start();
        Map<String, Object> weatherData = Map.of(
                "sessionId", 7,
                "action", "set_weather",
                "params", Map.of("weather", "heavy_rain_501", "fadeSec", 30));
        String weatherQueued = remote.syncRemote("GmRemote", "command", weatherData, String.class);
        weatherConsumer.join(5000);
        assertTrue(JSON.parseObject(weatherQueued).getBooleanValue("ok"),
                "set_weather 应白名单透传: " + weatherQueued);

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
