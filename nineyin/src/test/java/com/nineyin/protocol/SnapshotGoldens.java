package com.nineyin.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Assertions;

/**
 * 三期官方方言快照 golden：`*_official.bin/json` 是 Java 自有字节基准（防回归），
 * 与 Go 侧生成的 golden（无属性索引帧仍然对齐）区分。
 *
 * <p>重生成：`./mvnw -pl nineyin test -Dnineyin.golden.regen=true`（写回
 * src/test/resources/golden/）；普通运行断言字节一致。
 */
public final class SnapshotGoldens {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    public static boolean regen() {
        return Boolean.getBoolean("nineyin.golden.regen");
    }

    public static byte[] golden(String name) {
        try (InputStream in = SnapshotGoldens.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("read golden " + name, e);
        }
    }

    /** 断言或再生（regen 模式写盘后仍断言一次自读一致）。 */
    public static void assertOrRegen(String name, byte[] data) {
        if (regen()) {
            try {
                Files.createDirectories(GOLDEN_DIR);
                Files.write(GOLDEN_DIR.resolve(name), data);
            } catch (IOException e) {
                throw new IllegalStateException("write golden " + name, e);
            }
            return;
        }
        Assertions.assertArrayEquals(golden(name), data, "snapshot golden " + name);
    }

    public static void assertOrRegenText(String name, String text) {
        assertOrRegen(name, (text + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private SnapshotGoldens() {
    }
}
