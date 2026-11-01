package com.nineyin.gm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GM 面板数据中枢：在线会话快照 + 服务器状态 + 命令队列（对应 C++ gm::Hub）。
 * 命令消费由游戏侧轮询 tryPop；未实现的动作记日志（Phase 对齐 C++）。
 */
public final class GmHub {

    /** 在线玩家快照。 */
    public static final class PlayerSnapshot {

        public long sessionId;
        public String remote = "";
        public String name = "";
        public String sceneConfig = "";
        public String sceneResource = "";
        public float x;
        public float y;
        public float z;
        public float orient;
        public int silver;
        public int sp;
        public String faction = "";
        public String lastAction = "";
        public Integer guanYin;
        public Integer gold;
        public long updatedAtMs;
    }

    public static final class ServerStatus {

        public volatile boolean running = true;
        public volatile String listenAddr = "";
        public volatile String serverRoot = "";
        public volatile int connectedClients;
        public volatile long startMs = System.currentTimeMillis();
        public volatile String phase = "Java port (transport+login+scene)";
    }

    private final ServerStatus status = new ServerStatus();
    private final Map<Long, PlayerSnapshot> sessions = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentLinkedQueue<GmCommand>> queues = new ConcurrentHashMap<>();

    public ServerStatus status() {
        return status;
    }

    public void attach(long id, PlayerSnapshot snapshot) {
        snapshot.updatedAtMs = System.currentTimeMillis();
        sessions.put(id, snapshot);
        status.connectedClients = sessions.size();
    }

    public void detach(long id) {
        sessions.remove(id);
        queues.remove(id);
        status.connectedClients = sessions.size();
    }

    public PlayerSnapshot get(long id) {
        return sessions.get(id);
    }

    public void update(long id, java.util.function.Consumer<PlayerSnapshot> updater) {
        PlayerSnapshot snap = sessions.get(id);
        if (snap != null) {
            synchronized (snap) {
                updater.accept(snap);
                snap.updatedAtMs = System.currentTimeMillis();
            }
        }
    }

    public List<PlayerSnapshot> snapshots() {
        return new ArrayList<>(sessions.values());
    }

    /** 入队；会话不在线也接受（控制台可在无人在线时演练，对齐 C++ 软接受语义）。 */
    public void enqueue(GmCommand cmd) {
        if (cmd.sessionId() > 0) {
            queues.computeIfAbsent(cmd.sessionId(), k -> new ConcurrentLinkedQueue<>()).add(cmd);
        }
    }

    /** 非阻塞取一条该会话的命令；无命令返回 null。 */
    public GmCommand tryPop(long sessionId) {
        ConcurrentLinkedQueue<GmCommand> queue = queues.get(sessionId);
        return queue != null ? queue.poll() : null;
    }

    public int onlineCount() {
        return sessions.size();
    }

    /** 最小登录模式开关（面板可切换；对登录流程的实际生效由游戏侧接入）。 */
    private final java.util.concurrent.atomic.AtomicBoolean minimalLoginEnabled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public boolean minimalLogin() {
        return minimalLoginEnabled.get();
    }

    public boolean toggleMinimalLogin() {
        boolean next = !minimalLoginEnabled.get();
        minimalLoginEnabled.set(next);
        return next;
    }
}
