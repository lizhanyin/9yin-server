package com.nineyin.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nineyin.server.transport.XorKey;
import org.junit.jupiter.api.Test;

/** 对齐 Go internal/transport/key_test.go 的测试向量。 */
class XorKeyTest {

    @Test
    void initialKeyBytesAreLittleEndian() {
        // key_test.go：Apply({0,0,0,0,1}) = CC 3B 6D FA CD
        XorKey key = XorKey.initial();
        assertEquals(0xFA6D3BCC, key.value());
        byte[] plain = {0, 0, 0, 0, 1};
        byte[] out = key.applyCopy(plain);
        assertArrayEquals(new byte[]{(byte) 0xCC, 0x3B, 0x6D, (byte) 0xFA, (byte) 0xCD}, out);
    }

    @Test
    void applyIsInPlaceAndSymmetric() {
        XorKey key = new XorKey(0x12345678);
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        byte[] original = data.clone();
        key.apply(data, 0, data.length);
        assertArrayEquals(key.applyCopy(original), data);
        key.apply(data, 0, data.length);
        assertArrayEquals(original, data);
    }
}
