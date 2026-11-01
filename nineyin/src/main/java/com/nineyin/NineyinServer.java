package com.nineyin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;

import com.nineyin.server.world.db.RoleInventoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.util.ServerPaths;
import com.nineyin.server.world.npc.SceneNpcRegistry;
import com.nineyin.server.protocol.NpcSchema;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.gm.GmRemote;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.MySqlRoleRepository;
import com.nineyin.server.db.role.RoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.game.login.LoginFlow;
import com.nineyin.util.MySqlEnv;
import com.nineyin.server.handler.NineyinProtocolHandler;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import com.swingfrog.summer.protocol.custom.ProtocolCustomMgr;

/**
 * 九阴服务端入口（一期：按 Go 源码 9yin-go-server1 复刻登录链）。
 * 装配：serverRoot 定位 → 角色存档（DSN→MySQL / 默认 JSON）→ faculty.json →
 * Summer 网络模块接管（19061 登录链 + 4000 svrlist）→ GM RPC 端口
 * （minor.gm，默认 127.0.0.1:19081，供独立 gm-web 面板经 summer RPC 调 GmRemote）。
 * GM HTTP 面板已拆分至 gm-web 模块独立进程（19080）。
 */
public final class NineyinServer implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(NineyinServer.class);

    private final GmHub gmHub = new GmHub();
    private RoleStoreFacade roleStore;
    private FacultyStore facultyStore;
    private SceneNpcRegistry npcRegistry;
    private ScheduledExecutorService sceneScheduler;

    @Override
    public void init() {
        // Go openRoleStore：MySQL DSN 优先，否则 data/roles.json；DSN 来源见 MySqlEnv（env > mysql.env）。
        java.util.Optional<String> dsn = MySqlEnv.loadDsn();
        RoleRepository repository = openRoleRepository(dsn);
        roleStore = new RoleStoreFacade(repository);
        facultyStore = FacultyStore.open(ServerPaths.projectPath("data", "faculty.json"));
        // 三期：内功进度 DB 双源（role_faculty 快照读优先/写同步，JSON 仍为主档）。
        dsn.ifPresent(d -> {
            try {
                facultyStore.attachDatabase(d);
                log.info("faculty store attached to MySQL role_faculty");
            } catch (RuntimeException e) {
                log.warn("faculty store MySQL unavailable, JSON only: {}", e.getMessage());
            }
        });
        // GM RPC：会话中枢挂到 GmRemote（minor.gm 端口，供 gm-web 独立面板调用）。
        GmRemote.attach(gmHub);
        // Go -npc-all-chengdu 默认 true：启动加载共享模板表 + 每 creator 目录懒加载。
        npcRegistry = openNpcRegistry();
        sceneScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nineyin-scene");
            thread.setDaemon(true);
            return thread;
        });
        GmHub.ServerStatus status = gmHub.status();
        status.serverRoot = ServerPaths.serverRoot().toString();
        status.listenAddr = "game:" + gamePort();
        status.phase = "Go-replica phase 2 (starter chain + NPC viewport)";
        RoleInventoryStore inventoryStore = null;
        if (dsn.isPresent()) {
            try {
                inventoryStore = RoleInventoryStore.open(dsn.get());
                log.info("role inventory store attached to MySQL");
            } catch (RuntimeException e) {
                log.warn("role inventory MySQL unavailable, starter fallback: {}", e.getMessage());
            }
        }
        ProtocolCustomMgr.get().setHandler(new NineyinProtocolHandler(
                new LoginFlow(roleStore, facultyStore, gmHub, Clock.systemDefaultZone(),
                        npcRegistry, sceneScheduler, inventoryStore),
                "127.0.0.1", gamePort()));
        log.info("nineyin server initialized: root={} storage={} gmRpc=127.0.0.1:{} npcRegistry={}",
                ServerPaths.serverRoot(), repository.getClass().getSimpleName(),
                System.getProperty("minor.gm.port", "19081"), npcRegistry != null);
    }

    /** Go newSceneNPCRegistry(npcconfig, creator 根)；失败仅告警（无 NPC 可进世界）。 */
    private SceneNpcRegistry openNpcRegistry() {
        try {
            java.nio.file.Path share = ServerPaths.projectPath("resources", "modern", "share");
            return SceneNpcRegistry.open(share.resolve("npc").resolve("npcconfig"),
                    share.resolve("creator").resolve("npc_creator"),
                    NpcSchema.visibleNpcModernV1());
        } catch (Exception e) {
            log.warn("modern scene NPC registry unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static RoleRepository openRoleRepository(java.util.Optional<String> dsn) {
        if (dsn.isPresent()) {
            return MySqlRoleRepository.open(dsn.get());
        }
        return JsonRoleRepository.open(ServerPaths.projectPath("data", "roles.json"));
    }

    private static int gamePort() {
        try (InputStream in = NineyinServer.class.getClassLoader().getResourceAsStream("server.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return Integer.parseInt(properties.getProperty("server.port", "19061").trim());
            }
        } catch (IOException ignored) {
        }
        return 19061;
    }

    @Override
    public void start() {
        log.info("nineyin server started");
    }

    @Override
    public void stop() {
        roleStore.close();
        facultyStore.close();
        log.info("nineyin server stopped");
    }

    public static void main(String[] args) throws IOException {
        Summer.addModuleNet();
        String config = materializeServerProperties();
        Summer.hot(SummerConfig.newBuilder()
                .app(new NineyinServer())
                .serverProperties(config)
                .build());
    }

    /** ConfigMgr 只接受文件系统路径；jar 内运行时资源落临时文件传递。 */
    private static String materializeServerProperties() throws IOException {
        var url = NineyinServer.class.getClassLoader().getResource("server.properties");
        if (url == null) {
            throw new IllegalStateException("server.properties missing on classpath");
        }
        if ("file".equals(url.getProtocol())) {
            try {
                return Path.of(url.toURI()).toString();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("server.properties url invalid: " + url, e);
            }
        }
        Path tmp = Files.createTempFile("nineyin-server", ".properties");
        tmp.toFile().deleteOnExit();
        try (InputStream in = url.openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp.toString();
    }
}
