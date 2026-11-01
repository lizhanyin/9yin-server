package com.nineyin.protocol;

/** 协商属性线型，对齐 Go clientdata.WireType。 */
public enum WireType {
    BYTE(1),
    WORD(2),
    INT32(3),
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
