package com.nineyin.server.game.avatar.task;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.protocol.RecordSchemas;

/**
 * 任务组帧：位图公式（function_unlock_meta.json 已钉死值）、Task_Accepted 28 列
 * 满宽行、327 命令名寻址壳、Task_Record 15 列 story 行。
 */
class TaskMessagesTest {

    @Test
    void completedBitMatchesStarterGoldenAnchors() {
        // golden/function_unlock_meta.json：任务 1346 → row 42、1<<2（bit3）。
        assertArrayEquals(new long[]{42, 1L << 2}, TaskMessages.completedBit(1346));
        // 任务 51002 → row 1593、1<<26（final_row=1593 value=1<<26）。
        assertArrayEquals(new long[]{1593, 1L << 26}, TaskMessages.completedBit(51002));
        // 一基边界：serial 整除 32 时回退上行（task 31 → serial 32 → row 0 bit 32）。
        assertArrayEquals(new long[]{0, 1L << 31}, TaskMessages.completedBit(31));
        assertArrayEquals(new long[]{1, 1L}, TaskMessages.completedBit(32));
    }

    @Test
    void completedRowValuesMergeSameRowTasks() {
        Map<Long, Long> rows = TaskMessages.completedRowValues(List.of(1346, 1345));
        // 1345 → serial 1346 → row 42 bit 2；1346 → serial 1347 → row 42 bit 3：同行位求并。
        assertEquals(1L << 1 | 1L << 2, rows.get(42L));
        assertEquals(1, rows.size());
    }

    @Test
    void taskAcceptedRowIsFullWidthOfficialDialect() {
        TaskCatalog catalog = new TaskCatalog(
                java.util.Map.of(50, new TaskCatalog.TaskDef(50, 1, "", 1,
                        "npc_a", "npc_b", 448, 0, 0, List.of(), List.of(),
                        "", "", "", "", "title_50", "context_50", "complete_50",
                        "target_50", "next_50", 1, 0)),
                java.util.Map.of(50, new TaskCatalog.PrizeDef(50, "", 100, 5000,
                        "", 0, "", 0, "", 0,
                        "pill_a", 2, "", 0, "", 0, "", 0, 0, 0, 0)),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        byte[] frame = TaskMessages.taskAcceptedRow(1, 1, catalog,
                new PlayerTask.Accepted(50, PlayerTask.STATUS_DOING, 1234567890L));
        // 28 列满宽（含字符串材料列，宽于全零 golden 的 144B）
        assertEquals(0x11, frame[0]);
        assertTrue(frame.length > 144, "28 列满宽行（golden 全零形状 144B，带材料必然更宽）");
        // 小端 u16 recordIndex @10
        int recordIndex = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(RecordSchemas.indexByName("Task_Accepted"), recordIndex);
    }

    @Test
    void taskMsgUsesCommandNameRoutingShell() {
        byte[] frame = TaskMessages.taskAdded(75701);
        // 0x27 现代壳 | u16(LE) 值数=3 | tag6 "task_msg_logic" | tag2 sub=9 | tag2 75701
        assertEquals(0x27, frame[0]);
        int valueCount = (frame[1] & 0xFF) | (frame[2] & 0xFF) << 8;
        assertEquals(3, valueCount);
        assertEquals(6, frame[3], "首值为窄字符串（命令名寻址）");
        // 字符串载荷：u32(LE) 长含 NUL + UTF8 + NUL
        int length = (frame[4] & 0xFF) | (frame[5] & 0xFF) << 8
                | (frame[6] & 0xFF) << 16 | (frame[7] & 0xFF) << 24;
        String command = new String(frame, 8, length - 1, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("task_msg_logic", command);
        // 尾随 submsg=9 / taskId=75701 双 int32（小端）
        int offset = 8 + length;
        assertEquals(2, frame[offset]);
        assertEquals(TaskMessages.SUBMSG_TASK_ADDED, leInt(frame, offset + 1));
        assertEquals(75701, leInt(frame, offset + 6));
    }

    @Test
    void taskStoryRowIsOfficial15ColumnDialect() {
        TaskCatalog catalog = new TaskCatalog(
                java.util.Map.of(50, new TaskCatalog.TaskDef(50, 1, "", 1,
                        "npc_a", "npc_b", 448, 0, 0, List.of(), List.of(),
                        "", "", "", "", "title_50", "context_50", "complete_50",
                        "target_50", "next_50", 1, 0)),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of());
        byte[] frame = TaskMessages.taskStoryRow(1, 1, catalog,
                new TaskCatalog.Step(50, "story", "49", 1), "next_50");
        assertEquals(0x11, frame[0]);
        // 帧头 16B（op+flag+obj u32+owner u32+record u16+row u16+count u16），列数据 @16 起。
        int recordIndex = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(RecordSchemas.indexByName("Task_Record"), recordIndex);
        assertEquals(50, leInt(frame, 16), "列 0 = 任务 ID");
        assertEquals(TaskMessages.TASK_RECORD_SUBTYPE_STORY, leInt(frame, 20), "列 1 = subtype=6(story)");
    }

    @Test
    void taskProgressRowRendersHunterAndInteractiveDialects() {
        TaskCatalog catalog = new TaskCatalog(
                java.util.Map.of(80, new TaskCatalog.TaskDef(80, 1, "", 1,
                        "npc_a", "npc_b", 448, 0, 0, List.of(), List.of(),
                        "", "", "", "", "title_80", "context_80", "complete_80",
                        "target_80", "next_80", 1, 0)),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(75701, new TaskCatalog.HuntDef(
                        75701, "newmp_ut_001", 5, "hunterinfo_75701", 448)),
                java.util.Map.of(75801, new TaskCatalog.InteractDef(
                        75801, "wd_ut_001", 11, "interactinfo_75801")),
                java.util.Map.of());

        // hunter 行：subtype=1、curnum/maxnum=2/5（帧头 16B，int 列定长 4B）。
        byte[] hunter = TaskMessages.taskProgressRow(1, 1, catalog,
                new TaskCatalog.Step(80, "hunter", "75701", 2), 2);
        assertNotNull(hunter);
        assertEquals(0x11, hunter[0]);
        int recordIndex = (hunter[10] & 0xFF) | (hunter[11] & 0xFF) << 8;
        assertEquals(RecordSchemas.indexByName("Task_Record"), recordIndex);
        assertEquals(80, leInt(hunter, 16), "列 0 = 任务 ID");
        assertEquals(TaskMessages.TASK_RECORD_SUBTYPE_HUNTER, leInt(hunter, 20), "列 1 = subtype=1(hunter)");
        assertEquals(2, leInt(hunter, 32), "列 4 = curnum");
        assertEquals(5, leInt(hunter, 36), "列 5 = maxnum=huntlist Count");

        // interact 行：subtype=3、1/1。
        byte[] interact = TaskMessages.taskProgressRow(1, 1, catalog,
                new TaskCatalog.Step(80, "interact", "75801", 3), 1);
        assertNotNull(interact);
        assertEquals(TaskMessages.TASK_RECORD_SUBTYPE_INTERACTIVE, leInt(interact, 20), "列 1 = subtype=3(interact)");
        assertEquals(1, leInt(interact, 32));
        assertEquals(1, leInt(interact, 36));

        // 进度表缺配置（TypeID 无 huntlist 行）→ 不下发。
        assertNull(TaskMessages.taskProgressRow(1, 1, catalog,
                new TaskCatalog.Step(80, "hunter", "99999", 4), 0));
        // 非进度步骤 → 不下发。
        assertNull(TaskMessages.taskProgressRow(1, 1, catalog,
                new TaskCatalog.Step(80, "convoy", "75701", 5), 0));
    }

    /** 小端 int32 读取（PacketWriter.writeU32 低字节在前）。 */
    private static int leInt(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
    }

    @Test
    void completedRowSyncPairsDelBeforeAdd() {
        List<byte[]> frames = TaskMessages.taskCompletedRowSync(1, 1, 42, 1L << 2);
        assertEquals(2, frames.size(), "增量更新先删后加");
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(0x11, frames.get(1)[0]);
    }
}
