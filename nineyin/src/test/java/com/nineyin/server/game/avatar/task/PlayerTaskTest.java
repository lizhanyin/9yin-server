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
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
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
}
