package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;
import com.nineyin.util.ServerPaths;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 216 轻功点经济（qgdefine Consume/UseType 解析 + begin 扣点/end 免扣 +
 * 不足拒绝；2026-11-03 玩家资料「开局 10 点其后每秒 7 点」的 server 侧口径）。
 */
class QingGongEconomyTest {

    private Path tempRoot;
    private Path previousRoot;

    @BeforeEach
    void installMiniQgDefine() throws IOException {
        previousRoot = ServerPaths.serverRoot();
        tempRoot = Files.createTempDirectory("qg-define-test");
        Path dir = tempRoot.resolve(Path.of("resources", "modern", "share", "skill", "qinggong"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("qgdefine.ini"), """
                [qinggong_2]
                Consume=10
                UseType=1
                BufferID=buf_qg_drift

                [qinggong_5]
                Consume=15
                UseType=0

                [qinggong_7]
                Consume=13
                UseType=0

                [qinggong_11]
                Consume=10
                UseType=1
                BufferID=buf_qg_climb

                [qinggong_1]
                Para1=3.9
                """, StandardCharsets.ISO_8859_1);
        ServerPaths.overrideServerRoot(tempRoot);
    }

    @AfterEach
    void restoreRoot() {
        ServerPaths.overrideServerRoot(previousRoot);
    }

    /** 216 消息（0x1E 直达形态解析：TVarList int + text + int）。 */
    private static CustomC2S.Message use(String id, int flag) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        com.nineyin.server.protocol.PacketWriter writer =
                com.nineyin.server.protocol.PacketWriter.ofCapacity(24 + idBytes.length);
        writer.writeU8(0x1E).writeU16(3);
        writer.writeU8(2).writeU32(216);
        writer.writeU8(6).writeU32(idBytes.length + 1).writeBytes(idBytes).writeU8(0);
        writer.writeU8(2).writeU32(flag);
        return CustomC2S.parseDirect(writer.toBytes());
    }

    @Test
    void catalogParsesConsumeUseTypeAndBufferId() {
        QingGongHandler.Definition drift = QingGongHandler.catalog().get("qinggong_2");
        assertEquals(10, drift.consume());
        assertEquals(1, drift.useType());
        assertEquals("buf_qg_drift", drift.bufferId());
        QingGongHandler.Definition jump = QingGongHandler.catalog().get("qinggong_5");
        assertEquals(15, jump.consume());
        assertEquals(0, jump.useType());
        QingGongHandler.Definition passive = QingGongHandler.catalog().get("qinggong_1");
        assertEquals(0, passive.consume(), "无 Consume 不扣点");
    }

    @Test
    void beginChargesConsumeOnceAndEndNeverCharges() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection connection = new Connection(channel);
        PlayerActor player = PlayerActor.create("踏波侠", 0, Clock.systemUTC());

        QingGongHandler.handle(connection, player, use("qinggong_2", 1));
        assertEquals(140, player.actorState().qingGongPoint(), "begin 扣 qgdefine Consume=10");
        Object frame = channel.readOutbound();
        assertTrue(frame instanceof byte[] && (((byte[]) frame)[0] & 0xFF) == 0x10,
                "扣点随单属性帧下发");

        QingGongHandler.handle(connection, player, use("qinggong_2", 0));
        assertEquals(140, player.actorState().qingGongPoint(), "end 不再扣（一次按放双倍耗）");
    }

    @Test
    void heldBeginRetransmitsAreRenewalsNotRecharges() {
        // 2026-11-03 真机「按住 Shift 轻功条秒清」：引擎逐帧重发 begin，逐条扣费即秒清。
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection connection = new Connection(channel);
        PlayerActor player = PlayerActor.create("冲刺侠", 0, Clock.systemUTC());

        QingGongHandler.handle(connection, player, use("qinggong_2", 1));
        assertEquals(140, player.actorState().qingGongPoint());
        assertTrue(player.drifting());

        for (int i = 0; i < 30; i++) { // 引擎 1 秒内逐帧重发
            QingGongHandler.handle(connection, player, use("qinggong_2", 1));
        }
        assertEquals(140, player.actorState().qingGongPoint(), "同 id 续约不重复扣开局费");
        assertTrue(player.drifting());

        QingGongHandler.handle(connection, player, use("qinggong_2", 0));
        assertFalse(player.drifting(), "end 摘租约");
    }

    @Test
    void instantTypeEngineSpamIsGuardedButHumanPacePasses() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection connection = new Connection(channel);
        PlayerActor player = PlayerActor.create("雁行", 0, Clock.systemUTC());

        QingGongHandler.handle(connection, player, use("qinggong_7", 1));
        assertEquals(137, player.actorState().qingGongPoint(), "雁行功 Consume=13 逐次扣");
        for (int i = 0; i < 30; i++) { // 引擎逐帧重发（UseType=0）
            QingGongHandler.handle(connection, player, use("qinggong_7", 1));
        }
        assertEquals(137, player.actorState().qingGongPoint(),
                "500ms 重报闸内不重复扣（重发不是新动作）");
    }

    @Test
    void differentHoldIdBeginSwapsLeaseWithoutDoubleDrain() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection connection = new Connection(channel);
        PlayerActor player = PlayerActor.create("切换侠", 0, Clock.systemUTC());

        QingGongHandler.handle(connection, player, use("qinggong_2", 1));
        assertEquals(140, player.actorState().qingGongPoint());
        assertEquals("qinggong_2", player.driftQingGongId());
        // 异 id 新 begin（无配对 end）：换租约不叠双份秒耗，开局费照付
        QingGongHandler.handle(connection, player, use("qinggong_11", 1));
        assertEquals(130, player.actorState().qingGongPoint());
        assertEquals("qinggong_11", player.driftQingGongId());
        // 踏波行的游离 end（旧 id）不误摘新租约
        QingGongHandler.handle(connection, player, use("qinggong_2", 0));
        assertTrue(player.drifting(), "旧 id 的 end 不摘新租约");
        assertEquals("qinggong_11", player.driftQingGongId());
    }

    @Test
    void beginRejectedWhenPointsInsufficient() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection connection = new Connection(channel);
        PlayerActor player = PlayerActor.create("穷人", 0, Clock.systemUTC());
        player.spendQingGong(145);
        assertEquals(5, player.actorState().qingGongPoint());

        QingGongHandler.handle(connection, player, use("qinggong_5", 1));
        assertEquals(5, player.actorState().qingGongPoint(), "点数不足静默拒（不扣不帧）");
        assertFalse(player.drifting());
    }

    @Test
    void unknownIdAndNoConsumeStayNoOp() {
        EmbeddedChannel channel = new EmbeddedChannel();
        Connection connection = new Connection(channel);
        PlayerActor player = PlayerActor.create("健步", 0, Clock.systemUTC());
        QingGongHandler.handle(connection, player, use("qinggong_1", 1));
        assertEquals(150, player.actorState().qingGongPoint());
        QingGongHandler.handle(connection, player, use("qinggong_99", 1));
        assertEquals(150, player.actorState().qingGongPoint());
        assertTrue(player.releaseDrift() == false, "无租约可摘");
    }
}
