package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.gm.GmHub;
import com.nineyin.util.ServerPaths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 优雅停机落盘：shutdownFlush 对登记会话执行断连同款持久化
 * （pendingLocation + faculty + meridian），且幂等（二次刷写为空）。
 */
class GracefulShutdownTest {

    private ScheduledExecutorService scheduler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // LoginFlow 自持 meridians/customizing 存档按 ServerPaths 落盘——
        // 指到临时目录，避免测试写进仓库运行根 data/。
        ServerPaths.overrideServerRoot(tempDir);
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "test-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void shutdownFlushPersistsRegisteredSessions() throws Exception {
        JsonRoleRepository repository = JsonRoleRepository.open(tempDir.resolve("roles.json"));
        RoleStoreFacade store = new RoleStoreFacade(repository);
        FacultyStore faculty = FacultyStore.open(tempDir.resolve("faculty.json"));
        LoginFlow flow = new LoginFlow(store, faculty, new GmHub(),
                Clock.systemUTC(), null, scheduler);

        RoleStoreFacade.LoginResult login = store.login("graceful-shutdown-test");
        assertNotNull(login.account(), "空库登录应自动建账号");
        Roles.RoleSnapshot created = store.create(login.account(), "紫泉", null);

        LoginSession session = new LoginSession();
        session.selected = created;
        session.player = PlayerActor.create("紫泉", 0, Clock.systemUTC());
        // 未落盘的移动（停机前最后一次位置更新）。
        session.pendingLocation = new Roles.Location(
                new Roles.Scene("ini\\scene\\city05_ChengDu", "city05"),
                new Roles.Position(119.07f, 21.55f, 901.74f, 1.05f),
                created.location().version());
        flow.trackActive(session);

        assertEquals(1, flow.shutdownFlush(), "应刷写 1 个登记会话");

        // 位置：重开仓库按 key 登录可读回移动后的坐标（version CAS 已通过）。
        Roles.RoleSnapshot persisted = new RoleStoreFacade(
                JsonRoleRepository.open(tempDir.resolve("roles.json")))
                .login("graceful-shutdown-test").snapshot();
        assertNotNull(persisted);
        assertEquals(119.07f, persisted.location().position().x());
        assertEquals(901.74f, persisted.location().position().z());

        // 内功：faculty 存档出现该角色快照。
        assertNotNull(faculty.load(created.id()));

        // 经脉：自持存档已落临时运行根 data/meridians.json。
        assertTrue(Files.exists(tempDir.resolve("data").resolve("meridians.json")),
                "meridian 存档应在停机刷写时落盘");

        // 幂等：注册表已清，二次刷写为空。
        assertEquals(0, flow.shutdownFlush());
    }

    @Test
    void shutdownFlushWithoutSessionsIsNoop() {
        LoginFlow flow = new LoginFlow(
                new RoleStoreFacade(JsonRoleRepository.open(tempDir.resolve("roles.json"))),
                FacultyStore.open(tempDir.resolve("faculty.json")),
                new GmHub(), Clock.systemUTC(), null, scheduler);
        assertEquals(0, flow.shutdownFlush());
    }
}
