package com.nineyin.tools.dataimport.importer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 角色引导导入：roles.json → accounts / roles / role_appearances / role_locations。
 * 账号按 account_key 去重（已存在复用其 account_id，与 JSON 里的 account_id 无关——
 * 库内该 ID 可能已被后来创建的账号占用）；角色按 identities 的 role_id 原值落库
 * （role_id 1-5，与 data/ 各文件的角色键一致），已存在则跳过。
 */
public final class RolesImporter implements Importer {

    @Override
    public String name() {
        return "roles";
    }

    @Override
    public String description() {
        return "roles.json → accounts/roles/role_appearances/role_locations（按 account_key 去重、role_id 原值）";
    }

    @Override
    public void run(ImportContext ctx) throws SQLException {
        JSONObject root = ctx.readJson("roles.json");
        if (root == null) {
            System.out.println("  roles.json 缺失，跳过");
            return;
        }
        JSONObject accounts = root.getJSONObject("accounts");
        JSONObject identities = root.getJSONObject("identities");
        if (accounts == null || identities == null) {
            System.out.println("  roles.json 结构不含 accounts/identities，跳过");
            return;
        }
        int newAccounts = 0;
        int newRoles = 0;
        for (Map.Entry<String, Object> e : identities.entrySet()) {
            String accountKey = e.getKey();
            JSONObject ident = identities.getJSONObject(accountKey);
            JSONObject entry = accounts.getJSONObject(accountKey);
            if (ident == null || entry == null) {
                continue;
            }
            long accountId = resolveAccount(ctx, accountKey);
            if (accountId < 0) {
                continue;
            }
            newAccounts++;
            Long roleId = ident.getLong("role_id");
            if (roleId == null || ctx.roleExists(roleId)) {
                continue;
            }
            insertRole(ctx, roleId, accountId, entry, ident);
            ctx.addRoleId(roleId);
            newRoles++;
        }
        System.out.printf("  账号复用/新增 %d，角色新增 %d（已存在跳过）%n", newAccounts, newRoles);
    }

    /** 按 account_key 查已有账号；无则插入，返回 account_id；失败 -1。 */
    private long resolveAccount(ImportContext ctx, String accountKey) throws SQLException {
        try (PreparedStatement find = ctx.conn.prepareStatement(
                "SELECT account_id FROM accounts WHERE account_key = ?")) {
            find.setBytes(1, accountKey.getBytes(StandardCharsets.UTF_8));
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ins = ctx.conn.prepareStatement(
                "INSERT INTO accounts (account_key, status, version) VALUES (?, 1, 1)",
                Statement.RETURN_GENERATED_KEYS)) {
            ins.setBytes(1, accountKey.getBytes(StandardCharsets.UTF_8));
            ins.executeUpdate();
            try (ResultSet rs = ins.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    private void insertRole(ImportContext ctx, long roleId, long accountId,
                            JSONObject entry, JSONObject ident) throws SQLException {
        try (PreparedStatement ps = ctx.conn.prepareStatement(
                "INSERT INTO roles (role_id, account_id, slot, name, role_ext, version) "
                        + "VALUES (?, ?, 0, ?, CAST(? AS JSON), ?)")) {
            ps.setLong(1, roleId);
            ps.setLong(2, accountId);
            ps.setString(3, entry.getString("name"));
            ps.setString(4, extraJson(entry));
            ps.setLong(5, longOr(ident, "role_version", 1L));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = ctx.conn.prepareStatement(
                "INSERT INTO role_appearances (role_id, format_version, appearance, version) "
                        + "VALUES (?, 1, CAST(? AS JSON), ?)")) {
            ps.setLong(1, roleId);
            JSONArray appearance = entry.getJSONArray("appearance");
            ps.setString(2, appearance != null ? appearance.toJSONString() : "[]");
            ps.setLong(3, longOr(ident, "appearance_version", 1L));
            ps.executeUpdate();
        }
        JSONObject scene = entry.getJSONObject("scene");
        JSONObject pos = entry.getJSONObject("position");
        try (PreparedStatement ps = ctx.conn.prepareStatement(
                "INSERT INTO role_locations (role_id, scene_config, scene_resource, "
                        + "pos_x, pos_y, pos_z, orient, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, roleId);
            ps.setString(2, strOr(scene, "config"));
            ps.setString(3, strOr(scene, "resource"));
            ps.setFloat(4, pos != null ? pos.getFloatValue("x") : 0f);
            ps.setFloat(5, pos != null ? pos.getFloatValue("y") : 0f);
            ps.setFloat(6, pos != null ? pos.getFloatValue("z") : 0f);
            ps.setFloat(7, pos != null ? pos.getFloatValue("orient") : 0f);
            ps.setLong(8, longOr(ident, "location_version", 1L));
            ps.executeUpdate();
        }
    }

    private static long longOr(JSONObject o, String key, long def) {
        Long v = o != null ? o.getLong(key) : null;
        return v != null ? v : def;
    }

    private static String strOr(JSONObject o, String key) {
        String v = o != null ? o.getString(key) : null;
        return v != null ? v : "";
    }

    /** role_ext：name/appearance/scene/position 之外的全部字段原样透传（不改动原对象）。 */
    private static String extraJson(JSONObject entry) {
        JSONObject extra = new JSONObject(true);
        for (Map.Entry<String, Object> e : entry.entrySet()) {
            String k = e.getKey();
            if (!"name".equals(k) && !"appearance".equals(k)
                    && !"scene".equals(k) && !"position".equals(k)) {
                extra.put(k, e.getValue());
            }
        }
        return extra.toJSONString();
    }
}
