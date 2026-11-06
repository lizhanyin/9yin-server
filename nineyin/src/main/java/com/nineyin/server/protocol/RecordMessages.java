package com.nineyin.server.protocol;

/**
 * 记录行帧（0x11 SERVER_RECORD_ADD / 0x12 SERVER_RECORD_DEL_ROW），逐行移植
 * Go cmd/protocol-probe qinggong_records.go 的 serverRecordAdd* 家族与
 * shortcut_records.go 的 serverRecordDelRow。
 *
 * <p>行归属 scene object（第二字节 0）；+0x0C/+0x0E 是源行起始/数量而非列号，
 * 每帧恰一整行。列类型码：1=byte、2=u16、3=int32、4=int64、6=f64、7=窄字符串、
 * 8=宽字符串（u32 字节长含尾宽 NUL + UTF-16LE + 0x0000，bin64 fxnet2.dll
 * RecvRecordRow case8 取证）、9=object。
 *
 * <p>记录索引 = 官方 447 schema 方言的帧内下标（{@link RecordSchemas}），
 * 不再使用本服自协商下标——客户端 C++ 原生消费者（CoolLogicManager 等）对
 * cooldown_rec 的绑定按官方表为准，行帧索引必须落在官方方言内。
 * 官方宽表（Task_Accepted 28 列）行帧必须满宽：短行会让 RecvRecordRow 越界，
 * {@link #recordAddCells} 对缺失尾列按线型补零/空串。
 */
public final class RecordMessages {

    /** 官方方言下标（0x0A 帧 447 schema 的帧内位置）。 */
    public static final int RECORD_TASK_ACCEPTED = RecordSchemas.indexByName("Task_Accepted");
    /** 任务步骤进度行（15 列：subtype 1 杀/2 采/3 交互/4 护送/5 quest/6 story/7 用道具）。 */
    public static final int RECORD_TASK_RECORD = RecordSchemas.indexByName("Task_Record");
    public static final int RECORD_TASK_COMPLETED = RecordSchemas.indexByName("Task_Completed");
    public static final int RECORD_SHORTCUT = RecordSchemas.indexByName("shortcut_rec");
    /** CoolLogicManager 原生回调：每行 = StartACool(category, end-start, team, 0)（cols [3,4,4,2]，team u16）。 */
    public static final int RECORD_COOLDOWN = RecordSchemas.indexByName("cooldown_rec");
    public static final int RECORD_FIGHT_RELATION = RecordSchemas.indexByName("fight_relation_rec");
    public static final int RECORD_QING_GONG = RecordSchemas.indexByName("QingGongRec");
    public static final int RECORD_ACTIVE_QING_GONG = RecordSchemas.indexByName("ActiveQGSkillRec");
    /** 战斗一期：当前施放技能（单字符串列，官方 [7]）。 */
    public static final int RECORD_ACTIVE_SKILL = RecordSchemas.indexByName("active_skill_rec");
    /** 经脉系统：已激活经脉（单字符串列，F 盘抓包行 'jm_dantian' 实证）。 */
    public static final int RECORD_ACTIVE_JINGMAI = RecordSchemas.indexByName("active_jingmai_rec");
    /** 邮箱收件列表（官方 16 列：0/3/4 宽串，5-7/10/11 窄串，8/13 int64，15 f64）。 */
    public static final int RECORD_RECV_LETTER = RecordSchemas.indexByName("RecvLetterRec");

    /** 记录格：与 Go recordCell 一致，同一时间至多一种值。 */
    public static final class Cell {
        final Long int32;
        final Long int64;
        final String text;
        final Double real;

        private Cell(Long int32, Long int64, String text, Double real) {
            this.int32 = int32;
            this.int64 = int64;
            this.text = text;
            this.real = real;
        }

        public static Cell ofInt(long value) {
            return new Cell(value, null, null, null);
        }

        public static Cell ofInt64(long value) {
            return new Cell(null, value, null, null);
        }

        public static Cell ofString(String value) {
            return new Cell(null, null, value, null);
        }

        public static Cell ofDouble(double value) {
            return new Cell(null, null, null, value);
        }
    }

    /** Go serverRecordAddString：单字符串列记录行（QingGongRec/active_skill_rec/active_jingmai_rec）。 */
    public static byte[] recordAddString(int objectId, int ownerId, int recordIndex, String value) {
        PacketWriter writer = PacketWriter.ofCapacity(21 + value.length());
        writer.writeU8(0x11).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(recordIndex)
                .writeU16(0).writeU16(1);
        writer.writeU32(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);
        return writer.writeUtf8(value).writeU8(0).toBytes();
    }

    /** Go serverRecordAddInt32：单 int 列记录行（Task_Completed 位图）。 */
    public static byte[] recordAddInt32(int objectId, int ownerId, int recordIndex, long value) {
        return recordAddInt32Row(objectId, ownerId, recordIndex, 0, value);
    }

    /**
     * 显式起始行的单 int 列记录行（CardRec 位图按行号对齐：行 N = 卡号
     * [32N+1, 32N+32] 的位图）。客户端对 startRow 的方言是「空记录追加 / 指定行
     * 覆盖」皆可命中（整刷时行序升序推送），增量更新必须先删行再整刷。
     */
    public static byte[] recordAddInt32Row(int objectId, int ownerId, int recordIndex, int row, long value) {
        PacketWriter writer = PacketWriter.ofCapacity(20);
        return writer.writeU8(0x11).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(recordIndex)
                .writeU16(row).writeU16(1)
                .writeU32(value)
                .toBytes();
    }

    /**
     * 按官方 schema 写一整行。调用方可给前缀列（窄于声明宽度时缺失尾列按线型补
     * 零/空串——Task_Accepted 官方 28 列而功能解锁只填前 7 列）；超出声明宽度抛错。
     */
    public static byte[] recordAddCells(int objectId, int ownerId, int recordIndex, Cell... cells) {
        RecordSchemas.Schema schema = RecordSchemas.byIndex(recordIndex);
        if (schema == null) {
            throw new IllegalArgumentException("record index " + recordIndex + " outside official table");
        }
        byte[] columnTypes = schema.columnTypes();
        if (cells.length > columnTypes.length) {
            throw new IllegalArgumentException("record " + schema.name()
                    + " has " + cells.length + " cells, want at most " + columnTypes.length);
        }
        PacketWriter writer = PacketWriter.ofCapacity(16 + columnTypes.length * 8);
        writer.writeU8(0x11).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(recordIndex)
                .writeU16(0).writeU16(1);
        for (int column = 0; column < columnTypes.length; column++) {
            Cell cell = column < cells.length ? cells[column] : null;
            switch (columnTypes[column]) {
                case 1 -> {
                    // 官方表含 byte 列（ActiveQGSkillRec 第 2 列/Task_Accepted 12·24 列）；缺省补 0。
                    if (cell != null) {
                        requireInt32(cell, recordIndex, column);
                    }
                    writer.writeU8(cell == null ? 0 : (int) (cell.int32 & 0xFFL));
                }
                case 2 -> {
                    // 官方 cooldown_rec 第 4 列 team 是 u16（0xFFFF 个人行 / CoolDownTeam GCD 行）。
                    if (cell != null) {
                        requireInt32(cell, recordIndex, column);
                    }
                    writer.writeU16(cell == null ? 0 : (int) (cell.int32 & 0xFFFFL));
                }
                case 3 -> {
                    if (cell != null) {
                        requireInt32(cell, recordIndex, column);
                    }
                    writer.writeU32(cell == null ? 0 : cell.int32);
                }
                case 4 -> {
                    if (cell != null) {
                        requireInt64(cell, recordIndex, column);
                    }
                    writer.writeU64(cell == null ? 0 : cell.int64);
                }
                case 7 -> {
                    if (cell != null) {
                        requireString(cell, recordIndex, column);
                    }
                    String text = cell == null || cell.text == null ? "" : cell.text;
                    writer.writeU32(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);
                    writer.writeUtf8(text).writeU8(0);
                }
                case 6 -> {
                    // f64 列（RecvLetterRec 末列等）：8 字节 IEEE-754。
                    if (cell != null) {
                        requireDouble(cell, recordIndex, column);
                    }
                    writer.writeF64(cell == null ? 0.0 : cell.real);
                }
                case 8 -> {
                    // 宽字符串（bin64 fxnet2.dll RecvRecordRow 0x1060BC40 case8 取证）：
                    // u32 字节长（含尾宽 NUL，按字节计）+ UTF-16LE + 0x0000，与 case7 窄串对称。
                    if (cell != null) {
                        requireString(cell, recordIndex, column);
                    }
                    String text = cell == null || cell.text == null ? "" : cell.text;
                    byte[] wide = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
                    writer.writeU32(wide.length + 2);
                    writer.writeBytes(wide).writeU16(0);
                }
                default -> throw new IllegalArgumentException(
                        "record " + schema.name() + " column " + column
                        + " has unsupported official type " + columnTypes[column]);
            }
        }
        return writer.toBytes();
    }

    /** Go shortcut_records.go serverRecordDelRow：末位 u16 是记录内零基行号。 */
    public static byte[] recordDelRow(int objectId, int ownerId, int recordIndex, int row) {
        PacketWriter writer = PacketWriter.ofCapacity(14);
        return writer.writeU8(0x12).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(recordIndex)
                .writeU16(row)
                .toBytes();
    }

    private static void requireInt32(Cell cell, int recordIndex, int column) {
        if (cell.int32 == null || cell.int64 != null || cell.text != null || cell.real != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires int32");
        }
    }

    private static void requireInt64(Cell cell, int recordIndex, int column) {
        if (cell.int64 == null || cell.int32 != null || cell.text != null || cell.real != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires int64");
        }
    }

    private static void requireString(Cell cell, int recordIndex, int column) {
        if (cell.text == null || cell.int32 != null || cell.int64 != null || cell.real != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires string");
        }
    }

    private static void requireDouble(Cell cell, int recordIndex, int column) {
        if (cell.real == null || cell.int32 != null || cell.int64 != null || cell.text != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires double");
        }
    }

    private RecordMessages() {
    }
}
