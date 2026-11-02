package com.nineyin.server.gm;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.protocol.SceneNames;
import com.swingfrog.summer.annotation.Remote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GM RPC 入口（summer @Remote）：gm-web 独立面板经 minor.gm 端口
 * （StringLine，默认 127.0.0.1:19081）调用。
 * filter=true + serverName="gm" 保证方法仅 GM RPC 端口可达，游戏端口
 * （19061/4000）上不可见。契约：方法返回 JSON 字符串，面板侧解析——
 * 两个模块不共享类。{@link #attach(GmHub)} 由 NineyinServer.init 装配。
 */
@Remote(filter = true, serverName = "gm")
public class GmRemote {

    private static final Logger log = LoggerFactory.getLogger(GmRemote.class);

    private static volatile GmHub hub;

    /** NineyinServer.init 装配共享会话中枢（summer 反射实例化本类，无法构造注入）。 */
    public static void attach(GmHub gmHub) {
        hub = gmHub;
    }

    /** 服务器状态（原 /api/status）：running/listen_addr/server_root/connected_clients/uptime_sec/phase。 */
    public String status() {
        GmHub.ServerStatus st = hub().status();
        JSONObject o = new JSONObject();
        o.put("running", st.running);
        o.put("listen_addr", st.listenAddr);
        o.put("server_root", st.serverRoot);
        o.put("connected_clients", st.connectedClients);
        o.put("uptime_sec", (System.currentTimeMillis() - st.startMs) / 1000);
        o.put("phase", st.phase);
        return o.toJSONString();
    }

    /** 在线会话快照（原 /api/sessions），含门派/场景中文显示名。 */
    public String sessions() {
        GmHub gmHub = hub();
        JSONArray arr = new JSONArray();
        for (GmHub.PlayerSnapshot s : gmHub.snapshots()) {
            synchronized (s) {
                JSONObject scene = new JSONObject();
                scene.put("config", s.sceneConfig);
                scene.put("resource", s.sceneResource);
                scene.put("name", sceneDisplayName(s.sceneConfig, s.sceneResource));
                JSONObject pos = new JSONObject();
                pos.put("x", s.x);
                pos.put("y", s.y);
                pos.put("z", s.z);
                pos.put("orient", s.orient);

                JSONObject o = new JSONObject();
                o.put("sessionId", s.sessionId);
                o.put("remote", s.remote);
                o.put("name", s.name);
                o.put("faction", s.faction);
                o.put("factionName", FactionNames.displayName(s.faction));
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
        return arr.toJSONString();
    }

    /** Go gm_panel.go HTTP 层 2s 确认窗。 */
    private static final java.time.Duration COMMAND_CONFIRM_TIMEOUT = java.time.Duration.ofSeconds(2);

    /**
     * 命令入队（原 POST /api/sessions/{id}/command）：参数原样透传。
     * 对齐 Go HTTP 层两段语义：入口校验（数值域 + 未实现动作拒绝）失败立即
     * ok=false；通过则入队并等游戏侧回执（≤2s）——超时不把「已入队」描述成成功。
     */
    public String command(long sessionId, String action, JSONObject params) {
        if (action == null || action.isEmpty()) {
            return error("missing action");
        }
        String invalid = validateCommand(action, params);
        if (invalid != null) {
            return error(invalid);
        }
        GmHub gmHub = hub();
        GmCommand command = new GmCommand(sessionId, action, params != null ? params : new JSONObject());
        gmHub.enqueue(command);
        log.info("GM command queued: id={} action={} params={}", sessionId, action, params);
        GmCommand.Reply reply = command.awaitReply(COMMAND_CONFIRM_TIMEOUT);
        JSONObject o = new JSONObject();
        o.put("ok", reply.ok());
        o.put("message", reply.message());
        return o.toJSONString();
    }

    /** Go gm_panel.go switch(body.Action)：已实现动作白名单 + 数值域。 */
    private static String validateCommand(String action, JSONObject params) {
        return switch (action) {
            case "set_move_speed" -> floatRange(params, "value", 0.1f, 100f, "move speed");
            case "set_gravity" -> floatRange(params, "value", 0.1f, 100f, "gravity");
            case "set_silver" -> nonNegative(params, "silver");
            case "set_guanyin" -> nonNegative(params, "guanYin");
            case "set_gold" -> nonNegative(params, "gold");
            case "set_sp" -> intRange(params, "sp", 0, 100);
            case "add_sp" -> intRange(params, "sp", 1, 100);
            case "fill_sp" -> null;
            // 战斗一期：Lua 脚本热重载（formula/skill overlay 调参免重启）。
            case "reload_scripts" -> null;
            // 经脉：每日真气硬闸（是否开启 + 每日阈值，按角色持久化）。
            case "set_zhenqi_cap" -> {
                String enabledBad = intRange(params, "enabled", 0, 1);
                if (enabledBad != null) {
                    yield enabledBad;
                }
                yield intRange(params, "value", 1, 10_000_000);
            }
            // 四期面板动作（LoginFlow.applyGmCommand 已实现，此前漏挂白名单——
            // 面板按钮一直被入口拒绝，补齐透传；数值域由游戏侧分支自校验）。
            case "set_power_level" -> nonNegative(params, "value");
            case "set_alignment", "set_vitals", "cancel_title" -> null;
            case "wear_title" -> intRange(params, "titleId", 1, Integer.MAX_VALUE);
            default -> "unsupported action: " + action;
        };
    }

    private static String nonNegative(JSONObject params, String key) {
        Integer value = params != null ? params.getInteger(key) : null;
        if (value == null || value < 0) {
            return key + " must be non-negative";
        }
        return null;
    }

    private static String intRange(JSONObject params, String key, int min, int max) {
        Integer value = params != null ? params.getInteger(key) : null;
        if (value == null || value < min || value > max) {
            return key + " must be between " + min + " and " + max;
        }
        return null;
    }

    private static String floatRange(JSONObject params, String key, float min, float max, String label) {
        Float value = params != null ? params.getFloat(key) : null;
        if (value == null || value.isNaN() || value.isInfinite() || value < min || value > max) {
            return label + " must be between " + min + " and " + max;
        }
        return null;
    }

    /** 最小登录开关当前值（原 /api/minimal-login GET）。 */
    public String minimalLogin() {
        JSONObject o = new JSONObject();
        o.put("enabled", hub().minimalLogin());
        return o.toJSONString();
    }

    /** 切换最小登录开关（修复拆分前经命令队列下发、无人消费导致 toggle 失效的断链）。 */
    public String toggleMinimalLogin() {
        boolean enabled = hub().toggleMinimalLogin();
        log.info("GM minimal-login toggled: enabled={}", enabled);
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        return o.toJSONString();
    }

    private static GmHub hub() {
        GmHub gmHub = hub;
        if (gmHub == null) {
            throw new IllegalStateException("GmRemote not attached to GmHub");
        }
        return gmHub;
    }

    private static String error(String message) {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("message", message);
        return o.toJSONString();
    }

    /**
     * 面板场景显示名：复用选角界面的 {@link SceneNames} 解析；其兜底值是客户端
     * 本地化键（scene_name_NNN），对面板无意义，此时回退原配置码。
     */
    private static String sceneDisplayName(String config, String resource) {
        String name = SceneNames.sceneDisplayName(config, resource);
        return name != null && name.matches("scene_name_\\d+") ? (config == null ? "" : config) : name;
    }
}
