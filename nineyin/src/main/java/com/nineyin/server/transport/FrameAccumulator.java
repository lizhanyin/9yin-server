package com.nineyin.server.transport;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式帧累积器，对齐 Go internal/transport/frame.go 的 ReadEncoded 状态机。
 *
 * <p>逐块喂入 wire 字节，在 {@code EE EE} 终止符处切帧；每帧返回的是
 * <b>转义还原后的密文 body</b>（未 XOR），由调用方用读密钥解密——
 * 与 Go FrameReader.ReadFrame(key) 的两段式一致。
 */
public final class FrameAccumulator {

    private final int maxFrameSize;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean afterEscape;

    public FrameAccumulator() {
        this(FrameCodec.DEFAULT_MAX_FRAME_SIZE);
    }

    public FrameAccumulator(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    /** 喂入一段 wire 字节，返回其中完整帧的密文 body 列表（可能为空）。 */
    public List<byte[]> feed(byte[] data, int offset, int length) {
        List<byte[]> frames = new ArrayList<>(1);
        for (int i = offset; i < offset + length; i++) {
            int b = data[i] & 0xFF;
            if (afterEscape) {
                afterEscape = false;
                if (b == FrameCodec.ESCAPED_ESCAPE) {
                    buffer.write(0xEE);
                    checkLimit();
                } else if (b == FrameCodec.ESCAPED_TERMINATOR) {
                    frames.add(buffer.toByteArray());
                    buffer.reset();
                } else {
                    throw new FrameException(String.format("invalid escape: EE %02X", b));
                }
            } else if (b == FrameCodec.ESCAPE) {
                afterEscape = true;
            } else {
                buffer.write(b);
                checkLimit();
            }
        }
        return frames;
    }

    private void checkLimit() {
        if (buffer.size() > maxFrameSize) {
            throw new FrameException("frame length " + buffer.size() + " exceeds limit " + maxFrameSize);
        }
    }

    public boolean hasPendingData() {
        return buffer.size() > 0 || afterEscape;
    }
}
