package com.nineyin.tools.dataimport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Go 风格 MySQL DSN：user:pass@tcp(host:port)/dbname[?params]。
 * 与九阴现有工具（data-migrate.exe / NINEYIN_MYSQL_DSN）保持同一格式。
 */
public final class DsnParser {

    private static final Pattern P = Pattern.compile(
            "^(?<user>[^:@/]+)(?::(?<pass>[^@/]*))?@tcp\\((?<host>[^:/]+)(?::(?<port>\\d+))?\\)/(?<db>[^?]*)(?:\\?(?<params>.*))?$");

    public record Dsn(String user, String pass, String host, int port, String db, Map<String, String> params) {

        public String jdbcUrl(boolean withDatabase) {
            StringBuilder url = new StringBuilder("jdbc:mysql://")
                    .append(host).append(':').append(port).append('/');
            if (withDatabase && db != null && !db.isEmpty()) {
                url.append(db);
            }
            url.append("?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true")
                    .append("&allowMultiQueries=true&serverTimezone=Asia/Shanghai");
            if (params.containsKey("parseTime")) {
                url.append("&preserveInstants=true");
            }
            return url.toString();
        }
    }

    private DsnParser() {
    }

    public static Dsn parse(String dsn) {
        Matcher m = P.matcher(dsn.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "DSN 格式错误，期望 user:pass@tcp(host:port)/dbname，实际: " + dsn);
        }
        Map<String, String> params = new LinkedHashMap<>();
        String q = m.group("params");
        if (q != null && !q.isEmpty()) {
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    params.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
        }
        int port = m.group("port") == null ? 3306 : Integer.parseInt(m.group("port"));
        return new Dsn(m.group("user"), m.group("pass") == null ? "" : m.group("pass"),
                m.group("host"), port, m.group("db"), params);
    }
}
