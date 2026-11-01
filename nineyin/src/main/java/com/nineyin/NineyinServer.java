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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.game.ServerPaths;
import com.nineyin.world.npc.SceneNpcRegistry;
import com.nineyin.protocol.NpcSchema;
import com.nineyin.game.avatar.neigong.FacultyStore;
import com.nineyin.gm.GmCatalog;
import com.nineyin.gm.GmCatalogDb;
import com.nineyin.gm.GmHttpServer;
import com.nineyin.gm.GmHub;
import com.nineyin.role.JsonRoleRepository;
import com.nineyin.role.MySqlRoleRepository;
import com.nineyin.role.RoleRepository;
import com.nineyin.role.RoleStoreFacade;
import com.nineyin.server.LoginFlow;
import com.nineyin.server.MySqlEnv;
import com.nineyin.server.NineyinProtocolHandler;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import com.swingfrog.summer.protocol.custom.ProtocolCustomMgr;

/**
 * 九阴服务端入口（一期：按 Go 源码 9yin-go-server1 复刻登录链）。
 * 装配：serverRoot 定位 → 角色存档（DSN→MySQL / 默认 JSON）→ faculty.json →
 * Summer 网络模块接管（19061 登录链 + 4000 svrlist）→ GM HTTP 面板（默认 127.0.0.1:19080）。
 */
public final class NineyinServer implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(NineyinServer.class);

    public static final String GM_HOST_PROPERTY = "nineyin.gm.host";
    public static final String GM_PORT_PROPERTY = "nineyin.gm.port";

    private final GmHub gmHub = new GmHub();
    private GmHttpServer gmServer;
    private GmCatalog gmCatalog;
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
        gmCatalog = GmCatalog.create(ServerPaths.projectPath("resources", "modern", "share"));
        dsn.ifPresent(this::attachCatalogDatabase);
        gmServer = new GmHttpServer(gmHub, gmCatalog, gmHost(), gmPort());
        // Go -npc-all-chengdu 默认 true：启动加载共享模板表 + 每 creator 目录懒加载。
        npcRegistry = openNpcRegistry();
        sceneScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nineyin-scene");
            thread.setDaemon(true);
            return thread;
        });
        GmHub.ServerStatus status = gmHub.status();
        status.serverRoot = ServerPaths.serverRoot().toString();
        status.phase = "Go-replica phase 2 (starter chain + NPC viewport)";
        ProtocolCustomMgr.get().setHandler(new NineyinProtocolHandler(
                new LoginFlow(roleStore, facultyStore, gmHub, Clock.systemDefaultZone(),
                        npcRegistry, sceneScheduler),
                "127.0.0.1", gamePort()));
        log.info("nineyin server initialized: root={} storage={} gmPanel={} npcRegistry={}",
                ServerPaths.serverRoot(), repository.getClass().getSimpleName(), gmServer.url(),
                npcRegistry != null);
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

    /** GM 目录 data/*.json 来源切 MySQL（角色库同一 DSN）；打开失败仅告警回退文件源。 */
    private void attachCatalogDatabase(String dsn) {
        try {
            gmCatalog.useDatabase(GmCatalogDb.open(dsn));
            log.info("GM catalog sources attached to MySQL");
        } catch (RuntimeException e) {
            log.warn("GM catalog MySQL unavailable, fallback to data/ files: {}", e.getMessage());
        }
    }

    private static RoleRepository openRoleRepository(java.util.Optional<String> dsn) {
        if (dsn.isPresent()) {
            return MySqlRoleRepository.open(dsn.get());
        }
        return JsonRoleRepository.open(ServerPaths.projectPath("data", "roles.json"));
    }

    /** GM 面板监听地址：系统属性覆盖，默认仅 127.0.0.1（对齐 Go/C++ 控制台不对外暴露）。 */
    static String gmHost() {
        return System.getProperty(GM_HOST_PROPERTY, GmHttpServer.DEFAULT_HOST);
    }

    static int gmPort() {
        return Integer.parseInt(System.getProperty(GM_PORT_PROPERTY, String.valueOf(GmHttpServer.DEFAULT_PORT)));
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
        gmServer.start();
        gmHub.status().listenAddr = gmServer.url();
        log.info("nineyin server started");
    }

    @Override
    public void stop() {
        gmServer.stop();
        roleStore.close();
        gmCatalog.close();
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
