package com.nineyin.gm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
import java.util.regex.Pattern;

/**
 * gm-web 独立面板 HTTP 处理（多服务器管理网关）：
 * <ul>
 * <li>GET / 管理页 index.html（服务器列表存前端 localStorage）；GET /console.html 控制台
 *     （须带 ?s=host:port，由 console 侧门禁跳转）。</li>
 * <li>GET /api/manage/status?s=host:port — 管理页轮询目标游戏服在线状态。</li>
 * <li>动态数据 /api/status、/api/sessions、POST /api/sessions/{id}/command、
 *     /api/minimal-login（GET/POST）— 按 __s=host:port 经 {@link GameServerRpc} 路由到
 *     所选游戏服；断连时降级（sessions=[]、game_connected=false）。</li>
 * <li>17 个目录接口（装备/时装/经脉/称号…）— 本地 {@link GmCatalog}
 *     （resources/modern + MySQL 优先、data/*.json 回退；本机所有游戏服共享数据源）。</li>
 * </ul>
 * OPTIONS 204（CORS）。
 */
public final class GmRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(GmRequestHandler.class);

    private static final Pattern HOST_PATTERN =
            Pattern.compile("^(localhost|(\\d{1,3}\\.){3}\\d{1,3})$", Pattern.CASE_INSENSITIVE);

    private final GmCatalog catalog;
    private final GameServerRpc gameRpc;
    private final long startMs = System.currentTimeMillis();

    public GmRequestHandler(GmCatalog catalog, GameServerRpc gameRpc) {
        this.catalog = catalog;
        this.gameRpc = gameRpc;
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
        if (HttpMethod.GET.name().equals(method) && "/".equals(path)) {
            return GmHttpUtil.response(HttpResponseStatus.OK, page("index.html"), "text/html; charset=utf-8");
        }
        if (HttpMethod.GET.name().equals(method) && "/console.html".equals(path)) {
            return GmHttpUtil.response(HttpResponseStatus.OK, page("console.html"), "text/html; charset=utf-8");
        }
        if (!HttpMethod.GET.name().equals(method) && !HttpMethod.POST.name().equals(method)) {
            return GmHttpUtil.jsonError(HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed");
        }

        Map<String, List<String>> params = new QueryStringDecoder("?" + query, false).parameters();

        // 管理页状态轮询：?s=host:port
        if (HttpMethod.GET.name().equals(method) && "/api/manage/status".equals(path)) {
            return manageStatus(firstParam(params, "s"));
        }

        // POST /api/sessions/{id}/command（__s 路由）
        if (HttpMethod.POST.name().equals(method)
                && path.startsWith("/api/sessions/") && path.endsWith("/command")) {
            return proxyCommand(path, body, serverParam(params));
        }
        if (!HttpMethod.GET.name().equals(method)) {
            if ("/api/minimal-login".equals(path)) {
                return proxyToggle(serverParam(params));
            }
            return GmHttpUtil.jsonError(HttpResponseStatus.NOT_FOUND, "not found");
        }

        switch (path) {
            case "/api/status" -> {
                return proxyStatus(serverParam(params));
            }
            case "/api/sessions" -> {
                return proxySessions(serverParam(params));
            }
            case "/api/minimal-login" -> {
                return proxyMinimalLogin(serverParam(params));
            }
            // ---- 17 个目录接口：本地 GmCatalog ----
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
                String q0 = firstParam(params, "q");
                int view = parseIntOr(firstParam(params, "view"), 0);
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.items(q0, view));
            }
            case "/api/meridians" -> {
                return GmHttpUtil.json(HttpResponseStatus.OK, catalog.meridians());
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

    // ---------------------------------------------------------------- 管理页

    private HttpResponse manageStatus(String s) {
        Address addr = parseAddress(s);
        if (addr == null) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "invalid s, expect host:port");
        }
        JSONObject o = new JSONObject();
        try {
            String status = gameRpc.call(addr.host(), addr.port(), "status", Map.of());
            o.put("online", true);
            o.put("status", JSON.parseObject(status));
        } catch (GameServerRpc.GameServerOfflineException e) {
            o.put("online", false);
        }
        return GmHttpUtil.json(HttpResponseStatus.OK, o);
    }

    // ---------------------------------------------------------------- 动态代理

    private HttpResponse proxyStatus(Address addr) {
        JSONObject panel = new JSONObject();
        panel.put("panel_uptime_sec", (System.currentTimeMillis() - startMs) / 1000);
        if (addr == null) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing __s (host:port)");
        }
        try {
            JSONObject game = JSON.parseObject(gameRpc.call(addr.host(), addr.port(), "status", Map.of()));
            game.put("panel_uptime_sec", panel.get("panel_uptime_sec"));
            game.put("game_connected", true);
            return GmHttpUtil.json(HttpResponseStatus.OK, game);
        } catch (GameServerRpc.GameServerOfflineException e) {
            panel.put("running", false);
            panel.put("connected_clients", 0);
            panel.put("game_connected", false);
            return GmHttpUtil.json(HttpResponseStatus.OK, panel);
        }
    }

    private HttpResponse proxySessions(Address addr) {
        if (addr == null) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing __s (host:port)");
        }
        try {
            return GmHttpUtil.json(HttpResponseStatus.OK,
                    JSON.parseArray(gameRpc.call(addr.host(), addr.port(), "sessions", Map.of())));
        } catch (GameServerRpc.GameServerOfflineException e) {
            return GmHttpUtil.json(HttpResponseStatus.OK, new JSONArray());
        }
    }

    private HttpResponse proxyCommand(String path, String body, Address addr) {
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
        if (addr == null) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing __s (host:port)");
        }
        JSONObject params = new JSONObject();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (!"action".equals(e.getKey())) {
                params.put(e.getKey(), e.getValue());
            }
        }
        try {
            String result = gameRpc.call(addr.host(), addr.port(), "command",
                    Map.of("sessionId", id, "action", action, "params", params));
            JSONObject o = JSON.parseObject(result);
            // 游戏侧 ok=false（校验失败/未确认发送）→ 409 {"error":…}，面板 JS 才会告警。
            if (o != null && Boolean.FALSE.equals(o.getBoolean("ok"))) {
                return GmHttpUtil.jsonError(HttpResponseStatus.CONFLICT,
                        o.getString("message") != null ? o.getString("message") : "command failed");
            }
            return GmHttpUtil.json(HttpResponseStatus.OK, o);
        } catch (GameServerRpc.GameServerOfflineException e) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("message", "game server offline");
            return GmHttpUtil.json(HttpResponseStatus.OK, o);
        }
    }

    private HttpResponse proxyMinimalLogin(Address addr) {
        if (addr == null) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing __s (host:port)");
        }
        try {
            return GmHttpUtil.json(HttpResponseStatus.OK,
                    JSON.parseObject(gameRpc.call(addr.host(), addr.port(), "minimalLogin", Map.of())));
        } catch (GameServerRpc.GameServerOfflineException e) {
            JSONObject o = new JSONObject();
            o.put("enabled", false);
            return GmHttpUtil.json(HttpResponseStatus.OK, o);
        }
    }

    private HttpResponse proxyToggle(Address addr) {
        if (addr == null) {
            return GmHttpUtil.jsonError(HttpResponseStatus.BAD_REQUEST, "missing __s (host:port)");
        }
        try {
            return GmHttpUtil.json(HttpResponseStatus.OK,
                    JSON.parseObject(gameRpc.call(addr.host(), addr.port(), "toggleMinimalLogin", Map.of())));
        } catch (GameServerRpc.GameServerOfflineException e) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("message", "game server offline");
            return GmHttpUtil.json(HttpResponseStatus.OK, o);
        }
    }

    // ---------------------------------------------------------------- 地址与工具

    /** __s / s 参数（host:port）；host 仅允许 IP/localhost。 */
    private static Address parseAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon != raw.indexOf(':')) {
            return null;
        }
        String host = raw.substring(0, colon).trim();
        String portText = raw.substring(colon + 1).trim();
        if (!HOST_PATTERN.matcher(host).matches()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new Address(host.toLowerCase(), port);
    }

    private record Address(String host, int port) {
    }

    private static Address serverParam(Map<String, List<String>> params) {
        return parseAddress(firstParam(params, "__s"));
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

    private static String page(String name) {
        try (InputStream in = GmRequestHandler.class.getResourceAsStream("/nineyin/gm/" + name)) {
            if (in == null) {
                return "<html><body>" + name + " missing</body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<html><body>" + name + " load failed</body></html>";
        }
    }
}
