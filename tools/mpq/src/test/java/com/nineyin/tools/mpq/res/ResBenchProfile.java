package com.nineyin.tools.mpq.res;

import com.nineyin.tools.mpq.FileEntry;
import com.nineyin.tools.mpq.MpqArchive;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 随机读取慢的归因剖析：裸通道读 / 哈希查找 / 解压各占多少。 */
public class ResBenchProfile {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath().normalize();
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        List<String> names = new ArrayList<>();
        try (ResStore store = ResStore.open(dir)) {
            for (ResStore.ResFile f : store.list()) {
                if ("local".equals(f.layer())) {
                    names.add(f.name());
                }
            }
        }
        Collections.shuffle(names, new java.util.Random(42));
        List<String> pick = names.subList(0, Math.min(n, names.size()));
        System.out.println("样本 " + pick.size());

        MpqArchive archive = MpqArchive.open(dir.resolve("res-base.rep"));

        // 预热全路径一遍 + 预取文件位置。
        List<FileEntry> entries = new ArrayList<>(pick.size());
        for (String name : pick) {
            entries.add(archive.findFile(name).orElseThrow());
            archive.readFile(name);
        }
        // 再来一遍预热（确保页缓存完全就位）。
        for (String name : pick) {
            archive.readFile(name);
        }

        // 阶段 1：哈希查找（纯内存）。
        long tHash = time(() -> {
            for (String name : pick) {
                archive.findFile(name);
            }
        });

        // 阶段 2：裸通道读（每文件按其 offset/csize 各读一次，不解压不查找）。
        try (FileChannel ch = FileChannel.open(dir.resolve("res-base.rep"), StandardOpenOption.READ)) {
            final ByteBuffer[] bufHolder = {ByteBuffer.allocate(64 * 1024)};
            long tRaw = time(() -> {
                try {
                    for (FileEntry e : entries) {
                        int len = Math.max(1, e.compressedSize());
                        ByteBuffer buf = bufHolder[0];
                        if (buf.capacity() < len) {
                            buf = ByteBuffer.allocate(len);
                            bufHolder[0] = buf;
                        }
                        buf.clear();
                        buf.limit(len);
                        int r = 0;
                        while (r < len) {
                            int k = ch.read(buf, e.offset() + r);
                            if (k <= 0) {
                                break;
                            }
                            r += k;
                        }
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });

            // 阶段 3：完整 readFile（查找 + 读 + 解压）。
            long tFull = time(() -> {
                for (String name : pick) {
                    archive.readFile(name);
                }
            });
            long tFull2 = time(() -> {
                for (String name : pick) {
                    archive.readFile(name);
                }
            });

            int count = pick.size();
            System.out.printf("哈希查找     : %,d ms → %.1f µs/次%n", tHash, tHash * 1000.0 / count);
            System.out.printf("裸通道读     : %,d ms → %.1f µs/次%n", tRaw, tRaw * 1000.0 / count);
            System.out.printf("完整读取 #1  : %,d ms → %.1f µs/次%n", tFull, tFull * 1000.0 / count);
            System.out.printf("完整读取 #2  : %,d ms → %.1f µs/次%n", tFull2, tFull2 * 1000.0 / count);
            long other = tFull2 - tHash - tRaw;
            System.out.printf("差值(解压+装配): %,d ms → %.1f µs/次%n", other, other * 1000.0 / count);
        }
        archive.close();
    }

    private static long time(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (long) ((System.nanoTime() - start) / 1e6);
    }
}
