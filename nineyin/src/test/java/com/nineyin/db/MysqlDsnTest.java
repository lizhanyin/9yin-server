package com.nineyin.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlDsnTest {

    @Test
    void parsesGoStyleDsn() {
        MysqlDsn.JdbcConfig cfg = MysqlDsn.parse("nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin");
        assertEquals("nineyin", cfg.user());
        assertEquals("nineyin123", cfg.password());
        assertEquals("nineyin", cfg.database());
        assertTrue(cfg.jdbcUrl().startsWith("jdbc:mysql://127.0.0.1:3306/nineyin?"));
        assertTrue(cfg.jdbcUrl().contains("characterEncoding=UTF-8"));
        assertTrue(cfg.jdbcUrl().contains("allowPublicKeyRetrieval=true"));
    }

    @Test
    void passwordMayContainColon() {
        MysqlDsn.JdbcConfig cfg = MysqlDsn.parse("user:p:a:s:s@tcp(db.host:3307)/game_db");
        assertEquals("user", cfg.user());
        assertEquals("p:a:s:s", cfg.password());
        assertEquals("game_db", cfg.database());
        assertTrue(cfg.jdbcUrl().startsWith("jdbc:mysql://db.host:3307/game_db"));
    }

    @Test
    void rejectsMalformedDsn() {
        assertThrows(IllegalArgumentException.class, () -> MysqlDsn.parse("user:pass@db/game"));
        assertThrows(IllegalArgumentException.class, () -> MysqlDsn.parse("userpass@tcp(1.2.3.4:3306)/db"));
        assertThrows(IllegalArgumentException.class, () -> MysqlDsn.parse("user:pass@tcp(1.2.3.4:3306)"));
    }
}
