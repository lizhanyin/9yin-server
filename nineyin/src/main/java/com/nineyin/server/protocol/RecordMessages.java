package com.nineyin.server.protocol;

import java.util.List;

/**
 * 记录行帧（0x11 SERVER_RECORD_ADD / 0x12 SERVER_RECORD_DEL_ROW），逐行移植
 * Go cmd/protocol-probe qinggong_records.go 的 serverRecordAdd* 家族与
 * shortcut_records.go 的 serverRecordDelRow。
 *
 * <p>行归属 scene object（第二字节 0）；+0x0C/+0x0E 是源行起始/数量而非列号，
 * 每帧恰一整行时恒为 0/1。列类型码：3=int32、4=int64、7=窄字符串。
 */
public final class RecordMessages {

    /** 记录索引 = recordTable 协商顺序（QING_GONG_RECORD_SCHEMAS 的下标）。 */
    public static final int RECORD_QING_GONG = 0;
    public static final int RECORD_ACTIVE_QING_GONG = 1;
    public static final int RECORD_TASK_ACCEPTED = 2;
    public static final int RECORD_TASK_COMPLETED = 3;
    public static final int RECORD_SHORTCUT = 4;
    public static final int RECORD_COOLDOWN = 5;
    /** 战斗一期追加（官方协商表同名记录：active_skill_rec=[7]、fight_relation_rec=[9,2]）。 */
    public static final int RECORD_ACTIVE_SKILL = 6;
    public static final int RECORD_FIGHT_RELATION = 7;

    /** 记录格：与 Go recordCell 一致，同一时间至多一种值。 */
    public static final class Cell {
        final Long int32;
        final Long int64;
        final String text;

        private Cell(Long int32, Long int64, String text) {
            this.int32 = int32;
            this.int64 = int64;
            this.text = text;
        }

        public static Cell ofInt(long value) {
            return new Cell(value, null, null);
        }

        public static Cell ofInt64(long value) {
            return new Cell(null, value, null);
        }

        public static Cell ofString(String value) {
            return new Cell(null, null, value);
        }
    }

    /** Go serverRecordAddString：单字符串列记录行（QingGongRec/ActiveQGSkillRec）。 */
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
        PacketWriter writer = PacketWriter.ofCapacity(20);
        return writer.writeU8(0x11).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(recordIndex)
                .writeU16(0).writeU16(1)
                .writeU32(value)
                .toBytes();
    }

    /** Go serverRecordAddCells：按协商 schema 写一整行（Task_Accepted/shortcut_rec/cooldown_rec）。 */
    public static byte[] recordAddCells(int objectId, int ownerId, int recordIndex, Cell... cells) {
        List<SceneMessages.RecordSchema> schemas = SceneMessages.QING_GONG_RECORD_SCHEMAS;
        if (recordIndex < 0 || recordIndex >= schemas.size()) {
            throw new IllegalArgumentException("record index " + recordIndex + " has no negotiated schema");
        }
        byte[] columnTypes = schemas.get(recordIndex).columnTypes();
        if (cells.length != columnTypes.length) {
            throw new IllegalArgumentException("record " + schemas.get(recordIndex).name()
                    + " has " + cells.length + " cells, want complete row of " + columnTypes.length);
        }
        PacketWriter writer = PacketWriter.ofCapacity(16 + cells.length * 8);
        writer.writeU8(0x11).writeU8(0)
                .writeU32(objectId).writeU32(ownerId)
                .writeU16(recordIndex)
                .writeU16(0).writeU16(1);
        for (int column = 0; column < cells.length; column++) {
            Cell cell = cells[column];
            switch (columnTypes[column]) {
                case 2 -> {
                    // 官方 cooldown_rec 第 4 列 team 是 u16（0xFFFF 个人行 / CoolDownTeam GCD 行）。
                    requireInt32(cell, recordIndex, column);
                    writer.writeU16((int) (cell.int32 & 0xFFFFL));
                }
                case 3 -> {
                    requireInt32(cell, recordIndex, column);
                    writer.writeU32(cell.int32);
                }
                case 4 -> {
                    requireInt64(cell, recordIndex, column);
                    writer.writeU64(cell.int64);
                }
                case 7 -> {
                    requireString(cell, recordIndex, column);
                    String text = cell.text == null ? "" : cell.text;
                    writer.writeU32(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);
                    writer.writeUtf8(text).writeU8(0);
                }
                default -> throw new IllegalArgumentException(
                        "record column " + column + " has unsupported negotiated type " + columnTypes[column]);
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
        if (cell.int32 == null || cell.int64 != null || cell.text != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires int32");
        }
    }

    private static void requireInt64(Cell cell, int recordIndex, int column) {
        if (cell.int64 == null || cell.int32 != null || cell.text != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires int64");
        }
    }

    private static void requireString(Cell cell, int recordIndex, int column) {
        if (cell.text == null || cell.int32 != null || cell.int64 != null) {
            throw new IllegalArgumentException("record " + recordIndex + " column " + column + " requires string");
        }
    }

    private RecordMessages() {
    }
}
