package com.nineyin.tools.mpq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MPQ 归档头部（v1–v4）及 MPQ\x1B 用户数据头部。
 * 与 Rust mpq 库 header.rs 等价。
 */
final class MpqHeader {

    /** 小端 u32 的 "MPQ\x1A"。 */
    static final int MPQ_MAGIC = 0x1A51_504D;
    /** 小端 u32 的 "MPQ\x1B"（用户数据头部）。 */
    static final int USERDATA_MAGIC = 0x1B51_504D;

    static final int HEADER_SIZE_V1 = 0x20;
    static final int HEADER_SIZE_V2 = 0x2C;
    static final int HEADER_SIZE_V3 = 0x44;
    static final int HEADER_SIZE_V4 = 0xD0;
    /** v4 头部自身 MD5 覆盖的字节数（签名起至 MD5_HetTable 含）。 */
    static final int HEADER_MD5_SPAN = 0xC0;
    /** 检查头部候选时最多需要读取的字节数。 */
    static final int MAX_HEADER_READ = HEADER_SIZE_V4;

    /** 头部字段声明的 MPQ 格式版本。 */
    enum FormatVersion {
        V1, V2, V3, V4;

        static FormatVersion fromRaw(int v) {
            return switch (v) {
                case 0 -> V1;
                case 1 -> V2;
                case 2 -> V3;
                case 3 -> V4;
                default -> throw new MpqException("无法支持的 MPQ 格式版本字段：" + v);
            };
        }

        int raw() {
            return ordinal();
        }

        int headerSize() {
            return switch (this) {
                case V1 -> HEADER_SIZE_V1;
                case V2 -> HEADER_SIZE_V2;
                case V3 -> HEADER_SIZE_V3;
                case V4 -> HEADER_SIZE_V4;
            };
        }
    }

    int headerSize;
    int archiveSize;
    int formatVersion;
    int blockSize;
    long hashTablePos;
    long blockTablePos;
    int hashTableSize;
    int blockTableSize;

    // v2
    long hiBlockTablePos;

    // v3
    long archiveSize64;
    long betTablePos;
    long hetTablePos;

    // v4
    long hashTableSize64;
    long blockTableSize64;
    long hiBlockTableSize64;
    long hetTableSize64;
    long betTableSize64;
    /** v4：每个文件级 MD5 覆盖的原始块大小（0 = 无）。 */
    int rawChunkSize;
    byte[] md5BlockTable = new byte[16];
    byte[] md5HashTable = new byte[16];
    byte[] md5HiBlockTable = new byte[16];
    byte[] md5BetTable = new byte[16];
    byte[] md5HetTable = new byte[16];
    byte[] md5Header = new byte[16];

    /** 从 buf 解析头部（长度 >= 0x20；短于版本的尾字段读作 0）。 */
    static MpqHeader parse(byte[] buf) {
        if (buf.length < HEADER_SIZE_V1) {
            throw new MpqException("MPQ 头部不足 0x20 字节");
        }
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != MPQ_MAGIC) {
            throw new MpqException("MPQ 魔数错误");
        }
        FormatVersion version = FormatVersion.fromRaw(b.getShort(0x0C) & 0xFFFF);

        MpqHeader h = new MpqHeader();
        h.headerSize = b.getInt(0x04);
        h.archiveSize = b.getInt(0x08);
        h.formatVersion = b.getShort(0x0C) & 0xFFFF;
        h.blockSize = b.getShort(0x0E) & 0xFFFF;
        h.hashTablePos = b.getInt(0x10) & 0xFFFF_FFFFL;
        h.blockTablePos = b.getInt(0x14) & 0xFFFF_FFFFL;
        h.hashTableSize = b.getInt(0x18);
        h.blockTableSize = b.getInt(0x1C);

        // 宽容处理：某些工具写入的 header_size 小于标准 v1 大小（无符号比较）。
        if (Integer.compareUnsigned(h.headerSize, HEADER_SIZE_V1) < 0) {
            h.headerSize = HEADER_SIZE_V1;
        }

        if (version.ordinal() >= 1 && buf.length >= HEADER_SIZE_V2) {
            h.hiBlockTablePos = b.getLong(0x20);
            h.hashTablePos |= (b.getShort(0x28) & 0xFFFFL) << 32;
            h.blockTablePos |= (b.getShort(0x2A) & 0xFFFFL) << 32;
        }
        if (version.ordinal() >= 2 && buf.length >= HEADER_SIZE_V3) {
            h.archiveSize64 = b.getLong(0x2C);
            h.betTablePos = b.getLong(0x34);
            h.hetTablePos = b.getLong(0x3C);
        }
        if (version == FormatVersion.V4 && buf.length >= HEADER_SIZE_V4) {
            h.hashTableSize64 = b.getLong(0x44);
            h.blockTableSize64 = b.getLong(0x4C);
            h.hiBlockTableSize64 = b.getLong(0x54);
            h.hetTableSize64 = b.getLong(0x5C);
            h.betTableSize64 = b.getLong(0x64);
            h.rawChunkSize = b.getInt(0x6C);
            b.position(0x70);
            b.get(h.md5BlockTable);
            b.get(h.md5HashTable);
            b.get(h.md5HiBlockTable);
            b.get(h.md5BetTable);
            b.get(h.md5HetTable);
            b.get(h.md5Header);
        }
        return h;
    }

    /** 扫描内嵌归档时的合理性检查（全部无符号比较）。 */
    boolean plausible() {
        if (Integer.compareUnsigned(headerSize, HEADER_SIZE_V1) < 0
                || Integer.compareUnsigned(headerSize, 0x1000) > 0) {
            return false;
        }
        // sector 指数合理性检查：storm 实现里只有低字节有意义。
        return blockSize <= 0xFF;
    }

    /** sector 大小（字节）：512 << blockSize（低字节）。 */
    int sectorSize() {
        return 512 << (blockSize & 0xFF);
    }

    /** 有效归档大小（相对归档起始处）；宽容回退到实际可用长度。 */
    long archiveSize(long fileLenFromBase) {
        if (archiveSize64 != 0 && archiveSize64 <= fileLenFromBase) {
            return archiveSize64;
        }
        if (archiveSize != 0 && Integer.toUnsignedLong(archiveSize) <= fileLenFromBase) {
            return Integer.toUnsignedLong(archiveSize);
        }
        return fileLenFromBase;
    }

    // -- 写入侧 -------------------------------------------------------------

    /** 序列化归档头（v1–v4；v4 校验和由 finishV4Header 填充）。 */
    static byte[] writeHeader(FormatVersion version, int sectorShift,
                              int hashPos, int hashSize, int blockPos, int blockCount,
                              long archiveSize) {
        int headerSize = version.headerSize();
        ByteBuffer h = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(MPQ_MAGIC);
        h.putInt(headerSize);
        h.putInt((int) Math.min(archiveSize, 0xFFFF_FFFFL));
        h.putShort((short) version.raw());
        h.putShort((short) sectorShift);
        h.putInt(hashPos);
        h.putInt(blockPos);
        h.putInt(hashSize);
        h.putInt(blockCount);
        if (version.ordinal() >= FormatVersion.V2.ordinal()) {
            h.putLong(0L);              // hi-block 表位置
            h.putShort((short) 0);      // hash table 位置高位
            h.putShort((short) 0);      // block table 位置高位
        }
        if (version.ordinal() >= FormatVersion.V3.ordinal()) {
            h.putLong(archiveSize);     // 64 位归档大小
            h.putLong(0L);              // BET 位置
            h.putLong(0L);              // HET 位置
        }
        // v4 独有字段（表大小、MD5）保持为零，由 finishV4Header 填充。
        return h.array();
    }

    /**
     * 填充 v4 独有头字段：显式表存储大小、针对存储后（加密、未压缩）
     * 字节的表 MD5，以及头部自身 0x00..0xC0 的 MD5，与 StormLib 一致。
     */
    static void finishV4Header(byte[] header, byte[] hashBuf, byte[] blockBuf) {
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        h.putLong(0x44, hashBuf.length);
        h.putLong(0x4C, blockBuf.length);
        // 0x54 hi-block / 0x5C HET / 0x64 BET size64 与 0x6C rawChunkSize 保持 0。
        System.arraycopy(md5(blockBuf), 0, header, 0x70, 16);
        System.arraycopy(md5(hashBuf), 0, header, 0x80, 16);
        System.arraycopy(md5(ArraysSlice.of(header, 0, HEADER_MD5_SPAN)), 0, header, HEADER_MD5_SPAN, 16);
    }

    static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 MD5", e);
        }
    }

    /** 对应 StormLib IsValidMD5：全零 MD5 表示“不存在”。 */
    static boolean md5Present(byte[] md5) {
        for (byte b : md5) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    /** 小工具：切片（避免到处 Arrays.copyOfRange）。 */
    static final class ArraysSlice {
        static byte[] of(byte[] data, int off, int len) {
            byte[] out = new byte[len];
            System.arraycopy(data, off, out, 0, len);
            return out;
        }
    }

    /** MPQ\x1B 用户数据头部（War3/SC2 地图）。 */
    record UserDataHeader(int dataSize, int archiveOffset, int headerSize) {

        /** 数据不像合理的用户数据头部时返回 null。 */
        static UserDataHeader parse(byte[] buf) {
            if (buf.length < 16) {
                return null;
            }
            ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != USERDATA_MAGIC) {
                return null;
            }
            int dataSize = b.getInt(4);
            int archiveOffset = b.getInt(8);
            int headerSize = b.getInt(12);
            // 与 StormLib IsValidMpqUserData 一致的合理性检查。
            if (Integer.compareUnsigned(headerSize, dataSize) > 0
                    || Integer.compareUnsigned(dataSize, archiveOffset) > 0) {
                return null;
            }
            return new UserDataHeader(dataSize, archiveOffset, headerSize);
        }
    }
}
