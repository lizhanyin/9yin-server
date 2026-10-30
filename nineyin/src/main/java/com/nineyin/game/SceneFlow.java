package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.Appearance;
import com.nineyin.protocol.BootstrapPackets;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.InventoryPackets;
import com.nineyin.protocol.NpcPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxC2SHandler;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxSession;
import com.nineyin.server.GameScheduler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 场景同步与自定义消息总线：
 * - 67 字节移动包：更新位置、取消打坐、2 秒节流落库、>1m 触发 NPC 增量补发（上限 50）
 * - 自定义 msg（0x0A 信封 + 0x1E/0x27 直连两种载体，对齐 Go）：
 *   240 打坐、200/201 快捷键、211 技能（skill_init 学习校验 + 编译目录计费/CD/动作/区域结算）、
 *   216/217 轻功、218 招架、223/5223 操作模式、64/70/71 商店（打开流程见 SelectFlow）
 * - 传送门：移动进入触发半径（door_data.ini MoveTarget/SpringRange）
 * - 巡逻广播（任意 0x0A 驱动，500ms 节流）
 * - 每秒 tick（NineyinServer 驱动）：actor 恢复 + vital 同步 + NPC 重生
 */
public final class SceneFlow {

    public static final AttributeKey<SceneState> STATE =
            AttributeKey.valueOf("nineyin.scene.state");

    public static final int OP_ACTIVITY = 0x0A;
    public static final int OP_PING = 0x18;
    public static final int OP_CUSTOM_DIRECT_LEGACY = 0x1E;
    public static final int OP_CUSTOM_DIRECT_MODERN = 0x27;

    public static final float VIEW_RADIUS = 450f;
    private static final int INITIAL_NPC_CAP = 200;
    /** 副本场景全量摆位（clone003 实测约 330）一次性下发上限。 */
    private static final int CLONE_NPC_CAP = 400;
    private static final int STREAM_NPC_CAP = 50;
    private static final float PATROL_BROADCAST_RADIUS = 250f;
    private static final int PATROL_BROADCAST_CAP = 32;
    private static final long PORTAL_TRIGGER_COOLDOWN_MS = 2000;
    private static final long NPC_RESPAWN_MS = 15000;

    private static final Logger log = LoggerFactory.getLogger(SceneFlow.class);

    private final SceneManager sceneManager;
    private final RoleStore roleStore;
    private final QinggongCatalog qinggongCatalog;
    private final SceneSwitcher switcher;
    private final AiEventEngine ai;   // 可空：未接线时无 AI 事件（测试旧路径）
    private final NinJiuZhi ninjiuzhi;  // 全服阶段门控（缺省 alwaysOpen）
    /** 库存查询（MySQL 后端；JSON 后端为 null，msgId=30 移动回退日志）。 */
    private final com.nineyin.db.RoleInventoryStore inventory;
    /** 技能编译目录与学习表（211；空目录时全部走"未编译"拒绝路径）。 */
    private final SkillCatalog skillCatalog;
    private final SkillInitTable skillInit;

    /** 每会话场景同步状态。 */
    public static final class SceneState {

        public SceneWorld world;
        public boolean npcsSent;
        public final Set<Integer> sentIds = new HashSet<>();
        public final Map<Integer, Long> legSent = new HashMap<>();       // oid -> leg.startedMs
        public final Map<Integer, PendingArrive> pendingArrive = new HashMap<>();
        public float lastNpcX;
        public float lastNpcZ;
        public long lastPatrolMs;
        public long lastPosSaveMs;
        // 选中目标（SelectFlow 维护）
        public int selectedOid;
        public int selectedOwner;
        public float selectedX;
        public float selectedZ;
        // NPC 菜单（帧对话）
        public int lastMenuOid;
        public int lastMenuOwner;
        public long lastMenuAtMs;
        // 0x0C 重进场景
        public boolean awaitingReentryReady;
        public boolean qinggongGranted;
        public boolean progressGranted;
        // 轻量战斗（历史节流字段已由技能锁/CD 取代）
        public long lastPortalMs;
        // 每秒 tick
        public long lastVitalMs;
    }

    public record PendingArrive(long atMs, float x, float y, float z, float orient) {
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog) {
        this(sceneManager, roleStore, qinggongCatalog, null, null);
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog,
                     AiEventEngine ai) {
        this(sceneManager, roleStore, qinggongCatalog, ai, null);
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog,
                     AiEventEngine ai, NinJiuZhi ninjiuzhi) {
        this(sceneManager, roleStore, qinggongCatalog, ai, ninjiuzhi, new SkillCatalog(), new SkillInitTable());
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog,
                     AiEventEngine ai, NinJiuZhi ninjiuzhi,
                     SkillCatalog skillCatalog, SkillInitTable skillInit) {
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
        this.qinggongCatalog = qinggongCatalog;
        this.ninjiuzhi = ninjiuzhi != null ? ninjiuzhi : NinJiuZhi.alwaysOpen();
        this.switcher = new SceneSwitcher(sceneManager, roleStore, this, this.ninjiuzhi);
        this.ai = ai;
        this.inventory = com.nineyin.db.RoleInventoryStore.from(roleStore);
        this.skillCatalog = skillCatalog != null ? skillCatalog : new SkillCatalog();
        this.skillInit = skillInit != null ? skillInit : new SkillInitTable();
    }

    /** 九阴志全局门控（GM / 广播接线用）。 */
    public NinJiuZhi ninjiuzhi() {
        return ninjiuzhi;
    }

    public void register(FxOpcodeRouter router) {
        router.register(OP_ACTIVITY, this::onActivity);
        router.register(OP_PING, this::onPing);
        router.register(OP_CUSTOM_DIRECT_LEGACY, this::onDirectCustom);
        router.register(OP_CUSTOM_DIRECT_MODERN, this::onDirectCustom);
    }

    public SceneSwitcher switcher() {
        return switcher;
    }

    public SceneState stateOf(FxSession fx) {
        SceneState st = fx.channel().attr(STATE).get();
        if (st == null) {
            st = new SceneState();
            fx.channel().attr(STATE).set(st);
        }
        return st;
    }

    /** LoginFlow 的 ClientReady 钩子：首次下发场景 NPC。 */
    public void sendSceneNpcs(LoginSession session) {
        FxSession fx = session.fx;
        SceneState st = stateOf(fx);
        st.sentIds.clear();
        st.world = sceneManager.world(session.spawnResource);
        st.world.tick(System.currentTimeMillis());
        List<NpcInstance> visible = visibleNpcs(st.world, session.x, session.z);
        int cap = st.world.cloneScene ? CLONE_NPC_CAP : INITIAL_NPC_CAP;
        int sent = 0;
        for (NpcInstance n : visible) {
            float dx = n.x - session.x;
            float dz = n.z - session.z;
            if (dx * dx + dz * dz > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
            fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
            st.sentIds.add(n.objectId);
            if (++sent >= cap) {
                break;
            }
        }
        st.lastNpcX = session.x;
        st.lastNpcZ = session.z;
        st.npcsSent = true;
        log.info("scene {} NPCs sent={} of {} loaded (portals={})", session.spawnResource, sent,
                st.world.npcs.size(), st.world.portals.size());
    }

    /** 模板命中 + 非剧情 + 非活动怪，按距离排序（相近时功能 NPC 优先）；副本场景按 NpcProgress 波次门控。 */
    List<NpcInstance> visibleNpcs(SceneWorld world, float px, float pz) {
        List<NpcInstance> hits;
        synchronized (world) {
            hits = new ArrayList<>();
            for (NpcInstance n : world.npcs) {
                var tmpl = sceneManager.templates().lookup(n.configId);
                if (tmpl == com.nineyin.scene.NpcTemplate.MISSING) {
                    continue;
                }
                if ("eventnpc".equalsIgnoreCase(tmpl.scriptClass())) {
                    continue;
                }
                if (world.cloneScene && n.npcProgress > 0 && world.dungeonProgress < n.npcProgress) {
                    continue;
                }
                if (!ninjiuzhi.npcOpen(n.configId)) {
                    continue;
                }
                if (n.dead) {
                    continue;
                }
                hits.add(n);
            }
        }
        hits.sort(Comparator.comparingDouble((NpcInstance n) -> {
            float dx = n.x - px;
            float dz = n.z - pz;
            return (double) (dx * dx + dz * dz);
        }).thenComparingInt(SceneFlow::residentScore));
        return hits;
    }

    /** 重生间隔：数据 RefreshTime 优先，未配置走 15s 默认。 */
    static long respawnDelay(NpcInstance n) {
        return n.refreshMs >= 0 ? n.refreshMs : NPC_RESPAWN_MS;
    }

    private static int residentScore(NpcInstance n) {
        String id = n.configId.toLowerCase(Locale.ROOT);
        return id.contains("leader") || id.contains("world") ? -100 : 0;
    }

    // ---------- 0x18 ping → 回显客户端 tick 的 0x38（HUD 延迟显示依赖此回包） ----------

    private void onPing(FxSession fx, byte[] plain) {
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        if (session == null) {
            return;
        }
        int tick = plain.length >= 5 ? leU32(plain, 1) : 0;
        fx.send(S2CPackets.timeSync(tick, session.timeSeq++));
        if (++session.pingCount % 10 == 0) {
            fx.send(S2CPackets.objectPropertyInt(PropertyId.TIME_TICK,
                    LocalDateTime.now().getHour() * 60 + LocalDateTime.now().getMinute()));
        }
    }

    // ---------- 0x0A ----------

    private void onActivity(FxSession fx, byte[] plain) {
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        if (session == null) {
            return;
        }
        SceneState st = stateOf(fx);

        if (st.awaitingReentryReady) {
            // 目标场景加载期的旧场景活动包，忽略
            return;
        }

        // 67 字节移动位置包：偏移 0x26 起 4 个类型码 4 的 f32
        if (plain.length == 67 && plain.length > 0x26 + 20
                && plain[0x26] == 4 && plain[0x26 + 5] == 4
                && plain[0x26 + 10] == 4 && plain[0x26 + 15] == 4) {
            float mx = readF32(plain, 0x27);
            float my = readF32(plain, 0x2C);
            float mz = readF32(plain, 0x31);
            float mo = readF32(plain, 0x36);
            session.x = mx;
            session.y = my;
            session.z = mz;
            session.orient = mo;
            // 移动取消打坐
            if (session.actor != null && session.actor.logicState == PlayerActor.LOGIC_STATE_SITCROSS) {
                session.actor.logicState = PlayerActor.LOGIC_STATE_NORMAL;
                fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, 0));
                fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, "stand"));
                log.info("sitcross cancelled by move");
            }
            long now = System.currentTimeMillis();
            if (session.accountKey.length() > 8 && now - st.lastPosSaveMs > 2000) {
                st.lastPosSaveMs = now;
                roleStore.updateLocation(session.accountKey, mx, my, mz, mo);
            }
            // 选中目标超出 28m 自动清除
            SelectFlow.autoClearIfNeeded(fx, session, st);
            // 传送门 proximity 触发
            if (checkPortalProximity(fx, session, st, now)) {
                return;
            }
            // >1m 触发增量补发
            if (st.npcsSent && st.world != null) {
                float moved = (session.x - st.lastNpcX) * (session.x - st.lastNpcX)
                        + (session.z - st.lastNpcZ) * (session.z - st.lastNpcZ);
                if (moved > 1.0f) {
                    st.lastNpcX = session.x;
                    st.lastNpcZ = session.z;
                    streamNewNpcs(fx, st, session);
                }
            }
        } else {
            CustomMsg msg = CustomMsg.parse(plain);
            if (msg.ok()) {
                dispatchCustom(fx, session, msg);
            }
        }

        // 任意 0x0A 驱动巡逻广播（500ms 节流）
        broadcastPatrol(fx, st, session);
    }

    // ---------- 0x1E / 0x27 直连自定义消息 ----------

    private void onDirectCustom(FxSession fx, byte[] plain) {
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        if (session == null) {
            return;
        }
        CustomMsg msg = CustomMsg.parseDirect(plain);
        if (!msg.ok() || msg.val(0) == null || msg.val(0).type != CustomMsg.TYPE_I32) {
            log.debug("reject malformed C2S custom opcode=0x{} from {}",
                    Integer.toHexString(plain[0] & 0xFF), fx.remoteAddress());
            return;
        }
        dispatchCustom(fx, session, msg);
    }

    /** 传送门 proximity：进入 MoveTarget/SpringRange 半径即触发（零售客户端行为）。 */
    private boolean checkPortalProximity(FxSession fx, LoginSession session, SceneState st, long now) {
        if (st.world == null || st.world.portals.isEmpty()) {
            return false;
        }
        if (now - st.lastPortalMs < PORTAL_TRIGGER_COOLDOWN_MS) {
            return false;
        }
        for (SceneWorld.Portal portal : st.world.portals) {
            com.nineyin.scene.DoorRoutes.Route route = portal.route();
            float dx = route.trigger().x() - session.x;
            float dz = route.trigger().z() - session.z;
            float radius = route.triggerRadius();
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            st.lastPortalMs = now;
            if (route.sameScene()) {
                switcher.teleportWithinScene(fx, session, st,
                        route.position().x(), route.position().y(),
                        route.position().z(), route.position().orient());
                log.info("portal proximity teleport source={} npc={}", route.source(), portal.objectId());
            } else {
                switcher.switchScene(fx, session, st, route.config(), route.resource(),
                        route.position().x(), route.position().y(),
                        route.position().z(), route.position().orient());
                log.info("portal proximity switch source={} npc={} -> {}/{}",
                        route.source(), portal.objectId(), route.config(), route.resource());
            }
            return true;
        }
        return false;
    }

    private void streamNewNpcs(FxSession fx, SceneState st, LoginSession session) {
        int added = 0;
        for (NpcInstance n : visibleNpcs(st.world, session.x, session.z)) {
            if (st.sentIds.contains(n.objectId)) {
                continue;
            }
            float dx = n.x - session.x;
            float dz = n.z - session.z;
            if (dx * dx + dz * dz > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
            fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
            st.sentIds.add(n.objectId);
            if (++added >= STREAM_NPC_CAP) {
                break;
            }
        }
    }

    void dispatchCustom(FxSession fx, LoginSession session, CustomMsg msg) {
        int mid = msg.msgId();
        switch (mid) {
            case 240 -> handleSit(fx, session, msg);
            case 30 -> handleMoveItem(fx, session, msg);
            case 200 -> handleShortcutSet(fx, session, msg);
            case 201 -> handleShortcutRemove(fx, session, msg);
            case 211 -> handleSkill(fx, session, msg);
            case 216 -> handleQingGong(fx, session, msg);
            case 217 -> handleActiveQingGong(fx, session, msg);
            case 218 -> handleActiveParry(fx, session, msg);
            case 223, 5223 -> {
                CustomMsg.Val v = msg.val(1);
                if (v != null && v.type == CustomMsg.TYPE_I32) {
                    session.operateMode = v.asInt();
                    fx.send(S2CPackets.objectPropertyInt(223, session.operateMode));
                }
            }
            // 显示装备类型（参考服：showequip_type set to %d / invalid value %d；
            // 外观 gear visual 帧的线格式未钉死，先校验+存储）
            case 61 -> handleShowEquipType(fx, session, msg);
            // PK 三开关（参考服参数校验串钉死布局）：
            // 291 TVarList(291, mode)；292 无参 toggle（响应走 normal 0x10，属性待钉死）；
            // 293 TVarList(293, type, onoff)
            case 291 -> handleChangePkMode(fx, session, msg);
            case 292 -> handleChangeWarning(fx, session, msg);
            case 293 -> handlePkProtectToggle(fx, session, msg);
            // 用户须知"同意"确认（实测：客户端进世界后 ~30s 首发并每 ~1 分钟重复，直至服务器确认）
            case 819 -> handleTermsSign(fx, session);
            // 商店购买流程（64 打开 / 70 购买 / 71 关闭）：S2C 响应格式待抓包钉死，先对齐 C++ 记日志
            case 64 -> log.info("shop msg=64 open argc={} (response pending capture)", msg.size());
            case 70 -> log.info("shop msg=70 buy argc={} (response pending capture)", msg.size());
            case 71 -> log.info("shop msg=71 close argc={}", msg.size());
            default -> {
                // 监控：未识别 custom 消息按会话首现打 INFO（含解析后的参数），
                // 用于发现"同意用户协议"等稀疏客户端事件的 msgId；重复出现降为 DEBUG 防刷屏
                if (session.seenCustomMsgIds.add(mid)) {
                    log.info("custom msgId={} first this session account={} argc={} vals={}",
                            mid, session.accountKey, msg.size(), summarizeVals(msg));
                } else {
                    log.debug("custom msgId={} argc={} vals={}", mid, msg.size(), summarizeVals(msg));
                }
            }
        }
    }

    /**
     * 819 用户须知签署：落库 agreed_terms/agreed_terms_at（幂等，重复点击不刷新时间），
     * 回推属性 269 签署时间 / 270 签署标志，客户端据此停止周期性重弹协议框。
     */
    private void handleTermsSign(FxSession fx, LoginSession session) {
        if (session.accountKey.isEmpty()) {
            return;
        }
        boolean first = roleStore.markTermsAgreed(session.accountKey);
        long[] t = roleStore.termsState(session.accountKey);
        session.termsAgreed = (int) t[0];
        session.termsAgreedAtSec = t[1];
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_LAST_SIGN_TIME, (int) t[1]));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_SIGN_COUNT, (int) t[0]));
        log.info("terms sign accepted account={} first={} signCount={} at={} ip={}",
                session.accountKey, first, t[0], t[1], fx.remoteAddress());
    }

    /**
     * 291 PK 模式切换（参考服：change pk mode expects TVarList(291, mode) / invalid mode）。
     * 权威值 0x10 推送 PKMode(436, short)；官服属性表 type 1 → i16 编码。
     */
    private void handleChangePkMode(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 291, TVarList.intArg("mode"));
        if (!r.ok()) {
            log.info("change pk mode expects {}, got {}", r.expected(), r.got());
            return;
        }
        int mode = msg.val(1).intOrZero();
        if (mode < 0 || mode > 2) {
            log.info("change pk mode invalid mode {}", mode);
            return;
        }
        int old = session.pkMode;
        session.pkMode = mode;
        fx.send(S2CPackets.objectPropertyShort(PropertyId.PK_MODE, mode));
        log.info("pk mode {} -> {} (property 436 pushed)", old, mode);
    }

    /**
     * 292 警告开关（参考服：warning expects TVarList(292) with no extra values；
     * change warning %d -> %d via normal 0x10）。0x10 目标属性 ID 未从 exe 钉死，
     * 先翻转+存储，属性推送待抓包补上（见 docs/go-exe-protocol-analysis.md §3.2）。
     */
    private void handleChangeWarning(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 292);
        if (!r.ok()) {
            log.info("change warning expects {} with no extra values, got {}", r.expected(), r.got());
            return;
        }
        boolean next = !session.warningOn;
        int old = session.warningOn ? 1 : 0;
        session.warningOn = next;
        log.info("change warning {} -> {} (0x10 property pending capture)", old, next ? 1 : 0);
    }

    /**
     * 293 PK 保护开关（参考服：toggle expects TVarList(293, type, onoff)；
     * client pk protect toggle type=%d on=%d）。
     */
    private void handlePkProtectToggle(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 293, TVarList.intArg("type"), TVarList.intArg("onoff"));
        if (!r.ok()) {
            log.info("pk protect toggle expects {}, got {}", r.expected(), r.got());
            return;
        }
        session.pkProtectType = msg.val(1).intOrZero();
        session.pkProtectOn = msg.val(2).intOrZero() != 0;
        log.info("pk protect toggle type={} on={}", session.pkProtectType, session.pkProtectOn);
    }

    /** 61 显示装备类型（参考服：showequip_type set to %d / invalid value %d）。 */
    private void handleShowEquipType(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 61, TVarList.intArg("type"));
        if (!r.ok()) {
            log.info("showequip_type expects {}, got {}", r.expected(), r.got());
            return;
        }
        int type = msg.val(1).intOrZero();
        if (type < 0) {
            log.info("showequip_type invalid value {}", type);
            return;
        }
        session.showEquipType = type;
        log.info("showequip_type set to {}", type);
    }

    /** 参数摘要（单值 48 字符、总长 160 截断）：统一走 TVarList.describe，与校验错误文本同格式。 */
    private static String summarizeVals(CustomMsg msg) {
        return TVarList.describe(msg);
    }

    /** 打坐：cmd 1=坐下(102) 0/2=起身(0)；权威状态在 actor，并按 tick 恢复血蓝。 */
    private void handleSit(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        TVarList.Result r = TVarList.check(msg, 240, TVarList.intArg("start_or_stop"));
        if (!r.ok()) {
            log.info("sitcross expects {}, got {}", r.expected(), r.got());
            return;
        }
        int next = switch (msg.val(1).intOrZero()) {
            case 1 -> PlayerActor.LOGIC_STATE_SITCROSS;
            case 0, 2 -> PlayerActor.LOGIC_STATE_NORMAL;
            default -> session.actor.logicState;
        };
        if (next == session.actor.logicState) {
            return;
        }
        session.actor.logicState = next;
        fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, next));
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, next == PlayerActor.LOGIC_STATE_SITCROSS ? "sitdown" : "stand"));
        log.info("sitcross LogicState -> {} (cmd={})", next, msg.val(1).intOrZero());
    }

    /** 200 设快捷键：idx, kind(str 或 int→skill), id；同 index 替换（先 0x12 删旧行）。 */
    /**
     * msgId=30 移动物品（参考服日志钉死语义）：args=[fromView, fromSlot, toView, toSlot]。
     * 卸下=[1,槽位,121,0]（0=自动选格）；穿上=[121,格位,1,身体槽位]；背包页间移动同构。
     * 帧：源/目标按 0x19 ViewRemove 清格（页与镜像成对），0x18 ViewAdd 落位；
     * 库内同步移动并重建 inventoryFrames 缓存（0x0C 重进场景用）。
     */
    private void handleMoveItem(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.size() < 5 || session.roleId == 0 || session.liveBagged == null
                || session.liveEquipped == null || inventory == null) {
            log.info("move 30 ignored: no live inventory role={} argc={}",
                    session.roleId, msg.size());
            return;
        }
        int fromView = intArg(msg, 1);
        int fromSlot = intArg(msg, 2);
        int toView = intArg(msg, 3);
        int toSlot = intArg(msg, 4);
        long roleId = session.roleId;
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        if (fromView == InventoryPackets.VIEW_EQUIP_DOLL) {
            // 卸下：装备栏 → 背包装备页
            com.nineyin.db.RoleInventoryStore.Equipped item = removeEquipped(session, fromSlot);
            if (item == null) {
                log.info("move 30 equip[{}] empty", fromSlot);
                return;
            }
            int view = bagWireToCategory(toView) != 0 ? toView : InventoryPackets.VIEW_EQUIP_BAG;
            int slot = toSlot > 0 ? toSlot : freeBagSlot(session.liveBagged, view);
            out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_DOLL, fromSlot));
            out.add(InventoryPackets.viewRemove(view, slot));
            out.add(InventoryPackets.bagViewAdd(
                    new com.nineyin.db.RoleInventoryStore.Bagged(
                            slot, item.configId(), item.itemType(), 1,
                            bagWireToCategory(view), ""),
                    InventoryPackets.uniqueId(roleId, "bag", slot), view));
            int mirror = InventoryPackets.mirrorOf(view);
            if (mirror != 0) {
                out.add(InventoryPackets.viewRemove(mirror, slot));
            }
            session.liveBagged.add(new com.nineyin.db.RoleInventoryStore.Bagged(
                    slot, item.configId(), item.itemType(), 1, bagWireToCategory(view), ""));
            if (!inventory.moveEquipToBag(roleId, fromSlot, slot)) {
                log.warn("move 30 equip[{}]->bag[{}] persist failed", fromSlot, slot);
            }
            log.info("move 30 equip[{}]->bag[{}] config={}", fromSlot, slot, item.configId());
        } else if (toView == InventoryPackets.VIEW_EQUIP_DOLL) {
            // 穿上：背包页 → 装备栏（目标槽被占时旧装备让位到空闲背包格）
            int category = bagWireToCategory(fromView);
            com.nineyin.db.RoleInventoryStore.Bagged item = removeBagged(session, fromSlot, category);
            if (item == null) {
                log.info("move 30 bag[{}:{}] empty", fromView, fromSlot);
                return;
            }
            com.nineyin.db.RoleInventoryStore.Equipped old = removeEquipped(session, toSlot);
            int fromMirror = InventoryPackets.mirrorOf(fromView);
            out.add(InventoryPackets.viewRemove(fromView, fromSlot));
            if (fromMirror != 0) {
                out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
            }
            if (old != null) {
                int oldSlot = freeBagSlot(session.liveBagged, InventoryPackets.VIEW_EQUIP_BAG);
                out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_BAG, oldSlot));
                out.add(InventoryPackets.bagViewAdd(
                        new com.nineyin.db.RoleInventoryStore.Bagged(
                                oldSlot, old.configId(), old.itemType(), 1, 2, ""),
                        InventoryPackets.uniqueId(roleId, "bag", oldSlot),
                        InventoryPackets.VIEW_EQUIP_BAG));
                int m = InventoryPackets.mirrorOf(InventoryPackets.VIEW_EQUIP_BAG);
                if (m != 0) {
                    out.add(InventoryPackets.viewRemove(m, oldSlot));
                }
                session.liveBagged.add(new com.nineyin.db.RoleInventoryStore.Bagged(
                        oldSlot, old.configId(), old.itemType(), 1, 2, ""));
                if (!inventory.swapEquipSlot(roleId, toSlot, oldSlot)) {
                    log.warn("move 30 swap equip[{}]->bag[{}] persist failed", toSlot, oldSlot);
                }
            }
            out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_DOLL, toSlot));
            com.nineyin.db.RoleInventoryStore.Equipped worn = new com.nineyin.db.RoleInventoryStore.Equipped(
                    toSlot, item.configId(), item.itemType(),
                    equipTypeOf(item.configId()), 100, 100, "");
            out.add(InventoryPackets.equipmentViewAdd(
                    worn, InventoryPackets.uniqueId(roleId, "equip", toSlot)));
            session.liveEquipped.add(worn);
            if (!inventory.moveBagToEquip(roleId, fromSlot, toSlot, category)) {
                log.warn("move 30 bag[{}]->equip[{}] persist failed", fromSlot, toSlot);
            }
            log.info("move 30 bag[{}:{}]->equip[{}] config={}{}",
                    fromView, fromSlot, toSlot, item.configId(),
                    old != null ? " (swapped out " + old.configId() + ")" : "");
        } else {
            // 背包页间/页内移动
            int category = bagWireToCategory(fromView);
            com.nineyin.db.RoleInventoryStore.Bagged item = removeBagged(session, fromSlot, category);
            if (item == null) {
                log.info("move 30 bag[{}:{}] empty", fromView, fromSlot);
                return;
            }
            int view = toView != 0 ? toView : InventoryPackets.VIEW_TOOL_PAGE;
            int slot = toSlot > 0 ? toSlot : freeBagSlot(session.liveBagged, view);
            int fromMirror = InventoryPackets.mirrorOf(fromView);
            out.add(InventoryPackets.viewRemove(fromView, fromSlot));
            if (fromMirror != 0) {
                out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
            }
            out.add(InventoryPackets.viewRemove(view, slot));
            com.nineyin.db.RoleInventoryStore.Bagged moved =
                    new com.nineyin.db.RoleInventoryStore.Bagged(
                            slot, item.configId(), item.itemType(), item.amount(),
                            bagWireToCategory(view), "");
            out.add(InventoryPackets.bagViewAdd(
                    moved, InventoryPackets.uniqueId(roleId, "bag", slot), view));
            int mirror = InventoryPackets.mirrorOf(view);
            if (mirror != 0) {
                out.add(InventoryPackets.viewRemove(mirror, slot));
            }
            session.liveBagged.add(moved);
            if (!inventory.moveBagSlot(roleId, fromSlot, category, slot, bagWireToCategory(view))) {
                log.warn("move 30 bag[{}]->bag[{}] persist failed", fromSlot, slot);
            }
            log.info("move 30 bag[{}:{}]{}->bag[{}:{}] config={}",
                    fromView, fromSlot, category, view, slot, item.configId());
        }
        // 穿/脱触及装备纸娃娃时重推武器模型（对齐参考服 pushGearVisual：WeaponMode=1597 随 0x10 增量）
        if (fromView == InventoryPackets.VIEW_EQUIP_DOLL || toView == InventoryPackets.VIEW_EQUIP_DOLL) {
            out.add(InventoryPackets.weaponModeFrame(session.liveEquipped,
                    Appearance.resolveVisual(session.appearance).sex()));
        }
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
    }

    private static int intArg(CustomMsg msg, int idx) {
        CustomMsg.Val v = msg.val(idx);
        return v != null && v.asExactInt() ? v.intOrZero() : 0;
    }

    /** 背包 wire 视图 → game_items 分类（1道具 2装备 3材料 4任务；未知 0）。 */
    private static int bagWireToCategory(int view) {
        return switch (view) {
            case InventoryPackets.VIEW_TOOL_PAGE -> 1;
            case InventoryPackets.VIEW_EQUIP_BAG -> 2;
            case InventoryPackets.VIEW_MATERIAL_PAGE -> 3;
            case InventoryPackets.VIEW_TASK_PAGE -> 4;
            default -> 0;
        };
    }

    private static com.nineyin.db.RoleInventoryStore.Equipped removeEquipped(
            LoginSession session, int slot) {
        for (int i = 0; i < session.liveEquipped.size(); i++) {
            if (session.liveEquipped.get(i).slot() == slot) {
                return session.liveEquipped.remove(i);
            }
        }
        return null;
    }

    private static com.nineyin.db.RoleInventoryStore.Bagged removeBagged(
            LoginSession session, int slot, int category) {
        for (int i = 0; i < session.liveBagged.size(); i++) {
            var b = session.liveBagged.get(i);
            if (b.slot() == slot && (category == 0 || b.viewId() == category)) {
                return session.liveBagged.remove(i);
            }
        }
        return null;
    }

    /** 目标背包页第一个空格（1 起，无空位返回 max+1，上限 90）。 */
    private static int freeBagSlot(List<com.nineyin.db.RoleInventoryStore.Bagged> bagged, int view) {
        int category = bagWireToCategory(view);
        boolean[] used = new boolean[96];
        for (var b : bagged) {
            if (b.viewId() == category && b.slot() > 0 && b.slot() < used.length) {
                used[b.slot()] = true;
            }
        }
        for (int i = 1; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return 91;
    }

    /** 穿上时装备类型（equipment.ini；缺失空串，帧里同样容忍）。 */
    private static String equipTypeOf(String configId) {
        var st = com.nineyin.protocol.EquipmentModels.itemStatic(configId);
        return st != null ? st.equipType() : "";
    }

    private void handleShortcutSet(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null || msg.size() < 4) {
            return;
        }
        CustomMsg.Val idxVal = msg.val(1);
        if (idxVal == null || !idxVal.asExactInt()) {
            return;
        }
        int idx = idxVal.intOrZero();
        String kind = msg.val(2).str != null ? msg.val(2).str : "skill";
        if (kind.isEmpty() && msg.val(2).type == CustomMsg.TYPE_I32) {
            kind = "skill";
        }
        String id = msg.val(3).str != null ? msg.val(3).str : "";
        int oldRow = session.actor.setShortcut(idx, kind, id);
        if (oldRow >= 0) {
            fx.send(S2CPackets.recordDelRow(com.nineyin.protocol.RecordId.SHORTCUT_REC, oldRow));
        }
        fx.send(S2CPackets.shortcutAdd(idx, kind, id));
        log.info("set shortcut idx={} kind={} id={}", idx, kind, id);
    }

    /** 201 删快捷键行。 */
    private void handleShortcutRemove(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        CustomMsg.Val v = msg.val(1);
        if (v == null || !v.asExactInt()) {
            return;
        }
        int row = v.intOrZero();
        PlayerActor.Shortcut removed = session.actor.removeShortcutRow(row);
        if (removed != null) {
            fx.send(S2CPackets.recordDelRow(com.nineyin.protocol.RecordId.SHORTCUT_REC, row));
            log.info("remove shortcut row={} id={}", row, removed.id());
        }
    }

    /**
     * 211 技能释放（对照参考 exe handleSkillCustom 的校验链与日志）：
     * 参数 → 出生 → 已学习 → 可编译 → 目标 → 锁/CD/死亡/MP/SP/轻功点计费
     * → Buff 暂驻 + cooldown_rec 行 + vital + State 起手动作与后续段 → 伤害/控制结算 →
     * 击杀碎银掉落 + total_action 后技能态清零回 stand。
     */
    private void handleSkill(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val idVal = msg.val(1);
        if (idVal == null || idVal.type != CustomMsg.TYPE_STRING
                || idVal.str == null || idVal.str.isEmpty()) {
            log.info("reject malformed or unlearned USE_SKILL argc={} vals={}",
                    msg.size(), summarizeVals(msg));
            return;
        }
        String skillId = idVal.str;
        PlayerActor actor = session.actor;
        if (actor == null) {
            log.info("reject skill before player spawn id={}", skillId);
            return;
        }
        // 学习校验：全局 skill_init.ini 表优先（私服全学形态），否则角色内置集
        int level = skillInit.size() > 0 ? skillInit.level(skillId) : 0;
        if (level <= 0) {
            level = actor.learnedSkillLevel(skillId);
        }
        if (level <= 0) {
            log.info("reject unlearned installed skill id={}", skillId);
            return;
        }
        SkillCatalog.Definition def = skillCatalog.definition(skillId, level);
        if (def == null) {
            log.info("reject learned skill without compiled level response id={} level={}", skillId, level);
            return;
        }
        SceneState st = stateOf(fx);
        int targetOid = S2CPackets.PLAYER_OBJECT_ID;
        int targetOwner = S2CPackets.PLAYER_OWNER_ID;
        NpcInstance selected = null;
        if (def.requiresTarget()) {
            selected = selectedLivingTarget(st);
            if (selected == null) {
                log.info("reject skill id={} without living selected target", skillId);
                return;
            }
            targetOid = selected.objectId;
            targetOwner = selected.ownerId;
        }
        long now = System.currentTimeMillis();
        String reject = beginSkillUse(actor, def, targetOid, targetOwner, now);
        if (reject != null) {
            log.info("reject skill id={}: {}", skillId, reject);
            return;
        }
        stageSkillBuffs(fx, actor, def, now);
        // cooldown_rec 行（表 5：category, startMs, endMs, team）
        fx.send(S2CPackets.recordAddRow(com.nineyin.protocol.RecordId.COOLDOWN_REC, 0,
                def.cooldownCategory(), now, now + def.personalCdMs(), def.cooldownTeam()));
        fx.send(actor.vitalUpdate());
        if (!def.actionName().isEmpty()) {
            fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, def.actionName()));
            scheduleFollowupActions(fx, def);
        }
        int damageDealt = 0;
        int targetCount = 0;
        int lootTotal = 0;
        for (SkillCatalog.Effect effect : def.effects()) {
            if (effect.kind() != SkillCatalog.EffectKind.DAMAGE || effect.amount() <= 0) {
                continue;
            }
            if (def.targetMode() == SkillCatalog.TargetMode.SELF) {
                continue;   // 自身技无目标结算（对齐参考实现的 damage switch 无 SELF 分支）
            }
            SettleSummary settle = def.targetMode() == SkillCatalog.TargetMode.SELECTED
                    ? settleSelected(fx, session, st, def, selected, now)
                    : settleArea(fx, session, st, def, selected, msg, now);
            damageDealt += settle.damage();
            targetCount += settle.targets();
            lootTotal += settle.loot();
        }
        for (SkillCatalog.Effect effect : def.effects()) {
            if (effect.kind() == SkillCatalog.EffectKind.TARGET_CONTROL) {
                log.info("settled compiled target control skill={} buff={} targets={} lifetime={}ms",
                        def.id(), effect.buff().configId(), targetCount, effect.buff().lifetimeMs());
            }
        }
        if (lootTotal > 0) {
            actor.silver += lootTotal;
            fx.send(S2CPackets.playerBatch()
                    .i64(PropertyId.CAPITAL_TYPE1, actor.silver).build());
            log.info("skill defeated NPCs targets={} silver_drop={}", targetCount, lootTotal);
        }
        log.info("accepted skill id={} mp={} sp={} qg={} damage={} target={} targets={} personal_cd={}ms public_cd={}ms",
                skillId, actor.mp, actor.sp, actor.qinggongPoint, damageDealt, targetOid,
                targetCount, def.personalCdMs(), def.publicCdMs());
        scheduleCurrentSkillClear(fx, actor, def);
    }

    /** 计费与互斥（对照 Go beginSkillUse；失败返回原因文本，成功返回 null 并落账）。 */
    private String beginSkillUse(PlayerActor actor, SkillCatalog.Definition def,
                                 int targetOid, int targetOwner, long now) {
        if (now < actor.skillLockUntilMs) {
            return "skill locked remaining=" + (actor.skillLockUntilMs - now) + "ms";
        }
        Long ready = actor.skillCooldowns.get(def.id());
        if (ready != null && ready > now) {
            return "personal cooldown remaining=" + (ready - now) + "ms";
        }
        int teamKey = def.cooldownTeam() != 0 ? def.cooldownTeam() : -def.cooldownCategory();
        Long teamReady = actor.skillTeamCooldowns.get(teamKey);
        if (teamReady != null && teamReady > now) {
            return "public cooldown team=" + teamKey + " remaining=" + (teamReady - now) + "ms";
        }
        if (actor.hp <= 0) {
            return "player is dead";
        }
        if (actor.mp < def.mpCost()) {
            return "MP " + actor.mp + " < " + def.mpCost();
        }
        if (actor.sp < def.spCost()) {
            return "SP " + actor.sp + " < " + def.spCost();
        }
        if (def.qgCost() > 0 && !actor.useQingGong(def.qgCost())) {
            return "QingGongPoint cannot pay " + def.qgCost();
        }
        if (!actor.spendMp(def.mpCost())) {
            return "MP spend rejected";
        }
        actor.sp -= def.spCost();
        actor.skillCooldowns.put(def.id(), now + def.personalCdMs());
        actor.skillTeamCooldowns.put(teamKey, now + def.publicCdMs());
        actor.skillLockUntilMs = now + SKILL_LOCK_MS;
        actor.curSkillId = def.id();
        actor.curSkillEffectId = def.id();
        actor.curSkillLevel = def.level();
        actor.curSkillTargetOid = targetOid;
        actor.curSkillTargetOwner = targetOwner;
        actor.logicState = PlayerActor.LOGIC_STATE_FIGHTING;
        return null;
    }

    /** 技能 Buff 暂驻（自身 Buff / 架势轮换 / 红霸体），日志与参考服同构。 */
    private void stageSkillBuffs(FxSession fx, PlayerActor actor, SkillCatalog.Definition def, long now) {
        for (SkillCatalog.Effect effect : def.effects()) {
            SkillCatalog.BuffDef buff = null;
            switch (effect.kind()) {
                case PLAYER_BUFF -> buff = effect.buff();
                case STANCE_CYCLE -> buff = actor.nextStanceVariant(effect.buffVariants(), now);
                case RED_ARMOR -> buff = new SkillCatalog.BuffDef("buf_fixed_red",
                        PlayerActor.RED_ARMOR_STATIC, 1,
                        Math.max(1, def.totalActionDurationMs()), PlayerActor.BUFF_SLOT_RED_ARMOR);
                default -> {
                    continue;
                }
            }
            if (buff == null) {
                continue;
            }
            int slot = actor.activateSkillBuff(buff, now);
            if (slot == 0) {
                log.info("no native BuffInfo slot available skill={} buff={}", def.id(), buff.configId());
                continue;
            }
            log.info("staged compiled skill Buff skill={} buff={} property={} value={} expiry={}",
                    def.id(), buff.configId(), PropertyId.bufferInfoSlot(slot),
                    actor.buffInfo(slot), actor.buffExpiresAt(slot));
        }
    }

    /** zhaoshi 后续段：按 at 偏移补发 State 动作。 */
    private void scheduleFollowupActions(FxSession fx, SkillCatalog.Definition def) {
        for (SkillCatalog.Segment segment : def.followupActions()) {
            GameScheduler.schedule(Math.max(0, segment.atMs()), () -> {
                fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, segment.action()));
                log.info("skill follow-up State sent id={} action={} at={}ms",
                        def.id(), segment.action(), segment.atMs());
            });
        }
    }

    /** total_action 后清技能态并回 stand（旧定时器不覆盖新技能）。 */
    private void scheduleCurrentSkillClear(FxSession fx, PlayerActor actor, SkillCatalog.Definition def) {
        long delay = Math.max(1, def.totalActionDurationMs());
        GameScheduler.schedule(delay, () -> {
            if (!actor.clearCurrentSkill(def.id())) {
                return;
            }
            fx.send(actor.currentSkillClearFrame());
            fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, "stand"));
            log.info("skill state cleared id={} total_action={}ms", def.id(), delay);
        });
    }

    /** 选中且存活的可攻击目标。 */
    private NpcInstance selectedLivingTarget(SceneState st) {
        if (st.world == null || st.selectedOid == 0) {
            return null;
        }
        NpcInstance npc;
        synchronized (st.world) {
            npc = st.world.npcById(st.selectedOid);
        }
        return npc != null && !npc.dead && npc.hp > 0 ? npc : null;
    }

    /** 效果结算汇总：实际伤害 / 命中目标数 / 击杀掉落（碎银）。 */
    private record SettleSummary(int damage, int targets, int loot) {
    }

    /** 单体伤害效果结算（SELECTED）。 */
    private SettleSummary settleSelected(FxSession fx, LoginSession session, SceneState st,
                                         SkillCatalog.Definition def, NpcInstance selected, long now) {
        if (selected == null) {
            return new SettleSummary(0, 0, 0);
        }
        HitResult hit = settleSkillHit(fx, session, st, selected, def, now);
        return new SettleSummary(hit.damage(), hit.landed() ? 1 : 0, hit.loot());
    }

    /** 区域伤害效果结算（圆/扇/矩形/目标区域；日志与参考服同构，split/cadence 待钉死）。 */
    private SettleSummary settleArea(FxSession fx, LoginSession session, SceneState st,
                                     SkillCatalog.Definition def, NpcInstance selected,
                                     CustomMsg msg, long now) {
        CastCenter center = castCenter(st, selected, msg, session);
        if (center == null) {
            log.info("compiled area skill id={} has no center; no targets settled", def.id());
            return new SettleSummary(0, 0, 0);
        }
        List<NpcInstance> hits = areaTargets(st, center, def);
        int damage = 0;
        int targets = 0;
        int loot = 0;
        for (NpcInstance npc : hits) {
            HitResult hit = settleSkillHit(fx, session, st, npc, def, now);
            damage += hit.damage();
            targets += hit.landed() ? 1 : 0;
            loot += hit.loot();
        }
        log.info("settled compiled area skill id={} mode={} center=({},{},{}) radius={} angle={} width={} hits={} split={} cadence={}ms damage_total={}",
                def.id(), def.targetMode().ordinal(),
                String.format(Locale.ROOT, "%.2f", center.x()),
                String.format(Locale.ROOT, "%.2f", center.y()),
                String.format(Locale.ROOT, "%.2f", center.z()),
                String.format(Locale.ROOT, "%.1f", def.areaRadius()),
                String.format(Locale.ROOT, "%.1f", def.sectorAngle()),
                String.format(Locale.ROOT, "%.1f", def.areaWidth()),
                hits.size(), 1, 0, damage);
        return new SettleSummary(damage, targets, loot);
    }

    /** 区域技能中心：SELECTED_AREA 取目标位置，其余取施法者报点（msg 2/3/4=xyz，5=朝向）。 */
    private record CastCenter(float x, float y, float z, float orient) {
    }

    private CastCenter castCenter(SceneState st, NpcInstance selected, CustomMsg msg, LoginSession session) {
        if (selected != null) {
            return new CastCenter(selected.x, selected.y, selected.z, selected.orient);
        }
        float x = session.x;
        float y = session.y;
        float z = session.z;
        float orient = session.orient;
        CustomMsg.Val vx = msg.val(2);
        CustomMsg.Val vy = msg.val(3);
        CustomMsg.Val vz = msg.val(4);
        if (vx != null && vy != null && vz != null
                && (vx.type == CustomMsg.TYPE_F32 || vx.type == CustomMsg.TYPE_F64)) {
            x = vx.asFloat();
            y = vy.asFloat();
            z = vz.asFloat();
            CustomMsg.Val vo = msg.val(5);
            if (vo != null && (vo.type == CustomMsg.TYPE_F32 || vo.type == CustomMsg.TYPE_F64)) {
                orient = vo.asFloat();
            }
        }
        return new CastCenter(x, y, z, orient);
    }

    /** 形状过滤（圆/扇/矩形 + 高度差），与参考 exe applyShapedSkillDamage 同式。 */
    private List<NpcInstance> areaTargets(SceneState st, CastCenter center, SkillCatalog.Definition def) {
        if (st.world == null || def.areaRadius() <= 0) {
            return List.of();
        }
        List<NpcInstance> candidates;
        synchronized (st.world) {
            candidates = st.world.snapshotNear(center.x(), center.z(), def.areaRadius(),
                    AREA_TARGET_CAP, n -> !n.dead && n.hp > 0);
        }
        List<NpcInstance> hits = new ArrayList<>(candidates.size());
        float radiusSq = def.areaRadius() * def.areaRadius();
        for (NpcInstance n : candidates) {
            float dx = n.x - center.x();
            float dz = n.z - center.z();
            float dy = Math.abs(n.y - center.y());
            if (def.areaHeight() > 0 && dy > def.areaHeight()) {
                continue;
            }
            if (def.areaWidth() > 0) {
                double facingX = Math.cos(center.orient());
                double facingZ = Math.sin(center.orient());
                double forward = dx * facingX + dz * facingZ;
                double side = -dx * facingZ + dz * facingX;
                if (forward < 0 || forward > def.areaRadius()
                        || Math.abs(side) > def.areaWidth() / 2.0) {
                    continue;
                }
            } else if (dx * dx + dz * dz > radiusSq) {
                continue;
            } else if (def.sectorAngle() > 0) {
                double targetAngle = Math.atan2(dz, dx);
                double delta = Math.atan2(
                        Math.sin(targetAngle - center.orient()),
                        Math.cos(targetAngle - center.orient()));
                if (Math.abs(delta) > def.sectorAngle() * Math.PI / 360.0) {
                    continue;
                }
            }
            hits.add(n);
        }
        hits.sort(Comparator.comparingInt(n -> n.objectId));
        return hits;
    }

    /** 单目标结算结果：实际伤害 / 是否命中 / 击杀掉落（碎银 = MaxHP/100 夹 [1,99]）。 */
    private record HitResult(int damage, boolean landed, int loot) {
    }

    /**
     * 单目标伤害结算：CombatFormulas 掷未命中/暴击（技能倍率 = 编译伤害/100），
     * 命中掉血 + 0x10 血条批，死亡 0x0E 移除 + 重生排期 + AI/九阴志事件。
     */
    private HitResult settleSkillHit(FxSession fx, LoginSession session, SceneState st,
                                     NpcInstance npc, SkillCatalog.Definition def, long now) {
        double ratio = def.baseDamage() > 0 ? def.baseDamage() / 100.0 : 1.0;
        CombatFormulas.DamageResult hit = CombatFormulas.calc(session.actor.combatStats(),
                CombatStats.defender(npc.defense, npc.evasion),
                ratio, ThreadLocalRandom.current(), false);
        if (hit.miss()) {
            log.info("skill {} -> npc {} MISS", def.id(), npc.configId);
            if (ai != null) {
                ai.onNpcDamaged(st.world, npc, fx, now);
            } else {
                npc.lastCombatMs = now;
            }
            return new HitResult(0, false, 0);
        }
        int damage = hit.damage();
        npc.hp = Math.max(0, npc.hp - damage);
        log.info("skill {} -> npc {} damage={} crit={} reductionPct={}",
                def.id(), npc.configId, damage, hit.crit(),
                String.format(Locale.ROOT, "%.1f", hit.reductionPct() * 100));
        if (ai != null) {
            ai.onNpcDamaged(st.world, npc, fx, now);
        } else {
            npc.lastCombatMs = now;
        }
        S2CPackets.Batch npcBatch = S2CPackets.batch(npc.objectId, npc.ownerId);
        npcBatch.i32(PropertyId.HP, npc.hp)
                .i32(PropertyId.HP_RATIO, npc.maxHp > 0 ? (int) ((long) npc.hp * 100 / npc.maxHp) : 0);
        int loot = 0;
        if (npc.hp == 0) {
            npc.dead = true;
            npc.respawnAtMs = WorldBossManager.TAG.equals(npc.tag)
                    ? Long.MAX_VALUE : now + respawnDelay(npc);
            npcBatch.u8(PropertyId.DEAD, 1);
            loot = Math.max(1, Math.min(99, npc.maxHp / 100));
        }
        fx.send(npcBatch.build());
        if (npc.dead) {
            fx.send(S2CPackets.removeObject(npc.objectId, npc.ownerId));
            st.sentIds.remove(npc.objectId);
            if (ai != null) {
                ai.onNpcDeath(st.world, npc, 0);
            }
            ninjiuzhi.onKill();
            log.info("npc {} ({}) defeated, respawn in {}ms", npc.objectId, npc.configId, NPC_RESPAWN_MS);
        }
        return new HitResult(damage, true, loot);
    }

    /** 211 起手互斥窗口（参考实现 500ms）。 */
    private static final long SKILL_LOCK_MS = 500;
    /** 区域技能单次结算目标上限（参考无上限，此处防摆位异常爆帧）。 */
    private static final int AREA_TARGET_CAP = 64;

    // ---------- 轻功 ----------

    /** 216 USE_QINGGONG：(216, id, begin/end)。仅 begin=1 计费；buf_qg_drift/dst 映射原生 Buff 槽。 */
    private void handleQingGong(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        if (msg.size() != 3 || msg.val(1).type != CustomMsg.TYPE_STRING
                || msg.val(1).str == null || msg.val(1).str.isEmpty()
                || !msg.val(2).asExactInt()
                || (msg.val(2).intOrZero() != 0 && msg.val(2).intOrZero() != 1)) {
            log.info("reject malformed USE_QINGGONG argc={}", msg.size());
            return;
        }
        QinggongCatalog.Definition def = qinggongCatalog.lookup(msg.val(1).str);
        if (def == null) {
            log.info("reject unknown qinggong id={}", msg.val(1).str);
            return;
        }
        long now = System.currentTimeMillis();
        QinggongCatalog.BuffSpec spec = QinggongCatalog.buffSpec(def);
        if (msg.val(2).intOrZero() == 0) {
            if (spec != null && spec.clearOnEnd()
                    && session.actor.clearBuff(spec.slot(), spec.staticData())) {
                fx.send(session.actor.vitalUpdate());
            }
            log.info("qinggong end id={}", def.id());
            return;
        }
        if (spec != null && spec.clearOnEnd()
                && session.actor.hasActiveBuff(spec.slot(), spec.staticData(), now)) {
            log.info("ignore duplicate qinggong start id={}", def.id());
            return;
        }
        if (!session.actor.useQingGong(def.consume())) {
            log.info("reject qinggong id={} cost={} point={}", def.id(), def.consume(),
                    session.actor.qinggongPoint);
            return;
        }
        if (spec != null) {
            session.actor.activateBuff(spec.slot(), spec.staticData(), spec.durationMs(),
                    def.bufferId(), 1);
        }
        fx.send(session.actor.vitalUpdate());
        log.info("accepted qinggong id={} type={} cost={} point={}/{}", def.id(), def.type(),
                def.consume(), session.actor.qinggongPoint, session.actor.maxQinggongPoint);
    }

    /** 217 ACTIVE_QINGGONG：(217, id) → ActiveQGSkillRec 行（高阶轻功装备前置）。 */
    private void handleActiveQingGong(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        if (msg.size() != 2 || msg.val(1).type != CustomMsg.TYPE_STRING
                || msg.val(1).str == null || msg.val(1).str.isEmpty()) {
            log.info("reject malformed ACTIVE_QINGGONG argc={}", msg.size());
            return;
        }
        String id = msg.val(1).str;
        if (session.actor.activateQingGong(id)) {
            fx.send(BootstrapPackets.activeQinggongRow(id));
            log.info("activated qinggong id={} via ActiveQGSkillRec", id);
        } else {
            log.info("active qinggong already present id={}", id);
        }
    }

    /** 218 招架：subtype≠0 开启招架（耗耐力减伤，见 CombatFormulas.parryMultiplier），=0 收招。 */
    private void handleActiveParry(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null || msg.size() < 3) {
            return;
        }
        CustomMsg.Val subtype = msg.val(1);
        if (!subtype.asExactInt()) {
            return;
        }
        session.actor.parrying = subtype.intOrZero() != 0;
        log.info("active parry {} (subtype={} argument={} endurance={})",
                session.actor.parrying ? "on" : "off", subtype.intOrZero(), msg.val(2),
                session.actor.parryEndurance);
    }

    /** 0x1E msg=20 原生技能动作帧（AI 出招广播用；玩家 211 走 State 动作路由）。 */
    static byte[] nativeSkillAction(int casterOid, int casterOwner, String skillId,
                                    int targetOid, int targetOwner) {
        return CustomPackets.intMessage(20,
                new CustomPackets.ObjRef(casterOid, casterOwner),
                skillId,
                new CustomPackets.ObjRef(targetOid, targetOwner),
                0,
                0f);
    }

    // ---------- 每秒 tick（NineyinServer actor 调度器驱动） ----------

    /** actor 恢复 + NPC 重生 + 节流 vital 同步。 */
    public void tick(FxSession fx, LoginSession session) {
        SceneState st = fx.channel().attr(STATE).get();
        if (st == null || session.actor == null || !session.uiBootstrapped
                || st.awaitingReentryReady) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = session.actor.tick(now);
        respawnDeadNpcs(fx, st, session, now);
        if (st.world != null && ai != null) {
            ai.pump(st.world, now);
        }
        if (changed && now - st.lastVitalMs >= 1000) {
            st.lastVitalMs = now;
            fx.send(session.actor.vitalUpdate());
        }
    }

    private void respawnDeadNpcs(FxSession fx, SceneState st, LoginSession session, long now) {
        if (st.world == null) {
            return;
        }
        synchronized (st.world) {
            for (NpcInstance n : st.world.npcs) {
                if (!n.dead || now < n.respawnAtMs) {
                    continue;
                }
                n.dead = false;
                n.hp = n.maxHp;
                n.x = n.homeX;
                n.y = n.homeY;
                n.z = n.homeZ;
                float dx = n.x - session.x;
                float dz = n.z - session.z;
                if (dx * dx + dz * dz <= VIEW_RADIUS * VIEW_RADIUS) {
                    fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
                    fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
                    st.sentIds.add(n.objectId);
                }
            }
        }
    }

    // ---------- 巡逻广播 ----------

    private void broadcastPatrol(FxSession fx, SceneState st, LoginSession session) {
        long now = System.currentTimeMillis();
        // 到达确认 0x1F（更新的腿已开始则跳过，避免回拽）
        var it = st.pendingArrive.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now < e.getValue().atMs()) {
                continue;
            }
            SceneWorld.PendingLeg leg = st.world != null ? st.world.legOf(e.getKey()) : null;
            boolean skip = leg != null && leg.arriveAtMs > e.getValue().atMs();
            if (!skip) {
                PendingArrive a = e.getValue();
                fx.send(S2CPackets.serverLocation(e.getKey(), 1, a.x(), a.y(), a.z(), a.orient()));
            }
            it.remove();
        }
        if (!st.npcsSent || st.world == null || now - st.lastPatrolMs < 500) {
            return;
        }
        st.lastPatrolMs = now;
        st.world.tick(now);
        int moved = 0;
        synchronized (st.world) {
            for (NpcInstance n : st.world.npcs) {
                if (!st.sentIds.contains(n.objectId) || n.dead) {
                    continue;
                }
                float dx = n.x - session.x;
                float dz = n.z - session.z;
                if (dx * dx + dz * dz > PATROL_BROADCAST_RADIUS * PATROL_BROADCAST_RADIUS) {
                    continue;
                }
                if (n.route.size() < 2) {
                    continue;
                }
                SceneWorld.PendingLeg leg = st.world.legs.get(n.objectId);
                if (leg == null) {
                    continue;
                }
                Long sentAt = st.legSent.get(n.objectId);
                if (sentAt != null && sentAt == leg.startedMs) {
                    continue;
                }
                fx.send(S2CPackets.serverMoving(n.objectId, n.ownerId,
                        leg.x, leg.y, leg.z, leg.orient));
                st.legSent.put(n.objectId, leg.startedMs);
                long remain = Math.max(300, leg.arriveAtMs - now);
                st.pendingArrive.put(n.objectId,
                        new PendingArrive(now + remain, leg.x, leg.y, leg.z, leg.orient));
                if (++moved >= PATROL_BROADCAST_CAP) {
                    break;
                }
            }
        }
    }

    private static float readF32(byte[] b, int off) {
        return Float.intBitsToFloat(leU32(b, off));
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
