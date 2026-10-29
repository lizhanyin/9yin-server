package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.BootstrapPackets;
import com.nineyin.protocol.CustomPackets;
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
 *   240 打坐、200/201 快捷键、211 技能（含选中 NPC 轻量战斗）、216/217 轻功、218 招架、
 *   223/5223 操作模式、64/70/71 商店（打开流程见 SelectFlow）
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
        // 轻量战斗
        public long lastSkillMs;
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
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
        this.qinggongCatalog = qinggongCatalog;
        this.ninjiuzhi = ninjiuzhi != null ? ninjiuzhi : NinJiuZhi.alwaysOpen();
        this.switcher = new SceneSwitcher(sceneManager, roleStore, this, this.ninjiuzhi);
        this.ai = ai;
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
            // 商店购买流程（64 打开 / 70 购买 / 71 关闭）：S2C 响应格式待抓包钉死，先对齐 C++ 记日志
            case 64 -> log.info("shop msg=64 open argc={} (response pending capture)", msg.size());
            case 70 -> log.info("shop msg=70 buy argc={} (response pending capture)", msg.size());
            case 71 -> log.info("shop msg=71 close argc={}", msg.size());
            default -> {
            }
        }
    }

    /** 打坐：cmd 1=坐下(102) 0/2=起身(0)；权威状态在 actor，并按 tick 恢复血蓝。 */
    private void handleSit(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        CustomMsg.Val cmd = msg.val(1);
        if (cmd == null || !cmd.asExactInt()) {
            return;
        }
        int next = switch (cmd.intOrZero()) {
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
        log.info("sitcross LogicState -> {} (cmd={})", next, cmd.intOrZero());
    }

    /** 200 设快捷键：idx, kind(str 或 int→skill), id；同 index 替换（先 0x12 删旧行）。 */
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

    /** 211 技能：原生动作 + State 动作名 + 选中怪轻量战斗（掉血/死亡/重生）。 */
    private void handleSkill(FxSession fx, LoginSession session, CustomMsg msg) {
        String skillId = "";
        for (int i = 1; i < msg.size(); i++) {
            CustomMsg.Val v = msg.val(i);
            if (v.type == CustomMsg.TYPE_STRING && v.str != null && !v.str.isEmpty()) {
                skillId = v.str;
                break;
            }
        }
        fx.send(nativeSkillAction(1, 1, skillId, 1, 1));
        String action = "attack";
        String low = skillId.toLowerCase(Locale.ROOT);
        for (int i = 1; i <= 7; i++) {
            if (low.contains("hsqs0" + i)) {
                action = "hsqs_0" + i;
                break;
            }
        }
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, action));
        SceneState st = stateOf(fx);
        applySkillDamage(fx, session, st, skillId);
        log.info("use skill id={} action={}", skillId, action);
    }

    /** 轻量战斗：选中单位按 CombatFormulas 结算（攻防减免/破防/暴击）；死亡 0x0E 移除 + 15s 重生（tick 内补发）。 */
    private void applySkillDamage(FxSession fx, LoginSession session, SceneState st, String skillId) {
        if (session.actor == null || st.world == null || st.selectedOid == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - st.lastSkillMs < 700) {
            return; // 动作节流
        }
        NpcInstance npc;
        synchronized (st.world) {
            npc = st.world.npcById(st.selectedOid);
        }
        if (npc == null || npc.dead) {
            return;
        }
        st.lastSkillMs = now;
        // 技能状态元组（CurSkill* + LogicState=1），5s 后清除
        session.actor.curSkillId = skillId;
        session.actor.curSkillTargetOid = npc.objectId;
        session.actor.curSkillTargetOwner = npc.ownerId;
        session.actor.logicState = PlayerActor.LOGIC_STATE_FIGHTING;
        String expected = skillId;
        GameScheduler.schedule(5000, () -> {
            PlayerActor actor = session.actor;
            if (actor == null || !expected.equals(actor.curSkillId)) {
                return;
            }
            actor.curSkillId = "";
            actor.curSkillTargetOid = 0;
            actor.curSkillTargetOwner = 0;
            if (actor.logicState == PlayerActor.LOGIC_STATE_FIGHTING) {
                actor.logicState = PlayerActor.LOGIC_STATE_NORMAL;
            }
            fx.send(actor.vitalUpdate());
        });
        fx.send(session.actor.vitalUpdate());

        CombatFormulas.DamageResult hit = CombatFormulas.calc(session.actor.combatStats(),
                CombatStats.defender(npc.defense, npc.evasion),
                CombatFormulas.skillRatio(skillId), ThreadLocalRandom.current(), false);
        if (hit.miss()) {
            // 未命中：不掉血，但攻击意图仍拉仇恨/进战斗窗口
            log.info("skill {} -> npc {} MISS", skillId, npc.configId);
            if (ai != null) {
                ai.onNpcDamaged(st.world, npc, fx, now);
            } else {
                npc.lastCombatMs = now;
            }
            return;
        }
        int damage = hit.damage();
        npc.hp = Math.max(0, npc.hp - damage);
        log.info("skill {} -> npc {} damage={} crit={} reductionPct={}",
                skillId, npc.configId, damage, hit.crit(), String.format(Locale.ROOT, "%.1f", hit.reductionPct() * 100));
        if (ai != null) {
            ai.onNpcDamaged(st.world, npc, fx, now);
        } else {
            npc.lastCombatMs = now;
        }
        S2CPackets.Batch npcBatch = S2CPackets.batch(npc.objectId, npc.ownerId);
        npcBatch.i32(PropertyId.HP, npc.hp)
                .i32(PropertyId.HP_RATIO, npc.maxHp > 0 ? (int) ((long) npc.hp * 100 / npc.maxHp) : 0);
        if (npc.hp == 0) {
            npc.dead = true;
            // 世界 BOSS 不走固定摆位重生（WorldBossManager 负责回收与下轮时间档）
            npc.respawnAtMs = WorldBossManager.TAG.equals(npc.tag)
                    ? Long.MAX_VALUE : now + respawnDelay(npc);
            npcBatch.u8(PropertyId.DEAD, 1);
        }
        fx.send(npcBatch.build());
        if (npc.dead) {
            fx.send(S2CPackets.removeObject(npc.objectId, npc.ownerId));
            st.sentIds.remove(npc.objectId);
            if (ai != null) {
                // 玩家等级未建模：修罗等级分箱（Level 34-50/51-60）条件暂不满足
                ai.onNpcDeath(st.world, npc, 0);
            }
            // 九阴志事件点：击杀累计到阈值自动推进全服阶段
            ninjiuzhi.onKill();
            log.info("npc {} ({}) defeated, respawn in {}ms", npc.objectId, npc.configId, NPC_RESPAWN_MS);
        }
    }

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

    /** 0x1E msg=20 原生技能动作帧。 */
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
