package com.nineyin.server;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import com.swingfrog.summer.protocol.custom.ProtocolCustomMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 九阴服务端入口：注册 FxNet2 自定义协议 + 登录状态机，仅加载网络模块
 * （DB/Redis 模块在对应阶段按需开启）。
 */
public final class NineyinServer implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(NineyinServer.class);

    private final RoleStore roleStore;
    private final FxOpcodeRouter router;

    public NineyinServer() {
        this.roleStore = new RoleStore(Path.of("data", "roles.json"));
        this.router = new FxOpcodeRouter();
        new LoginFlow(roleStore).register(router);
    }

    public FxOpcodeRouter router() {
        return router;
    }

    @Override
    public void init() {
        ProtocolCustomMgr.get().setHandler(new FxProtocolCustomHandler(router));
        log.info("nineyin server init: FxNet2 custom protocol registered");
    }

    @Override
    public void start() {
        log.info("nineyin server started");
    }

    @Override
    public void stop() {
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
