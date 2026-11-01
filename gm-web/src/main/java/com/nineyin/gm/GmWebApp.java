package com.nineyin.gm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.swingfrog.summer.app.Summer;
import com.swingfrog.summer.app.SummerApp;
import com.swingfrog.summer.app.SummerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gm-web 独立面板进程入口：Summer.hot（框架要求自身起一个 server，默认
 * 127.0.0.1:19083，无外部用途；动态游戏服连接经 {@link SummerGameServerRpc}
 * 运行时注册，server.clientList 留空）+ 面板 HTTP（默认 127.0.0.1:19080：
 * / 管理页、/console.html 控制台、17 目录接口本地数据）。
 *
 * <p>面板端口可用系统属性覆盖：-Dnineyin.gm.host / -Dnineyin.gm.port。
 * 目录数据源：env NINEYIN_MYSQL_DSN / mysql.env（见 {@link GmEnv}）。
 */
public class GmWebApp implements SummerApp {

    private static final Logger log = LoggerFactory.getLogger(GmWebApp.class);

    public static final String HOST_PROPERTY = "nineyin.gm.host";
    public static final String PORT_PROPERTY = "nineyin.gm.port";

    private GmCatalog catalog;
    private GmHttpServer panel;

    @Override
    public void init() {
        catalog = GmCatalog.create(GmEnv.shareRoot());
        GmEnv.loadDsn().ifPresent(this::attachCatalogDatabase);
    }

    /** 目录 data/*.json 来源切 MySQL；打开失败仅告警回退文件源（与拆分前一致）。 */
    private void attachCatalogDatabase(String dsn) {
        try {
            catalog.useDatabase(GmCatalogDb.open(dsn));
            log.info("GM catalog sources attached to MySQL");
        } catch (RuntimeException e) {
            log.warn("GM catalog MySQL unavailable, fallback to data/ files: {}", e.getMessage());
        }
    }

    @Override
    public void start() {
        panel = new GmHttpServer(new GmRequestHandler(catalog, new SummerGameServerRpc()),
                host(), port());
        panel.start();
    }

    @Override
    public void stop() {
        if (panel != null) {
            panel.stop();
        }
        if (catalog != null) {
            catalog.close();
        }
    }

    static String host() {
        return System.getProperty(HOST_PROPERTY, GmHttpServer.DEFAULT_HOST);
    }

    static int port() {
        return Integer.parseInt(System.getProperty(PORT_PROPERTY,
                String.valueOf(GmHttpServer.DEFAULT_PORT)));
    }

    public static void main(String[] args) throws IOException {
        Summer.addModuleNet();
        String config = materializeServerProperties();
        Summer.hot(SummerConfig.newBuilder()
                .app(new GmWebApp())
                .projectPackage("com.nineyin.gm")
                .serverProperties(config)
                .build());
    }

    /** ConfigMgr 只接受文件系统路径；jar 内运行时资源落临时文件传递。 */
    private static String materializeServerProperties() throws IOException {
        var url = GmWebApp.class.getClassLoader().getResource("gm-web-server.properties");
        if (url == null) {
            throw new IllegalStateException("gm-web-server.properties missing on classpath");
        }
        if ("file".equals(url.getProtocol())) {
            try {
                return Path.of(url.toURI()).toString();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("gm-web-server.properties url invalid: " + url, e);
            }
        }
        Path tmp = Files.createTempFile("gm-web-server", ".properties");
        tmp.toFile().deleteOnExit();
        try (InputStream in = url.openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp.toString();
    }
}
