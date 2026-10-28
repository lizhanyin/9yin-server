package com.nineyin.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 测试向量移植自 C++ tests/transport_key_test.cpp（源自 Go key_test.go）。 */
class FxKeyTest {

    @Test
    void initialValueMatchesLittleEndianWireKey() {
        FxKey key = FxKey.initial();
        assertEquals(0xFA6D3BCC, key.toInt());
    }

    @Test
    void applyXorVector() {
        FxKey key = FxKey.initial();
        byte[] input = {0x00, 0x00, 0x00, 0x00, 0x01};
        byte[] got = key.apply(input);
        assertEquals(5, got.length);
        assertEquals((byte) 0xCC, got[0]);
        assertEquals((byte) 0x3B, got[1]);
        assertEquals((byte) 0x6D, got[2]);
        assertEquals((byte) 0xFA, got[3]);
        assertEquals((byte) 0xCD, got[4]);
    }

    @Test
    void xorIsSymmetric() {
        FxKey key = FxKey.initial();
        byte[] input = {0x00, 0x00, 0x00, 0x00, 0x01};
        assertArrayEquals(input, key.apply(key.apply(input)));
    }
}
