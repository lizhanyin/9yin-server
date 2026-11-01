package com.nineyin.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** mysql.env 解析与定位（set "K=V" 批处理风格 / 纯 KEY=VALUE / :: # 注释）。 */
class MySqlEnvTest {

    @Test
    void parsesBatchStyleAndPlainKeys() {
        Map<String, String> values = MySqlEnv.parse(List.of(
                ":: 复制本文件为 mysql.env 并填入连接信息。",
                "",
                "# 通用注释",
                "set \"NINEYIN_MYSQL_DSN=nineyin:secret@tcp(127.0.0.1:3306)/nineyin\"",
                "set OTHER_FLAG=1",
                "PLAIN_KEY=plain value",
                "无等号行忽略"));
        assertEquals("nineyin:secret@tcp(127.0.0.1:3306)/nineyin", values.get("NINEYIN_MYSQL_DSN"));
        assertEquals("1", values.get("OTHER_FLAG"));
        assertEquals("plain value", values.get("PLAIN_KEY"));
        assertEquals(3, values.size());
    }

    @Test
    void envFilePropertyLoadsDsn(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve("mysql.env");
        Files.writeString(envFile, "set \"NINEYIN_MYSQL_DSN=user:pw@tcp(localhost:3306)/db\"\n");
        try {
            System.setProperty(MySqlEnv.ENV_FILE_PROPERTY, envFile.toString());
            assertEquals("user:pw@tcp(localhost:3306)/db", MySqlEnv.loadDsn().orElse(null));
        } finally {
            System.clearProperty(MySqlEnv.ENV_FILE_PROPERTY);
        }
    }

    @Test
    void missingFileYieldsEmptyDsn() {
        assumeTrue(System.getenv(MySqlEnv.DSN_KEY) == null, "存在 DSN 环境变量时优先级不同，跳过");
        System.setProperty(MySqlEnv.ENV_FILE_PROPERTY,
                Path.of("definitely-missing-mysql-env").toAbsolutePath().toString());
        try {
            assertEquals(java.util.Optional.empty(), MySqlEnv.loadDsn());
        } finally {
            System.clearProperty(MySqlEnv.ENV_FILE_PROPERTY);
        }
    }
}
