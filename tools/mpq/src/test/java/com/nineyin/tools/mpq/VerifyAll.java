package com.nineyin.tools.mpq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 全量读取验证：逐条目解压 resources.rep，统计失败 / 告警 / 哈希抽查。 */
public class VerifyAll {

    public static void main(String[] args) throws Exception {
        Path mpq = Path.of(args.length > 0 ? args[0] : "E:\\Workspace\\project\\game\\resources.rep");
        long start = System.nanoTime();
        long totalBytes = 0;
        int failures = 0;
        try (MpqArchive archive = MpqArchive.open(mpq)) {
            List<FileEntry> entries = archive.entries();
            System.out.println("条目数: " + entries.size() + "，版本 v" + archive.version());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (FileEntry e : entries) {
                if (!e.exists()) {
                    continue;
                }
                try {
                    byte[] data = archive.readEntry(e);
                    totalBytes += data.length;
                    digest.update(data);
                } catch (MpqException ex) {
                    failures++;
                    if (failures <= 10) {
                        System.out.println("失败 " + e.displayName() + ": " + ex.getMessage());
                    }
                }
            }
            System.out.println("失败: " + failures);
            System.out.println("读取告警: " + archive.readWarnings().size()
                    + (archive.readWarnings().isEmpty() ? "" : " 例: " + archive.readWarnings().get(0)));
            System.out.println("打开告警: " + archive.warnings());
            // 全部内容的聚合哈希：作为该归档内容的指纹。
            System.out.println("内容聚合 sha256: " + HexFormat.of().formatHex(digest.digest()));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("解压总量: %.1f MB，耗时 %.1fs，%.1f MB/s%n",
                totalBytes / 1e6, seconds, totalBytes / 1e6 / seconds);
    }
}
