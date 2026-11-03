package com.nineyin.tools.mpq;

import java.nio.charset.StandardCharsets;

/**
 * Storm 风格的加密、哈希与名称处理，复刻暴雪 storm.dll / StormLib 的算法：
 * 0x500 项 crypt table、经典 hash table 的四个哈希函数，以及对称流密码。
 * 与 Rust mpq 库 crypt.rs 逐位等价。
 */
final class StormCrypt {

    /** 经典 hash table 索引使用的哈希类型（hash % table_size）。 */
    static final int HASH_TABLE_INDEX = 0x000;
    /** 生成 hash table 条目 name1 的哈希类型。 */
    static final int HASH_NAME_A = 0x100;
    /** 生成 hash table 条目 name2 的哈希类型。 */
    static final int HASH_NAME_B = 0x200;
    /** 生成文件级加密密钥的哈希类型。 */
    static final int HASH_FILE_KEY = 0x300;
    /** 流密码 "key2 mix" 子表偏移。 */
    private static final int HASH_KEY2_MIX = 0x400;

    /** 经典 hash table 的加密密钥：HashString("(hash table)", FILE_KEY)。 */
    static final int KEY_HASH_TABLE = 0xC3AF_3770;
    /** 经典 block table 的加密密钥：HashString("(block table)", FILE_KEY)。 */
    static final int KEY_BLOCK_TABLE = 0xEC83_B3A3;

    /** storm crypt table，类加载时生成。 */
    static final int[] CRYPT_TABLE = buildCryptTable();

    private StormCrypt() {
    }

    private static int[] buildCryptTable() {
        int[] table = new int[0x500];
        int seed = 0x0010_0001;
        for (int index1 = 0; index1 < 0x100; index1++) {
            for (int i = 0; i < 5; i++) {
                int index2 = index1 + i * 0x100;
                seed = (seed * 125 + 3) % 0x2AAAAB;
                int temp1 = (seed & 0xFFFF) << 16;
                seed = (seed * 125 + 3) % 0x2AAAAB;
                int temp2 = seed & 0xFFFF;
                table[index2] = temp1 | temp2;
            }
        }
        return table;
    }

    /** storm 大写映射：a-z → A-Z，/ → \，>= 0x80 原样通过。 */
    static byte upperByte(byte b) {
        if (b >= 'a' && b <= 'z') {
            return (byte) (b - 0x20);
        }
        if (b == '/') {
            return '\\';
        }
        return b;
    }

    /** storm 小写映射：A-Z → a-z，/ → \（HET/BET 名称哈希用）。 */
    static byte lowerByte(byte b) {
        if (b >= 'A' && b <= 'Z') {
            return (byte) (b + 0x20);
        }
        if (b == '/') {
            return '\\';
        }
        return b;
    }

    /** 按经典表方式规范化文件名：ASCII 大写化，/ 转 \。 */
    static byte[] normalizeName(String name) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = upperByte(raw[i]);
        }
        return out;
    }

    /** MPQ 名字相等：规范化（大写、\ 分隔符）后的字节相等。 */
    static boolean sameName(String a, String b) {
        byte[] x = normalizeName(a);
        byte[] y = normalizeName(b);
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            if (x[i] != y[i]) {
                return false;
            }
        }
        return true;
    }

    /** 对已规范化的字节串执行 Storm HashString。 */
    static int hashBytes(byte[] normalized, int hashType) {
        int seed1 = 0x7FED_7FED;
        int seed2 = 0xEEEE_EEEE;
        for (byte b : normalized) {
            int ch = b & 0xFF;
            seed1 = CRYPT_TABLE[hashType + ch] ^ (seed1 + seed2);
            seed2 = ch + seed1 + seed2 + (seed2 << 5) + 3;
        }
        return seed1;
    }

    /** 对原始名称执行 Storm HashString（内部先规范化）。 */
    static int hashString(String name, int hashType) {
        return hashBytes(normalizeName(name), hashType);
    }

    /** 用 storm 流密码就地加/解密 data（小端 u32 为单位；int 环绕即 u32 回绕）。 */
    static void encryptBytes(byte[] data, int key) {
        cryptBytes(data, 0, data.length, key, true);
    }

    /** 就地加密 data 的 [off, off+len) 区间。 */
    static void encryptBytes(byte[] data, int off, int len, int key) {
        cryptBytes(data, off, len, key, true);
    }

    /** 用 storm 流密码就地解密 data。 */
    static void decryptBytes(byte[] data, int key) {
        cryptBytes(data, 0, data.length, key, false);
    }

    /** 就地解密 data 的 [off, off+len) 区间。 */
    static void decryptBytes(byte[] data, int off, int len, int key) {
        cryptBytes(data, off, len, key, false);
    }

    /** 对称流密码：加密与解密是同一运算，区别只在密钥流推进用明文还是密文。 */
    private static void cryptBytes(byte[] data, int off, int len, int key, boolean encrypting) {
        int key1 = key;
        int key2 = 0xEEEE_EEEE;
        int end = off + (len & ~3);
        for (int i = off; i < end; i += 4) {
            int value = (data[i] & 0xFF) | (data[i + 1] & 0xFF) << 8
                    | (data[i + 2] & 0xFF) << 16 | (data[i + 3] & 0xFF) << 24;
            key2 += CRYPT_TABLE[HASH_KEY2_MIX + (key1 & 0xFF)];
            int out = value ^ (key1 + key2);
            key1 = ((~key1 << 21) + 0x1111_1111) | (key1 >>> 11);
            int plain = encrypting ? value : out;
            key2 = plain + key2 + (key2 << 5) + 3;
            data[i] = (byte) out;
            data[i + 1] = (byte) (out >>> 8);
            data[i + 2] = (byte) (out >>> 16);
            data[i + 3] = (byte) (out >>> 24);
        }
    }

    /**
     * 派生文件级加密密钥。
     *
     * @param name     归档内的完整文件名（只取基础名参与哈希）
     * @param fixKey   块带 FILE_KEY_V2 标志
     * @param blockPos 文件块相对归档起始处的偏移
     * @param fileSize 未压缩的文件大小
     */
    static int fileKey(String name, boolean fixKey, long blockPos, int fileSize) {
        String plain = name;
        int cut = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        if (cut >= 0) {
            plain = name.substring(cut + 1);
        }
        int key = hashString(plain, HASH_FILE_KEY);
        if (fixKey) {
            key = (key + (int) blockPos) ^ fileSize;
        }
        return key;
    }
}
