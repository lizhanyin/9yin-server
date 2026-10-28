package com.nineyin.tools.dataimport;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据导入工具入口。
 *
 * <pre>
 * 用法：data-import [-dsn user:pass@tcp(host:port)/dbname] &lt;命令&gt;
 *   -dsn  Go 风格 MySQL DSN；缺省读环境变量 NINEYIN_MYSQL_DSN，
 *         再缺省使用 nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin
 *
 * 命令：
 *   apply-schema  建库并执行全部建表脚本（accounts/roles 基表 + 45 张数据表）
 *   import        JSON 数据导入（vip/邮件/兵录/称号/生活职业等导入器逐步补齐，当前未实现）
 * </pre>
 *
 * 建表脚本 schema/0001_init_roles.sql 与 nineyin 模块 resources/nineyin/db/0001_init_roles.sql
 * 内容对应，改其一需同步另一份。
 */
public final class DataImportMain {

    private static final String DEFAULT_DSN = "nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin";

    public static void main(String[] args) {
        String dsn = null;
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("-dsn".equals(args[i]) && i + 1 < args.length) {
                dsn = args[++i];
            } else {
                commands.add(args[i]);
            }
        }
        if (commands.isEmpty()) {
            usageAndExit("缺少命令");
        }
        if (dsn == null) {
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
                case "import" -> {
                    System.err.println("import 命令尚未实现：导入器（vip/邮件/兵录/称号/生活职业等）后续按需补齐。");
                    System.exit(2);
                }
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

    private static void usageAndExit(String reason) {
        System.err.println(reason);
        System.err.println("用法: data-import [-dsn user:pass@tcp(host:port)/dbname] <apply-schema|import>");
        System.exit(2);
    }

    private DataImportMain() {
    }
}
