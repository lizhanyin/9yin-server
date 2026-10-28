package com.nineyin.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 0x0A 自定义消息解析：21 字节头 + u16 参数个数 + 类型化值。
 * 类型码：2=i32 3=i64 4=f32 6/7=string 8=object(8 字节)。
 */
public final class CustomMsg {

    public static final int TYPE_I32 = 2;
    public static final int TYPE_I64 = 3;
    public static final int TYPE_F32 = 4;
    public static final int TYPE_STRING = 6;
    public static final int TYPE_OBJECT = 8;

    public static final class Val {

        public final int type;
        public final long i64;      // i32 也存这里（低 32 位有符号）
        public final float f32;
        public final String str;

        Val(int type, long i64, float f32, String str) {
            this.type = type;
            this.i64 = i64;
            this.f32 = f32;
            this.str = str;
        }

        public int asInt() {
            return (int) i64;
        }

        public long asLong() {
            return i64;
        }

        public float asFloat() {
            return f32;
        }

        public String asString() {
            return str;
        }
    }

    public final List<Val> vals = new ArrayList<>();

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

    /** 解析失败返回 ok=false 的空消息。 */
    public static CustomMsg parse(byte[] plain) {
        CustomMsg out = new CustomMsg();
        final int header = 21;
        if (plain.length < header + 3 || plain[0] != 0x0A) {
            return out;
        }
        int off = header;
        int count = (plain[off] & 0xFF) | ((plain[off + 1] & 0xFF) << 8);
        off += 2;
        if (count == 0 || count > 64) {
            return out;
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
                    out.vals.add(new Val(type, readI32(plain, off), 0, null));
                    off += 4;
                }
                case TYPE_I64 -> {
                    if (off + 8 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, readI64(plain, off), 0, null));
                    off += 8;
                }
                case TYPE_F32 -> {
                    if (off + 4 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, 0,
                            Float.intBitsToFloat((int) readU32(plain, off)), null));
                    off += 4;
                }
                case TYPE_STRING, 7 -> {
                    if (off + 4 > plain.length) {
                        return new CustomMsg();
                    }
                    long n = readU32(plain, off);
                    off += 4;
                    if (n > 4096 || off + n > plain.length) {
                        return new CustomMsg();
                    }
                    int len = (int) n;
                    if (len > 0 && plain[off + len - 1] == 0) {
                        len--;
                    }
                    out.vals.add(new Val(TYPE_STRING, 0, 0,
                            new String(plain, off, len, java.nio.charset.StandardCharsets.UTF_8)));
                    off += (int) n;
                }
                case TYPE_OBJECT -> {
                    if (off + 8 > plain.length) {
                        return new CustomMsg();
                    }
                    out.vals.add(new Val(type, readU32(plain, off), 0, null));
                    off += 8;
                }
                default -> {
                    return new CustomMsg();
                }
            }
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
}
