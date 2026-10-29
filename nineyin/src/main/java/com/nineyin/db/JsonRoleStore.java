package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.parser.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号键 → 角色的文件存储（data/roles.json，对齐 C++ JSON 后端语义）。
 *
 * 磁盘格式与原生 C++ 版存档一致：顶层为 {version, accounts, identities,
 * next_account_id, next_role_id} 包装，条目内 scene/position 为嵌套对象，
 * 其余成长/状态字段以 {@link StoredRole#getExtra()} 原样透传。
 * 旧版 Java 扁平 Map 格式仍可读取（首次落盘即转为原生格式）。
 *
 * 读档失败时内存表为空，此时拒绝落盘——防止半空快照整文件覆盖原生存档。
 */
public final class JsonRoleStore implements RoleStore {

    private static final Logger log = LoggerFactory.getLogger(JsonRoleStore.class);

    /** 原生条目中被显式映射的基础键，其余一律进 extra。 */
    private static final Set<String> ENTRY_BASE_KEYS = Set.of("name", "appearance", "scene", "position");

    private final Path file;
    private final Map<String, StoredRole> roles = new ConcurrentHashMap<>();

    /** 用户须知签署状态（agreed, agreedAtSec），独立 sidecar 文件，不动原生存档格式。 */
    private final Path termsFile;
    private final Map<String, long[]> terms = new ConcurrentHashMap<>();

    /** 原生存档包装的元数据，读档时捕获、落盘时原样带回（Java 端不解释）。 */
    private long archiveVersion = 1;
    private final Map<String, Object> identities = new LinkedHashMap<>();
    private long nextAccountId;
    private long nextRoleId;
    private boolean loadFailed;

    public JsonRoleStore(Path file) {
        this.file = file;
        this.termsFile = file.resolveSibling("terms.json");
        load();
        loadTerms();
    }

    public synchronized boolean exists(String accountKey) {
        return roles.containsKey(accountKey);
    }

    public synchronized StoredRole get(String accountKey) {
        return roles.get(accountKey);
    }

    public synchronized void put(String accountKey, StoredRole role) {
        roles.put(accountKey, role);
        save();
    }

    public synchronized void updateLocation(String accountKey,
                                            float x, float y, float z, float orient) {
        StoredRole role = roles.get(accountKey);
        if (role == null) {
            return;
        }
        role.setX(x);
        role.setY(y);
        role.setZ(z);
        role.setOrient(orient);
    }

    public synchronized int size() {
        return roles.size();
    }

    /** 全量落盘（断线/停服时调用）。 */
    public synchronized void flush() {
        if (!roles.isEmpty()) {
            save();
        }
    }

    @Override
    public synchronized long[] termsState(String accountKey) {
        long[] t = terms.get(accountKey);
        return t != null ? t.clone() : new long[]{0, 0};
    }

    @Override
    public synchronized boolean markTermsAgreed(String accountKey) {
        long[] t = terms.get(accountKey);
        boolean first = t == null || t[0] == 0 || t[1] == 0;
        long at = t != null && t[1] > 0 ? t[1] : System.currentTimeMillis() / 1000;
        terms.put(accountKey, new long[]{1, at});
        saveTerms();
        return first;
    }

    @Override
    public synchronized void resetTermsAgreed(String accountKey) {
        terms.remove(accountKey);
        saveTerms();
    }

    private void loadTerms() {
        if (!Files.exists(termsFile)) {
            return;
        }
        try {
            JSONObject top = JSON.parseObject(
                    Files.readString(termsFile, StandardCharsets.UTF_8), Feature.OrderedField);
            if (top == null) {
                return;
            }
            for (Map.Entry<String, Object> e : top.entrySet()) {
                if (e.getValue() instanceof JSONArray arr && arr.size() == 2) {
                    terms.put(e.getKey(), new long[]{arr.getLongValue(0), arr.getLongValue(1)});
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("terms.json load failed: {}", e.getMessage());
        }
    }

    private void saveTerms() {
        JSONObject top = new JSONObject(true);
        terms.forEach((k, v) -> top.put(k, v));
        try {
            Files.writeString(termsFile, top.toJSONString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("terms.json save failed: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JSONObject top = JSON.parseObject(json, Feature.OrderedField);
            if (top != null && top.get("accounts") instanceof JSONObject accounts) {
                loadNative(top, accounts);
            } else {
                loadLegacy(json);
            }
            log.info("role store loaded {} roles from {}", roles.size(), file);
        } catch (Exception e) {
            loadFailed = true;
            log.error("role store load failed: {}", file, e);
        }
    }

    /** 原生 C++ 存档：顶层包装 + accounts 子树。 */
    private void loadNative(JSONObject top, JSONObject accounts) {
        Long version = top.getLong("version");
        if (version != null) {
            archiveVersion = version;
        }
        if (top.get("identities") instanceof JSONObject ids) {
            identities.putAll(ids);
        }
        nextAccountId = top.getLongValue("next_account_id");
        nextRoleId = top.getLongValue("next_role_id");
        for (Map.Entry<String, Object> e : accounts.entrySet()) {
            if (e.getValue() instanceof JSONObject entry) {
                roles.put(e.getKey(), fromNativeEntry(entry));
            } else {
                log.warn("skip non-object role entry: {}", e.getKey());
            }
        }
    }

    /** 旧版 Java 扁平 Map 格式（无包装层）。 */
    private void loadLegacy(String json) {
        Map<String, StoredRole> loaded = JSON.parseObject(
                json, new TypeReference<Map<String, StoredRole>>() {
                });
        if (loaded != null) {
            roles.putAll(loaded);
        }
    }

    private static StoredRole fromNativeEntry(JSONObject entry) {
        StoredRole role = new StoredRole();
        String name = entry.getString("name");
        if (name != null) {
            role.setName(name);
        }
        if (entry.get("appearance") instanceof JSONArray array) {
            role.setAppearance(array.toJavaList(String.class));
        }
        if (entry.get("scene") instanceof JSONObject scene) {
            String config = scene.getString("config");
            String resource = scene.getString("resource");
            if (config != null) {
                role.setSceneConfig(config);
            }
            if (resource != null) {
                role.setSceneResource(resource);
            }
        }
        if (entry.get("position") instanceof JSONObject position) {
            role.setX(position.getFloatValue("x"));
            role.setY(position.getFloatValue("y"));
            role.setZ(position.getFloatValue("z"));
            role.setOrient(position.getFloatValue("orient"));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> field : entry.entrySet()) {
            if (!ENTRY_BASE_KEYS.contains(field.getKey())) {
                extra.put(field.getKey(), field.getValue());
            }
        }
        role.setExtra(extra);
        return role;
    }

    private static JSONObject toNativeEntry(StoredRole role) {
        JSONObject scene = new JSONObject(true);
        scene.put("config", role.getSceneConfig());
        scene.put("resource", role.getSceneResource());
        JSONObject position = new JSONObject(true);
        position.put("x", role.getX());
        position.put("y", role.getY());
        position.put("z", role.getZ());
        position.put("orient", role.getOrient());
        JSONObject entry = new JSONObject(true);
        entry.put("name", role.getName());
        entry.put("appearance", role.getAppearance());
        entry.put("scene", scene);
        entry.put("position", position);
        for (Map.Entry<String, Object> field : role.getExtra().entrySet()) {
            entry.put(field.getKey(), field.getValue());
        }
        return entry;
    }

    private void save() {
        if (loadFailed) {
            log.error("role store save skipped — load previously failed, refusing to overwrite {}", file);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JSONObject accounts = new JSONObject(true);
            for (Map.Entry<String, StoredRole> e : new HashMap<>(roles).entrySet()) {
                accounts.put(e.getKey(), toNativeEntry(e.getValue()));
            }
            JSONObject ids = new JSONObject(true);
            ids.putAll(identities);
            JSONObject wrapper = new JSONObject(true);
            wrapper.put("version", archiveVersion);
            wrapper.put("accounts", accounts);
            wrapper.put("identities", ids);
            wrapper.put("next_account_id", nextAccountId);
            wrapper.put("next_role_id", nextRoleId);
            Files.writeString(file, JSON.toJSONString(wrapper, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("role store save failed: {}", file, e);
        }
    }
}
