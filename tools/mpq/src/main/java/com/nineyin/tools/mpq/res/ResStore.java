package com.nineyin.tools.mpq.res;

import com.nineyin.tools.mpq.FileEntry;
import com.nineyin.tools.mpq.MpqArchive;
import com.nineyin.tools.mpq.MpqException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 三层资源读取：本地散文件 &gt; res-patch.rep &gt; res-base.rep，第一个命中生效；
 * 清单墓碑（deleted）三层皆视为不存在。实例线程安全；{@link #reload()} 可热切换
 * 补丁（原子替换归档句柄）。
 *
 * <pre>
 * 目录布局（dir 为资源根）：
 *   dir/res-base.rep        全量基础层（必需）
 *   dir/res-patch.rep       覆盖层（可选，缺省或清单缺失时无此层）
 *   dir/res-manifest.json   外置清单（可选；缺失时按缺省布局只用 base + 本地层）
 *   dir/resources/...       本地散文件（名字与归档内一致，含 resources\ 前缀）
 * </pre>
 */
public final class ResStore implements AutoCloseable {

    /** 合并视图中的一个文件。 */
    public record ResFile(String name, long size, String layer) {
    }

    /** 打开中的归档句柄（reload 时整体原子换出）。 */
    private static final class Layers {
        final MpqArchive base;
        final MpqArchive patch;   // null = 无补丁层

        Layers(MpqArchive base, MpqArchive patch) {
            this.base = base;
            this.patch = patch;
        }

        void close() {
            base.close();
            if (patch != null) {
                patch.close();
            }
        }
    }

    private final Path dir;
    private final Path localRoot;
    private volatile Layers layers;
    private volatile ResManifest manifest;

    private ResStore(Path dir, Layers layers, ResManifest manifest) {
        this.dir = dir;
        this.localRoot = dir.resolve(manifest.localDir == null
                ? ResManifest.DEFAULT_LOCAL_DIR : manifest.localDir);
        this.layers = layers;
        this.manifest = manifest;
    }

    /**
     * 打开资源根目录。清单存在则按清单；否则按缺省布局
     * （res-base.rep + resources/ 本地层，无补丁）。
     */
    public static ResStore open(Path dir) {
        // 统一绝对化：相对路径（如 "."）normalize 后可能成为空路径，
        // 使本地层的前缀校验（startsWith）失效。
        Path root = dir.toAbsolutePath().normalize();
        Path manifestPath = root.resolve(ResManifest.FILE_NAME);
        ResManifest manifest = Files.exists(manifestPath)
                ? ResManifest.load(manifestPath) : ResManifest.defaults();
        Layers layers = openLayers(root, manifest);
        return new ResStore(root, layers, manifest);
    }

    private static Layers openLayers(Path dir, ResManifest manifest) {
        if (!Files.isRegularFile(dir.resolve(manifest.baseFile))) {
            throw new MpqException("基础层不存在：" + dir.resolve(manifest.baseFile));
        }
        MpqArchive base = MpqArchive.open(dir.resolve(manifest.baseFile));
        MpqArchive patch = null;
        try {
            if (manifest.patchFile != null) {
                Path patchPath = dir.resolve(manifest.patchFile);
                if (Files.isRegularFile(patchPath)) {
                    patch = MpqArchive.open(patchPath);
                }
            }
            return new Layers(base, patch);
        } catch (RuntimeException e) {
            base.close();
            throw e;
        }
    }

    /** 热重载：按磁盘上最新的清单重开 base/patch，原子换出句柄。 */
    public synchronized void reload() {
        Path manifestPath = dir.resolve(ResManifest.FILE_NAME);
        ResManifest fresh = Files.exists(manifestPath)
                ? ResManifest.load(manifestPath) : ResManifest.defaults();
        Layers next = openLayers(dir, fresh);
        this.layers = next;
        this.manifest = fresh;
    }

    // -- 查找 ---------------------------------------------------------------

    /** 名字所在层："local" / "patch" / "base"；不存在（含墓碑）返回 null。 */
    public String layer(String name) {
        String norm = ResManifest.normalize(name);
        if (isDeleted(norm)) {
            return null;
        }
        if (localFileOf(norm) != null) {
            return "local";
        }
        Layers l = layers;
        if (l.patch != null && l.patch.hasFile(norm)) {
            return "patch";
        }
        return l.base.hasFile(norm) ? "base" : null;
    }

    public boolean exists(String name) {
        return layer(name) != null;
    }

    /** 读取文件内容；不存在（含墓碑）时抛 MpqException。 */
    public byte[] read(String name) {
        String norm = ResManifest.normalize(name);
        if (isDeleted(norm)) {
            throw new MpqException("资源不存在（墓碑）：" + name);
        }
        Path local = localFileOf(norm);
        if (local != null) {
            try {
                return Files.readAllBytes(local);
            } catch (IOException e) {
                throw new MpqException("本地文件读取失败：" + local + "（" + e.getMessage() + "）", e);
            }
        }
        Layers l = layers;
        if (l.patch != null && l.patch.hasFile(norm)) {
            return l.patch.readFile(norm);
        }
        if (l.base.hasFile(norm)) {
            return l.base.readFile(norm);
        }
        throw new MpqException("资源不存在：" + name);
    }

    private boolean isDeleted(String normalized) {
        return manifest.deleted.contains(normalized.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 本地散文件路径；文件不存在或名字越界（路径穿越）时返回 null。
     */
    private Path localFileOf(String normalized) {
        if (normalized.isEmpty()) {
            return null;
        }
        Path candidate = dir.resolve(normalized.replace('\\', '/')).normalize();
        if (!candidate.startsWith(dir)) {
            return null; // 拒绝 .. / 绝对路径
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    // -- 合并视图 ------------------------------------------------------------

    /**
     * 三层合并后的全量清单：patch 覆盖 base，本地覆盖 patch，墓碑移除。
     */
    public List<ResFile> list() {
        Layers l = layers;
        Map<String, ResFile> merged = new LinkedHashMap<>();

        for (FileEntry e : l.base.entries()) {
            if (e.name() == null || !e.exists() || "(listfile)".equalsIgnoreCase(e.name())) {
                continue;
            }
            merged.put(key(e.name()), new ResFile(e.name(),
                    Integer.toUnsignedLong(e.size()), "base"));
        }
        if (l.patch != null) {
            for (FileEntry e : l.patch.entries()) {
                if (e.name() == null || !e.exists() || "(listfile)".equalsIgnoreCase(e.name())) {
                    continue;
                }
                merged.put(key(e.name()), new ResFile(e.name(),
                        Integer.toUnsignedLong(e.size()), "patch"));
            }
        }
        // 本地层：只枚举清单 localDir 子树（名字与归档一致，含 localDir 前缀）。
        String localDirName = manifest.localDir == null
                ? ResManifest.DEFAULT_LOCAL_DIR : ResManifest.localDirName(manifest.localDir);
        Path localTree = dir.resolve(localDirName);
        if (Files.isDirectory(localTree)) {
            try (Stream<Path> walk = Files.walk(localTree)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    Path rel = localTree.relativize(p);
                    String name = localDirName + "/" + rel.toString().replace('\\', '/');
                    merged.put(key(name), new ResFile(ResManifest.normalize(name),
                            fileSize(p), "local"));
                });
            } catch (IOException e) {
                throw new MpqException("本地层枚举失败：" + localTree + "（" + e.getMessage() + "）", e);
            }
        }
        // 墓碑移除。
        for (String tomb : manifest.deleted) {
            merged.remove(tomb);
        }
        return List.copyOf(merged.values());
    }

    private static String key(String name) {
        return ResManifest.normalizeLower(name);
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    // -- 诊断 ---------------------------------------------------------------

    /** base 层条目数。 */
    public int baseCount() {
        return layers.base.entries().size();
    }

    /** patch 层条目数（无补丁层为 0）。 */
    public int patchCount() {
        Layers l = layers;
        return l.patch != null ? l.patch.entries().size() : 0;
    }

    /** 打开告警（base 与 patch 之和）。 */
    public List<String> warnings() {
        Layers l = layers;
        List<String> out = new ArrayList<>(l.base.warnings());
        if (l.patch != null) {
            out.addAll(l.patch.warnings());
        }
        return out;
    }

    public String baseVersion() {
        return manifest.baseVersion;
    }

    public String patchVersion() {
        return manifest.patchVersion;
    }

    /** 是否有补丁层。 */
    public boolean hasPatch() {
        return layers.patch != null;
    }

    @Override
    public void close() {
        layers.close();
    }
}
