package com.nineyin.tools.mpq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.nineyin.tools.mpq.MpqPrimitivesTest.resource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Rust mpq 库生成的夹具做跨语言对拍：
 * 读取 gen_v1..v4 / enc_v1 并校验每个文件的 sha256。
 */
class MpqFixtureTest {

    /** 解析 manifest.txt（名字<TAB>sha256<TAB>大小）。 */
    private static Map<String, String> manifestSha() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (String line : new String(resource("mpq/manifest.txt"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\t");
            out.put(parts[0], parts[1]);
        }
        return out;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gen_v1", "gen_v2", "gen_v3", "gen_v4"})
    void readsRustGeneratedArchives(String archiveName) throws Exception {
        byte[] bytes = resource("mpq/" + archiveName + ".mpq");
        Map<String, String> manifest = manifestSha();
        try (MpqArchive archive = MpqArchive.open(bytes)) {
            assertEquals(archiveName.endsWith("1") ? 1 : Integer.parseInt(archiveName.substring(5)),
                    archive.version());
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                assertTrue(archive.hasFile(e.getKey()), e.getKey() + " 应存在");
                assertEquals(e.getValue(), sha256(archive.readFile(e.getKey())), e.getKey());
            }
            // 空文件读取为 0 字节。
            assertEquals(0, archive.readFile("empty.dat").length);
        }
    }

    @Test
    void readsHandBuiltEncryptedArchive() throws Exception {
        // enc_v1 由 Rust 手工构造：名字密钥加密 / key-v2（压缩与不可压缩）、
        // sector CRC、PKWARE implode、明文单块。
        byte[] bytes = resource("mpq/enc_v1.mpq");
        try (MpqArchive archive = MpqArchive.open(bytes)) {
            assertEquals(1, archive.version());

            assertEquals("plain stored single-unit file\n",
                    new String(archive.readFile("plain.txt"), StandardCharsets.UTF_8));

            // secret.txt：名字密钥 + zlib 单块加密。
            byte[] secret = archive.readFile("secret.txt");
            assertEquals(3000, secret.length);

            // fixed.bin：key-v2 加密 + 压缩 sector（偏移表 key-1、sector key+i、dword 填充）。
            byte[] fixed = archive.readFile("fixed.bin");
            assertEquals(13_000, fixed.length);

            // rand.bin：key-v2 加密 + 不可压缩固定偏移 sector。
            byte[] rand = archive.readFile("rand.bin");
            assertEquals(9_500, rand.length);

            // crc.bin：带 sector 校验和，不应产生 CRC 告警。
            byte[] crc = archive.readFile("crc.bin");
            assertEquals(12_000, crc.length);
            assertTrue(archive.readWarnings().isEmpty(), () -> String.valueOf(archive.readWarnings()));

            // pk.txt：旧式 PKWARE implode 单块。
            assertArrayEquals(resource("mpq/pk_text.orig"), archive.readFile("pk.txt"));

            // 与 Rust 端相同的确定性内容生成器对拍（pattern/noise 算法一致）。
            assertArrayEquals(patternBytes(3000, 0x0BAD_F00D), secret);
            assertArrayEquals(patternBytes(13_000, 0x0F11_0F11), fixed);
            assertArrayEquals(patternBytes(12_000, 0xCCCC_0001), crc);
            assertArrayEquals(noiseBytes(9_500, 0x5EED_5EED), rand);
        }
    }

    @Test
    void readsMiniV1Fixture() throws Exception {
        try (MpqArchive archive = MpqArchive.open(resource("mpq/mini_v1.mpq"))) {
            assertTrue(archive.fileNames().contains("war3map.j"));
            assertEquals("// dummy map script\n", new String(archive.readFile("war3map.j"),
                    StandardCharsets.UTF_8));
        }
    }

    /** 与 Rust 夹具生成器一致的半随机可压缩内容（xorshift 游程）。 */
    static byte[] patternBytes(int len, int seed) {
        int x = seed | 1;
        byte[] out = new byte[len];
        int pos = 0;
        while (pos < len) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            int run = 1 + (x & 0x3F);
            byte b = (byte) (x >> 8);
            for (int i = 0; i < run && pos < len; i++) {
                out[pos++] = b;
            }
        }
        return out;
    }

    /** 与 Rust 夹具生成器一致的不可压缩内容。 */
    static byte[] noiseBytes(int len, int seed) {
        int x = seed | 1;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            out[i] = (byte) (x >> 16);
        }
        return out;
    }
}
