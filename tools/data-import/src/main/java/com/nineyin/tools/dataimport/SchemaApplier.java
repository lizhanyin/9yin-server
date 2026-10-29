package com.nineyin.tools.dataimport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 按序执行 classpath 下 schema/ 中的建表脚本：
 * 先建库（utf8mb4），再逐个执行 SQL 文件（脚本内语句使用 allowMultiQueries 一次执行）。
 */
public final class SchemaApplier {

    private static final String[] SCHEMA_SCRIPTS = {
            "/schema/0001_init_roles.sql",
            "/schema/0002_data_tables.sql",
            "/schema/0003_server_list_and_terms.sql",
    };

    public int apply(DsnParser.Dsn dsn) throws SQLException {
        ensureDatabase(dsn);
        try (Connection conn = DriverManager.getConnection(dsn.jdbcUrl(true), dsn.user(), dsn.pass());
             Statement st = conn.createStatement()) {
            int tables = 0;
            for (String script : SCHEMA_SCRIPTS) {
                String sql = readResource(script);
                st.execute(sql);
                int n = countCreateTable(sql);
                tables += n;
                System.out.printf("已执行 %s（%d 张表）%n", script, n);
            }
            return tables;
        }
    }

    private void ensureDatabase(DsnParser.Dsn dsn) throws SQLException {
        if (dsn.db() == null || dsn.db().isEmpty()) {
            throw new SQLException("DSN 未指定数据库名：user:pass@tcp(host:port)/dbname");
        }
        try (Connection conn = DriverManager.getConnection(dsn.jdbcUrl(false), dsn.user(), dsn.pass());
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + dsn.db().replace("`", "``")
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            System.out.printf("数据库 %s 已就绪%n", dsn.db());
        }
    }

    private static String readResource(String path) {
        try (InputStream in = SchemaApplier.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少打包资源 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败 " + path, e);
        }
    }

    /** 统计脚本里的 CREATE TABLE 语句数（用于执行结果核对）。 */
    static int countCreateTable(String sql) {
        int n = 0;
        for (String line : sql.split("\n")) {
            String t = line.trim().toUpperCase();
            if (t.startsWith("CREATE TABLE")) {
                n++;
            }
        }
        return n;
    }
}
