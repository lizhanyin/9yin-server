package com.nineyin.tools.dataimport;

import com.nineyin.tools.dataimport.importer.ImportContext;
import com.nineyin.tools.dataimport.importer.Importer;
import com.nineyin.tools.dataimport.importer.RolesImporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导入工具入口。
 *
 * <pre>
 * 用法：data-import [-dsn user:pass@tcp(host:port)/dbname] [-data 目录] &lt;命令&gt;
 *   -dsn   Go 风格 MySQL DSN；缺省读环境变量 NINEYIN_MYSQL_DSN，
 *          再缺省使用 nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin
 *   -data  data 目录；缺省读 NINEYIN_DATA_DIR，再缺省依次尝试 ./data、
 *          NINEYIN_SERVER_ROOT/data
 *
 * 命令：
 *   apply-schema      建库并执行全部建表脚本（accounts/roles 基表 + 45 张数据表）
 *   import list       列出全部导入器
 *   import all        依序执行全部导入（roles 引导 → 配置表 → 角色数据）
 *   import &lt;name&gt;     执行单个导入器（见 import list）
 * </pre>
 *
 * 建表脚本 schema/0001_init_roles.sql 与 nineyin 模块 resources/nineyin/db/0001_init_roles.sql
 * 内容对应，改其一需同步另一份。
 */
public final class DataImportMain {

    private static final String DEFAULT_DSN = "nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin";

    public static void main(String[] args) {
        String dsn = null;
        String dataDir = null;
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("-dsn".equals(args[i]) && i + 1 < args.length) {
                dsn = args[++i];
            } else if ("-data".equals(args[i]) && i + 1 < args.length) {
                dataDir = args[++i];
            } else {
                commands.add(args[i]);
            }
        }
        if (commands.isEmpty()) {
            usageAndExit("缺少命令");
        }
        if (dsn == null || dsn.isBlank()) {
            dsn = System.getenv("NINEYIN_MYSQL_DSN");
        }
        if (dsn == null || dsn.isBlank()) {
            dsn = DEFAULT_DSN;
        }

        String command = commands.get(0);
        try {
            switch (command) {
                case "apply-schema" -> {
                    DsnParser.Dsn parsed = DsnParser.parse(dsn);
                    int tables = new SchemaApplier().apply(parsed);
                    System.out.printf("apply-schema 完成，共执行 %d 条 CREATE TABLE%n", tables);
                }
                case "import" -> runImport(dsn, resolveDataDir(dataDir), commands.subList(1, commands.size()));
                default -> usageAndExit("未知命令: " + command);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.exit(2);
        } catch (SQLException e) {
            System.err.println("数据库错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runImport(String dsn, Path dataDir, List<String> rest) throws SQLException {
        Map<String, Importer> registry = registry();
        if (rest.isEmpty() || "list".equals(rest.get(0))) {
            System.out.println("可用导入器（import all 依此顺序执行）：");
            for (Importer im : registry.values()) {
                System.out.printf("  %-20s %s%n", im.name(), im.description());
            }
            return;
        }
        if (!Files.isDirectory(dataDir)) {
            System.err.println("data 目录不存在: " + dataDir.toAbsolutePath()
                    + "（用 -data 指定，或设 NINEYIN_DATA_DIR）");
            System.exit(2);
        }
        List<Importer> toRun = new ArrayList<>();
        if ("all".equals(rest.get(0))) {
            toRun.addAll(registry.values());
        } else {
            for (String name : rest) {
                Importer im = registry.get(name);
                if (im == null) {
                    System.err.println("未知导入器: " + name + "（import list 查看）");
                    System.exit(2);
                }
                toRun.add(im);
            }
        }

        DsnParser.Dsn parsed = DsnParser.parse(dsn);
        int failed = 0;
        try (Connection conn = DriverManager.getConnection(parsed.jdbcUrl(true), parsed.user(), parsed.pass())) {
            conn.setAutoCommit(false);
            ImportContext ctx = ImportContext.open(conn, dataDir);
            System.out.printf("data 目录: %s%n", dataDir.toAbsolutePath());
            for (Importer im : toRun) {
                System.out.printf("== %s: %s%n", im.name(), im.description());
                try {
                    im.run(ctx);
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    failed++;
                    System.err.println("  失败（已回滚）: " + e.getMessage());
                }
            }
        }
        if (failed > 0) {
            System.err.printf("完成，%d/%d 个导入器失败%n", failed, toRun.size());
            System.exit(1);
        }
        System.out.printf("全部完成（%d 个导入器）%n", toRun.size());
    }

    /** 导入器注册表：roles 引导在最前（角色数据依赖它），其后配置表、角色数据。 */
    private static Map<String, Importer> registry() {
        Map<String, Importer> map = new LinkedHashMap<>();
        Importer roles = new RolesImporter();
        map.put(roles.name(), roles);
        for (Importer im : com.nineyin.tools.dataimport.importer.ConfigImporters.all()) {
            map.put(im.name(), im);
        }
        for (Importer im : com.nineyin.tools.dataimport.importer.RoleDataImporters.all()) {
            map.put(im.name(), im);
        }
        return map;
    }

    private static Path resolveDataDir(String flag) {
        if (flag != null) {
            return Path.of(flag);
        }
        String env = System.getenv("NINEYIN_DATA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        if (Files.isDirectory(Path.of("data"))) {
            return Path.of("data");
        }
        env = System.getenv("NINEYIN_SERVER_ROOT");
        if (env != null && !env.isBlank() && Files.isDirectory(Path.of(env, "data"))) {
            return Path.of(env, "data");
        }
        return Path.of("data");
    }

    private static void usageAndExit(String reason) {
        System.err.println(reason);
        System.err.println("用法: data-import [-dsn user:pass@tcp(host:port)/dbname] [-data 目录] "
                + "<apply-schema | import list | import all | import <name>>");
        System.exit(2);
    }

    private DataImportMain() {
    }
}
