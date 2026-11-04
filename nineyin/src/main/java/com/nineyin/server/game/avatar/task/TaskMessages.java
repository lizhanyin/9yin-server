package com.nineyin.server.game.avatar.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.RecordMessages;

/**
 * 任务系统 S2C 组帧：task_msg_logic(327 命令名寻址) +
 * Task_Accepted(29, 官方 28 列方言) / Task_Record(30, 15 列) / Task_Completed(31,
 * 32 任务/行位图)。
 *
 * <p>列语义与子命令逆向自客户端 lua64（task_define.lua /
 * form_talk_data.lua / custom_handler.lua，详见 docs/task-system.md）。
 * NPC 对话菜单（0x1C SERVER_MENU）由 {@link com.nineyin.server.protocol.NpcMessages}
 * 统一组帧，任务项以 {@link com.nineyin.server.world.npc.NpcDialog} 本地 mark 路由。
 */
public final class TaskMessages {

    // ---- task_msg_logic 子命令（custom_handler.lua task_msg_logic） ----
    /** 接任务对话窗：args = (npcIdent, info 14 段 '|' 串)。 */
    public static final int SUBMSG_SHOW_ACCEPT_FORM = 1;
    /** 交任务对话窗：args = (npcIdent, funcid, taskId, titleId, menuId, condLevel, condTips, line)。 */
    public static final int SUBMSG_SHOW_SUBMIT_FORM = 2;
    /** 任务未完成提示：args = (npc, taskId, menuStr)。 */
    public static final int SUBMSG_SHOW_UNCOMPLETE = 4;
    /** 任务提交完成：args = (taskId,)；客户端刷新 LastObject 头顶 + on_task_submit。 */
    public static final int SUBMSG_TASK_SUBMITTED = 8;
    /** 任务接受成功：args = (taskId,)；客户端 on_task_add。 */
    public static final int SUBMSG_TASK_ADDED = 9;
    /** NPC 头顶任务标记：args = (npcConfigId, flag 2 可交/4 可接)。 */
    public static final int SUBMSG_NPC_EFFECT = 15;

    /** Task_Record 子类型（task_define.lua subtype_*）：6=story 对话步骤。 */
    public static final int TASK_RECORD_SUBTYPE_STORY = 6;

    private static final String TASK_COMMAND = "task_msg_logic";

    /** 327 task_msg_logic 下行（0x27 壳命令名寻址）。 */
    public static byte[] taskMsg(int submsg, CustomMessages.Value... args) {
        CustomMessages.Value[] values = new CustomMessages.Value[args.length + 1];
        values[0] = CustomMessages.Value.ofInt32(submsg);
        System.arraycopy(args, 0, values, 1, args.length);
        return CustomMessages.modernCustomCommandMessage(TASK_COMMAND, values);
    }

    /**
     * 接任务窗（sub=1）：info 为 14 段 '|' 串（show_accept_form 逆向）——
     * mark(菜单路由号)/taskId/titleId/menuId/prize_base/prize_ex/prize_over/
     * decMoney/adventure_plot/lock_value/strLimit/title/line/tiaozhan。
     * mark 是 NpcDialog 分配的本地路由号（官方 funcid 数字空间的替代方言）。
     */
    public static byte[] showAcceptForm(String npcIdent, int mark, TaskCatalog.TaskDef def,
                                        TaskCatalog.PrizeDef prize) {
        String prizeBase = prize != null ? prize.renderPrizeString() : "";
        String info = String.join("|",
                Integer.toString(mark),
                Integer.toString(def.id()),
                def.titleId(),
                "task_menu_accept_" + def.id(),
                prizeBase,
                "", "", "0", "0", "0", "",
                def.titleId(),
                Integer.toString(def.line()),
                "0");
        return taskMsg(SUBMSG_SHOW_ACCEPT_FORM,
                CustomMessages.Value.ofString(npcIdent),
                CustomMessages.Value.ofString(info));
    }

    /** 交任务窗（sub=2）：8 参（show_submit_form 逆向；mark 为本地菜单路由号）。 */
    public static byte[] showSubmitForm(String npcIdent, int mark, TaskCatalog.TaskDef def,
                                        String conditionTips) {
        return taskMsg(SUBMSG_SHOW_SUBMIT_FORM,
                CustomMessages.Value.ofString(npcIdent),
                CustomMessages.Value.ofInt32(mark),
                CustomMessages.Value.ofInt32(def.id()),
                CustomMessages.Value.ofString(def.titleId()),
                CustomMessages.Value.ofString("task_menu_complete_" + def.id()),
                CustomMessages.Value.ofInt32(0),
                CustomMessages.Value.ofString(conditionTips == null ? "" : conditionTips),
                CustomMessages.Value.ofInt32(def.line()));
    }

    /** 任务接受成功（sub=9）：客户端 on_task_add 后读 Task_Accepted 记录行渲染。 */
    public static byte[] taskAdded(int taskId) {
        return taskMsg(SUBMSG_TASK_ADDED, CustomMessages.Value.ofInt32(taskId));
    }

    /** 任务提交完成（sub=8）：客户端刷新头顶标记 + on_task_submit。 */
    public static byte[] taskSubmitted(int taskId) {
        return taskMsg(SUBMSG_TASK_SUBMITTED, CustomMessages.Value.ofInt32(taskId));
    }

    /** NPC 头顶任务标记（sub=15）：flag 2 可交 / 4 可接。 */
    public static byte[] npcEffect(String npcConfigId, int flag) {
        return taskMsg(SUBMSG_NPC_EFFECT,
                CustomMessages.Value.ofString(npcConfigId),
                CustomMessages.Value.ofInt32(flag));
    }

    /**
     * Task_Accepted 整行（官方 28 列方言；列语义 = task_define.lua
     * accept_rec_*：0 id/1 line/2 server/3 submit_scene/4 submit_npc/5 title/
     * 6 status/7 track_flag/8 step/9 time(i64)/10 plot/11 can_giveup(byte)/
     * 12 target/13 context/14 accept_npc/15 accept_scene/16 submit_npc_ex/
     * 17 prize_base/18 prize_ex/19 prize_over/20 accept_limit/21 submit_limit/
     * 22 repute_limit/23 vip(byte)/24 sceneid/25 prize_condition/26·27 未用）。
     */
    public static byte[] taskAcceptedRow(long objectId, long ownerId, TaskCatalog catalog,
                                         PlayerTask.Accepted entry) {
        TaskCatalog.TaskDef def = catalog.task(entry.taskId());
        if (def == null) {
            throw new IllegalArgumentException("task " + entry.taskId() + " not configured");
        }
        TaskCatalog.PrizeDef prize = catalog.prize(entry.taskId());
        return RecordMessages.recordAddCells((int) objectId, (int) ownerId,
                RecordMessages.RECORD_TASK_ACCEPTED,
                RecordMessages.Cell.ofInt(def.id()),                                  // 0 id
                RecordMessages.Cell.ofInt(def.line()),                               // 1 line
                RecordMessages.Cell.ofString(""),                                    // 2 server
                RecordMessages.Cell.ofInt(def.sceneId()),                            // 3 submit_scene
                RecordMessages.Cell.ofString(def.submitNpc()),                       // 4 submit_npc
                RecordMessages.Cell.ofString(def.titleId()),                         // 5 title 文本 key
                RecordMessages.Cell.ofInt(entry.status()),                           // 6 status
                RecordMessages.Cell.ofInt(0),                                        // 7 track_flag
                RecordMessages.Cell.ofInt(def.step()),                               // 8 step
                RecordMessages.Cell.ofInt64(entry.acceptedAtUnixMillis()),           // 9 time
                RecordMessages.Cell.ofInt(0),                                        // 10 plot
                RecordMessages.Cell.ofInt(def.canGiveup()),                          // 11 can_giveup
                RecordMessages.Cell.ofString(def.taskTargetId()),                    // 12 target
                RecordMessages.Cell.ofString(def.contextId()),                       // 13 context
                RecordMessages.Cell.ofString(def.acceptNpc()),                       // 14 accept_npc
                RecordMessages.Cell.ofInt(def.sceneId()),                            // 15 accept_scene
                RecordMessages.Cell.ofString(def.submitNpcEx()),                     // 16 submit_npc_ex
                RecordMessages.Cell.ofString(prize != null ? prize.renderPrizeString() : ""), // 17 prize_base
                RecordMessages.Cell.ofString(""),                                    // 18 prize_ex
                RecordMessages.Cell.ofString(""),                                    // 19 prize_over
                RecordMessages.Cell.ofString(def.acceptLimit()),                     // 20 accept_limit
                RecordMessages.Cell.ofString(def.submitLimit()),                     // 21 submit_limit
                RecordMessages.Cell.ofString(""),                                    // 22 repute_limit
                RecordMessages.Cell.ofInt(0),                                        // 23 vip
                RecordMessages.Cell.ofInt(def.sceneId()),                            // 24 sceneid
                RecordMessages.Cell.ofString(""));                                   // 25 prize_condition（26/27 补零）
    }

    /**
     * Task_Record story 步骤行（15 列：0 id/1 subtype/2 index/3 order/4 curnum/
     * 5 maxnum/6 key/7 scene/8 trackinfo/12 curnum_ex/13 maxnum_ex/14 hyper_link）。
     */
    public static byte[] taskStoryRow(long objectId, long ownerId, TaskCatalog catalog,
                                      TaskCatalog.Step step, String trackInfoId) {
        TaskCatalog.TaskDef def = catalog.task(step.taskId());
        return RecordMessages.recordAddCells((int) objectId, (int) ownerId,
                com.nineyin.server.protocol.RecordSchemas.indexByName("Task_Record"),
                RecordMessages.Cell.ofInt(step.taskId()),        // 0 id
                RecordMessages.Cell.ofInt(TASK_RECORD_SUBTYPE_STORY), // 1 subtype=story
                RecordMessages.Cell.ofInt(0),                    // 2 index
                RecordMessages.Cell.ofInt(step.order()),         // 3 order
                RecordMessages.Cell.ofInt(0),                    // 4 curnum
                RecordMessages.Cell.ofInt(1),                    // 5 maxnum
                RecordMessages.Cell.ofString(step.typeId()),     // 6 key（story 对话目标）
                RecordMessages.Cell.ofString(def != null ? String.valueOf(def.sceneId()) : ""), // 7 scene
                RecordMessages.Cell.ofString(trackInfoId == null ? "" : trackInfoId)); // 8 trackinfo
    }

    /**
     * Task_Completed 单行同步帧（删+加）：newValue 必须是该行合并后的完整位值
     * （客户端 RecvRecordRow 方言：增量更新必须先删行再整刷，CardRec 同模式；
     * 只发本任务 mask 会清掉同行其它任务位）。
     */
    public static List<byte[]> taskCompletedRowSync(long objectId, long ownerId, long row, long newValue) {
        List<byte[]> frames = new ArrayList<>(2);
        frames.add(RecordMessages.recordDelRow((int) objectId, (int) ownerId,
                RecordMessages.RECORD_TASK_COMPLETED, (int) row));
        frames.add(RecordMessages.recordAddInt32Row((int) objectId, (int) ownerId,
                RecordMessages.RECORD_TASK_COMPLETED, (int) row, newValue));
        return frames;
    }

    /**
     * 位图行值合并：任务 ID 集 → row→bits（Go taskCompletedBit 一基序号映射，
     * serial=taskId+1、row=serial/32、bit=serial%32（0 回退上行 32））。
     * Number 口径兼容 Integer（玩家集）与 Long（starter 族）。
     */
    public static Map<Long, Long> completedRowValues(Iterable<? extends Number> taskIds) {
        Map<Long, Long> rows = new TreeMap<>();
        for (Number taskId : taskIds) {
            long[] rowAndMask = completedBit(taskId.longValue());
            rows.merge(rowAndMask[0], rowAndMask[1], Long::sum);
        }
        return rows;
    }

    /** 一基位映射（与 StarterQingGong.taskCompletedBit 逐行对齐，starter 链复用）。 */
    public static long[] completedBit(long taskId) {
        long serial = taskId + 1;
        long row = serial / 32;
        long bit = serial % 32;
        if (bit == 0) {
            row--;
            bit = 32;
        }
        return new long[]{row, 1L << (bit - 1)};
    }

    private TaskMessages() {
    }
}
