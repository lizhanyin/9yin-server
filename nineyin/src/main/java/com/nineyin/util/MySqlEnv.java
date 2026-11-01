package com.nineyin.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 根目录 mysql.env 数据库配置加载。
 *
 * <p>格式兼容 mysql.env.example 的批处理写法与通用 KEY=VALUE：
 * {@code set "NINEYIN_MYSQL_DSN=..."}、{@code NINEYIN_MYSQL_DSN=...}，
 * {@code ::} 与 {@code #} 开头为注释。查找顺序：
 * 系统属性/环境变量 NINEYIN_MYSQL_ENV 指定文件 → 当前目录及父链的 mysql.env
 * （jar 部署目录场景）→ serverRoot/mysql.env。
 *
 * <p>DSN 取值优先级：环境变量 NINEYIN_MYSQL_DSN（保持 Go 兼容）> mysql.env > 无（JSON 存档）。
 */
public final class MySqlEnv {

    public static final String DSN_KEY = "NINEYIN_MYSQL_DSN";
    public static final String ENV_FILE_PROPERTY = "nineyin.mysqlEnv";
    public static final String ENV_FILE_VARIABLE = "NINEYIN_MYSQL_ENV";

    private MySqlEnv() {
    }

    /** MySQL DSN：环境变量优先，其次 mysql.env；都没有返回 empty（调用方回退 JSON 存档）。 */
    public static Optional<String> loadDsn() {
        String fromEnv = System.getenv(DSN_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv.trim());
        }
        Path file = locate();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(parse(readLines(file)).get(DSN_KEY)).filter(v -> !v.isBlank());
    }

    /** 定位 mysql.env；找不到返回 null。 */
    public static Path locate() {
        String configured = System.getProperty(ENV_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENV_FILE_VARIABLE);
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            return Files.isRegularFile(path) ? path : null;
        }
        Path fromCwd = findUpwards(Path.of("").toAbsolutePath());
        if (fromCwd != null) {
            return fromCwd;
        }
        Path fromRoot = ServerPaths.serverRoot().resolve("mysql.env");
        return Files.isRegularFile(fromRoot) ? fromRoot : null;
    }

    private static Path findUpwards(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve("mysql.env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static java.util.List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return java.util.List.of();
        }
    }

    /** 解析 KEY=VALUE 行（支持 set "K=V" 批处理风格与 :: / # 注释）。 */
    public static Map<String, String> parse(java.util.List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("::") || line.startsWith("#")) {
                continue;
            }
            if (line.regionMatches(true, 0, "set ", 0, 4)) {
                line = line.substring(4).trim();
            }
            boolean quoted = line.startsWith("\"") && line.endsWith("\"") && line.length() >= 2;
            if (quoted) {
                line = line.substring(1, line.length() - 1);
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }
}
