package com.nineyin.gm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * GM 面板 HTTP 请求处理：
 * GET / 控制台页面；GET /api/status、/api/sessions、/api/minimal-login 及 17 个目录接口；
 * POST /api/sessions/{id}/command（任意参数透传）；OPTIONS 204（CORS）。
 */
public final class GmRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(GmRequestHandler.class);

    private final GmHub hub;
    private final GmCatalog catalog;

    public GmRequestHandler(GmHub hub) {
        this(hub, GmCatalog.empty());
    }

    public GmRequestHandler(GmHub hub, GmCatalog catalog) {
        this.hub = hub;
        this.catalog = catalog;
    }

    public HttpResponse handle(FullHttpRequest req) {
        String method = req.method().name();
        String uri = req.uri();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        String query = q >= 0 ? uri.substring(q + 1) : "";
        String body = req.content().toString(CharsetUtil.UTF_8);

        if (HttpMethod.OPTIONS.name().equals(method)) {
            return GmHttpUtil.response(HttpResponseStatus.NO_CONTENT, "", "application/json");
        }
        if (HttpMethod.GET.name().equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            return GmHttpUtil.response(HttpResponseStatus.OK, consoleHtml(), "text/html; charset=utf-8");
        }
        if (!HttpMethod.GET.name().equals(method) && !HttpMethod.POST.name().equals(method)) {
            return GmHttpUtil.jsonError(HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed");
        }

        // POST /api/sessions/{id}/command
        if (HttpMethod.POST.name().equals(method)
                && path.startsWith("/api/sessions/") && path.endsWith("/command")) {
            return handleCommand(path, body);
        }
        if (!HttpMethod.GET.name().equals(method)) {
            return GmHttpUtil.jsonError(HttpResponseStatus.NOT_FOUND, "not found");
        }

        switch (path) {
            case "/api/status" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, statusJson());
            }
            case "/api/sessions" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, sessionsJson());
            }
            case "/api/bots" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.bots());
            }
            case "/api/cards" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.cards());
            }
            case "/api/hair-cards" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.hairCards());
            }
            case "/api/clones" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.clones());
            }
            case "/api/combat-dummy/taolus" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.taolus());
            }
            case "/api/fashion-catalog" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.fashionCatalog());
            }
            case "/api/giftboxes" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.giftBoxes());
            }
            case "/api/idle-stances" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.idleStances());
            }
            case "/api/items" -> {
                Map<String, List<String>> params = new QueryStringDecoder("?" + query, false).parameters();
                String q0 = firstParam(params, "q");
                int view = parseIntOr(firstParam(params, "view"), 0);
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.items(q0, view));
            }
            case "/api/meridians" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.meridians());
            }
            case "/api/minimal-login" -> {
                JSONObject o = new JSONObject();
                o.put("enabled", hub.minimalLogin());
                return GmHttpUtil.json(HttpResponseStatus.OK, o);
            }
            case "/api/mounts" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.mounts());
            }
            case "/api/origin/status" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.originStatus());
            }
            case "/api/recast-affixes" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.recastAffixes());
            }
            case "/api/repute/status" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.reputeStatus());
            }
            case "/api/titles" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.titles());
            }
            case "/api/weather" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.weather());
            }
            default -> {
                return GmHttpUtil.jsonError(HttpResponseStatus.NOT_FOUND, "not found");
            }
        }
    }

    private JSONObject statusJson() {
        GmHub.ServerStatus st = hub.status();
        JSONObject o = new JSONObject();
        o.put("running", st.running);
        o.put("listen_addr", st.listenAddr);
        o.put("server_root", st.serverRoot);
        o.put("connected_clients", st.connectedClients);
        o.put("uptime_sec", (System.currentTimeMillis() - st.startMs) / 1000);
        o.put("phase", st.phase);
        return o;
    }

    private JSONArray sessionsJson() {
        JSONArray arr = new JSONArray();
        for (GmHub.PlayerSnapshot s : hub.snapshots()) {
            synchronized (s) {
                JSONObject scene = new JSONObject(true);
                scene.put("config", s.sceneConfig);
                scene.put("resource", s.sceneResource);
                JSONObject pos = new JSONObject(true);
                pos.put("x", s.x);
                pos.put("y", s.y);
                pos.put("z", s.z);
                pos.put("orient", s.orient);

                JSONObject o = new JSONObject(true);
                o.put("sessionId", s.sessionId);
                o.put("remote", s.remote);
                o.put("name", s.name);
                o.put("faction", s.faction);
                o.put("scene", scene);
                o.put("position", pos);
                o.put("silver", s.silver);
                o.put("sp", s.sp);
                if (s.guanYin != null) {
                    o.put("guanYin", s.guanYin);
                }
                if (s.gold != null) {
                    o.put("gold", s.gold);
                }
                o.put("lastAction", s.lastAction);
                arr.add(o);
            }
        }
        return arr;
    }

    private HttpResponse handleCommand(String path, String body) {
        String idStr = path.substring("/api/sessions/".length(), path.length() - "/command".length());
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
        String action = in != null ? in.getString("action") : null;
        if (action == null || action.isEmpty()) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing action");
        }
        JSONObject params = new JSONObject(true);
        if (in != null) {
            for (Map.Entry<String, Object> e : in.entrySet()) {
                if (!"action".equals(e.getKey())) {
                    params.put(e.getKey(), e.getValue());
                }
            }
        }
        hub.enqueue(new GmCommand(id, action, params));
        log.info("GM command queued: id={} action={} params={}", id, action, params);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("message", hub.get(id) != null ? "queued" : "queued (session offline)");
        return GmHttpUtil.json(HttpResponseStatus.OK, o);
    }

    private static String firstParam(Map<String, List<String>> params, String key) {
        List<String> v = params.get(key);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    private static int parseIntOr(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
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
