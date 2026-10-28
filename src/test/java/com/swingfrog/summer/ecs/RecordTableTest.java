package com.swingfrog.summer.ecs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordTableTest {

    @Test
    void addRowProducesPendingAdd() {
        RecordTable table = new RecordTable();
        int row = table.addRow(9, "skill", "zs_default_01");
        assertEquals(0, row);
        assertTrue(table.hasPending());
        List<RecordTable.RowOp> ops = table.drainPending();
        assertEquals(1, ops.size());
        assertEquals(RecordTable.OpType.ADD, ops.get(0).type());
        assertEquals(0, ops.get(0).row());
        assertFalse(table.hasPending());
    }

    @Test
    void updateAndRemoveRows() {
        RecordTable table = new RecordTable();
        table.addRow(1, "a");
        table.addRow(2, "b");
        table.addRow(3, "c");
        table.drainPending();

        table.updateRow(1, 2, "B");
        assertEquals(2, table.getRow(1)[0]);

        table.removeRow(0);
        assertEquals(2, table.rowCount());
        assertEquals(2, table.getRow(0)[0]);
        assertEquals(0, table.findRowByKey(2)); // 删除 0 行后行号前移

        List<RecordTable.RowOp> ops = table.drainPending();
        // update(1) + remove(0) + 前移补发 update(0) update(1)
        assertEquals(4, ops.size());
        assertEquals(RecordTable.OpType.UPDATE, ops.get(0).type());
        assertEquals(RecordTable.OpType.REMOVE, ops.get(1).type());
        assertEquals(0, ops.get(1).row());
        assertEquals(RecordTable.OpType.UPDATE, ops.get(2).type());
        assertEquals(RecordTable.OpType.UPDATE, ops.get(3).type());
    }

    @Test
    void listenerReceivesRowEvents() {
        StringBuilder log = new StringBuilder();
        RecordTable table = new RecordTable();
        table.addListener(new RecordTable.Listener() {
            @Override
            public void onRowAdded(int row, Object[] values) {
                log.append("+").append(row);
            }

            @Override
            public void onRowRemoved(int row, Object[] oldValues) {
                log.append("-").append(row);
            }
        });
        table.addRow(1);
        table.removeRow(0);
        assertEquals("+0-0", log.toString());
    }

    @Test
    void clearResetsOpsAndRows() {
        RecordTable table = new RecordTable();
        table.addRow(1);
        table.addRow(2);
        table.clear();
        assertEquals(0, table.rowCount());
        List<RecordTable.RowOp> ops = table.drainPending();
        assertEquals(1, ops.size());
        assertEquals(RecordTable.OpType.CLEAR, ops.get(0).type());
    }

    @Test
    void rowsAreDefensiveCopied() {
        RecordTable table = new RecordTable();
        Object[] values = {1, "a"};
        table.addRow(values);
        values[0] = 999;
        assertEquals(1, table.getRow(0)[0]);
        assertArrayEquals(new Object[]{1, "a"}, table.getRow(0));
    }
}
