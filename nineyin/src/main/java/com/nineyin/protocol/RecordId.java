package com.nineyin.protocol;

/**
 * 协议记录表索引与 schema。
 *
 * 表 0..5 对应 C++ make_record_table_empty 的 6 张表（Java 行推送按此简化 schema 自洽）；
 * 表 6..39 为参考服 0x0A 帧实机声明的完整 40 表清单（2026-10-30 抓包逐字节解析，
 * 帧长 841 恰好消费完毕，见 docs/go-exe-protocol-analysis.md §3.22）。
 * 列类型：1=u8 2=i16 3=int32 4=int64 5=f32 6=f64 7=string 8=oid(8B)。
 *
 * 注意：参考服表 1/2/5 的列型与 Java 简化版不同
 * （ActiveQGSkillRec Go=[7,1]、Task_Accepted Go=28 列、cooldown_rec Go 末列=2）；
 * Java 的 0x11 行推送须与自家声明一致，故 0..5 维持原状，6..39 采用 Go 原始列型。
 */
public final class RecordId {

    private RecordId() {
    }

    public static final int COLUMN_U8 = 1;
    public static final int COLUMN_I16 = 2;
    public static final int COLUMN_INT = 3;
    public static final int COLUMN_LONG = 4;
    public static final int COLUMN_F32 = 5;
    public static final int COLUMN_F64 = 6;
    public static final int COLUMN_STRING = 7;
    public static final int COLUMN_OID = 8;

    public static final int QING_GONG_REC = 0;
    public static final int ACTIVE_QG_SKILL_REC = 1;
    public static final int TASK_ACCEPTED = 2;
    public static final int TASK_COMPLETED = 3;
    public static final int SHORTCUT_REC = 4;
    public static final int COOLDOWN_REC = 5;
    public static final int FORCE_REC = 6;
    public static final int TASK_TIME_LIMIT_REC = 7;
    public static final int TIME_LIMIT_FORM_REC = 8;
    public static final int CARD_REC = 9;
    public static final int USE_CARD_REC = 10;
    public static final int TITLE_REC = 11;
    public static final int ACTIVE_JINGMAI_REC = 12;
    public static final int PREPARE_JINGMAI_REC = 13;
    public static final int NEW_RIDE_REC = 14;
    public static final int VIP_INFO_REC = 15;
    public static final int WEATHER_REC = 16;
    public static final int RECV_LETTER_REC = 17;
    public static final int REC_BOX_EVENT = 18;
    public static final int REC_BOX_EVENT_PRIZE = 19;
    public static final int TASK_RECORD = 20;
    public static final int JOB_REC = 21;
    public static final int CARD_SKILL_REC = 22;
    public static final int BINGLU_RANK_REC = 23;
    public static final int FASHION_SUIT_REC = 24;
    public static final int BLEND_COLLECT_REC = 25;
    public static final int SECOND_WEAR_WUJI_REC = 26;
    public static final int WUJI_ACTIVATE_REC = 27;
    public static final int PROP_MODIFY_PACK_REC = 28;
    public static final int SKILL_MODIFY_PACK_REC = 29;
    public static final int RANDOM_PROP_REC = 30;
    public static final int RANDOM_SKILL_REC = 31;
    public static final int TEMP_TERSURE_REC = 32;
    public static final int CLONE_REC_SAVE = 33;
    public static final int TEAM_REC = 34;
    public static final int ORIGIN_ACTIVE = 35;
    public static final int ORIGIN_NOTIFY = 36;
    public static final int ORIGIN_COMPLETED = 37;
    public static final int ORIGIN_PRIZED = 38;
    public static final int REPUTE_RECORD = 39;

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
            // ---- 以下为参考服 40 表清单的 6..39（列型与 Go 0x0A 帧逐字节一致） ----
            new Schema("ForceRec", new int[]{COLUMN_I16, COLUMN_I16}),
            new Schema("TaskTimeLimitRec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_INT,
                    COLUMN_INT, COLUMN_LONG, COLUMN_LONG}),
            new Schema("Time_Limit_Form_Rec", new int[]{COLUMN_STRING, COLUMN_STRING,
                    COLUMN_INT, COLUMN_LONG, COLUMN_INT, COLUMN_STRING, COLUMN_INT, COLUMN_INT}),
            new Schema("CardRec", new int[]{COLUMN_INT}),
            new Schema("UseCardRec", new int[]{COLUMN_INT}),
            new Schema("title_rec", new int[]{COLUMN_INT, COLUMN_INT}),
            new Schema("active_jingmai_rec", new int[]{COLUMN_STRING}),
            new Schema("PepareJingMaiRec", new int[]{COLUMN_STRING}),
            new Schema("NewRideRec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_INT, COLUMN_INT}),
            new Schema("vip_info_rec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_LONG,
                    COLUMN_INT, COLUMN_LONG, COLUMN_LONG}),
            new Schema("WeatherRec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_INT,
                    COLUMN_STRING, COLUMN_STRING, COLUMN_LONG, COLUMN_INT}),
            new Schema("RecvLetterRec", new int[]{COLUMN_STRING, COLUMN_STRING, COLUMN_U8,
                    COLUMN_STRING, COLUMN_STRING, COLUMN_STRING, COLUMN_STRING, COLUMN_STRING,
                    COLUMN_LONG, COLUMN_U8, COLUMN_STRING, COLUMN_STRING, COLUMN_U8,
                    COLUMN_LONG, COLUMN_U8}),
            new Schema("rec_box_event", new int[]{COLUMN_STRING, COLUMN_INT}),
            new Schema("rec_box_event_prize", new int[]{COLUMN_STRING, COLUMN_INT}),
            new Schema("Task_Record", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_INT, COLUMN_INT,
                    COLUMN_INT, COLUMN_INT, COLUMN_STRING, COLUMN_STRING, COLUMN_STRING,
                    COLUMN_INT, COLUMN_STRING, COLUMN_LONG, COLUMN_INT, COLUMN_INT,
                    COLUMN_STRING}),
            new Schema("job_rec", new int[]{COLUMN_STRING, COLUMN_INT, COLUMN_INT, COLUMN_INT,
                    COLUMN_INT, COLUMN_INT, COLUMN_INT, COLUMN_INT}),
            new Schema("CardSkillRec", new int[]{COLUMN_STRING, COLUMN_INT, COLUMN_INT,
                    COLUMN_INT}),
            new Schema("binglu_rank_rec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_INT}),
            new Schema("FashionSuitRec", new int[]{COLUMN_STRING, COLUMN_INT, COLUMN_INT,
                    COLUMN_LONG, COLUMN_INT, COLUMN_INT}),
            new Schema("blend_collect_rec", new int[]{COLUMN_STRING, COLUMN_INT, COLUMN_INT,
                    COLUMN_INT}),
            new Schema("SecondWearWuJiRec", new int[]{COLUMN_INT}),
            new Schema("WuJiActivateRec", new int[]{COLUMN_INT}),
            new Schema("PropModifyPackRec", new int[]{COLUMN_INT}),
            new Schema("SkillModifyPackRec", new int[]{COLUMN_INT}),
            new Schema("RandomPropRec", new int[]{COLUMN_INT, COLUMN_F64, COLUMN_INT,
                    COLUMN_INT, COLUMN_INT, COLUMN_INT}),
            new Schema("RandomSkillRec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_F64,
                    COLUMN_INT}),
            new Schema("TempTersureRec", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_F64,
                    COLUMN_INT, COLUMN_INT, COLUMN_INT, COLUMN_INT, COLUMN_INT}),
            new Schema("clone_rec_save", new int[]{COLUMN_INT, COLUMN_INT, COLUMN_STRING,
                    COLUMN_LONG, COLUMN_INT, COLUMN_STRING, COLUMN_INT}),
            new Schema("team_rec", new int[]{COLUMN_OID, COLUMN_STRING, COLUMN_INT,
                    COLUMN_INT, COLUMN_INT, COLUMN_INT, COLUMN_STRING, COLUMN_INT,
                    COLUMN_INT, COLUMN_STRING, COLUMN_STRING, COLUMN_INT, COLUMN_INT}),
            new Schema("Origin_Active", new int[]{COLUMN_INT}),
            new Schema("Origin_Notify", new int[]{COLUMN_INT}),
            new Schema("Origin_Completed", new int[]{COLUMN_INT}),
            new Schema("Origin_Prized", new int[]{COLUMN_INT}),
            new Schema("Repute_Record", new int[]{COLUMN_STRING, COLUMN_INT, COLUMN_INT}),
    };
}
