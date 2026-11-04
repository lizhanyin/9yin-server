package com.nineyin.server.game.avatar.task;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.server.world.npc.NpcTemplateTable;
import com.nineyin.util.ServerPaths;

/**
 * 任务静态配置目录（share/task TSV + npcconfig 任务归属列），进程级缓存。
 *
 * <p>四类源表（GBK + 带引号 TSV，{@link NpcTemplateTable#parseQuotedTsv} 语义）：
 * <ul>
 * <li>主任务表 task/task/task_*.txt（两行表头，列名行在 records[1]，59 列方言）；
 * <li>奖励表 task/task/prize/prize_*.txt（两行表头，28 列）；
 * <li>步骤表 task/task/subfunction/subfunction_*.txt（两行表头，Type=story/interact）；
 * <li>NPC 归属 npcconfig 全目录 txt 的 table@TaskCanAccept / table@TaskCanSubmit
 *     列（三行表头，值为分号分隔任务 ID 尾分号，如 {@code 24721;24727;}）。
 * </ul>
 */
public final class TaskCatalog {

    /** 主任务行（列名定位，未知列缺省 0/""）。 */
    public record TaskDef(int id, int line, String plot, int step,
                          String acceptNpc, String submitNpc, int sceneId,
                          int autoAcceptId, int autoSubmit,
                          List<Integer> preTaskLimit, List<Integer> nextTaskLimit,
                          String acceptLimit, String submitLimit,
                          String taskItemList, String submitNpcEx,
                          String titleId, String contextId, String completeDialogId,
                          String taskTargetId, String trackInfoId,
                          int canGiveup, int timeLimitLength) {
    }

    /** 奖励行（prize 表 28 列方言的有效子集）。 */
    public record PrizeDef(int id, String title, long faculty, long normalCapital,
                           String reputeName1, int reputeCount1,
                           String reputeName3, int reputeCount2,
                           String dropId, int prizeItemType,
                           String item1, int num1, String item2, int num2,
                           String item3, int num3, String item4, int num4,
                           int liveGroove, int liveGrooveCount, long karmaPrize) {

        /**
         * 官方 23 段逗号奖励串（form_talk_movie.fill_prize 逆向）：[1]钱 [2]历练
         * [3,4]门派声望 [5,6]江湖声望 [7,8]预留 [9]可选奖励 [10]随机标记
         * [11..18]Item1,Num1..Item4,Num4 [19]预留 [20,21]生活槽 [22]钱2 [23]预留。
         * 全 0 串客户端不渲染奖励区（is_have_prize）。
         */
        public String renderPrizeString() {
            StringBuilder out = new StringBuilder(96);
            out.append(normalCapital);            // 1 钱
            out.append(',').append(faculty);      // 2 历练
            out.append(',').append(text(reputeName1));   // 3 门派声望名
            out.append(',').append(reputeCount1); // 4
            out.append(',').append(text(reputeName3));   // 5 江湖声望名
            out.append(',').append(reputeCount2); // 6
            out.append(",0,0");                   // 7,8 预留
            out.append(",0");                     // 9 可选奖励（一期不支持选择）
            out.append(',').append(dropId == null || dropId.isEmpty() ? 0 : 1); // 10 随机标记
            appendItem(out, item1, num1);         // 11,12
            appendItem(out, item2, num2);         // 13,14
            appendItem(out, item3, num3);         // 15,16
            appendItem(out, item4, num4);         // 17,18
            out.append(",0");                     // 19 预留
            out.append(',').append(liveGroove);   // 20 生活槽类型
            out.append(',').append(liveGrooveCount); // 21
            out.append(',').append(karmaPrize);   // 22 钱2
            out.append(",0");                     // 23 预留
            return out.toString();
        }

        private static void appendItem(StringBuilder out, String item, int num) {
            boolean has = item != null && !item.isEmpty();
            out.append(',').append(has ? item : 0).append(',').append(has ? num : 0);
        }

        private static String text(String value) {
            return value == null || value.isEmpty() ? "0" : value;
        }
    }

    /** 步骤行（subfunction 表：Type=story 对话 / interact 交互）。 */
    public record Step(int taskId, String type, String typeId, int order) {
    }

    /** NPC 任务归属（table@TaskCanAccept / table@TaskCanSubmit 两列）。 */
    public record NpcTasks(List<Integer> accept, List<Integer> submit) {
    }

    private final Map<Integer, TaskDef> tasks;
    private final Map<Integer, PrizeDef> prizes;
    private final Map<Integer, List<Step>> steps;
    private final Map<String, NpcTasks> npcTasks;

    TaskCatalog(Map<Integer, TaskDef> tasks, Map<Integer, PrizeDef> prizes,
                Map<Integer, List<Step>> steps, Map<String, NpcTasks> npcTasks) {
        this.tasks = tasks;
        this.prizes = prizes;
        this.steps = steps;
        this.npcTasks = npcTasks;
    }

    public static TaskCatalog load() {
        return ResourceCache.of("task-catalog:" + ServerPaths.projectPath("resources", "modern", "share"),
                () -> loadShare(ServerPaths.projectPath("resources", "modern", "share")));
    }

    static TaskCatalog loadShare(Path shareRoot) {
        Map<Integer, TaskDef> tasks = new TreeMap<>();
        Map<Integer, PrizeDef> prizes = new TreeMap<>();
        Map<Integer, List<Step>> steps = new TreeMap<>();
        for (Path file : tsvFiles(shareRoot.resolve(Path.of("task", "task", "task")))) {
            loadTaskTable(file, tasks);
        }
        for (Path file : tsvFiles(shareRoot.resolve(Path.of("task", "task", "prize")))) {
            loadPrizeTable(file, prizes);
        }
        for (Path file : tsvFiles(shareRoot.resolve(Path.of("task", "task", "subfunction")))) {
            loadStepTable(file, steps);
        }
        Map<String, NpcTasks> npcTasks = new HashMap<>();
        loadNpcTasks(shareRoot.resolve(Path.of("npc", "npcconfig")), npcTasks);
        return new TaskCatalog(tasks, prizes, steps, npcTasks);
    }

    public TaskDef task(int id) {
        return tasks.get(id);
    }

    public PrizeDef prize(int taskId) {
        return prizes.get(taskId);
    }

    /** 步骤按 Order 升序（同序保持文件内顺序）。 */
    public List<Step> steps(int taskId) {
        List<Step> list = steps.get(taskId);
        if (list == null) {
            return List.of();
        }
        List<Step> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(Step::order));
        return sorted;
    }

    public NpcTasks npcTasks(String npcConfigId) {
        return npcTasks.getOrDefault(npcConfigId == null ? "" : npcConfigId,
                new NpcTasks(List.of(), List.of()));
    }

    public int taskCount() {
        return tasks.size();
    }

    // ------------------------------------------------------------------ 表加载

    /** 主任务表：records[0]=类型行、records[1]=列名行；ID 列必在。 */
    private static void loadTaskTable(Path file, Map<Integer, TaskDef> out) {
        Table table = Table.load(file, 1);
        if (table == null) {
            return;
        }
        for (String[] record : table.records) {
            int id = table.intOf(record, "ID", 0);
            if (id <= 0) {
                continue;
            }
            out.putIfAbsent(id, new TaskDef(
                    id,
                    table.intOf(record, "Line", 0),
                    table.textOf(record, "Plot"),
                    table.intOf(record, "Step", 0),
                    table.textOf(record, "AcceptNpc"),
                    table.textOf(record, "SubmitNpc"),
                    table.intOf(record, "SceneID", 0),
                    table.intOf(record, "AutoAcceptID", 0),
                    table.intOf(record, "AutoSubmit", 0),
                    idList(table.textOf(record, "PreTaskLimit")),
                    idList(table.textOf(record, "NextTaskLimit")),
                    table.textOf(record, "AcceptLimit"),
                    table.textOf(record, "SubmitLimit"),
                    table.textOf(record, "TaskItemList"),
                    table.textOf(record, "SubmitNpcEx"),
                    table.textOf(record, "TitleId"),
                    table.textOf(record, "ContextId"),
                    table.textOf(record, "CompleteDialogId"),
                    table.textOf(record, "TaskTargetId"),
                    table.textOf(record, "TrackInfoID"),
                    table.intOf(record, "CanGiveup", 0),
                    table.intOf(record, "TimeLimitLength", 0)));
        }
    }

    private static void loadPrizeTable(Path file, Map<Integer, PrizeDef> out) {
        Table table = Table.load(file, 1);
        if (table == null) {
            return;
        }
        for (String[] record : table.records) {
            int id = table.intOf(record, "ID", 0);
            if (id <= 0) {
                continue;
            }
            out.putIfAbsent(id, new PrizeDef(
                    id,
                    table.textOf(record, "Title"),
                    table.longOf(record, "Faculty", 0),
                    table.longOf(record, "NormalCapital", 0),
                    table.textOf(record, "ReputeName1"),
                    table.intOf(record, "ReputeCount1", 0),
                    table.textOf(record, "ReputeName3"),
                    table.intOf(record, "ReputeCount2", 0),
                    table.textOf(record, "DropID"),
                    table.intOf(record, "PrizeItemType", 0),
                    table.textOf(record, "Item1"), table.intOf(record, "Num1", 0),
                    table.textOf(record, "Item2"), table.intOf(record, "Num2", 0),
                    table.textOf(record, "Item3"), table.intOf(record, "Num3", 0),
                    table.textOf(record, "Item4"), table.intOf(record, "Num4", 0),
                    table.intOf(record, "LiveGroove", 0),
                    table.intOf(record, "LiveGrooveCount", 0),
                    table.longOf(record, "KarmaPrize", 0)));
        }
    }

    private static void loadStepTable(Path file, Map<Integer, List<Step>> out) {
        Table table = Table.load(file, 1);
        if (table == null) {
            return;
        }
        for (String[] record : table.records) {
            int id = table.intOf(record, "ID", 0);
            String type = table.textOf(record, "Type");
            String typeId = table.textOf(record, "TypeID");
            if (id <= 0 || type.isEmpty() || typeId.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(id, key -> new ArrayList<>())
                    .add(new Step(id, type, typeId, table.intOf(record, "Order", 0)));
        }
    }

    /**
     * NPC 任务归属：npcconfig 子目录全部 .txt（复用 {@link NpcTemplateTable} 的
     * 三行表头/带引号 TSV 方言），table@TaskCanAccept / table@TaskCanSubmit 列
     * 值为分号分隔任务 ID 尾分号。无该两列的表静默跳过。
     */
    private static void loadNpcTasks(Path npcConfigRoot, Map<String, NpcTasks> out) {
        if (!Files.isDirectory(npcConfigRoot)) {
            return;
        }
        for (Path file : tsvFiles(npcConfigRoot)) {
            NpcTemplateTable table;
            try {
                table = NpcTemplateTable.load(file);
            } catch (IOException | RuntimeException e) {
                continue; // 非模板表（字典 ini 等）与解析失败均跳过
            }
            List<Integer> acceptColumns = table.columnIndices("table@TaskCanAccept");
            List<Integer> submitColumns = table.columnIndices("table@TaskCanSubmit");
            if (acceptColumns.isEmpty() && submitColumns.isEmpty()) {
                continue;
            }
            for (NpcTemplateTable.Row row : table.rows) {
                String configId = row.configId;
                if (configId == null || configId.isEmpty()) {
                    continue;
                }
                NpcTasks existing = out.get(configId);
                List<Integer> accept = existing != null ? new ArrayList<>(existing.accept()) : new ArrayList<>();
                List<Integer> submit = existing != null ? new ArrayList<>(existing.submit()) : new ArrayList<>();
                boolean changed = false;
                for (int column : acceptColumns) {
                    changed |= appendTaskIds(row, column, accept);
                }
                for (int column : submitColumns) {
                    changed |= appendTaskIds(row, column, submit);
                }
                if (changed || existing == null) {
                    out.put(configId, new NpcTasks(List.copyOf(accept), List.copyOf(submit)));
                }
            }
        }
    }

    private static boolean appendTaskIds(NpcTemplateTable.Row row, int column, List<Integer> out) {
        NpcTemplateTable.RawCell cell = row.cellAt(column);
        if (cell == null || !cell.set()) {
            return false;
        }
        boolean changed = false;
        for (String part : cell.raw().split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(trimmed);
                if (id > 0 && !out.contains(id)) {
                    out.add(id);
                    changed = true;
                }
            } catch (NumberFormatException ignored) {
                // 非数字配置（极少数模板带说明文本）跳过
            }
        }
        return changed;
    }

    /** "3160;1410;104641" → [3160, 1410, 104641]。 */
    static List<Integer> idList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(trimmed);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 非数字引用跳过
            }
        }
        return List.copyOf(ids);
    }

    private static List<Path> tsvFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .forEach(files::add);
        } catch (IOException e) {
            return List.of();
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    // ------------------------------------------------------------------ 通用 TSV 表

    /** 两行表头 TSV（类型行 + 列名行）：按列名取值，列缺失返回缺省。 */
    static final class Table {

        final List<String> columns;
        final List<String[]> records;
        private final Map<String, Integer> columnByName = new HashMap<>();

        private Table(List<String> columns, List<String[]> records) {
            this.columns = columns;
            this.records = records;
            for (int i = 0; i < columns.size(); i++) {
                columnByName.putIfAbsent(columns.get(i), i);
            }
        }

        static Table load(Path file, int columnNameRow) {
            String text;
            try {
                text = Files.readString(file, Charset.forName("GBK"));
            } catch (IOException | RuntimeException e) {
                return null;
            }
            List<List<String>> parsed = NpcTemplateTable.parseQuotedTsv(text);
            if (parsed.size() <= columnNameRow) {
                return null;
            }
            List<String> columns = parsed.get(columnNameRow);
            List<String[]> records = new ArrayList<>();
            for (int i = columnNameRow + 1; i < parsed.size(); i++) {
                List<String> record = parsed.get(i);
                boolean allEmpty = true;
                for (String raw : record) {
                    if (!raw.isEmpty()) {
                        allEmpty = false;
                        break;
                    }
                }
                if (!allEmpty) {
                    records.add(record.toArray(new String[0]));
                }
            }
            return new Table(columns, records);
        }

        String textOf(String[] record, String column) {
            Integer index = columnByName.get(column);
            if (index == null || index >= record.length) {
                return "";
            }
            return record[index].trim();
        }

        int intOf(String[] record, String column, int fallback) {
            String raw = textOf(record, column);
            if (raw.isEmpty()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        long longOf(String[] record, String column, long fallback) {
            String raw = textOf(record, column);
            if (raw.isEmpty()) {
                return fallback;
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
