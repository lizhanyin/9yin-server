package com.nineyin.server;

import com.nineyin.db.JsonRoleStore;
import com.nineyin.db.MySqlRoleStore;
import com.nineyin.db.MysqlDsn;
import com.nineyin.db.RoleStore;
import com.nineyin.game.SceneFlow;
import com.nineyin.game.SelectFlow;
import com.nineyin.gm.GmCommand;
import com.nineyin.gm.GmHttpServer;
import com.nineyin.gm.GmHub;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.Appearance;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.SceneManager;
import com.nineyin.shop.ShopRepository;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import com.swingfrog.summer.ecs.tick.TickScheduler;
import com.swingfrog.summer.protocol.custom.ProtocolCustomMgr;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 九阴服务端入口：FxNet2 自定义协议 + 登录状态机 + 场景同步 + GM 面板，
 * 仅加载网络模块（DB/Redis 模块在对应阶段按需开启）。
 */
public final class NineyinServer implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(NineyinServer.class);

    public static final AttributeKey<Long> GM_SESSION_ID =
            AttributeKey.valueOf("nineyin.gm.sessionId");

    private final RoleStore roleStore;
    private final SceneManager sceneManager;
    private final FxOpcodeRouter router;
    private final ShopRepository shopRepository;
    private final List<FxSession> sessions = new CopyOnWriteArrayList<>();
    private final Map<Long, FxSession> sessionById = new ConcurrentHashMap<>();
    private final AtomicLong sessionIds = new AtomicLong();
    private final GmHub gmHub = new GmHub();
    private final GmHttpServer gmServer;
    private TickScheduler clockScheduler;

    public NineyinServer() {
        this.roleStore = openRoleStore();
        this.sceneManager = new SceneManager();
        this.router = new FxOpcodeRouter();
        this.gmServer = new GmHttpServer(gmHub);

        LoginFlow loginFlow = new LoginFlow(roleStore);
        SceneFlow sceneFlow = new SceneFlow(sceneManager, roleStore);
        loginFlow.addReadyHook(sceneFlow::sendSceneNpcs);
        loginFlow.register(router);
        sceneFlow.register(router);
        new SelectFlow(sceneManager).register(router);
        shopRepository = new ShopRepository();
        shopRepository.load(SceneManager.locateShareRoot().resolve("trade").resolve("shop.ini"));
    }

    public FxOpcodeRouter router() {
        return router;
    }

    /**
     * 角色存储：优先 MySQL（NINEYIN_MYSQL_DSN，缺省同 mysql.env），
     * 连不上自动退回 JSON 文件单后端。MySQL 后端同时双写 JSON 兜底。
     */
    private static RoleStore openRoleStore() {
        String dsn = System.getenv("NINEYIN_MYSQL_DSN");
        if (dsn == null || dsn.isEmpty()) {
            dsn = "nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin";
        }
        try {
            MySqlRoleStore mysql = new MySqlRoleStore(MysqlDsn.parse(dsn), Path.of("data", "roles.json"));
            log.info("role store backend: MySQL (dsn db={})", MysqlDsn.parse(dsn).database());
            return mysql;
        } catch (Exception e) {
            log.warn("MySQL unavailable ({}), falling back to JSON-only role store", e.getMessage());
            return new JsonRoleStore(Path.of("data", "roles.json"));
        }
    }

    @Override
    public void init() {
        FxConnectionListener listener = new FxConnectionListener() {
            @Override
            public void onConnected(FxSession session) {
                sessions.add(session);
                long id = sessionIds.incrementAndGet();
                session.channel().attr(GM_SESSION_ID).set(id);
                sessionById.put(id, session);
                GmHub.PlayerSnapshot snap = new GmHub.PlayerSnapshot();
                snap.sessionId = id;
                snap.remote = session.remoteAddress();
                gmHub.attach(id, snap);
            }

            @Override
            public void onDisconnected(FxSession session) {
                sessions.remove(session);
                Long id = session.channel().attr(GM_SESSION_ID).get();
                if (id != null) {
                    sessionById.remove(id);
                    gmHub.detach(id);
                }
                LoginSession login = session.channel().attr(LoginFlow.SESSION).get();
                if (login != null) {
                    roleStore.updateLocation(login.accountKey, login.x, login.y, login.z, login.orient);
                }
                roleStore.flush();
            }
        };
        ProtocolCustomMgr.get().setHandler(
                new FxProtocolCustomHandler(router, listener, "127.0.0.1", 19061));
        log.info("nineyin server init: FxNet2 + svrlist protocols registered");
    }

    @Override
    public void start() {
        GmHub.ServerStatus status = gmHub.status();
        status.listenAddr = "0.0.0.0:19061 (game), 0.0.0.0:4000 (area)";
        status.serverRoot = SceneManager.locateShareRoot().toString();
        gmServer.start();

        clockScheduler = new TickScheduler("nineyin-clock", 2000);
        clockScheduler.addTask((tick, now) -> {
            LocalDateTime time = LocalDateTime.now();
            int minutes = time.getHour() * 60 + time.getMinute();
            for (FxSession fx : sessions) {
                LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
                if (login == null) {
                    continue;
                }
                if (login.uiBootstrapped) {
                    long elapsed = System.nanoTime() / 1_000_000L - login.bootSteadyMs;
                    fx.send(S2CPackets.timeSync((int) (elapsed & 0xFFFFFFFFL), login.timeSeq++));
                    fx.send(S2CPackets.objectPropertyInt(PropertyId.TIME_TICK, minutes));
                }
                Long id = fx.channel().attr(GM_SESSION_ID).get();
                if (id != null) {
                    updateSnapshot(id, login);
                    consumeGmCommands(id, fx);
                }
            }
        });
        clockScheduler.start();
        log.info("nineyin server started (share root: {}, gm: {})",
                status.serverRoot, gmServer.url());
    }

    private void updateSnapshot(long id, LoginSession login) {
        gmHub.update(id, snap -> {
            snap.name = login.roleName;
            snap.sceneConfig = login.spawnConfig;
            snap.sceneResource = login.spawnResource;
            snap.x = login.x;
            snap.y = login.y;
            snap.z = login.z;
            snap.orient = login.orient;
            if (snap.faction.isEmpty()) {
                snap.faction = Appearance.get(login.appearance, 3, "");
            }
        });
    }

    /** 消费 GM 命令：kick 立即生效，其余记日志（数值系统接入后逐个实现）。 */
    private void consumeGmCommands(long id, FxSession fx) {
        GmCommand cmd;
        while ((cmd = gmHub.tryPop(id)) != null) {
            switch (cmd.action()) {
                case GmCommand.ACTION_KICK -> {
                    log.info("GM kick session={} {}", id, fx.remoteAddress());
                    fx.close();
                }
                default -> log.info("GM command pending (not implemented): id={} action={}",
                        id, cmd.action());
            }
        }
    }

    @Override
    public void stop() {
        if (clockScheduler != null) {
            clockScheduler.close();
        }
        gmServer.stop();
        roleStore.flush();
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

    /**
     * ConfigMgr 只接受文件系统路径；jar 内运行时资源无真实路径，
     * 落临时文件传递。file 协议下用 toURI() 规避中文目录的 URL 编码。
     */
    private static String materializeServerProperties() throws IOException {
        URL url = NineyinServer.class.getClassLoader().getResource("nineyin/server.properties");
        if (url == null) {
            throw new IllegalStateException("nineyin/server.properties missing on classpath");
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
