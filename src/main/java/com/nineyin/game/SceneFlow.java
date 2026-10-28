package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.NpcPackets;
import com.nineyin.protocol.PacketWriter;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxC2SHandler;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxSession;
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

/**
 * 场景同步与自定义消息总线（0x0A / 0x18）：
 * - 67 字节移动包：更新位置、取消打坐、2 秒节流落库、>1m 触发 NPC 增量补发（上限 50）
 * - 自定义 msg：240 打坐、200/201 快捷键、211 技能动作（v1）、操作模式
 * - 巡逻广播（任意 0x0A 驱动，500ms 节流）：到达确认 0x1F、新腿 0x20（250m 内，上限 32）
 */
public final class SceneFlow {

    public static final AttributeKey<SceneState> STATE =
            AttributeKey.valueOf("nineyin.scene.state");

    public static final int OP_ACTIVITY = 0x0A;
    public static final int OP_PING = 0x18;

    private static final Logger log = LoggerFactory.getLogger(SceneFlow.class);

    private static final float VIEW_RADIUS = 450f;
    private static final int INITIAL_NPC_CAP = 200;
    private static final int STREAM_NPC_CAP = 50;
    private static final float PATROL_BROADCAST_RADIUS = 250f;
    private static final int PATROL_BROADCAST_CAP = 32;

    private final SceneManager sceneManager;
    private final RoleStore roleStore;

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
    }

    public record PendingArrive(long atMs, float x, float y, float z, float orient) {
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore) {
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
    }

    public void register(FxOpcodeRouter router) {
        router.register(OP_ACTIVITY, this::onActivity);
        router.register(OP_PING, this::onPing);
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
            if (++sent >= INITIAL_NPC_CAP) {
                break;
            }
        }
        st.lastNpcX = session.x;
        st.lastNpcZ = session.z;
        st.npcsSent = true;
        log.info("scene {} NPCs sent={} of {} loaded", session.spawnResource, sent,
                st.world.npcs.size());
    }

    /** 模板命中 + 非剧情 + 非活动怪，按距离排序（相近时功能 NPC 优先）。 */
    private List<NpcInstance> visibleNpcs(SceneWorld world, float px, float pz) {
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
                if (SceneManager.isActivityId(n.configId)) {
                    continue;
                }
                hits.add(n);
            }
        }
        List<NpcInstance> sorted = hits;
        sorted.sort(Comparator.comparingDouble((NpcInstance n) -> {
            float dx = n.x - px;
            float dz = n.z - pz;
            return (double) (dx * dx + dz * dz);
        }).thenComparingInt(SceneFlow::residentScore));
        return sorted;
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
            if (session.logicState == 102) {
                session.logicState = 0;
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

    private void dispatchCustom(FxSession fx, LoginSession session, CustomMsg msg) {
        int mid = msg.msgId();
        switch (mid) {
            case 240 -> handleSit(fx, session, msg);
            case 200 -> handleShortcutSet(fx, session, msg);
            case 201 -> handleShortcutRemove(fx, session, msg);
            case 211 -> handleSkill(fx, session, msg);
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

    /** 打坐：cmd 1=坐下(102) 0/2=起身(0)。 */
    private void handleSit(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val cmd = msg.val(1);
        if (cmd == null || cmd.type != CustomMsg.TYPE_I32) {
            return;
        }
        int next = switch (cmd.asInt()) {
            case 1 -> 102;
            case 0, 2 -> 0;
            default -> session.logicState;
        };
        if (next == session.logicState) {
            return;
        }
        session.logicState = next;
        fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, next));
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, next == 102 ? "sitdown" : "stand"));
        log.info("sitcross LogicState -> {} (cmd={})", next, cmd.asInt());
    }

    /** 200 设快捷键：idx, kind(str 或 int→skill), id；同 index 替换（先 0x12 删旧行）。 */
    private void handleShortcutSet(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.size() < 4) {
            return;
        }
        int idx = msg.val(1).type == CustomMsg.TYPE_I32 ? msg.val(1).asInt() : 0;
        String kind = msg.val(2).str != null ? msg.val(2).str : "skill";
        if (kind.isEmpty() && msg.val(2).type == CustomMsg.TYPE_I32) {
            kind = "skill";
        }
        String id = msg.val(3).str != null ? msg.val(3).str : "";
        int oldRow = -1;
        for (int i = 0; i < session.shortcuts.size(); i++) {
            if (session.shortcuts.get(i).index() == idx) {
                oldRow = i;
                break;
            }
        }
        if (oldRow >= 0) {
            fx.send(S2CPackets.recordDelRow(4, oldRow));
            session.shortcuts.remove(oldRow);
        }
        LoginSession.Shortcut sc = new LoginSession.Shortcut(idx, kind, id);
        session.shortcuts.add(sc);
        fx.send(S2CPackets.shortcutAdd(sc.index(), sc.kind(), sc.id()));
        log.info("set shortcut idx={} kind={} id={}", idx, kind, id);
    }

    /** 201 删快捷键行。 */
    private void handleShortcutRemove(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val v = msg.val(1);
        if (v == null || v.type != CustomMsg.TYPE_I32) {
            return;
        }
        int row = v.asInt();
        if (row >= 0 && row < session.shortcuts.size()) {
            fx.send(S2CPackets.recordDelRow(4, row));
            session.shortcuts.remove(row);
            log.info("remove shortcut row={}", row);
        }
    }

    /** 211 技能（v1）：0x1E msg20 原生动作 + State 动作名 + 选中目标 HP 飘字桩。 */
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
        log.info("use skill id={} action={}", skillId, action);
    }

    /** 0x1E msg=20 原生技能动作帧。 */
    static byte[] nativeSkillAction(int casterOid, int casterOwner, String skillId,
                                    int targetOid, int targetOwner) {
        PacketWriter w = new PacketWriter()
                .u8(0x1E)
                .u8(6)      // 参数个数
                .u8(0);
        cvI32(w, 20);
        cvObj(w, casterOid, casterOwner);
        cvStr(w, skillId);
        cvObj(w, targetOid, targetOwner);
        cvI32(w, 0);
        cvF32(w, 0f);
        return w.toByteArray();
    }

    private static void cvI32(PacketWriter w, int v) {
        w.u8(2).i32(v);
    }

    private static void cvF32(PacketWriter w, float v) {
        w.u8(4).f32(v);
    }

    private static void cvObj(PacketWriter w, int oid, int owner) {
        w.u8(8).u32(oid).u32(owner);
    }

    private static void cvStr(PacketWriter w, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        w.u8(6).u32(b.length + 1).raw(b).u8(0);
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
                if (!st.sentIds.contains(n.objectId)) {
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
