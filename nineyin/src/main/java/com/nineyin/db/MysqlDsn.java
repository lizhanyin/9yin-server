package com.nineyin.db;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Go 风式 DSN 解析：user:pass@tcp(host:port)/db（与 C++ / mysql.env 一致）。
 * 转成 JDBC URL（含 UTF-8 时区参数，保证中文名正确落库）。
 */
public final class MysqlDsn {

    public record JdbcConfig(String jdbcUrl, String user, String password, String database) {
    }

    private MysqlDsn() {
    }

    /** 解析失败抛 IllegalArgumentException。 */
    public static JdbcConfig parse(String dsn) {
        String s = dsn.trim();
        int at = s.indexOf("@tcp(");
        if (at < 0) {
            throw new IllegalArgumentException("bad DSN (expect user:pass@tcp(host:port)/db): " + dsn);
        }
        int close = s.indexOf(')', at);
        if (close < 0 || close + 1 >= s.length() || s.charAt(close + 1) != '/') {
            throw new IllegalArgumentException("bad DSN (missing /db after tcp(...)): " + dsn);
        }
        String userPass = s.substring(0, at);
        String hostPort = s.substring(at + 5, close);
        String db = s.substring(close + 2);
        if (hostPort.isEmpty() || db.isEmpty()) {
            throw new IllegalArgumentException("bad DSN host/db: " + dsn);
        }
        int colon = userPass.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("bad DSN credentials: " + dsn);
        }
        String user = userPass.substring(0, colon);
        String pass = userPass.substring(colon + 1);
        String url = "jdbc:mysql://" + hostPort + "/" + URLEncoder.encode(db, StandardCharsets.UTF_8)
                + "?useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcConfig(url, user, pass, db);
    }
}
