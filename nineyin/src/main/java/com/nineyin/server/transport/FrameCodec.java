package com.nineyin.server.transport;

import java.util.List;

/**
 * FxNet2 帧编解码，对齐 Go internal/transport/frame.go。
 *
 * <p>帧没有长度包头：wire = escape(XOR(plain)) + 终止符 {@code EE EE}；
 * 密文中的 {@code 0xEE} 转义为 {@code EE 00}。解码为逐字节状态机，
 * 遇到非法转义（{@code EE xx}，xx 不是 00/EE）即抛 {@link FrameException}。
 */
public final class FrameCodec {

    /** Go main.go：transport.DefaultConnectionConfig() 后 MaxFrameSize 覆写为 64KB。 */
    public static final int DEFAULT_MAX_FRAME_SIZE = 64 << 10;

    public static final int ESCAPE = 0xEE;
    public static final int ESCAPED_TERMINATOR = 0xEE;
    public static final int ESCAPED_ESCAPE = 0x00;

    private FrameCodec() {
    }

    /** frame.go EncodeFrame：先 XOR，再转义，末尾追加 EE EE。 */
    public static byte[] encode(byte[] plain, XorKey key) {
        return encode(plain, key, DEFAULT_MAX_FRAME_SIZE);
    }

    public static byte[] encode(byte[] plain, XorKey key, int maxFrameSize) {
        if (plain.length > maxFrameSize) {
            throw new FrameException("frame length " + plain.length + " exceeds limit " + maxFrameSize);
        }
        byte[] body = key.applyCopy(plain);
        int escapeCount = 0;
        for (byte b : body) {
            if ((b & 0xFF) == ESCAPE) {
                escapeCount++;
            }
        }
        byte[] wire = new byte[body.length + escapeCount + 2];
        int out = 0;
        for (byte b : body) {
            wire[out++] = b;
            if ((b & 0xFF) == ESCAPE) {
                wire[out++] = ESCAPED_ESCAPE;
            }
        }
        wire[out++] = (byte) ESCAPE;
        wire[out] = (byte) ESCAPED_TERMINATOR;
        return wire;
    }

    /** frame.go ReadFrame：状态机还原转义得到密文 body 后 XOR（与 encode 互逆）。 */
    public static byte[] decode(byte[] wire, XorKey key) {
        FrameAccumulator accumulator = new FrameAccumulator(DEFAULT_MAX_FRAME_SIZE);
        List<byte[]> frames = accumulator.feed(wire, 0, wire.length);
        if (frames.isEmpty()) {
            throw new FrameException("frame terminator not found");
        }
        if (frames.size() > 1 || accumulator.hasPendingData()) {
            throw new FrameException("trailing data after frame terminator");
        }
        byte[] frame = frames.get(0);
        key.apply(frame, 0, frame.length);
        return frame;
    }
}
