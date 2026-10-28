package com.nineyin.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 游戏侧共享调度器：场景重进 750ms 延迟、技能状态清理、buff 到期等
 * 非 Netty 线程的延迟任务统一走这里，避免阻塞 IO 线程。
 */
public final class GameScheduler {

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "nineyin-game-scheduler");
                t.setDaemon(true);
                return t;
            });

    private GameScheduler() {
    }

    public static ScheduledFuture<?> schedule(long delayMs, Runnable task) {
        return EXECUTOR.schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }
}
