package com.nineyin.tools.mpq;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MPQ 归档写入：创建空归档、导入（新增/替换）与删除文件、版本转换。
 * 移植自 Rust mpq 库 create.rs / modify.rs。
 *
 * <p>策略与 Rust 端一致：保留文件的已存储字节原样复制（压缩方式、
 * sector CRC、加密状态不变），只有容器被重写；新文件以 zlib 编码
 * （小文件单块 single-unit，永不加密）；(listfile) 重建，
 * (attributes)/(signature) 丢弃；v4 头会重算全部 MD5 校验和；
 * 归档前后的字节（MPQ\x1B 包装、内嵌前缀、尾部附加）保留。
 * 不支持 Rust 库自定义的密码 AEAD 扩展（遇保护归档明确报错）。
 */
public final class MpqWriter {

    /** 导入时不可使用的内部文件名（由修改器 / 格式本身管理）。 */
    private static final List<String> FORBIDDEN_IMPORT_NAMES = List.of(
            "(listfile)", "(attributes)", "(signature)", "(userdata)", "(password)");

    private MpqWriter() {
    }

    // == 选项与结果 ==========================================================

    /** 创建空归档的配置。 */
    public record CreateOptions(MpqHeader.FormatVersion version, int hashTableSize, int sectorShift) {

        public static CreateOptions defaults() {
            return new CreateOptions(MpqHeader.FormatVersion.V1, 1024, 3);
        }

        public static CreateOptions ofVersion(MpqHeader.FormatVersion version) {
            return new CreateOptions(version, 1024, 3);
        }
    }

    /** 一次请求的删除操作。 */
    public sealed interface Deletion {

        /** 删除所有名字匹配的条目（MPQ 规范化：不分大小写与斜杠方向）。 */
        record ByName(String name) implements Deletion {
        }

        /** 精确删除一个表条目（寻址无名条目的唯一途径）。 */
        record ByKey(TableSource source, int index) implements Deletion {
        }
    }

    /** modify 的可调选项。 */
    public record ModifyOptions(boolean reencryptKeyV2, MpqHeader.FormatVersion targetVersion) {

        public static ModifyOptions defaults() {
            return new ModifyOptions(true, null);
        }
    }

    /** 一次 modify 运行所做的变更。 */
    public record ModifyReport(List<String> added, List<String> replaced, List<String> deleted,
                               List<String> dropped, List<String> reEncoded) {

        static ModifyReport empty() {
            return new ModifyReport(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>());
        }
    }

    /** modify 运行成功的结果。 */
    public record ModifyResult(byte[] bytes, ModifyReport report) {
    }

    // == 创建 ================================================================

    /** 构建一个新的空 MPQ 归档（内存字节）。 */
    public static byte[] create(CreateOptions options) {
        if (options.sectorShift() > 30) {
            throw new MpqException("无效的扇区指数 " + options.sectorShift() + "（应在 0–30）");
        }
        int hashCount = normalizeHashSize(options.hashTableSize());
        byte[] bytes = emptyArchive(options.version(), hashCount, options.sectorShift());
        // 自检：空容器必须能以所请求的版本打开，且不含任何条目。
        try (MpqArchive check = MpqArchive.open(bytes)) {
            if (check.version() != options.version().ordinal() + 1 || !check.entries().isEmpty()) {
                throw new MpqException("新建归档自检失败");
            }
        }
        return bytes;
    }

    /** 将哈希表大小收敛为合法的 2 的幂（4..=1Mi 项）。 */
    static int normalizeHashSize(int requested) {
        long clamped = Math.max(4, Math.min(requested, 1 << 20));
        return (int) nextPow2((int) clamped);
    }

    /** 组装空容器：头部 | 加密的全空闲哈希表 | 加密的单空闲块表。 */
    static byte[] emptyArchive(MpqHeader.FormatVersion version, int hashCount, int sectorShift) {
        ByteBuffer freeEntry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        freeEntry.putInt(0xFFFF_FFFF);
        freeEntry.putInt(0xFFFF_FFFF);
        freeEntry.position(12);
        freeEntry.putInt(ClassicTables.HASH_ENTRY_FREE);
        byte[] free = freeEntry.array();

        ByteBuffer hashBuf = ByteBuffer.allocate(hashCount * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < hashCount; i++) {
            hashBuf.put(free);
        }
        byte[] hashBytes = hashBuf.array();
        StormCrypt.encryptBytes(hashBytes, StormCrypt.KEY_HASH_TABLE);

        // 一个空闲块项（全零）：占位用的未使用块。
        byte[] blockBytes = new byte[16];
        StormCrypt.encryptBytes(blockBytes, StormCrypt.KEY_BLOCK_TABLE);

        int hashPos = version.headerSize();
        int blockPos = hashPos + hashBytes.length;
        long total = blockPos + (long) blockBytes.length;
        byte[] header = MpqHeader.writeHeader(version, sectorShift, hashPos, hashCount,
                blockPos, 1, total);
        if (version == MpqHeader.FormatVersion.V4) {
            MpqHeader.finishV4Header(header, hashBytes, blockBytes);
        }

        ByteBuffer out = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(header);
        out.put(hashBytes);
        out.put(blockBytes);
        return out.array();
    }

    // == 修改 ================================================================

    /** 向归档导入和/或删除条目，返回完整的修改后字节（简单选项）。 */
    public static ModifyResult modify(byte[] data, List<Deletion> deletions,
                                      LinkedHashMap<String, byte[]> additions) {
        return modify(data, deletions, additions, ModifyOptions.defaults());
    }

    /** 将归档容器转换为另一种头版本（v1–v4）。 */
    public static ModifyResult convert(byte[] data, MpqHeader.FormatVersion target) {
        return modify(data, List.of(), new LinkedHashMap<>(), new ModifyOptions(true, target));
    }

    /** 带显式选项的 modify。 */
    public static ModifyResult modify(byte[] data, List<Deletion> deletions,
                                      LinkedHashMap<String, byte[]> additions,
                                      ModifyOptions options) {
        MpqArchive archive = MpqArchive.open(data);
        try {
            return modifyInner(archive, data, deletions, additions, options);
        } finally {
            archive.close();
        }
    }

    // == modify 主流程 =======================================================

    /** 经典 hash 条目字段，用于按名字（重新）锚定的块。 */
    private record HashFields(int name1, int name2, int locale, byte platform) {

        static HashFields of(String name) {
            return new HashFields(StormCrypt.hashString(name, StormCrypt.HASH_NAME_A),
                    StormCrypt.hashString(name, StormCrypt.HASH_NAME_B), 0, (byte) 0);
        }
    }

    /** 块写入新归档的方式。 */
    private sealed interface Payload {
        record Raw(byte[] bytes, int flags) implements Payload {
        }

        record Fresh(byte[] data) implements Payload {
        }

        record FreshEncoded(byte[] bytes, int flags) implements Payload {
        }

        record ReEncrypted(String name, byte[] data) implements Payload {
        }
    }

    private record OutBlock(Payload payload, int fsize, String name,
                            List<HashFields> hashes, boolean classicRemap) {
    }

    private record Placed(long pos, byte[] bytes, int flags) {
    }

    private record SrcBlock(TableSource source, int index, long offset, int csize,
                            int fsize, int flags, String name) {
    }

    private static ModifyResult modifyInner(MpqArchive archive, byte[] data, List<Deletion> deletions,
                                            LinkedHashMap<String, byte[]> additions,
                                            ModifyOptions options) {
        int base = (int) archive.archiveOffset();
        int sectorSize = archive.sectorSize();
        MpqHeader.FormatVersion sourceVersion = archive.formatVersion();

        if (archive.isPasswordProtected()) {
            throw new MpqException("归档启用了 Rust mpq 库的密码 AEAD 保护，本模块不支持修改"
                    + "（请先在 Rust 端 unprotect）");
        }

        MpqHeader.FormatVersion version = options.targetVersion() != null
                ? options.targetVersion() : sourceVersion;
        boolean converting = options.targetVersion() != null && options.targetVersion() != sourceVersion;
        int sectorShift = archive.header.blockSize;

        if (!converting && deletions.isEmpty() && additions.isEmpty()) {
            return new ModifyResult(data, ModifyReport.empty());
        }

        // --- 规范化导入项（后面的重复项覆盖先前的） ---
        List<Map.Entry<String, byte[]>> adds = new ArrayList<>();
        for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
            String name = sanitizeName(addition.getKey());
            int existing = -1;
            for (int i = 0; i < adds.size(); i++) {
                if (StormCrypt.sameName(adds.get(i).getKey(), name)) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                adds.set(existing, Map.entry(name, addition.getValue()));
            } else {
                adds.add(Map.entry(name, addition.getValue()));
            }
        }

        // --- 快照源表 ---
        Map<Long, String> nameOf = new HashMap<>();
        for (FileEntry e : archive.entries()) {
            if (e.name() != null) {
                nameOf.put(((long) e.source().ordinal() << 32) | Integer.toUnsignedLong(e.blockIndex()),
                        e.name());
            }
        }

        ClassicTables classic = archive.classic;
        ExtTables.BetTable bet = archive.bet;
        boolean useClassicBlocks = classic != null && classic.blockTable.length > 0;

        // 经典 hash 条目按指向块分组（仅占用项）—— 匹配无名但可寻址的文件。
        Map<Integer, List<ClassicTables.HashEntry>> hashByBlock = new HashMap<>();
        if (classic != null) {
            for (ClassicTables.HashEntry entry : classic.hashTable) {
                if (!entry.isFree()) {
                    hashByBlock.computeIfAbsent(entry.blockIndex(), k -> new ArrayList<>()).add(entry);
                }
            }
        }

        // --- 枚举源块 ---
        List<SrcBlock> src = new ArrayList<>();
        if (useClassicBlocks) {
            for (int i = 0; i < classic.blockTable.length; i++) {
                ClassicTables.BlockEntry block = classic.blockTable[i];
                if ((block.flags() & FileEntry.FILE_EXISTS) == 0) {
                    continue; // 幽灵/空闲条目
                }
                src.add(new SrcBlock(TableSource.CLASSIC, i, classic.blockOffset(i),
                        block.csize(), block.fsize(), block.flags(), nameOf.get(key(TableSource.CLASSIC, i))));
            }
        } else if (bet != null) {
            for (int i = 0; i < bet.entryCount; i++) {
                ExtTables.BetFileRecord rec = bet.record(i);
                if ((rec.flags() & FileEntry.FILE_EXISTS) == 0) {
                    continue;
                }
                src.add(new SrcBlock(TableSource.BET, i, rec.filePos(), rec.cmpSize(),
                        rec.fileSize(), rec.flags(), nameOf.get(key(TableSource.BET, i))));
            }
        }

        // --- 决定丢弃哪些源块 ---
        HashSet<Long> droppedKeys = new HashSet<>();
        ModifyReport report = ModifyReport.empty();

        for (Deletion del : deletions) {
            if (del instanceof Deletion.ByName byName) {
                if (StormCrypt.sameName(byName.name(), "(password)")) {
                    throw new MpqException("不能删除 (password)（密码数据）");
                }
                for (SrcBlock b : src) {
                    if (b.name() != null && StormCrypt.sameName(b.name(), byName.name())
                            && droppedKeys.add(key(b.source(), b.index()))) {
                        report.deleted().add(b.name());
                    }
                }
                // 注：无名条目无法按名匹配，由下方归档查找覆盖。
                // 无名但可寻址的条目由归档查找覆盖。
                archive.findFile(byName.name()).ifPresent(e -> {
                    if (droppedKeys.add(key(e.source(), e.blockIndex()))) {
                        report.deleted().add(byName.name());
                    }
                });
            } else if (del instanceof Deletion.ByKey byKey) {
                for (SrcBlock b : src) {
                    if (b.source() == byKey.source() && b.index() == byKey.index()) {
                        report.deleted().add(b.name() != null ? b.name() : "<unnamed #" + b.index() + ">");
                        droppedKeys.add(key(b.source(), b.index()));
                    }
                }
            }
        }

        // 由修改器管理或因修改而失效的内部文件。
        for (SrcBlock b : src) {
            if (b.name() == null) {
                continue;
            }
            if (b.name().equals("(attributes)") || b.name().equals("(signature)")) {
                report.dropped().add(b.name());
                droppedKeys.add(key(b.source(), b.index()));
            } else if (b.name().equals("(listfile)")) {
                // 总是在下方重建；从不复制。
                droppedKeys.add(key(b.source(), b.index()));
            }
        }

        // 导入项替换匹配条目 —— 按名字、按经典 hash 对、或经归档查找。
        for (Map.Entry<String, byte[]> add : adds) {
            String name = add.getKey();
            byte[] norm = StormCrypt.normalizeName(name);
            int pair1 = StormCrypt.hashBytes(norm, StormCrypt.HASH_NAME_A);
            int pair2 = StormCrypt.hashBytes(norm, StormCrypt.HASH_NAME_B);
            boolean replacedAny = false;
            for (SrcBlock b : src) {
                boolean nameMatch = b.name() != null && StormCrypt.sameName(b.name(), name);
                List<ClassicTables.HashEntry> entries = hashByBlock.get(b.index());
                boolean hashMatch = b.source() == TableSource.CLASSIC && b.name() == null
                        && entries != null
                        && entries.stream().anyMatch(e -> e.name1() == pair1 && e.name2() == pair2);
                if (nameMatch || hashMatch) {
                    droppedKeys.add(key(b.source(), b.index()));
                    replacedAny = true;
                }
            }
            var found = archive.findFile(name);
            if (found.isPresent() && droppedKeys.add(key(found.get().source(), found.get().blockIndex()))) {
                replacedAny = true;
            }
            if (replacedAny) {
                report.replaced().add(name);
            } else {
                report.added().add(name);
            }
        }

        // --- 构建输出块 ---
        List<OutBlock> blocks = new ArrayList<>();
        Map<Integer, Integer> remap = new HashMap<>();
        List<Map.Entry<String, byte[]>> reencoded = new ArrayList<>();

        for (SrcBlock b : src) {
            if (droppedKeys.contains(key(b.source(), b.index()))) {
                continue;
            }
            int start = base + (int) Math.min(b.offset(), Integer.MAX_VALUE);
            int end = Math.min(start + b.csize(), data.length);
            byte[] raw = Arrays.copyOfRange(data, Math.min(start, end), end);

            if ((b.flags() & FileEntry.FILE_PASSWORD_AEAD) != 0) {
                throw new MpqException("源归档包含密码 AEAD 块（" + b.name()
                        + "），本模块不支持；请先在 Rust 端 unprotect");
            }

            Payload payload;
            if ((b.flags() & FileEntry.FILE_KEY_V2) != 0 && b.name() != null) {
                // key-v2 密钥派生自块偏移，而偏移会变化：从解码内容重新编码。
                try {
                    FileEntry entry = new FileEntry(b.name(), 0, b.index(), b.offset(),
                            b.csize(), b.fsize(), b.flags(), b.source());
                    byte[] content = archive.readEntry(entry);
                    report.reEncoded().add(b.name());
                    reencoded.add(Map.entry(b.name(), content));
                    payload = options.reencryptKeyV2()
                            ? new Payload.ReEncrypted(b.name(), content)
                            : (Payload) new Payload.Fresh(content);
                } catch (MpqException e) {
                    // 源中本就无法读取；原样复制保持现状。
                    payload = new Payload.Raw(raw, b.flags());
                }
            } else {
                payload = new Payload.Raw(raw, b.flags());
            }

            List<HashFields> hashes = new ArrayList<>();
            if (b.source() == TableSource.CLASSIC) {
                List<ClassicTables.HashEntry> entries = hashByBlock.get(b.index());
                if (entries != null) {
                    for (ClassicTables.HashEntry e : entries) {
                        hashes.add(new HashFields(e.name1(), e.name2(), e.locale(), e.platform()));
                    }
                }
            } else if (b.name() != null) {
                hashes.add(HashFields.of(b.name()));
            }
            if (b.source() == TableSource.CLASSIC) {
                remap.put(b.index(), blocks.size());
            }
            blocks.add(new OutBlock(payload, b.fsize(), b.name(), hashes, b.source() == TableSource.CLASSIC));
        }

        for (Map.Entry<String, byte[]> add : adds) {
            blocks.add(new OutBlock(new Payload.Fresh(add.getValue()), add.getValue().length,
                    add.getKey(), List.of(HashFields.of(add.getKey())), false));
        }

        // 重建的 listfile：除内部文件外的所有保留 / 新增名字。
        List<String> listfileNames = blocks.stream().map(OutBlock::name).filter(Objects::nonNull)
                .filter(n -> FORBIDDEN_IMPORT_NAMES.stream().noneMatch(f -> f.equals(n)))
                .toList();
        if (!listfileNames.isEmpty()) {
            byte[] content = String.join("\r\n", listfileNames).getBytes(StandardCharsets.UTF_8);
            blocks.add(new OutBlock(new Payload.Fresh(content), content.length, "(listfile)",
                    List.of(HashFields.of("(listfile)")), false));
        }

        // 转换不得静默产生孤儿文件（无名且无法重新锚定的 BET 条目）。
        if (converting) {
            long orphaned = blocks.stream()
                    .filter(b -> b.name() == null && b.hashes().isEmpty() && !b.classicRemap())
                    .count();
            if (orphaned > 0) {
                throw new MpqException("无法转换：源归档使用 HET/BET 表且缺少 (listfile)，"
                        + orphaned + " 个文件的名字无法恢复，转为经典表后将无法按名访问"
                        + "（可先解包再以目标版本重建）");
            }
        }

        if (blocks.isEmpty()) {
            // 零文件的源连重建的 listfile 都无法承载；纯转换退化为空目标归档。
            if (converting && adds.isEmpty()) {
                int hashCount = normalizeHashSize(Math.max(archive.header.hashTableSize, 4));
                byte[] inner = emptyArchive(version, hashCount, sectorShift);
                byte[] out = new byte[base + inner.length];
                System.arraycopy(data, 0, out, 0, base);
                System.arraycopy(inner, 0, out, base, inner.length);
                long oldEnd = base + archive.header.archiveSize(data.length - base);
                byte[] out2 = appendTail(out, data, oldEnd);
                try (MpqArchive check = MpqArchive.open(out2)) {
                    if (check.version() != version.ordinal() + 1) {
                        throw new MpqException("转换校验失败：版本不一致");
                    }
                }
                return new ModifyResult(out2, report);
            }
            throw new MpqException("修改后归档不包含任何文件；至少需要保留一个条目");
        }

        // --- 布局与编码 ---
        long headerSize = version.headerSize();
        List<Placed> placed = new ArrayList<>(blocks.size());

        // Fresh 块的编码不依赖位置，先全部预编码。
        for (int i = 0; i < blocks.size(); i++) {
            OutBlock b = blocks.get(i);
            if (b.payload() instanceof Payload.Fresh fresh) {
                Encoded encoded = encodeFresh(fresh.data(), sectorSize);
                blocks.set(i, new OutBlock(new Payload.FreshEncoded(encoded.bytes(), encoded.flags()),
                        b.fsize(), b.name(), b.hashes(), b.classicRemap()));
            }
        }

        long cursor = headerSize;
        for (int i = 0; i < blocks.size(); i++) {
            OutBlock b = blocks.get(i);
            long pos = cursor;
            byte[] bytes;
            int flags;
            if (b.payload() instanceof Payload.Raw raw) {
                bytes = raw.bytes();
                flags = raw.flags();
            } else if (b.payload() instanceof Payload.FreshEncoded fe) {
                bytes = fe.bytes();
                flags = fe.flags();
            } else if (b.payload() instanceof Payload.ReEncrypted re) {
                Encoded encoded = encodeFreshReencrypted(re.name(), re.data(), sectorSize, pos);
                bytes = encoded.bytes();
                flags = encoded.flags();
            } else {
                throw new IllegalStateException("不可达的 payload 状态");
            }
            cursor += bytes.length;
            cursor = (cursor + 3) & ~3L; // dword 对齐
            placed.add(new Placed(pos, bytes, flags));
        }

        // --- hash table ---
        // 名字未知的条目只有在原表槽位布局保留时才能存活。
        boolean slotPreservation = classic != null && classic.hashTable.length > 0
                && Arrays.stream(classic.hashTable).anyMatch(e -> !e.isFree()
                && src.stream().anyMatch(b -> b.source() == TableSource.CLASSIC
                && b.index() == e.blockIndex()
                && !droppedKeys.contains(key(b.source(), b.index()))
                && b.name() == null));

        ClassicTables.HashEntry[] hashEntries;
        if (slotPreservation) {
            hashEntries = classic.hashTable.clone();
            for (int i = 0; i < hashEntries.length; i++) {
                ClassicTables.HashEntry e = hashEntries[i];
                if (e.isFree()) {
                    continue;
                }
                Integer mapped = remap.get(e.blockIndex());
                hashEntries[i] = new ClassicTables.HashEntry(e.name1(), e.name2(), e.locale(),
                        e.platform(), e.flags(),
                        mapped != null ? mapped : ClassicTables.HASH_ENTRY_DELETED);
            }
        } else {
            int inserts = blocks.stream().mapToInt(b -> b.hashes().size()).sum();
            hashEntries = newHashEntries(Math.max(4, nextPow2(inserts * 2)));
        }
        for (int i = 0; i < blocks.size(); i++) {
            OutBlock b = blocks.get(i);
            if (slotPreservation && b.classicRemap()) {
                continue; // 已由重映射后的原始槽位覆盖
            }
            if (b.name() == null) {
                continue;
            }
            for (HashFields fields : b.hashes()) {
                if (!insertIntoHash(hashEntries, b.name(), fields, i)) {
                    throw new MpqException("原始哈希表已满，无法插入新文件（且缺少名单无法重建更大的表）");
                }
            }
        }

        // --- 序列化表 ---
        ByteBuffer hashBuf = ByteBuffer.allocate(hashEntries.length * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (ClassicTables.HashEntry e : hashEntries) {
            e.serialize(hashBuf);
        }
        byte[] hashBytes = hashBuf.array();
        StormCrypt.encryptBytes(hashBytes, StormCrypt.KEY_HASH_TABLE);

        ByteBuffer blockBuf = ByteBuffer.allocate(placed.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < blocks.size(); i++) {
            Placed p = placed.get(i);
            if (p.pos() > 0xFFFF_FFFFL) {
                throw new MpqException("归档超过 4 GB，无法用 32 位偏移写出");
            }
            blockBuf.putInt((int) p.pos());
            blockBuf.putInt(p.bytes().length);
            blockBuf.putInt(blocks.get(i).fsize());
            blockBuf.putInt(p.flags() | FileEntry.FILE_EXISTS);
        }
        byte[] blockBytes = blockBuf.array();
        StormCrypt.encryptBytes(blockBytes, StormCrypt.KEY_BLOCK_TABLE);

        long hashPos = cursor;
        cursor += hashBytes.length;
        long blockPos = cursor;
        cursor += blockBytes.length;
        if (cursor > 0xFFFF_FFFFL) {
            throw new MpqException("归档超过 4 GB，无法写出");
        }

        // --- 组装 ---
        byte[] header = MpqHeader.writeHeader(version, sectorShift, (int) hashPos,
                hashEntries.length, (int) blockPos, placed.size(), cursor);
        if (version == MpqHeader.FormatVersion.V4) {
            MpqHeader.finishV4Header(header, hashBytes, blockBytes);
        }

        ByteBuffer outBuf = ByteBuffer.allocate(base + (int) cursor).order(ByteOrder.LITTLE_ENDIAN);
        outBuf.put(data, 0, base);
        outBuf.put(header);
        for (Placed p : placed) {
            outBuf.put(p.bytes());
            int pad = (4 - p.bytes().length % 4) % 4;
            outBuf.put(new byte[pad]);
        }
        outBuf.put(hashBytes);
        outBuf.put(blockBytes);
        byte[] out = outBuf.array();

        // 原归档之后的字节（追加的数据）保留。
        long oldEnd = base + archive.header.archiveSize(data.length - base);
        out = appendTail(out, data, oldEnd);

        // --- 自检：结果必须能打开并正确读回 ---
        try (MpqArchive check = MpqArchive.open(out)) {
            if (options.targetVersion() != null && check.version() != options.targetVersion().ordinal() + 1) {
                throw new MpqException("转换校验失败：写出的版本与目标不一致");
            }
            for (Map.Entry<String, byte[]> add : adds) {
                if (!Arrays.equals(check.readFile(add.getKey()), add.getValue())) {
                    throw new MpqException("校验失败：" + add.getKey() + " 内容不一致");
                }
            }
            for (Map.Entry<String, byte[]> re : reencoded) {
                if (!Arrays.equals(check.readFile(re.getKey()), re.getValue())) {
                    throw new MpqException("校验失败：" + re.getKey() + " 重编码后内容不一致");
                }
            }
            for (Deletion del : deletions) {
                if (del instanceof Deletion.ByName byName
                        && adds.stream().noneMatch(a -> StormCrypt.sameName(a.getKey(), byName.name()))
                        && check.hasFile(byName.name())) {
                    throw new MpqException("校验失败：" + byName.name() + " 仍然存在");
                }
            }
        }
        return new ModifyResult(out, report);
    }

    private static long key(TableSource source, int index) {
        return ((long) source.ordinal() << 32) | Integer.toUnsignedLong(index);
    }

    /** 保留原归档之后追加的字节。 */
    private static byte[] appendTail(byte[] out, byte[] data, long oldEnd) {
        if (oldEnd < data.length) {
            int tail = data.length - (int) oldEnd;
            byte[] grown = Arrays.copyOf(out, out.length + tail);
            System.arraycopy(data, (int) oldEnd, grown, out.length, tail);
            return grown;
        }
        return out;
    }

    private static ClassicTables.HashEntry[] newHashEntries(int count) {
        ClassicTables.HashEntry[] entries = new ClassicTables.HashEntry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new ClassicTables.HashEntry(0xFFFF_FFFF, 0xFFFF_FFFF, 0, (byte) 0,
                    (byte) 0, ClassicTables.HASH_ENTRY_FREE);
        }
        return entries;
    }

    /** 规范化导入名：/ → \，去掉空 / . / .. 部分。 */
    static String sanitizeName(String name) {
        if (FORBIDDEN_IMPORT_NAMES.contains(name)) {
            throw new MpqException("不允许使用保留名 " + name);
        }
        String normalized = name.replace("/", "\\\\");
        List<String> cleaned = new ArrayList<>();
        for (String part : normalized.split("\\\\", -1)) {
            if (!part.isEmpty() && !part.equals(".") && !part.equals("..")) {
                cleaned.add(part);
            }
        }
        if (cleaned.isEmpty()) {
            throw new MpqException("无效的文件名 \"" + name + "\"");
        }
        return String.join("\\", cleaned);
    }

    private static int nextPow2(int n) {
        if (n <= 1) {
            return 1;
        }
        return Integer.highestOneBit(n - 1) << 1;
    }

    /** 开放探测插入；同名对 + locale 的占用槽位被替换；表满返回 false。 */
    private static boolean insertIntoHash(ClassicTables.HashEntry[] entries, String name,
                                          HashFields fields, int blockIndex) {
        byte[] norm = StormCrypt.normalizeName(name);
        long start = Integer.toUnsignedLong(StormCrypt.hashBytes(norm, StormCrypt.HASH_TABLE_INDEX))
                % entries.length;
        for (int off = 0; off < entries.length; off++) {
            int slot = (int) ((start + off) % entries.length);
            ClassicTables.HashEntry e = entries[slot];
            boolean same = !e.isFree() && e.name1() == fields.name1() && e.name2() == fields.name2()
                    && e.locale() == fields.locale();
            if (same || e.isFree()) {
                entries[slot] = new ClassicTables.HashEntry(fields.name1(), fields.name2(),
                        fields.locale(), fields.platform(), (byte) 0, blockIndex);
                return true;
            }
        }
        return false;
    }

    // == 编码 ================================================================

    /** 编码产物：存储字节 + 块标志。 */
    record Encoded(byte[] bytes, int flags) {
    }

    /**
     * 将内容编码为新存储块：有收益时每 sector 用 zlib，小文件单块
     * single-unit，不可压缩时明文 sector。
     */
    static Encoded encodeFresh(byte[] data, int sectorSize) {
        int fsize = data.length;

        if (fsize == 0) {
            return new Encoded(new byte[0], FileEntry.FILE_SINGLE_UNIT);
        }

        if (fsize < sectorSize) {
            byte[] z = MpqCompression.zlibCompress(data);
            if (z.length + 1 < data.length) {
                byte[] b = new byte[z.length + 1];
                b[0] = MpqCompression.M_ZLIB;
                System.arraycopy(z, 0, b, 1, z.length);
                return new Encoded(b, FileEntry.FILE_SINGLE_UNIT | FileEntry.FILE_COMPRESS);
            }
            return new Encoded(data.clone(), FileEntry.FILE_SINGLE_UNIT);
        }

        int nsec = (fsize + sectorSize - 1) / sectorSize;
        byte[][] sectors = new byte[nsec][];
        long total = 0;
        for (int i = 0; i < nsec; i++) {
            byte[] chunk = Arrays.copyOfRange(data, i * sectorSize, Math.min((i + 1) * sectorSize, fsize));
            byte[] z = MpqCompression.zlibCompress(chunk);
            if (z.length + 1 < chunk.length) {
                sectors[i] = new byte[z.length + 1];
                sectors[i][0] = MpqCompression.M_ZLIB;
                System.arraycopy(z, 0, sectors[i], 1, z.length);
            } else {
                sectors[i] = chunk;
            }
            total += sectors[i].length;
        }
        if (total >= data.length) {
            // 没有任何内容可压缩：按固定偏移明文存储。
            return new Encoded(data.clone(), 0);
        }

        int tableLen = (nsec + 1) * 4;
        ByteBuffer block = ByteBuffer.allocate(tableLen + (int) total).order(ByteOrder.LITTLE_ENDIAN);
        int acc = tableLen;
        block.putInt(acc);
        for (byte[] s : sectors) {
            acc += s.length;
            block.putInt(acc);
        }
        for (byte[] s : sectors) {
            block.put(s);
        }
        return new Encoded(block.array(), FileEntry.FILE_COMPRESS);
    }

    /**
     * encodeFresh 再加上以块新偏移派生密钥的 key-v2 加密。
     * 磁盘布局约定：single unit 整体加密；压缩 sector 填充到 dword 边界
     * （偏移含填充），偏移表用 key-1、每个 sector 用 key+i 加密；
     * 不可压缩时按固定偏移原样存储、逐个加密。
     */
    static Encoded encodeFreshReencrypted(String name, byte[] data, int sectorSize, long pos) {
        final int enc = FileEntry.FILE_ENCRYPTED | FileEntry.FILE_KEY_V2;
        int fsize = data.length;
        int key = StormCrypt.fileKey(name, true, pos, fsize);

        if (fsize == 0) {
            return new Encoded(new byte[0], FileEntry.FILE_SINGLE_UNIT);
        }

        if (fsize < sectorSize) {
            byte[] z = MpqCompression.zlibCompress(data);
            boolean compressed = z.length + 1 < data.length;
            byte[] payload;
            if (compressed) {
                payload = new byte[z.length + 1];
                payload[0] = MpqCompression.M_ZLIB;
                System.arraycopy(z, 0, payload, 1, z.length);
            } else {
                payload = data.clone();
            }
            StormCrypt.encryptBytes(payload, key);
            return new Encoded(payload,
                    enc | FileEntry.FILE_SINGLE_UNIT | (compressed ? FileEntry.FILE_COMPRESS : 0));
        }

        int nsec = (fsize + sectorSize - 1) / sectorSize;
        byte[][] sectors = new byte[nsec][];
        boolean compressedAny = false;
        for (int i = 0; i < nsec; i++) {
            byte[] chunk = Arrays.copyOfRange(data, i * sectorSize, Math.min((i + 1) * sectorSize, fsize));
            byte[] z = MpqCompression.zlibCompress(chunk);
            // 填充到 dword 后（z+1+3 = z+4）仍小于原文才算有收益（读取方以长度区分）。
            if (z.length + 4 < chunk.length) {
                byte[] s = new byte[z.length + 1];
                s[0] = MpqCompression.M_ZLIB;
                System.arraycopy(z, 0, s, 1, z.length);
                sectors[i] = s;
                compressedAny = true;
            } else {
                sectors[i] = chunk;
            }
        }
        if (!compressedAny) {
            // 固定偏移明文 sector，逐个加密。
            ByteBuffer payload = ByteBuffer.allocate(fsize).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < nsec; i++) {
                StormCrypt.encryptBytes(sectors[i], key + i);
                payload.put(sectors[i]);
            }
            return new Encoded(payload.array(), enc);
        }

        // 压缩布局：全部 sector 填充到 dword 边界（偏移含填充），
        // 偏移表 key-1、每个 sector key+i 加密。
        long total = 0;
        for (int i = 0; i < nsec; i++) {
            int pad = (4 - sectors[i].length % 4) % 4;
            if (pad != 0) {
                sectors[i] = Arrays.copyOf(sectors[i], sectors[i].length + pad);
            }
            total += sectors[i].length;
        }
        int tableLen = (nsec + 1) * 4;
        ByteBuffer block = ByteBuffer.allocate(tableLen + (int) total).order(ByteOrder.LITTLE_ENDIAN);
        int acc = tableLen;
        block.putInt(acc);
        for (byte[] s : sectors) {
            acc += s.length;
            block.putInt(acc);
        }
        StormCrypt.encryptBytes(block.array(), 0, tableLen, key - 1);
        for (int i = 0; i < nsec; i++) {
            StormCrypt.encryptBytes(sectors[i], key + i);
            block.put(sectors[i]);
        }
        return new Encoded(block.array(), enc | FileEntry.FILE_COMPRESS);
    }
}
