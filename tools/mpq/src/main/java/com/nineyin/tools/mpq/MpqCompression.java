package com.nineyin.tools.mpq;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * MPQ 压缩数据的解压/压缩（sector、单单元、各表）。
 * 与 Rust mpq 库 compression.rs 等价；bzip2 / LZMA 暂不支持（明确报错）。
 */
final class MpqCompression {

    static final byte M_HUFFMAN = 0x01;
    static final byte M_ZLIB = 0x02;
    static final byte M_PKWARE = 0x08;
    static final byte M_BZIP2 = 0x10;
    /** LZMA；0x12 是完整方法值，而非标志组合。 */
    static final byte M_LZMA = 0x12;

    private MpqCompression() {
    }

    /** zlib（zlib 容器 deflate）解压；输出截断至 maxOut（take 语义）。 */
    static byte[] zlibDecompress(byte[] data, int maxOut) {
        if (maxOut <= 0) {
            return new byte[0];
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[maxOut];
            int written = 0;
            while (written < maxOut && !inflater.finished()) {
                try {
                    int n = inflater.inflate(out, written, maxOut - written);
                    written += n;
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw new MpqException("zlib: 数据不完整或格式错误");
                    }
                } catch (DataFormatException e) {
                    throw new MpqException("zlib: " + e.getMessage(), e);
                }
            }
            return written == maxOut ? out : Arrays.copyOf(out, written);
        } finally {
            inflater.end();
        }
    }

    /** 压缩为 zlib 流（写入侧对应 decompressMasked 的 zlib 方法）。 */
    static byte[] zlibCompress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 16);
            byte[] buf = new byte[64 * 1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** 单步解压（无方法字节）。 */
    private static byte[] decompressOne(int method, byte[] data, int maxOut) {
        return switch (method) {
            case 0x02 -> zlibDecompress(data, maxOut);
            case 0x08 -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOut, 1 << 20));
                Pkware.explode(data, out, maxOut);
                yield out.toByteArray();
            }
            case 0x10 -> throw new MpqException("暂不支持 bzip2 压缩方法 0x10（本模块只实现 zlib/PKWARE）");
            case 0x12 -> throw new MpqException("暂不支持 LZMA 压缩方法 0x12（本模块只实现 zlib/PKWARE）");
            default -> throw new MpqException("无法支持的压缩方法 0x" + Integer.toHexString(method));
        };
    }

    /**
     * 解压带方法字节的 MPQ 压缩块（COMPRESS 标志文件的 sector、单单元、扩展表）。
     * 白名单与 StormLib 的 SCompDecompress2 一致；本模块可解 0x02/0x08，
     * 其余（含 bzip2/LZMA）明确报错。
     */
    static byte[] decompressMasked(byte[] data, int maxOut) {
        if (data.length == 0) {
            return new byte[0];
        }
        int mask = data[0] & 0xFF;

        if (isKnownMethod(mask)) {
            if (mask == 0x02 || mask == 0x08) {
                byte[] tail = Arrays.copyOfRange(data, 1, data.length);
                return decompressOne(mask, tail, maxOut);
            }
            // 0x10 / 0x12 等已知但本模块未实现的方法。
            throw new MpqException("暂不支持的压缩方法 0x" + Integer.toHexString(mask)
                    + "（本模块只实现 zlib 0x02 / PKWARE 0x08）");
        }

        // 回退（宽容处理）：方法字节未知或缺失 —— 假定整个块是普通 zlib 流。
        try {
            return zlibDecompress(data, maxOut);
        } catch (MpqException e) {
            throw new MpqException("无法支持的压缩方法 0x" + Integer.toHexString(mask));
        }
    }

    private static boolean isKnownMethod(int mask) {
        return switch (mask) {
            case 0x01, 0x02, 0x08, 0x10, 0x12, 0x20, 0x22, 0x30, 0x41, 0x81 -> true;
            default -> false;
        };
    }

    /** 解压旧式 IMPLODE 标志存储的原始 PKWARE 流（无方法字节）。 */
    static byte[] decompressImploded(byte[] data, int maxOut) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOut, 1 << 20));
        Pkware.explode(data, out, maxOut);
        return out.toByteArray();
    }
}
