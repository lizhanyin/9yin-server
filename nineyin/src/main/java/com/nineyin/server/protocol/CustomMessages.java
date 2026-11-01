package com.nineyin.server.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 自定义 TVarList 消息（S2C 0x1E 旧壳 / 0x27 现代壳），逐行移植 Go
 * cmd/protocol-probe messages.go 的 serverCustomIntMessage /
 * serverModernCustomIntMessage / encodeServerCustomValuesWithOpcode。
 *
 * <p>布局：opcode | u16 参数个数 | 参数*，每个参数 = u8 线型 + 载荷；
 * 线型 2=int32、3=int64、4=float32、6=窄字符串(u32(字节数+1)+文本+NUL)、8=object(u32+u32)。
 * 数字路由首参是消息 ID，合法区间 [1,764]（FxGameLogic 注册表槽位换算）。
 */
public final class CustomMessages {

    /** 与 Go serverCustomValue 一致：至多一种值非空（wideText 为 F 盘 exe 扩展的 tag7）。 */
    public static final class Value {
        final String text;
        final String wideText;
        final Integer int32;
        final Long int64;
        final Float float32;
        final Double float64;
        final long objectId;
        final long ownerId;
        /** 空对象（tag8 + 双 u32 零）：msg20 玩家出招目标恒 obj(0,0)（62433 技能抓包实证）。 */
        final boolean emptyObject;

        private Value(String text, Integer int32, Long int64, Float float32, long objectId, long ownerId) {
            this(text, null, int32, int64, float32, null, objectId, ownerId);
        }

        private Value(String text, Integer int32, Long int64, Float float32,
                      Double float64, long objectId, long ownerId) {
            this(text, null, int32, int64, float32, float64, objectId, ownerId);
        }

        private Value(String text, String wideText, Integer int32, Long int64, Float float32,
                      Double float64, long objectId, long ownerId) {
            this(text, wideText, int32, int64, float32, float64, objectId, ownerId, false);
        }

        private Value(boolean emptyObject) {
            this(null, null, null, null, null, null, 0, 0, emptyObject);
        }

        private Value(String text, String wideText, Integer int32, Long int64, Float float32,
                      Double float64, long objectId, long ownerId, boolean emptyObject) {
            this.text = text;
            this.wideText = wideText;
            this.int32 = int32;
            this.int64 = int64;
            this.float32 = float32;
            this.float64 = float64;
            this.objectId = objectId;
            this.ownerId = ownerId;
            this.emptyObject = emptyObject;
        }

        public String text() {
            return text;
        }

        public boolean isText() {
            return text != null;
        }

        public boolean isFloat64() {
            return float64 != null;
        }

        public double float64() {
            return float64 == null ? 0 : float64;
        }

        public int int32() {
            return int32 == null ? 0 : int32;
        }

        public long int64() {
            return int64 == null ? 0 : int64;
        }

        public float float32() {
            return float32 == null ? 0 : float32;
        }

        public static Value ofString(String value) {
            return new Value(value, null, null, null, 0, 0);
        }

        /** F 盘 exe 的 tag7 宽字符串（msg169 双角色名等），对齐老分支 appendUcs2。 */
        public static Value ofWideString(String value) {
            return new Value(null, value, null, null, null, null, 0, 0);
        }

        public static Value ofInt32(int value) {
            return new Value(null, value, null, null, 0, 0);
        }

        public static Value ofInt64(long value) {
            return new Value(null, null, value, null, 0, 0);
        }

        public static Value ofFloat64(double value) {
            return new Value(null, null, null, null, value, 0L, 0L);
        }

        public static Value ofFloat32(float value) {
            return new Value(null, null, null, value, 0, 0);
        }

        public static Value ofObject(long objectId, long ownerId) {
            return new Value(null, null, null, null, objectId, ownerId);
        }

        /** 空对象 obj(0,0)：编码为 tag8 + 双 u32 零（非空对象的退路是抛 empty argument）。 */
        public static Value ofEmptyObject() {
            return new Value(true);
        }
    }

    /** Go serverCustomIntMessage：旧 0x1E 壳（仅限已在该壳上验证过的路由）。 */
    public static byte[] customIntMessage(int messageId, Value... values) {
        return customIntMessageWithOpcode(0x1E, messageId, values);
    }

    /** Go serverModernCustomIntMessage：现代 FxNet2 别名壳 0x27（新原生路由用它）。 */
    public static byte[] modernCustomIntMessage(int messageId, Value... values) {
        return customIntMessageWithOpcode(0x27, messageId, values);
    }

    private static byte[] customIntMessageWithOpcode(int opcode, int messageId, Value... values) {
        if (messageId < 1 || messageId >= 765) {
            throw new IllegalArgumentException("custom message id " + messageId + " outside client range [1,764]");
        }
        Value[] args = new Value[values.length + 1];
        args[0] = Value.ofInt32(messageId);
        System.arraycopy(values, 0, args, 1, values.length);
        return encode(opcode, args);
    }

    private static byte[] encode(int opcode, Value[] args) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(opcode).writeU16(args.length);
        for (Value value : args) {
            if (value.wideText != null) {
                // tag7 宽字符串：u32(字节数+2) + UTF-16LE 文本 + u16 NUL（F 盘抓包 msg169 双名实证）。
                byte[] data = value.wideText.getBytes(StandardCharsets.UTF_16LE);
                writer.writeU8(7).writeU32(data.length + 2).writeBytes(data).writeU16(0);
            } else if (value.text != null) {
                byte[] data = value.text.getBytes(StandardCharsets.UTF_8);
                writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
            } else if (value.int32 != null) {
                writer.writeU8(2).writeU32(value.int32 & 0xFFFFFFFFL);
            } else if (value.int64 != null) {
                writer.writeU8(3).writeU64(value.int64);
            } else if (value.float32 != null) {
                writer.writeU8(4).writeU32(Float.floatToRawIntBits(value.float32) & 0xFFFFFFFFL);
            } else if (value.float64 != null) {
                writer.writeU8(5).writeU64(Double.doubleToRawLongBits(value.float64));
            } else if (value.objectId != 0 || value.ownerId != 0 || value.emptyObject) {
                writer.writeU8(8).writeU32(value.objectId).writeU32(value.ownerId);
            } else {
                throw new IllegalArgumentException("custom message has an empty argument");
            }
        }
        return writer.toBytes();
    }

    private CustomMessages() {
    }
}
