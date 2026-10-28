package com.swingfrog.summer.ecs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 记录表：行级 CRUD + 待同步行操作队列，对应协议记录表（背包/技能栏/快捷栏/任务）。
 * 行内值按列顺序排列，列类型由使用方按协议 schema 约定（int32/int64/string），
 * 同步层将 pendingOps 编码为 0x11 RecordAddRow / 0x12 RecordDelRow。
 * 单线程访问（玩家线程）。
 */
public final class RecordTable {

    public enum OpType { ADD, UPDATE, REMOVE, CLEAR}

    public record RowOp(OpType type, int row) {
    }

    public interface Listener {

        default void onRowAdded(int row, Object[] values) {
        }

        default void onRowUpdated(int row, Object[] oldValues, Object[] newValues) {
        }

        default void onRowRemoved(int row, Object[] oldValues) {
        }

        default void onCleared() {
        }
    }

    private final List<Object[]> rows = new ArrayList<>();
    private final ArrayDeque<RowOp> pendingOps = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** 追加一行，返回行号。 */
    public int addRow(Object... values) {
        rows.add(values.clone());
        int row = rows.size() - 1;
        pendingOps.add(new RowOp(OpType.ADD, row));
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onRowAdded(row, values);
        }
        return row;
    }

    public void updateRow(int row, Object... values) {
        checkRow(row);
        Object[] old = rows.set(row, values.clone());
        pendingOps.add(new RowOp(OpType.UPDATE, row));
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onRowUpdated(row, old, values);
        }
    }

    /** 删除一行（行号紧凑化：后续行前移），并对每个受影响行补 UPDATE 操作。 */
    public void removeRow(int row) {
        checkRow(row);
        Object[] old = rows.remove(row);
        pendingOps.add(new RowOp(OpType.REMOVE, row));
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onRowRemoved(row, old);
        }
        for (int r = row; r < rows.size(); r++) {
            pendingOps.add(new RowOp(OpType.UPDATE, r));
        }
    }

    public void clear() {
        rows.clear();
        pendingOps.clear();
        pendingOps.add(new RowOp(OpType.CLEAR, -1));
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onCleared();
        }
    }

    public Object[] getRow(int row) {
        checkRow(row);
        return rows.get(row);
    }

    /** 按首列值查行号，找不到返回 -1（首列通常为行键，如快捷栏 index）。 */
    public int findRowByKey(Object key) {
        for (int r = 0; r < rows.size(); r++) {
            if (rows.get(r).length > 0 && Objects.equals(rows.get(r)[0], key)) {
                return r;
            }
        }
        return -1;
    }

    public int rowCount() {
        return rows.size();
    }

    public List<Object[]> rows() {
        return Collections.unmodifiableList(rows);
    }

    public boolean hasPending() {
        return !pendingOps.isEmpty();
    }

    /** 返回待同步操作快照并清空队列。 */
    public List<RowOp> drainPending() {
        if (pendingOps.isEmpty()) {
            return List.of();
        }
        List<RowOp> ops = new ArrayList<>(pendingOps);
        pendingOps.clear();
        return ops;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= rows.size()) {
            throw new IndexOutOfBoundsException("record row " + row + " of " + rows.size());
        }
    }

    @Override
    public String toString() {
        return "RecordTable{rows=" + rows.size() + ", pending=" + pendingOps.size() + ", data=" +
                Arrays.deepToString(rows.toArray()) + "}";
    }
}
