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
import com.nineyin.server.world.npc.PatrolSchedule;
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
        final PatrolSchedule schedule;

        SceneEntity(int id, String description, int ownerId, WorldScene.Transform transform,
                    List<PropertyValues.Indexed> properties, byte[] payload,
                    String configId, String scriptClass, boolean combat,
                    PatrolSchedule schedule) {
            this.id = id;
            this.description = description;
            this.ownerId = ownerId;
            this.transform = transform;
            this.properties = properties;
            this.payload = payload;
            this.configId = configId;
            this.scriptClass = scriptClass;
            this.combat = combat;
            this.schedule = schedule;
        }
    }

    /** 在巡实体的步进链代（视口重进换新链，旧链回调按代自灭）。 */
    private final Map<Integer, Long> patrolChains = new HashMap<>();

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

    private final Connection connection;
    private final ScheduledExecutorService scheduler;
    /** 时刻源（默认系统时钟；测试注入可控时钟驱动时间表步进）。 */
    private final java.util.function.LongSupplier clock;
    private final WorldScene scene = new WorldScene();
    private final WorldViewport viewport = new WorldViewport();
    private final Map<Integer, SceneEntity> entities = new HashMap<>();
    private boolean closed;
    private long epoch;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private boolean armed;
    /** 首个活动包已物化：此后视口新进的巡逻 NPC 立即开链。 */
    private boolean materialized;
    /** 巡逻链代序号：视口重进换新链，旧链回调按代自灭。 */
    private long patrolChainSeq;

    public SceneLifecycle(Connection connection, ScheduledExecutorService scheduler) {
        this(connection, scheduler, System::currentTimeMillis);
    }

    /** 测试注入可控时钟（时间表步进确定性驱动）。 */
    public SceneLifecycle(Connection connection, ScheduledExecutorService scheduler,
                          java.util.function.LongSupplier clock) {
        this.connection = connection;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    /** Go begin：新场景世代（清实体/停定时器），不发帧。 */
    public synchronized void begin(String config, String resource) {
        if (closed) {
            return;
        }
        scene.begin(config, resource);
        entities.clear();
        patrolChains.clear();
        armed = false;
        materialized = false;
        epoch++;
        stopTimersLocked();
    }

    /**
     * Go registerNPC：预编码 0x0D 载荷并入世界实体（不上线）。
     * 巡逻 NPC 按共同时钟采样当前时刻位置（入参须为每次注册的独立克隆，
     * 如 NpcViewport.cloneSpawn——本方法会就地改写其坐标）。
     */
    public synchronized void registerNpc(int id, NpcSpawn npc) {
        if (id == 0 || id == 1) {
            throw new IllegalStateException("NPC scene identity " + id
                    + " collides with reserved player identity");
        }
        boolean combat = isCombatScriptClass(npc.resolved.scriptClass);
        if (!combat && npc.schedule != null) {
            PatrolSchedule.Position position = npc.schedule.positionAt(clock.getAsLong());
            npc.x = position.x();
            npc.y = position.y();
            npc.z = position.z();
            npc.orient = position.orient();
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
        entities.put(id, new SceneEntity(id, "npc:" + npc.resolved.configId, 1, transform,
                ordered, payload, npc.resolved.configId, npc.resolved.scriptClass,
                combat, npc.schedule));
        // 物化后视口新进（重进）的巡逻 NPC 立即开链（从时间表当前处继续）。
        if (materialized && npc.schedule != null && !combat) {
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
        patrolChains.remove(id);
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
                    meta.combat, meta.schedule));
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
     * 启动路线巡逻：全部已注册、带巡逻时间表、非战斗的实体按 ID 序开链。
     * 首步 5s 后（避开 0x1F 重放窗口 250..5000ms，防重放帧把走动 NPC 拽回出生点）
     * + 下标×300ms 错峰。步进按时间表绝对时刻推进（跨连接一致，重进续走）。
     */
    public synchronized void startPatrol() {
        if (closed) {
            return;
        }
        List<Integer> ids = new ArrayList<>(entities.size());
        for (SceneEntity meta : entities.values()) {
            if (!meta.combat && meta.schedule != null && !patrolChains.containsKey(meta.id)) {
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
        if (meta == null || meta.combat || meta.schedule == null || patrolChains.containsKey(id)) {
            return;
        }
        long chain = ++patrolChainSeq;
        patrolChains.put(id, chain);
        long epochSnapshot = epoch;
        scheduleLocked(PATROL_FIRST_STEP_DELAY_MILLIS
                        + (long) (stagger % PATROL_STAGGER_SPREAD) * PATROL_STAGGER_MILLIS,
                () -> patrolStep(id, chain, epochSnapshot));
    }

    /**
     * 巡逻步进（时间表驱动）：行进段发 0x20 目标路点（剩余时长 = 时间表绝对
     * 时刻推算，与客户端 0x0D 同值速度一致），到点/停留段只排程不发帧；
     * 停留时长取 mappath 停留列（0~120s）。
     */
    private void patrolStep(int id, long chain, long epochSnapshot) {
        long delayMillis;
        WorldScene.Transform destination = null;
        long travelMillis = 0;
        synchronized (this) {
            Long live = patrolChains.get(id);
            SceneEntity meta = entities.get(id);
            if (live != null && live != chain) {
                return; // 旧链回调：新链已接管，勿动
            }
            if (closed || epoch != epochSnapshot || live == null || meta == null) {
                patrolChains.remove(id); // 实体已摘/换代：本链自灭
                return;
            }
            Object action = meta.schedule.actionAt(clock.getAsLong());
            if (action instanceof PatrolSchedule.Moving moving) {
                destination = new WorldScene.Transform(moving.destX(), moving.destY(),
                        moving.destZ(), moving.destOrient());
                travelMillis = Math.max(50, moving.remainingMs());
                delayMillis = travelMillis;
            } else {
                PatrolSchedule.Dwelling dwelling = (PatrolSchedule.Dwelling) action;
                delayMillis = Math.max(50, dwelling.waitMs());
            }
        }
        if (destination != null) {
            moveNpc(id, destination, travelMillis);
        }
        scheduleLocked(delayMillis, () -> patrolStep(id, chain, epochSnapshot));
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
        patrolChains.clear();
        stopTimersLocked();
    }

}
