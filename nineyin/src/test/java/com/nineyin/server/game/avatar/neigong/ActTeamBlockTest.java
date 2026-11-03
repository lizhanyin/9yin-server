package com.nineyin.server.game.avatar.neigong;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameCodec;
import com.nineyin.server.transport.FrameEncoder;
import com.nineyin.server.transport.XorKey;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演武碎银额度块（ActDayValue 族）+ 团练疲劳块（TeamFacultyValue 族）下发/扣费/GM，
 * 与演武线帧回归（LogicState 108/0 翻转，F 抓包 127-0-0-1-61579 钉死的帧形）。
 */
class ActTeamBlockTest {

    private static final Instant NOW = Instant.parse("2026-01-02T15:04:05Z");

    private EmbeddedChannel channel;
    private PlayerActor player;
    private Path serverRoot;

    @BeforeEach
    void setUp() throws Exception {
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new FrameEncoder(new Connection(null)));
        player = PlayerActor.create("李逍遥", 9_999_999, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        // 注入临时 SERVER_ROOT（resources/modern/share/faculty/actfacultyinfo.ini 单书样例）。
        serverRoot = Files.createTempDirectory("act-test");
        Path shareRoot = serverRoot.resolve(Path.of("resources", "modern", "share"));
        Files.createDirectories(shareRoot.resolve(Path.of("faculty")));
        Files.writeString(shareRoot.resolve(Path.of("faculty", "actfacultyinfo.ini")),
                "[ng_wd_001]\r\nr=1,11,10,40,130\r\nr=30,11,500,1510,4550\r\n",
                StandardCharsets.ISO_8859_1);
        com.nineyin.commom.cache.ResourceCache.invalidateAll();
        com.nineyin.util.ServerPaths.overrideServerRoot(serverRoot);
    }

    @AfterEach
    void tearDown() {
        com.nineyin.util.ServerPaths.overrideServerRoot(null);
        com.nineyin.commom.cache.ResourceCache.invalidateAll();
        channel.close();
        deleteRecursively(serverRoot);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private List<byte[]> drain() {
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

    private void handle(int messageId, CustomMessages.Value... values) {
        FacultyCustomHandler.handle(new Connection(channel), player, direct(messageId, values));
    }

    /** 客户端 0x1E 直达帧构造（C2S 无 [1,764] 限制，勿用 S2C 编码器）。 */
    private static CustomC2S.Message direct(int messageId, CustomMessages.Value... values) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(values.length + 1);
        appendClientValue(writer, CustomMessages.Value.ofInt32(messageId));
        for (CustomMessages.Value value : values) {
            appendClientValue(writer, value);
        }
        CustomC2S.Message parsed = CustomC2S.parseDirect(writer.toBytes());
        assertNotNull(parsed, "direct frame must parse");
        return parsed;
    }

    private static void appendClientValue(PacketWriter writer, CustomMessages.Value value) {
        if (value.isText()) {
            byte[] data = value.text().getBytes(StandardCharsets.UTF_8);
            writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
        } else if (value.isFloat64()) {
            writer.writeU8(5).writeU64(Double.doubleToRawLongBits(value.float64()));
        } else {
            writer.writeU8(2).writeU32(value.int32() & 0xFFFFFFFFL);
        }
    }

    /** 解 0x10 帧 → 索引→值（int 域），按官方表线型跳过字符串。 */
    private static Map<Integer, Long> decodeProps(byte[] frame) {
        Map<Integer, Long> props = new HashMap<>();
        int count = (frame[10] & 0xFF) | ((frame[11] & 0xFF) << 8);
        int off = 12;
        for (int i = 0; i < count && off + 2 <= frame.length; i++) {
            int idx = (frame[off] & 0xFF) | ((frame[off + 1] & 0xFF) << 8);
            off += 2;
            PropertyTable.Entry entry = null;
            for (PropertyTable.Entry candidate : PropertyTable.entries()) {
                if (candidate.index() == idx) {
                    entry = candidate;
                    break;
                }
            }
            assertNotNull(entry, "unknown prop index " + idx);
            switch (entry.typeCode()) {
                case 1 -> {
                    props.put(idx, (long) frame[off]);
                    off += 1;
                }
                case 2 -> {
                    props.put(idx, (long) ((frame[off] & 0xFF) | (frame[off + 1] << 8)));
                    off += 2;
                }
                case 3 -> {
                    props.put(idx, (long) ((frame[off] & 0xFF) | ((frame[off + 1] & 0xFF) << 8)
                            | ((frame[off + 2] & 0xFF) << 16) | ((frame[off + 3] & 0xFF) << 24)));
                    off += 4;
                }
                case 4, 9 -> {
                    props.put(idx, java.nio.ByteBuffer.wrap(frame, off, 8).getLong());
                    off += 8;
                }
                case 5 -> {
                    // float 记原始位（断言只查存在性/位值）
                    props.put(idx, (long) Float.floatToRawIntBits(
                            java.nio.ByteBuffer.wrap(frame, off, 4).getFloat()));
                    off += 4;
                }
                case 7, 8 -> {
                    int len = (frame[off] | (frame[off + 1] << 8) | (frame[off + 2] << 16)
                            | (frame[off + 3] << 24));
                    off += 4 + len;
                }
                default -> off += 4;
            }
        }
        return props;
    }

    private static final int IDX_LOGIC_STATE = 463;
    private static final int IDX_MOVE_SPEED = 384;
    private static final int IDX_IN_PARRY = 471;
    private static final int IDX_PARRY_VALUE = 475;
    private static final int IDX_SHOT_POWER = 501;
    private static final int IDX_MELEE_POWER_ADD = 503;
    private static final int IDX_PHY_FINAL_ADD = 523;
    private static final int IDX_PHY_VA = 565;
    private static final int IDX_CAPITAL0 = 416;
    private static final int IDX_FACULTY_STYLE = 759;
    private static final int IDX_TEAM_VALUE = 777;
    private static final int IDX_TEAM_LAST = 778;
    private static final int IDX_ACT_DAY_VALUE = 790;
    private static final int IDX_ACT_COST_SUIYIN = 791;
    private static final int IDX_ACT_LAST = 795;
    private static final int IDX_ACT_USED = 796;
    private static final int IDX_ACT_MAX = 797;

    // ------------------------------------------------------------------ 下发块

    @Test
    void propertiesCarryActAndTeamBlock() {
        player.progress().setActDayLimit(1000); // 1000 两 = 1_000_000 毫
        player.progress().tryChargeActSuiYin(130_000);
        player.progress().setTeamFacultyLast(7777);
        List<PropertyValues.Indexed> props = player.progress().properties();
        Map<String, Integer> byName = new HashMap<>();
        for (PropertyValues.Indexed prop : props) {
            PropertyTable.Entry entry = PropertyTable.byIndex(prop.int16Index());
            if (entry != null) {
                byName.put(entry.name(), prop.value().i32());
            }
        }
        assertEquals(1_000_000, byName.get("ActDayValue"));
        assertEquals(130_000, byName.get("ActCostSuiYin"));
        assertEquals(1_000_000, byName.get("ActDayValueMax"));
        assertEquals(130_000, byName.get("ActDayValueUsed"));
        assertEquals(0, byName.get("TeamFacultyState"));
        assertEquals(0, byName.get("TeamFacultyValue"));
        assertEquals(7777, byName.get("TeamFacultyLast"));
        // 客户端主面板"可用碎银" = (ActDayValue-ActCostSuiYin)/1000 两 = 870 两。
        assertEquals(870, (byName.get("ActDayValue") - byName.get("ActCostSuiYin")) / 1000);
    }

    @Test
    void unlimitedByDefaultSendsLargeQuotaAndZeroMax() {
        List<PropertyValues.Indexed> props = player.progress().properties();
        Map<String, Integer> byName = new HashMap<>();
        for (PropertyValues.Indexed prop : props) {
            PropertyTable.Entry entry = PropertyTable.byIndex(prop.int16Index());
            if (entry != null) {
                byName.put(entry.name(), prop.value().i32());
            }
        }
        assertEquals(PlayerProgress.UNLIMITED_ACT_DAY_VALUE, byName.get("ActDayValue"));
        assertEquals(0, byName.get("ActDayValueMax"));
        assertEquals(0, byName.get("ActCostSuiYin"));
    }

    // ------------------------------------------------------------------ 扣费链

    @Test
    void actPayChargesSilverAndAccountsCost() {
        handle(1006, CustomMessages.Value.ofInt32(21)); // 演武开始
        drain();
        int silverBefore = player.silver();
        handle(1006, CustomMessages.Value.ofInt32(23),
                CustomMessages.Value.ofFloat64(0), CustomMessages.Value.ofFloat64(1));
        List<byte[]> frames = drain();
        // ng_wd_001 层 30（seed 书）卡 0 基价 500 文（1 两 = 1000 文）。
        assertEquals(silverBefore - 500, player.silver());
        assertEquals(500, player.progress().actCostSuiYin());
        assertEquals(500, player.progress().snapshot().actDayValueUsed());
        // 帧序：[0x10 碎银单帧, 0x18 书帧, 0x10 vital, 191/24]。
        assertEquals(4, frames.size());
        assertEquals(0x10, frames.get(0)[0] & 0xFF);
        assertEquals(0x18, frames.get(1)[0] & 0xFF);
        assertEquals(0x10, frames.get(2)[0] & 0xFF);
        assertEquals(0x1E, frames.get(3)[0] & 0xFF);
        assertEquals(24, frames.get(3)[9] & 0xFF);
        Map<Integer, Long> vital = decodeProps(frames.get(2));
        assertEquals(500L, vital.get(IDX_ACT_COST_SUIYIN));
        assertEquals(500L, vital.get(IDX_ACT_USED));
        assertEquals(0L, vital.get(IDX_ACT_MAX), "默认不限制（Max=0）");
        assertEquals(PlayerProgress.UNLIMITED_ACT_DAY_VALUE,
                vital.get(IDX_ACT_DAY_VALUE));
        assertEquals(0L, vital.get(IDX_TEAM_VALUE), "团练疲劳块随帧（默认 0）");
        assertEquals(0L, vital.get(IDX_TEAM_LAST));
    }

    @Test
    void actPayRejectsWhenDailyLimitExceeded() {
        player.progress().setActDayLimit(1); // 1 两 = 1000 文 < 卡 2 基价 4550 文
        handle(1006, CustomMessages.Value.ofInt32(21));
        drain();
        handle(1006, CustomMessages.Value.ofInt32(23),
                CustomMessages.Value.ofFloat64(2), CustomMessages.Value.ofFloat64(1));
        List<byte[]> frames = drain();
        assertTrue(frames.isEmpty(), "超限打卡应静默拒绝（零帧零变更）");
        assertEquals(0, player.progress().actCostSuiYin());
    }

    @Test
    void actPayRejectsWhenSilverInsufficient() {
        player.setSilver(100); // 文级余额 < 卡价
        handle(1006, CustomMessages.Value.ofInt32(21));
        drain();
        handle(1006, CustomMessages.Value.ofInt32(23),
                CustomMessages.Value.ofFloat64(0), CustomMessages.Value.ofFloat64(1));
        assertTrue(drain().isEmpty());
        assertEquals(100, player.silver());
    }

    @Test
    void nonSuiyinPaymentAwardsWithoutCharge() {
        handle(1006, CustomMessages.Value.ofInt32(21));
        drain();
        int silverBefore = player.silver();
        handle(1006, CustomMessages.Value.ofInt32(23),
                CustomMessages.Value.ofFloat64(1), CustomMessages.Value.ofFloat64(2)); // 官银
        List<byte[]> frames = drain();
        assertFalse(frames.isEmpty());
        assertEquals(silverBefore, player.silver());
        assertEquals(0, player.progress().actCostSuiYin());
    }

    // ------------------------------------------------------------------ GM

    @Test
    void actDayLimitRoundTripAndUnlimitedReset() {
        assertEquals(1000, player.progress().setActDayLimit(1000));
        assertTrue(player.progress().tryChargeActSuiYin(999_999));
        assertFalse(player.progress().tryChargeActSuiYin(2)); // 999_999+2 > 1_000_000
        assertEquals(0, player.progress().setActDayLimit(0)); // 改不限制并清账
        assertEquals(0, player.progress().actCostSuiYin());
        assertEquals(PlayerProgress.UNLIMITED_ACT_DAY_VALUE, player.progress().actDayValueMax() == 0
                ? player.progress().snapshot().actDayValue() : -1);
        assertTrue(player.progress().tryChargeActSuiYin(Integer.MAX_VALUE / 2)); // 不再设限
    }

    @Test
    void clearTeamFatigueResetsConsumption() {
        PlayerProgress.Snapshot snapshot = player.facultySnapshot();
        PlayerProgress.Snapshot loaded = new PlayerProgress.Snapshot(snapshot.curNeiGong(),
                snapshot.faculty(), snapshot.facultyState(), snapshot.facultyStyle(),
                snapshot.facultyName(), snapshot.fillSpeed(), snapshot.fillSpeedBase(),
                snapshot.fillSpeedMultiplier(), snapshot.lastAdvanceUtc(), snapshot.books(),
                snapshot.liveGroove1(), snapshot.liveGroove2(), snapshot.liveGroove3(),
                snapshot.liveGroove4(), snapshot.liveGroove5(), snapshot.holdFaculty(),
                snapshot.liveSpeedPower(), snapshot.lastLivepointUtc(),
                PlayerProgress.UNLIMITED_ACT_DAY_VALUE, 0, 0, 0, PlayerProgress.UNLIMITED_ACT_DAY_VALUE,
                79_190, 66);
        player.restoreFaculty(loaded, NOW);
        assertEquals(79_190, player.progress().teamFacultyValue());
        player.progress().clearTeamFacultyFatigue();
        assertEquals(0, player.progress().teamFacultyValue());
        assertEquals(66, player.progress().snapshot().teamFacultyLast());
    }

    // ------------------------------------------------------- 演武线帧回归（动作链）

    @Test
    void actBeginAndExitToggleLogicStateOnTheWire() {
        handle(1006, CustomMessages.Value.ofInt32(21)); // 开始
        List<byte[]> beginFrames = drain();
        assertEquals(2, beginFrames.size());
        Map<Integer, Long> beginVital = decodeProps(beginFrames.get(0));
        assertEquals(108, beginVital.get(IDX_LOGIC_STATE), "演武开始帧必须带 LogicState=108");
        // 参考全量帧补齐块（61579 #2194 对照）：运动/格挡/Add 分解族/货币/终伤。
        assertTrue(beginVital.containsKey(IDX_MOVE_SPEED), "MoveSpeed 补齐");
        assertEquals(0L, beginVital.get(IDX_IN_PARRY), "InParry=0（未格挡）");
        assertEquals(940L, beginVital.get(IDX_PARRY_VALUE), "ParryValue=100+840 书加成");
        assertEquals(75L, beginVital.get(IDX_MELEE_POWER_ADD), "MeleePowerAdd=有效臂力 30+45");
        assertEquals(80L, beginVital.get(IDX_SHOT_POWER));
        assertEquals(23L, beginVital.get(IDX_PHY_VA));
        assertEquals(0L, beginVital.get(IDX_PHY_FINAL_ADD));
        assertTrue(beginVital.containsKey(IDX_CAPITAL0), "CapitalType0(黄金) 补齐");
        assertEquals(2, beginVital.get(IDX_FACULTY_STYLE));
        assertEquals(0, beginVital.get(773), "演武中 FillSpeed=0（面板显示停止）");
        assertEquals(200, beginVital.get(774), "NorFacultyAdd 恒 200（F 抓包 #2194 diff 钉死）");
        // 191/22 = [191, 22, 0]
        byte[] msg = beginFrames.get(1);
        assertEquals(0x1E, msg[0] & 0xFF);
        assertArrayEquals(new byte[]{2, (byte) 0xBF, 0, 0, 0},
                java.util.Arrays.copyOfRange(msg, 3, 8));
        assertArrayEquals(new byte[]{2, 22, 0, 0, 0},
                java.util.Arrays.copyOfRange(msg, 8, 13));

        handle(1006, CustomMessages.Value.ofInt32(22), CustomMessages.Value.ofFloat64(0)); // 退出
        List<byte[]> exitFrames = drain();
        assertEquals(1, exitFrames.size());
        Map<Integer, Long> exitVital = decodeProps(exitFrames.get(0));
        assertEquals(0, exitVital.get(IDX_LOGIC_STATE), "退出演武必须回落 LogicState=0");
        assertEquals(1, exitVital.get(IDX_FACULTY_STYLE), "退出后 FacultyStyle=1（对齐参考）");
    }

    @Test
    void exitThenBeginRestartsLogicStateTransition() {
        handle(1006, CustomMessages.Value.ofInt32(21));
        drain();
        handle(1006, CustomMessages.Value.ofInt32(22), CustomMessages.Value.ofFloat64(0));
        drain();
        handle(1006, CustomMessages.Value.ofInt32(21)); // 客户端每轮 自动 22→24→21
        List<byte[]> frames = drain();
        assertEquals(2, frames.size());
        assertEquals(108, decodeProps(frames.get(0)).get(IDX_LOGIC_STATE));
    }

}
