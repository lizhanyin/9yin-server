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
import com.nineyin.server.protocol.NpcMessages;
import com.nineyin.server.protocol.NpcSchema;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.PropertyValues.Value;
import com.nineyin.server.transport.Connection;

/**
 * 单连接场景生命周期：世界实体 + 视口物化 + 巡逻模拟，逐行移植 Go
 * scene_lifecycle.go 的 begin/registerNPC/clientReady/clientActivity/
 * reconcileViewport/moveNPC/startPatrol/重放族。菜单/商店/战斗/传送门不在本期。
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
        float patrolSign;

        SceneEntity(int id, String description, int ownerId, WorldScene.Transform transform,
                    List<PropertyValues.Indexed> properties, byte[] payload,
                    String configId, String scriptClass, boolean combat) {
            this.id = id;
            this.description = description;
            this.ownerId = ownerId;
            this.transform = transform;
            this.properties = properties;
            this.payload = payload;
            this.configId = configId;
            this.scriptClass = scriptClass;
            this.combat = combat;
        }
    }

    private record LocationReplay(int id, byte[] payload) {
    }

    /** Go actor2LocationReplayDelays。 */
    private static final long[] LOCATION_REPLAY_DELAYS_MILLIS = {250, 750, 1500, 3000, 5000};

    private final Connection connection;
    private final ScheduledExecutorService scheduler;
    private final WorldScene scene = new WorldScene();
    private final WorldViewport viewport = new WorldViewport();
    private final Map<Integer, SceneEntity> entities = new HashMap<>();
    private boolean closed;
    private long epoch;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private boolean armed;
    private boolean patrolStarted;

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
        armed = false;
        patrolStarted = false;
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
        entities.put(id, new SceneEntity(id, "npc:" + npc.resolved.configId, 1, transform,
                ordered, payload, npc.resolved.configId, npc.resolved.scriptClass,
                isCombatScriptClass(npc.resolved.scriptClass)));
    }

    /** Go npcInteractionFor 的战斗判定（巡逻排除敌对 actor）。 */
    static boolean isCombatScriptClass(String scriptClass) {
        String normalized = scriptClass == null ? "" : scriptClass.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("attacknpc") || normalized.equals("bossnpc");
    }

    /** Go unregister：摘除世界实体（同代时 reconcile 发 0x0E）。 */
    public synchronized void unregister(int id) {
        scene.remove(id);
        entities.remove(id);
    }

    /** Go clientReady：armed 置位，不发帧（ready 早于 RegisterCallBack 的窗口）。 */
    public synchronized void clientReady() {
        if (closed) {
            return;
        }
        armed = true;
    }

    /** Go clientActivity：armed 后首个活动包物化视口（一次）。 */
    public synchronized void clientActivity() {
        if (closed || !armed) {
            return;
        }
        reconcileViewportLocked();
        armed = false;
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
            SceneEntity updated = new SceneEntity(meta.id, meta.description, meta.ownerId,
                    destination, meta.properties, meta.payload, meta.configId, meta.scriptClass, meta.combat);
            updated.patrolSign = meta.patrolSign;
            entities.put(id, updated);
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

    /** Go startPatrol：非战斗实体按 ID 序取前 count 个，5s 周期 ±1.5 X 摆动。 */
    public synchronized void startPatrol(int count) {
        if (count <= 0 || closed || patrolStarted) {
            return;
        }
        patrolStarted = true;
        List<Integer> ids = new ArrayList<>(entities.size());
        for (SceneEntity meta : entities.values()) {
            if (!meta.combat) {
                ids.add(meta.id);
            }
        }
        ids.sort(Integer::compareTo);
        if (count > ids.size()) {
            count = ids.size();
        }
        List<Integer> selected = ids.subList(0, count);
        long epochSnapshot = epoch;
        for (int index = 0; index < selected.size(); index++) {
            int id = selected.get(index);
            long delay = 3000 + (long) index * 700;
            scheduleLocked(delay, () -> patrolStep(id, epochSnapshot));
        }
    }

    private void patrolStep(int id, long epochSnapshot) {
        WorldScene.Transform destination;
        synchronized (this) {
            if (closed || epoch != epochSnapshot) {
                return;
            }
            SceneEntity meta = entities.get(id);
            if (meta == null) {
                return;
            }
            float sign = meta.patrolSign == 0 ? 1 : meta.patrolSign;
            destination = new WorldScene.Transform(
                    meta.transform.x() + sign * 1.5f, meta.transform.y(),
                    meta.transform.z(), meta.transform.orient());
            SceneEntity updated = new SceneEntity(meta.id, meta.description, meta.ownerId,
                    destination, meta.properties, meta.payload, meta.configId, meta.scriptClass, meta.combat);
            updated.patrolSign = -sign;
            entities.put(id, updated);
        }
        moveNpc(id, destination, 1500);
        scheduleLocked(5000, () -> patrolStep(id, epochSnapshot));
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
        stopTimersLocked();
    }

}
