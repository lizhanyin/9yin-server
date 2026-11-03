package com.nineyin.tools.mpq;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础算法对拍：storm 加密/哈希常量、Jenkins 参考向量、PKWARE 参考向量。
 * 向量均取自 Rust mpq 库的单元测试。
 */
class MpqPrimitivesTest {

    @Test
    void stormHashMatchesStormlibConstants() {
        // 命中 StormLib 文档常量即证明 crypt table 与哈希函数逐位精确。
        assertEquals(0xC3AF_3770, StormCrypt.hashString("(hash table)", StormCrypt.HASH_FILE_KEY));
        assertEquals(0xEC83_B3A3, StormCrypt.hashString("(block table)", StormCrypt.HASH_FILE_KEY));
    }

    @Test
    void stormEncryptDecryptRoundtrip() {
        byte[] original = new byte[100];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 7 + 3);
        }
        byte[] buf = original.clone();
        StormCrypt.encryptBytes(buf, 0x1234_5678);
        assertTrue(!Arrays.equals(buf, original));
        StormCrypt.decryptBytes(buf, 0x1234_5678);
        assertArrayEquals(original, buf);
    }

    @Test
    void stormNameNormalization() {
        assertArrayEquals(new byte[]{'A', 'B', 'C', '\\', 'D', 'E', 'F', '.', 'T', 'X', 'T'},
                StormCrypt.normalizeName("abc/def.txt"));
        assertEquals(StormCrypt.hashString("ABC\\DEF.txt", 1), StormCrypt.hashString("abc/DEF.txt", 1));
    }

    @Test
    void jenkinsReferenceValues() {
        // 与 StormLib HashStringJenkins 一致（lower 表、pc=2、pb=1）。
        assertEquals(0x3bc43bb0b2f3866aL, JenkinsHash.fileNameHash("(listfile)"));
        assertEquals(0xdeadbef1deadbef2L, JenkinsHash.fileNameHash(""));
        assertEquals(0xcbc05d12b47025a5L, JenkinsHash.fileNameHash("abc"));
        assertEquals(0x5252117e4beda79dL, JenkinsHash.fileNameHash("war3map.j"));
        assertEquals(0x97b18367a6e9e2a8L, JenkinsHash.fileNameHash("exactly12chr"));
        assertEquals(0x4e7480ccc6359c94L, JenkinsHash.fileNameHash("exactly13chrs"));
    }

    @Test
    void pkwareExplodesReferenceVectors() throws Exception {
        String[][] vectors = {
                {"mpq/pk_text.bin", "mpq/pk_text.orig"},
                {"mpq/pk_rand.bin", "mpq/pk_rand.orig"},
                {"mpq/pk_runs.bin", "mpq/pk_runs.orig"},
        };
        for (String[] v : vectors) {
            byte[] stream = resource(v[0]);
            byte[] plain = resource(v[1]);
            ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length);
            Pkware.explode(stream, out, plain.length);
            assertArrayEquals(plain, out.toByteArray(), v[0]);
        }
    }

    @Test
    void pkwareRejectsBadStreams() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(MpqException.class, () -> Pkware.explode(new byte[0], out, 64));
        assertThrows(MpqException.class, () -> Pkware.explode(new byte[]{0, 9, 0, 0, 0}, out, 64));
        assertThrows(MpqException.class, () -> Pkware.explode(new byte[]{7, 4, 0, 0, 0}, out, 64));
    }

    @Test
    void zlibRoundtripViaMask() {
        byte[] plain = ("hello hello hello compressed data 1234567890").repeat(4)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] z = MpqCompression.zlibCompress(plain);
        byte[] block = new byte[z.length + 1];
        block[0] = MpqCompression.M_ZLIB;
        System.arraycopy(z, 0, block, 1, z.length);
        assertArrayEquals(plain, MpqCompression.decompressMasked(block, plain.length));

        // 缺方法字节回退：整个块是普通 zlib 流。
        assertArrayEquals(plain, MpqCompression.decompressMasked(z, plain.length));
    }

    static byte[] resource(String name) throws Exception {
        try (InputStream in = MpqPrimitivesTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("找不到测试资源 " + name);
            }
            return in.readAllBytes();
        }
    }
}
