package com.nineyin.gm;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

/** GM HTTP 响应工具：统一 CORS / no-store / Connection: close（对齐 C++ http_response）。 */
final class GmHttpUtil {

    private GmHttpUtil() {
    }

    static HttpResponse response(HttpResponseStatus status, String body, String contentType) {
        ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        resp.headers().set("Content-Type", contentType);
        resp.headers().set("Content-Length", content.readableBytes());
        resp.headers().set("Connection", HttpHeaderValues.CLOSE);
        resp.headers().set("Access-Control-Allow-Origin", "*");
        resp.headers().set("Cache-Control", "no-store");
        return resp;
    }

    static HttpResponse json(HttpResponseStatus status, Object json) {
        return response(status, com.alibaba.fastjson.JSON.toJSONString(json),
                "application/json; charset=utf-8");
    }

    static HttpResponse jsonError(HttpResponseStatus status, String error) {
        com.alibaba.fastjson.JSONObject o = new com.alibaba.fastjson.JSONObject();
        o.put("error", error);
        return json(status, o);
    }
}
