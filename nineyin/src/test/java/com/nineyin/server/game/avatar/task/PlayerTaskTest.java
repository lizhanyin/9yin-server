package com.nineyin.server.game.avatar.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 任务状态机：接取校验（配置/前置/查重）→ 提交（位图置位）/ 放弃 / 快照恢复。
 */
class PlayerTaskTest {

    private static TaskCatalog catalog() {
        // 空目录构造（loadShare 对缺失目录返回空索引）
        return TaskCatalog.loadShare(java.nio.file.Path.of("nonexistent-share-root"));
    }

    /** 两任务目录：50 无前置；51 前置 50。 */
    private static TaskCatalog twoTaskCatalog() {
        return new TaskCatalog(
                java.util.Map.of(
                        50, taskDef(50, List.of()),
                        51, taskDef(51, List.of(50))),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of());
    }

    /**
     * 进度任务目录：80 含 hunter 75701（×2）+ interact 75801 + story；
     * 81 纯对话（无进度步骤）。
     */
    private static TaskCatalog progressCatalog() {
        return new TaskCatalog(
                java.util.Map.of(
                        80, taskDef(80, List.of()),
                        81, taskDef(81, List.of())),
                java.util.Map.of(),
                java.util.Map.of(
                        80, List.of(
                                new TaskCatalog.Step(80, "story", "900", 1),
                                new TaskCatalog.Step(80, "hunter", "75701", 2),
                                new TaskCatalog.Step(80, "interact", "75801", 3)),
                        81, List.of(new TaskCatalog.Step(81, "story", "901", 1))),
                java.util.Map.of(
                        75701, new TaskCatalog.HuntDef(75701, "newmp_ut_001", 2, "hunterinfo_75701", 448)),
                java.util.Map.of(
                        75801, new TaskCatalog.InteractDef(75801, "wd_ut_001", 448, "interactinfo_75801")),
                java.util.Map.of());
    }

    private static TaskCatalog.TaskDef taskDef(int id, List<Integer> pre) {
        return new TaskCatalog.TaskDef(id, 1, "", 1, "npc_a", "npc_b", 448, 0, 0,
                pre, List.of(), "", "", "", "", "title_" + id, "context_" + id,
                "complete_" + id, "target_" + id, "next_" + id, 1, 0);
    }

    @Test
    void acceptValidatesConfigDuplicateAndPreTask() {
        TaskCatalog catalog = twoTaskCatalog();
        PlayerTask task = new PlayerTask();

        assertEquals(PlayerTask.Reject.NOT_CONFIGURED, task.accept(catalog, 999, 0).reject());
        assertTrue(task.accept(catalog, 50, 1000).ok());
        assertEquals(PlayerTask.Reject.ALREADY_ACCEPTED, task.accept(catalog, 50, 1000).reject());
        assertEquals(PlayerTask.Reject.PRE_TASK_NOT_COMPLETED, task.accept(catalog, 51, 1000).reject());

        assertNull(task.complete(999), "未接任务提交无操作");
        assertNotNull(task.complete(50));
        assertTrue(task.isCompleted(50));
        assertEquals(PlayerTask.Reject.ALREADY_COMPLETED, task.accept(catalog, 50, 1000).reject());
        assertTrue(task.accept(catalog, 51, 1000).ok(), "前置完成后可接");
    }

    @Test
    void completeAndGiveupMoveAcceptedEntries() {
        TaskCatalog catalog = twoTaskCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 50, 1000);

        PlayerTask.Accepted gaveUp = task.giveup(51);
        assertNull(gaveUp, "未接任务放弃无操作");
        assertNotNull(task.giveup(50));
        assertFalse(task.isAccepted(50));
        assertFalse(task.isCompleted(50), "放弃不进完成集");

        task.accept(catalog, 50, 1000);
        PlayerTask.Accepted completed = task.complete(50);
        assertNotNull(completed);
        assertEquals(50, completed.taskId());
        assertEquals(PlayerTask.STATUS_DOING, completed.status());
        assertTrue(task.isCompleted(50));
        assertFalse(task.isAccepted(50), "完成后移出已接表");
    }

    @Test
    void snapshotRestoresAcceptedOrderAndCompletedSet() {
        TaskCatalog catalog = twoTaskCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 50, 2000);
        task.complete(50);
        task.accept(catalog, 51, 1000);

        PlayerTask.Snapshot snapshot = task.snapshot();
        assertEquals(List.of(51), snapshot.accepted().stream()
                .map(PlayerTask.Accepted::taskId).toList());
        assertEquals(List.of(50), snapshot.completed());

        PlayerTask restored = new PlayerTask();
        restored.restore(snapshot);
        assertEquals(List.of(51), restored.acceptedIds());
        assertEquals(List.of(50), restored.completedIds());
        assertEquals(PlayerTask.Reject.ALREADY_ACCEPTED, restored.accept(catalog, 51, 0).reject());

        restored.restore(null);
        assertTrue(restored.acceptedIds().isEmpty(), "null 快照清空态");
    }

    @Test
    void progressAdvancesCapsAndFlipsCanSubmit() {
        TaskCatalog catalog = progressCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 80, 1000);

        // 接取即初始化两个进度步骤计数 0；未满前不可提交。
        assertFalse(task.isProgressComplete(catalog, 80));
        assertEquals(PlayerTask.STATUS_DOING, task.acceptedTask(80).status());

        // hunter ×2：逐次推进，第二次满；story 不参与门控。
        assertTrue(task.advance(catalog, 80, "75701").changed());
        assertEquals(1, task.progressOf(80, "75701"));
        var second = task.advance(catalog, 80, "75701");
        assertTrue(second.changed());
        assertFalse(second.justCompleted(), "interact 步骤仍未满");
        assertEquals(2, task.progressOf(80, "75701"));

        // 封顶：已满步骤再推进无变化。
        assertFalse(task.advance(catalog, 80, "75701").changed());
        assertEquals(2, task.progressOf(80, "75701"));

        // interact 1/1 满的瞬间：任务转可提交。
        var last = task.advance(catalog, 80, "75801");
        assertTrue(last.changed());
        assertTrue(last.justCompleted());
        assertTrue(task.isProgressComplete(catalog, 80));
        assertEquals(PlayerTask.STATUS_CAN_SUBMIT, task.acceptedTask(80).status());

        // 未配置步骤/未知 TypeID 无操作。
        assertFalse(task.advance(catalog, 80, "900").changed(), "story 步骤非进度");
        assertFalse(task.advance(catalog, 80, "99999").changed(), "未知 TypeID");
    }

    @Test
    void taskWithoutProgressStepsIsImmediatelySubmittable() {
        TaskCatalog catalog = progressCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 81, 1000);
        assertTrue(task.isProgressComplete(catalog, 81), "纯对话任务接取即视为完成（一期行为）");
        assertEquals(PlayerTask.STATUS_DOING, task.acceptedTask(81).status());
    }

    @Test
    void fillProgressCompletesAllStepsAtOnce() {
        TaskCatalog catalog = progressCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 80, 1000);

        List<String> filled = task.fillProgress(catalog, 80);
        assertEquals(List.of("75701", "75801"), filled);
        assertTrue(task.isProgressComplete(catalog, 80));
        assertEquals(PlayerTask.STATUS_CAN_SUBMIT, task.acceptedTask(80).status());

        // 再补无变化（GM 幂等）。
        assertTrue(task.fillProgress(catalog, 80).isEmpty());
        // 未接任务无操作。
        assertTrue(task.fillProgress(catalog, 81).isEmpty());
    }

    @Test
    void snapshotRoundTripsProgressAndLegacyArchiveRestores() {
        TaskCatalog catalog = progressCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 80, 1000);
        task.advance(catalog, 80, "75701");

        PlayerTask.Snapshot snapshot = task.snapshot();
        PlayerTask restored = new PlayerTask();
        restored.restore(snapshot);
        assertEquals(1, restored.progressOf(80, "75701"));
        assertTrue(restored.isAccepted(80));

        // 老档（无 progress 节点 → 空 Map）：一期档恢复即视为可交（进度条目缺失
        // 的宽容口径——接取路径总会初始化计数，仅老档/异常档会走到空分支）。
        PlayerTask legacy = new PlayerTask();
        legacy.restore(new PlayerTask.Snapshot(
                List.of(new PlayerTask.Accepted(80, PlayerTask.STATUS_DOING, 1000)),
                List.of(), java.util.Map.of()));
        assertEquals(0, legacy.progressOf(80, "75701"));
        assertTrue(legacy.isProgressComplete(catalog, 80), "老档空进度 → 恒真（一期兼容）");
    }

    @Test
    void giveupAndCompleteClearProgress() {
        TaskCatalog catalog = progressCatalog();
        PlayerTask task = new PlayerTask();
        task.accept(catalog, 80, 1000);
        task.advance(catalog, 80, "75701");

        assertNotNull(task.giveup(80));
        assertEquals(0, task.progressOf(80, "75701"), "放弃清进度");

        task.accept(catalog, 80, 1000);
        task.fillProgress(catalog, 80);
        assertNotNull(task.complete(80));
        assertEquals(0, task.progressOf(80, "75701"), "提交清进度");
        assertTrue(task.isProgressComplete(catalog, 80), "无进度条目恒真");
    }
}
