package com.nineyin.tools.mpq;

import java.io.ByteArrayOutputStream;

/**
 * PKWARE Data Compression Library 的 explode（解压）。
 * 忠实移植自 StormLib 所用的公开 PKWARE DCL 实现（pklib/explode.c），
 * 与 Rust mpq 库 pkware.rs 等价。MPQ 以方法字节 0x08 或旧式 IMPLODE
 * 标志存储 PKWARE 压缩数据。
 */
final class Pkware {

    private static final byte CMP_BINARY = 0;
    private static final byte CMP_ASCII = 1;

    // -- 规范码表（提取自 StormLib pklib/explode.c，勿手工修改） ----------

    static final int[] DIST_BITS = {2,4,4,5,5,5,5,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8,8};

    static final int[] DIST_CODE = {3,13,5,25,9,17,1,62,30,46,14,54,22,38,6,58,26,42,10,50,18,34,66,2,124,60,92,28,108,44,76,12,116,52,84,20,100,36,68,4,120,56,88,24,104,40,72,8,240,112,176,48,208,80,144,16,224,96,160,32,192,64,128,0};

    static final int[] EX_LEN_BITS = {0,0,0,0,0,0,0,0,1,2,3,4,5,6,7,8};

    static final int[] LEN_BASE = {0,1,2,3,4,5,6,7,8,10,14,22,38,70,134,262};

    static final int[] LEN_BITS = {3,2,3,3,4,4,4,5,5,5,5,6,6,6,7,7};

    static final int[] LEN_CODE = {5,3,1,6,10,2,12,20,4,24,8,48,16,32,64,0};

    static final int[] CH_BITS_ASC = {11,12,12,12,12,12,12,12,12,8,7,12,12,7,12,12,12,12,12,12,12,12,12,12,12,12,13,12,12,12,12,12,4,10,8,12,10,12,10,8,7,7,8,9,7,6,7,8,7,6,7,7,7,7,8,7,7,8,8,12,11,7,9,11,12,6,7,6,6,5,7,8,8,6,11,9,6,7,6,6,7,11,6,6,6,7,9,8,9,9,11,8,11,9,12,8,12,5,6,6,6,5,6,6,6,5,11,7,5,6,5,5,6,10,5,5,5,5,8,7,8,8,10,11,11,12,12,12,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,13,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,12,13,12,13,13,13,12,13,13,13,12,13,13,13,13,12,13,13,13,12,12,12,13,13,13,13,13,13,13,13,13,13,13};

    static final int[] CH_CODE_ASC = {1168,4064,2016,3040,992,3552,1504,2528,480,184,98,3808,1760,34,2784,736,3296,1248,2272,224,3936,1888,2912,864,3424,1376,4672,2400,352,3680,1632,2656,15,592,56,608,80,3168,912,216,66,2,88,432,124,41,60,152,92,9,28,108,44,76,24,12,116,232,104,1120,144,52,176,1808,2144,49,84,17,33,23,20,168,40,1,784,304,62,100,30,46,36,1296,14,54,22,68,48,200,464,208,272,72,1552,336,96,136,4000,7,38,6,58,27,26,42,10,11,528,4,19,50,3,29,18,400,13,21,5,25,8,120,240,112,656,1040,16,1952,2976,928,576,7232,3136,5184,1088,6208,2112,4160,64,8064,3968,6016,1920,7040,2944,4992,896,7552,3456,5504,1408,6528,2432,4480,384,7808,3712,5760,1664,6784,2688,4736,640,7296,3200,5248,1152,6272,2176,4224,128,7936,3840,5888,1792,6912,2816,4864,3488,1440,2464,416,3744,1696,2720,672,3232,1184,2208,160,3872,1824,2848,800,3360,1312,2336,288,3616,1568,2592,544,3104,1056,2080,32,4032,1984,3008,960,3520,1472,2496,448,3776,1728,2752,704,3264,1216,2240,192,3904,1856,2880,832,768,3392,7424,3328,5376,1344,1280,6400,2304,2368,4352,256,7680,3584,320,5632,1536,6656,3648,1600,2624,2560,4608,512,7168,3072,5120,1024,6144,2048,4096,0};

    // decode_lit 的结果码
    private static final int LIT_ERROR = 0x306;
    private static final int LIT_END = 0x305;

    private Pkware() {
    }

    /** 构建长度码/距离码的规范码查找表。 */
    private static byte[] genDecodeTabs(int[] startIndexes, int[] lengthBits, int elements) {
        byte[] positions = new byte[0x100];
        for (int i = 0; i < elements; i++) {
            int length = 1 << lengthBits[i];
            int index = startIndexes[i];
            while (index < 0x100) {
                positions[index] = (byte) i;
                index += length;
            }
        }
        return positions;
    }

    /** ASCII 模式的四级解码表。 */
    private static final class AscTables {
        final byte[] chBitsAsc = new byte[0x100];
        final byte[] l1 = new byte[0x100];
        final byte[] l2 = new byte[0x100];
        final byte[] l3 = new byte[0x80];
        final byte[] l4 = new byte[0x100];
    }

    private static AscTables genAscTabs() {
        AscTables t = new AscTables();
        System.arraycopy(CH_BITS_ASC, 0, t.chBitsAsc, 0, 0x100);

        // 从 0xFF 向 0 迭代各符号；表已清零，与参考实现一致。
        for (int count = 0xFF; count >= 0; count--) {
            int code = CH_CODE_ASC[count];
            int bitsAsc = t.chBitsAsc[count] & 0xFF;

            if (bitsAsc <= 8) {
                int add = 1 << bitsAsc;
                int acc = code;
                while (acc < 0x100) {
                    t.l1[acc] = (byte) count;
                    acc += add;
                }
            } else if ((code & 0xFF) != 0) {
                t.l1[code & 0xFF] = (byte) 0xFF;
                if ((code & 0x3F) != 0) {
                    int bits = bitsAsc - 4;
                    t.chBitsAsc[count] = (byte) bits;
                    int add = 1 << bits;
                    int acc = code >>> 4;
                    while (acc < 0x100) {
                        t.l2[acc] = (byte) count;
                        acc += add;
                    }
                } else {
                    int bits = bitsAsc - 6;
                    t.chBitsAsc[count] = (byte) bits;
                    int add = 1 << bits;
                    int acc = code >>> 6;
                    while (acc < 0x80) {
                        t.l3[acc] = (byte) count;
                        acc += add;
                    }
                }
            } else {
                int bits = bitsAsc - 8;
                t.chBitsAsc[count] = (byte) bits;
                int add = 1 << bits;
                int acc = code >>> 8;
                while (acc < 0x100) {
                    t.l4[acc] = (byte) count;
                    acc += add;
                }
            }
        }
        return t;
    }

    /** 解压 PKWARE DCL 流；最多 maxOut 字节被追加进 out。 */
    static void explode(byte[] input, ByteArrayOutputStream out, int maxOut) {
        if (input.length <= 4) {
            throw new MpqException("pkware 流过短");
        }
        int ctype = input[0] & 0xFF;
        int dsizeBits = input[1] & 0xFF;
        if (dsizeBits < 4 || dsizeBits > 6) {
            throw new MpqException("pkware 非法的字典大小");
        }
        if (ctype != CMP_BINARY && ctype != CMP_ASCII) {
            throw new MpqException("pkware 非法的模式");
        }
        int dsizeMask = 0xFFFF >>> (16 - dsizeBits);

        ExplodeCtx ctx = new ExplodeCtx(input, dsizeBits, dsizeMask, ctype,
                genDecodeTabs(LEN_CODE, LEN_BITS, 0x10),
                genDecodeTabs(DIST_CODE, DIST_BITS, 0x40),
                ctype == CMP_ASCII ? genAscTabs() : null);
        ctx.bitBuff = input[2] & 0xFF;
        ctx.inPos = 3;
        expand(ctx, out, maxOut);
    }

    private static final class ExplodeCtx {
        final byte[] input;
        int inPos;
        /** 16 位位缓冲区；有效位是低 8 + extraBits 位。 */
        int bitBuff;
        int extraBits;
        final int dsizeBits;
        final int dsizeMask;
        final int ctype;
        final byte[] lengthCodes;
        final byte[] distPosCodes;
        final AscTables asc;

        ExplodeCtx(byte[] input, int dsizeBits, int dsizeMask, int ctype,
                   byte[] lengthCodes, byte[] distPosCodes, AscTables asc) {
            this.input = input;
            this.dsizeBits = dsizeBits;
            this.dsizeMask = dsizeMask;
            this.ctype = ctype;
            this.lengthCodes = lengthCodes;
            this.distPosCodes = distPosCodes;
            this.asc = asc;
        }

        /** 从位缓冲区移除 nbits 位，必要时从输入补充；返回是否仍在流内。 */
        boolean wasteBits(int nbits) {
            if (nbits <= extraBits) {
                extraBits -= nbits;
                bitBuff >>>= nbits;
                return true;
            }
            bitBuff >>>= extraBits;
            if (inPos >= input.length) {
                return false;
            }
            bitBuff |= (input[inPos] & 0xFF) << 8;
            inPos++;
            bitBuff >>>= nbits - extraBits;
            // C 里按无符号回绕计算 (extra - nbits) + 8；调整顺序避免下溢。
            extraBits = (extraBits + 8) - nbits;
            return true;
        }
    }

    private static int decodeLit(ExplodeCtx ctx) {
        if ((ctx.bitBuff & 1) != 0) {
            if (!ctx.wasteBits(1)) {
                return LIT_ERROR;
            }
            int lengthCode = ctx.lengthCodes[ctx.bitBuff & 0xFF] & 0xFF;

            if (!ctx.wasteBits(LEN_BITS[lengthCode] & 0xFF)) {
                return LIT_ERROR;
            }
            int extraBitsCount = EX_LEN_BITS[lengthCode] & 0xFF;
            int code = lengthCode;
            if (extraBitsCount != 0) {
                int extra = ctx.bitBuff & ((1 << extraBitsCount) - 1);
                // 只有「流结束 且 不是特殊终止组合」才算解码失败。
                if (!ctx.wasteBits(extraBitsCount) && (lengthCode + extra) != 0x10E) {
                    return LIT_ERROR;
                }
                code = LEN_BASE[lengthCode] + extra;
            }
            return code + 0x100;
        }

        if (!ctx.wasteBits(1)) {
            return LIT_ERROR;
        }

        if (ctx.ctype == CMP_BINARY) {
            int b = ctx.bitBuff & 0xFF;
            if (!ctx.wasteBits(8)) {
                return LIT_ERROR;
            }
            return b;
        }

        // ASCII 模式
        AscTables asc = ctx.asc;
        int value;
        if ((ctx.bitBuff & 0xFF) != 0) {
            int v = asc.l1[ctx.bitBuff & 0xFF] & 0xFF;
            if (v == 0xFF) {
                if ((ctx.bitBuff & 0x3F) != 0) {
                    if (!ctx.wasteBits(4)) {
                        return LIT_ERROR;
                    }
                    v = asc.l2[ctx.bitBuff & 0xFF] & 0xFF;
                } else {
                    if (!ctx.wasteBits(6)) {
                        return LIT_ERROR;
                    }
                    v = asc.l3[ctx.bitBuff & 0x7F] & 0xFF;
                }
            }
            value = v;
        } else {
            if (!ctx.wasteBits(8)) {
                return LIT_ERROR;
            }
            value = asc.l4[ctx.bitBuff & 0xFF] & 0xFF;
        }

        int bits = asc.chBitsAsc[value] & 0xFF;
        if (!ctx.wasteBits(bits)) {
            return LIT_ERROR;
        }
        return value;
    }

    private static int decodeDist(ExplodeCtx ctx, int repLength) {
        int distPosCode = ctx.distPosCodes[ctx.bitBuff & 0xFF] & 0xFF;
        int distPosBits = DIST_BITS[distPosCode] & 0xFF;
        if (!ctx.wasteBits(distPosBits)) {
            return 0;
        }

        int distance;
        if (repLength == 2) {
            int d = (distPosCode << 2) | (ctx.bitBuff & 0x03);
            if (!ctx.wasteBits(2)) {
                return 0;
            }
            distance = d;
        } else {
            int d = (distPosCode << ctx.dsizeBits) | (ctx.bitBuff & ctx.dsizeMask);
            if (!ctx.wasteBits(ctx.dsizeBits)) {
                return 0;
            }
            distance = d;
        }
        return distance + 1;
    }

    private static void expand(ExplodeCtx ctx, ByteArrayOutputStream out, int maxOut) {
        // 0x2204 字节环形缓冲区；解压从 0x1000 处开始写，越过 0x2000 冲刷 0x1000。
        byte[] ring = new byte[0x2204];
        int outputPos = 0x1000;
        final int limit = out.size() + maxOut;

        while (true) {
            int literal = decodeLit(ctx);
            if (literal >= LIT_END) {
                if (literal == LIT_END) {
                    break;
                }
                throw new MpqException("pkware 流已损坏");
            }

            if (literal >= 0x100) {
                int repLength = literal - 0xFE;
                int minusDist = decodeDist(ctx, repLength);
                if (minusDist == 0) {
                    throw new MpqException("pkware 流已损坏（距离）");
                }
                if (minusDist > outputPos) {
                    throw new MpqException("pkware 距离越过起点");
                }

                int target = outputPos;
                int source = outputPos - minusDist;
                outputPos += repLength;
                // 逐字节复制：序列可能重叠（游程式重复）。
                int t = target;
                int s = source;
                while (t < outputPos) {
                    ring[t] = ring[s];
                    t++;
                    s++;
                }
            } else {
                ring[outputPos] = (byte) literal;
                outputPos++;
            }

            if (outputPos >= 0x2000) {
                int take = Math.min(0x1000, limit - out.size());
                if (take > 0) {
                    out.write(ring, 0x1000, take);
                }
                int keep = outputPos - 0x1000;
                System.arraycopy(ring, 0x1000, ring, 0, keep);
                outputPos = keep;
            }
        }

        // 最终冲刷：输出所有待处理内容（受 maxOut 截断）。
        int take = Math.min(outputPos - 0x1000, limit - out.size());
        if (take > 0) {
            out.write(ring, 0x1000, take);
        }
    }
}
