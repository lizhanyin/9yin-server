package com.nineyin.game.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.nineyin.world.scene.SceneLifecycle;
import com.nineyin.world.scene.WorldScene;
import org.junit.jupiter.api.Test;

import com.nineyin.world.npc.NpcCreator;
import com.nineyin.world.npc.NpcResolver;
import com.nineyin.world.npc.NpcSpawn;
import com.nineyin.protocol.PropertyValues.Value;
import com.nineyin.transport.Connection;
import com.nineyin.transport.FrameCodec;
import com.nineyin.transport.XorKey;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 场景生命周期行为测试（合成 NPC，不依赖资源）：物化顺序、世代语义、
 * 巡逻步进与 0x20/0x10/0x0E 帧，对齐 Go scene_lifecycle 的可观察行为。
 */
class SceneLifecycleTest {

    /** 记录任务不执行（与 e2e 相同语义），可手动推进。 */
    static final class ManualScheduler implements ScheduledExecutorService {
        final List<Runnable> tasks = new ArrayList<>();

        private static final java.util.concurrent.ScheduledFuture<?> NONE =
                new java.util.concurrent.ScheduledFuture<>() {
                    @Override
                    public long getDelay(TimeUnit unit) {
                        return 0;
                    }

                    @Override
                    public int compareTo(java.util.concurrent.Delayed other) {
                        return 0;
                    }

                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public Object get() {
                        return null;
                    }

                    @Override
                    public Object get(long timeout, TimeUnit unit) {
                        return null;
                    }
                };

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            return NONE;
        }

        @Override
        public <V> java.util.concurrent.ScheduledFuture<V> schedule(
                java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                               long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }

    private static NpcSpawn syntheticNpc(String configId, float x, float y, float z, float orient) {
        NpcCreator.Transform transform = new NpcCreator.Transform();
        transform.x = x;
        transform.y = y;
        transform.z = z;
        transform.ay = orient;
        NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                "test#" + configId, configId, "", transform);
        resolved.properties.put("Type", Value.ofByte(4));
        resolved.properties.put("ConfigID", Value.ofString(configId));
        resolved.properties.put("PosiX", Value.ofFloat32(x));
        resolved.properties.put("PosiY", Value.ofFloat32(y));
        resolved.properties.put("PosiZ", Value.ofFloat32(z));
        resolved.properties.put("Orient", Value.ofFloat32(orient));
        resolved.properties.put("Name", Value.ofWideString("测试" + configId));
        return NpcSpawn.fromInstance(resolved, transform);
    }

    private record ChannelWorld(EmbeddedChannel channel, ManualScheduler scheduler, SceneLifecycle world) {
        List<byte[]> drain() {
            List<byte[]> frames = new ArrayList<>();
            ByteBuf buffer;
            while ((buffer = channel.readOutbound()) != null) {
                byte[] wire = new byte[buffer.readableBytes()];
                buffer.readBytes(wire);
                buffer.release();
                frames.add(FrameCodec.decode(wire, XorKey.initial()));
            }
            return frames;
        }
    }

    private static ChannelWorld newWorld() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(new com.nineyin.transport.FrameEncoder(new Connection(null)));
        ManualScheduler scheduler = new ManualScheduler();
        SceneLifecycle world = new SceneLifecycle(new Connection(channel), scheduler);
        world.begin("ini\\scene\\test", "test");
        return new ChannelWorld(channel, scheduler, world);
    }

    @Test
    void materializeSortsObjectsByIdAndSendsLocation() {
        ChannelWorld env = newWorld();
        env.world().registerNpc(3, syntheticNpc("npc_c", 10, 0, 10, 0));
        env.world().registerNpc(2, syntheticNpc("npc_b", 20, 0, 20, 0));
        env.world().clientReady();
        // ready 前不物化（armed 屏障）。
        assertTrue(env.drain().isEmpty());
        env.world().clientActivity();
        List<byte[]> frames = env.drain();
        assertEquals(4, frames.size());
        assertEquals(0x0D, frames.get(0)[0] & 0xFF);
        assertEquals(2, u32(frames.get(0), 1));
        assertEquals(0x1F, frames.get(1)[0] & 0xFF);
        assertEquals(0x0D, frames.get(2)[0] & 0xFF);
        assertEquals(3, u32(frames.get(2), 1));
        assertEquals(0x1F, frames.get(3)[0] & 0xFF);
        // 5 个重放批次（250/750/1500ms/3s/5s）。
        assertEquals(5, env.scheduler().tasks.size());
        // 重复 clientActivity 不再物化。
        env.world().clientActivity();
        assertTrue(env.drain().isEmpty());
    }

    @Test
    void moveNpcSendsMovingThenTransformUpdate() {
        ChannelWorld env = newWorld();
        env.world().registerNpc(2, syntheticNpc("npc_b", 20, 0, 20, 0));
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        int replayTasks = env.scheduler().tasks.size(); // 物化重放批次（5）
        env.world().moveNpc(2, new WorldScene.Transform(21.5f, 0, 20, 0), 1500);
        List<byte[]> frames = env.drain();
        // 0x20 ServerMoving + 0x1F 权威坐标（官方表无 Pos* 属性，更新走 0x1F）。
        assertEquals(2, frames.size());
        assertEquals(0x20, frames.get(0)[0] & 0xFF);
        assertEquals(45, frames.get(0).length);
        assertEquals(0x1F, frames.get(1)[0] & 0xFF);
        assertEquals(25, frames.get(1).length);
        assertEquals(2, u32(frames.get(0), 1));
        // 到点 0x1F 定时任务已登记（在重放批次之后）。
        assertEquals(replayTasks + 1, env.scheduler().tasks.size());
        env.scheduler().tasks.get(replayTasks).run();
        frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x1F, frames.get(0)[0] & 0xFF);
    }

    @Test
    void unregisterEmitsRemoveWithinSameGeneration() {
        ChannelWorld env = newWorld();
        env.world().registerNpc(2, syntheticNpc("npc_b", 20, 0, 20, 0));
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        env.world().unregister(2);
        env.world().reconcileViewport();
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x0E, frames.get(0)[0] & 0xFF);
        assertEquals(2, u32(frames.get(0), 1));
    }

    @Test
    void beginSuppressesRemovalsAcrossGeneration() {
        ChannelWorld env = newWorld();
        env.world().registerNpc(2, syntheticNpc("npc_b", 20, 0, 20, 0));
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        // 换代：旧对象 0x0E 不发（可能命中复用 ID），由 ExitScene 语义清除。
        env.world().begin("ini\\scene\\test2", "test2");
        env.world().reconcileViewport();
        assertTrue(env.drain().isEmpty());
        // 新世代重新 armed → 注册+物化。
        env.world().registerNpc(2, syntheticNpc("npc_b2", 1, 0, 1, 0));
        env.world().clientReady();
        env.world().clientActivity();
        List<byte[]> frames = env.drain();
        assertEquals(2, frames.size());
        assertEquals(0x0D, frames.get(0)[0] & 0xFF);
    }

    @Test
    void patrolStepAlternatesDirection() {
        ChannelWorld env = newWorld();
        env.world().registerNpc(2, syntheticNpc("npc_b", 20, 0, 20, 0));
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        int replayTasks = env.scheduler().tasks.size(); // 物化重放批次（5）
        env.world().startPatrol(1);
        assertEquals(replayTasks + 1, env.scheduler().tasks.size());
        env.scheduler().tasks.get(replayTasks).run(); // 3s 后第一步
        List<byte[]> frames = env.drain();
        assertEquals(2, frames.size()); // 0x20 + 0x10
        assertEquals(0x20, frames.get(0)[0] & 0xFF);
        assertEquals(21.5f, Float.intBitsToFloat((int) u32(frames.get(0), 9)), 1e-4f, "X +1.5");
        // 到点 0x1F（1.5s）与下一步（5s 周期）均已登记。
        assertEquals(replayTasks + 3, env.scheduler().tasks.size());
        env.scheduler().tasks.get(replayTasks + 1).run(); // 到点 0x1F
        assertEquals(0x1F, env.drain().get(0)[0] & 0xFF);
        env.scheduler().tasks.get(replayTasks + 2).run(); // 第二步：方向翻转
        frames = env.drain();
        assertEquals(0x20, frames.get(0)[0] & 0xFF);
        assertEquals(20.0f, Float.intBitsToFloat((int) u32(frames.get(0), 9)), 1e-4f, "X 回摆");
    }

    private static long u32(byte[] frame, int off) {
        return (frame[off] & 0xFFL) | (frame[off + 1] & 0xFFL) << 8
                | (frame[off + 2] & 0xFFL) << 16 | (frame[off + 3] & 0xFFL) << 24;
    }
}
