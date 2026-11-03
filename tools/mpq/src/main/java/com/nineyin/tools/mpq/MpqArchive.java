package com.nineyin.tools.mpq;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;

import static com.nineyin.tools.mpq.ClassicTables.HASH_ENTRY_FREE;

/**
 * 一个已打开的 MPQ 归档（只读）。移植自 Rust mpq 库 archive.rs：
 * 头部定位（含 MPQ\x1B 用户数据与内嵌归档扫描）、经典表与 HET/BET 表、
 * 按名查找与读取（sector / 单单元、加密、zlib / PKWARE 解压）。
 *
 * <p>与 Rust 端的差异：不做缺失 (listfile) 时的名字恢复（内置魔兽清单
 * 启发式），也不解析 (attributes)；不支持 Rust 库自定义的密码 AEAD
 * 扩展（遇到即明确报错）。实例线程安全（读取经内部锁串行化）。
 */
public final class MpqArchive implements AutoCloseable {

    /** 可能存在也可能不存在的内部文件名（均为可选）。 */
    public static final List<String> SPECIAL_FILES = List.of(
            "(listfile)", "(attributes)", "(signature)", "(userdata)", "(password)");

    private final ByteReader reader;
    private final long base;
    private final long fileLen;
    final MpqHeader header;
    private final MpqHeader.FormatVersion version;
    private final int sectorSize;
    /** 经典表（可能为 null：没有任何经典表内容）。 */
    final ClassicTables classic;
    final ExtTables.HetTable het;
    final ExtTables.BetTable bet;
    /** 来自 (listfile) 的文件名。 */
    final List<String> names;
    private final List<String> warnings;
    /** 读取文件数据时遇到的非致命问题（有界环形缓冲）。 */
    private final Deque<String> readWarnings = new ArrayDeque<>();

    private MpqArchive(ByteReader reader) {
        this.reader = reader;
        this.fileLen = reader.length();
        this.base = locateHeader(reader, fileLen);
        this.header = MpqHeader.parse(reader.readAtOrEof(base, MpqHeader.MAX_HEADER_READ));
        this.version = MpqHeader.FormatVersion.fromRaw(header.formatVersion);
        this.warnings = new ArrayList<>();
        LoadedTables tables = loadTables();
        this.classic = tables.classic;
        this.het = tables.het;
        this.bet = tables.bet;
        this.sectorSize = tables.sectorSize;
        this.names = new ArrayList<>();
        loadListfile();
    }

    /** 从文件打开归档。 */
    public static MpqArchive open(Path path) {
        ByteReader.File file = new ByteReader.File(path);
        try {
            return new MpqArchive(file);
        } catch (MpqException | UncheckedIOException e) {
            file.close();
            throw e;
        }
    }

    /** 从内存字节打开归档。 */
    public static MpqArchive open(byte[] data) {
        return new MpqArchive(new ByteReader.Memory(data));
    }

    @Override
    public void close() {
        if (reader instanceof ByteReader.File f) {
            f.close();
        }
    }

    // -- 访问器 --------------------------------------------------------------

    /** MPQ 格式版本（1–4）。 */
    public int version() {
        return version.ordinal() + 1;
    }

    /** MPQ 格式版本枚举。 */
    public MpqHeader.FormatVersion formatVersion() {
        return version;
    }

    /** sector 大小（字节）。 */
    public int sectorSize() {
        return sectorSize;
    }

    /** 归档在所在文件中的偏移。 */
    public long archiveOffset() {
        return base;
    }

    /** 打开归档时发现的非致命问题。 */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 读取文件数据时遇到的非致命问题（最近 64 条）。 */
    public synchronized List<String> readWarnings() {
        return List.copyOf(readWarnings);
    }

    /** 来自 (listfile) 的文件名（缺失时为空）。 */
    public List<String> fileNames() {
        return List.copyOf(names);
    }

    /** 是否为 Rust 库自定义密码 AEAD 扩展保护的归档（本模块不支持）。 */
    public boolean isPasswordProtected() {
        return hasFile("(password)");
    }

    /** 归档中是否存在该名字的文件。 */
    public boolean hasFile(String name) {
        FileEntry e = findEntry(name);
        return e != null && e.exists();
    }

    /** 按名字查找文件；返回附带名字的条目。 */
    public Optional<FileEntry> findFile(String name) {
        FileEntry entry = findEntry(name);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new FileEntry(name, entry.locale(), entry.blockIndex(), entry.offset(),
                entry.compressedSize(), entry.size(), entry.flags(), entry.source()));
    }

    /** 按名字读取文件内容。 */
    public byte[] readFile(String name) {
        FileEntry entry = findEntry(name);
        if (entry == null) {
            throw new MpqException("归档中找不到文件：" + name);
        }
        // 附上名字：加密文件的密钥推导依赖归档内完整文件名。
        return readEntry(new FileEntry(name, entry.locale(), entry.blockIndex(), entry.offset(),
                entry.compressedSize(), entry.size(), entry.flags(), entry.source()));
    }

    /** 列出所有文件条目（已知名字的附带名字）。 */
    public List<FileEntry> entries() {
        List<FileEntry> result = new ArrayList<>();

        if (classic != null && classic.blockTable.length > 0) {
            // 经典表：遍历 hash 条目以正确处理 locale 与重复名字。
            boolean[] seen = new boolean[classic.blockTable.length];
            for (ClassicTables.HashEntry hash : classic.hashTable) {
                if (hash.isFree()) {
                    continue;
                }
                int idx = hash.blockIndex();
                if (Integer.compareUnsigned(idx, classic.blockTable.length) >= 0 || seen[idx]) {
                    continue;
                }
                seen[idx] = true;
                result.add(fileEntryFromClassic(idx, hash.locale()));
            }
        } else if (bet != null) {
            for (int i = 0; i < bet.entryCount; i++) {
                result.add(fileEntryFromBet(i));
            }
        }

        // 附上名字：(listfile) 条目优先，再是已知特殊文件。
        Map<Long, Integer> slotOf = new HashMap<>(result.size() * 2);
        for (int i = 0; i < result.size(); i++) {
            FileEntry e = result.get(i);
            slotOf.putIfAbsent(key(e.source(), e.blockIndex()), i);
        }
        List<String> candidates = new ArrayList<>(names);
        candidates.addAll(SPECIAL_FILES);
        for (String name : candidates) {
            FileEntry entry = findEntry(name);
            if (entry == null || !entry.exists()) {
                continue;
            }
            Integer slot = slotOf.get(key(entry.source(), entry.blockIndex()));
            if (slot != null && result.get(slot).name() == null) {
                FileEntry e = result.get(slot);
                result.set(slot, new FileEntry(name, e.locale(), e.blockIndex(), e.offset(),
                        e.compressedSize(), e.size(), e.flags(), e.source()));
            }
        }
        return result;
    }

    private static long key(TableSource source, int index) {
        return ((long) source.ordinal() << 32) | Integer.toUnsignedLong(index);
    }

    // -- 查找 ---------------------------------------------------------------

    private FileEntry findEntry(String name) {
        // 经典 hash table 优先（与 StormLib 一致）。
        if (classic != null && classic.hashTable.length > 0) {
            ClassicTables.HashEntry hash = classic.find(name);
            if (hash != null && Integer.compareUnsigned(hash.blockIndex(), classic.blockTable.length) < 0) {
                return fileEntryFromClassic(hash.blockIndex(), hash.locale());
            }
        }

        // HET+BET 回退路径。
        if (het != null && bet != null) {
            long fileHash = JenkinsHash.fileNameHash(name);
            long masked = (fileHash & het.andMask()) | het.orMask();
            int hash1 = (int) ((masked >>> (het.nameHashBitSize - 8)) & 0xFF);
            for (int index : het.lookup(fileHash)) {
                if (bet.fullNameHash(hash1, index) == masked) {
                    return fileEntryFromBet(index);
                }
            }
        }
        return null;
    }

    private FileEntry fileEntryFromClassic(int index, int locale) {
        ClassicTables.BlockEntry block = classic.blockTable[index];
        return new FileEntry(null, locale, index, classic.blockOffset(index),
                block.csize(), block.fsize(), block.flags(), TableSource.CLASSIC);
    }

    private FileEntry fileEntryFromBet(int index) {
        if (Integer.compareUnsigned(index, bet.entryCount) >= 0) {
            return null;
        }
        ExtTables.BetFileRecord rec = bet.record(index);
        return new FileEntry(null, 0, index, rec.filePos(), rec.cmpSize(), rec.fileSize(),
                rec.flags(), TableSource.BET);
    }

    // -- 读取 ---------------------------------------------------------------

    /** 按给定的 FileEntry（来自 entries）读取文件内容。线程安全。 */
    public byte[] readEntry(FileEntry entry) {
        if (!entry.exists()) {
            throw new MpqException("文件不存在：" + entry.displayName());
        }
        synchronized (this) {
            verifyChunkMd5s(entry);
        }

        if ((entry.flags() & FileEntry.FILE_PASSWORD_AEAD) != 0) {
            throw new MpqException(entry.displayName()
                    + " 使用 Rust mpq 库的密码 AEAD 扩展存储，本模块不支持（请在 Rust 端 unprotect 后再处理）");
        }

        int key = 0;
        if (entry.isEncrypted()) {
            if (entry.name() == null) {
                throw new MpqException("文件已加密且名字未知，无法推导解密密钥");
            }
            key = StormCrypt.fileKey(entry.name(), (entry.flags() & FileEntry.FILE_KEY_V2) != 0,
                    entry.offset(), entry.size());
        }

        long rawStart = base + entry.offset();

        if (entry.isSingleUnit()) {
            byte[] data = reader.readAtOrEof(rawStart, entry.compressedSize());
            if (entry.isEncrypted()) {
                StormCrypt.decryptBytes(data, key);
            }
            if (entry.isCompressed()) {
                if ((entry.flags() & FileEntry.FILE_COMPRESS) != 0) {
                    data = MpqCompression.decompressMasked(data, entry.size());
                } else if ((entry.flags() & FileEntry.FILE_IMPLODE) != 0) {
                    data = MpqCompression.decompressImploded(data, entry.size());
                }
            }
            return trimToSize(data, entry.size());
        }

        // 基于 sector 的文件。
        long sectorSizeL = Integer.toUnsignedLong(sectorSize);
        int nsec = (int) ((Integer.toUnsignedLong(entry.size()) + sectorSizeL - 1) / sectorSizeL);
        boolean compressed = entry.isCompressed();
        boolean encrypted = entry.isEncrypted();
        boolean hasCrc = (entry.flags() & FileEntry.FILE_SECTOR_CRC) != 0;

        // sector 偏移表只存在于压缩文件中。
        int[] offsets;
        if (compressed) {
            int tableDwords = nsec + 1 + (hasCrc ? nsec : 0);
            byte[] raw = reader.readAtOrEof(rawStart, tableDwords * 4);
            if (encrypted) {
                StormCrypt.decryptBytes(raw, key - 1);
            }
            offsets = new int[tableDwords];
            for (int i = 0; i < tableDwords; i++) {
                offsets[i] = (raw[i * 4] & 0xFF) | (raw[i * 4 + 1] & 0xFF) << 8
                        | (raw[i * 4 + 2] & 0xFF) << 16 | (raw[i * 4 + 3] & 0xFF) << 24;
            }
        } else {
            offsets = new int[0];
        }

        if (compressed) {
            // 与 StormLib 一致的健全性检查：偏移单调递增、sector 大小合理。
            for (int i = 0; i < nsec; i++) {
                int a = i < offsets.length ? offsets[i] : -1;           // u32::MAX 语义
                int b = i + 1 < offsets.length ? offsets[i + 1] : -1;
                if (Integer.compareUnsigned(b, a) < 0
                        || Long.compareUnsigned(Integer.toUnsignedLong(b) - Integer.toUnsignedLong(a),
                        Integer.toUnsignedLong(sectorSize)) > 0
                        || Integer.compareUnsigned(b, entry.compressedSize()) > 0) {
                    throw new MpqException("sector 偏移表不合理（已损坏的归档）");
                }
            }
        }

        byte[] out = new byte[entry.size()];
        int outPos = 0;
        for (int i = 0; i < nsec; i++) {
            long expected = Math.min(Integer.toUnsignedLong(entry.size()) - i * sectorSizeL, sectorSizeL);

            long rawOff;
            long rawLen;
            if (compressed) {
                long a = Integer.toUnsignedLong(offsets[i]);
                long b = Integer.toUnsignedLong(offsets[i + 1]);
                rawOff = a;
                rawLen = b - a;
            } else {
                rawOff = i * sectorSizeL;
                rawLen = expected;
            }

            if (rawLen == 0) {
                // 稀疏/缺失的 sector：以零填充。
                outPos = fillZeros(out, outPos, (int) expected);
                continue;
            }

            byte[] raw = reader.readAtOrEof(rawStart + rawOff, (int) rawLen);
            if (encrypted) {
                StormCrypt.decryptBytes(raw, key + i);
            }

            byte[] sector = raw;
            if (compressed && Integer.toUnsignedLong(raw.length) < expected) {
                if ((entry.flags() & FileEntry.FILE_COMPRESS) != 0) {
                    sector = MpqCompression.decompressMasked(raw, (int) expected);
                } else {
                    sector = MpqCompression.decompressImploded(raw, (int) expected);
                }
            }

            int take = (int) Math.min(sector.length, expected);
            if (sector.length < expected) {
                outPos = fillZeros(out, outPos, (int) expected - sector.length);
            }
            System.arraycopy(sector, 0, out, outPos, take);
            outPos += take;

            // 可选的逐 sector CRC：不匹配仅告警。
            if (hasCrc) {
                int crcIndex = nsec + 1 + i;
                if (crcIndex < offsets.length) {
                    int stored = offsets[crcIndex];
                    if (stored != 0 && stored != -1) {
                        CRC32 crc = new CRC32();
                        crc.update(sector, 0, take);
                        if ((int) crc.getValue() != stored) {
                            addReadWarning(entry.displayName() + ": sector " + i + " CRC 不匹配");
                        }
                    }
                }
            }
        }
        return trimToSize(out, entry.size());
    }

    private static int fillZeros(byte[] out, int pos, int count) {
        return pos + count; // out 已零初始化
    }

    private byte[] trimToSize(byte[] data, int size) {
        long want = Integer.toUnsignedLong(size);
        if (data.length == want) {
            return data;
        }
        if (data.length > want) {
            return Arrays.copyOf(data, (int) want);
        }
        // 解压结果短于声明大小：零填充（宽容处理，与 Rust 一致）。
        byte[] out = new byte[(int) want];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    private void addReadWarning(String msg) {
        synchronized (this) {
            if (readWarnings.size() < 64) {
                readWarnings.addLast(msg);
            }
        }
    }

    /** v4 raw-chunk 校验：文件存储数据后跟每块 MD5；不匹配仅告警。 */
    private void verifyChunkMd5s(FileEntry entry) {
        int chunk = header.rawChunkSize;
        if (version != MpqHeader.FormatVersion.V4 || chunk == 0 || entry.compressedSize() == 0
                || readWarnings.size() >= 64) {
            return;
        }
        long start = base + entry.offset();
        long rawLen = Integer.toUnsignedLong(entry.compressedSize());
        int count = (int) ((rawLen + chunk - 1) / chunk);
        int md5Array = (int) rawLen + count * 16;
        if (start + md5Array > fileLen) {
            return;
        }
        byte[] all = reader.readAtOrEof(start, md5Array);
        for (int i = 0; i < count; i++) {
            int from = i * chunk;
            int to = (int) Math.min((long) (i + 1) * chunk, rawLen);
            byte[] expected = MpqHeader.ArraysSlice.of(all, (int) rawLen + i * 16, 16);
            if (!Arrays.equals(MpqHeader.md5(MpqHeader.ArraysSlice.of(all, from, to - from)), expected)) {
                addReadWarning(entry.displayName() + ": raw chunk " + i + " MD5 不匹配");
            }
        }
    }

    // -- 打开流程 ------------------------------------------------------------

    private record LoadedTables(ClassicTables classic, ExtTables.HetTable het, ExtTables.BetTable bet,
                                int sectorSize) {
    }

    /** 在流中查找 MPQ 头所在的字节偏移。 */
    private static long locateHeader(ByteReader reader, long fileLen) {
        if (fileLen < 0x20) {
            throw new MpqException("文件中找不到 MPQ 归档头部");
        }

        byte[] probe = reader.readAtOrEof(0, MpqHeader.MAX_HEADER_READ);
        if (leU32(probe, 0) == MpqHeader.MPQ_MAGIC) {
            return 0;
        }

        MpqHeader.UserDataHeader ud = MpqHeader.UserDataHeader.parse(probe);
        if (ud != null) {
            long target = Integer.toUnsignedLong(ud.archiveOffset());
            if (target + 0x20 <= fileLen) {
                byte[] at = reader.readAtOrEof(target, MpqHeader.MAX_HEADER_READ);
                if (leU32(at, 0) == MpqHeader.MPQ_MAGIC) {
                    return target;
                }
            }
        }

        // 扫描内嵌归档（按 0x200 对齐，与 storm.dll 相同；上限 128 MB）。
        long limit = Math.min(fileLen, 0x0800_0000L);
        final int chunk = 0x10_0000;
        long offset = 0;

        outer:
        while (offset + 0x20 <= limit) {
            int want = (int) Math.min(limit - offset, chunk);
            byte[] w = reader.readAtOrEof(offset, want + 0x200);

            int inOff = 0;
            while (inOff + 0x20 <= want) {
                int magic = leU32(w, inOff);
                if (magic == MpqHeader.USERDATA_MAGIC) {
                    MpqHeader.UserDataHeader ud2 = MpqHeader.UserDataHeader.parse(
                            MpqHeader.ArraysSlice.of(w, inOff, Math.min(16, w.length - inOff)));
                    if (ud2 != null) {
                        long target = offset + inOff + Integer.toUnsignedLong(ud2.archiveOffset());
                        if (target + 0x20 <= fileLen) {
                            byte[] at = reader.readAtOrEof(target, MpqHeader.MAX_HEADER_READ);
                            if (leU32(at, 0) == MpqHeader.MPQ_MAGIC) {
                                return target;
                            }
                        }
                    }
                } else if (magic == MpqHeader.MPQ_MAGIC) {
                    try {
                        MpqHeader h = MpqHeader.parse(MpqHeader.ArraysSlice.of(w, inOff,
                                Math.min(MpqHeader.MAX_HEADER_READ, w.length - inOff)));
                        if (h.plausible()) {
                            return offset + inOff;
                        }
                    } catch (MpqException ignored) {
                        // 候选不成立，继续扫描。
                    }
                }
                inOff += 0x200;
            }
            if (want < chunk) {
                break outer;
            }
            offset += chunk;
        }

        throw new MpqException("文件中找不到 MPQ 归档头部");
    }

    private static int leU32(byte[] buf, int off) {
        return (buf[off] & 0xFF) | (buf[off + 1] & 0xFF) << 8
                | (buf[off + 2] & 0xFF) << 16 | (buf[off + 3] & 0xFF) << 24;
    }

    /** 加载 hash/block/hi-block 与 HET/BET 表，逐表容错降级为警告。 */
    private LoadedTables loadTables() {
        long archiveEnd = base + header.archiveSize(fileLen - Math.min(base, fileLen));

        // v4：头部自带 0x00..0xC0 的 MD5，不匹配仅告警。
        if (version == MpqHeader.FormatVersion.V4
                && MpqHeader.md5Present(header.md5Header)
                && base + MpqHeader.HEADER_MD5_SPAN <= fileLen) {
            byte[] raw = reader.readAtOrEof(base, MpqHeader.HEADER_MD5_SPAN);
            if (!Arrays.equals(MpqHeader.md5(raw), header.md5Header)) {
                warnings.add("v4 头部 MD5 不匹配（0x00-0xC0）");
            }
        }

        long[] stored = storedTableSizes(archiveEnd);
        Long hashStored = stored[0] >= 0 ? stored[0] : null;
        Long blockStored = stored[1] >= 0 ? stored[1] : null;

        List<ClassicTables.HashEntry> hashTable = new ArrayList<>();
        List<ClassicTables.BlockEntry> blockTable = new ArrayList<>();
        List<Short> hiBlock = new ArrayList<>();

        if (header.hashTableSize != 0) {
            long tableSize = Integer.toUnsignedLong(header.hashTableSize) * 16;
            try {
                byte[] buf = ClassicTables.loadMpqTable(reader, base + header.hashTablePos,
                        hashStored, (int) Math.min(tableSize, Integer.MAX_VALUE), StormCrypt.KEY_HASH_TABLE);
                hashTable.addAll(Arrays.asList(ClassicTables.parseHashTable(buf)));
                verifyStoredMd5("hash table", base + header.hashTablePos,
                        hashStored != null ? hashStored : tableSize, header.md5HashTable);
            } catch (MpqException e) {
                warnings.add("hash table 不可用：" + e.getMessage());
            }
        }

        if (header.blockTableSize != 0) {
            long tableSize = Integer.toUnsignedLong(header.blockTableSize) * 16;
            try {
                byte[] buf = ClassicTables.loadMpqTable(reader, base + header.blockTablePos,
                        blockStored, (int) Math.min(tableSize, Integer.MAX_VALUE), StormCrypt.KEY_BLOCK_TABLE);
                blockTable.addAll(Arrays.asList(ClassicTables.parseBlockTable(buf)));
                verifyStoredMd5("block table", base + header.blockTablePos,
                        blockStored != null ? blockStored : tableSize, header.md5BlockTable);
            } catch (MpqException e) {
                warnings.add("block table 不可用：" + e.getMessage());
            }
        }

        if (header.hiBlockTablePos != 0 && !blockTable.isEmpty()) {
            try {
                byte[] buf = reader.readAtOrEof(base + header.hiBlockTablePos, blockTable.size() * 2);
                verifyStoredMd5("hi-block table", base + header.hiBlockTablePos,
                        blockTable.size() * 2L, header.md5HiBlockTable);
                for (int i = 0; i + 1 < buf.length; i += 2) {
                    hiBlock.add((short) ((buf[i] & 0xFF) | (buf[i + 1] & 0xFF) << 8));
                }
            } catch (MpqException e) {
                warnings.add("hi-block table 不可读：" + e.getMessage());
            }
        }

        ClassicTables classicTables = null;
        if (!hashTable.isEmpty() || !blockTable.isEmpty()) {
            classicTables = new ClassicTables(
                    hashTable.toArray(new ClassicTables.HashEntry[0]),
                    blockTable.toArray(new ClassicTables.BlockEntry[0]),
                    toShortArray(hiBlock));
        }

        // HET/BET 表（v3+）。
        ExtTables.HetTable hetTable = null;
        ExtTables.BetTable betTable = null;
        if (version.ordinal() >= MpqHeader.FormatVersion.V3.ordinal()) {
            if (header.hetTablePos != 0) {
                long size = hetStoredSize(archiveEnd);
                try {
                    byte[] buf = ExtTables.loadExtTable(reader, base + header.hetTablePos,
                            size, ExtTables.HET_SIGNATURE, StormCrypt.KEY_HASH_TABLE);
                    hetTable = ExtTables.HetTable.parse(buf);
                    verifyStoredMd5("HET table", base + header.hetTablePos, size, header.md5HetTable);
                } catch (MpqException e) {
                    warnings.add("HET table 不可用：" + e.getMessage());
                }
            }
            if (header.betTablePos != 0) {
                long size = betStoredSize(archiveEnd);
                try {
                    byte[] buf = ExtTables.loadExtTable(reader, base + header.betTablePos,
                            size, ExtTables.BET_SIGNATURE, StormCrypt.KEY_BLOCK_TABLE);
                    betTable = ExtTables.BetTable.parse(buf);
                    verifyStoredMd5("BET table", base + header.betTablePos, size, header.md5BetTable);
                } catch (MpqException e) {
                    warnings.add("BET table 不可用：" + e.getMessage());
                }
            }
        }

        // 可用性检查：至少一个哈希索引 + 一个文件表。
        boolean hasIndex = (classicTables != null && classicTables.hashTable.length > 0) || hetTable != null;
        boolean hasFiles = (classicTables != null && classicTables.blockTable.length > 0) || betTable != null;
        if (!hasIndex) {
            throw new MpqException("没有可用的哈希索引（经典 hash 表或 HET 表）");
        }
        if (!hasFiles) {
            throw new MpqException("没有可用的文件表（经典 block 表或 BET 表）");
        }
        if ((classicTables == null || classicTables.hashTable.length == 0)
                && hetTable != null && betTable == null) {
            throw new MpqException("存在 HET 表但缺少 BET 表");
        }

        int sectorSize = header.sectorSize();
        if (sectorSize == 0) {
            sectorSize = 512;
            warnings.add("无效的 sector 指数，按 512 字节 sector 处理");
        }
        return new LoadedTables(classicTables, hetTable, betTable, sectorSize);
    }

    private static short[] toShortArray(List<Short> list) {
        short[] out = new short[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** 经典表在磁盘上的大小：v1 用未压缩大小，v2/v3 由表间隙推导，v4 直接存储。 */
    private long[] storedTableSizes(long archiveEnd) {
        long hashSize = Integer.toUnsignedLong(header.hashTableSize) * 16;
        long blockSize = Integer.toUnsignedLong(header.blockTableSize) * 16;

        return switch (version) {
            case V1 -> new long[]{-1, -1};
            case V4 -> new long[]{
                    header.hashTableSize64 != 0 && header.hashTableSize64 <= hashSize
                            ? header.hashTableSize64 : -1,
                    header.blockTableSize64 != 0 && header.blockTableSize64 <= blockSize
                            ? header.blockTableSize64 : -1};
            case V2, V3 -> {
                long hashPos = base + header.hashTablePos;
                long blockPos = base + header.blockTablePos;
                long hiPos = header.hiBlockTablePos != 0 ? base + header.hiBlockTablePos : archiveEnd;

                long hashStored = header.hashTablePos != 0 && blockPos > hashPos
                        ? Math.min(blockPos - hashPos, hashSize) : -1;
                long blockStored = header.blockTablePos != 0 && hiPos > blockPos
                        ? Math.min(hiPos - blockPos, blockSize) : -1;
                yield new long[]{hashStored, blockStored};
            }
        };
    }

    private long hetStoredSize(long archiveEnd) {
        if (version == MpqHeader.FormatVersion.V4) {
            return header.hetTableSize64;
        }
        long upper = lowerTableBoundary(archiveEnd);
        return Math.max(0, upper - header.hetTablePos);
    }

    private long betStoredSize(long archiveEnd) {
        if (version == MpqHeader.FormatVersion.V4) {
            return header.betTableSize64;
        }
        // BET 紧贴下一个边界（hash table 或归档结尾）之下。
        long upper = header.hashTablePos != 0 ? header.hashTablePos : lowerTableBoundary(archiveEnd);
        return Math.max(0, upper - header.betTablePos);
    }

    /** 各上界（hash / block / hi-block / 归档结尾）中的最小值。 */
    private long lowerTableBoundary(long archiveEnd) {
        long boundary = archiveEnd;
        if (header.hashTablePos != 0) {
            boundary = Math.min(boundary, header.hashTablePos);
        }
        if (header.blockTablePos != 0) {
            boundary = Math.min(boundary, header.blockTablePos);
        }
        if (header.hiBlockTablePos != 0) {
            boundary = Math.min(boundary, header.hiBlockTablePos);
        }
        return boundary;
    }

    /** 针对存储后的字节校验 v4 表 MD5；不匹配仅告警。 */
    private void verifyStoredMd5(String what, long offset, long stored, byte[] expected) {
        if (version != MpqHeader.FormatVersion.V4 || !MpqHeader.md5Present(expected) || stored == 0) {
            return;
        }
        if (offset >= fileLen || fileLen - offset < stored) {
            return;
        }
        byte[] raw = reader.readAtOrEof(offset, (int) Math.min(stored, Integer.MAX_VALUE));
        if (!Arrays.equals(MpqHeader.md5(raw), expected)) {
            warnings.add(what + ": v4 表 MD5 不匹配");
        }
    }

    /** 读取 (listfile)（可选）。 */
    private void loadListfile() {
        if (hasFile("(listfile)")) {
            try {
                byte[] data = readFile("(listfile)");
                String text = new String(data, StandardCharsets.UTF_8);
                LinkedHashSet<String> seen = new LinkedHashSet<>();
                for (String part : text.split("[;\r\n]")) {
                    String s = part.trim();
                    if (!s.isEmpty()) {
                        seen.add(s);
                    }
                }
                names.addAll(seen);
            } catch (MpqException e) {
                warnings.add("(listfile) 存在但不可读：" + e.getMessage());
            }
        }
        if (names.isEmpty()) {
            warnings.add("缺少 (listfile)，文件名未知");
        }
    }
}
