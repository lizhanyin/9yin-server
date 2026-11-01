package com.nineyin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.game.avatar.neigong.FacultyStore;
import com.nineyin.game.ServerPaths;
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
 * Summer 网络模块接管（19061 登录链 + 4000 svrlist）。
 */
public final class NineyinServer implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(NineyinServer.class);

    private RoleStoreFacade roleStore;
    private FacultyStore facultyStore;

    @Override
    public void init() {
        // Go openRoleStore：MySQL DSN 优先，否则 data/roles.json；DSN 来源见 MySqlEnv（env > mysql.env）。
        RoleRepository repository = openRoleRepository();
        roleStore = new RoleStoreFacade(repository);
        facultyStore = FacultyStore.open(ServerPaths.projectPath("data", "faculty.json"));
        ProtocolCustomMgr.get().setHandler(new NineyinProtocolHandler(
                new LoginFlow(roleStore, facultyStore, Clock.systemDefaultZone()),
                "127.0.0.1", gamePort()));
        log.info("nineyin server initialized: root={} storage={}",
                ServerPaths.serverRoot(), repository.getClass().getSimpleName());
    }

    private static RoleRepository openRoleRepository() {
        java.util.Optional<String> dsn = MySqlEnv.loadDsn();
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
