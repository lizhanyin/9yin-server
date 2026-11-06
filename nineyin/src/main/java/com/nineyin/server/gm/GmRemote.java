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
                if (s.botName != null && !s.botName.isEmpty()) {
                    JSONObject bot = new JSONObject();
                    bot.put("name", s.botName);
                    bot.put("hp", s.botHp);
                    bot.put("maxHp", s.botMaxHp);
                    bot.put("following", s.botFollowing);
                    bot.put("support", s.botSupport == null ? "" : s.botSupport);
                    o.put("bot", bot);
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
            // 经脉周天直设（面板档 36..216；游戏侧按目录上限逐条钳制）。
            case "set_jingmai_tier" -> {
                String idBad = nonEmpty(params, "jingMaiId");
                if (idBad != null) {
                    yield idBad;
                }
                yield intRange(params, "jingMaiTier", 1, 216);
            }
            // 内功全书收录（无参）：neigong.ini 目录补书 level 1，已有书等级/修为不动。
            case "learn_all_neigong" -> null;
            // 修炼：修为总量（Faculty 修炼点余额）+ 修速基础值×倍率 + 当前内功本层修炼度填满。
            case "set_faculty" -> intRange(params, "value", 0, 2_000_000_000);
            case "set_fill_speed" -> {
                String baseBad = intRange(params, "base", 1, 1_000_000);
                if (baseBad != null) {
                    yield baseBad;
                }
                yield floatRange(params, "multiplier", 0.1f, 100f, "multiplier");
            }
            case "fill_current_neigong" -> null;
            // 历练层：发历练（1..999 点）+ 潜能修为直设 + 转化速度加成 %。
            case "add_livepoint" -> intRange(params, "points", 1, 999);
            case "set_hold_faculty" -> nonNegative(params, "value");
            case "set_live_speed_power" -> intRange(params, "value", 0, 1000);
            // 演武碎银每日上限（两，0=不限制）+ 团练疲劳清除/剩余抵扣直设。
            case "set_act_day_limit" -> intRange(params, "value", 0, 999_999);
            case "clear_team_fatigue" -> null;
            case "set_team_faculty_last" -> nonNegative(params, "value");
            // 四期面板动作（LoginFlow.applyGmCommand 已实现，此前漏挂白名单——
            // 面板按钮一直被入口拒绝，补齐透传；数值域由游戏侧分支自校验）。
            case "set_power_level" -> nonNegative(params, "value");
            // 基础五维（面板"五维属性"区块）：五参均可选（缺省/0=跳过该维），到位时 0..1e6。
            case "set_five_attributes" -> {
                String bad = null;
                for (String key : new String[]{"attrStr", "attrSta", "attrDex", "attrIng", "attrSpi"}) {
                    Integer value = params != null ? params.getInteger(key) : null;
                    if (value != null && (value < 0 || value > 1_000_000)) {
                        bad = key + " must be between 0 and 1000000";
                        break;
                    }
                }
                yield bad;
            }
            case "set_alignment", "set_vitals", "cancel_title" -> null;
            // 门派切换：闭名单（GmFactionPresets 面板键 → school_/force_/newschool_ 规范编码），
            // 不接受任意字符串；落库与帧下发在游戏侧 applyFaction。
            case "set_faction" -> {
                String keyBad = nonEmpty(params, "faction");
                if (keyBad != null) {
                    yield keyBad;
                }
                String key = params.getString("faction");
                yield GmFactionPresets.preset(key) != null ? null : "unknown faction preset: " + key;
            }
            // 场景天气切换：闭名单（GmWeatherPresets 面板键 = 客户端 sceneparastore
            // 天气渲染包目录名，49 键），不接受任意字符串；fadeSec 过渡秒可选 0..600
            // （缺省游戏侧取 10）。帧下发在游戏侧 applyWeather（on_weather_sky sub 4）。
            case "set_weather" -> {
                String keyBad = nonEmpty(params, "weather");
                if (keyBad != null) {
                    yield keyBad;
                }
                String weather = params.getString("weather");
                String unknown = GmWeatherPresets.preset(weather) != null
                        ? null : "unknown weather preset: " + weather;
                if (unknown != null) {
                    yield unknown;
                }
                Integer fadeSec = params != null ? params.getInteger("fadeSec") : null;
                yield fadeSec == null ? null : intRange(params, "fadeSec", 0, 600);
            }
            case "wear_title" -> intRange(params, "titleId", 1, Integer.MAX_VALUE);
            // 称号所有权：授予（幂等 INSERT，不动佩戴位）/ 清空全部（游戏侧补佩戴位复位帧）。
            case "grant_title" -> intRange(params, "titleId", 1, Integer.MAX_VALUE);
            case "clear_titles" -> null;
            // 快捷栏整表对账落库（无参）+ PK 模式位（0..8 宽域，语义由客户端渲染）。
            case "save_shortcuts" -> null;
            case "set_pk_mode" -> intRange(params, "pkMode", 0, 8);
            // V2 装备外观挂载：物品发放（wearNow 可选 0/1；不可穿戴由游戏侧降级入背包）。
            case "grant_item", "add_item" -> {
                String configBad = nonEmpty(params, "itemConfig");
                if (configBad != null) {
                    yield configBad;
                }
                yield intRange(params, "amount", 1, 9999);
            }
            // 任务二期调试：一键补满已接任务进度（taskId 省略或 0=全部已接）。
            case "task_progress" -> params != null && params.containsKey("taskId")
                    ? intRange(params, "taskId", 0, 999_999)
                    : null;
            // 礼包与物品区块：系统邮件附件发送（send_test_mail 同参数域）+
            // 礼包测试商人拉到身边（无参）。
            case "send_test_mail" -> {
                String configBad = nonEmpty(params, "itemConfig");
                if (configBad != null) {
                    yield configBad;
                }
                yield intRange(params, "amount", 1, 9999);
            }
            // GM 邮件：任意角色系统信（自定义标题/正文 + 可选物品附件；content 与
            // itemConfig 至少一项）。roleName/title/content 可选限长；物品存在性与
            // 投递路由（本会话/在线他人/离线直写信箱）在游戏侧 applySendMail。
            case "send_mail" -> {
                String content = params != null ? params.getString("content") : null;
                String itemConfig = params != null ? params.getString("itemConfig") : null;
                boolean hasContent = content != null && !content.trim().isEmpty();
                boolean hasItem = itemConfig != null && !itemConfig.trim().isEmpty();
                if (!hasContent && !hasItem) {
                    yield "content 与 itemConfig 至少填一项";
                }
                String titleBad = lengthLimit(params, "title", 64);
                if (titleBad != null) {
                    yield titleBad;
                }
                String contentBad = lengthLimit(params, "content", 1024);
                if (contentBad != null) {
                    yield contentBad;
                }
                String roleBad = lengthLimit(params, "roleName",
                        com.nineyin.server.db.role.Roles.MAX_ROLE_NAME_RUNES);
                if (roleBad != null) {
                    yield roleBad;
                }
                // amount 可选（缺省游戏侧取 1）；带值才校验域（intRange 对 null 直接拒）。
                Integer amount = params != null ? params.getInteger("amount") : null;
                yield amount == null ? null : intRange(params, "amount", 1, 9999);
            }
            case "giftbox_merchant_here" -> null;
            // V3 风物志：卡牌挂载/卸下/全量收录（GM 网页直接驱动）。
            case "equip_card" -> intRange(params, "cardId", 1, Integer.MAX_VALUE);
            // 风物志头发：仅纯发型卡（只含 Hat 部件），同部件互斥由游戏侧裁决。
            case "equip_hair" -> intRange(params, "cardId", 1, Integer.MAX_VALUE);
            case "unequip_card", "grant_all_cards", "grant_all_fashion" -> null;
            // V4 三套外观：显示类型切换（0=江湖装 1=身份装 2=时装）+ 身份装按门派激活。
            case "set_showequip_type" -> intRange(params, "type", 0, 2);
            case "grant_identity_suits" -> null;
            // 配套：模板生成武器（101-108/122 + cook/smith）与防具（部位名白名单）。
            case "gm_generate_weapon" -> {
                String typeBad = intRange(params, "weaponType", 101, 122);
                if (typeBad != null) {
                    yield typeBad;
                }
                String crafter = params != null ? params.getString("crafter") : null;
                yield crafter != null && (crafter.equals("cook") || crafter.equals("smith"))
                        ? null : "crafter must be cook or smith";
            }
            case "gm_generate_armor" -> {
                String slot = params != null ? params.getString("armorSlot") : null;
                yield slot != null && java.util.Set.of("helmet", "amulet", "cloth", "pants",
                        "leg", "shoes", "wrist", "ear", "ring").contains(slot)
                        ? null : "armorSlot must be helmet/amulet/cloth/pants/leg/shoes/ear/ring";
            }
            // 配套：模板生成护腕（无参——模板挑选与词条构造全部在游戏侧）。
            case "gm_generate_bracer" -> null;
            // 传送三动作（Go gm_panel.go switch_scene/switch_scene_preset/teleport）：
            // 预设键入口即校验（表=GmScenePresets 142 键，含踢馆 tiguan_1..35）；
            // 免费格式由游戏侧解析。
            case "switch_scene_preset" -> {
                String presetBad = nonEmpty(params, "preset");
                if (presetBad != null) {
                    yield presetBad;
                }
                String key = params.getString("preset");
                yield com.nineyin.server.world.scene.GmScenePresets.preset(key) != null
                        ? null : "unknown preset: " + key;
            }
            case "switch_scene" -> nonEmpty(params, "destination");
            case "teleport" -> nonEmpty(params, "position");
            // 副本三动作（面板「副本」区块）：enter 带 cloneConfig（cloneinfo.ini 节名，
            // 形如 ini\scene\clone003）+ value 难度档 1普通/2困难/3地狱/4炼狱；
            // 存在性校验与波次链在游戏侧（LoginFlow，需资源根）。
            case "enter_clone" -> {
                String configBad = nonEmpty(params, "cloneConfig");
                if (configBad != null) {
                    yield configBad;
                }
                yield intRange(params, "value", 1, 4);
            }
            case "reenter_clone", "destroy_clone" -> null;
            // 弹出游戏 GM 面板（GMCC）：无参，帧由 LoginFlow.applyPopupGmPanel 组。
            case "popup_gm_panel" -> null;
            // 洗练六动作：随机/定向/抛光/招式开光（slot）；加词条（slot+packIDs+affixKind）；清空（slot）。
            case "recast_slot_prop", "recast_slot_dire", "recast_slot_polish",
                 "recast_slot_skill", "recast_clear_affix" -> intRange(params, "slot", 1, 63);
            case "recast_add_affix" -> {
                String slotBad = intRange(params, "slot", 1, 63);
                if (slotBad != null) {
                    yield slotBad;
                }
                String kind = params != null ? params.getString("affixKind") : null;
                if (!"prop".equals(kind) && !"skill".equals(kind)) {
                    yield "affixKind must be prop or skill";
                }
                JSONArray packIds = params != null ? params.getJSONArray("packIDs") : null;
                if (packIds == null || packIds.isEmpty()) {
                    yield "packIDs must be a non-empty int array";
                }
                if (packIds.size() > 12) {
                    yield "packIDs at most 12 entries";
                }
                for (int i = 0; i < packIds.size(); i++) {
                    Integer id = packIds.getInteger(i);
                    if (id == null || id < 1) {
                        yield "packIDs[" + i + "] must be a positive int";
                    }
                }
                yield null;
            }
            // 场景生成：训练木桩（dummyHp 可选 0=默认 10 万）+ 世界宝箱（无参）。
            case "spawn_dummy" -> {
                Integer hp = params != null ? params.getInteger("dummyHp") : null;
                yield hp == null ? null : intRange(params, "dummyHp", 0, 100_000_000);
            }
            case "spawn_chest" -> null;
            // 战斗假人（bak spawn_combat_dummy 同款）：血量可选 0=默认 5 万（上限与木桩一致）；
            // 名字/招式为自由文本限长；单段伤害可选（当前仅记日志，数值域先行校验）。
            case "spawn_combat_dummy" -> {
                Integer hp = params != null ? params.getInteger("dummyHp") : null;
                if (hp != null) {
                    String hpBad = intRange(params, "dummyHp", 0, 100_000_000);
                    if (hpBad != null) {
                        yield hpBad;
                    }
                }
                Integer damage = params != null ? params.getInteger("dummyDamage") : null;
                if (damage != null) {
                    String damageBad = intRange(params, "dummyDamage", 0, 1_000_000);
                    if (damageBad != null) {
                        yield damageBad;
                    }
                }
                String nameBad = lengthLimit(params, "dummyName", 64);
                if (nameBad != null) {
                    yield nameBad;
                }
                yield lengthLimit(params, "dummySkills", 512);
            }
            case "clear_combat_dummies" -> null;
            // 世界 BOSS（worldboss_*）：官方刷新表驱动，无参——当前场景门派固定点每点
            // 1 只 + 场景池命中的江湖 BOSS 随机点；击杀后消失不重生；clear/list 按会话记账。
            case "worldboss_spawn", "worldboss_clear", "worldboss_list" -> null;
            // 门派声望（repute_apply）：阵营闭名单在游戏侧按 repute_level.ini 校验
            //（外部表，worldboss 同款两段范式）；此处只验通用域——name 非空限长、
            // mode ∈ set/add（缺省 set）、value 与面板 max 一致。
            case "repute_apply" -> {
                String nameBad = nonEmpty(params, "reputeName");
                if (nameBad != null) {
                    yield nameBad;
                }
                String nameLen = lengthLimit(params, "reputeName", 64);
                if (nameLen != null) {
                    yield nameLen;
                }
                String mode = params != null ? params.getString("reputeMode") : null;
                if (mode != null && !mode.isBlank() && !"set".equals(mode) && !"add".equals(mode)) {
                    yield "reputeMode must be set or add";
                }
                yield intRange(params, "reputeValue", 0, 9_999_999);
            }
            // 伙伴系统（bot_*）：预设闭名单驱动——ensure 可选 bot 键（GmBotPresets），
            // 拉人/跟随开关/复活无参；辅助配置 enabled/intervalSec/heal 均可选（缺省用预设值）。
            case "bot_ensure" -> {
                String bot = params != null ? params.getString("bot") : null;
                yield bot == null ? null
                        : GmBotPresets.preset(bot) == null
                        ? "bot must be one of " + GmBotPresets.keys() : null;
            }
            case "bot_pull_here", "bot_follow_start", "bot_follow_stop", "bot_revive" -> null;
            case "bot_support_configure" -> {
                Integer enabled = params != null ? params.getInteger("enabled") : null;
                if (enabled != null) {
                    String enabledBad = intRange(params, "enabled", 0, 1);
                    if (enabledBad != null) {
                        yield enabledBad;
                    }
                }
                Integer intervalSec = params != null ? params.getInteger("intervalSec") : null;
                if (intervalSec != null) {
                    String intervalBad = intRange(params, "intervalSec", 1, 60);
                    if (intervalBad != null) {
                        yield intervalBad;
                    }
                }
                Integer heal = params != null ? params.getInteger("heal") : null;
                yield heal == null ? null : intRange(params, "heal", 0, 100_000);
            }
            // 坐骑管理：按道具 config 骑乘/再点同骑下马（pack 解析与记录帧由游戏侧）。
            case "mount_ride" -> nonEmpty(params, "mountConfig");
            // 兵录设置：bingluArgs="set <row> <cur> <fill> [max]" / "max [row]"
            // （binglu_rank_rec 独立记录帧域，子命令细校验由游戏侧）。
            case "binglu" -> nonEmpty(params, "bingluArgs");
            // 武学套路两动作：无参——满级档值与遗忘基线全部由游戏侧（LoginFlow）处理。
            case "learn_all_taolu", "forget_all_taolu" -> null;
            // 待机站姿（持久化）：b_action_index.ini 节名；空串 = 恢复默认站姿。
            case "idle_stance_set" -> {
                String stance = params != null ? params.getString("stanceAction") : null;
                yield stance == null || stance.length() > 64
                        ? "stanceAction must be a string of at most 64 chars"
                        : null;
            }
            // 御风神水（大轻功）：无参——挂 buf_ride_yufeng 10 分钟租约（重复点续期）/摘除。
            case "yufeng_add", "yufeng_remove" -> null;
            // 身份系统：无参——origin.ini 全量激活（领奖走 C2S 702，玩家手动点）。
            case "origin_activate_all" -> null;
            default -> "unsupported action: " + action;
        };
    }

    private static String nonEmpty(JSONObject params, String key) {
        String value = params != null ? params.getString(key) : null;
        if (value == null || value.trim().isEmpty()) {
            return key + " must be a non-empty string";
        }
        return null;
    }

    /** 可选字符串限长（null/缺省放行，超长拒绝）。 */
    private static String lengthLimit(JSONObject params, String key, int max) {
        String value = params != null ? params.getString(key) : null;
        if (value != null && value.length() > max) {
            return key + " must be a string of at most " + max + " chars";
        }
        return null;
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
