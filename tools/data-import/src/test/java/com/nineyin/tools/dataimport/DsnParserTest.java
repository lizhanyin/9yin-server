package com.nineyin.tools.dataimport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DsnParserTest {

    @Test
    void parsesFullDsn() {
        DsnParser.Dsn d = DsnParser.parse("nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin");
        assertEquals("nineyin", d.user());
        assertEquals("nineyin123", d.pass());
        assertEquals("127.0.0.1", d.host());
        assertEquals(3306, d.port());
        assertEquals("nineyin", d.db());
    }

    @Test
    void defaultsPortAndEmptyPassword() {
        DsnParser.Dsn d = DsnParser.parse("root@tcp(localhost)/db1");
        assertEquals("", d.pass());
        assertEquals(3306, d.port());
        assertEquals("db1", d.db());
        assertEquals("localhost", d.host());
    }

    @Test
    void parsesQueryParams() {
        DsnParser.Dsn d = DsnParser.parse("u:p@tcp(h:3307)/db?parseTime=true&charset=utf8mb4");
        assertEquals("true", d.params().get("parseTime"));
        assertEquals("utf8mb4", d.params().get("charset"));
        assertEquals(3307, d.port());
    }

    @Test
    void jdbcUrlIncludesDatabaseOnlyWhenAsked() {
        DsnParser.Dsn d = DsnParser.parse("u:p@tcp(h)/db");
        assertEquals(true, d.jdbcUrl(true).contains("/db?"));
        assertEquals(false, d.jdbcUrl(false).contains("/db?"));
    }

    @Test
    void rejectsMalformedDsn() {
        assertThrows(IllegalArgumentException.class, () -> DsnParser.parse("not-a-dsn"));
        assertThrows(IllegalArgumentException.class, () -> DsnParser.parse("u:p@udp(h)/db"));
    }
}
