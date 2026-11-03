package com.nineyin.tools.mpq;

import java.nio.charset.StandardCharsets;

/**
 * Jenkins lookup3 hashlittle2 —— MPQ v3+ 归档 HET/BET 表的 64 位名称哈希。
 * 移植自 Bob Jenkins 公有领域 lookup3.c，与 Rust mpq 库 jenkins.rs 等价。
 */
final class JenkinsHash {

    private JenkinsHash() {
    }

    /** HET/BET 表使用的 64 位文件名哈希：小写归一化后以 pc=2、pb=1 做哈希。 */
    static long fileNameHash(String name) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        byte[] normalized = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            normalized[i] = StormCrypt.lowerByte(raw[i]);
        }
        long[] cb = hashlittle2(normalized, 2, 1);
        return (cb[1] << 32) | (cb[0] & 0xFFFF_FFFFL);
    }

    /** hashlittle2(key, len, pc, pb)；返回 {c, b}（按无符号 u32 语义存放）。 */
    private static long[] hashlittle2(byte[] key, int pc, int pb) {
        int a;
        int b;
        int c;

        // a = b = c = 0xdeadbeef + 长度 + pc; c += pb
        int init = 0xdead_beef + key.length + pc;
        a = init;
        b = init;
        c = init + pb;

        int off = 0;
        int remaining = key.length;
        while (remaining > 12) {
            a += leU32(key, off);
            b += leU32(key, off + 4);
            c += leU32(key, off + 8);
            int[] abc = {a, b, c};
            mix(abc);
            a = abc[0];
            b = abc[1];
            c = abc[2];
            off += 12;
            remaining -= 12;
        }

        // 最后一个（可能不完整的）块，各情况依次向下执行。
        int n = remaining;
        if (n == 0) {
            return new long[]{c & 0xFFFF_FFFFL, b & 0xFFFF_FFFFL};
        }
        if (n >= 12) {
            c += (key[off + 11] & 0xFF) << 24;
        }
        if (n >= 11) {
            c += (key[off + 10] & 0xFF) << 16;
        }
        if (n >= 10) {
            c += (key[off + 9] & 0xFF) << 8;
        }
        if (n >= 9) {
            c += key[off + 8] & 0xFF;
        }
        if (n >= 8) {
            b += (key[off + 7] & 0xFF) << 24;
        }
        if (n >= 7) {
            b += (key[off + 6] & 0xFF) << 16;
        }
        if (n >= 6) {
            b += (key[off + 5] & 0xFF) << 8;
        }
        if (n >= 5) {
            b += key[off + 4] & 0xFF;
        }
        if (n >= 4) {
            a += (key[off + 3] & 0xFF) << 24;
        }
        if (n >= 3) {
            a += (key[off + 2] & 0xFF) << 16;
        }
        if (n >= 2) {
            a += (key[off + 1] & 0xFF) << 8;
        }
        // 走到这里必然 n >= 1
        a += key[off] & 0xFF;

        int[] abc = {a, b, c};
        finalMix(abc);
        return new long[]{abc[2] & 0xFFFF_FFFFL, abc[1] & 0xFFFF_FFFFL};
    }

    private static int leU32(byte[] k, int off) {
        return (k[off] & 0xFF) | (k[off + 1] & 0xFF) << 8
                | (k[off + 2] & 0xFF) << 16 | (k[off + 3] & 0xFF) << 24;
    }

    private static void mix(int[] abc) {
        int a = abc[0], b = abc[1], c = abc[2];
        a -= c;
        a ^= Integer.rotateLeft(c, 4);
        c += b;
        b -= a;
        b ^= Integer.rotateLeft(a, 6);
        a += c;
        c -= b;
        c ^= Integer.rotateLeft(b, 8);
        b += a;
        a -= c;
        a ^= Integer.rotateLeft(c, 16);
        c += b;
        b -= a;
        b ^= Integer.rotateLeft(a, 19);
        a += c;
        c -= b;
        c ^= Integer.rotateLeft(b, 4);
        b += a;
        abc[0] = a;
        abc[1] = b;
        abc[2] = c;
    }

    private static void finalMix(int[] abc) {
        int a = abc[0], b = abc[1], c = abc[2];
        c ^= b;
        c -= Integer.rotateLeft(b, 14);
        a ^= c;
        a -= Integer.rotateLeft(c, 11);
        b ^= a;
        b -= Integer.rotateLeft(a, 25);
        c ^= b;
        c -= Integer.rotateLeft(b, 16);
        a ^= c;
        a -= Integer.rotateLeft(c, 4);
        b ^= a;
        b -= Integer.rotateLeft(a, 14);
        c ^= b;
        c -= Integer.rotateLeft(b, 24);
        abc[0] = a;
        abc[1] = b;
        abc[2] = c;
    }
}
