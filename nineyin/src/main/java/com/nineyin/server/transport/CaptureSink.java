package com.nineyin.server.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import com.nineyin.util.ServerPaths;

/**
 * wire 级抓包落盘阀（对齐 Go 参考服 captures/local 行为）：按连接双向各一文件，
 * 内容为 XOR+转义后的原始 wire 字节——与 smoke/capture_deep.py 的解码格式完全一致，
 * 可直接 overview/customs 解析，用于与参考服抓包逐帧 diff。
 *
 * <p>目录：{@code <serverRoot>/captures/local/<ip转义>_<port>_c2s.bin|_s2c.bin}。
 * 系统属性 {@code nineyin.capture.dir} 覆盖目录；{@code nineyin.capture.disabled=true} 关闭
 * （默认开）。写失败静默吞（抓包永不影响业务管线）。
 */
public final class CaptureSink {

    private static final boolean DISABLED = Boolean.getBoolean("nineyin.capture.disabled");
    private static final Path DIR = resolveDir();
    private static final ConcurrentHashMap<String, OutputStream> OPEN = new ConcurrentHashMap<>();

    private CaptureSink() {
    }

    private static Path resolveDir() {
        if (DISABLED) {
            return null;
        }
        String override = System.getProperty("nineyin.capture.dir");
        Path dir = (override == null || override.isBlank())
                ? ServerPaths.serverRoot().resolve("captures").resolve("local")
                : Path.of(override.trim());
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            return null;
        }
    }

    /** ip:port → 文件名安全形式（/127.0.0.1:50768 → 127-0-0-1-50768；剥前导斜杠，
     *  否则 Path.resolve 会把带斜杠的名字当绝对路径解析到盘根）。 */
    static String tag(String remote) {
        return remote.replace("/", "").replace(':', '-').replace('.', '-');
    }

    /** 追加 wire 字节；每连接每方向独立文件，尾帧关闭时收尾。 */
    public static void append(String remote, boolean downstream, byte[] wire, int length) {
        if (DIR == null || wire == null || length <= 0) {
            return;
        }
        String key = tag(remote) + (downstream ? "_s2c" : "_c2s");
        try {
            OutputStream out = OPEN.computeIfAbsent(key, k -> {
                try {
                    return Files.newOutputStream(DIR.resolve(k + ".bin"),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    System.err.println("[capture] open failed " + k + ": " + e);
                    return null;
                }
            });
            if (out != null) {
                synchronized (out) {
                    out.write(wire, 0, length);
                    out.flush();
                }
            }
        } catch (IOException | RuntimeException e) {
            // 抓包失败不影响业务
        }
    }

    /** 连接关闭时收尾该连接的两个文件（静默）。 */
    public static void close(String remote) {
        if (DIR == null) {
            return;
        }
        String base = tag(remote);
        for (String suffix : new String[]{"_c2s", "_s2c"}) {
            OutputStream out = OPEN.remove(base + suffix);
            if (out != null) {
                try {
                    synchronized (out) {
                        out.close();
                    }
                } catch (IOException e) {
                    // 静默
                }
            }
        }
    }
}
