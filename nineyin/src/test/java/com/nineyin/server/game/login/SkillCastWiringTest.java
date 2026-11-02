package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.WireType;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.fight.FightState;
import com.nineyin.server.world.fight.SkillCastHandlerFlowTest;
import com.nineyin.server.world.npc.NpcResolver;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * LoginFlow 战斗接线：0x07 目标请求 → LastObject 属性帧、custom 431 等价选中、
 * 0x1E 直达 msg211 → SkillCastHandler 全链（不走过登录协议，手工装配 session）。
 */
class SkillCastWiringTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    @BeforeAll
    static void needsSharedSkillResources() {
        Assumptions.assumeTrue(java.nio.file.Files.isDirectory(
                GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过接线测试");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    private static final class Wiring {

        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final LoginFlow flow;
        final LoginSession session = new LoginSession();
        /** flow/world 共用：出招命中 tick 挂在 flow 侧，测试用 runPending() 泵。 */
        final DirectRunScheduler scheduler = new DirectRunScheduler();

        Wiring() throws Exception {
            java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("skill-wiring");
            flow = new LoginFlow(new RoleStoreFacade(JsonRoleRepository.open(temp.resolve("roles.json"))),
                    FacultyStore.open(temp.resolve("faculty.json")), new GmHub(),
                    Clock.systemUTC(), null, scheduler);
            session.player = PlayerActor.create("紫泉", 99999, Clock.systemUTC());
            session.world = new SceneLifecycle(connection, scheduler);
            session.world.begin("ini\\scene\\scene01", "scene01");
            session.world.registerNpc(2, combatNpc("monv030101", 1000, 100, 20, 100));
            session.fight = new FightState();
            session.learnedSkills.put("CS_jh_sfgp01", 1);
        }

        List<byte[]> drain() {
            List<byte[]> frames = new java.util.ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }
    }

    /**
     * 收起不定时任务的单任务调度器（facultyTicker 等注册任务直跑）：
     * schedule 只记录不执行（战斗收尾/多段命中 tick 默认不跑，测试只验接线首帧），
     * 需要 tick 落地的用 {@link #runPending()} 手动泵（7da2ce3 起命中走调度）。
     */
    static final class DirectRunScheduler extends SkillCastHandlerFlowTest.LoginFlowTestSchedulerAdapter {

        final List<Runnable> pending = new java.util.ArrayList<>();

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay,
                                                                java.util.concurrent.TimeUnit unit) {
            pending.add(command);
            return null;
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long period, java.util.concurrent.TimeUnit unit) {
            return null;
        }

        void runPending() {
            for (Runnable task : List.copyOf(pending)) {
                task.run();
            }
            pending.clear();
        }
    }

    @Test
    void objectRequestSelectsTargetAndRepliesLastObject() throws Exception {
        Wiring wiring = new Wiring();
        byte[] frame = new byte[33];
        frame[0] = 0x07;
        writeU32(frame, 17, 577);  // seq
        writeU32(frame, 21, 2);    // objId
        writeU32(frame, 25, 1);    // owner
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session, frame));

        List<byte[]> replies = wiring.drain();
        assertEquals(1, replies.size(), "exactly the LastObject frame");
        byte[] lastObject = replies.get(0);
        assertEquals(0x10, lastObject[0] & 0xFF);
        assertEquals(1, u32(lastObject, 2), "player object");
        assertEquals(1, u16(lastObject, 10), "single property");
        int index = u16(lastObject, 12);
        assertEquals(414, index, "LastObject 属性索引（62433 抓包 idx414，玩家域末现）");
        assertEquals(PropertyTable.type("LastObject"),
                WireType.OBJECT);
        long packed = s64(lastObject, 14);
        assertEquals((2 & 0xFFFFFFFFL) | 1L << 32, packed, "obj(2,1) packed");
        assertTrue(wiring.session.fight.livingSelectedTarget(wiring.session.world) != null,
                "selection recorded");
    }

    @Test
    void custom431SelectsSameTarget() throws Exception {
        Wiring wiring = new Wiring();
        PacketWriter writer = PacketWriter.ofCapacity(24);
        writer.writeU8(0x1E).writeU16(2);
        writer.writeU8(2).writeU32(431);
        writer.writeU8(8).writeU32(2).writeU32(1);
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session, writer.toBytes()));

        List<byte[]> replies = wiring.drain();
        assertEquals(1, replies.size(), "LastObject reply for 431");
        assertEquals(0x10, replies.get(0)[0] & 0xFF);
        assertTrue(wiring.session.fight.livingSelectedTarget(wiring.session.world) != null);
    }

    @Test
    void useSkillDispatchesToCombatHandler() throws Exception {
        Wiring wiring = new Wiring();
        wiring.session.fight.selectTarget(wiring.session.world, 2, 1);

        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(6);
        writer.writeU8(2).writeU32(211);
        byte[] id = "CS_jh_sfgp01".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writer.writeU8(6).writeU32(id.length + 1).writeBytes(id).writeU8(0);
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(100f));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(20f));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(100f));
        writer.writeU8(4).writeU32(Float.floatToRawIntBits(1f));
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session, writer.toBytes()));

        List<byte[]> replies = wiring.drain();
        // 即时帧 5 张：CD 行 + active_skill_rec + GCD 行 + 玩家全量 0x10 + msg20
        // （337 坐标回显已停发；本环境无 View40 行号故无 CanUse 帧；命中挂调度不即时）
        assertEquals(5, replies.size(), "cast frames emitted via LoginFlow dispatch");
        assertEquals(0x11, replies.get(0)[0] & 0xFF);
        assertEquals(0x11, replies.get(1)[0] & 0xFF);
        assertEquals(0x11, replies.get(2)[0] & 0xFF);
        assertEquals(0x10, replies.get(3)[0] & 0xFF);
        assertEquals(0x1E, replies.get(4)[0] & 0xFF, "msg20 技能动作帧");
    }

    @Test
    void manualParryEngageOutsideCombatAndInCombat() throws Exception {
        Wiring wiring = new Wiring();
        PacketWriter engage = PacketWriter.ofCapacity(24);
        engage.writeU8(0x1E).writeU16(2);
        engage.writeU8(2).writeU32(54);
        engage.writeU8(3).writeU64(0);   // 按下
        byte[] engageFrame = engage.toBytes();

        // 非战斗态（LogicState=0）也可按格挡（原版右键起手式随时可用）：回 169 + 0x10 两帧
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session, engageFrame));
        List<byte[]> replies = wiring.drain();
        assertEquals(2, replies.size(), "msg169 + 0x10 parry frames outside combat");
        assertEquals(0x1E, replies.get(0)[0] & 0xFF);
        assertTrue(wiring.session.player.parrying());

        // 松开复位，再验战斗内按下同样回两帧
        PacketWriter release = PacketWriter.ofCapacity(24);
        release.writeU8(0x1E).writeU16(2);
        release.writeU8(2).writeU32(54);
        release.writeU8(3).writeU64(1);   // 松开
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session, release.toBytes()));
        wiring.drain();
        assertTrue(!wiring.session.player.parrying());

        PacketWriter cast = PacketWriter.ofCapacity(64);
        cast.writeU8(0x1E).writeU16(6);
        cast.writeU8(2).writeU32(211);
        byte[] id = "CS_jh_sfgp01".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        cast.writeU8(6).writeU32(id.length + 1).writeBytes(id).writeU8(0);
        cast.writeU8(4).writeU32(Float.floatToRawIntBits(100f));
        cast.writeU8(4).writeU32(Float.floatToRawIntBits(20f));
        cast.writeU8(4).writeU32(Float.floatToRawIntBits(100f));
        cast.writeU8(4).writeU32(Float.floatToRawIntBits(1f));
        wiring.session.fight.selectTarget(wiring.session.world, 2, 1);
        wiring.flow.dispatch(wiring.connection, wiring.session, cast.toBytes());
        wiring.drain();
        // 多段技能命中挂调度（7da2ce3）：泵 tick 后首段命中 → enterCombat
        wiring.scheduler.runPending();
        wiring.drain();
        assertTrue(wiring.session.player.fighting(), "hit enters combat");

        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session, engageFrame));
        replies = wiring.drain();
        assertEquals(2, replies.size(), "msg169 + 0x10 parry frames in combat");
        assertEquals(0x1E, replies.get(0)[0] & 0xFF);
        assertTrue(wiring.session.player.parrying());
    }

    // ------------------------------------------------------------------ helpers

    static NpcSpawn combatNpc(String model, int maxHp, float x, float y, float z) {
        NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                "wiring-mon-1", model, "attacknpc", null);
        resolved.properties.put("MaxHP", PropertyValues.Value.ofInt32(maxHp));
        resolved.properties.put("HP", PropertyValues.Value.ofInt32(maxHp));
        resolved.properties.put("Resource", PropertyValues.Value.ofString(model));
        return new NpcSpawn(resolved, x, y, z, 0f);
    }

    static void writeU32(byte[] frame, int off, int value) {
        frame[off] = (byte) value;
        frame[off + 1] = (byte) (value >>> 8);
        frame[off + 2] = (byte) (value >>> 16);
        frame[off + 3] = (byte) (value >>> 24);
    }

    static int u16(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8;
    }

    static int u32(byte[] frame, int off) {
        return (frame[off] & 0xFF) | (frame[off + 1] & 0xFF) << 8
                | (frame[off + 2] & 0xFF) << 16 | (frame[off + 3] & 0xFF) << 24;
    }

    static long s64(byte[] frame, int off) {
        return (u32(frame, off) & 0xFFFFFFFFL) | (u32(frame, off + 4) & 0xFFFFFFFFL) << 32;
    }
}
