package com.nineyin.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** GmHttpServer 启动冒烟：随机端口起服，/api/status 与 /api/sessions 可达。 */
class GmHttpServerTest {

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void startsAndServesStatusAndSessions() throws Exception {
        int port = freePort();
        GmHub hub = new GmHub();
        GmHttpServer server = new GmHttpServer(hub, GmCatalog.empty(), "127.0.0.1", port);
        server.start();

        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = 7;
        snapshot.name = "GM冒烟角色";
        snapshot.faction = "force_yihua";
        snapshot.sceneConfig = "ini\\scene\\school09_YiHuaGong";
        hub.attach(7, snapshot);

        try {
            String status = get("http://127.0.0.1:" + port + "/api/status");
            assertTrue(status.contains("\"running\""), "status 应包含 running 字段: " + status);
            String sessions = get("http://127.0.0.1:" + port + "/api/sessions");
            assertTrue(sessions.contains("GM冒烟角色"), "sessions 应包含挂接的会话: " + sessions);
            // 面板显示名字段必须存在（资源表缺失时回退原编码，值不在此断言）。
            assertTrue(sessions.contains("\"factionName\""), "sessions 应含 factionName: " + sessions);
            assertTrue(sessions.contains("\"scene\"") && sessions.contains("\"name\""),
                    "sessions 的 scene 应含显示名: " + sessions);

            hub.detach(7);
            String afterDetach = get("http://127.0.0.1:" + port + "/api/sessions");
            assertTrue(!afterDetach.contains("GM冒烟角色"), "detach 后会话应消失: " + afterDetach);
            assertEquals(0, hub.onlineCount());
        } finally {
            server.stop();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try (var input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
