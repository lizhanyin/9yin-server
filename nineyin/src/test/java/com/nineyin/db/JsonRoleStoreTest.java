package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonRoleStore 原生 C++ 存档格式兼容：读取、透传扩展字段、
 * 包装元数据（identities / next_*）保留、旧 Java 扁平格式迁移、读档失败写保护。
 */
class JsonRoleStoreTest {

    /** 按文档实测样本缩写的原生格式（嵌套 scene/position + 扩展成长字段 + 包装元数据）。 */
    private static final String NATIVE_SAMPLE = """
            {
              "version": 1,
              "accounts": {
                "acct:v1:sample": {
                  "name": "原生侠",
                  "appearance": ["book1", "icon\\\\players\\\\boy01_create.png"],
                  "scene": {"config": "ini\\\\scene\\\\city05_ChengDu", "resource": "city05"},
                  "position": {"x": 695.26, "y": 23.01, "z": 669.24, "orient": 2.04},
                  "power_level": 7,
                  "nei_gong_points": 120,
                  "justice": 30,
                  "max_ene": 1000
                }
              },
              "identities": {"role:原生侠": 5},
              "next_account_id": 6,
              "next_role_id": 6
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void loadsNativeFormatAndKeepsExtras() throws Exception {
        Path file = tempDir.resolve("roles.json");
        Files.writeString(file, NATIVE_SAMPLE, StandardCharsets.UTF_8);

        JsonRoleStore store = new JsonRoleStore(file);
        StoredRole role = store.get("acct:v1:sample");
        assertNotNull(role);
        assertEquals("原生侠", role.getName());
        assertEquals(List.of("book1", "icon\\players\\boy01_create.png"), role.getAppearance());
        assertEquals("ini\\scene\\city05_ChengDu", role.getSceneConfig());
        assertEquals("city05", role.getSceneResource());
        assertEquals(695.26f, role.getX(), 0.001f);
        assertEquals(23.01f, role.getY(), 0.001f);
        assertEquals(669.24f, role.getZ(), 0.001f);
        assertEquals(2.04f, role.getOrient(), 0.001f);
        assertEquals(4, role.getExtra().size());
        assertEquals(7, ((Number) role.getExtra().get("power_level")).intValue());
        assertEquals(120, ((Number) role.getExtra().get("nei_gong_points")).intValue());
        assertEquals(1000, ((Number) role.getExtra().get("max_ene")).intValue());
        assertEquals(1, store.size());
    }

    @Test
    void savePreservesNativeWrapperMetadataAndOtherAccounts() throws Exception {
        Path file = tempDir.resolve("roles.json");
        Files.writeString(file, NATIVE_SAMPLE, StandardCharsets.UTF_8);
        JsonRoleStore store = new JsonRoleStore(file);

        // 新建角触发落盘：原生条目必须原样保留，包装元数据不可丢
        StoredRole fresh = new StoredRole();
        fresh.setName("新建侠");
        fresh.setSceneConfig("ini\\scene\\city01_Start");
        fresh.setSceneResource("city01");
        store.put("acct:v1:fresh", fresh);

        JSONObject top = JSON.parseObject(
                Files.readString(file, StandardCharsets.UTF_8), Feature.OrderedField);
        assertNotNull(top.getJSONObject("accounts"));
        assertEquals(6, ((Number) top.get("next_account_id")).intValue());
        assertEquals(6, ((Number) top.get("next_role_id")).intValue());
        assertEquals(5, ((Number) top.getJSONObject("identities").get("role:原生侠")).intValue());

        JSONObject sample = top.getJSONObject("accounts").getJSONObject("acct:v1:sample");
        assertNotNull(sample, "原账号条目不得被覆盖丢失");
        assertEquals(7, ((Number) sample.get("power_level")).intValue());
        assertEquals("city05", sample.getJSONObject("scene").getString("resource"));
        assertEquals(695.26, sample.getJSONObject("position").getDoubleValue("x"), 0.001);
        JSONObject freshEntry = top.getJSONObject("accounts").getJSONObject("acct:v1:fresh");
        assertEquals("新建侠", freshEntry.getString("name"));
        assertEquals("city01", freshEntry.getJSONObject("scene").getString("resource"));

        // 落盘后重读一遍，扩展字段仍完整
        JsonRoleStore reload = new JsonRoleStore(file);
        assertEquals(7, ((Number) reload.get("acct:v1:sample").getExtra().get("power_level")).intValue());
    }

    @Test
    void loadsLegacyJavaFormatAndConvertsToNativeOnSave() throws Exception {
        Path file = tempDir.resolve("roles.json");
        Files.writeString(file, """
                {
                  "acct:v1:legacy": {
                    "name": "旧版侠",
                    "appearance": ["book2"],
                    "sceneConfig": "ini\\\\scene\\\\city02",
                    "sceneResource": "city02",
                    "x": 1.5, "y": 2.5, "z": 3.5, "orient": 4.5
                  }
                }
                """, StandardCharsets.UTF_8);

        JsonRoleStore store = new JsonRoleStore(file);
        StoredRole role = store.get("acct:v1:legacy");
        assertNotNull(role);
        assertEquals("旧版侠", role.getName());
        assertEquals("ini\\scene\\city02", role.getSceneConfig());
        assertEquals(4.5f, role.getOrient(), 0.001f);

        StoredRole fresh = new StoredRole();
        fresh.setName("迁移侠");
        store.put("acct:v1:convert", fresh);

        JSONObject top = JSON.parseObject(
                Files.readString(file, StandardCharsets.UTF_8), Feature.OrderedField);
        assertNotNull(top.getJSONObject("accounts"), "旧格式首次落盘应转为原生包装格式");
        assertEquals("city02", top.getJSONObject("accounts")
                .getJSONObject("acct:v1:legacy").getJSONObject("scene").getString("resource"));
    }

    @Test
    void refusesToOverwriteFileWhenLoadFailed() throws Exception {
        Path file = tempDir.resolve("roles.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        JsonRoleStore store = new JsonRoleStore(file);
        assertNull(store.get("acct:v1:any"));

        StoredRole role = new StoredRole();
        role.setName("保护侠");
        store.put("acct:v1:protected", role);

        assertEquals("{ this is not json",
                Files.readString(file, StandardCharsets.UTF_8),
                "读档失败后磁盘内容必须保持原样，不得被半空快照覆盖");
        assertNotNull(store.get("acct:v1:protected"), "内存读写不受写保护影响");
    }
}
