package com.nineyin.server.db.role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nineyin.util.GoJson;

/**
 * 单 JSON 文档账号仓库，逐行对齐 Go internal/role/json_repository.go + json_m2_repository.go。
 *
 * <p>文档格式（Go MarshalIndent 两空格输出，map 键升序）：
 * {version, accounts{name,appearance,scene,position}, identities{account_id,role_id,status,
 * account_version,slot,role_version,appearance_version,location_version,delete_requested},
 * next_account_id, next_role_id}。写回走临时文件 + 原子替换；读取兼容旧版单角色文档。
 */
public final class JsonRoleRepository implements RoleRepository {

    private static final int JSON_FORMAT_VERSION = 1;
    private static final String LEGACY_DEFAULT_ACCOUNT = "local_default";

    /** Go jsonIdentity：字段序与 omitempty 对齐。 */
    private static final class JsonIdentity {
        long accountId;
        long roleId;
        int status = 1;
        long accountVersion;
        int slot;
        long roleVersion;
        long appearanceVersion;
        long locationVersion;
        boolean deleteRequested;
    }

    private final Path path;
    private final Map<String, Roles.RoleValue> accounts = new LinkedHashMap<>();
    private final Map<String, JsonIdentity> identities = new LinkedHashMap<>();
    private long nextAccountID = 1;
    private long nextRoleID = 1;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public static JsonRoleRepository open(Path path) {
        if (path.toString().isBlank()) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "role: empty repository path");
        }
        JsonRoleRepository repository = new JsonRoleRepository(path.toAbsolutePath().normalize());
        if (!Files.exists(repository.path)) {
            return repository;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(repository.path);
        } catch (IOException e) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, "read role repository");
        }
        if (new String(data, StandardCharsets.UTF_8).isBlank()) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, "decode role repository: empty document");
        }
        repository.decode(data);
        return repository;
    }

    private JsonRoleRepository(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    // ---------------------------------------------------------------- decode

    private void decode(byte[] data) {
        JSONObject document = JSON.parseObject(new String(data, StandardCharsets.UTF_8));
        if (document.containsKey("accounts") && document.get("accounts") != null) {
            int version = document.getIntValue("version");
            if (version != JSON_FORMAT_VERSION) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE,
                        "decode role repository: unsupported version " + version);
            }
            JSONObject accountsNode = document.getJSONObject("accounts");
            for (Map.Entry<String, Object> entry : accountsNode.entrySet()) {
                if (entry.getKey().isBlank()) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE,
                            "decode role repository: empty account key");
                }
                accounts.put(entry.getKey(), normalize(parseRoleValue((JSONObject) entry.getValue())));
            }
            JSONObject identitiesNode = document.getJSONObject("identities");
            if (identitiesNode != null) {
                for (Map.Entry<String, Object> entry : identitiesNode.entrySet()) {
                    identities.put(entry.getKey(), parseIdentity((JSONObject) entry.getValue()));
                }
            }
            nextAccountID = document.getLongValue("next_account_id");
            nextRoleID = document.getLongValue("next_role_id");
            if (nextAccountID == 0) {
                nextAccountID = 1;
            }
            if (nextRoleID == 0) {
                nextRoleID = 1;
            }
            rebuildIdentities();
            return;
        }
        // 旧版单角色文档：Name != "" 是唯一存在标志。
        String name = document.getString("name");
        if (name != null && !name.isEmpty()) {
            Roles.RoleValue legacy = new Roles.RoleValue(
                    name,
                    strings(document.getJSONArray("appearance")),
                    new Roles.Scene(document.getString("scene_config"), document.getString("scene_resource")),
                    new Roles.Position(document.getFloatValue("PosX"), document.getFloatValue("PosY"),
                            document.getFloatValue("PosZ"), document.getFloatValue("Orient")));
            accounts.put(LEGACY_DEFAULT_ACCOUNT, normalize(legacy));
            rebuildIdentities();
        }
    }

    private static Roles.RoleValue parseRoleValue(JSONObject node) {
        JSONObject sceneNode = node.getJSONObject("scene");
        JSONObject positionNode = node.getJSONObject("position");
        return new Roles.RoleValue(
                node.getString("name") == null ? "" : node.getString("name"),
                strings(node.getJSONArray("appearance")),
                new Roles.Scene(
                        sceneNode != null && sceneNode.getString("config") != null ? sceneNode.getString("config") : "",
                        sceneNode != null && sceneNode.getString("resource") != null ? sceneNode.getString("resource") : ""),
                positionNode != null
                        ? new Roles.Position(positionNode.getFloatValue("x"),
                        positionNode.getFloatValue("y"),
                        positionNode.getFloatValue("z"),
                        positionNode.getFloatValue("orient"))
                        : new Roles.Position(0, 0, 0, 0));
    }

    private static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (Object value : array) {
                values.add(value == null ? "" : value.toString());
            }
        }
        return values;
    }

    private static JsonIdentity parseIdentity(JSONObject node) {
        JsonIdentity identity = new JsonIdentity();
        identity.accountId = node.getLongValue("account_id");
        identity.roleId = node.getLongValue("role_id");
        identity.status = node.getIntValue("status");
        identity.accountVersion = node.getLongValue("account_version");
        identity.slot = node.getIntValue("slot");
        identity.roleVersion = node.getLongValue("role_version");
        identity.appearanceVersion = node.getLongValue("appearance_version");
        identity.locationVersion = node.getLongValue("location_version");
        identity.deleteRequested = node.getBooleanValue("delete_requested");
        return identity;
    }

    // ------------------------------------------------------- AccountRepository

    @Override
    public Roles.Account findByKey(String key) {
        validateAccountKey(key);
        lock.readLock().lock();
        try {
            JsonIdentity identity = identities.get(key);
            if (identity == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
            }
            if (identity.status != Roles.AccountStatus.ACTIVE.value) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.ACCOUNT_DISABLED);
            }
            return accountFrom(key, identity);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Roles.Account provisionLegacy(String key) {
        validateAccountKey(key);
        lock.writeLock().lock();
        try {
            JsonIdentity existing = identities.get(key);
            if (existing != null) {
                if (existing.status != Roles.AccountStatus.ACTIVE.value) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.ACCOUNT_DISABLED);
                }
                return accountFrom(key, existing);
            }
            JsonIdentity identity = ensureIdentity(key, false);
            try {
                persist();
            } catch (RuntimeException e) {
                identities.remove(key);
                throw e;
            }
            return accountFrom(key, identity);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --------------------------------------------------------- RoleRepository

    @Override
    public List<Roles.RoleSummary> listByAccount(long accountId) {
        lock.readLock().lock();
        try {
            JsonIdentity identity = findAccount(accountId);
            if (identity == null) {
                return List.of();
            }
            String key = identityKey(accountId);
            Roles.RoleValue value = accounts.get(key);
            if (value == null || identity.roleId == 0) {
                return List.of();
            }
            return List.of(new Roles.RoleSummary(identity.roleId, identity.slot, value.name(),
                    identity.deleteRequested, identity.roleVersion));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Roles.RoleSnapshot loadOwned(long accountId, long roleId) {
        lock.readLock().lock();
        try {
            String key = findRoleKey(roleId);
            if (key == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
            }
            JsonIdentity identity = identities.get(key);
            if (identity.accountId != accountId) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_OWNED);
            }
            Roles.RoleValue value = accounts.get(key);
            if (value == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
            }
            return snapshotFrom(value, identity);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Roles.RoleSnapshot loadOwnedByName(long accountId, String name) {
        lock.readLock().lock();
        try {
            for (Map.Entry<String, Roles.RoleValue> entry : accounts.entrySet()) {
                if (!entry.getValue().name().equals(name)) {
                    continue;
                }
                JsonIdentity identity = identities.get(entry.getKey());
                if (identity == null || identity.roleId == 0) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
                }
                if (identity.accountId != accountId) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_OWNED);
                }
                return snapshotFrom(entry.getValue(), identity);
            }
            throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Roles.RoleSnapshot create(long accountId, Roles.NewRole draft) {
        validateDraft(draft);
        lock.writeLock().lock();
        try {
            JsonIdentity identity = findAccount(accountId);
            if (identity == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
            }
            String key = identityKey(accountId);
            if (identity.status != Roles.AccountStatus.ACTIVE.value) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.ACCOUNT_DISABLED);
            }
            Roles.RoleValue existingAccount = accounts.get(key);
            if (identity.roleId != 0 || (existingAccount != null && !existingAccount.name().isEmpty())) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.ROLE_SLOT_TAKEN);
            }
            for (Roles.RoleValue value : accounts.values()) {
                if (value.name().equals(draft.name())) {
                    throw new RoleRepositoryException(RoleRepositoryException.Code.ROLE_NAME_TAKEN);
                }
            }
            JsonIdentity previous = identity;
            JsonIdentity ensured = ensureIdentity(key, true);
            ensured.slot = draft.slot();
            identities.put(key, ensured);
            Roles.RoleValue value = new Roles.RoleValue(draft.name(),
                    new ArrayList<>(draft.appearance().values()),
                    draft.location().scene(), draft.location().position());
            accounts.put(key, value);
            try {
                persist();
            } catch (RuntimeException e) {
                accounts.remove(key);
                identities.put(key, previous);
                throw e;
            }
            return snapshotFrom(value, ensured);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void requestDelete(long accountId, long roleId) {
        lock.writeLock().lock();
        try {
            String key = findRoleKey(roleId);
            if (key == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
            }
            JsonIdentity identity = identities.get(key);
            if (identity.accountId != accountId) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_OWNED);
            }
            JsonIdentity previous = copy(identity);
            identity.deleteRequested = true;
            identity.roleVersion++;
            identities.put(key, identity);
            try {
                persist();
            } catch (RuntimeException e) {
                identities.put(key, previous);
                throw e;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public long saveAppearance(long roleId, long expectedVersion, Roles.Appearance value) {
        if (value.formatVersion() == 0) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "zero appearance format");
        }
        lock.writeLock().lock();
        try {
            String key = findRoleKey(roleId);
            if (key == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
            }
            JsonIdentity identity = identities.get(key);
            if (identity.appearanceVersion != expectedVersion) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.VERSION_CONFLICT);
            }
            Roles.RoleValue roleValue = accounts.get(key);
            if (roleValue == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
            }
            List<String> previous = roleValue.appearance();
            accounts.put(key, new Roles.RoleValue(roleValue.name(), new ArrayList<>(value.values()),
                    roleValue.scene(), roleValue.position()));
            identity.appearanceVersion++;
            try {
                persist();
            } catch (RuntimeException e) {
                accounts.put(key, new Roles.RoleValue(roleValue.name(), previous, roleValue.scene(), roleValue.position()));
                identity.appearanceVersion--;
                throw e;
            }
            return identity.appearanceVersion;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public long saveLocation(long roleId, long expectedVersion, Roles.Location value) {
        validateLocation(value);
        lock.writeLock().lock();
        try {
            String key = findRoleKey(roleId);
            if (key == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
            }
            JsonIdentity identity = identities.get(key);
            if (identity.locationVersion != expectedVersion) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.VERSION_CONFLICT);
            }
            Roles.RoleValue roleValue = accounts.get(key);
            if (roleValue == null) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.CORRUPT_SNAPSHOT);
            }
            accounts.put(key, new Roles.RoleValue(roleValue.name(), roleValue.appearance(),
                    value.scene(), value.position()));
            identity.locationVersion++;
            try {
                persist();
            } catch (RuntimeException e) {
                accounts.put(key, roleValue);
                identity.locationVersion--;
                throw e;
            }
            return identity.locationVersion;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        // JSON 仓库无资源。
    }

    // ---------------------------------------------------------------- helpers

    private static Roles.Account accountFrom(String key, JsonIdentity identity) {
        return new Roles.Account(identity.accountId, key, Roles.AccountStatus.of(identity.status), identity.accountVersion);
    }

    private Roles.RoleSnapshot snapshotFrom(Roles.RoleValue value, JsonIdentity identity) {
        return new Roles.RoleSnapshot(identity.roleId, identity.slot, value.name(), identity.deleteRequested,
                identity.roleVersion, identity.accountId,
                new Roles.Appearance(1, new ArrayList<>(value.appearance()), identity.appearanceVersion),
                new Roles.Location(value.scene(), value.position(), identity.locationVersion));
    }

    private JsonIdentity findAccount(long accountId) {
        for (JsonIdentity identity : identities.values()) {
            if (identity.accountId == accountId) {
                return identity;
            }
        }
        return null;
    }

    private String identityKey(long accountId) {
        for (Map.Entry<String, JsonIdentity> entry : identities.entrySet()) {
            if (entry.getValue().accountId == accountId) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String findRoleKey(long roleId) {
        if (roleId == 0) {
            return null;
        }
        for (Map.Entry<String, JsonIdentity> entry : identities.entrySet()) {
            if (entry.getValue().roleId == roleId) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void rebuildIdentities() {
        List<String> keys = new ArrayList<>(accounts.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            ensureIdentity(key, true);
        }
        for (JsonIdentity identity : identities.values()) {
            if (identity.accountId >= nextAccountID) {
                nextAccountID = identity.accountId + 1;
            }
            if (identity.roleId >= nextRoleID) {
                nextRoleID = identity.roleId + 1;
            }
        }
        if (nextAccountID == 0) {
            nextAccountID = 1;
        }
        if (nextRoleID == 0) {
            nextRoleID = 1;
        }
    }

    private JsonIdentity ensureIdentity(String key, boolean withRole) {
        JsonIdentity identity = identities.get(key);
        if (identity == null) {
            identity = new JsonIdentity();
            identity.accountId = nextAccountID;
            identity.status = Roles.AccountStatus.ACTIVE.value;
            identity.accountVersion = 1;
            nextAccountID++;
        }
        if (identity.accountVersion == 0) {
            identity.accountVersion = 1;
        }
        if (withRole && identity.roleId == 0) {
            identity.roleId = nextRoleID;
            nextRoleID++;
            identity.roleVersion = 1;
            identity.appearanceVersion = 1;
            identity.locationVersion = 1;
        }
        identities.put(key, identity);
        return identity;
    }

    private static JsonIdentity copy(JsonIdentity identity) {
        JsonIdentity duplicate = new JsonIdentity();
        duplicate.accountId = identity.accountId;
        duplicate.roleId = identity.roleId;
        duplicate.status = identity.status;
        duplicate.accountVersion = identity.accountVersion;
        duplicate.slot = identity.slot;
        duplicate.roleVersion = identity.roleVersion;
        duplicate.appearanceVersion = identity.appearanceVersion;
        duplicate.locationVersion = identity.locationVersion;
        duplicate.deleteRequested = identity.deleteRequested;
        return duplicate;
    }

    /** Go normalize：路径反斜杠折叠、场景兜底、全零坐标兜底。 */
    private static Roles.RoleValue normalize(Roles.RoleValue value) {
        String config = value.scene().config();
        while (config.contains("\\\\")) {
            config = config.replace("\\\\", "\\");
        }
        if (config.isEmpty()) {
            config = "ini\\scene\\school07_WuDang";
        }
        String resource = value.scene().resource();
        if (resource.isEmpty() || resource.equals("scene07")) {
            resource = "school07";
        }
        Roles.Position position = value.position();
        if (position.x() == 0 && position.y() == 0 && position.z() == 0) {
            position = new Roles.Position(479.75f, 87.5f, 624.5f, 3.12f);
        }
        return new Roles.RoleValue(value.name(), value.appearance(), new Roles.Scene(config, resource), position);
    }

    private static void validateAccountKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ACCOUNT_KEY);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ACCOUNT_KEY);
            }
        }
    }

    private static void validateDraft(Roles.NewRole draft) {
        if (draft.slot() >= Roles.DEFAULT_MAX_ROLE_SLOTS || draft.name() == null || draft.name().isBlank()
                || draft.name().codePointCount(0, draft.name().length()) > Roles.MAX_ROLE_NAME_RUNES
                || hasControl(draft.name())) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "invalid role slot or name");
        }
        validateLocation(draft.location());
    }

    private static void validateLocation(Roles.Location location) {
        if (location.scene().config().isEmpty() || location.scene().resource().isEmpty()
                || location.scene().config().codePointCount(0, location.scene().config().length()) > 255
                || location.scene().resource().codePointCount(0, location.scene().resource().length()) > 64
                || hasControl(location.scene().config()) || hasControl(location.scene().resource())) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "empty scene");
        }
        float[] values = {location.position().x(), location.position().y(),
                location.position().z(), location.position().orient()};
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new RoleRepositoryException(RoleRepositoryException.Code.INVALID_ARGUMENT, "non-finite location");
            }
        }
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- persist

    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", JSON_FORMAT_VERSION);
        if (!accounts.isEmpty()) {
            GoJson.Object accountsNode = new GoJson.Object();
            List<String> keys = new ArrayList<>(accounts.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                accountsNode.raw(key, renderRole(accounts.get(key), 2));
            }
            document.raw("accounts", accountsNode.render(1));
        }
        if (!identities.isEmpty()) {
            GoJson.Object identitiesNode = new GoJson.Object();
            List<String> keys = new ArrayList<>(identities.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                identitiesNode.raw(key, renderIdentity(identities.get(key), 2));
            }
            document.raw("identities", identitiesNode.render(1));
        }
        if (nextAccountID != 0) {
            document.field("next_account_id", nextAccountID);
        }
        if (nextRoleID != 0) {
            document.field("next_role_id", nextRoleID);
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".roles-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.STORAGE, "write role repository");
        }
    }

    private static String renderRole(Roles.RoleValue value, int depth) {
        GoJson.Object node = new GoJson.Object();
        node.field("name", value.name());
        if (value.appearance() != null && !value.appearance().isEmpty()) {
            List<String> items = new ArrayList<>();
            for (String item : value.appearance()) {
                items.add(GoJson.quote(item));
            }
            node.raw("appearance", GoJson.renderArray(items, depth + 1));
        }
        GoJson.Object scene = new GoJson.Object();
        scene.field("config", value.scene().config());
        scene.field("resource", value.scene().resource());
        node.raw("scene", scene.render(depth + 1));
        GoJson.Object position = new GoJson.Object();
        position.floatField("x", value.position().x());
        position.floatField("y", value.position().y());
        position.floatField("z", value.position().z());
        position.floatField("orient", value.position().orient());
        node.raw("position", position.render(depth + 1));
        return node.render(depth);
    }

    private static String renderIdentity(JsonIdentity identity, int depth) {
        GoJson.Object node = new GoJson.Object();
        node.field("account_id", identity.accountId);
        if (identity.roleId != 0) {
            node.field("role_id", identity.roleId);
        }
        node.field("status", identity.status);
        node.field("account_version", identity.accountVersion);
        if (identity.slot != 0) {
            node.field("slot", identity.slot);
        }
        if (identity.roleVersion != 0) {
            node.field("role_version", identity.roleVersion);
        }
        if (identity.appearanceVersion != 0) {
            node.field("appearance_version", identity.appearanceVersion);
        }
        if (identity.locationVersion != 0) {
            node.field("location_version", identity.locationVersion);
        }
        if (identity.deleteRequested) {
            node.field("delete_requested", true);
        }
        return node.render(depth);
    }
}
