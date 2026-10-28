package com.swingfrog.summer.ecs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景帧循环：固定频率驱动 tick 任务，对应 C++ SharedSceneWorld 的滴答模型
 * （巡逻推进、AOI 广播等）。每个场景绑定一个实例、一个专属线程；
 * 单个任务抛异常只记日志，不影响后续 tick 与其他任务。
 */
public final class TickScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TickScheduler.class);

    /** tick 任务：tickCount 从 0 递增，nowMs 为调度线程时钟。 */
    public interface TickTask {

        void onTick(long tickCount, long nowMs);
    }

    /** 任务句柄，用于移除。 */
    public static final class Handle {

        private final TickTask task;
        private volatile boolean removed;

        private Handle(TickTask task) {
            this.task = task;
        }

        public boolean isRemoved() {
            return removed;
        }
    }

    private final long periodMs;
    private final List<Handle> tasks = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCount = new AtomicLong();
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private volatile boolean running;

    public TickScheduler(String name, long periodMs) {
        if (periodMs <= 0) {
            throw new IllegalArgumentException("periodMs must be positive");
        }
        this.periodMs = periodMs;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name + "-tick");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        future = executor.scheduleAtFixedRate(this::runOnce, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public Handle addTask(TickTask task) {
        Handle handle = new Handle(task);
        tasks.add(handle);
        return handle;
    }

    public void removeTask(Handle handle) {
        handle.removed = true;
        tasks.remove(handle);
    }

    public long periodMs() {
        return periodMs;
    }

    public boolean isRunning() {
        return running;
    }

    private void runOnce() {
        long tick = tickCount.getAndIncrement();
        long now = System.currentTimeMillis();
        for (Handle h : tasks) {
            if (h.removed) {
                continue;
            }
            try {
                h.task.onTick(tick, now);
            } catch (Throwable t) {
                log.error("tick task error, tick={}", tick, t);
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (future != null) {
            future.cancel(false);
        }
        executor.shutdownNow();
    }
}
