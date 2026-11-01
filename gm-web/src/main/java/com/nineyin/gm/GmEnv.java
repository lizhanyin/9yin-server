package com.nineyin.gm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * gm-web 独立进程的环境解析（不依赖 nineyin 模块类）：
 * <ul>
 * <li>serverRoot：env NINEYIN_SERVER_ROOT > 系统属性 nineyin.serverRoot >
 *     cwd 向上探测 resources/modern/share（目录数据/DSN 定位基准）。</li>
 * <li>DSN：env NINEYIN_MYSQL_DSN > mysql.env（属性/变量 NINEYIN_MYSQL_ENV 指定
 *     → cwd 及父链 → serverRoot/mysql.env）。行格式兼容批处理 set "K=V"。</li>
 * </ul>
 */
public final class GmEnv {

    public static final String ROOT_VARIABLE = "NINEYIN_SERVER_ROOT";
    public static final String ROOT_PROPERTY = "nineyin.serverRoot";
    public static final String DSN_KEY = "NINEYIN_MYSQL_DSN";
    public static final String ENV_FILE_PROPERTY = "nineyin.mysqlEnv";
    public static final String ENV_FILE_VARIABLE = "NINEYIN_MYSQL_ENV";

    private GmEnv() {
    }

    /** 服务器根目录（resources/modern/share 的上级）。 */
    public static Path serverRoot() {
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ROOT_VARIABLE);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            if (Files.isDirectory(current.resolve(Path.of("resources", "modern", "share")))) {
                return current;
            }
        }
        return Path.of("").toAbsolutePath();
    }

    /** share 根（resources/modern/share）。 */
    public static Path shareRoot() {
        return serverRoot().resolve(Path.of("resources", "modern", "share"));
    }

    /** MySQL DSN；都没有返回 empty（目录源回退 data/*.json 文件）。 */
    public static Optional<String> loadDsn() {
        String fromEnv = System.getenv(DSN_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv.trim());
        }
        Path file = locateMysqlEnv();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(parse(readLines(file)).get(DSN_KEY)).filter(v -> !v.isBlank());
    }

    private static Path locateMysqlEnv() {
        String configured = System.getProperty(ENV_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENV_FILE_VARIABLE);
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            return Files.isRegularFile(path) ? path : null;
        }
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve("mysql.env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path fromRoot = serverRoot().resolve("mysql.env");
        return Files.isRegularFile(fromRoot) ? fromRoot : null;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 解析 KEY=VALUE 行（支持 set "K=V" 批处理风格与 :: / # 注释）。 */
    public static Map<String, String> parse(List<String> lines) {
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
