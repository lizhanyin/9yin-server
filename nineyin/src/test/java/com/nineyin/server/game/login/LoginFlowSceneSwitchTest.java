package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.session.SessionMachine;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 传送三动作（Go sceneRuntime.SwitchScene/TeleportWithinScene 的 Java 移植）：
 * switch_scene_preset 走 0x0C 重入链（延迟 0x0B+0x0D、残余 0x0A/0x09 忽略、目标
 * 0x09 一次性收尾 0x1F+0x10+行恢复+物化）；teleport 仅单帧 0x1F；加载中拒绝。
 */
class LoginFlowSceneSwitchTest {

    /** Go 仓库根（share 资源）；默认 G: 机器，可用 GO_SERVER_ROOT 环境变量指向本地克隆。 */
    private static final Path GO_SERVER_ROOT = Path.of(
            System.getenv().getOrDefault("GO_SERVER_ROOT", "G:/pojie/ai/9yin-go-server1"));

    @BeforeAll
    static void needsGoResources() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/item")),
                "Go 仓库 share 资源不可用，跳过（重入行恢复需要经脉/装备表）");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    /** 手动调度器沿用 LoginFlowTest.ManualScheduler（同包复用；延迟任务由测试显式触发）。 */

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final GmHub hub = new GmHub();
        final LoginSession session = new LoginSession();
        final LoginFlowTest.ManualScheduler scheduler = new LoginFlowTest.ManualScheduler();
        final LoginFlow flow;
        final Connection connection;

        Env() {
            connection = new Connection(channel);
            session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
            session.visual = new RoleVisual(0, "icon\\players\\boy01_create.png",
                    "face", "obj\\char\\b_hair\\b_hair1", "cloth", "pants", "shoes", "action");
            session.selected = new Roles.RoleSnapshot(1, 0, "李逍遥", false, 7L, 1L,
                    new Roles.Appearance(1, List.of()), born04Location(7L));
            session.world = new SceneLifecycle(connection, scheduler);
            session.world.begin("ini\\scene\\born04_QianDengZhen", "born04");
            toInWorld(session.machine);
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, scheduler);
        }

        GmCommand enqueue(String action, Map<String, Object> params) {
            GmCommand command = new GmCommand(session.machine.id(), action, new JSONObject(params));
            hub.enqueue(command);
            return command;
        }

        byte[] readFrame() {
            return channel.readOutbound();
        }

        /** 排空并按帧首字节归类（跳过重入行恢复的多帧内容）。 */
        List<Integer> drainFrameOpcodes() {
            List<Integer> opcodes = new ArrayList<>();
            byte[] frame;
            while ((frame = readFrame()) != null) {
                opcodes.add(frame[0] & 0xFF);
            }
            return opcodes;
        }
    }

    private static Roles.Location born04Location(long version) {
        return new Roles.Location(new Roles.Scene("ini\\scene\\born04_QianDengZhen", "born04"),
                new Roles.Position(377.371f, 20.269f, 1021.644f, 2.930f), version);
    }

    private static void toInWorld(SessionMachine machine) {
        assertNull(machine.loginCompleted(true));
        assertNull(machine.roleChosen());
        assertNull(machine.ready());
        assertNull(machine.activity());
        assertEquals(SessionMachine.Phase.IN_WORLD, machine.phase());
    }

    @Test
    void presetSwitchRunsFullReentryChain() {
        Env env = new Env();
        GmCommand command = env.enqueue("switch_scene_preset", Map.of("preset", "yanjing"));

        env.flow.drainGmCommands(env.connection, env.session);

        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).ok(), "预设切图应成功");
        // 0x0C 退场帧（1 字节）即刻写出；相位进入重入窗口。
        byte[] exitFrame = env.readFrame();
        assertNotNull(exitFrame, "应写出 0x0C");
        assertEquals(0x0C, exitFrame[0] & 0xFF);
        assertEquals(1, exitFrame.length);
        assertEquals(SessionMachine.Phase.ENTERING_WORLD, env.session.machine.phase());
        assertTrue(env.session.awaitingReentryReady, "重入屏障应立起");
        assertFalse(env.session.reentryFramesSent, "延迟帧任务未触发前不允许收尾");

        // 旧场景残余 0x0A / 早到 0x09：忽略（不写帧、不开物化屏障、相位不动）。
        assertTrue(env.flow.dispatch(env.connection, env.session, new byte[]{0x0A}));
        assertTrue(env.flow.dispatch(env.connection, env.session, new byte[]{0x09}));
        assertNull(env.readFrame(), "残余包不应触发任何帧");
        assertTrue(env.session.awaitingReentryReady);
        assertEquals(SessionMachine.Phase.ENTERING_WORLD, env.session.machine.phase());

        // 750ms 延迟任务：0x0B 进场 + 0x0D 玩家归档。
        assertEquals(1, env.scheduler.tasks.size(), "应恰好登记一个延迟帧任务");
        env.scheduler.tasks.remove(0).run();
        byte[] entryFrame = env.readFrame();
        assertNotNull(entryFrame, "应写出 0x0B");
        assertEquals(0x0B, entryFrame[0] & 0xFF);
        byte[] addObject = env.readFrame();
        assertNotNull(addObject, "应写出 0x0D");
        assertEquals(0x0D, addObject[0] & 0xFF);
        assertTrue(env.session.reentryFramesSent);

        // 目标场景 0x09：延迟 0x1F + 0x10 + 行恢复 + 物化，相位回 InWorld。
        assertTrue(env.flow.dispatch(env.connection, env.session, new byte[]{0x09}));
        byte[] location = env.readFrame();
        assertNotNull(location, "应补延迟 0x1F");
        assertEquals(0x1F, location[0] & 0xFF);
        byte[] vitals = env.readFrame();
        assertNotNull(vitals, "应补 0x10 全量");
        assertEquals(0x10, vitals[0] & 0xFF);
        assertFalse(env.readFrame() == null, "行恢复链应继续写帧");
        assertEquals(SessionMachine.Phase.IN_WORLD, env.session.machine.phase());
        assertFalse(env.session.awaitingReentryReady, "屏障应撤下");
        assertTrue(env.session.qingGongGranted && env.session.neigongGranted
                && env.session.meridianGranted, "一次性行标志应复位后重授");
        assertNull(env.session.reentryLocation);

        // 面板快照：场景与 lastAction 已更新。
        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertEquals("ini\\scene\\city01_YanJing", snapshot.sceneConfig);
        assertEquals("city01", snapshot.sceneResource);
        assertEquals("已传送到 燕京", snapshot.lastAction);
    }

    @Test
    void sceneSwitchRearmsKeybindPushForPropertyReplay() {
        // 键位四属性（198/199/200/223）挂玩家属性视图，0x0C 清对象后随 addObject
        // 重建而丢（2026-11-04 真机跨场景丢数据）——重入收尾必须复位重放闸。
        // MySQL 集成式：scratch 角色种四列，切图收尾后单属性帧逐一下发。
        String dsn = com.nineyin.util.MySqlEnv.loadDsn()
                .orElse("nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([^:@/]+):([^@]*)@tcp\\(([^):]+):(\\d+)\\)/([^?]+)$").matcher(dsn);
        org.junit.jupiter.api.Assumptions.assumeTrue(m.matches(), "DSN 形态不符，跳过");
        final long role = 990032L;
        java.sql.Connection jdbc = null;
        try {
            jdbc = java.sql.DriverManager.getConnection(
                    "jdbc:mysql://" + m.group(3) + ":" + m.group(4) + "/" + m.group(5)
                            + "?useSSL=false&connectTimeout=3000&socketTimeout=3000"
                            + "&allowPublicKeyRetrieval=true",
                    m.group(1), m.group(2));
            org.junit.jupiter.api.Assumptions.assumeTrue(jdbc.isValid(2), "MySQL 不可用，跳过");
            try (java.sql.PreparedStatement seed = jdbc.prepareStatement(
                    "INSERT INTO role_keybinds (role_id, bind_value, shortcut_key1, "
                            + "shortcut_key2, operate_mode) VALUES (?, ?, ?, ?, 1) "
                            + "ON DUPLICATE KEY UPDATE bind_value=VALUES(bind_value), "
                            + "shortcut_key1=VALUES(shortcut_key1), "
                            + "shortcut_key2=VALUES(shortcut_key2), "
                            + "operate_mode=VALUES(operate_mode)")) {
                seed.setLong(1, role);
                seed.setString(2, "1,87;2,83;");
                seed.setString(3, "1,87;2,83;");
                seed.setString(4, "1,81;2,69;");
                seed.executeUpdate();
            }
            Env env = new Env();
            env.session.selected = new Roles.RoleSnapshot(role, 0, "李逍遥", false, 7L, 1L,
                    new Roles.Appearance(1, List.of()), born04Location(7L));
            com.nineyin.server.world.db.RoleInventoryStore store =
                    com.nineyin.server.world.db.RoleInventoryStore.open(dsn);
            try {
                java.lang.reflect.Field field = LoginFlow.class.getDeclaredField("inventoryStore");
                field.setAccessible(true);
                field.set(env.flow, store);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("inject inventoryStore", e);
            }
            env.session.keybindPushed = true;
            env.enqueue("switch_scene_preset", Map.of("preset", "yanjing"));
            env.flow.drainGmCommands(env.connection, env.session);
            env.scheduler.tasks.remove(0).run();      // 0x0B + 0x0D 延迟帧
            assertTrue(env.flow.dispatch(env.connection, env.session, new byte[]{0x09}),
                    "目标场景 0x09 收尾应成功");

            java.util.Set<Integer> propIdx = new java.util.HashSet<>();
            byte[] frame;
            while ((frame = env.readFrame()) != null) {
                if ((frame[0] & 0xFF) == 0x10 && frame.length > 14) {
                    propIdx.add((frame[12] & 0xFF) | (frame[13] & 0xFF) << 8);
                }
            }
            assertTrue(propIdx.contains(198) && propIdx.contains(199)
                    && propIdx.contains(200) && propIdx.contains(223),
                    "重入收尾应重推键位四属性，实际=" + propIdx);
        } catch (java.sql.SQLException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "MySQL 不可用，跳过: " + e.getMessage());
        } finally {
            if (jdbc != null) {
                try (java.sql.PreparedStatement cleanup = jdbc.prepareStatement(
                        "DELETE FROM role_keybinds WHERE role_id = ?")) {
                    cleanup.setLong(1, role);
                    cleanup.executeUpdate();
                } catch (java.sql.SQLException ignored) {
                    // 清理失败不判失败（scratch 行幂等覆盖）。
                }
                try {
                    jdbc.close();
                } catch (java.sql.SQLException ignored) {
                    // 关闭失败忽略。
                }
            }
        }
    }

    @Test
    void sceneSwitchReplaysShortcutRowsAfterExitScene() {
        // ExitScene 清玩家对象挂接记录行（连接级 schema 存活）——重入收尾必须按内存
        // 快照整表重放 shortcut_rec（record=93），会话内编辑（200/201）随之保留；
        // starter 模式无 DB，内存即唯一事实源（Go reentry grantShortcutRows 同款）。
        Env env = new Env();
        // 会话内编辑缩影：idx40 设 A 再替换为 B（替换=删旧追新，B 应存活重放）。
        env.flow.handleSetShortcut(env.connection, env.session, shortcutMessage(200, 40, "skill", "CS_jh_xfz01"));
        env.flow.handleSetShortcut(env.connection, env.session, shortcutMessage(200, 40, "skill", "CS_wd_tjq01"));
        env.drainFrameOpcodes();

        env.enqueue("switch_scene_preset", Map.of("preset", "yanjing"));
        env.flow.drainGmCommands(env.connection, env.session);
        env.scheduler.tasks.remove(0).run();      // 0x0B + 0x0D 延迟帧
        assertTrue(env.flow.dispatch(env.connection, env.session, new byte[]{0x09}),
                "目标场景 0x09 收尾应成功");

        Map<Integer, String> shortcutIds = new java.util.HashMap<>();
        int duplicateRows = 0;
        byte[] frame;
        while ((frame = env.readFrame()) != null) {
            if ((frame[0] & 0xFF) != 0x11 || frame.length < 16
                    || ((frame[10] & 0xFF) | (frame[11] & 0xFF) << 8) != 93) {
                continue;
            }
            int index = (frame[16] & 0xFF) | (frame[17] & 0xFF) << 8
                    | (frame[18] & 0xFF) << 16 | (frame[19] & 0xFF) << 24;
            // 列区 [u32 index][str kind][str id]：str = u32 len(含 NUL) + utf8 + NUL。
            int offset = 20;
            String id = "";
            for (int cell = 0; cell < 2 && offset + 4 <= frame.length; cell++) {
                int len = ((frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                        | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24) - 1;
                offset += 4;
                String text = new String(frame, offset, len, java.nio.charset.StandardCharsets.UTF_8);
                offset += len + 1;
                if (cell == 1) {
                    id = text;
                }
            }
            duplicateRows += shortcutIds.put(index, id) != null ? 1 : 0;
        }
        assertEquals("CS_wd_tjq01", shortcutIds.get(40), "会话内替换值应整表重放");
        assertTrue(shortcutIds.containsKey(9), "打坐 idx9 应随链补授/重放");
        for (int index = 10; index <= 16; index++) {
            assertTrue(shortcutIds.containsKey(index), "starter idx" + index + " 应重放");
        }
        assertEquals(0, duplicateRows, "重放不得产生重复行");
    }

    /** 0x1E 直达 [msgId, int, str, str]（200 设快捷栏）。 */
    private static com.nineyin.server.protocol.CustomC2S.Message shortcutMessage(
            int msgId, int index, String kind, String id) {
        com.nineyin.server.protocol.PacketWriter writer =
                com.nineyin.server.protocol.PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(4);
        writer.writeU8(2).writeU32(msgId).writeU8(2).writeU32(index);
        byte[] kindBytes = kind.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(kindBytes.length + 1).writeBytes(kindBytes).writeU8(0);
        byte[] idBytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(idBytes.length + 1).writeBytes(idBytes).writeU8(0);
        return com.nineyin.server.protocol.CustomC2S.parseDirect(writer.toBytes());
    }

    @Test
    void teleportWithinSceneWritesSingleLocationFrame() {
        Env env = new Env();
        GmCommand command = env.enqueue("teleport", Map.of("position", "100.5,20,300,1.5"));

        env.flow.drainGmCommands(env.connection, env.session);

        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).ok(), "场景内瞬移应成功");
        List<Integer> opcodes = env.drainFrameOpcodes();
        assertEquals(List.of(0x1F), opcodes, "仅一帧 0x1F，不退场不读图");
        assertEquals(SessionMachine.Phase.IN_WORLD, env.session.machine.phase());
        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertEquals(100.5f, snapshot.x, 1e-3f);
        assertEquals(300f, snapshot.z, 1e-3f);
    }

    @Test
    void teleportAndSwitchRejectedWhileSceneLoading() {
        Env env = new Env();
        GmCommand enter = env.enqueue("switch_scene_preset", Map.of("preset", "wudang"));
        env.flow.drainGmCommands(env.connection, env.session);
        assertTrue(enter.awaitReply(java.time.Duration.ofMillis(1)).ok());

        GmCommand teleport = env.enqueue("teleport", Map.of("position", "1,2,3,0"));
        GmCommand again = env.enqueue("switch_scene_preset", Map.of("preset", "emei"));
        env.flow.drainGmCommands(env.connection, env.session);

        assertFalse(teleport.awaitReply(java.time.Duration.ofMillis(1)).ok(), "加载中瞬移应拒绝");
        assertTrue(teleport.awaitReply(java.time.Duration.ofMillis(1)).message().contains("加载"));
        assertFalse(again.awaitReply(java.time.Duration.ofMillis(1)).ok(), "加载中二次切图应拒绝");
    }

    @Test
    void unknownPresetAndBadDestinationFailWithoutFrames() {
        Env env = new Env();
        GmCommand unknown = env.enqueue("switch_scene_preset", Map.of("preset", "nonesuch"));
        GmCommand badDest = env.enqueue("switch_scene", Map.of("destination", "city01"));
        GmCommand badPos = env.enqueue("teleport", Map.of("position", "1,2"));
        GmCommand badRange = env.enqueue("teleport", Map.of("position", "999999,2,3,0"));

        env.flow.drainGmCommands(env.connection, env.session);

        assertFalse(unknown.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(unknown.awaitReply(java.time.Duration.ofMillis(1)).message().contains("nonesuch"));
        assertFalse(badDest.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(badDest.awaitReply(java.time.Duration.ofMillis(1)).message().contains("config,resource"));
        assertFalse(badPos.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertFalse(badRange.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertNull(env.readFrame(), "失败命令不应写帧");
    }

    @Test
    void switchSceneParsesFreeFormDestination() {
        Env env = new Env();
        GmCommand command = env.enqueue("switch_scene", Map.of(
                "destination", "ini\\scene\\city02_SuZhou,city02,438.140,0.800,1188.282,-2.325"));

        env.flow.drainGmCommands(env.connection, env.session);

        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).ok(), "自定义切图应成功");
        byte[] exitFrame = env.readFrame();
        assertNotNull(exitFrame);
        assertEquals(0x0C, exitFrame[0] & 0xFF);
        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertEquals("city02", snapshot.sceneResource);
        assertEquals(438.140f, snapshot.x, 1e-3f);
        // 版本沿用当前乐观版本 7（Go 同款保留 previous.Version）。
        assertEquals(7L, env.session.reentryLocation.version());
    }

    @Test
    void switchFailsBeforeEnteringScene() {
        Env env = new Env();
        env.session.world = null;
        env.session.selected = null;
        GmCommand command = env.enqueue("switch_scene_preset", Map.of("preset", "yanjing"));

        env.flow.drainGmCommands(env.connection, env.session);

        assertFalse(command.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertEquals("角色尚未进入场景", command.awaitReply(java.time.Duration.ofMillis(1)).message());
        assertNull(env.readFrame());
    }

    /** 选角 0x04 显示名仍按新场景解析（SceneNames 表不受 GM 切图影响，仅回归入口）。 */
    @Test
    void entrySceneFrameCarriesTargetSceneStrings() {
        Env env = new Env();
        GmCommand command = env.enqueue("switch_scene_preset", Map.of("preset", "shaolin"));
        env.flow.drainGmCommands(env.connection, env.session);
        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).ok());
        env.readFrame(); // 0x0C
        env.scheduler.tasks.remove(0).run();
        byte[] entry = env.readFrame();
        assertNotNull(entry);
        String text = new String(entry, java.nio.charset.StandardCharsets.US_ASCII);
        // 0x0B 帧内带 config/resource 字符串（GBK/ASCII 混排，按子串断言）。
        assertTrue(text.contains("school08_ShaoLin"), "0x0B 应携带目标场景 config");
        assertTrue(text.contains("school08"));
        // 0x1F 延迟到 0x09（此时还没发）：0x0D 之后无更多帧。
        assertNotNull(env.readFrame()); // 0x0D
        assertNull(env.readFrame(), "0x1F/0x10 必须等目标场景 0x09");
    }
}
