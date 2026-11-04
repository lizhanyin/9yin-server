package com.nineyin.server.game.avatar.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 玩家任务运行时状态（挂在 PlayerActor 上）：已接任务表 + 已完成位图集。
 *
 * <p>状态流转（对话型一期）：
 * <ul>
 * <li>accept：校验（配置存在、前置完成、未接未完成）→ isdoing；
 * <li>complete：已接 → 删除已接 + 完成集置位（Task_Completed 位图一基序号）；
 * <li>giveup：已接 → 删除（完成集不动）。
 * </ul>starter 功能解锁族（1069-1073/1344/1140-1147/51002/1346）由
 * StarterQingGong 链独立下发，本状态与其隔离（登录恢复帧去重）。
 */
public final class PlayerTask {

    /** 任务状态（Task_Accepted 列 6 客户端方言：0 进行中 / 1 失败 / 2 可提交）。 */
    public static final int STATUS_DOING = 0;
    public static final int STATUS_FAILED = 1;
    public static final int STATUS_CAN_SUBMIT = 2;

    /** 已接任务（持久化形态；材料列由 TaskDef 即时渲染，不落档）。 */
    public record Accepted(int taskId, int status, long acceptedAtUnixMillis) {
    }

    /** 存档快照：已接列表（任务序升序）+ 已完成任务 ID 集。 */
    public record Snapshot(List<Accepted> accepted, List<Integer> completed) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of());
    }

    private final TreeMap<Integer, Accepted> accepted = new TreeMap<>();
    private final TreeSet<Integer> completed = new TreeSet<>();

    /** 接取任务；返回 null 表示校验失败（原因见 {@link Reject}）。 */
    public AcceptResult accept(TaskCatalog catalog, int taskId, long now) {
        TaskCatalog.TaskDef def = catalog.task(taskId);
        if (def == null) {
            return AcceptResult.reject(Reject.NOT_CONFIGURED);
        }
        if (completed.contains(taskId)) {
            return AcceptResult.reject(Reject.ALREADY_COMPLETED);
        }
        if (accepted.containsKey(taskId)) {
            return AcceptResult.reject(Reject.ALREADY_ACCEPTED);
        }
        for (int pre : def.preTaskLimit()) {
            if (completed.contains(pre)) {
                return AcceptResult.accepted(acceptInternal(taskId, now));
            }
        }
        return def.preTaskLimit().isEmpty()
                ? AcceptResult.accepted(acceptInternal(taskId, now))
                : AcceptResult.reject(Reject.PRE_TASK_NOT_COMPLETED);
    }

    private Accepted acceptInternal(int taskId, long now) {
        Accepted entry = new Accepted(taskId, STATUS_DOING, now);
        accepted.put(taskId, entry);
        return entry;
    }

    /** 提交任务：删除已接 + 完成置位；返回被提交条目，未接返回 null。 */
    public Accepted complete(int taskId) {
        Accepted entry = accepted.remove(taskId);
        if (entry == null) {
            return null;
        }
        completed.add(taskId);
        return entry;
    }

    /** 放弃任务：仅删已接；返回被放弃条目，未接返回 null。 */
    public Accepted giveup(int taskId) {
        return accepted.remove(taskId);
    }

    public boolean isAccepted(int taskId) {
        return accepted.containsKey(taskId);
    }

    public boolean isCompleted(int taskId) {
        return completed.contains(taskId);
    }

    public Accepted acceptedTask(int taskId) {
        return accepted.get(taskId);
    }

    /** 已接任务 ID 升序快照（帧下发顺序）。 */
    public List<Integer> acceptedIds() {
        return List.copyOf(accepted.navigableKeySet());
    }

    /** 已完成任务 ID 升序快照。 */
    public List<Integer> completedIds() {
        return List.copyOf(completed);
    }

    public Snapshot snapshot() {
        List<Accepted> entries = new ArrayList<>(accepted.values());
        return new Snapshot(List.copyOf(entries), List.copyOf(completed));
    }

    public void restore(Snapshot snapshot) {
        accepted.clear();
        completed.clear();
        if (snapshot == null) {
            return;
        }
        for (Accepted entry : snapshot.accepted()) {
            accepted.put(entry.taskId(), entry);
        }
        completed.addAll(snapshot.completed());
    }

    /** 接取结果：成功携带条目，失败携带原因。 */
    public record AcceptResult(Accepted entry, Reject reject) {

        static AcceptResult accepted(Accepted entry) {
            return new AcceptResult(entry, null);
        }

        static AcceptResult reject(Reject reject) {
            return new AcceptResult(null, reject);
        }

        public boolean ok() {
            return entry != null;
        }
    }

    /** 接取校验失败原因（日志/客户端提示用）。 */
    public enum Reject {
        NOT_CONFIGURED,
        ALREADY_COMPLETED,
        ALREADY_ACCEPTED,
        PRE_TASK_NOT_COMPLETED,
    }
}
