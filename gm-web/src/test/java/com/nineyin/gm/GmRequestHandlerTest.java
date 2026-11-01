package com.nineyin.gm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EmbeddedChannel 上的 gm-web 网关全链路（页面路由 + 动态代理 + 本地目录）。 */
class GmRequestHandlerTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String ADDR = "127.0.0.1:19081";

    /** 记录调用的 stub：可配置响应或离线。 */
    private static final class StubRpc implements GameServerRpc {

        final List<String> calls = new ArrayList<>();
        String statusJson = "{\"running\":true,\"connected_clients\":2,\"phase\":\"stub\"}";
        String sessionsJson = "[]";
        String commandJson = "{\"ok\":true,\"message\":\"queued\"}";
        String minimalLoginJson = "{\"enabled\":false}";
        boolean offline;

        @Override
        public String call(String host, int port, String method, Map<String, Object> data) {
            calls.add(host + ":" + port + "/" + method);
            if (offline) {
                throw new GameServerOfflineException("stub offline");
            }
            return switch (method) {
                case "status" -> statusJson;
                case "sessions" -> sessionsJson;
                case "command" -> commandJson;
                case "minimalLogin" -> minimalLoginJson;
                case "toggleMinimalLogin" -> "{\"enabled\":true}";
                default -> throw new IllegalArgumentException(method);
            };
        }
    }

    private StubRpc rpc;
    private GmCatalog catalog;

    @BeforeEach
    void setup(@TempDir Path tmp) throws IOException {
        rpc = new StubRpc();
        Path share = Files.createDirectories(tmp.resolve("resources").resolve("modern").resolve("share"));
        Path data = Files.createDirectories(tmp.resolve("data"));
        // GBK INI：克隆目录 + 天气
        Files.write(Files.createDirectories(share.resolve("clone")).resolve("cloneinfo.ini"), (
                "[ini\\scene\\clone001]\nDesc=孔雀山庄\nEasyPowerLevel=50\n"
                        + "[ini\\scene\\clone002]\nDesc=龙门客栈\n").getBytes(GBK));
        Files.write(Files.createDirectories(share.resolve("rule").resolve("weather"))
                .resolve("weathereffect.ini"), "[light_rain]\nConfigID=rain01\n".getBytes(GBK));
        // data JSON：称号 + 经脉
        Files.write(data.resolve("称号数据.json"),
                "{\"catalog\":{\"titles\":[{\"id\":1,\"name\":\"小沙弥\",\"type\":1}]}}".getBytes(StandardCharsets.UTF_8));
        Files.write(data.resolve("经脉.json"),
                "{\"meridians\":[{\"id\":\"jm_x\",\"name\":\"手少阴心经\",\"maxLevel\":216}]}".getBytes(StandardCharsets.UTF_8));
        catalog = GmCatalog.createForTest(share, data);
    }

    /** 每次请求独立通道（一请求一连接语义，与 GmHttpServer 行为一致）。 */
    private FullHttpResponse request(String method, String uri, String body) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new GmHttpServer.GmChannelHandler(new GmRequestHandler(catalog, rpc)));
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.valueOf(method), uri,
                body == null ? Unpooled.EMPTY_BUFFER
                        : Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, req.content().readableBytes());
        channel.writeInbound(req);
        FullHttpResponse resp = channel.readOutbound();
        channel.finishAndReleaseAll();
        assertNotNull(resp);
        return resp;
    }

    private static String content(FullHttpResponse resp) {
        return resp.content().toString(CharsetUtil.UTF_8);
    }

    @Test
    void pagesServed() {
        FullHttpResponse index = request("GET", "/", null);
        assertEquals(200, index.status().code());
        assertTrue(content(index).contains("九阴 GM · 服务器管理"), "根路径应服务管理页 index.html");

        FullHttpResponse console = request("GET", "/console.html", null);
        assertEquals(200, console.status().code());
        assertTrue(content(console).contains("九阴本地 GM"));
        assertTrue(console.headers().get("Content-Type").startsWith("text/html"));
    }

    @Test
    void manageStatusPollsGameServer() {
        FullHttpResponse resp = request("GET", "/api/manage/status?s=" + ADDR, null);
        assertEquals(200, resp.status().code());
        JSONObject o = JSON.parseObject(content(resp));
        assertTrue(o.getBoolean("online"));
        assertEquals("stub", o.getJSONObject("status").getString("phase"));

        rpc.offline = true;
        JSONObject offline = JSON.parseObject(content(request("GET", "/api/manage/status?s=" + ADDR, null)));
        assertFalse(offline.getBoolean("online"));

        assertEquals(400, request("GET", "/api/manage/status?s=evil.com:1", null).status().code());
    }

    @Test
    void statusMergesPanelFields() {
        JSONObject o = JSON.parseObject(content(request("GET", "/api/status?__s=" + ADDR, null)));
        assertTrue(o.getBoolean("game_connected"));
        assertTrue(o.containsKey("panel_uptime_sec"));
        assertEquals(2, o.getIntValue("connected_clients"));

        rpc.offline = true;
        JSONObject degraded = JSON.parseObject(content(request("GET", "/api/status?__s=" + ADDR, null)));
        assertFalse(degraded.getBoolean("game_connected"));
        assertFalse(degraded.getBoolean("running"));
    }

    @Test
    void sessionsProxyAndDegrade() {
        rpc.sessionsJson = "[{\"sessionId\":1,\"name\":\"测试侠\",\"scene\":{\"config\":\"ini\\\\scene\\\\city05_ChengDu\"}}]";
        JSONArray arr = JSON.parseArray(content(request("GET", "/api/sessions?__s=" + ADDR, null)));
        assertEquals("测试侠", arr.getJSONObject(0).getString("name"));

        rpc.offline = true;
        assertEquals(0, JSON.parseArray(content(request("GET", "/api/sessions?__s=" + ADDR, null))).size());
        assertEquals(400, request("GET", "/api/sessions", null).status().code(), "缺 __s 应 400");
    }

    @Test
    void commandProxiedWithParams() {
        FullHttpResponse resp = request("POST", "/api/sessions/3/command?__s=" + ADDR,
                "{\"action\":\"add_item\",\"itemConfig\":\"box_1\",\"amount\":2}");
        assertEquals(200, resp.status().code());
        assertTrue(JSON.parseObject(content(resp)).getBoolean("ok"));
        assertTrue(rpc.calls.contains("127.0.0.1:19081/command"));

        assertEquals(400, request("POST", "/api/sessions/3/command", "{}").status().code(), "缺 action 应 400");
        assertEquals(400, request("POST", "/api/sessions/abc/command?__s=" + ADDR, "{\"action\":\"kick\"}")
                .status().code());

        rpc.offline = true;
        JSONObject offline = JSON.parseObject(content(
                request("POST", "/api/sessions/3/command?__s=" + ADDR, "{\"action\":\"kick\"}")));
        assertFalse(offline.getBoolean("ok"));
    }

    @Test
    void commandRefusalMappedToConflictError() {
        // 游戏侧 ok=false（未实现动作/数值域/未确认发送）→ 409 {"error":…}，面板 JS 才会告警。
        rpc.commandJson = "{\"ok\":false,\"message\":\"unsupported action: kick\"}";
        FullHttpResponse resp = request("POST", "/api/sessions/3/command?__s=" + ADDR,
                "{\"action\":\"kick\"}");
        assertEquals(409, resp.status().code());
        assertEquals("unsupported action: kick", JSON.parseObject(content(resp)).getString("error"));
    }

    @Test
    void minimalLoginGetAndToggleProxied() {
        assertFalse(JSON.parseObject(content(
                request("GET", "/api/minimal-login?__s=" + ADDR, null))).getBoolean("enabled"));
        assertTrue(JSON.parseObject(content(
                request("POST", "/api/minimal-login?__s=" + ADDR, null))).getBoolean("enabled"));
        assertTrue(rpc.calls.contains("127.0.0.1:19081/toggleMinimalLogin"));
    }

    @Test
    void catalogEndpointsServeFromFiles() {
        JSONArray clones = JSON.parseArray(content(request("GET", "/api/clones", null)));
        assertEquals(2, clones.size());
        assertEquals("孔雀山庄", clones.getJSONObject(0).getString("description"));

        JSONArray weather = JSON.parseArray(content(request("GET", "/api/weather", null)));
        assertEquals("rain01", weather.getJSONObject(0).getString("TXID"));

        JSONArray titles = JSON.parseArray(content(request("GET", "/api/titles", null)));
        assertEquals("小沙弥", titles.getJSONObject(0).getString("name"));

        JSONArray meridians = JSON.parseArray(content(request("GET", "/api/meridians", null)));
        assertEquals("jm_x", meridians.getJSONObject(0).getString("id"));

        // 目录数据缺失的接口应优雅降级为空而不是 500
        assertEquals(200, request("GET", "/api/mounts", null).status().code());
        assertEquals(0, JSON.parseArray(content(request("GET", "/api/mounts", null))).size());
        JSONObject origin = JSON.parseObject(content(request("GET", "/api/origin/status", null)));
        assertFalse(origin.getBoolean("loaded"));
    }

    @Test
    void unknownAndPreflight() {
        assertEquals(404, request("GET", "/api/unknown", null).status().code());
        FullHttpResponse resp = request("OPTIONS", "/api/status", null);
        assertEquals(204, resp.status().code());
        assertEquals("GET, POST, OPTIONS", resp.headers().get("Access-Control-Allow-Methods"));
    }
}
