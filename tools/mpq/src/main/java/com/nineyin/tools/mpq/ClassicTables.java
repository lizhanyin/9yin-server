package com.nineyin.tools.mpq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 经典 hash table / block table / hi-block table。
 * 与 Rust mpq 库 tables.rs 等价。
 */
final class ClassicTables {

    /** 哨兵值：hash 槽位从未使用；查找在此终止。 */
    static final int HASH_ENTRY_FREE = 0xFFFF_FFFF;
    /** 哨兵值：hash 槽位指向的文件已被删除。 */
    static final int HASH_ENTRY_DELETED = 0xFFFF_FFFE;

    /** 一个经典 hash table 表项（已解密）。 */
    record HashEntry(int name1, int name2, int locale, byte platform, byte flags, int blockIndex) {

        boolean isFree() {
            return Integer.compareUnsigned(blockIndex, HASH_ENTRY_DELETED) >= 0;
        }

        void serialize(ByteBuffer out) {
            out.putInt(name1);
            out.putInt(name2);
            out.putShort((short) locale);
            out.put(platform);
            out.put(flags);
            out.putInt(blockIndex);
        }
    }

    /** 一个经典 block table 表项（已解密）。 */
    record BlockEntry(int pos, int csize, int fsize, int flags) {
    }

    final HashEntry[] hashTable;
    final BlockEntry[] blockTable;
    /** 每个块偏移的高 16 位（可能为空数组）。 */
    final short[] hiBlock;

    ClassicTables(HashEntry[] hashTable, BlockEntry[] blockTable, short[] hiBlock) {
        this.hashTable = hashTable;
        this.blockTable = blockTable;
        this.hiBlock = hiBlock;
    }

    ClassicTables() {
        this(new HashEntry[0], new BlockEntry[0], new short[0]);
    }

    /** 按名称查找（不分 locale，首个匹配生效）；返回对应 hash 表项。 */
    HashEntry find(String name) {
        if (hashTable.length == 0) {
            return null;
        }
        byte[] normalized = StormCrypt.normalizeName(name);
        long start = Integer.toUnsignedLong(StormCrypt.hashBytes(normalized, StormCrypt.HASH_TABLE_INDEX));
        int name1 = StormCrypt.hashBytes(normalized, StormCrypt.HASH_NAME_A);
        int name2 = StormCrypt.hashBytes(normalized, StormCrypt.HASH_NAME_B);

        // hash table 大小本应是 2 的幂；用取模让格式古怪的归档也能打开。
        int size = hashTable.length;
        int index = (int) (start % size);
        int startIndex = index;

        while (true) {
            HashEntry entry = hashTable[index];
            if (entry.name1 == name1 && entry.name2 == name2 && !entry.isFree()
                    && Integer.compareUnsigned(entry.blockIndex, blockTable.length) < 0) {
                return entry;
            }
            if (entry.blockIndex == HASH_ENTRY_FREE) {
                return null;
            }
            index = (index + 1) % size;
            if (index == startIndex) {
                return null;
            }
        }
    }

    /** 将 hi-block 的 16 位字合并进块偏移（48 位结果）。 */
    long blockOffset(int blockIndex) {
        long hi = blockIndex < hiBlock.length ? (hiBlock[blockIndex] & 0xFFFFL) : 0;
        return (hi << 32) | Integer.toUnsignedLong(blockTable[blockIndex].pos);
    }

    /** 将已解密的 hash table 缓冲区解析为表项。 */
    static HashEntry[] parseHashTable(byte[] buf) {
        int n = buf.length / 16;
        HashEntry[] entries = new HashEntry[n];
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            entries[i] = new HashEntry(
                    b.getInt(),
                    b.getInt(),
                    b.getShort() & 0xFFFF,
                    b.get(),
                    b.get(),
                    b.getInt());
        }
        return entries;
    }

    /** 将已解密的 block table 缓冲区解析为表项。 */
    static BlockEntry[] parseBlockTable(byte[] buf) {
        int n = buf.length / 16;
        BlockEntry[] entries = new BlockEntry[n];
        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            entries[i] = new BlockEntry(b.getInt(), b.getInt(), b.getInt(), b.getInt());
        }
        return entries;
    }

    /**
     * 读取、解密并（需要时）解压一张经典表。
     *
     * @param storedSize 表在磁盘上的字节数（null = 未压缩）；小于 tableSize 表示已压缩
     * @param tableSize  未压缩大小（entries * 16）
     */
    static byte[] loadMpqTable(ByteReader reader, long offset, Long storedSize, int tableSize, int key) {
        if (tableSize == 0) {
            return new byte[0];
        }
        long stored0 = storedSize != null ? Math.min(storedSize, Integer.toUnsignedLong(tableSize))
                : Integer.toUnsignedLong(tableSize);
        int stored = (int) Math.min(stored0, Integer.MAX_VALUE);

        byte[] raw = reader.readAtOrEof(offset, stored);
        if (key != 0) {
            StormCrypt.decryptBytes(raw, key);
        }
        if (stored < tableSize) {
            byte[] table = MpqCompression.decompressMasked(raw, tableSize);
            if (table.length < tableSize) {
                throw new MpqException("解压后的表过短");
            }
            return table;
        }
        return raw;
    }
}
