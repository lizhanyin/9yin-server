package com.swingfrog.summer.test.concurrent;

import org.junit.jupiter.api.Test;

import com.swingfrog.summer.concurrent.VirtualThreadMgr;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架级虚拟线程执行器行为验证。
 * 注意：VirtualThreadMgr 是单例，测试中不调用 shutdown，避免影响其他用例。
 */
class VirtualThreadMgrTest {

    @Test
    void executesTaskOnNamedVirtualThread() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        VirtualThreadMgr.get().execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("SummerVirtual-"),
                "thread name should start with SummerVirtual- but was " + threadName.get());
    }

    @Test
    void blockingTaskCompletes() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        VirtualThreadMgr.get().execute(() -> {
            try {
                Thread.sleep(200); // 阻塞点应让出载体线程而非占用
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.countDown();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
    }

    @Test
    void tasksRunConcurrently() throws Exception {
        // 两个任务各自先放行对方再等待对方：若被串行化在单线程上必超时，虚拟线程并发则立即完成
        CountDownLatch firstReleased = new CountDownLatch(1);
        CountDownLatch secondReleased = new CountDownLatch(1);
        CountDownLatch bothDone = new CountDownLatch(2);
        VirtualThreadMgr.get().execute(() -> {
            firstReleased.countDown();
            try {
                assertTrue(secondReleased.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            bothDone.countDown();
        });
        VirtualThreadMgr.get().execute(() -> {
            secondReleased.countDown();
            try {
                assertTrue(firstReleased.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            bothDone.countDown();
        });
        assertTrue(bothDone.await(3, TimeUnit.SECONDS));
    }
}
