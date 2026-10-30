package com.nineyin.game;

import com.nineyin.protocol.FxText;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义消息（TVarList）解析，对齐 Go parseClientCustomValues：
 * 类型码 2=i32 3=i64 4=f32 5=f64 6=string(GBK 窄串) 7=widestring(UTF-16LE) 8=object(8 字节)。
 * 两种载体：0x0A 信封（21 字节头 + u16 个数 + 值）与 0x1E/0x27 直连（op + u16 个数 + 值）。
 */
public final class CustomMsg {

    public static final int TYPE_I32 = 2;
    public static final int TYPE_I64 = 3;
    public static final int TYPE_F32 = 4;
    public static final int TYPE_F64 = 5;
    public static final int TYPE_STRING = 6;
    public static final int TYPE_WIDE_STRING = 7;
    public static final int TYPE_OBJECT = 8;

    public static final class Val {

        public final int type;
        public final long i64;      // i32 也存这里（低 32 位有符号）
        public final float f32;
        public final double f64;
        public final String str;
        public final int objectId;  // type 8：低字 objectID
        public final int ownerId;   // type 8：高字 ownerID

        Val(int type, long i64, float f32, double f64, String str, int objectId, int ownerId) {
            this.type = type;
            this.i64 = i64;
            this.f32 = f32;
            this.f64 = f64;
            this.str = str;
            this.objectId = objectId;
            this.ownerId = ownerId;
        }

        public int asInt() {
            return (int) i64;
        }

        public long asLong() {
            return i64;
        }

        public float asFloat() {
            double v = asDouble();
            return (float) v;
        }

        public double asDouble() {
            return switch (type) {
                case TYPE_F32 -> f32;
                case TYPE_F64 -> f64;
                default -> i64;   // i32/i64 以整数值参与浮点换算
            };
        }

        public String asString() {
            return str;
        }

        /** 枚举值容错取整：现代客户端部分表单把枚举值发成 double（如 faculty 1006/23）。 */
        public boolean asExactInt() {
            return switch (type) {
                case TYPE_I32, TYPE_I64 -> true;
                case TYPE_F32, TYPE_F64 -> {
                    double v = asDouble();
                    yield !Double.isNaN(v) && !Double.isInfinite(v)
                            && Math.rint(v) == v && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
                }
                default -> false;
            };
        }

        public int intOrZero() {
            return asExactInt() ? (int) asDouble() : 0;
        }

        @Override
        public String toString() {
            return switch (type) {
                case TYPE_I32 -> "int:" + asInt();
                case TYPE_I64 -> "int64:" + i64;
                case TYPE_F32 -> "float:" + f32;
                case TYPE_F64 -> "double:" + f64;
                case TYPE_STRING -> "string:" + str;
                case TYPE_WIDE_STRING -> "widestr:" + str;
                case TYPE_OBJECT -> "object:" + objectId + "-" + ownerId;
                default -> "type" + type;
            };
        }
    }

    public final List<Val> vals = new ArrayList<>();

    /** 外层载体 opcode（0x0A / 0x1E / 0x27），便于日志。 */
    public int opcode;

    public boolean ok() {
        return !vals.isEmpty();
    }

    public int msgId() {
        return !vals.isEmpty() && vals.get(0).type == TYPE_I32 ? vals.get(0).asInt() : -1;
    }

    public int size() {
        return vals.size();
    }

    public Val val(int i) {
        return i < vals.size() ? vals.get(i) : null;
    }

    /** 0x0A 信封：21 字节头 + u16 个数 + 值。非完整类型表时返回空消息（ok=false）。 */
    public static CustomMsg parse(byte[] plain) {
        final int header = 21;
        if (plain.length < header + 3 || plain[0] != 0x0A) {
            return new CustomMsg();
        }
        return parseValues(plain[0] & 0xFF, plain, header);
    }

    /** 0x1E/0x27 直连：op + u16 个数 + 值（3 字节头）。 */
    public static CustomMsg parseDirect(byte[] plain) {
        if (plain.length < 3 || (plain[0] != 0x1E && plain[0] != 0x27)) {
            return new CustomMsg();
        }
        return parseValues(plain[0] & 0xFF, plain, 1);
    }

    private static CustomMsg parseValues(int opcode, byte[] plain, int off) {
        CustomMsg out = new CustomMsg();
        out.opcode = opcode;
        int count = (plain[off] & 0xFF) | ((plain[off + 1] & 0xFF) << 8);
        off += 2;
        if (count == 0 || count > 1024) {
            return new CustomMsg();
        }
        for (int i = 0; i < count; i++) {
            if (off >= plain.length) {
                return new CustomMsg();
            }
            int type = plain[off++] & 0xFF;
            switch (type) {
                case TYPE_I32 -> {
                    if (off + 4 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, readI32(plain, off), 0, 0, null, 0, 0));
                    off += 4;
                }
                case TYPE_I64 -> {
                    if (off + 8 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, readI64(plain, off), 0, 0, null, 0, 0));
                    off += 8;
                }
                case TYPE_F32 -> {
                    if (off + 4 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, 0,
                            Float.intBitsToFloat((int) readU32(plain, off)), 0, null, 0, 0));
                    off += 4;
                }
                case TYPE_F64 -> {
                    if (off + 8 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, 0, 0, Double.longBitsToDouble(readI64(plain, off)),
                            null, 0, 0));
                    off += 8;
                }
                case TYPE_STRING, TYPE_WIDE_STRING -> {
                    if (off + 4 > plain.length) {
                        return new CustomMsg();
                    }
                    long n = readU32(plain, off);
                    off += 4;
                    if (n < 1 || n > 65536 || off + n > plain.length) {
                        return new CustomMsg();
                    }
                    int len = (int) n;
                    if (plain[off + len - 1] != 0) {
                        return new CustomMsg();
                    }
                    String text;
                    if (type == TYPE_WIDE_STRING) {
                        if (len < 2 || len % 2 != 0 || plain[off + len - 2] != 0) {
                            return new CustomMsg();
                        }
                        StringBuilder sb = new StringBuilder(len / 2);
                        for (int u = 0; u < len - 2; u += 2) {
                            sb.append((char) ((plain[off + u] & 0xFF)
                                    | ((plain[off + u + 1] & 0xFF) << 8)));
                        }
                        text = sb.toString();
                    } else {
                        text = FxText.narrow(plain, off, len - 1);
                    }
                    out.vals.add(new Val(type, 0, 0, 0, text, 0, 0));
                    off += len;
                }
                case TYPE_OBJECT -> {
                    if (off + 8 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, readU32(plain, off), 0, 0, null,
                            (int) readU32(plain, off), (int) readU32(plain, off + 4)));
                    off += 8;
                }
                default -> {
                    return new CustomMsg();
                }
            }
        }
        if (off != plain.length) {
            return new CustomMsg();
        }
        return out;
    }

    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static int readI32(byte[] b, int off) {
        return (int) readU32(b, off);
    }

    private static long readI64(byte[] b, int off) {
        return readU32(b, off) | (readU32(b, off + 4) << 32);
    }

    private CustomMsg() {
    }

    /** 直接按值构造（测试与校验层用）：msgId 占首位 i32，args 依次追加。 */
    public static CustomMsg of(int msgId, Val... args) {
        CustomMsg m = new CustomMsg();
        m.vals.add(new Val(TYPE_I32, msgId, 0, 0, null, 0, 0));
        for (Val a : args) {
            m.vals.add(a);
        }
        return m;
    }
}
