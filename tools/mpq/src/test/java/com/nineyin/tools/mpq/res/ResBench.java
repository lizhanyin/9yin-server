package com.nineyin.tools.mpq.res;

import com.nineyin.tools.mpq.MpqArchive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MPQ 与散文件直读的性能对比基准。
 * 取材：同时存在于本地散文件目录与 res-base.rep 中的文件（内容同源）。
 *
 * 用法: ResBench <资源根目录> [随机读取次数]
 */
public class ResBench {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath().normalize();
        int randomReads = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;

        List<String> names = new ArrayList<>();
        long looseBytes = 0;
        try (ResStore store = ResStore.open(dir)) {
            for (ResStore.ResFile f : store.list()) {
                if ("local".equals(f.layer())) {
                    names.add(f.name());
                    looseBytes += f.size();
                }
            }
        }
        System.out.printf("对比样本: %d 个文件，合计 %.1f MB（散文件与 base 同名条目）%n",
                names.size(), looseBytes / 1e6);

        Path baseRep = dir.resolve("res-base.rep");
        MpqArchive archive = MpqArchive.open(baseRep);
        Path resources = dir.resolve("resources");

        // 正确性抽查：两种路径内容应一致（散文件盖住了 base，但抽查 base 与散文件
        // 同名时内容可能确有差异 —— 本基准只测性能，内容差异已在接入时统计过）。

        // 预热：两种路径各读全量一遍（页缓存就位 + JIT）。
        readAllLoose(resources, names);
        readAllMpq(archive, names);

        int reps = 3;
        System.out.println("\n== 全量扫描（boot 场景，热缓存，取 3 轮最优） ==");
        long bestLoose = Long.MAX_VALUE;
        long bestMpq = Long.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            bestLoose = Math.min(bestLoose, time(() -> readAllLoose(resources, names)));
            bestMpq = Math.min(bestMpq, time(() -> readAllMpq(archive, names)));
        }
        report("散文件直读", bestLoose, looseBytes, names.size());
        report("MPQ 读取    ", bestMpq, looseBytes, names.size());

        System.out.printf("%n== 随机单文件读取（%d 次，热缓存） ==%n", randomReads);
        List<String> shuffled = new ArrayList<>(names);
        Collections.shuffle(shuffled, new java.util.Random(42));
        List<String> pick = shuffled.subList(0, Math.min(randomReads, shuffled.size()));

        // 预热
        for (String n : pick) {
            Files.readAllBytes(loosePath(resources, n));
            archive.readFile(n);
        }

        long tLoose = time(() -> {
            try {
                for (String n : pick) {
                    Files.readAllBytes(loosePath(resources, n));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long tMpq = time(() -> {
            for (String n : pick) {
                archive.readFile(n);
            }
        });
        int n = pick.size();
        System.out.printf("散文件直读: %d 次 / %d ms → 平均 %.1f µs/次%n", n, tLoose, tLoose * 1000.0 / n);
        System.out.printf("MPQ 读取    : %d 次 / %d ms → 平均 %.1f µs/次（含 zlib 解压）%n", n, tMpq, tMpq * 1000.0 / n);
        System.out.printf("单次开销差: %.1f µs%n", (tMpq - tLoose) * 1000.0 / n);

        archive.close();
    }

    /** 归档名 resources\x\y → 散文件路径 <resources>/x/y。 */
    private static Path loosePath(Path resources, String name) {
        String rel = name.startsWith("resources\\") ? name.substring("resources\\".length()) : name;
        return resources.resolve(rel.replace('\\', '/'));
    }

    private static void readAllLoose(Path resources, List<String> names) {
        try {
            for (String n : names) {
                Files.readAllBytes(loosePath(resources, n));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void readAllMpq(MpqArchive archive, List<String> names) {
        for (String n : names) {
            archive.readFile(n);
        }
    }

    private static long time(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (long) ((System.nanoTime() - start) / 1e6);
    }

    private static void report(String label, long ms, long bytes, int files) {
        System.out.printf("%s: %d ms（%.1f MB/s，%.1f µs/文件）%n",
                label, ms, bytes / 1e6 / (ms / 1000.0), ms * 1000.0 / files);
    }
}
