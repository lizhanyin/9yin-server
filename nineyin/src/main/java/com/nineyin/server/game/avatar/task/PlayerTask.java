package com.nineyin.server.game.avatar.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 玩家任务运行时状态（挂在 PlayerActor 上）：已接任务表 + 已完成位图集 +
 * 进度步骤计数（二期：hunter 杀怪 / interact 交互）。
 *
 * <p>状态流转：
 * <ul>
 * <li>accept：校验（配置存在、前置完成、未接未完成）→ isdoing；进度步骤初始化 0；
 * <li>advance：杀怪/交互事件 → 计数 +1 封顶 maxnum；全部进度步骤满 → status=2 可提交；
 * <li>complete：已接 → 删除已接 + 进度 + 完成集置位（Task_Completed 位图一基序号）；
 * <li>giveup：已接 → 删除已接 + 进度（完成集不动）。
 * </ul>无进度步骤的任务（纯对话）接取即视为进度完成——保持一期行为。
 * starter 功能解锁族（1069-1073/1344/1140-1147/51002/1346）由
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

    /**
     * 存档快照：已接列表（任务序升序）+ 已完成任务 ID 集 + 进度计数
     * （taskId → 步骤 TypeID → curnum；老档缺节点 → 空 Map，向前兼容）。
     */
    public record Snapshot(List<Accepted> accepted, List<Integer> completed,
                           Map<Integer, Map<Integer, Integer>> progress) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), Map.of());
    }

    /** 步骤推进结果：changed=计数有变（需回刷 Task_Record 行）；justCompleted=本次刚好满。 */
    public record AdvanceResult(boolean changed, boolean justCompleted) {
    }

    private final TreeMap<Integer, Accepted> accepted = new TreeMap<>();
    private final TreeSet<Integer> completed = new TreeSet<>();
    private final Map<Integer, Map<Integer, Integer>> progress = new TreeMap<>();

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
                return AcceptResult.accepted(acceptInternal(catalog, taskId, now));
            }
        }
        return def.preTaskLimit().isEmpty()
                ? AcceptResult.accepted(acceptInternal(catalog, taskId, now))
                : AcceptResult.reject(Reject.PRE_TASK_NOT_COMPLETED);
    }

    private Accepted acceptInternal(TaskCatalog catalog, int taskId, long now) {
        Accepted entry = new Accepted(taskId, STATUS_DOING, now);
        accepted.put(taskId, entry);
        // 进度步骤初始化 0（快照即含全量步骤，登录恢复/镜像按此渲染）。
        Map<Integer, Integer> counts = new TreeMap<>();
        for (TaskCatalog.Step step : catalog.steps(taskId)) {
            if (stepMaxnum(catalog, step) > 0) {
                counts.put(parseInt(step.typeId()), 0);
            }
        }
        if (!counts.isEmpty()) {
            progress.put(taskId, counts);
        }
        return entry;
    }

    /** 提交任务：删除已接 + 进度 + 完成置位；返回被提交条目，未接返回 null。 */
    public Accepted complete(int taskId) {
        Accepted entry = accepted.remove(taskId);
        if (entry == null) {
            return null;
        }
        progress.remove(taskId);
        completed.add(taskId);
        return entry;
    }

    /** 放弃任务：仅删已接 + 进度；返回被放弃条目，未接返回 null。 */
    public Accepted giveup(int taskId) {
        progress.remove(taskId);
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

    // ------------------------------------------------------------------ 进度步骤（二期）

    /**
     * 进度步骤的目标数：hunter→huntlist Count、interact→1；
     * story/未配置进度表的步骤返回 -1（不参与计数与门控）。
     */
    public static int stepMaxnum(TaskCatalog catalog, TaskCatalog.Step step) {
        if ("hunter".equalsIgnoreCase(step.type())) {
            TaskCatalog.HuntDef hunt = catalog.hunt(parseInt(step.typeId()));
            return hunt != null ? hunt.count() : -1;
        }
        if ("interact".equalsIgnoreCase(step.type())) {
            TaskCatalog.InteractDef interact = catalog.interact(parseInt(step.typeId()));
            return interact != null ? 1 : -1;
        }
        return -1;
    }

    /** 步骤当前计数（未接/未初始化 → 0）。 */
    public int progressOf(int taskId, String stepTypeId) {
        Map<Integer, Integer> counts = progress.get(taskId);
        if (counts == null) {
            return 0;
        }
        Integer value = counts.get(parseInt(stepTypeId));
        return value != null ? value : 0;
    }

    /**
     * 步骤推进 +1（封顶 maxnum）：未接/非进度步骤/已满均无变化；
     * 全部进度步骤满的瞬间把已接条目 status 置 {@link #STATUS_CAN_SUBMIT}。
     */
    public AdvanceResult advance(TaskCatalog catalog, int taskId, String stepTypeId) {
        TaskCatalog.Step step = findStep(catalog, taskId, stepTypeId);
        if (step == null) {
            return new AdvanceResult(false, false);
        }
        int maxnum = stepMaxnum(catalog, step);
        Map<Integer, Integer> counts = progress.get(taskId);
        if (maxnum <= 0 || counts == null) {
            return new AdvanceResult(false, false);
        }
        int key = parseInt(stepTypeId);
        int current = counts.getOrDefault(key, 0);
        if (current >= maxnum) {
            return new AdvanceResult(false, false);
        }
        counts.put(key, current + 1);
        if (!isProgressComplete(catalog, taskId)) {
            return new AdvanceResult(true, false);
        }
        Accepted entry = accepted.get(taskId);
        if (entry != null && entry.status() == STATUS_DOING) {
            accepted.put(taskId, new Accepted(taskId, STATUS_CAN_SUBMIT, entry.acceptedAtUnixMillis()));
        }
        return new AdvanceResult(true, true);
    }

    /** GM 调试：一步补满任务全部进度步骤；返回有变化的步骤 TypeID 列表。 */
    public List<String> fillProgress(TaskCatalog catalog, int taskId) {
        Map<Integer, Integer> counts = progress.get(taskId);
        if (counts == null || !accepted.containsKey(taskId)) {
            return List.of();
        }
        List<String> filled = new ArrayList<>();
        for (TaskCatalog.Step step : catalog.steps(taskId)) {
            int maxnum = stepMaxnum(catalog, step);
            if (maxnum <= 0) {
                continue;
            }
            int key = parseInt(step.typeId());
            if (counts.getOrDefault(key, 0) < maxnum) {
                counts.put(key, maxnum);
                filled.add(step.typeId());
            }
        }
        if (!filled.isEmpty() && isProgressComplete(catalog, taskId)) {
            Accepted entry = accepted.get(taskId);
            if (entry != null && entry.status() == STATUS_DOING) {
                accepted.put(taskId, new Accepted(taskId, STATUS_CAN_SUBMIT, entry.acceptedAtUnixMillis()));
            }
        }
        return List.copyOf(filled);
    }

    /**
     * 进度完成判定（提交门控）：全部 hunter/interact 步骤计数 ≥ maxnum；
     * 无进度步骤的任务恒真（一期纯对话任务行为）；配置缺失的步骤不门控。
     */
    public boolean isProgressComplete(TaskCatalog catalog, int taskId) {
        Map<Integer, Integer> counts = progress.get(taskId);
        if (counts == null) {
            return true;
        }
        for (TaskCatalog.Step step : catalog.steps(taskId)) {
            int maxnum = stepMaxnum(catalog, step);
            if (maxnum <= 0) {
                continue;
            }
            if (counts.getOrDefault(parseInt(step.typeId()), 0) < maxnum) {
                return false;
            }
        }
        return true;
    }

    /** 按任务在 subfunction 的步骤里查 TypeID 匹配的进度步骤（含类型校验）。 */
    private static TaskCatalog.Step findStep(TaskCatalog catalog, int taskId, String stepTypeId) {
        for (TaskCatalog.Step step : catalog.steps(taskId)) {
            if (step.typeId().equals(stepTypeId)
                    && ("hunter".equalsIgnoreCase(step.type()) || "interact".equalsIgnoreCase(step.type()))) {
                return step;
            }
        }
        return null;
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
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
        Map<Integer, Map<Integer, Integer>> progressCopy = new TreeMap<>();
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : progress.entrySet()) {
            progressCopy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return new Snapshot(List.copyOf(entries), List.copyOf(completed), Map.copyOf(progressCopy));
    }

    public void restore(Snapshot snapshot) {
        accepted.clear();
        completed.clear();
        progress.clear();
        if (snapshot == null) {
            return;
        }
        for (Accepted entry : snapshot.accepted()) {
            accepted.put(entry.taskId(), entry);
        }
        completed.addAll(snapshot.completed());
        if (snapshot.progress() != null) {
            for (Map.Entry<Integer, Map<Integer, Integer>> entry : snapshot.progress().entrySet()) {
                progress.put(entry.getKey(), new TreeMap<>(entry.getValue()));
            }
        }
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
