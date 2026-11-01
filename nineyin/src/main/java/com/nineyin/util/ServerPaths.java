package com.nineyin.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 运行根定位，对齐 Go runtime_paths.go：env NINEYIN_SERVER_ROOT → 系统属性 → cwd 向上探测。 */
public final class ServerPaths {

    public static final String SERVER_ROOT_ENV = "NINEYIN_SERVER_ROOT";
    public static final String SERVER_ROOT_PROPERTY = "nineyin.serverRoot";

    private static volatile Path cachedRoot;

    private ServerPaths() {
    }

    public static Path serverRoot() {
        Path root = cachedRoot;
        if (root != null) {
            return root;
        }
        synchronized (ServerPaths.class) {
            if (cachedRoot == null) {
                cachedRoot = locate();
            }
            return cachedRoot;
        }
    }

    /** 测试注入。 */
    public static void overrideServerRoot(Path root) {
        synchronized (ServerPaths.class) {
            cachedRoot = root;
        }
    }

    private static Path locate() {
        String configured = System.getProperty(SERVER_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(SERVER_ROOT_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        Path start = Path.of("").toAbsolutePath();
        for (Path current = start; current != null; current = current.getParent()) {
            if (isServerRoot(current)) {
                return current;
            }
        }
        return start;
    }

    /** Go isRuntimeProjectRoot：resources/modern/share 目录或 go.mod。Java 侧以 pom.xml 代 go.mod。 */
    private static boolean isServerRoot(Path path) {
        if (Files.isDirectory(path.resolve(Path.of("resources", "modern", "share")))) {
            return true;
        }
        return Files.isRegularFile(path.resolve("pom.xml")) || Files.isRegularFile(path.resolve("go.mod"));
    }

    public static Path projectPath(String... parts) {
        return serverRoot().resolve(String.join("/", parts));
    }

    public static byte[] readIfExists(Path path) {
        try {
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
