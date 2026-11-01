package com.nineyin.cache;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置资源共用读取 + 缓存：init/idres/ini 等静态配置一次性读入，按文件路径缓存，
 * 后续使用直接命中缓存（如 11MB 的 equipment.ini 只在首次解析）。
 *
 * <p>缓存键为规范化绝对路径：serverRoot 切换（测试/多环境）后路径不同自然分开缓存。
 * 所有读取容错（文件缺失/IO 失败返回空结果），与各消费方原 best-effort 行为一致。
 */
public final class ResourceCache {

    private static final ConcurrentHashMap<Object, Object> CACHE = new ConcurrentHashMap<>();

    private ResourceCache() {
    }

    /**
     * 通用缓存入口：同一键只保留首个加载结果。不用 computeIfAbsent——loader 内部会
     * 再进缓存（text 层），ConcurrentHashMap 嵌套 computeIfAbsent 撞桶会抛 Recursive
     * update；get + putIfAbsent 的偶发重复加载对幂等的配置读取无害。
     */
    @SuppressWarnings("unchecked")
    public static <T> T of(Object key, java.util.function.Supplier<T> loader) {
        Object value = CACHE.get(key);
        if (value != null) {
            return (T) value;
        }
        T loaded = loader.get();
        Object raced = CACHE.putIfAbsent(key, loaded);
        return raced != null ? (T) raced : loaded;
    }

    /** 全量失效（测试改资源后用；生产无场景）。 */
    public static void invalidateAll() {
        CACHE.clear();
    }

    /**
     * 文本整体读取（BOM 容错）并按文件缓存——唯一的读盘层。
     * 其余解析（ini/idres）基于该缓存文本执行；解析产物由调用方经 {@link #of}
     * 挂在自己的键（通常是目录/root 路径）上，避免同文件递归更新缓存。
     */
    public static String text(Path file, Charset charset) {
        return of(file.toAbsolutePath().normalize(), () -> {
            Path path = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                return "";
            }
            try {
                String data = new String(Files.readAllBytes(path), charset);
                return !data.isEmpty() && data.charAt(0) == '﻿' ? data.substring(1) : data;
            } catch (Exception e) {
                return "";
            }
        });
    }

    /** UTF-8 idres（key=value 行，BOM 容错）→ 键统一小写的有序表；键重复取首值。 */
    public static Map<String, String> idresLowerCased(Path file) {
        Map<String, String> values = new HashMap<>();
        for (String raw : text(file, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (!value.isEmpty()) {
                values.putIfAbsent(key, value);
            }
        }
        return values;
    }

    /**
     * INI 逐行解析回调（section, key, value）：兼容 ; / # / // 注释、[节] 头、
     * key=value；文件缺失/失败静默跳过。GBK/Latin-1 等由调用方指定 charset。
     * 文本读盘经缓存；重复解析的抑制由调用方在表构建层完成。
     */
    public static void ini(Path file, Charset charset, IniConsumer consumer) {
        String section = "";
        for (String raw : text(file, charset).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                    || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || section.isEmpty()) {
                continue;
            }
            consumer.accept(section, line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    @FunctionalInterface
    public interface IniConsumer {

        void accept(String section, String key, String value);
    }
}
