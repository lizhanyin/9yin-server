package com.nineyin.server.game.avatar.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.protocol.RecordSchemas;

/**
 * 声望记录行帧（Repute_Record，官方 447 schema 方言下标 44，列 [窄串,int32,int32]
 * = 声望名/数值/等级）。行序 = 内存 LinkedHashMap 下发序；增量更新先 0x12 倒序
 * 删旧行再 0x11 整刷（MountMessages.ownedClearFrames/ownedFrames 同方言——
 * NewRideRec/CardRec 增量更新已验证客户端接受「先删后加」整刷）。
 *
 * <p>消费方：condition.ini 1706 条 {@code Name=Repute_Record} 条件（按行名取列值
 * 比较，Para2=阵营名）与声望面板；等级列按 {@link ReputeLevelTable#levelOf} 填。
 */
public final class ReputeMessages {

    /** 官方 447 schema 方言下标。 */
    public static final int RECORD_REPUTE = RecordSchemas.indexByName("Repute_Record");

    private ReputeMessages() {
    }

    /** 声望行整推：一行一阵营（名/值/等级，等级由调用方按曲线算好）。 */
    public static List<byte[]> rowsFrames(int objectId, int ownerId,
                                          Map<String, Long> rows, ReputeLevelTable table) {
        List<byte[]> frames = new ArrayList<>(rows.size());
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            frames.add(RecordMessages.recordAddCells(objectId, ownerId, RECORD_REPUTE,
                    RecordMessages.Cell.ofString(row.getKey()),
                    RecordMessages.Cell.ofInt(row.getValue().intValue()),
                    RecordMessages.Cell.ofInt(table.levelOf(row.getKey(), row.getValue()))));
        }
        return frames;
    }

    /** 删除已存在的声望行（旧行数 0..oldRows-1 倒序，整刷前清场）。 */
    public static List<byte[]> clearFrames(int objectId, int ownerId, int oldRows) {
        List<byte[]> frames = new ArrayList<>(oldRows);
        for (int row = oldRows - 1; row >= 0; row--) {
            frames.add(RecordMessages.recordDelRow(objectId, ownerId, RECORD_REPUTE, row));
        }
        return frames;
    }
}
