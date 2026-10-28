package com.nineyin.transport;

import java.util.Arrays;

/**
 * 流式帧累积器：逐段喂入线上字节，按转义规则还原帧体，遇到 EE EE 判定帧完整。
 * feed 返回本次消费的字节数——一段数据里若含多帧，调用方可据此继续喂下一帧。
 */
public final class FxFrameAccumulator {

    private final int limit;
    private byte[] buffer = new byte[256];
    private int length;
    private boolean complete;
    private boolean inEscape;

    public FxFrameAccumulator(int limit) {
        if (limit <= 0) {
            throw new FxFrameException("invalid frame size limit");
        }
        this.limit = limit;
    }

    /**
     * 喂入一段线上字节，返回消费的字节数。
     * 帧完成时返回值可能小于 len，剩余字节属于下一帧。
     */
    public int feed(byte[] data, int offset, int len) {
        if (complete) {
            return 0;
        }
        int i = offset;
        final int end = offset + len;
        for (; i < end; i++) {
            int b = data[i] & 0xFF;
            if (inEscape) {
                inEscape = false;
                if (b == 0x00) {
                    append((byte) 0xEE);
                } else if (b == 0xEE) {
                    complete = true;
                    return i - offset + 1;
                } else {
                    throw new FxInvalidEscapeException(b);
                }
            } else if (b == 0xEE) {
                inEscape = true;
            } else {
                append((byte) b);
            }
            if (length > limit) {
                throw new FxFrameTooLargeException(length, limit);
            }
        }
        return len;
    }

    public int feed(byte[] data) {
        return feed(data, 0, data.length);
    }

    /** 帧完成后取回明文（用读方向密钥解密），并复位累积器。 */
    public byte[] takeFrame(FxKey key) {
        if (!complete) {
            throw new FxFrameException("no complete frame available");
        }
        byte[] plain = key.apply(buffer, 0, length);
        reset();
        return plain;
    }

    public boolean hasCompleteFrame() {
        return complete;
    }

    public int buffered() {
        return length;
    }

    public void reset() {
        length = 0;
        complete = false;
        inEscape = false;
    }

    private void append(byte b) {
        if (length == buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length << 1, 64));
        }
        buffer[length++] = b;
    }
}
