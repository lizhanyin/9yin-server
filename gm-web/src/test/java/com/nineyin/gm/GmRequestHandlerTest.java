package com.nineyin.gm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EmbeddedChannel 上的 GM HTTP 全链路（编解码 + 聚合 + 处理）。 */
class GmRequestHandlerTest {

    private static final Charset GBK = Charset.forName("GBK");

    private GmHub hub;
    private GmCatalog catalog;

    @BeforeEach
    void setup(@TempDir Path tmp) throws IOException {
        hub = new GmHub();
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
                new GmHttpServer.GmChannelHandler(new GmRequestHandler(hub, catalog)));
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
    void statusEndpoint() {
        hub.status().listenAddr = "0.0.0.0:19061";
        FullHttpResponse resp = request("GET", "/api/status", null);
        assertEquals(200, resp.status().code());
        JSONObject o = JSON.parseObject(content(resp));
        assertEquals(true, o.getBoolean("running"));
        assertEquals("0.0.0.0:19061", o.getString("listen_addr"));
        assertEquals(0, o.getIntValue("connected_clients"));
        assertEquals("*", resp.headers().get("Access-Control-Allow-Origin"));
    }

    @Test
    void sessionsEndpointNestedShape() {
        GmHub.PlayerSnapshot snap = new GmHub.PlayerSnapshot();
        snap.sessionId = 1;
        snap.name = "测试侠";
        snap.sceneConfig = "ini\\scene\\city05_ChengDu";
        snap.sceneResource = "city05";
        snap.x = 915.5f;
        hub.attach(1, snap);

        FullHttpResponse resp = request("GET", "/api/sessions", null);
        assertEquals(200, resp.status().code());
        JSONObject o = JSON.parseArray(content(resp)).getJSONObject(0);
        assertEquals("测试侠", o.getString("name"));
        assertEquals("ini\\scene\\city05_ChengDu", o.getJSONObject("scene").getString("config"));
        assertEquals("city05", o.getJSONObject("scene").getString("resource"));
        assertEquals(915.5f, o.getJSONObject("position").getFloatValue("x"), 0.001f);
        assertFalse(o.containsKey("guanYin"), "guanYin null 时不应序列化");
    }

    @Test
    void commandEndpointPassesParamsThrough() {
        FullHttpResponse resp = request("POST", "/api/sessions/3/command",
                "{\"action\":\"add_item\",\"itemConfig\":\"box_1\",\"amount\":2,\"wearNow\":true}");
        assertEquals(200, resp.status().code());
        assertTrue(JSON.parseObject(content(resp)).getBoolean("ok"));
        GmCommand cmd = hub.tryPop(3);
        assertNotNull(cmd);
        assertEquals("add_item", cmd.action());
        assertEquals("box_1", cmd.params().getString("itemConfig"));
        assertEquals(2, cmd.params().getIntValue("amount"));
        assertEquals(true, cmd.params().getBoolean("wearNow"));
        assertFalse(cmd.params().containsKey("action"), "action 不应混入 params");
    }

    @Test
    void commandValidation() {
        assertEquals(400, request("POST", "/api/sessions/3/command", "{}").status().code());
        assertEquals(400, request("POST", "/api/sessions/abc/command", "{\"action\":\"kick\"}")
                .status().code());
        assertEquals(404, request("GET", "/api/unknown", null).status().code());
    }

    @Test
    void minimalLoginToggleEndpoint() {
        FullHttpResponse resp = request("GET", "/api/minimal-login", null);
        assertEquals(200, resp.status().code());
        assertFalse(JSON.parseObject(content(resp)).getBoolean("enabled"));
        hub.toggleMinimalLogin();
        assertTrue(JSON.parseObject(content(request("GET", "/api/minimal-login", null)))
                .getBoolean("enabled"));
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
        assertEquals(216, meridians.getJSONObject(0).getIntValue("defaultMax"));

        // 目录数据缺失的接口应优雅降级为空而不是 500
        assertEquals(200, request("GET", "/api/mounts", null).status().code());
        assertEquals(0, JSON.parseArray(content(request("GET", "/api/mounts", null))).size());
        JSONObject origin = JSON.parseObject(content(request("GET", "/api/origin/status", null)));
        assertFalse(origin.getBoolean("loaded"));
    }

    @Test
    void consolePageServed() {
        FullHttpResponse resp = request("GET", "/", null);
        assertEquals(200, resp.status().code());
        assertTrue(content(resp).contains("九阴本地 GM"));
        assertTrue(resp.headers().get("Content-Type").startsWith("text/html"));
    }

    @Test
    void optionsPreflight() {
        FullHttpResponse resp = request("OPTIONS", "/api/status", null);
        assertEquals(204, resp.status().code());
        assertEquals(0, resp.content().readableBytes());
        assertEquals("GET, POST, OPTIONS", resp.headers().get("Access-Control-Allow-Methods"));
    }
}
