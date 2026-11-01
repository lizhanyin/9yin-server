package com.nineyin.world.npc;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 现代客户端 NPC 模板表（GBK + 带引号 TSV），逐行移植 Go
 * clientdata.LoadNPCTemplateTable。前三行 = 注释/类型/列名；分隔用的全空行
 * 是排版不是数据；部分字段含带引号的嵌入换行，必须按 CSV 语义解析。
 */
public final class NpcTemplateTable {

    /** Go RawCell：一列原始值（Set=该格非空）。 */
    public record RawCell(int columnIndex, String columnName, String raw, boolean set) {
    }

    /** Go NPCTemplateRow。 */
    public static final class Row {
        public final String source;
        public final int logicalRecord;
        public final String configId;
        public final String scriptClass;
        final List<RawCell> cells;
        private final Map<String, List<Integer>> columnsByName;

        Row(String source, int logicalRecord, List<RawCell> cells,
            Map<String, List<Integer>> columnsByName, String configId, String scriptClass) {
            this.source = source;
            this.logicalRecord = logicalRecord;
            this.cells = cells;
            this.columnsByName = columnsByName;
            this.configId = configId;
            this.scriptClass = scriptClass;
        }

        public RawCell cellAt(int index) {
            if (index < 0 || index >= cells.size()) {
                return null;
            }
            return cells.get(index);
        }
    }

    public final String source;
    public final List<String> columns;
    public final List<Row> rows;
    private final Map<String, List<Integer>> columnsByName;
    private final Map<String, List<Integer>> rowsById;

    private NpcTemplateTable(String source, List<String> columns,
                             Map<String, List<Integer>> columnsByName) {
        this.source = source;
        this.columns = columns;
        this.columnsByName = columnsByName;
        this.rows = new ArrayList<>();
        this.rowsById = new HashMap<>();
    }

    public List<Integer> columnIndices(String name) {
        return columnsByName.getOrDefault(name, List.of());
    }

    public List<Row> lookupAll(String configId) {
        List<Integer> indices = rowsById.getOrDefault(configID(configId), List.of());
        List<Row> result = new ArrayList<>(indices.size());
        for (int index : indices) {
            result.add(rows.get(index));
        }
        return result;
    }

    /** Go Lookup：0 行=未找到，>1 行=歧义，均抛 IllegalStateException。 */
    public Row lookup(String configId) {
        List<Integer> indices = rowsById.getOrDefault(configID(configId), List.of());
        if (indices.isEmpty()) {
            throw new IllegalStateException("NPC template not found: " + configId + " in " + source);
        }
        if (indices.size() > 1) {
            throw new IllegalStateException("NPC template ID is ambiguous: " + configId + " in " + source
                    + " at " + indices.size() + " records");
        }
        return rows.get(indices.get(0));
    }

    private static String configID(String configId) {
        return configId == null ? "" : configId;
    }

    public static NpcTemplateTable load(Path file) throws IOException {
        String text = Files.readString(file, Charset.forName("GBK"));
        List<List<String>> records = parseQuotedTsv(text);
        if (records.size() < 3) {
            throw new IOException("NPC template " + file + " lacks 3 header records");
        }
        List<String> columns = records.get(2);
        if (columns.isEmpty()) {
            throw new IOException("NPC template " + file + " has no columns");
        }
        if (records.get(0).size() != columns.size() || records.get(1).size() != columns.size()) {
            throw new IOException("NPC template " + file + " header widths comments=" + records.get(0).size()
                    + " types=" + records.get(1).size() + " columns=" + columns.size());
        }
        Map<String, List<Integer>> columnsByName = new HashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            columnsByName.computeIfAbsent(columns.get(index), name -> new ArrayList<>()).add(index);
        }
        List<Integer> idColumns = columnsByName.getOrDefault("ID", List.of());
        if (idColumns.size() != 1) {
            throw new IOException("NPC template " + file + " has " + idColumns.size() + " ID columns, want 1");
        }
        List<Integer> scriptColumns = columnsByName.getOrDefault("script", List.of());

        NpcTemplateTable table = new NpcTemplateTable(file.toString(), List.copyOf(columns), columnsByName);
        int logicalRecord = 3;
        for (int recordIndex = 3; recordIndex < records.size(); recordIndex++) {
            List<String> record = records.get(recordIndex);
            boolean allEmpty = true;
            for (String raw : record) {
                if (!raw.isEmpty()) {
                    allEmpty = false;
                    break;
                }
            }
            if (allEmpty) {
                continue;
            }
            if (record.size() != columns.size()) {
                throw new IOException("NPC template " + file + " record " + (recordIndex + 1)
                        + " has " + record.size() + " fields, want " + columns.size());
            }
            logicalRecord++;
            List<RawCell> cells = new ArrayList<>(record.size());
            for (int index = 0; index < record.size(); index++) {
                String raw = record.get(index);
                cells.add(new RawCell(index, columns.get(index), raw, !raw.isEmpty()));
            }
            String configId = record.get(idColumns.get(0));
            String scriptClass = scriptColumns.size() == 1 ? record.get(scriptColumns.get(0)) : "";
            Row row = new Row(table.source, logicalRecord, cells, columnsByName, configId, scriptClass);
            int rowIndex = table.rows.size();
            table.rows.add(row);
            if (!configId.isEmpty()) {
                table.rowsById.computeIfAbsent(configId, id -> new ArrayList<>()).add(rowIndex);
            }
        }
        return table;
    }

    /** encoding/csv（Comma='\t'）语义：带引号字段可含换行/制表符，"" 为字面引号。 */
    static List<List<String>> parseQuotedTsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
                continue;
            }
            if (c == '\t') {
                fields.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                continue;
            }
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                records.add(fields);
                fields = new ArrayList<>();
                continue;
            }
            field.append(c);
            fieldStarted = true;
        }
        if (field.length() > 0 || fieldStarted || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(fields);
        }
        return records;
    }
}
