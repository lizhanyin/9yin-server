package com.nineyin.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

/** 对齐 Go internal/transport/frame_test.go：抓包 round-trip + 转义/多帧/流式/错误路径。 */
class FrameCodecTest {

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = FrameCodecTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void capturedFirstFlightRoundTrip() throws Exception {
        // frame_test.go:12-37：真实抓包解出 121 字节明文（首字节 0x02），
        // 且重编码与原 wire 逐字节一致。
        byte[] wire = golden("connect_first_flight.bin");
        byte[] plain = FrameCodec.decode(wire, XorKey.initial());
        byte[] expectedPlain = golden("login_first_flight_plain.bin");
        assertEquals(121, plain.length);
        assertEquals(0x02, plain[0] & 0xFF);
        assertArrayEquals(expectedPlain, plain);
        assertArrayEquals(wire, FrameCodec.encode(plain, XorKey.initial()));
    }

    @Test
    void escapeOnlyFrame() {
        // plain {0x22} XOR 首字节 0xCC -> 0xEE，转义为 EE 00，再接终止符 EE EE。
        byte[] wire = FrameCodec.encode(new byte[]{0x22}, XorKey.initial());
        assertArrayEquals(new byte[]{(byte) 0xEE, 0x00, (byte) 0xEE, (byte) 0xEE}, wire);
        assertArrayEquals(new byte[]{0x22}, FrameCodec.decode(wire, XorKey.initial()));
    }

    @Test
    void emptyFrame() {
        byte[] wire = FrameCodec.encode(new byte[0], XorKey.initial());
        assertArrayEquals(new byte[]{(byte) 0xEE, (byte) 0xEE}, wire);
        assertEquals(0, FrameCodec.decode(wire, XorKey.initial()).length);
    }

    @Test
    void streamingByteByByte() throws Exception {
        byte[] wire = golden("connect_first_flight.bin");
        FrameAccumulator accumulator = new FrameAccumulator();
        List<byte[]> frames = List.of();
        for (byte b : wire) {
            frames = accumulator.feed(new byte[]{b}, 0, 1);
        }
        assertEquals(1, frames.size());
        XorKey key = XorKey.initial();
        key.apply(frames.get(0), 0, frames.get(0).length);
        assertArrayEquals(golden("login_first_flight_plain.bin"), frames.get(0));
    }

    @Test
    void multipleFramesInOneChunk() {
        byte[] first = FrameCodec.encode(new byte[]{0x22}, XorKey.initial());
        byte[] second = FrameCodec.encode(new byte[0], XorKey.initial());
        byte[] merged = new byte[first.length + second.length];
        System.arraycopy(first, 0, merged, 0, first.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        FrameAccumulator accumulator = new FrameAccumulator();
        List<byte[]> frames = accumulator.feed(merged, 0, merged.length);
        assertEquals(2, frames.size());
        XorKey key = XorKey.initial();
        for (byte[] frame : frames) {
            key.apply(frame, 0, frame.length);
        }
        assertArrayEquals(new byte[]{0x22}, frames.get(0));
        assertEquals(0, frames.get(1).length);
        assertTrue(!accumulator.hasPendingData());
    }

    @Test
    void invalidEscapeRejected() {
        FrameAccumulator accumulator = new FrameAccumulator();
        FrameException ex = assertThrows(FrameException.class,
                () -> accumulator.feed(new byte[]{(byte) 0xEE, 0x01}, 0, 2));
        assertTrue(ex.getMessage().contains("EE 01"));
    }

    @Test
    void overlongFrameRejected() {
        byte[] plain = new byte[FrameCodec.DEFAULT_MAX_FRAME_SIZE + 1];
        assertThrows(FrameException.class, () -> FrameCodec.encode(plain, XorKey.initial()));
        // Go 语义 len(body) > limit 才报错：恰等于 limit 的帧合法，超一字节在累积时拒绝。
        FrameAccumulator exact = new FrameAccumulator(FrameCodec.DEFAULT_MAX_FRAME_SIZE);
        byte[] wire = FrameCodec.encode(new byte[FrameCodec.DEFAULT_MAX_FRAME_SIZE], XorKey.initial());
        assertEquals(1, exact.feed(wire, 0, wire.length).size());
        byte[] over = FrameCodec.encode(plain, XorKey.initial(), FrameCodec.DEFAULT_MAX_FRAME_SIZE + 16);
        FrameAccumulator accumulator = new FrameAccumulator(FrameCodec.DEFAULT_MAX_FRAME_SIZE);
        assertThrows(FrameException.class, () -> accumulator.feed(over, 0, over.length));
    }
}
