package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.RecordSchemas;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.npc.NpcResolver;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.scene.SceneLifecycle;
import com.nineyin.util.ServerPaths;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 任务二期进度链：接取下发全部步骤行（story+hunter+interact）→ 交互点击推进
 * （Task_Record 删+加）→ 提交门控（进度未满不上可交菜单/拒绝提交）→ GM 补满
 * （步骤行 + Task_Accepted 状态行回刷 + 头顶标记）→ 提交（步骤行删除 + 位图 +
 * 奖励）→ 行序镜像（乱序接取 + 状态回刷移尾后的删行号寻址）。
 *
 * <p>合成 mini-share（@TempDir 内六源 TSV）驱动，不依赖真实资源树；
 * ResourceCache 按路径键隔离，ServerPaths 覆盖在 @AfterAll 还原。
 */
class TaskProgressFlowTest {

    @TempDir
    static Path shareRoot;

    private static Path previousRoot;

    @BeforeAll
    static void installMiniShare() throws Exception {
        // ServerPaths 覆盖的是服务端根：六源 TSV 落在 resources/modern/share 下。
        Path share = shareRoot.resolve(Path.of("resources", "modern", "share"));
        // 主任务表：80=进度任务（hunter+interact+story），70=纯对话任务。
        write(share.resolve(Path.of("task", "task", "task", "task_flow.txt")),
                "STRING\tSTRING\r\n"
                        + "ID\tPlot\tStep\tLine\tAcceptNpc\tSubmitNpc\tSceneID\tAutoAcceptID\tAutoSubmit\tPreTaskLimit\tNextTaskLimit\tTitleId\tContextId\tCompleteDialogId\tTaskTargetId\tTrackInfoID\tCanGiveup\tTimeLimitLength",
                "80\t\t1\t1\tnpc_flow_a\tnpc_flow_b\t448\t0\t0\t\t\ttitle_80\tcontext_80\tcomplete_80\ttarget_80\tnext_80\t1\t0",
                "70\t\t1\t1\tnpc_flow_a\tnpc_flow_b\t448\t0\t0\t\t\ttitle_70\tcontext_70\tcomplete_70\ttarget_70\tnext_70\t1\t0");
        write(share.resolve(Path.of("task", "task", "prize", "prize_flow.txt")),
                "STRING\tSTRING\r\nID\tTitle\tFaculty\tNormalCapital",
                "80\t勤修\t100\t0");
        write(share.resolve(Path.of("task", "task", "subfunction", "subfunction_flow.txt")),
                "STRING\tSTRING\tSTRING\tSTRING\r\nID\tType\tTypeID\tOrder",
                "80\tstory\t900\t1",
                "80\thunter\t75701\t2",
                "80\tinteract\t75801\t3");
        write(share.resolve(Path.of("task", "task", "huntlist", "huntlist_flow.txt")),
                "STRING\tSTRING\tSTRING\tSTRING\tSTRING\r\nID\tNpcId\tCount\tTrackInfo\tSceneID",
                "75701\tmon_flow_001\t2\thunterinfo_75701\t448");
        write(share.resolve(Path.of("task", "task", "interactivelist", "interactivelist_flow.txt")),
                "STRING\tSTRING\tSTRING\tSTRING\r\nID\tNpcId\tSceneID\tTrackInfo",
                "75801\twd_flow_001\t448\tinteractinfo_75801");
        write(share.resolve(Path.of("npc", "npcconfig", "worldnpc", "flow.txt")),
                "注释\t注释\t注释\t注释\r\nSTRING\tSTRING\tSTRING\tSTRING\r\n"
                        + "ID\tName\ttable@TaskCanAccept\ttable@TaskCanSubmit",
                "npc_flow_a\t测试甲\t80;70;\t",
                "npc_flow_b\t测试乙\t\t80;70;");
        previousRoot = ServerPaths.serverRoot();
        ServerPaths.overrideServerRoot(shareRoot);
    }

    @AfterAll
    static void restoreRoot() {
        if (previousRoot != null) {
            ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /** 不跑定时任务的调度器（进度链无 tick 依赖）。 */
    static final class NoopScheduler extends com.nineyin.server.world.fight.SkillCastHandlerFlowTest.LoginFlowTestSchedulerAdapter {
        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay,
                                                                java.util.concurrent.TimeUnit unit) {
            return null;
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long period, java.util.concurrent.TimeUnit unit) {
            return null;
        }
    }

    private static final class Wiring {

        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final LoginFlow flow;
        final LoginSession session = new LoginSession();
        final GmHub hub = new GmHub();

        Wiring() throws Exception {
            java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("task-progress");
            flow = new LoginFlow(new RoleStoreFacade(JsonRoleRepository.open(temp.resolve("roles.json"))),
                    FacultyStore.open(temp.resolve("faculty.json")), hub,
                    Clock.systemUTC(), null, new NoopScheduler(), null, null);
            session.player = PlayerActor.create("进度测试", 99999, Clock.systemUTC());
            session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                    990_041L, 0, "进度测试", false, 1, 1,
                    new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                    new com.nineyin.server.db.role.Roles.Location(
                            new com.nineyin.server.db.role.Roles.Scene("born04", "born04"),
                            new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
            session.world = new SceneLifecycle(connection, new NoopScheduler());
            session.world.begin("ini\\scene\\born04", "born04");
        }

        /** 非战斗 NPC 物化（对话/交互目标）。 */
        void registerNpc(int objectId, String configId) {
            NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                    "wiring-" + configId, configId, "commonnpc", null);
            resolved.properties.put("Name", PropertyValues.Value.ofWideString(configId));
            NpcSpawn npc = new NpcSpawn(resolved, 700f, 0f, 700f, 0f);
            session.world.registerNpc(objectId, npc.cloneSpawn());
            List<NpcSpawn> catalog = new ArrayList<>(session.sceneNpcs);
            while (catalog.size() < objectId - 1) {
                catalog.add(null);
            }
            catalog.add(objectId - 2, npc);
            session.sceneNpcs = java.util.Collections.unmodifiableList(catalog);
            session.activeNpcs.add(objectId - 2);
        }

        void click(int objectId, int funcId) {
            byte[] frame = new byte[33];
            frame[0] = 0x07;
            writeU32(frame, 17, 1);
            writeU32(frame, 21, objectId);
            writeU32(frame, 25, 1);
            writeU32(frame, 29, funcId);
            assertTrue(flow.dispatch(connection, session, frame));
        }

        void gmTaskProgress(int taskId) {
            GmCommand command = new GmCommand(session.machine.id(), "task_progress",
                    new JSONObject(taskId > 0 ? Map.of("taskId", taskId) : Map.of()));
            hub.enqueue(command);
            flow.drainGmCommands(connection, session);
            assertTrue(command.awaitReply(java.time.Duration.ofMillis(1000)).ok(),
                    "task_progress 应成功");
        }

        List<byte[]> drain() {
            List<byte[]> frames = new ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    @Test
    void acceptSendsAllStepRowsAndInteractClickAdvances() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerNpc(2, "npc_flow_a");
        wiring.registerNpc(3, "wd_flow_001");
        wiring.registerNpc(4, "npc_flow_b");

        // 点接取 NPC：任务专线菜单（可接 80/70 + 告辞），mark1 = 接 80。
        wiring.click(2, 0);
        List<byte[]> menuFrames = wiring.drain();
        assertEquals(2, menuFrames.size(), "LastObject + 菜单");
        List<String> texts = new ArrayList<>();
        List<int[]> marks = menuMarks(menuFrames.get(1), texts);
        assertEquals(List.of("title_80", "title_70", "ui_form_talk_leave"), texts);

        wiring.click(2, marks.get(0)[1]);
        List<byte[]> acceptFrames = wiring.drain();
        // 0x1D 清菜单 + Task_Accepted 行 + story/hunter/interact 三步骤行 + 327 sub=9。
        List<Integer> ops = acceptFrames.stream().map(f -> f[0] & 0xFF).toList();
        assertEquals(List.of(0x1D, 0x11, 0x11, 0x11, 0x11, 0x27), ops, "接取帧序");
        assertTrue(wiring.session.player.task().isAccepted(80));
        assertEquals(0, wiring.session.player.task().progressOf(80, "75701"), "hunter 计数初始化 0");
        // 三条步骤行：Task_Record 表，subtype 6/1/3。
        List<int[]> stepSubtypes = new ArrayList<>();
        for (byte[] frame : acceptFrames) {
            if ((frame[0] & 0xFF) == 0x11 && u16(frame, 10) == RecordSchemas.indexByName("Task_Record")) {
                stepSubtypes.add(new int[]{leInt(frame, 16), leInt(frame, 20)});
            }
        }
        assertEquals(3, stepSubtypes.size(), "三个步骤行");
        assertEquals(List.of(6, 1, 3), stepSubtypes.stream().map(s -> s[1]).toList(),
                "story/hunter/interact 子类型");

        // 点交互目标 NPC：interact 步骤 1/1（Task_Record 删+加），任务未满不回刷已接行。
        wiring.click(3, 0);
        List<byte[]> interactFrames = wiring.drain();
        List<Integer> interactOps = interactFrames.stream().map(f -> f[0] & 0xFF).toList();
        assertEquals(List.of(0x12, 0x11, 0x10), interactOps, "交互推进帧序 + LastObject");
        byte[] interactAdd = interactFrames.get(1);
        assertEquals(1, leInt(interactAdd, 32), "interact curnum=1");
        assertEquals(1, wiring.session.player.task().progressOf(80, "75801"));

        // 进度未满：交任务 NPC 不出现可交菜单项（无 0x1C）。
        wiring.click(4, 0);
        List<byte[]> gated = wiring.drain();
        assertTrue(gated.stream().noneMatch(f -> (f[0] & 0xFF) == 0x1C), "门控：未满不上可交菜单");
        assertFalse(wiring.session.player.task().isProgressComplete(
                com.nineyin.server.game.avatar.task.TaskCatalog.load(), 80));
    }

    @Test
    void fillAndSubmitCompleteTheLoop() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerNpc(2, "npc_flow_a");
        wiring.registerNpc(4, "npc_flow_b");
        wiring.click(2, 0);
        List<byte[]> menuFrames = wiring.drain();
        List<int[]> marks = menuMarks(menuFrames.get(1), new ArrayList<>());
        wiring.click(2, marks.get(0)[1]); // 接 80
        wiring.drain();

        // GM 补满：hunter+interact 两步骤行删+加 + Task_Accepted 状态行删+加 + 头顶标记 sub=15。
        wiring.gmTaskProgress(80);
        List<byte[]> fillFrames = wiring.drain();
        List<Integer> fillOps = fillFrames.stream().map(f -> f[0] & 0xFF).toList();
        assertEquals(List.of(0x12, 0x11, 0x12, 0x11, 0x12, 0x11, 0x27), fillOps, "补满帧序");
        assertEquals(2, wiring.session.player.task().progressOf(80, "75701"));
        assertEquals(com.nineyin.server.game.avatar.task.PlayerTask.STATUS_CAN_SUBMIT,
                wiring.session.player.task().acceptedTask(80).status());

        // 交任务 NPC 菜单出现可交项 → mark 提交。
        wiring.click(4, 0);
        List<byte[]> submitMenu = wiring.drain();
        List<String> submitTexts = new ArrayList<>();
        List<int[]> submitMarks = menuMarks(submitMenu.get(1), submitTexts);
        assertEquals(List.of("title_80", "ui_form_talk_leave"), submitTexts, "仅可交任务 + 告辞");

        wiring.click(4, submitMarks.get(0)[1]);
        List<byte[]> submitFrames = wiring.drain();
        // 0x1D + 0x12 已接行 + 0x12×3 步骤行（降序）+ 位图删加 + 327 sub=8 + 0x10 历练刷新。
        List<Integer> submitOps = submitFrames.stream().map(f -> f[0] & 0xFF).toList();
        assertEquals(List.of(0x1D, 0x12, 0x12, 0x12, 0x12, 0x12, 0x11, 0x27, 0x10), submitOps,
                "提交帧序");
        assertTrue(wiring.session.player.task().isCompleted(80));
        assertFalse(wiring.session.player.task().isAccepted(80));
        assertTrue(wiring.session.taskRecordRowKeys.isEmpty(), "步骤行镜像清空");
    }

    @Test
    void outOfOrderAcceptAndStatusRefreshKeepRowNumbersAligned() throws Exception {
        Wiring wiring = new Wiring();
        wiring.registerNpc(2, "npc_flow_a");
        wiring.registerNpc(4, "npc_flow_b");

        // 乱序接取：先 80 后 70（镜像 [80, 70]，非升序）。
        wiring.click(2, 0);
        List<int[]> marks = menuMarks(wiring.drain().get(1), new ArrayList<>());
        wiring.click(2, marks.get(0)[1]);
        wiring.drain();
        wiring.click(2, 0);
        marks = menuMarks(wiring.drain().get(1), new ArrayList<>());
        wiring.click(2, marks.get(0)[1]); // 二次菜单仅剩 title_70 可接
        wiring.drain();
        assertEquals(List.of(80, 70), wiring.session.taskAcceptedRowOrder, "镜像=接取序");

        // 补满 80 → 状态回刷把 80 移到镜像尾（[70, 80]）。
        wiring.gmTaskProgress(80);
        wiring.drain();
        assertEquals(List.of(70, 80), wiring.session.taskAcceptedRowOrder, "回刷移尾");

        // 提交 80：删行号必须按镜像 = starter 15 + 1（升序口径会错发 15）。
        wiring.click(4, 0);
        List<int[]> submitMarks = menuMarks(wiring.drain().get(1), new ArrayList<>());
        // 可交项顺序 = TaskCanSubmit 配置序（80 在前）→ mark1 = 提交 80。
        wiring.click(4, submitMarks.get(0)[1]);
        List<byte[]> submitFrames = wiring.drain();
        int starterRows = com.nineyin.server.game.starter.StarterQingGong.acceptedTaskIds().size();
        byte[] acceptedDel = submitFrames.stream()
                .filter(f -> (f[0] & 0xFF) == 0x12
                        && u16(f, 10) == RecordSchemas.indexByName("Task_Accepted"))
                .findFirst().orElseThrow();
        assertEquals(starterRows + 1, u16(acceptedDel, 12), "删行号按镜像寻址（80 已移尾）");

        // 再提交 70（纯对话任务，无需进度）：行号回到 starter+0。
        wiring.click(4, 0);
        submitMarks = menuMarks(wiring.drain().get(1), new ArrayList<>());
        wiring.click(4, submitMarks.get(0)[1]);
        submitFrames = wiring.drain();
        byte[] secondDel = submitFrames.stream()
                .filter(f -> (f[0] & 0xFF) == 0x12
                        && u16(f, 10) == RecordSchemas.indexByName("Task_Accepted"))
                .findFirst().orElseThrow();
        assertEquals(starterRows, u16(secondDel, 12), "70 现为镜像首行");
    }

    // ------------------------------------------------------------------ helpers

    private static void write(Path file, String... lines) throws Exception {
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append("\r\n");
        }
        Files.write(file, text.toString().getBytes(Charset.forName("GBK")));
    }

    /** 解析 0x1C 菜单：返回 {type, mark}，texts 收集每项文本 key。 */
    private static List<int[]> menuMarks(byte[] frame, List<String> texts) {
        assertEquals(0x1C, frame[0] & 0xFF);
        int count = u16(frame, 9);
        List<int[]> marks = new ArrayList<>();
        int offset = 11;
        for (int i = 0; i < count; i++) {
            int type = frame[offset] & 0xFF;
            int mark = u16(frame, offset + 1);
            int byteLen = u32(frame, offset + 3);
            String text = new String(frame, offset + 7, byteLen - 2,
                    java.nio.charset.StandardCharsets.UTF_16LE);
            marks.add(new int[]{type, mark});
            texts.add(text);
            offset += 7 + byteLen;
        }
        return marks;
    }

    private static void writeU32(byte[] frame, int off, int value) {
        frame[off] = (byte) value;
        frame[off + 1] = (byte) (value >>> 8);
        frame[off + 2] = (byte) (value >>> 16);
        frame[off + 3] = (byte) (value >>> 24);
    }

    private static int u16(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
    }

    private static int u32(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8
                | (frame[off + 2] & 0xFF) << 16 | (frame[off + 3] & 0xFF) << 24;
    }

    private static int leInt(byte[] frame, int offset) {
        return u32(frame, offset);
    }
}
