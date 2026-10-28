package com.nineyin.gm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * GM 面板 HTTP 请求处理（对应 C++ HttpServer::handle_request）：
 * GET / 控制台页面；GET /api/status；GET /api/sessions；
 * POST /api/sessions/{id}/command；OPTIONS 204（CORS）。
 */
public final class GmRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(GmRequestHandler.class);

    private final GmHub hub;

    public GmRequestHandler(GmHub hub) {
        this.hub = hub;
    }

    public HttpResponse handle(FullHttpRequest req) {
        String method = req.method().name();
        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        String body = req.content().toString(CharsetUtil.UTF_8);

        if (HttpMethod.OPTIONS.name().equals(method)) {
            return GmHttpUtil.response(HttpResponseStatus.NO_CONTENT, "", "application/json");
        }
        if (HttpMethod.GET.name().equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            return GmHttpUtil.response(HttpResponseStatus.OK, consoleHtml(), "text/html; charset=utf-8");
        }
        if (HttpMethod.GET.name().equals(method) && "/api/status".equals(path)) {
            GmHub.ServerStatus st = hub.status();
            JSONObject o = new JSONObject();
            o.put("running", st.running);
            o.put("listen_addr", st.listenAddr);
            o.put("server_root", st.serverRoot);
            o.put("connected_clients", st.connectedClients);
            o.put("uptime_sec", (System.currentTimeMillis() - st.startMs) / 1000);
            o.put("phase", st.phase);
            return GmHttpUtil.json(HttpResponseStatus.OK, o);
        }
        if (HttpMethod.GET.name().equals(method) && "/api/sessions".equals(path)) {
            JSONArray arr = new JSONArray();
            for (GmHub.PlayerSnapshot s : hub.snapshots()) {
                synchronized (s) {
                    JSONObject o = new JSONObject();
                    o.put("sessionId", s.sessionId);
                    o.put("remote", s.remote);
                    o.put("name", s.name);
                    o.put("scene", s.sceneConfig);
                    o.put("resource", s.sceneResource);
                    o.put("x", s.x);
                    o.put("y", s.y);
                    o.put("z", s.z);
                    o.put("orient", s.orient);
                    o.put("silver", s.silver);
                    o.put("sp", s.sp);
                    o.put("faction", s.faction);
                    o.put("lastAction", s.lastAction);
                    arr.add(o);
                }
            }
            return GmHttpUtil.json(HttpResponseStatus.OK, arr);
        }
        // POST /api/sessions/{id}/command
        if (HttpMethod.POST.name().equals(method)
                && path.startsWith("/api/sessions/") && path.endsWith("/command")) {
            String idStr = path.substring("/api/sessions/".length(),
                    path.length() - "/command".length());
            long id;
            try {
                id = Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "invalid session id");
            }
            JSONObject in;
            try {
                in = JSON.parseObject(body);
            } catch (Exception e) {
                return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "invalid json body");
            }
            String action = in.getString("action");
            if (action == null || action.isEmpty()) {
                return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing action");
            }
            int silver = in.getIntValue("silver");
            int sp = in.getIntValue("sp");
            if (sp == 0 && silver != 0) {
                sp = silver;
            }
            int amount = in.getIntValue("amount");
            GmCommand cmd = new GmCommand(id, action, silver, sp,
                    orEmpty(in.getString("preset")), orEmpty(in.getString("destination")),
                    orEmpty(in.getString("faction")), orEmpty(in.getString("itemId")),
                    amount <= 0 ? 1 : amount);
            hub.enqueue(cmd);
            log.info("GM command queued: id={} action={}", id, action);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("message", hub.get(id) != null ? "queued" : "queued (session offline)");
            return GmHttpUtil.json(HttpResponseStatus.OK, o);
        }
        return GmHttpUtil.jsonError(HttpResponseStatus.NOT_FOUND, "not found");
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    static String consoleHtml() {
        try (InputStream in = GmRequestHandler.class.getResourceAsStream("/nineyin/gm/console.html")) {
            if (in == null) {
                return "<html><body>console.html missing</body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body>console load failed</body></html>";
        }
    }
}
