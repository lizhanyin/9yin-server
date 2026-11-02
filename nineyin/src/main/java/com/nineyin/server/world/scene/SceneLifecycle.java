package com.nineyin.server.world.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.npc.PatrolRoute;
import com.nineyin.server.protocol.NpcMessages;
import com.nineyin.server.protocol.NpcSchema;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.PropertyValues.Value;
import com.nineyin.server.transport.Connection;

/**
 * 单连接场景生命周期：世界实体 + 视口物化 + 巡逻模拟，主体逐行移植 Go
 * scene_lifecycle.go（begin/registerNPC/clientReady/clientActivity/
 * reconcileViewport/moveNPC/重放族）；巡逻为 mappath 官方路线版
 * （Go 的 5s ±1.5 摆动占位已被路线步进取代）。菜单/商店/战斗/传送门不在本期。
 */
public final class SceneLifecycle {

    /** Go sceneEntity（物化所需子集）。 */
    static final class SceneEntity {
        final int id;
        final String description;
        final int ownerId;
        final WorldScene.Transform transform;
        final List<PropertyValues.Indexed> properties;
        final byte[] payload;
        final String configId;
        final String scriptClass;
        final boolean combat;
        final PatrolRoute route;
        final int routeStartIdx;

        SceneEntity(int id, String description, int ownerId, WorldScene.Transform transform,
                    List<PropertyValues.Indexed> properties, byte[] payload,
                    String configId, String scriptClass, boolean combat,
                    PatrolRoute route, int routeStartIdx) {
            this.id = id;
            this.description = description;
            this.ownerId = ownerId;
            this.transform = transform;
            this.properties = properties;
            this.payload = payload;
            this.configId = configId;
            this.scriptClass = scriptClass;
            this.combat = combat;
            this.route = route;
            this.routeStartIdx = routeStartIdx;
        }
    }

    /** 巡逻步进状态（路线当前下标、往返方向与链代）。 */
    private static final class PatrolState {
        int idx;
        int dir = 1;
        final long chain;

        PatrolState(int idx, long chain) {
            this.idx = idx;
            this.chain = chain;
        }
    }

    private record LocationReplay(int id, byte[] payload) {
    }

    /** Go actor2LocationReplayDelays。 */
    private static final long[] LOCATION_REPLAY_DELAYS_MILLIS = {250, 750, 1500, 3000, 5000};

    /** 巡逻首步延迟：避开 0x1F 重放窗口（末批 5000ms）。 */
    private static final long PATROL_FIRST_STEP_DELAY_MILLIS = 5000;
    /** 巡逻错峰：同批开链实体按下标 ×300ms 铺开。 */
    private static final long PATROL_STAGGER_MILLIS = 300;
    /** 错峰铺开宽度（超过后回绕，避免大场景尾部延迟过长）。 */
    private static final int PATROL_STAGGER_SPREAD = 16;
    /** 路点停留（毫秒）。 */
    private static final long PATROL_WAYPOINT_WAIT_MILLIS = 200;
    /** 单腿 XZ 上限（米）：更长的腿视为脏数据跳过。 */
    private static final float PATROL_MAX_LEG_METERS = 28f;
    /** 单腿高度差上限（米）。 */
    private static final float PATROL_MAX_LEG_DY_METERS = 6f;

    private final Connection connection;
    private final ScheduledExecutorService scheduler;
    private final WorldScene scene = new WorldScene();
    private final WorldViewport viewport = new WorldViewport();
    private final Map<Integer, SceneEntity> entities = new HashMap<>();
    /** 在巡实体的步进状态（id → idx/dir）；unregister/begin 清除。 */
    private final Map<Integer, PatrolState> patrolStates = new HashMap<>();
    private boolean closed;
    private long epoch;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private boolean armed;
    /** 首个活动包已物化：此后视口新进的巡逻 NPC 立即开链。 */
    private boolean materialized;
    /** 巡逻链代序号：视口重进换新链，旧链回调按代自灭。 */
    private long patrolChainSeq;

    public SceneLifecycle(Connection connection, ScheduledExecutorService scheduler) {
        this.connection = connection;
        this.scheduler = scheduler;
    }

    /** Go begin：新场景世代（清实体/停定时器），不发帧。 */
    public synchronized void begin(String config, String resource) {
        if (closed) {
            return;
        }
        scene.begin(config, resource);
        entities.clear();
        patrolStates.clear();
        armed = false;
        materialized = false;
        epoch++;
        stopTimersLocked();
    }

    /** Go registerNPC：预编码 0x0D 载荷并入世界实体（不上线）。 */
    public synchronized void registerNpc(int id, NpcSpawn npc) {
        if (id == 0 || id == 1) {
            throw new IllegalStateException("NPC scene identity " + id
                    + " collides with reserved player identity");
        }
        List<PropertyValues.Indexed> ordered = npc.resolved.orderedProperties(
                NpcSchema.visibleNpcModernV1());
        byte[] payload = NpcMessages.npcAddObject(id, npc);
        Map<String, Value> archive = new HashMap<>(ordered.size());
        for (PropertyValues.Indexed property : ordered) {
            archive.put(property.name(), property.value());
        }
        WorldScene.Transform transform = new WorldScene.Transform(npc.x, npc.y, npc.z, npc.orient);
        scene.add(id, 1, WorldScene.EntityKind.NPC, transform, archive);
        boolean combat = isCombatScriptClass(npc.resolved.scriptClass);
        entities.put(id, new SceneEntity(id, "npc:" + npc.resolved.configId, 1, transform,
                ordered, payload, npc.resolved.configId, npc.resolved.scriptClass,
                combat, npc.route, npc.routeStartIdx));
        // 物化后视口新进（重进）的巡逻 NPC 立即开链（吸附起点重新起步）。
        if (materialized && npc.route != null && !combat) {
            startPatrolFor(id, 0);
        }
    }

    /** Go npcInteractionFor 的战斗判定（巡逻排除敌对 actor）。 */
    static boolean isCombatScriptClass(String scriptClass) {
        String normalized = scriptClass == null ? "" : scriptClass.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("attacknpc") || normalized.equals("bossnpc");
    }

    /** 战斗期 NPC 快照（技能释放/伤害结算读取；properties 按名取值）。 */
    public record CombatNpcInfo(int id, int ownerId, boolean combat,
                                float x, float y, float z, float orient,
                                int maxHp, String model, String name) {

        public String modelOrFallback() {
            return model == null || model.isEmpty() ? "monv000000" : model;
        }
    }

    /** 单个战斗 NPC 信息（未物化/不存在 → null）。 */
    public synchronized CombatNpcInfo combatNpcInfo(int id) {
        SceneEntity entity = entities.get(id);
        if (entity == null) {
            return null;
        }
        return toCombatInfo(entity);
    }

    /** 全部 combat NPC（面积技能目标选择的快照遍历）。 */
    public synchronized java.util.List<CombatNpcInfo> combatNpcs() {
        java.util.List<CombatNpcInfo> result = new java.util.ArrayList<>(entities.size());
        for (SceneEntity entity : entities.values()) {
            if (entity.combat) {
                result.add(toCombatInfo(entity));
            }
        }
        result.sort(java.util.Comparator.comparingInt(CombatNpcInfo::id));
        return result;
    }

    private static CombatNpcInfo toCombatInfo(SceneEntity entity) {
        int maxHp = 1000;
        String model = "";
        String name = "";
        for (PropertyValues.Indexed property : entity.properties) {
            switch (property.name()) {
                case "MaxHP" -> maxHp = property.value().i32();
                case "Resource" -> model = property.value().text();
                case "Name" -> name = property.value().text();
                default -> {
                }
            }
        }
        if (maxHp <= 0) {
            maxHp = 1000;
        }
        if (model == null) {
            model = "";
        }
        if (name == null) {
            name = "";
        }
        return new CombatNpcInfo(entity.id, entity.ownerId, entity.combat,
                entity.transform.x(), entity.transform.y(), entity.transform.z(),
                entity.transform.orient(), Math.max(maxHp, 1), model, name);
    }

    /** Go unregister：摘除世界实体（同代时 reconcile 发 0x0E），巡逻步进随链自灭。 */
    public synchronized void unregister(int id) {
        scene.remove(id);
        entities.remove(id);
        patrolStates.remove(id);
    }

    /** Go clientReady：armed 置位，不发帧（ready 早于 RegisterCallBack 的窗口）。 */
    public synchronized void clientReady() {
        if (closed) {
            return;
        }
        armed = true;
    }

    /** Go clientActivity：armed 后首个活动包物化视口（一次）+ 启动路线巡逻。 */
    public synchronized void clientActivity() {
        if (closed || !armed) {
            return;
        }
        reconcileViewportLocked();
        armed = false;
        materialized = true;
        startPatrol();
    }

    public synchronized void reconcileViewport() {
        reconcileViewportLocked();
    }

    /** Go reconcileViewportLocked：更新走 0x10 紧凑坐标；新增按 ID 序 0x0D+0x1F；同代才发 0x0E。 */
    private void reconcileViewportLocked() {
        WorldViewport.Delta delta = viewport.plan(scene.snapshot());
        for (WorldScene.Entity update : delta.updates) {
            SceneEntity entity = entities.get(update.id);
            if (entity == null) {
                throw new IllegalStateException("missing property encoder for scene object " + update.id);
            }
            // 官方表无 Pos* 属性：移动实体的权威更新走 0x1F（与官服一致）。
            connection.writeFrame(NpcMessages.serverLocation(entity.id, entity.ownerId,
                    entity.transform.x(), entity.transform.y(),
                    entity.transform.z(), entity.transform.orient()));
        }
        if (delta.previousGeneration == delta.generation) {
            for (WorldScene.Entity remove : delta.removes) {
                connection.writeFrame(NpcMessages.sceneRemoveObject(remove.id, remove.ownerId));
            }
        }
        delta.adds.sort(Comparator.comparingInt(entity -> entity.id));
        List<LocationReplay> replays = new ArrayList<>(delta.adds.size());
        for (WorldScene.Entity addition : delta.adds) {
            SceneEntity entity = entities.get(addition.id);
            if (entity == null) {
                throw new IllegalStateException("missing payload encoder for scene object " + addition.id);
            }
            connection.writeFrame(entity.payload);
            if (entity.ownerId != 0) {
                byte[] location = NpcMessages.serverLocation(entity.id, entity.ownerId,
                        entity.transform.x(), entity.transform.y(),
                        entity.transform.z(), entity.transform.orient());
                connection.writeFrame(location);
                replays.add(new LocationReplay(entity.id, location));
            }
        }
        viewport.commit(delta);
        if (!replays.isEmpty()) {
            scheduleLocationReplaysLocked(replays);
        }
    }

    /** Go moveNPC：替换实体 + Posi* 属性 + 0x20 + 对账 + 到点 0x1F。 */
    public void moveNpc(int id, WorldScene.Transform destination, long arrivalMillis) {
        synchronized (this) {
            if (closed) {
                return;
            }
            SceneEntity meta = entities.get(id);
            if (meta == null) {
                throw new IllegalStateException("move unknown scene object " + id);
            }
            WorldScene.Entity current = scene.get(id);
            WorldScene.Entity moved = new WorldScene.Entity(current.id, current.ownerId,
                    current.kind, destination, current.properties, current.revision);
            moved.properties.put("PosiX", PropertyValues.Value.ofFloat32(destination.x()));
            moved.properties.put("PosiY", PropertyValues.Value.ofFloat32(destination.y()));
            moved.properties.put("PosiZ", PropertyValues.Value.ofFloat32(destination.z()));
            moved.properties.put("Orient", PropertyValues.Value.ofFloat32(destination.orient()));
            scene.replace(moved);
            entities.put(id, new SceneEntity(meta.id, meta.description, meta.ownerId,
                    destination, meta.properties, meta.payload, meta.configId, meta.scriptClass,
                    meta.combat, meta.route, meta.routeStartIdx));
            connection.writeFrame(NpcMessages.serverMoving(meta.id, meta.ownerId,
                    NpcMessages.motionFromDest(destination.x(), destination.y(),
                            destination.z(), destination.orient())));
            reconcileViewportLocked();
            if (arrivalMillis <= 0) {
                arrivalMillis = 1500;
            }
            long epochSnapshot = epoch;
            scheduleLocked(arrivalMillis, () -> {
                synchronized (this) {
                    if (closed || epoch != epochSnapshot) {
                        return;
                    }
                    SceneEntity arrived = entities.get(id);
                    if (arrived == null) {
                        return;
                    }
                    connection.writeFrame(NpcMessages.serverLocation(arrived.id, arrived.ownerId,
                            arrived.transform.x(), arrived.transform.y(),
                            arrived.transform.z(), arrived.transform.orient()));
                }
            });
        }
    }

    /**
     * 启动路线巡逻：全部已注册、带 mappath 路线、非战斗的实体按 ID 序开链。
     * 首步 5s 后（避开 0x1F 重放窗口 250..5000ms，防重放帧把走动 NPC 拽回出生点）
     * + 下标×300ms 错峰。
     */
    public synchronized void startPatrol() {
        if (closed) {
            return;
        }
        List<Integer> ids = new ArrayList<>(entities.size());
        for (SceneEntity meta : entities.values()) {
            if (!meta.combat && meta.route != null && !patrolStates.containsKey(meta.id)) {
                ids.add(meta.id);
            }
        }
        ids.sort(Integer::compareTo);
        for (int index = 0; index < ids.size(); index++) {
            startPatrolFor(ids.get(index), index);
        }
    }

    /** 单实体开链（物化后视口重进复用；stagger 为错峰序号）。 */
    private void startPatrolFor(int id, int stagger) {
        SceneEntity meta = entities.get(id);
        if (meta == null || meta.combat || meta.route == null || patrolStates.containsKey(id)) {
            return;
        }
        long chain = ++patrolChainSeq;
        patrolStates.put(id, new PatrolState(meta.routeStartIdx, chain));
        long epochSnapshot = epoch;
        scheduleLocked(PATROL_FIRST_STEP_DELAY_MILLIS
                        + (long) (stagger % PATROL_STAGGER_SPREAD) * PATROL_STAGGER_MILLIS,
                () -> patrolStep(id, chain, epochSnapshot));
    }

    /**
     * 巡逻步进：下一路点（端点弹回）；腿长 XZ&gt;28m、|dy|&gt;6m 或 &lt;0.15m 的点
     * 只推进不下发；速度取实体 WalkSpeed/RunSpeed 属性（客户端 0x0D 同值驱动动画，
     * 缺失回退 1.25/4.0），行程时间 = 距离/速度，到点后停 200ms 走下一腿。
     */
    private void patrolStep(int id, long chain, long epochSnapshot) {
        WorldScene.Transform destination;
        long legMillis;
        synchronized (this) {
            PatrolState state = patrolStates.get(id);
            SceneEntity meta = entities.get(id);
            if (state != null && state.chain != chain) {
                return; // 旧链回调：新链已接管状态，勿动
            }
            if (closed || epoch != epochSnapshot || state == null || meta == null) {
                patrolStates.remove(id); // 实体已摘/换代：本链自灭
                return;
            }
            PatrolRoute route = meta.route;
            Leg leg = nextLeg(route, state, meta.transform);
            if (leg == null) {
                patrolStates.remove(id); // 路线无可达点：站立
                return;
            }
            float speed = patrolSpeed(meta, leg.run());
            float travelMillis = leg.distanceXz() / speed * 1000f;
            legMillis = Math.max(600, Math.min(60_000, (long) travelMillis));
            destination = new WorldScene.Transform(leg.x(), leg.y(), leg.z(), leg.orient());
        }
        moveNpc(id, destination, legMillis);
        scheduleLocked(legMillis + PATROL_WAYPOINT_WAIT_MILLIS,
                () -> patrolStep(id, chain, epochSnapshot));
    }

    /** 单腿目标（含朝向与步速类型）；无可达点返回 null。 */
    private static Leg nextLeg(PatrolRoute route, PatrolState state, WorldScene.Transform from) {
        int dir = state.dir == 0 ? 1 : state.dir;
        for (int attempt = 0; attempt < route.size(); attempt++) {
            int next = state.idx + dir;
            if (next < 0 || next >= route.size()) {
                dir = -dir; // 端点弹回
                next = state.idx + dir;
                if (next < 0 || next >= route.size()) {
                    return null;
                }
            }
            PatrolRoute.Waypoint waypoint = route.at(next);
            state.idx = next;
            state.dir = dir;
            float dx = waypoint.x() - from.x();
            float dy = waypoint.y() - from.y();
            float dz = waypoint.z() - from.z();
            float distXz = (float) Math.sqrt(dx * dx + dz * dz);
            if (distXz > PATROL_MAX_LEG_METERS || Math.abs(dy) > PATROL_MAX_LEG_DY_METERS) {
                continue; // 脏数据/断链路点：跳过
            }
            if (distXz < 0.15f) {
                continue; // 重合点：推进但不下发
            }
            float orient = waypoint.hasFacing()
                    ? waypoint.orient() : (float) Math.atan2(dx, dz);
            return new Leg(waypoint.x(), waypoint.y(), waypoint.z(), orient,
                    distXz, waypoint.run());
        }
        return null;
    }

    /** 巡逻速度：run 腿 RunSpeed、walk 腿 WalkSpeed（与 0x0D 下发属性同值），钳 0.5..15。 */
    private static float patrolSpeed(SceneEntity meta, boolean run) {
        float fallback = run ? 4.0f : 1.25f;
        float speed = 0f;
        for (PropertyValues.Indexed property : meta.properties) {
            if (run ? "RunSpeed".equals(property.name()) : "WalkSpeed".equals(property.name())) {
                speed = property.value().f32();
                break;
            }
        }
        if (speed <= 0f || !Float.isFinite(speed)) {
            speed = fallback;
        }
        return Math.max(0.5f, Math.min(15f, speed));
    }

    /** 一步移动目标（服务端行程计算用）。 */
    private record Leg(float x, float y, float z, float orient, float distanceXz, boolean run) {
    }

    /** Go scheduleLocationReplaysLocked：+250/750/1500ms/3s/5s 重放 0x1F 批次。 */
    private synchronized void scheduleLocationReplaysLocked(List<LocationReplay> replays) {
        long epochSnapshot = epoch;
        for (long delay : LOCATION_REPLAY_DELAYS_MILLIS) {
            List<LocationReplay> batch = new ArrayList<>(replays.size());
            for (LocationReplay replay : replays) {
                batch.add(new LocationReplay(replay.id, replay.payload.clone()));
            }
            scheduleLocked(delay, () -> {
                synchronized (this) {
                    if (closed || epoch != epochSnapshot) {
                        return;
                    }
                    for (LocationReplay replay : batch) {
                        if (!entities.containsKey(replay.id)) {
                            continue;
                        }
                        connection.writeFrame(replay.payload);
                    }
                }
            });
        }
    }

    private void scheduleLocked(long delayMillis, Runnable task) {
        timers.add(scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS));
    }

    private void stopTimersLocked() {
        for (ScheduledFuture<?> timer : timers) {
            timer.cancel(false);
        }
        timers.clear();
    }

    /** Go close：停定时器并使在途回调失效。 */
    public synchronized void close() {
        closed = true;
        epoch++;
        patrolStates.clear();
        stopTimersLocked();
    }

}
