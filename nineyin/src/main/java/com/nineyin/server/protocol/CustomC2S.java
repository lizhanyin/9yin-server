package com.nineyin.server.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端自定义 TVarList 消息解析，逐行移植 Go custom_c2s.go：
 * ① 0x1E/0x27 直达帧；② 0x0A 活动信封（21 字节头后同构列表）。
 * 值标签：2=int32 3=int64 4=float32 5=float64 6=窄字符串 7=宽字符串 8=object。
 * 解析失败返回 null（信封里还有运动/活动流量，不是错误）。
 */
public final class CustomC2S {

    /** Go clientCustomValue。 */
    public static final class Value {
        public final int tag;
        public final long int32;
        public final long int64;
        public final float float32;
        public final double float64;
        public final String text;

        Value(int tag, long int32, long int64, float float32, double float64, String text) {
            this.tag = tag;
            this.int32 = int32;
            this.int64 = int64;
            this.float32 = float32;
            this.float64 = float64;
            this.text = text;
        }

        /** Go exactInt32：整值 int32/int64/float32/float64 皆可，越界/非整抛错。 */
        public int exactInt32() {
            double value = switch (tag) {
                case 2 -> int32;
                case 3 -> int64;
                case 4 -> float32;
                case 5 -> float64;
                default -> throw new IllegalStateException("value is not numeric (tag " + tag + ")");
            };
            if (value != Math.floor(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalStateException("value " + value + " is not an exact int32");
            }
            return (int) value;
        }

        public String textOrNull() {
            return tag == 6 || tag == 7 ? text : null;
        }
    }

    /** Values[0]=消息 ID（int），[1]=子命令，[2..]=参数。 */
    public static final class Message {
        public final List<Value> values;

        Message(List<Value> values) {
            this.values = values;
        }

        public int messageId() {
            return values.get(0).exactInt32();
        }
    }

    /** Go parseClientCustomMessage：0x1E/0x27 直达。 */
    public static Message parseDirect(byte[] plain) {
        if (plain.length < 3 || (plain[0] & 0xFF) != 0x1E && (plain[0] & 0xFF) != 0x27) {
            return null;
        }
        List<Value> values = parseValues(plain, 1);
        return values == null || values.isEmpty() || values.get(0).tag != 2 ? null : new Message(values);
    }

    /** Go parseClientActivityCustomMessage：0x0A + 21 字节头（16 会话区 + u32 序列）。 */
    public static Message parseActivityEnvelope(byte[] plain) {
        if ((plain[0] & 0xFF) != 0x0A || plain.length < 24) {
            return null;
        }
        List<Value> values = parseValues(plain, 21);
        return values == null || values.isEmpty() || values.get(0).tag != 2 ? null : new Message(values);
    }

    /** Go parseClientCustomValues：u16 count + 类型化值；尾随字节拒绝。 */
    static List<Value> parseValues(byte[] plain, int offset) {
        if (offset + 2 > plain.length) {
            return null;
        }
        int count = (plain[offset] & 0xFF) | (plain[offset + 1] & 0xFF) << 8;
        offset += 2;
        List<Value> values = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            if (offset >= plain.length) {
                return null;
            }
            int tag = plain[offset++] & 0xFF;
            switch (tag) {
                case 2 -> {
                    long v = readU32(plain, offset);
                    offset += 4;
                    values.add(new Value(2, v, 0, 0, 0, null));
                }
                case 3 -> {
                    long v = readU32(plain, offset) | readU32(plain, offset + 4) << 32;
                    offset += 8;
                    values.add(new Value(3, 0, v, 0, 0, null));
                }
                case 4 -> {
                    float v = Float.intBitsToFloat((int) readU32(plain, offset));
                    offset += 4;
                    values.add(new Value(4, 0, 0, v, 0, null));
                }
                case 5 -> {
                    long bits = readU32(plain, offset) | readU32(plain, offset + 4) << 32;
                    offset += 8;
                    values.add(new Value(5, 0, 0, 0, Double.longBitsToDouble(bits), null));
                }
                case 6 -> {
                    int length = (int) readU32(plain, offset);
                    offset += 4;
                    if (length <= 0 || offset + length > plain.length) {
                        return null;
                    }
                    values.add(new Value(6, 0, 0, 0, 0,
                            new String(plain, offset, length - 1, StandardCharsets.UTF_8)));
                    offset += length;
                }
                case 7 -> {
                    int length = (int) readU32(plain, offset);
                    offset += 4;
                    if (length <= 0 || length % 2 != 0 || offset + length > plain.length) {
                        return null;
                    }
                    values.add(new Value(7, 0, 0, 0, 0,
                            new String(plain, offset, length - 2, StandardCharsets.UTF_16LE)));
                    offset += length;
                }
                case 8 -> {
                    long v = readU32(plain, offset) | readU32(plain, offset + 4) << 32;
                    offset += 8;
                    values.add(new Value(8, 0, v, 0, 0, null));
                }
                default -> {
                    return null;
                }
            }
        }
        if (offset != plain.length) {
            return null; // Go：尾随字节拒绝
        }
        return values;
    }

    private static long readU32(byte[] plain, int offset) {
        if (offset + 4 > plain.length) {
            throw new IllegalStateException("truncated custom value");
        }
        return (plain[offset] & 0xFFL) | (plain[offset + 1] & 0xFFL) << 8
                | (plain[offset + 2] & 0xFFL) << 16 | (plain[offset + 3] & 0xFFL) << 24;
    }

    private CustomC2S() {
    }
}
