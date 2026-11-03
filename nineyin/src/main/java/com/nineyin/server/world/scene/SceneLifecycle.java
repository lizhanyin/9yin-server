package com.nineyin.server.world.scene;

import java.util.ArrayList;
import java.util.Collections;
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

    /** 在巡实体登记（id → 0；0x21 广播 ticker 遍历，unregister/begin 清除）。 */
    private final Map<Integer, Long> patrolChains = new HashMap<>();

    private record LocationReplay(int id, byte[] payload) {
    }

    /** Go actor2LocationReplayDelays。 */
    private static final long[] LOCATION_REPLAY_DELAYS_MILLIS = {250, 750, 1500, 3000, 5000};

    /** 巡逻广播首拍延迟：避开 0x1F 重放窗口（末批 5000ms）。 */
    private static final long PATROL_FIRST_BROADCAST_DELAY_MILLIS = 5000;
    /** 巡逻广播周期：官方真机 ServerAllDest（0x21）每秒一拍全量广播。 */
    private static final long PATROL_BROADCAST_PERIOD_MILLIS = 1000;
    /** 单帧 0x21 记录上限（超出分帧；官方抓包单帧最多 5 条，留余量）。 */
    private static final int PATROL_BROADCAST_BATCH = 8;

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
    /** 巡逻广播 ticker 在跑（begin/close 停表后允许重开）。 */
    private boolean patrolBroadcasting;

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
        patrolBroadcasting = false;
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
        // 物化后视口新进（重进）的巡逻 NPC 立即入巡（从时间表当前处继续）。
        if (materialized && npc.schedule != null && !combat) {
            patrolChains.putIfAbsent(id, 0L);
            ensurePatrolBroadcastLocked();
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

    /**
     * Go moveNPC：替换实体 + Posi* 属性 + 0x20 + 对账 + 到点 0x1F。
     * 注：0x20 ServerMoving 为 Go 参考服帧型，官方真机抓包从未出现（巡逻
     * 移动已改走 0x21 广播）；本方法保留给非巡逻显式移动，勿用于巡逻。
     */
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
     * 启动路线巡逻：登记全部已注册、带巡逻时间表、非战斗的实体，并启动
     * 1s 周期的 0x21 ServerAllDest 广播 ticker（首拍 5s 后，避开 0x1F 重放窗口
     * 250..5000ms，防重放帧把走动 NPC 拽回出生点）。步进按时间表绝对时刻
     * 采样（跨连接一致，重进续走）。0x20 ServerMoving 为 Go 参考服未验证
     * 帧型（官方真机抓包从未出现），巡逻移动一律走 0x21。
     */
    public synchronized void startPatrol() {
        if (closed) {
            return;
        }
        for (SceneEntity meta : entities.values()) {
            if (!meta.combat && meta.schedule != null) {
                patrolChains.putIfAbsent(meta.id, 0L);
            }
        }
        ensurePatrolBroadcastLocked();
    }

    /** 广播 ticker 不在跑则启动（幂等；begin/close 停表后重开）。 */
    private void ensurePatrolBroadcastLocked() {
        if (closed || patrolBroadcasting || patrolChains.isEmpty()) {
            return;
        }
        patrolBroadcasting = true;
        long epochSnapshot = epoch;
        scheduleLocked(PATROL_FIRST_BROADCAST_DELAY_MILLIS,
                () -> patrolBroadcastTick(epochSnapshot));
    }

    /**
     * 巡逻广播拍（时间表驱动）：对全部在巡实体按时刻采样目的地/速度/移动
     * 标志，按 ID 序组成 0x21 批量帧（超出单帧上限分帧）；行进中实体的服务端
     * 权威坐标同步为时间表插值位置（combat 快照与 0x0D 重进采样同源）。
     * 0x21 不动场景 revision——视口对账不因巡逻产生 0x1F 更新（与官方一致）。
     */
    private void patrolBroadcastTick(long epochSnapshot) {
        List<NpcMessages.AllDestEntry> batch = new ArrayList<>(patrolChains.size());
        synchronized (this) {
            if (closed || epoch != epochSnapshot) {
                patrolBroadcasting = false;
                return; // 换代：旧 ticker 自灭（新世代按需重启）
            }
            if (patrolChains.isEmpty()) {
                patrolBroadcasting = false;
                return;
            }
            long now = clock.getAsLong();
            List<Integer> ids = new ArrayList<>(patrolChains.keySet());
            Collections.sort(ids);
            for (Integer id : ids) {
                SceneEntity meta = entities.get(id);
                if (meta == null || meta.schedule == null) {
                    patrolChains.remove(id);
                    continue;
                }
                PatrolSchedule.Motion motion = meta.schedule.motionAt(now);
                batch.add(new NpcMessages.AllDestEntry(meta.id, meta.ownerId,
                        motion.destX(), motion.destY(), motion.destZ(),
                        motion.destOrient(), motion.speed(), motion.moving() ? 1 : 0));
                PatrolSchedule.Position position = meta.schedule.positionAt(now);
                entities.put(id, new SceneEntity(meta.id, meta.description, meta.ownerId,
                        new WorldScene.Transform(position.x(), position.y(),
                                position.z(), position.orient()),
                        meta.properties, meta.payload, meta.configId, meta.scriptClass,
                        meta.combat, meta.schedule));
            }
            if (patrolChains.isEmpty()) {
                patrolBroadcasting = false;
                return;
            }
            for (int from = 0; from < batch.size(); from += PATROL_BROADCAST_BATCH) {
                List<NpcMessages.AllDestEntry> slice = batch.subList(from,
                        Math.min(batch.size(), from + PATROL_BROADCAST_BATCH));
                byte[] frame = NpcMessages.serverAllDest(new ArrayList<>(slice));
                if (frame != null) {
                    connection.writeFrame(frame);
                }
            }
            scheduleLocked(PATROL_BROADCAST_PERIOD_MILLIS,
                    () -> patrolBroadcastTick(epochSnapshot));
        }
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
        patrolBroadcasting = false;
        stopTimersLocked();
    }

}
