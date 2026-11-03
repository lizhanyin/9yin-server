package com.nineyin.tools.mpq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * HET / BET 表 —— v3+ 的扩展 hash/block 表，布局与查找语义遵循
 * StormLib SBaseFileTable.cpp。与 Rust mpq 库 hetbet.rs 等价。
 */
final class ExtTables {

    static final int HET_SIGNATURE = 0x1A54_4548; // 'HET\x1A'
    static final int BET_SIGNATURE = 0x1A54_4542; // 'BET\x1A'

    private ExtTables() {
    }

    /**
     * 加载扩展表（HET/BET）：校验签名、解密，必要时解压。
     * size 是 MPQ 头给出的磁盘字节大小。
     */
    static byte[] loadExtTable(ByteReader reader, long offset, long size, int signature, int key) {
        if (offset == 0 || size == 0) {
            throw new MpqException("扩展表偏移/大小为零");
        }
        int cap = (int) Math.min(size, Integer.MAX_VALUE);
        byte[] raw = reader.readAtOrEof(offset, cap);
        if (raw.length < 12) {
            throw new MpqException("扩展表过短");
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int sig = b.getInt(0);
        int version = b.getInt(4);
        int dataSize = b.getInt(8);

        if (sig != signature) {
            throw new MpqException("扩展表签名不匹配");
        }
        if (version != 1) {
            throw new MpqException("扩展表版本不是 1");
        }

        // 头部推算的磁盘大小超出实际时，以声明的 data_size 为准。
        long want = Integer.toUnsignedLong(dataSize) + 12;
        if (want < raw.length) {
            byte[] trimmed = new byte[(int) want];
            System.arraycopy(raw, 0, trimmed, 0, trimmed.length);
            raw = trimmed;
        }

        // 解密 12 字节头部之后的内容。
        StormCrypt.decryptBytes(raw, 12, raw.length - 12, key);

        // data_size 大于磁盘大小意味着负载已压缩。
        long wantTotal = Integer.toUnsignedLong(dataSize) + 12;
        if (wantTotal > raw.length) {
            byte[] decompressed = MpqCompression.decompressMasked(
                    MpqHeader.ArraysSlice.of(raw, 12, raw.length - 12), dataSize);
            byte[] table = new byte[12 + Math.min(decompressed.length, dataSize)];
            System.arraycopy(raw, 0, table, 0, 12);
            System.arraycopy(decompressed, 0, table, 12, table.length - 12);
            if (table.length < wantTotal) {
                throw new MpqException("扩展表解压后过短");
            }
            return table;
        }
        return raw;
    }

    // -- HET 表 --------------------------------------------------------------

    /** 已解析的 HET 表。 */
    static final class HetTable {
        /** 每槽一个探测字节；0 = 空闲槽位。 */
        final byte[] nameHash1;
        /** 按位打包的文件索引，每槽 indexSizeTotal 位。 */
        final byte[] betIndexes;
        final int entryCount;
        final int totalCount;
        final int nameHashBitSize;
        final int indexSizeTotal;
        final int indexSize;

        HetTable(byte[] nameHash1, byte[] betIndexes, int entryCount, int totalCount,
                 int nameHashBitSize, int indexSizeTotal, int indexSize) {
            this.nameHash1 = nameHash1;
            this.betIndexes = betIndexes;
            this.entryCount = entryCount;
            this.totalCount = totalCount;
            this.nameHashBitSize = nameHashBitSize;
            this.indexSizeTotal = indexSizeTotal;
            this.indexSize = indexSize;
        }

        static HetTable parse(byte[] table) {
            if (table.length < 12 + 32) {
                throw new MpqException("HET 表过短");
            }
            ByteBuffer b = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            int tableSize = b.getInt(12);
            int entryCount = b.getInt(16);
            int totalCount = b.getInt(20);
            int nameHashBitSize = b.getInt(24);
            int indexSizeTotal = b.getInt(28);
            int indexSizeExtra = b.getInt(32);
            int indexSize = b.getInt(36);
            int indexTableSize = b.getInt(40);

            if (totalCount == 0 || nameHashBitSize == 0 || nameHashBitSize > 64) {
                throw new MpqException("HET 头部字段超出范围");
            }
            if (indexSize > indexSizeTotal || indexSizeTotal == 0 || indexSize > 32) {
                throw new MpqException("HET 索引大小超出范围");
            }

            byte[] data = MpqHeader.ArraysSlice.of(table, 44, table.length - 44);
            long hashesLen = Integer.toUnsignedLong(totalCount);
            long indexesComputed = (hashesLen * indexSizeTotal + 7) / 8;
            long indexesLen = Integer.compareUnsigned(indexTableSize, 0) > 0
                    && data.length >= hashesLen + Integer.toUnsignedLong(indexTableSize)
                    && Math.abs(Integer.toUnsignedLong(indexTableSize) - indexesComputed) <= 8
                    ? Integer.toUnsignedLong(indexTableSize) : indexesComputed;
            if (data.length < hashesLen || data.length < hashesLen + indexesLen) {
                throw new MpqException("HET 表主体被截断");
            }
            return new HetTable(
                    MpqHeader.ArraysSlice.of(data, 0, (int) hashesLen),
                    MpqHeader.ArraysSlice.of(data, (int) hashesLen, (int) indexesLen),
                    entryCount, totalCount, nameHashBitSize, indexSizeTotal, indexSize);
        }

        long andMask() {
            return nameHashBitSize == 64 ? -1L : (1L << nameHashBitSize) - 1;
        }

        long orMask() {
            return 1L << (nameHashBitSize - 1);
        }

        /** 用完整（掩码后）名称哈希探测；按探测顺序返回候选文件索引。 */
        int[] lookup(long hash) {
            long fileHash = (hash & andMask()) | orMask();
            int hash1 = (int) ((fileHash >>> (nameHashBitSize - 8)) & 0xFF);

            long total = Integer.toUnsignedLong(totalCount);
            long slot = Long.remainderUnsigned(fileHash, total);
            long start = slot;
            int[] found = new int[8];
            int count = 0;

            while (true) {
                int probe = nameHash1[(int) slot] & 0xFF;
                if (probe == 0) {
                    break; // 空闲槽位终止探测
                }
                if (probe == hash1) {
                    long idx = getBits(betIndexes,
                            (int) slot * indexSizeTotal, indexSize);
                    if (count == found.length) {
                        int[] grown = new int[found.length * 2];
                        System.arraycopy(found, 0, grown, 0, found.length);
                        found = grown;
                    }
                    found[count++] = (int) idx;
                }
                slot = (slot + 1) % total;
                if (slot == start) {
                    break;
                }
            }
            int[] result = new int[count];
            System.arraycopy(found, 0, result, 0, count);
            return result;
        }
    }

    // -- BET 表 --------------------------------------------------------------

    /** 单条 BET 文件记录，已解析成经典结构字段。 */
    record BetFileRecord(long filePos, int fileSize, int cmpSize, int flags) {
    }

    /** 已解析的 BET 表。 */
    static final class BetTable {
        final int[] flags;
        final byte[] fileTable;
        final byte[] nameHashes;
        final int entryCount;
        final int tableEntrySize;
        final int bitIndexFilePos;
        final int bitIndexFileSize;
        final int bitIndexCmpSize;
        final int bitIndexFlagIndex;
        final int bitCountFilePos;
        final int bitCountFileSize;
        final int bitCountCmpSize;
        final int bitCountFlagIndex;
        final int bitTotalNameHash2;
        final int bitCountNameHash2;

        BetTable(int[] flags, byte[] fileTable, byte[] nameHashes, int entryCount, int tableEntrySize,
                 int bitIndexFilePos, int bitIndexFileSize, int bitIndexCmpSize, int bitIndexFlagIndex,
                 int bitCountFilePos, int bitCountFileSize, int bitCountCmpSize, int bitCountFlagIndex,
                 int bitTotalNameHash2, int bitCountNameHash2) {
            this.flags = flags;
            this.fileTable = fileTable;
            this.nameHashes = nameHashes;
            this.entryCount = entryCount;
            this.tableEntrySize = tableEntrySize;
            this.bitIndexFilePos = bitIndexFilePos;
            this.bitIndexFileSize = bitIndexFileSize;
            this.bitIndexCmpSize = bitIndexCmpSize;
            this.bitIndexFlagIndex = bitIndexFlagIndex;
            this.bitCountFilePos = bitCountFilePos;
            this.bitCountFileSize = bitCountFileSize;
            this.bitCountCmpSize = bitCountCmpSize;
            this.bitCountFlagIndex = bitCountFlagIndex;
            this.bitTotalNameHash2 = bitTotalNameHash2;
            this.bitCountNameHash2 = bitCountNameHash2;
        }

        static BetTable parse(byte[] table) {
            if (table.length < 12 + 76) {
                throw new MpqException("BET 表过短");
            }
            ByteBuffer b = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            int entryCount = b.getInt(16);
            int flagCount = b.getInt(84);

            if (Integer.compareUnsigned(entryCount, 50_000_000) > 0) {
                throw new MpqException("BET 条目数超出范围");
            }

            int pos = 88;
            byte[] data = MpqHeader.ArraysSlice.of(table, pos, table.length - pos);
            long flagsLen = Integer.toUnsignedLong(flagCount) * 4;
            if (data.length < flagsLen) {
                throw new MpqException("BET 标志数组被截断");
            }
            int[] flags = new int[flagCount];
            for (int i = 0; i < flagCount; i++) {
                flags[i] = ByteBuffer.wrap(data, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            pos += flagsLen;

            int tableEntrySize = b.getInt(24);
            long fileTableBits = Integer.toUnsignedLong(entryCount) * Integer.toUnsignedLong(tableEntrySize);
            int fileTableBytes = (int) ((fileTableBits + 7) / 8);
            if (table.length < pos + fileTableBytes) {
                throw new MpqException("BET 文件表被截断");
            }
            byte[] fileTable = MpqHeader.ArraysSlice.of(table, pos, fileTableBytes);
            pos += fileTableBytes;

            int bitTotalNameHash2 = b.getInt(68);
            long hashBytes = (Integer.toUnsignedLong(entryCount)
                    * Integer.toUnsignedLong(bitTotalNameHash2) + 7) / 8;
            if (table.length < pos + hashBytes) {
                throw new MpqException("BET 名称哈希数组被截断");
            }
            byte[] nameHashes = MpqHeader.ArraysSlice.of(table, (int) pos, (int) hashBytes);

            return new BetTable(flags, fileTable, nameHashes, entryCount, tableEntrySize,
                    b.getInt(28), b.getInt(32), b.getInt(36), b.getInt(40),
                    b.getInt(48), b.getInt(52), b.getInt(56), b.getInt(60),
                    bitTotalNameHash2, b.getInt(76));
        }

        /** 将文件记录 index 解码为经典结构字段。 */
        BetFileRecord record(int index) {
            int base = index * tableEntrySize;
            long filePos = 0;
            int fileSize = 0;
            int cmpSize = 0;
            int flagBits = 0;
            if (bitCountFilePos > 0) {
                filePos = getBits(fileTable, base + bitIndexFilePos, bitCountFilePos);
            }
            if (bitCountFileSize > 0) {
                fileSize = (int) getBits(fileTable, base + bitIndexFileSize, bitCountFileSize);
            }
            if (bitCountCmpSize > 0) {
                cmpSize = (int) getBits(fileTable, base + bitIndexCmpSize, bitCountCmpSize);
            }
            if (bitCountFlagIndex > 0 && flags.length > 0) {
                int flagIndex = (int) getBits(fileTable, base + bitIndexFlagIndex, bitCountFlagIndex);
                if (flagIndex < flags.length) {
                    flagBits = flags[flagIndex];
                }
            }
            return new BetFileRecord(filePos, fileSize, cmpSize, flagBits);
        }

        /** 结合 HET 探测字节为某条记录重建完整的 HET 风格名称哈希。 */
        long fullNameHash(int hash1, int index) {
            return ((hash1 & 0xFFFL) << bitCountNameHash2) | nameHash2(index);
        }

        /** 文件记录 index 所存储的（部分）名称哈希。 */
        long nameHash2(int index) {
            return getBits(nameHashes, index * bitTotalNameHash2, bitCountNameHash2);
        }
    }

    // -- 位操作：LSB 优先，与 StormLib TMPQBits 一致 --------------------------

    /** 从绝对位偏移 offset 起读 bits 位（LSB 优先）。 */
    static long getBits(byte[] data, long offset, int bits) {
        if (bits == 0 || bits > 64) {
            return 0;
        }
        long result = 0;
        for (int i = 0; i < bits; i++) {
            long bitPos = offset + i;
            int byteIdx = (int) (bitPos / 8);
            int bit = byteIdx < data.length ? (data[byteIdx] >>> (bitPos % 8)) & 1 : 0;
            result |= (long) bit << i;
        }
        return result;
    }
}
