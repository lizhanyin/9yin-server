package com.nineyin.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 测试向量移植自 C++ tests/transport_frame_test.cpp。 */
class FxFrameTest {

    private final FxKey key = FxKey.initial();

    @Test
    void basicRoundTrip() {
        byte[] plain = {0x01, 0x02, 0x03, (byte) 0xEE, 0x04};
        byte[] wire = FxFrame.encode(plain, key);
        assertTrue(wire.length >= 2);
        assertEquals((byte) 0xEE, wire[wire.length - 2]);
        assertEquals((byte) 0xEE, wire[wire.length - 1]);

        FxFrameAccumulator reader = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        int consumed = reader.feed(wire);
        assertEquals(wire.length, consumed);
        assertTrue(reader.hasCompleteFrame());
        assertArrayEquals(plain, reader.takeFrame(key));
    }

    @Test
    void escapeOnly() {
        byte[] plain = {(byte) 0xEE};
        byte[] wire = FxFrame.encode(plain, key);
        FxFrameAccumulator reader = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        assertTrue(reader.feed(wire) > 0);
        assertTrue(reader.hasCompleteFrame());
        assertArrayEquals(plain, reader.takeFrame(key));
    }

    @Test
    void streamingByteByByte() {
        byte[] plain = {0x22, (byte) 0xD5, (byte) 0x80, 0x18, (byte) 0xEE, 9, 10};
        byte[] wire = FxFrame.encode(plain, key);
        FxFrameAccumulator reader = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        boolean done = false;
        for (byte b : wire) {
            byte[] chunk = {b};
            int consumed = reader.feed(chunk);
            if (consumed == 1 && reader.hasCompleteFrame()) {
                done = true;
                break;
            }
        }
        assertTrue(done, "streaming did not complete");
        assertArrayEquals(plain, reader.takeFrame(key));
    }

    @Test
    void multipleFramesInOneChunk() {
        byte[] frame1 = {(byte) 0xEE, 0x01};
        byte[] frame2 = {0x02};
        byte[] wire1 = FxFrame.encode(frame1, key);
        byte[] wire2 = FxFrame.encode(frame2, key);
        byte[] both = new byte[wire1.length + wire2.length];
        System.arraycopy(wire1, 0, both, 0, wire1.length);
        System.arraycopy(wire2, 0, both, wire1.length, wire2.length);

        FxFrameAccumulator reader = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        int consumed = reader.feed(both);
        assertEquals(wire1.length, consumed, "first feed must stop at frame boundary");
        assertTrue(reader.hasCompleteFrame());
        assertArrayEquals(frame1, reader.takeFrame(key));

        int consumed2 = reader.feed(both, consumed, both.length - consumed);
        assertEquals(wire2.length, consumed2);
        assertTrue(reader.hasCompleteFrame());
        assertArrayEquals(frame2, reader.takeFrame(key));
    }

    @Test
    void invalidEscapeThrows() {
        FxFrameAccumulator reader = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        byte[] bad = {0x01, (byte) 0xEE, 0x7F};
        assertThrows(FxInvalidEscapeException.class, () -> reader.feed(bad));
    }

    @Test
    void oversizeThrows() {
        assertThrows(FxFrameTooLargeException.class,
                () -> FxFrame.encode(new byte[16], key, 8));
    }

    @Test
    void decoderHandlesPrefetch() {
        // 编码器产生的线上字节与 C++ 语义一致：转义后 XOR 字节中的 EE 变为 EE 00
        byte[] plain = {(byte) 0xEE};
        byte[] wire = FxFrame.encode(plain, key);
        // 明文 EE XOR key[0]=CC 得 0x22，不含 EE：线上 = 密文体 1 字节 + 终止符 2 字节
        assertEquals(3, wire.length);
        assertFalse(wire[0] == (byte) 0xEE);
        assertArrayEquals(plain, FxFrame.decodeBody(new byte[]{wire[0]}, key));
    }
}
