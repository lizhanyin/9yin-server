package com.nineyin.server.game.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.nineyin.server.transport.FrameEncoder;
import com.nineyin.server.world.scene.SceneLifecycle;
import com.nineyin.server.world.scene.WorldScene;
import org.junit.jupiter.api.Test;

import com.nineyin.server.world.npc.NpcCreator;
import com.nineyin.server.world.npc.NpcResolver;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.npc.PatrolRoute;
import com.nineyin.server.world.npc.PatrolSchedule;
import com.nineyin.server.protocol.PropertyValues.Value;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameCodec;
import com.nineyin.server.transport.XorKey;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 场景生命周期行为测试（合成 NPC，不依赖资源）：物化顺序、世代语义、
 * mappath 路线巡逻（0x21 广播拍/弹回/停留/视口重进）与 0x0D/0x1F/0x0E 帧，
 * 对齐 Go scene_lifecycle 的可观察行为与官方 ServerAllDest 真机节拍。
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

    private record ChannelWorld(EmbeddedChannel channel, ManualScheduler scheduler,
                                SceneLifecycle world, long[] clock) {
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
        channel.pipeline().addLast(new FrameEncoder(new Connection(null)));
        ManualScheduler scheduler = new ManualScheduler();
        long[] clock = {0L};
        SceneLifecycle world = new SceneLifecycle(new Connection(channel), scheduler,
                () -> clock[0]);
        world.begin("ini\\scene\\test", "test");
        return new ChannelWorld(channel, scheduler, world, clock);
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
    void patrolFollowsScheduleWithFacingAndBounce() {
        ChannelWorld env = newWorld();
        // 路线 (20,0,20) → (23,0,20) → (26,0,20)；walk=0.75 → 每腿 4000ms，
        // 停留全 0，周期 16000ms（去程 2 腿 + 回程 2 腿）。
        PatrolRoute route = new PatrolRoute("test_route", List.of(
                new PatrolRoute.Waypoint(20f, 0f, 20f, 0f, false, 0),
                new PatrolRoute.Waypoint(23f, 0f, 20f, 0f, false, 0),
                new PatrolRoute.Waypoint(26f, 0f, 20f, 1.5f, false, 0)));
        long epoch = 1_000_000L;
        NpcSpawn npc = syntheticNpc("npc_b", 20, 0, 20, 0);
        npc.schedule = PatrolSchedule.build(route, 0.75f, 4f, epoch);
        // 注册即按共同时钟采样当前位置：offset=5500 → 第二段 w1→w2 的 3/8 处。
        env.clock()[0] = epoch + 5500;
        env.world().registerNpc(2, npc);
        float sampledX = env.world().combatNpcInfo(2).x();
        assertTrue(sampledX > 23f && sampledX < 26f, "当前位置在 w1→w2 段上 " + sampledX);
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        int tickerTask = env.scheduler().tasks.size() - 1; // 5 重放批 + 巡逻首拍
        // 首拍：第二段行进中 → 0x21 目标 w2、速度 0.75、移动标志 1。
        env.scheduler().tasks.get(tickerTask).run();
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x21, frames.get(0)[0] & 0xFF);
        assertEquals(1, frames.get(0)[1] | frames.get(0)[2] << 8, "单记录");
        assertEquals(26f, Float.intBitsToFloat((int) u32(frames.get(0), 11)), 1e-4f, "目标 w2");
        assertEquals(1.5f, Float.intBitsToFloat((int) u32(frames.get(0), 23)), 1e-4f, "路点朝向");
        assertEquals(0.75f, Float.intBitsToFloat((int) u32(frames.get(0), 27)), 1e-4f, "walk 速度");
        assertEquals(1, (int) u32(frames.get(0), 43), "行进标志");
        // 下一拍已排（1s 周期链）。
        assertEquals(tickerTask + 2, env.scheduler().tasks.size());
        // 推进时钟至回程段 w2→w1（offset 9500）。
        env.clock()[0] = epoch + 9500;
        env.scheduler().tasks.get(tickerTask + 1).run();
        frames = env.drain();
        assertEquals(0x21, frames.get(0)[0] & 0xFF);
        assertEquals(23f, Float.intBitsToFloat((int) u32(frames.get(0), 11)), 1e-4f, "回程 w1");
        assertEquals((float) -Math.PI / 2,
                Float.intBitsToFloat((int) u32(frames.get(0), 23)), 1e-3f, "行进朝向");
        // 端点弹回：回程 w1→w0（offset 13500）。
        env.clock()[0] = epoch + 13500;
        env.scheduler().tasks.get(env.scheduler().tasks.size() - 1).run();
        frames = env.drain();
        assertEquals(0x21, frames.get(0)[0] & 0xFF);
        assertEquals(20f, Float.intBitsToFloat((int) u32(frames.get(0), 11)), 1e-4f, "端点弹回");
    }

    @Test
    void patrolDwellsAtWaypointPerSchedule() {
        ChannelWorld env = newWorld();
        // w0→w1 行程 4000ms，w1 停留 3000ms，回程 4000ms；周期 11000ms。
        PatrolRoute route = new PatrolRoute("test_dwell", List.of(
                new PatrolRoute.Waypoint(20f, 0f, 20f, 0f, false, 0),
                new PatrolRoute.Waypoint(23f, 0f, 20f, 0f, false, 3000)));
        long epoch = 1_000_000L;
        NpcSpawn npc = syntheticNpc("npc_b", 20, 0, 20, 0);
        npc.schedule = PatrolSchedule.build(route, 0.75f, 4f, epoch);
        env.clock()[0] = epoch + 5500;
        env.world().registerNpc(2, npc);
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        int tickerTask = env.scheduler().tasks.size() - 1;
        // 首拍落在 w1 停留窗（4000..7000）：0x21 速度 0、标志 0（到达语义）。
        env.scheduler().tasks.get(tickerTask).run();
        List<byte[]> frames = env.drain();
        assertEquals(0x21, frames.get(0)[0] & 0xFF);
        assertEquals(0f, Float.intBitsToFloat((int) u32(frames.get(0), 27)), 1e-4f, "停留速度 0");
        assertEquals(0, (int) u32(frames.get(0), 43), "到达标志");
        assertEquals(7, env.scheduler().tasks.size()); // 5 重放 + 首/次拍
        env.clock()[0] = epoch + 7500; // 停留结束：回程开腿
        env.scheduler().tasks.get(tickerTask + 1).run();
        frames = env.drain();
        assertEquals(0x21, frames.get(0)[0] & 0xFF);
        assertEquals(20f, Float.intBitsToFloat((int) u32(frames.get(0), 11)), 1e-4f, "停留后回程");
        assertEquals(0.75f, Float.intBitsToFloat((int) u32(frames.get(0), 27)), 1e-4f, "回程速度");
    }

    @Test
    void patrolRestartsAfterViewportReentry() {
        ChannelWorld env = newWorld();
        PatrolRoute route = new PatrolRoute("test_reentry", List.of(
                new PatrolRoute.Waypoint(20f, 0f, 20f, 0f, false, 0),
                new PatrolRoute.Waypoint(30f, 0f, 20f, 0f, false, 0)));
        long epoch = 1_000_000L;
        NpcSpawn npc = syntheticNpc("npc_b", 20, 0, 20, 0);
        npc.schedule = PatrolSchedule.build(route, 1f, 4f, epoch); // 每腿 10000ms
        env.clock()[0] = epoch + 500;
        env.world().registerNpc(2, npc);
        env.world().clientReady();
        env.world().clientActivity();
        env.drain();
        float xBefore = env.world().combatNpcInfo(2).x();
        int tasksBeforeLeave = env.scheduler().tasks.size();
        env.world().unregister(2);
        env.world().reconcileViewport();
        env.drain();
        // 物化后重进：广播 ticker 已在跑（不重复启动），位置按时间表继续。
        env.clock()[0] = epoch + 3000;
        env.world().registerNpc(2, npc);
        assertEquals(tasksBeforeLeave, env.scheduler().tasks.size(), "ticker 不重复启动");
        float xAfter = env.world().combatNpcInfo(2).x();
        assertTrue(xAfter > xBefore, "重进位置沿路线继续 " + xBefore + " → " + xAfter);
        assertTrue(xAfter > 20f && xAfter < 30f, "在首段上");
        env.clock()[0] = epoch + 8000;
        env.scheduler().tasks.get(tasksBeforeLeave - 1).run();
        List<byte[]> frames = env.drain();
        assertEquals(0x21, frames.get(0)[0] & 0xFF);
        assertEquals(30f, Float.intBitsToFloat((int) u32(frames.get(0), 11)), 1e-4f, "重进后入巡");
        assertEquals(1, (int) u32(frames.get(0), 43), "行进标志");
    }

    private static long u32(byte[] frame, int off) {
        return (frame[off] & 0xFFL) | (frame[off + 1] & 0xFFL) << 8
                | (frame[off + 2] & 0xFFL) << 16 | (frame[off + 3] & 0xFFL) << 24;
    }
}
