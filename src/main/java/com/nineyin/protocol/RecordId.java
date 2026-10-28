package com.nineyin.protocol;

/**
 * 协议记录表索引与 schema，对应 C++ make_record_table_empty 的 6 张表
 * （0x0A 记录表下发顺序即此数组顺序）。
 * 列类型：3=int32 4=int64 7=string。
 */
public final class RecordId {

    private RecordId() {
    }

    public static final int COLUMN_INT = 3;
    public static final int COLUMN_LONG = 4;
    public static final int COLUMN_STRING = 7;

    public static final int QING_GONG_REC = 0;
    public static final int ACTIVE_QG_SKILL_REC = 1;
    public static final int TASK_ACCEPTED = 2;
    public static final int TASK_COMPLETED = 3;
    public static final int SHORTCUT_REC = 4;
    public static final int COOLDOWN_REC = 5;

    public record Schema(String name, int[] columnTypes) {
    }

    public static final Schema[] SCHEMAS = {
            new Schema("QingGongRec", new int[]{COLUMN_STRING}),
            new Schema("ActiveQGSkillRec", new int[]{COLUMN_STRING}),
            new Schema("Task_Accepted", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_INT,
                    COLUMN_STRING, COLUMN_STRING, COLUMN_STRING, COLUMN_INT}),
            new Schema("Task_Completed", new int[]{COLUMN_INT}),
            new Schema("shortcut_rec", new int[]{COLUMN_INT, COLUMN_STRING, COLUMN_STRING}),
            new Schema("cooldown_rec", new int[]{COLUMN_INT, COLUMN_LONG, COLUMN_LONG, COLUMN_INT}),
    };
}
