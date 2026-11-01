package com.nineyin.transport;

/**
 * 4 字节循环 XOR 密钥，对齐 Go internal/transport/key.go。
 *
 * <p>密钥字节为该 u32 值的小端序列：{@code 0xFA6D3BCC -> {0xCC, 0x3B, 0x6D, 0xFA}}。
 * {@link #apply(byte[], int, int)} 原地对每帧 body 从偏移 0 重新对齐异或（无跨帧状态），
 * 加解密同一操作。
 */
public final class XorKey {

    /** Go transport.InitialKeyValue。 */
    public static final int INITIAL_VALUE = 0xFA6D3BCC;

    private final int value;

    public static XorKey initial() {
        return new XorKey(INITIAL_VALUE);
    }

    public XorKey(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** key.go keyBytes：小端布局。 */
    private int keyByteAt(int index) {
        return (value >>> (8 * (index & 3))) & 0xFF;
    }

    /** key.go Apply：dst[i] = src[i] ^ keyBytes[i & 3]，帧内索引从 0 对齐，原地翻转。 */
    public void apply(byte[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            int b = buffer[offset + i] & 0xFF;
            buffer[offset + i] = (byte) (b ^ keyByteAt(i));
        }
    }

    public byte[] applyCopy(byte[] src) {
        byte[] out = new byte[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        apply(out, 0, out.length);
        return out;
    }
}
