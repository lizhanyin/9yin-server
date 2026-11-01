package com.nineyin.tools.dataimport.importer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** 导入上下文：连接 + data 目录 + 已存在的角色 ID 集合 + 批量插入助手。 */
public final class ImportContext {

    public final Connection conn;
    public final Path dataDir;
    private final Set<Long> roleIds;

    private ImportContext(Connection conn, Path dataDir, Set<Long> roleIds) {
        this.conn = conn;
        this.dataDir = dataDir;
        this.roleIds = roleIds;
    }

    public static ImportContext open(Connection conn, Path dataDir) throws SQLException {
        Set<Long> ids = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT role_id FROM roles")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return new ImportContext(conn, dataDir, ids);
    }

    /** 角色 ID 是否已存在于 roles 表（role_* 数据只导已有角色，FK 保证）。 */
    public boolean roleExists(long roleId) {
        return roleIds.contains(roleId);
    }

    public void addRoleId(long roleId) {
        roleIds.add(roleId);
    }

    /** 读 data 目录 JSON（UTF-8，容忍 BOM）；缺失返回 null。 */
    public JSONObject readJson(String fileName) {
        Path p = dataDir.resolve(fileName);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            return JSON.parseObject(text);
        } catch (IOException | RuntimeException e) {
            System.err.println("  读取失败 " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    public void clearTable(String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM `" + table + "`");
        }
    }

    /** 批量插入：add() 累积，满 1000 条自动 executeBatch；close() 收尾。 */
    public Batcher batch(String sql) throws SQLException {
        return new Batcher(conn.prepareStatement(sql));
    }

    public static final class Batcher implements AutoCloseable {

        private static final int FLUSH = 1000;
        private final PreparedStatement ps;
        private int pending;

        Batcher(PreparedStatement ps) {
            this.ps = ps;
        }

        public PreparedStatement ps() {
            return ps;
        }

        public void add() throws SQLException {
            ps.addBatch();
            if (++pending >= FLUSH) {
                flush();
            }
        }

        private void flush() throws SQLException {
            if (pending > 0) {
                ps.executeBatch();
                pending = 0;
            }
        }

        @Override
        public void close() throws SQLException {
            flush();
            ps.close();
        }
    }
}
