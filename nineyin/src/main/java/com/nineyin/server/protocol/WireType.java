package com.nineyin.server.protocol;

/**
 * 协商属性线型，对齐 Go clientdata.WireType。官方表 type=4 是小端 int64（非 double）：
 * 真机抓包实证（captures/local 12141 s2c）CapitalType1 行 5C97010000000000=104284、
 * CapitalType2 行 8005480000000000=4720000；58 个 4 型行全是货币/时间戳/计数语义。
 */
public enum WireType {
    BYTE(1),
    WORD(2),
    INT32(3),
    INT64(4),
    FLOAT32(5),
    STRING(7),
    WIDE_STRING(8),
    OBJECT(9);

    public final int value;

    WireType(int value) {
        this.value = value;
    }

    public static WireType of(int value) {
        for (WireType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported wire type " + value);
    }
}
