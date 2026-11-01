package com.nineyin.role;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用 Go 侧导出的 seed/scenario golden 做字节级一致性验证：
 * 同一操作序列（provision→create→saveLocation→saveFaction）必须产出与 Go
 * MarshalIndent 完全相同的 roles.json。
 */
class JsonRoleRepositoryTest {

    private static final String SCENARIO_KEY =
            "acct:v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = JsonRoleRepositoryTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void scenarioMatchesGoByteForByte(@TempDir Path tempDir) throws Exception {
        Path rolesPath = tempDir.resolve("roles.json");
        Files.copy(probeGoldenFile("roles_seed.json"), rolesPath, StandardCopyOption.REPLACE_EXISTING);

        JsonRoleRepository repository = JsonRoleRepository.open(rolesPath);
        RoleStoreFacade store = new RoleStoreFacade(repository);

        RoleStoreFacade.LoginResult login = store.login(SCENARIO_KEY);
        assertNotNull(login.account());
        assertNull(login.snapshot());

        Roles.RoleSnapshot created = store.create(login.account(), "金镶玉", null);
        assertEquals("金镶玉", created.name());
        assertEquals("force_yihua", RoleStoreFacade.roleFaction(created.appearance().values()));

        created = store.saveLocation(created, new Roles.Location(
                new Roles.Scene("ini\\scene\\city05_ChengDu", "city05"),
                new Roles.Position(119.07f, 21.55f, 901.74f, 1.05f), created.location().version()));
        created = store.saveFaction(created, "force_emei");
        assertEquals(2, created.location().version());
        assertEquals(2, created.appearance().version());

        assertArrayEquals(golden("roles_scenario.json"), Files.readAllBytes(rolesPath));
    }

    private Path probeGoldenFile(String name) throws Exception {
        // 把 classpath golden 拷到 tempDir 的辅助：seed 通过 golden() 读取。
        Path seed = Files.createTempFile("roles-seed", ".json");
        Files.write(seed, golden(name));
        return seed;
    }

    @Test
    void legacySingleRoleDocumentImported() throws Exception {
        Path legacy = Files.createTempFile("legacy-roles", ".json");
        Files.writeString(legacy, "{\"name\":\"沙涛\",\"appearance\":[\"book4\"],"
                + "\"scene_config\":\"ini\\\\scene\\\\city05_ChengDu\",\"scene_resource\":\"city05\","
                + "\"PosX\":100.5,\"PosY\":10.0,\"PosZ\":20.0,\"Orient\":3.0}");
        JsonRoleRepository repository = JsonRoleRepository.open(legacy);
        Roles.Account account = repository.provisionLegacy("local_default");
        // legacy 键即 local_default，ProvisionLegacy 返回已存在的 identity 而非新建。
        var summaries = repository.listByAccount(account.id());
        assertEquals(1, summaries.size());
        assertEquals("沙涛", summaries.get(0).name());
        repository.close();
    }

    @Test
    void normalizeAppliesCompatibilityDefaults() throws Exception {
        Path doc = Files.createTempFile("normalize-roles", ".json");
        Files.writeString(doc, "{\"version\":1,\"accounts\":{\"acct:v1:aa\":{\"name\":\"X\","
                + "\"appearance\":[],\"scene\":{\"config\":\"\",\"resource\":\"scene07\"},"
                + "\"position\":{\"x\":0,\"y\":0,\"z\":0,\"orient\":0}}}}");
        JsonRoleRepository repository = JsonRoleRepository.open(doc);
        RoleStoreFacade store = new RoleStoreFacade(repository);
        RoleStoreFacade.LoginResult login = store.login("acct:v1:aa");
        assertNotNull(login.snapshot());
        assertEquals("ini\\scene\\school07_WuDang", login.snapshot().location().scene().config());
        assertEquals("school07", login.snapshot().location().scene().resource());
        assertEquals(479.75f, login.snapshot().location().position().x());
        assertEquals(87.5f, login.snapshot().location().position().y());
        assertEquals(624.5f, login.snapshot().location().position().z());
        assertEquals(3.12f, login.snapshot().location().position().orient());
    }

    @Test
    void createRejectsDuplicateNameAndSlot() throws Exception {
        Path doc = Files.createTempFile("dup-roles", ".json");
        Files.writeString(doc, "{\"version\":1,\"accounts\":{}}");
        JsonRoleRepository repository = JsonRoleRepository.open(doc);
        Roles.Account account = repository.provisionLegacy("acct:v1:bb");
        Roles.RoleSnapshot first = repository.create(account.id(), new Roles.NewRole(0, "一号",
                new Roles.Appearance(1, java.util.List.of()), RoleStoreFacade.defaultRoleLocation()));
        assertNotNull(first);

        RoleRepositoryException slotTaken = assertThrows(RoleRepositoryException.class,
                () -> repository.create(account.id(), new Roles.NewRole(0, "二号",
                        new Roles.Appearance(1, java.util.List.of()), RoleStoreFacade.defaultRoleLocation())));
        assertTrue(slotTaken.is(RoleRepositoryException.Code.ROLE_SLOT_TAKEN));

        Roles.Account other = repository.provisionLegacy("acct:v1:cc");
        RoleRepositoryException nameTaken = assertThrows(RoleRepositoryException.class,
                () -> repository.create(other.id(), new Roles.NewRole(0, "一号",
                        new Roles.Appearance(1, java.util.List.of()), RoleStoreFacade.defaultRoleLocation())));
        assertTrue(nameTaken.is(RoleRepositoryException.Code.ROLE_NAME_TAKEN));
    }

    @Test
    void saveLocationUsesCasVersion() throws Exception {
        Path doc = Files.createTempFile("cas-roles", ".json");
        Files.writeString(doc, "{\"version\":1,\"accounts\":{}}");
        JsonRoleRepository repository = JsonRoleRepository.open(doc);
        Roles.Account account = repository.provisionLegacy("acct:v1:dd");
        Roles.RoleSnapshot created = repository.create(account.id(), new Roles.NewRole(0, "甲",
                new Roles.Appearance(1, java.util.List.of()), RoleStoreFacade.defaultRoleLocation()));

        Roles.Location moved = new Roles.Location(
                new Roles.Scene("ini\\scene\\city05_ChengDu", "city05"),
                new Roles.Position(1, 2, 3, 4), 999);
        RoleRepositoryException conflict = assertThrows(RoleRepositoryException.class,
                () -> repository.saveLocation(created.id(), 999, moved));
        assertTrue(conflict.is(RoleRepositoryException.Code.VERSION_CONFLICT));

        long next = repository.saveLocation(created.id(), created.location().version(), moved);
        assertEquals(created.location().version() + 1, next);
    }
}
