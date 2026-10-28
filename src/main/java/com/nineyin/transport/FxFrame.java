package com.nineyin.transport;

import java.io.ByteArrayOutputStream;

/**
 * FxNet2 帧编码：明文先与 4 字节循环 key 异或，再对密文中的 0xEE 做转义（EE -> EE 00），
 * 最后追加帧终止符 EE EE。线上没有长度字段，靠终止符分帧。
 */
public final class FxFrame {

    public static final int DEFAULT_MAX_FRAME_SIZE = 1 << 20; // 1 MiB

    private FxFrame() {
    }

    public static byte[] encode(byte[] plain, FxKey key, int limit) {
        if (limit <= 0) {
            throw new FxFrameException("invalid frame size limit");
        }
        if (plain.length > limit) {
            throw new FxFrameTooLargeException(plain.length, limit);
        }
        byte[] xored = key.apply(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream(xored.length + 2 + (xored.length >> 3) + 8);
        for (byte b : xored) {
            out.write(b);
            if (b == (byte) 0xEE) {
                out.write(0x00);
            }
        }
        out.write(0xEE);
        out.write(0xEE);
        return out.toByteArray();
    }

    public static byte[] encode(byte[] plain, FxKey key) {
        return encode(plain, key, DEFAULT_MAX_FRAME_SIZE);
    }

    /** 解码一个完整帧体（终止符已被剥离、转义已还原），仅剩 XOR 还原。 */
    public static byte[] decodeBody(byte[] encodedBody, FxKey key) {
        return key.apply(encodedBody);
    }
}
