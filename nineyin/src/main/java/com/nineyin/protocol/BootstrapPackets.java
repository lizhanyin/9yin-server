package com.nineyin.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * ClientReady 阶段的种子下发，全部对照 Go protocol-probe 移植：
 * - 8 个背包视图（选角链发，见 LoginFlow）
 * - QingGongRec 记录行 + View 46（分类 18 项 + QG_JH 技能实例 52 项）
 * - Task_Accepted / Task_Completed 功能解锁（主界面 + 内功面板前置）
 * - 打坐 + 心佛掌 + 太极拳(古谱) 快捷键
 * - View 43 内功容器种子（6 本初始内功，静态值）
 */
public final class BootstrapPackets {

    private BootstrapPackets() {
    }

    // ---------- 背包视图（选角链，0x0A 之后 0x0B 之前） ----------

    /** (viewId, capacity)：四大背包页 + 四个扩展页（share/view_define.lua）。 */
    private static final int[][] STARTER_BAG_VIEWS = {
            {121, 132}, {2, 132}, {123, 132}, {125, 132},
            {122, 16}, {3, 16}, {124, 16}, {126, 16},
    };

    public static List<byte[]> starterBagViewFrames() {
        List<byte[]> frames = new ArrayList<>(STARTER_BAG_VIEWS.length);
        for (int[] view : STARTER_BAG_VIEWS) {
            frames.add(SkillPackets.createView(view[0], view[1]));
        }
        return frames;
    }

    // ---------- 轻功（QingGongRec + View 46） ----------

    /** 轻功分类（QingGongRec 解锁分类；ConfigID 由客户端自行解析 QGDefine/QGSkill）。 */
    public static final List<String> STARTER_QINGGONG_IDS = List.of(
            "qinggong_1", "qinggong_2", "qinggong_3", "qinggong_4", "qinggong_5",
            "qinggong_6", "qinggong_7", "qinggong_8", "qinggong_9", "qinggong_10",
            "qinggong_11", "qinggong_12", "qinggong_14", "qinggong_15", "qinggong_16",
            "qinggong_17", "qinggong_26", "qinggong_gaojie");

    /** 轻功技能实例（WuXueQuery:GetLearnID_QGSkill 从 View 46 读卡片）。 */
    public static final List<String> STARTER_QINGGONG_SKILL_IDS = List.of(
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

    /** QingGongRec 行 + View 46 容器与全部项。0x0C 重进场景后玩家对象重建时需重发。 */
    public static List<byte[]> qinggongGrantFrames() {
        List<String> items = new ArrayList<>(STARTER_QINGGONG_IDS.size()
                + STARTER_QINGGONG_SKILL_IDS.size());
        items.addAll(STARTER_QINGGONG_IDS);
        items.addAll(STARTER_QINGGONG_SKILL_IDS);
        List<byte[]> frames = new ArrayList<>(items.size() + 1 + STARTER_QINGGONG_IDS.size());
        for (String id : STARTER_QINGGONG_IDS) {
            frames.add(S2CPackets.qinggongRowAdd(id));
        }
        frames.add(SkillPackets.createView(SkillPackets.VIEW_QINGGONG, items.size()));
        for (int i = 0; i < items.size(); i++) {
            frames.add(SkillPackets.qinggongViewAdd(SkillPackets.VIEW_QINGGONG, i + 1, items.get(i)));
        }
        return frames;
    }

    /** ActiveQGSkillRec 行（217 激活的高阶轻功）。 */
    public static byte[] activeQinggongRow(String id) {
        return S2CPackets.recordAddRow(RecordId.ACTIVE_QG_SKILL_REC, 0, id);
    }

    // ---------- 功能/内功面板解锁（Task_Accepted + Task_Completed） ----------

    private static final int FUNCTION_UNLOCK_TASK = 1346;
    private static final int FACULTY_GUIDE_FIRST = 1069;
    private static final int FACULTY_GUIDE_LAST = 1073;
    private static final int FACULTY_ALT_TASK = 1344;
    private static final int FACULTY_ACT_FIRST = 1140;
    private static final int FACULTY_ACT_LAST = 1147;
    private static final int FACULTY_ACT_ALT_TASK = 51002;

    /** taskCompletedBit：一Based任务串号映射到 32 位 Task_Completed 行/位（Go taskCompletedBit）。 */
    static long[] taskCompletedBit(int taskId) {
        long serial = taskId + 1L;
        long row = serial / 32;
        long bit = serial % 32;
        if (bit == 0) {
            row--;
            bit = 32;
        }
        return new long[]{row, 1L << (bit - 1)};
    }

    /** 已接任务（7 列兼容前缀：id、状态 9、0、三个空串、0）+ 完成位图行（含前置零行）。 */
    public static List<byte[]> functionUnlockFrames() {
        return functionUnlockFrames(taskId -> true);
    }

    /**
     * 功能/内功面板解锁（九阴志 tasks 门控版）：未到阶段的解锁任务整行剔除，
     * 主解锁任务 1346 同样过闸。任务号即 game_ninjiuzhi_overrides.tasks 的 item_key。
     */
    public static List<byte[]> functionUnlockFrames(java.util.function.IntPredicate taskOpen) {
        List<Integer> accepted = new ArrayList<>();
        for (int t = FACULTY_GUIDE_FIRST; t <= FACULTY_GUIDE_LAST; t++) {
            accepted.add(t);
        }
        accepted.add(FACULTY_ALT_TASK);
        for (int t = FACULTY_ACT_FIRST; t <= FACULTY_ACT_LAST; t++) {
            accepted.add(t);
        }
        accepted.add(FACULTY_ACT_ALT_TASK);
        accepted.removeIf(t -> !taskOpen.test(t));

        List<Integer> completed = new ArrayList<>(accepted);
        if (taskOpen.test(FUNCTION_UNLOCK_TASK)) {
            completed.add(FUNCTION_UNLOCK_TASK);
        }

        List<byte[]> frames = new ArrayList<>(accepted.size() + 8);
        for (int taskId : accepted) {
            frames.add(S2CPackets.recordAddRow(RecordId.TASK_ACCEPTED, 0,
                    taskId, 9, 0, "", "", "", 0));
        }
        long maxRow = 0;
        long[] rowValues = new long[64];
        for (int taskId : completed) {
            long[] rb = taskCompletedBit(taskId);
            if (rb[0] >= rowValues.length) {
                long[] grown = new long[(int) (rb[0] + 8)];
                System.arraycopy(rowValues, 0, grown, 0, rowValues.length);
                rowValues = grown;
            }
            rowValues[(int) rb[0]] |= rb[1];
            if (rb[0] > maxRow) {
                maxRow = rb[0];
            }
        }
        for (int row = 0; row <= maxRow; row++) {
            frames.add(S2CPackets.recordAddRow(RecordId.TASK_COMPLETED, 0,
                    (int) rowValues[row]));
        }
        return frames;
    }

    // ---------- 初始快捷键 ----------

    public static final int SITCROSS_SHORTCUT_INDEX = 9;
    private static final int HEART_BUDDHA_BASE = 10;   // grid_shortcut_1 第二页起点
    private static final int TAIJI_BASE = 20;          // grid_shortcut_1 第三页起点

    private static final List<String> HEART_BUDDHA_IDS = List.of(
            "CS_jh_xfz01", "CS_jh_xfz02", "CS_jh_xfz03", "CS_jh_xfz04",
            "CS_jh_xfz05", "CS_jh_xfz06", "CS_jh_xfz07");

    private static final List<String> TAIJI_IDS = List.of(
            "CS_wd_tjq01", "CS_wd_tjq02", "CS_wd_tjq03", "CS_wd_tjq04",
            "CS_wd_tjq05", "CS_wd_tjq06", "CS_wd_tjq07", "CS_wd_tjq08");

    /** 打坐快捷键（Ctrl+0，栏位 9）。 */
    public static byte[] sitcrossShortcutRow() {
        return S2CPackets.shortcutAdd(SITCROSS_SHORTCUT_INDEX, "skill", "zs_default_01");
    }

    /** 心佛掌（数字页 2）+ 太极拳古谱（数字页 3）快捷键。 */
    public record StarterShortcut(int index, String id) {
    }

    public static List<StarterShortcut> starterShortcuts() {
        List<StarterShortcut> list = new ArrayList<>(HEART_BUDDHA_IDS.size() + TAIJI_IDS.size());
        for (int i = 0; i < HEART_BUDDHA_IDS.size(); i++) {
            list.add(new StarterShortcut(HEART_BUDDHA_BASE + i, HEART_BUDDHA_IDS.get(i)));
        }
        for (int i = 0; i < TAIJI_IDS.size(); i++) {
            list.add(new StarterShortcut(TAIJI_BASE + i, TAIJI_IDS.get(i)));
        }
        return list;
    }

    /** 心佛掌（数字页 2）+ 太极拳古谱（数字页 3）快捷键行。 */
    public static List<byte[]> starterShortcutRows() {
        List<byte[]> frames = new ArrayList<>(HEART_BUDDHA_IDS.size() + TAIJI_IDS.size());
        for (StarterShortcut sc : starterShortcuts()) {
            frames.add(S2CPackets.shortcutAdd(sc.index(), "skill", sc.id()));
        }
        return frames;
    }

    // ---------- View 43 内功容器种子（静态值，取自 Go newPlayerProgress） ----------

    public record NeiGongBook(String configId, int staticData, int itemType, int level,
                              int maxLevel, long total, long power, long maxPower,
                              String buffId, int buffStaticData, int buffLevel, long fill,
                              String school, int wuXing,
                              int strAdd, int dexAdd, int ingAdd, int spiAdd, int staAdd) {
    }

    public static final List<NeiGongBook> STARTER_BOOKS = List.of(
            new NeiGongBook("ng_wd_001", 5, 1002, 30, 36, 273200, 4550, 4550,
                    "buf_ng_wd_001", 5954, 3, 0, "ng_wd", 5, 45, 13, 46, 13, 31),
            new NeiGongBook("ng_yh_001", 114, 1002, 30, 71, 579600, 8180, 8180,
                    "buf_ng_yh_001", 13073, 3, 0, "ng_yh", 0, 55, 70, 97, 67, 57),
            new NeiGongBook("ng_th_001", 113, 1002, 30, 71, 579600, 8180, 8180,
                    "buf_ng_th_001", 13434, 3, 0, "ng_th", 0, 66, 52, 103, 68, 56),
            new NeiGongBook("ng_wd_002", 6, 1002, 1, 36, 700, 120, 120, "", 0, 0, 0, "ng_wd", 5, 0, 0, 0, 0, 0),
            new NeiGongBook("ng_jh_001", 1, 1002, 1, 36, 700, 120, 120, "", 0, 0, 0, "ng_jh", 0, 0, 0, 0, 0, 0),
            new NeiGongBook("ng_em_001", 8, 1002, 1, 36, 700, 120, 120, "", 0, 0, 0, "ng_em", 0, 0, 0, 0, 0, 0));

    /** View 43 容器 + 6 本初始内功（9 属性布局）。 */
    public static List<byte[]> neigongViewFrames() {
        return neigongViewFrames(configId -> true);
    }

    /** View 43 容器 + 初始内功（九阴志 inner_books 门控版，键为内功 configId）。 */
    public static List<byte[]> neigongViewFrames(java.util.function.Predicate<String> bookOpen) {
        return neigongViewFrames(STARTER_BOOKS, bookOpen);
    }

    /**
     * View 43 容器 + 指定内功书列表（DB role_faculty 恢复路径；门控键为内功 configId）。
     * 容器容量按实际书本数，槽位顺序 = 列表序。
     */
    public static List<byte[]> neigongViewFrames(List<NeiGongBook> books,
                                                 java.util.function.Predicate<String> bookOpen) {
        List<byte[]> frames = new ArrayList<>(books.size() + 1);
        frames.add(SkillPackets.createView(SkillPackets.VIEW_NEIGONG, books.size()));
        int slot = 0;
        for (NeiGongBook book : books) {
            if (!bookOpen.test(book.configId())) {
                continue;
            }
            slot++;
            // 16 属性布局（对齐新参考 exe bookFrame 确名子集）：+School 门派代码串、
            // +五维 Add（StrAdd/DexAdd/IngAdd/SpiAdd/StaAdd）、+WuXing 五行（阴阳太极伴生 int）
            PacketWriter w = SkillPackets.viewAddHeader(SkillPackets.VIEW_NEIGONG, slot, 16)
                    .u16(SkillPackets.V_CONFIG_ID).string(book.configId())
                    .u16(PropertyId.STATIC_DATA).i32(book.staticData())
                    .u16(PropertyId.ITEM_TYPE).i32(book.itemType())
                    .u16(PropertyId.SCHOOL).string(book.school())
                    .u16(PropertyId.LEVEL).u8(book.level())
                    .u16(PropertyId.MAX_LEVEL).i32(book.maxLevel())
                    .u16(PropertyId.STR_ADD).i32(book.strAdd())
                    .u16(PropertyId.DEX_ADD).i32(book.dexAdd())
                    .u16(PropertyId.ING_ADD).i32(book.ingAdd())
                    .u16(PropertyId.SPI_ADD).i32(book.spiAdd())
                    .u16(PropertyId.STA_ADD).i32(book.staAdd())
                    .u16(PropertyId.CUR_FILL_VALUE).i32((int) book.fill())
                    .u16(PropertyId.TOTAL_FILL_VALUE).i32((int) book.total())
                    .u16(PropertyId.POWER_VALUE).i32((int) book.power())
                    .u16(PropertyId.MAX_POWER_VALUE).i32((int) book.maxPower())
                    .u16(PropertyId.WU_XING).i32(book.wuXing());
            frames.add(w.toByteArray());
        }
        return frames;
    }

    // ---------- ready 尾帧族（§3.22：出生特效 + 内置快捷键方案） ----------

    /** 出生特效目标（玩家 oid-owner；参考 `sent first-login born effect route=140 target=1-1`）。 */
    public static final String BORN_EFFECT_TARGET = "1-1";

    /**
     * 内置默认快捷键方案（参考 exe 内嵌常量，ready 尾经 msg 195 推送、
     * 同串另走 0x10 属性 326/327/328；两方案仅 3-6 槽顺序不同）。
     */
    public static final String DEFAULT_SHORTCUT_SCHEME_1 =
            "1,87;2,83;3,81;4,69;5,65;6,68;7,32;8,90;9,144;10,111;11,2;12,16;13,17;14,88;15,18;"
                    + "201,27;202,67;204,66;207,77;208,71;210,75;211,76;212,84;214,85;215,73;216,79;"
                    + "217,80;224,123;225,86;226,78;227,72;228,74;229,89;301,36;302,35;304,37;305,39;"
                    + "306,38;307,40;401,13;402,33;403,34;404,1114146;405,1114145;502,9;601,122;"
                    + "602,301989978;603,109;604,107;605,120;606,1114189;607,1114190;608,1114299;"
                    + "609,1114301;612,119;613,121;701,189;702,187;712,49;713,50;714,51;715,52;716,53;"
                    + "717,54;718,55;719,56;720,57;721,48;722,1114161;723,1114162;724,1114163;"
                    + "725,1114164;726,1114165;727,1114166;728,1114167;729,1114168;730,1114169;731,1114160;"
                    + "732,112;733,113;734,114;735,115;736,116;";

    public static final String DEFAULT_SHORTCUT_SCHEME_2 =
            "1,87;2,83;3,65;4,68;5,81;6,69;7,32;8,90;9,144;10,111;11,2;12,16;13,17;14,88;15,18;"
                    + "201,27;202,67;204,66;207,77;208,71;210,75;211,76;212,84;214,85;215,73;216,79;"
                    + "217,80;224,123;225,86;226,78;227,72;228,74;229,89;301,36;302,35;304,37;305,39;"
                    + "306,38;307,40;401,13;402,33;403,34;404,1114146;405,1114145;502,9;601,122;"
                    + "602,301989978;603,109;604,107;605,120;606,1114189;607,1114190;608,1114299;"
                    + "609,1114301;612,119;613,121;701,189;702,187;712,49;713,50;714,51;715,52;716,53;"
                    + "717,54;718,55;719,56;720,57;721,48;722,1114161;723,1114162;724,1114163;"
                    + "725,1114164;726,1114165;727,1114166;728,1114167;729,1114168;730,1114169;731,1114160;"
                    + "732,112;733,113;734,114;735,115;736,116;";

    /** 内置默认方案 ×2（msg 195 sub=1/2；库里有角色存档方案前，客户端栏位即此默认）。 */
    public static List<byte[]> shortcutSchemeFrames() {
        List<byte[]> frames = new ArrayList<>(2);
        frames.add(CustomPackets.shortcutScheme(1, DEFAULT_SHORTCUT_SCHEME_1));
        frames.add(CustomPackets.shortcutScheme(2, DEFAULT_SHORTCUT_SCHEME_2));
        return frames;
    }
}
