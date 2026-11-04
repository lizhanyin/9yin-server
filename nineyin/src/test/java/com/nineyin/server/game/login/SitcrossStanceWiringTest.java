package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.WireType;
import com.nineyin.server.db.role.JsonRoleRepository;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.fight.CombatSkillCatalog;
import com.nineyin.server.world.fight.FightState;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 打坐 240 全链 + 架招 ReplaceCategory 互斥（2026-11-03）：
 * 240 (1/0/2) 经 LoginFlow dispatch → LogicState 102/0 + 0x10 vital；
 * 花神 15101 与太极 6013 同属 ReplaceCategory=220214，后挂架招逐出先挂（只留一个）。
 */
class SitcrossStanceWiringTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    @BeforeAll
    static void needsSharedSkillResources() {
        Assumptions.assumeTrue(java.nio.file.Files.isDirectory(
                GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill 资源不可用，跳过接线测试");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
        Assumptions.assumeTrue(CombatSkillCatalog.load().buffReplaceCategory(6013) == 220214L,
                "buff_static 缺 ReplaceCategory 数据，跳过互斥测试");
    }

    /** 可推进时钟：跨套路公共 GCD（7s）的出招序列测试用。 */
    static final class MutableClock extends Clock {

        private volatile long millis = 1_800_000_000_000L;

        void advanceSeconds(long seconds) {
            millis += seconds * 1000;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis);
        }
    }

    private static final class Wiring {

        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel);
        final LoginFlow flow;
        final LoginSession session = new LoginSession();
        final SkillCastWiringTest.DirectRunScheduler scheduler =
                new SkillCastWiringTest.DirectRunScheduler();
        final MutableClock clock = new MutableClock();

        Wiring() throws Exception {
            java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("sitcross-wiring");
            flow = new LoginFlow(new RoleStoreFacade(JsonRoleRepository.open(temp.resolve("roles.json"))),
                    FacultyStore.open(temp.resolve("faculty.json")), new GmHub(),
                    clock, null, scheduler);
            session.player = PlayerActor.create("打坐侠", 99999, clock);
            session.world = new SceneLifecycle(connection, scheduler);
            session.world.begin("ini\\scene\\scene01", "scene01");
            session.world.registerNpc(2, SkillCastWiringTest.combatNpc("monv030101", 1000, 100, 20, 100));
            session.fight = new FightState();
            session.learnedSkills.put("CS_yhwq_hsqs04", 1);
            session.learnedSkills.put("CS_wd_tjq04", 1);
        }

        List<byte[]> drain() {
            List<byte[]> frames = new java.util.ArrayList<>();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                frames.add((byte[]) outbound);
            }
            return frames;
        }

        /** 0x1E 直达 custom 帧（msgId int + 可选后续值块）。 */
        byte[] direct(int messageId, PacketWriter... extra) {
            PacketWriter writer = PacketWriter.ofCapacity(16);
            writer.writeU8(0x1E).writeU16(1 + extra.length);
            writer.writeU8(2).writeU32(messageId);
            for (PacketWriter value : extra) {
                writer.writeBytes(value.toBytes());
            }
            return writer.toBytes();
        }

        static PacketWriter int32(int value) {
            PacketWriter writer = PacketWriter.ofCapacity(5);
            writer.writeU8(2).writeU32(value);
            return writer;
        }

        static PacketWriter skillId(String id) {
            PacketWriter writer = PacketWriter.ofCapacity(8 + id.length());
            writer.writeU8(6).writeU32(id.getBytes(StandardCharsets.UTF_8).length + 1)
                    .writeBytes(id.getBytes(StandardCharsets.UTF_8)).writeU8(0);
            return writer;
        }

        static PacketWriter f32(float value) {
            PacketWriter writer = PacketWriter.ofCapacity(5);
            writer.writeU8(4).writeU32(Float.floatToRawIntBits(value));
            return writer;
        }

        void useSkill(String id) {
            assertTrue(flow.dispatch(connection, session,
                    direct(211, skillId(id), f32(100f), f32(20f), f32(100f), f32(1f))),
                    "211 dispatch accepted");
        }
    }

    // ------------------------------------------------------------------ msg240

    @Test
    void sitcrossStartStopAndInterruptedFormAllEngageLeaveState() throws Exception {
        Wiring wiring = new Wiring();
        assertFalse(wiring.session.player.sitting());

        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session,
                wiring.direct(240, Wiring.int32(1))));
        List<byte[]> replies = wiring.drain();
        assertEquals(1, replies.size(), "state change replies exactly one 0x10 vital");
        assertEquals(0x10, replies.get(0)[0] & 0xFF);
        assertTrue(wiring.session.player.sitting(), "LogicState=102 engaged");

        // 重复 1（按住）非切换：重推 102 面板 resync 客户端动画态（零帧会让重点无效）
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session,
                wiring.direct(240, Wiring.int32(1))));
        assertEquals(1, wiring.drain().size(), "duplicate start resyncs panel");
        assertTrue(wiring.session.player.sitting());

        // 2（坐姿动作完结回执，三服抓包 12141/52905/63296 同证）保持坐态并重推面板
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session,
                wiring.direct(240, Wiring.int32(2))));
        assertEquals(1, wiring.drain().size(), "completion ack keeps seated + resync");
        assertTrue(wiring.session.player.sitting(), "240(2) does NOT stand up");

        // 未坐时 2 无操作（零帧零迁移）
        wiring.session.player.applySitcross(false);
        wiring.drain();
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session,
                wiring.direct(240, Wiring.int32(2))));
        assertEquals(0, wiring.drain().size(), "stray 2 while standing is a no-op");
        assertFalse(wiring.session.player.sitting());

        // 0（真停坐形态）起立
        wiring.session.player.applySitcross(true);
        wiring.drain();
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session,
                wiring.direct(240, Wiring.int32(0))));
        assertEquals(1, wiring.drain().size(), "explicit 0 stands up");
        assertFalse(wiring.session.player.sitting());

        // 未知值忽略
        assertTrue(wiring.flow.dispatch(wiring.connection, wiring.session,
                wiring.direct(240, Wiring.int32(7))));
        assertEquals(0, wiring.drain().size(), "unknown value ignored");
        assertFalse(wiring.session.player.sitting());
    }

    @Test
    void sitcrossVitalCarriesLogicState102() throws Exception {
        Wiring wiring = new Wiring();
        wiring.flow.dispatch(wiring.connection, wiring.session, wiring.direct(240, Wiring.int32(1)));
        byte[] vital = wiring.drain().get(0);
        assertEquals(WireType.BYTE, PropertyTable.playerWireType("LogicState"));
        assertTrue(containsIntProp(vital, PropertyTable.playerIdx("LogicState"),
                Actor.LOGIC_STATE_SITCROSS), "vital carries LogicState=102");
    }

    // ------------------------------------------------------------------ 架招互斥

    @Test
    void castingSecondStanceEvictsFirstSameReplaceCategoryBuff() throws Exception {
        Wiring wiring = new Wiring();
        wiring.session.fight.selectTarget(wiring.session.world, 2, 1);

        // 先挂太极守势（架势轮换首态，无目标技能），跨套路 7s 公共 GCD 后再出花神04
        wiring.useSkill("CS_wd_tjq04");
        wiring.drain();
        Map<Integer, Long> afterTaiji =
                wiring.session.player.activeBuffStaticDatas(wiring.clock.instant());
        assertTrue(afterTaiji.containsValue(6013L), "太极守势 6013 挂上（架势轮换首态）");

        wiring.clock.advanceSeconds(10);
        wiring.useSkill("CS_yhwq_hsqs04");
        List<byte[]> replies = wiring.drain();
        Map<Integer, Long> afterYihua =
                wiring.session.player.activeBuffStaticDatas(wiring.clock.instant());
        assertTrue(afterYihua.containsValue(15101L), "花神04 架招 buff 15101 挂上");
        assertFalse(afterYihua.containsValue(6013L), "太极架招被同组逐出——架招只能有一个");
        assertEquals(1, afterYihua.values().stream()
                        .filter(staticData -> CombatSkillCatalog.load()
                                .buffReplaceCategory(staticData) == 220214L).count(),
                "exactly one stance-family buff remains");

        int bufferInfo7 = PropertyTable.playerIdx("BufferInfo7");
        boolean hasEmptySlot7Frame = false;
        for (byte[] frame : replies) {
            if ((frame[0] & 0xFF) == 0x10
                    && containsEmptyStringProp(frame, bufferInfo7)) {
                hasEmptySlot7Frame = true;
            }
        }
        assertTrue(hasEmptySlot7Frame, "逐出随出招发空 BufferInfo7 槽帧");
    }

    @Test
    void replaceCategoryLookupMatchesOfficialData() {
        CombatSkillCatalog catalog = CombatSkillCatalog.load();
        assertEquals(220214L, catalog.buffReplaceCategory(6013), "太极守势");
        assertEquals(220214L, catalog.buffReplaceCategory(6055), "太极攻势");
        assertEquals(220214L, catalog.buffReplaceCategory(15101), "花神04");
        assertEquals(0, catalog.buffReplaceCategory(8682), "红霸体不在互斥组");
        assertEquals(7, catalog.qingGongDrainPerSecond("buf_qg_drift"), "踏波行 7/s");
        assertEquals(10, catalog.qingGongDrainPerSecond("buf_qg_light"), "缓降 10/s");
        assertEquals(7, catalog.qingGongDrainPerSecond("buf_qg_climb"), "上天梯/壁虎 7/s");
    }

    // ------------------------------------------------------------------ helpers

    /** 0x10 帧内是否含 idx=expected 的整值属性（idx u16 + 表线型值，无 tag 字节方言）。 */
    private static boolean containsIntProp(byte[] frame, int index, int expected) {
        int count = u16(frame, 10);
        int offset = 12;
        for (int i = 0; i < count; i++) {
            int idx = u16(frame, offset);
            offset += 2;
            PropertyTable.Entry entry = PropertyTable.byIndex(idx);
            if (entry == null) {
                return false;
            }
            WireType type = PropertyTable.playerWireType(entry.name());
            if (idx == index) {
                return switch (type) {
                    case BYTE -> frame[offset] == (byte) expected;
                    case WORD -> u16(frame, offset) == expected;
                    case INT32 -> u32(frame, offset) == (expected & 0xFFFFFFFFL);
                    default -> false;
                };
            }
            offset += wireSize(type, frame, offset);
        }
        return false;
    }

    /** 0x10 帧内是否含 idx 的空窄串属性（BufferInfoN 逐出帧形态：len=1 仅 NUL）。 */
    private static boolean containsEmptyStringProp(byte[] frame, int index) {
        int count = u16(frame, 10);
        int offset = 12;
        for (int i = 0; i < count; i++) {
            int idx = u16(frame, offset);
            offset += 2;
            PropertyTable.Entry entry = PropertyTable.byIndex(idx);
            if (entry == null) {
                return false;
            }
            WireType type = PropertyTable.playerWireType(entry.name());
            if (idx == index && type == WireType.STRING) {
                return u32(frame, offset) == 1;
            }
            offset += wireSize(type, frame, offset);
        }
        return false;
    }

    private static int wireSize(WireType type, byte[] frame, int offset) {
        return switch (type) {
            case BYTE -> 1;
            case WORD -> 2;
            case INT32, FLOAT32 -> 4;
            case INT64, OBJECT -> 8;
            case STRING, WIDE_STRING -> 4 + (int) u32(frame, offset);
            default -> 0;
        };
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8
                | (data[offset + 2] & 0xFFL) << 16 | (data[offset + 3] & 0xFFL) << 24;
    }
}
