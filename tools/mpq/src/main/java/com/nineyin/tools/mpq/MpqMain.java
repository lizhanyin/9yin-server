package com.nineyin.tools.mpq;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * MPQ 命令行工具：查看 / 列表 / 解包 / 打包 / 增删文件 / 版本转换 / 三层资源仓。
 *
 * <pre>
 * 用法: mpq &lt;命令&gt; [参数]
 *   info    &lt;mpq&gt;                     头部与条目概览
 *   list    &lt;mpq&gt;                     列出文件（名字 / 大小 / 压缩后 / 标志）
 *   extract &lt;mpq&gt; [destDir] [名字…]   解包全部或指定文件（默认 ./&lt;mpq名&gt;/）
 *   pack    &lt;dir&gt; &lt;out.mpq&gt; [-v 1-4]  目录递归打包为新归档（默认 v1）
 *   add     &lt;mpq&gt; &lt;本地文件=归档名&gt;…  添加 / 替换文件（写回原 mpq）
 *   remove  &lt;mpq&gt; &lt;名字&gt;…            删除文件（写回原 mpq）
 *   convert &lt;mpq&gt; &lt;out.mpq&gt; -v 1-4   转换头部版本
 *   store   info|list|read &lt;资源根&gt;…  三层资源仓（本地 &gt; patch &gt; base）
 * </pre>
 */
public final class MpqMain {

    public static void main(String[] args) {
        if (args.length < 2) {
            usage(System.err);
            System.exit(2);
            return;
        }
        try {
            run(args);
        } catch (MpqException | IOException e) {
            System.err.println("错误：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage(PrintStream out) {
        out.println("""
                用法: mpq <命令> [参数]
                  info    <mpq>                     头部与条目概览
                  list    <mpq>                     列出文件
                  extract <mpq> [destDir] [名字…]   解包全部或指定文件
                  pack    <dir> <out.mpq> [-v 1-4]  目录递归打包为新归档（默认 v1）
                  add     <mpq> <本地文件=归档名>…  添加 / 替换文件
                  remove  <mpq> <名字>…            删除文件
                  convert <mpq> <out.mpq> -v 1-4   转换头部版本
                  store   info|list|read <资源根>…  三层资源仓（本地 > patch > base）""");
    }

    private static void run(String[] args) throws IOException {
        switch (args[0]) {
            case "info" -> info(Path.of(args[1]));
            case "list" -> list(Path.of(args[1]));
            case "extract" -> extract(Path.of(args[1]), args);
            case "pack" -> pack(args);
            case "add" -> add(args);
            case "remove" -> remove(args);
            case "convert" -> convert(args);
            case "store" -> store(args);
            default -> {
                usage(System.err);
                System.exit(2);
            }
        }
    }

    private static void info(Path path) {
        try (MpqArchive archive = MpqArchive.open(path)) {
            System.out.println("文件        : " + path);
            System.out.println("格式版本    : v" + archive.version());
            System.out.println("sector 大小 : " + archive.sectorSize());
            System.out.println("归档偏移    : 0x" + Long.toHexString(archive.archiveOffset()));
            System.out.println("文件条目    : " + archive.entries().size());
            System.out.println("已知名字    : " + archive.fileNames().size());
            for (String w : archive.warnings()) {
                System.out.println("警告        : " + w);
            }
        }
    }

    private static void list(Path path) {
        try (MpqArchive archive = MpqArchive.open(path)) {
            System.out.printf("%-10s %-10s %-8s %s%n", "大小", "压缩后", "标志", "名字");
            for (FileEntry e : archive.entries()) {
                System.out.printf("%-10d %-10d %-8s %s%n",
                        Integer.toUnsignedLong(e.size()), Integer.toUnsignedLong(e.compressedSize()),
                        flagsSummary(e), e.displayName());
            }
            for (String w : archive.warnings()) {
                System.out.println("警告: " + w);
            }
        }
    }

    private static String flagsSummary(FileEntry e) {
        StringBuilder sb = new StringBuilder();
        if (!e.exists()) {
            sb.append('!');
        }
        if (e.isSingleUnit()) {
            sb.append('U');
        }
        if (e.isCompressed()) {
            sb.append('C');
        }
        if (e.isEncrypted()) {
            sb.append('E');
        }
        return sb.toString();
    }

    private static void extract(Path path, String[] args) throws IOException {
        Path dest;
        List<String> wanted = new ArrayList<>();
        if (args.length > 2 && !args[2].startsWith("-")) {
            dest = Path.of(args[2]);
            for (int i = 3; i < args.length; i++) {
                wanted.add(args[i]);
            }
        } else {
            String name = path.getFileName().toString();
            name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            dest = Path.of(name);
            for (int i = 2; i < args.length; i++) {
                wanted.add(args[i]);
            }
        }
        try (MpqArchive archive = MpqArchive.open(path)) {
            List<FileEntry> entries = archive.entries().stream()
                    // (listfile) 是格式内部元数据，解包时跳过。
                    .filter(e -> !"(listfile)".equalsIgnoreCase(e.displayName()))
                    .toList();
            if (!wanted.isEmpty()) {
                entries = entries.stream().filter(e -> wanted.stream()
                        .anyMatch(w -> StormCrypt.sameName(w, e.displayName()))).toList();
            }
            for (FileEntry e : entries) {
                byte[] data = archive.readEntry(e);
                Path target = dest.resolve(e.displayName().replace('\\', '/'));
                Files.createDirectories(target.getParent() == null ? dest : target.getParent());
                Files.write(target, data);
                System.out.println("写出 " + target + " (" + data.length + " 字节)");
            }
            System.out.println("共解包 " + entries.size() + " 个文件到 " + dest);
        }
    }

    private static void pack(String[] args) throws IOException {
        Path dir = Path.of(args[1]);
        Path out = Path.of(args[2]);
        MpqHeader.FormatVersion version = MpqHeader.FormatVersion.V1;
        for (int i = 3; i < args.length - 1; i++) {
            if ("-v".equals(args[i])) {
                version = versionOf(args[i + 1]);
            }
        }
        if (!Files.isDirectory(dir)) {
            throw new MpqException("不是目录：" + dir);
        }
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                Path rel = dir.relativize(p);
                String name = rel.toString().replace('\\', '/') // 统一 / 再交给 sanitize
                        .replaceFirst("^/", "");
                try {
                    files.put(MpqWriter.sanitizeName(name), Files.readAllBytes(p));
                } catch (IOException e) {
                    throw new MpqException("读取 " + p + " 失败：" + e.getMessage(), e);
                }
            });
        }
        if (files.isEmpty()) {
            throw new MpqException("目录为空：" + dir);
        }
        byte[] archive = MpqWriter.create(MpqWriter.CreateOptions.ofVersion(version));
        MpqWriter.ModifyResult result = MpqWriter.modify(archive, List.of(), files);
        Files.write(out, result.bytes());
        System.out.println("打包 " + files.size() + " 个文件 → " + out + "（v" + (version.ordinal() + 1) + "）");
    }

    private static void add(String[] args) throws IOException {
        Path path = Path.of(args[1]);
        LinkedHashMap<String, byte[]> additions = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].lastIndexOf('=');
            if (eq <= 0) {
                throw new MpqException("参数应为 <本地文件=归档名>：" + args[i]);
            }
            Path local = Path.of(args[i].substring(0, eq));
            additions.put(args[i].substring(eq + 1), Files.readAllBytes(local));
        }
        byte[] data = Files.readAllBytes(path);
        MpqWriter.ModifyResult result = MpqWriter.modify(data, List.of(), additions);
        Files.write(path, result.bytes());
        report(result.report());
    }

    private static void remove(String[] args) throws IOException {
        Path path = Path.of(args[1]);
        List<MpqWriter.Deletion> deletions = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            deletions.add(new MpqWriter.Deletion.ByName(args[i]));
        }
        byte[] data = Files.readAllBytes(path);
        MpqWriter.ModifyResult result = MpqWriter.modify(data, deletions, new LinkedHashMap<>());
        Files.write(path, result.bytes());
        report(result.report());
    }

    private static void convert(String[] args) throws IOException {
        Path in = Path.of(args[1]);
        Path out = Path.of(args[2]);
        MpqHeader.FormatVersion version = MpqHeader.FormatVersion.V3;
        for (int i = 3; i < args.length - 1; i++) {
            if ("-v".equals(args[i])) {
                version = versionOf(args[i + 1]);
            }
        }
        MpqWriter.ModifyResult result = MpqWriter.convert(Files.readAllBytes(in), version);
        Files.write(out, result.bytes());
        System.out.println("转换 " + in + " → " + out + "（v" + (version.ordinal() + 1) + "）");
    }

    private static MpqHeader.FormatVersion versionOf(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "1", "v1" -> MpqHeader.FormatVersion.V1;
            case "2", "v2" -> MpqHeader.FormatVersion.V2;
            case "3", "v3" -> MpqHeader.FormatVersion.V3;
            case "4", "v4" -> MpqHeader.FormatVersion.V4;
            default -> throw new MpqException("无效版本 \"" + s + "\"（应为 1–4）");
        };
    }

    // == 三层资源仓 -----------------------------------------------------------

    private static void store(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: mpq store info|list|read <资源根目录> [名字/前缀]");
            System.exit(2);
            return;
        }
        Path dir = Path.of(args[2]);
        String cmd = args[1];
        try (com.nineyin.tools.mpq.res.ResStore store = com.nineyin.tools.mpq.res.ResStore.open(dir)) {
            switch (cmd) {
                case "info" -> {
                    System.out.println("资源根    : " + dir.toAbsolutePath());
                    System.out.println("base 条目 : " + store.baseCount()
                            + "（版本 " + store.baseVersion() + "）");
                    System.out.println("patch     : " + (store.hasPatch()
                            ? store.patchCount() + " 条目（版本 " + store.patchVersion() + "）"
                            : "无"));
                    System.out.println("合并清单  : " + store.list().size() + " 个文件");
                    for (String w : store.warnings()) {
                        System.out.println("警告      : " + w);
                    }
                }
                case "list" -> {
                    String prefix = args.length > 3 ? args[3] : null;
                    List<com.nineyin.tools.mpq.res.ResStore.ResFile> files = store.list();
                    int shown = 0;
                    for (var f : files) {
                        if (prefix == null || f.name().toLowerCase(Locale.ROOT)
                                .startsWith(prefix.toLowerCase(Locale.ROOT).replace('/', '\\'))) {
                            System.out.printf("%-6s %10d  %s%n", f.layer(), f.size(), f.name());
                            shown++;
                        }
                    }
                    System.out.println("共 " + shown + " 个（全量 " + files.size() + "）");
                }
                case "read" -> {
                    if (args.length < 4) {
                        throw new MpqException("用法: mpq store read <资源根目录> <名字>");
                    }
                    String name = args[3];
                    String layer = store.layer(name);
                    if (layer == null) {
                        System.out.println("不存在: " + name);
                        System.exit(1);
                        return;
                    }
                    byte[] data = store.read(name);
                    System.out.println("层: " + layer + "，大小: " + data.length + " 字节");
                    // 文本文件预览前 200 字节；二进制只显示长度。
                    String preview = new String(data, 0, Math.min(200, data.length),
                            StandardCharsets.UTF_8).replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "?");
                    System.out.println("预览: " + preview.split("\r?\n")[0]);
                }
                default -> {
                    System.err.println("未知子命令：" + cmd);
                    System.exit(2);
                }
            }
        }
    }

    private static void report(MpqWriter.ModifyReport report) {
        for (String n : report.added()) {
            System.out.println("新增 " + n);
        }
        for (String n : report.replaced()) {
            System.out.println("替换 " + n);
        }
        for (String n : report.deleted()) {
            System.out.println("删除 " + n);
        }
        for (String n : report.dropped()) {
            System.out.println("丢弃 " + n);
        }
        for (String n : report.reEncoded()) {
            System.out.println("重编码 " + n);
        }
    }

    private MpqMain() {
    }
}
