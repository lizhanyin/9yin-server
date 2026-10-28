package com.swingfrog.summer.ecs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSchedulerTest {

    @Test
    void drivesTasksAtFixedRate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        List<Long> ticks = new CopyOnWriteArrayList<>();
        try (TickScheduler scheduler = new TickScheduler("test-tick", 20)) {
            scheduler.addTask((tick, now) -> {
                ticks.add(tick);
                latch.countDown();
            });
            scheduler.start();
            assertTrue(latch.await(5, TimeUnit.SECONDS), "3 ticks should fire within 5s");
            assertTrue(ticks.get(1) > ticks.get(0), "tickCount must increase");
        }
    }

    @Test
    void taskExceptionDoesNotStopTicking() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        try (TickScheduler scheduler = new TickScheduler("test-tick-err", 15)) {
            scheduler.addTask((tick, now) -> {
                throw new IllegalStateException("boom");
            });
            scheduler.addTask((tick, now) -> latch.countDown());
            scheduler.start();
            assertTrue(latch.await(5, TimeUnit.SECONDS), "healthy task must keep ticking");
        }
    }

    @Test
    void removedTaskStops() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        List<Long> ticks = new CopyOnWriteArrayList<>();
        try (TickScheduler scheduler = new TickScheduler("test-tick-rm", 15)) {
            TickScheduler.Handle[] holder = new TickScheduler.Handle[1];
            holder[0] = scheduler.addTask((tick, now) -> {
                ticks.add(tick);
                latch.countDown();
                if (tick >= 1) {
                    scheduler.removeTask(holder[0]);
                }
            });
            scheduler.start();
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            int sizeAfterRemove = ticks.size();
            Thread.sleep(80);
            assertEqualsAfterSleep(sizeAfterRemove, ticks);
        }
    }

    private static void assertEqualsAfterSleep(int expectedMax, List<Long> ticks) {
        // 移除后允许 0 次新增（宽限一次已入队的执行）
        assertTrue(ticks.size() <= expectedMax + 1,
                "removed task should stop ticking, got " + ticks.size());
    }
}
