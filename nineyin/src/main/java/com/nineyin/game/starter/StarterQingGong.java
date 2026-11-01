package com.nineyin.game.starter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nineyin.protocol.Props;
import com.nineyin.protocol.RecordMessages;
import com.nineyin.protocol.SceneMessages;

/**
 * ClientReady 后的轻功/功能解锁下发，逐行移植 Go qinggong_records.go 的
 * starter 表与 grantStarterQingGong/grantFunctionUnlock 帧序列。
 *
 * <p>轻功有两条真源：QingGongRec 记录行喂 CheckLearn_QingGong，
 * View 46（QingGongContainer）喂条件检查与 WuXueQuery:GetLearnID_QGSkill。
 */
public final class StarterQingGong {

    public static final int VIEWPORT_QING_GONG = 46;
    /** 当前客户端的基础轻功类目（配置 ID，客户端自行解析 QGDefine/QGSkill）。 */
    public static final List<String> STARTER_QING_GONG_IDS = List.of(
            "qinggong_1", "qinggong_2", "qinggong_3", "qinggong_4", "qinggong_5",
            "qinggong_6", "qinggong_7", "qinggong_8", "qinggong_9", "qinggong_10",
            "qinggong_11", "qinggong_12", "qinggong_14", "qinggong_15", "qinggong_16",
            "qinggong_17", "qinggong_26", "qinggong_gaojie");

    /** QG 技能实例 ID（非类目）：View 46 卡片数据源。 */
    public static final List<String> STARTER_QING_GONG_SKILL_IDS = List.of(
            "QG_JH_001", "QG_JH_002", "QG_JH_003", "QG_JH_004", "QG_JH_005", "QG_JH_006",
            "QG_JH_007", "QG_JH_008", "QG_JH_009", "QG_JH_010",
            "QG_JH_011", "QG_JH_012", "QG_JH_013", "QG_JH_014", "QG_JH_015", "QG_JH_016",
            "QG_JH_017", "QG_JH_018", "QG_JH_019", "QG_JH_020",
            "QG_JH_021", "QG_JH_022", "QG_JH_023", "QG_JH_024", "QG_JH_025", "QG_JH_026",
            "QG_JH_027", "QG_JH_028", "QG_JH_029", "QG_JH_02_001",
            "QG_JH_030", "QG_JH_031", "QG_JH_032", "QG_JH_033", "QG_JH_034", "QG_JH_035",
            "QG_JH_036", "QG_JH_037", "QG_JH_038", "QG_JH_039",
            "QG_JH_03_001", "QG_JH_040", "QG_JH_041", "QG_JH_04_001", "QG_JH_05_001",
            "QG_JH_06_001", "QG_JH_gj_001", "QG_JH_gj_002", "QG_JH_gj_003", "QG_JH_gj_004",
            "QG_JH_gj_006", "QG_JH_gjskill_001");

    // ---- 功能/内功前置任务（条件 23614/23613 的直读与已完成双分支） ----
    static final long FUNCTION_UNLOCK_TASK_ID = 1346;
    static final long FACULTY_GUIDE_TASK_FIRST = 1069;
    static final long FACULTY_GUIDE_TASK_LAST = 1073;
    static final long FACULTY_ALT_TASK_ID = 1344;
    static final long FACULTY_ACT_TASK_FIRST = 1140;
    static final long FACULTY_ACT_TASK_LAST = 1147;
    static final long FACULTY_ACT_ALT_TASK_ID = 51002;

    /** Go grantStarterQingGong：QingGongRec 行 + View 46 容器与条目（不含功能解锁）。 */
    public static List<byte[]> qingGongFrames(int objectId, int ownerId) {
        List<byte[]> frames = new ArrayList<>(STARTER_QING_GONG_IDS.size()
                + STARTER_QING_GONG_SKILL_IDS.size() + 1);
        for (String id : STARTER_QING_GONG_IDS) {
            frames.add(RecordMessages.recordAddString(objectId, ownerId,
                    RecordMessages.RECORD_QING_GONG, id));
        }
        List<String> viewItems = viewItems();
        frames.add(SceneMessages.createView(
                new SceneMessages.ViewSpec(VIEWPORT_QING_GONG, viewItems.size())));
        for (int slot = 0; slot < viewItems.size(); slot++) {
            List<SceneMessages.ViewProperty> properties = new ArrayList<>(1);
            Props.addView(properties, "ConfigID",
                    com.nineyin.protocol.PropertyValues.Value.ofString(viewItems.get(slot)));
            frames.add(SceneMessages.viewAdd(VIEWPORT_QING_GONG, slot + 1, properties));
        }
        return frames;
    }

    /**
     * Go grantFunctionUnlock：Task_Accepted 七列兼容行（id,9,0,"","","",0）
     * + Task_Completed 位图全行 0..finalRow（append-only，含前导零行）。
     */
    public static List<byte[]> functionUnlockFrames(int objectId, int ownerId) {
        List<byte[]> frames = new ArrayList<>(acceptedTaskIds().size() + 16);
        for (long taskId : acceptedTaskIds()) {
            frames.add(RecordMessages.recordAddCells(objectId, ownerId,
                    RecordMessages.RECORD_TASK_ACCEPTED,
                    RecordMessages.Cell.ofInt(taskId), RecordMessages.Cell.ofInt(9), RecordMessages.Cell.ofInt(0),
                    RecordMessages.Cell.ofString(""), RecordMessages.Cell.ofString(""),
                    RecordMessages.Cell.ofString(""), RecordMessages.Cell.ofInt(0)));
        }
        Map<Long, Long> rowValues = new HashMap<>();
        long finalRow = 0;
        for (long taskId : completedTaskIds()) {
            long[] rowAndMask = taskCompletedBit(taskId);
            rowValues.merge(rowAndMask[0], rowAndMask[1], Long::sum);
            if (rowAndMask[0] > finalRow) {
                finalRow = rowAndMask[0];
            }
        }
        for (long row = 0; row <= finalRow; row++) {
            frames.add(RecordMessages.recordAddInt32(objectId, ownerId,
                    RecordMessages.RECORD_TASK_COMPLETED, rowValues.getOrDefault(row, 0L)));
        }
        return frames;
    }

    /** Go taskCompletedBit：一位基任务序号映射 32 位 Task_Completed 行/位。 */
    static long[] taskCompletedBit(long taskId) {
        long serial = taskId + 1;
        long row = serial / 32;
        long bit = serial % 32;
        if (bit == 0) {
            row--;
            bit = 32;
        }
        return new long[]{row, 1L << (bit - 1)};
    }

    static List<Long> acceptedTaskIds() {
        List<Long> ids = new ArrayList<>(15);
        for (long id = FACULTY_GUIDE_TASK_FIRST; id <= FACULTY_GUIDE_TASK_LAST; id++) {
            ids.add(id);
        }
        ids.add(FACULTY_ALT_TASK_ID);
        for (long id = FACULTY_ACT_TASK_FIRST; id <= FACULTY_ACT_TASK_LAST; id++) {
            ids.add(id);
        }
        ids.add(FACULTY_ACT_ALT_TASK_ID);
        return ids;
    }

    static List<Long> completedTaskIds() {
        List<Long> ids = new ArrayList<>(16);
        ids.add(FUNCTION_UNLOCK_TASK_ID);
        ids.add(FACULTY_ALT_TASK_ID);
        ids.add(FACULTY_ACT_ALT_TASK_ID);
        for (long id = FACULTY_GUIDE_TASK_FIRST; id <= FACULTY_GUIDE_TASK_LAST; id++) {
            ids.add(id);
        }
        for (long id = FACULTY_ACT_TASK_FIRST; id <= FACULTY_ACT_TASK_LAST; id++) {
            ids.add(id);
        }
        return ids;
    }

    static List<String> viewItems() {
        List<String> items = new ArrayList<>(STARTER_QING_GONG_IDS.size() + STARTER_QING_GONG_SKILL_IDS.size());
        items.addAll(STARTER_QING_GONG_IDS);
        items.addAll(STARTER_QING_GONG_SKILL_IDS);
        return items;
    }

    private StarterQingGong() {
    }
}
