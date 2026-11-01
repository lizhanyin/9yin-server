package com.nineyin.protocol;

/** 对齐 Go clientdata.Value + IndexedProperty：类型化属性值（0x09 表协商的线型）。 */
public final class PropertyValues {

    private PropertyValues() {
    }

    public record Value(WireType type, int u8, int u16, int i32, float f32, long u64, String text) {

        public static Value ofByte(int value) {
            return new Value(WireType.BYTE, value, 0, 0, 0, 0, null);
        }

        public static Value ofWord(int value) {
            return new Value(WireType.WORD, 0, value, 0, 0, 0, null);
        }

        public static Value ofInt32(int value) {
            return new Value(WireType.INT32, 0, 0, value, 0, 0, null);
        }

        public static Value ofFloat32(float value) {
            return new Value(WireType.FLOAT32, 0, 0, 0, value, 0, null);
        }

        public static Value ofString(String value) {
            return new Value(WireType.STRING, 0, 0, 0, 0, 0, value);
        }

        public static Value ofWideString(String value) {
            return new Value(WireType.WIDE_STRING, 0, 0, 0, 0, 0, value);
        }

        public static Value ofObject(long value) {
            return new Value(WireType.OBJECT, 0, 0, 0, 0, value, null);
        }

        public static Value ofObject(int objectId, int ownerId) {
            return ofObject((objectId & 0xFFFFFFFFL) | ((long) ownerId << 32));
        }
    }

    public record Indexed(int int16Index, String name, Value value) {

        public static Indexed of(int index, String name, Value value) {
            return new Indexed(index, name, value);
        }
    }
}
