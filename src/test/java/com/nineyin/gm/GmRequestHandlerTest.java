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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EmbeddedChannel 上的 GM HTTP 全链路（编解码 + 聚合 + 处理）。 */
class GmRequestHandlerTest {

    private GmHub hub;

    @BeforeEach
    void setup() {
        hub = new GmHub();
    }

    /** 每次请求独立通道（一请求一连接语义，与 GmHttpServer 行为一致）。 */
    private FullHttpResponse request(String method, String uri, String body) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new GmHttpServer.GmChannelHandler(new GmRequestHandler(hub)));
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
    void sessionsEndpointListsSnapshots() {
        GmHub.PlayerSnapshot snap = new GmHub.PlayerSnapshot();
        snap.sessionId = 1;
        snap.name = "测试侠";
        snap.sceneConfig = "ini\\scene\\city05_ChengDu";
        snap.sceneResource = "city05";
        snap.x = 915.5f;
        hub.attach(1, snap);

        FullHttpResponse resp = request("GET", "/api/sessions", null);
        assertEquals(200, resp.status().code());
        JSONArray arr = JSON.parseArray(content(resp));
        assertEquals(1, arr.size());
        assertEquals("测试侠", arr.getJSONObject(0).getString("name"));
        assertEquals("city05", arr.getJSONObject(0).getString("resource"));
        assertEquals(915.5f, arr.getJSONObject(0).getFloatValue("x"), 0.001f);
    }

    @Test
    void commandEndpointEnqueues() {
        FullHttpResponse resp = request("POST", "/api/sessions/3/command",
                "{\"action\":\"give_item\",\"itemId\":\"box_1\",\"amount\":2}");
        assertEquals(200, resp.status().code());
        assertTrue(JSON.parseObject(content(resp)).getBoolean("ok"));
        GmCommand cmd = hub.tryPop(3);
        assertNotNull(cmd);
        assertEquals("give_item", cmd.action());
        assertEquals("box_1", cmd.itemId());
        assertEquals(2, cmd.amount());
    }

    @Test
    void commandValidation() {
        assertEquals(400, request("POST", "/api/sessions/3/command", "{}").status().code());
        assertEquals(400, request("POST", "/api/sessions/abc/command", "{\"action\":\"kick\"}")
                .status().code());
        assertEquals(404, request("GET", "/api/unknown", null).status().code());
    }

    @Test
    void consolePageServed() {
        FullHttpResponse resp = request("GET", "/", null);
        assertEquals(200, resp.status().code());
        assertTrue(content(resp).contains("9yin GM Console"));
        assertTrue(resp.headers().get("Content-Type").startsWith("text/html"));
    }

    @Test
    void optionsPreflight() {
        FullHttpResponse resp = request("OPTIONS", "/api/status", null);
        assertEquals(204, resp.status().code());
        assertEquals(0, resp.content().readableBytes());
    }
}
