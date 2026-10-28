package com.nineyin.server;

import com.nineyin.db.RoleStore;
import com.nineyin.game.SceneFlow;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.SceneManager;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import com.swingfrog.summer.ecs.TickScheduler;
import com.swingfrog.summer.protocol.custom.ProtocolCustomMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 九阴服务端入口：FxNet2 自定义协议 + 登录状态机 + 场景同步，
 * 仅加载网络模块（DB/Redis 模块在对应阶段按需开启）。
 */
public final class NineyinServer implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(NineyinServer.class);

    private final RoleStore roleStore;
    private final SceneManager sceneManager;
    private final FxOpcodeRouter router;
    private final List<FxSession> sessions = new CopyOnWriteArrayList<>();
    private TickScheduler clockScheduler;

    public NineyinServer() {
        this.roleStore = new RoleStore(Path.of("data", "roles.json"));
        this.sceneManager = new SceneManager();
        this.router = new FxOpcodeRouter();

        LoginFlow loginFlow = new LoginFlow(roleStore);
        SceneFlow sceneFlow = new SceneFlow(sceneManager, roleStore);
        loginFlow.addReadyHook(sceneFlow::sendSceneNpcs);
        loginFlow.register(router);
        sceneFlow.register(router);
    }

    public FxOpcodeRouter router() {
        return router;
    }

    @Override
    public void init() {
        FxConnectionListener listener = new FxConnectionListener() {
            @Override
            public void onConnected(FxSession session) {
                sessions.add(session);
            }

            @Override
            public void onDisconnected(FxSession session) {
                sessions.remove(session);
                LoginSession login = session.channel().attr(LoginFlow.SESSION).get();
                if (login != null) {
                    roleStore.updateLocation(login.accountKey, login.x, login.y, login.z, login.orient);
                }
                roleStore.flush();
            }
        };
        ProtocolCustomMgr.get().setHandler(new FxProtocolCustomHandler(router, listener));
        log.info("nineyin server init: FxNet2 custom protocol registered");
    }

    @Override
    public void start() {
        // 周期时间同步：0x38（约 2 秒）+ TimeTick 属性（UI 时钟）
        clockScheduler = new TickScheduler("nineyin-clock", 2000);
        clockScheduler.addTask((tick, now) -> {
            LocalDateTime time = LocalDateTime.now();
            int minutes = time.getHour() * 60 + time.getMinute();
            for (FxSession fx : sessions) {
                LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
                if (login == null || !login.uiBootstrapped) {
                    continue;
                }
                long elapsed = System.nanoTime() / 1_000_000L - login.bootSteadyMs;
                fx.send(S2CPackets.timeSync((int) (elapsed & 0xFFFFFFFFL), login.timeSeq++));
                fx.send(S2CPackets.objectPropertyInt(PropertyId.TIME_TICK, minutes));
            }
        });
        clockScheduler.start();
        log.info("nineyin server started (share root: {})", SceneManager.locateShareRoot());
    }

    @Override
    public void stop() {
        if (clockScheduler != null) {
            clockScheduler.close();
        }
        roleStore.flush();
        log.info("nineyin server stopped");
    }

    public static void main(String[] args) {
        Summer.addModuleNet();
        String config = NineyinServer.class.getClassLoader()
                .getResource("nineyin/server.properties").getPath();
        Summer.hot(SummerConfig.newBuilder()
                .app(new NineyinServer())
                .serverProperties(config)
                .build());
    }
}
