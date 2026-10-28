package com.nineyin.transport;

/**
 * FxNet2 传输密钥：4 字节循环 XOR，字节布局对应线上观察到的小端 uint32。
 * XOR 对称，加密与解密是同一操作。
 */
public final class FxKey {

    public static final int INITIAL_VALUE = 0xFA6D3BCC;

    private final byte b0;
    private final byte b1;
    private final byte b2;
    private final byte b3;

    public FxKey(int value) {
        b0 = (byte) (value & 0xFF);
        b1 = (byte) ((value >> 8) & 0xFF);
        b2 = (byte) ((value >> 16) & 0xFF);
        b3 = (byte) ((value >> 24) & 0xFF);
    }

    public static FxKey initial() {
        return new FxKey(INITIAL_VALUE);
    }

    public int toInt() {
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    /** 对 src 区间逐字节异或，返回新数组。 */
    public byte[] apply(byte[] src, int offset, int length) {
        byte[] dst = new byte[length];
        for (int i = 0; i < length; i++) {
            dst[i] = (byte) (src[offset + i] ^ keyByteAt(i));
        }
        return dst;
    }

    public byte[] apply(byte[] src) {
        return apply(src, 0, src.length);
    }

    private byte keyByteAt(int i) {
        return switch (i & 3) {
            case 0 -> b0;
            case 1 -> b1;
            case 2 -> b2;
            default -> b3;
        };
    }
}
