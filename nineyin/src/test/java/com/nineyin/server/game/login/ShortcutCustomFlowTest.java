package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * C2S 200/201 快捷栏编辑链路（帧序同客户端记录表操作语义）：
 * 200 替换 = 0x12 删旧行 + 0x11 增新行且新行落表尾（客户端删行重编号、增行追加，
 * 服务端列表必须镜像，行号口径才不漂移）；201 上行的是**可见快捷栏索引**
 * （shortcut_game.lua remove_shortcut(index) 全调用点），按索引删行 = 0x12；
 * 交换四连（put_other_short：201(源) 200(源,目标) 201(目标) 200(目标,源)）后两栏互换。
 */
class ShortcutCustomFlowTest {

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final LoginSession session = new LoginSession();
        final LoginFlow flow = new LoginFlow(null, null, new com.nineyin.server.gm.GmHub(),
                Clock.systemUTC(), null, null);

        Env() {
            session.player = PlayerActor.create("紫泉", 0, Clock.systemUTC());
            session.selected = new Roles.RoleSnapshot(66L, 1, "紫泉", false, 0, 1,
                    new Roles.Appearance(1, List.of()), null);
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

    /** 0x1E 直达 [msgId, int, str, str]（200）或 [msgId, int]（201）。 */
    private static CustomC2S.Message message(int msgId, Object... args) {
        PacketWriter writer = PacketWriter.ofCapacity(64);
        writer.writeU8(0x1E).writeU16(1 + args.length);
        writer.writeU8(2).writeU32(msgId);
        for (Object arg : args) {
            if (arg instanceof Integer value) {
                writer.writeU8(2).writeU32(value);
            } else {
                byte[] data = String.valueOf(arg).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writer.writeU8(6).writeU32(data.length + 1).writeBytes(data).writeU8(0);
            }
        }
        return CustomC2S.parseDirect(writer.toBytes());
    }

    /** 0x12 删行帧尾 u16 = 记录行号。 */
    private static int delRowNumber(byte[] frame) {
        return frame[12] & 0xFF | (frame[13] & 0xFF) << 8;
    }

    @Test
    void setShortcutReplacesRowAndRemoveDeletesIt() {
        Env env = new Env();
        // 首次设置 index=12：一帧 0x11（无旧行）。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp01"));
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x11, frames.get(0)[0]);
        assertEquals(RecordMessages.RECORD_SHORTCUT,
                (frames.get(0)[10] & 0xFF) | frames.get(0)[11] << 8, "官方下标 93");
        assertEquals(1, env.session.player.shortcutSnapshot().size());

        // 幂等重设同值：零帧。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp01"));
        assertNull(env.channel.readOutbound(), "幂等设置零帧");

        // 替换 index=12：0x12 删旧行(行0) + 0x11 增新行。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp04"));
        frames = env.drain();
        assertEquals(2, frames.size(), "替换 = 删旧 + 增新");
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(0, delRowNumber(frames.get(0)), "旧行在行0");
        assertEquals(0x11, frames.get(1)[0]);
        var snapshot = env.session.player.shortcutSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals("CS_jh_sfgp04", snapshot.get(0).id());

        // 201 按可见索引删（客户端 remove_shortcut(index)）：一帧 0x12，内存清空。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 12));
        frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x12, frames.get(0)[0]);
        assertTrue(env.session.player.shortcutSnapshot().isEmpty());
        // 再删同索引静默；删不存在的索引也静默。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 12));
        assertNull(env.channel.readOutbound(), "缺行删除零帧");
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 0));
        assertNull(env.channel.readOutbound(), "未命中索引零帧");
    }

    /**
     * 替换后新行落表尾：服务端行序必须与客户端记录表一致（0x12 删行重编号 +
     * 0x11 追加），否则后续删行的行号错位——真机"覆盖失败/原栏位还有技能"根因之一。
     */
    @Test
    void replaceMovesRowToTableEnd() {
        Env env = new Env();
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 10, "skill", "CS_jh_xfz01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 11, "skill", "CS_jh_xfz02"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_xfz03"));
        env.drain();

        // 替换中间行 idx11：0x12 删行1 + 0x11 追加，快照行序变为 [10, 12, 11]。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 11, "skill", "CS_wd_tjq01"));
        List<byte[]> frames = env.drain();
        assertEquals(2, frames.size());
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(1, delRowNumber(frames.get(0)), "被替换旧行在行1");
        assertEquals(0x11, frames.get(1)[0]);
        List<PlayerActor.PlayerShortcut> snapshot = env.session.player.shortcutSnapshot();
        assertEquals(List.of(10, 12, 11),
                snapshot.stream().map(PlayerActor.PlayerShortcut::index).toList(),
                "替换行移表尾（客户端追加语义）");
        assertEquals("CS_wd_tjq01", snapshot.get(2).id());

        // 行序漂移下删 idx12：0x12 行号必须是客户端表内行1（不是旧口径行2）。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 12));
        frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(1, delRowNumber(frames.get(0)), "idx12 删后位于行1");
        assertEquals(List.of(10, 11),
                env.session.player.shortcutSnapshot().stream()
                        .map(PlayerActor.PlayerShortcut::index).toList());
    }

    /**
     * 真机拖拽交换四连（shortcut_game.lua put_other_short）：
     * 201(源) → 200(源,目标技) → 201(目标) → 200(目标,源技)，最终两栏互换、
     * 第三栏不受牵连。旧实现 201 按行号解释会错删无辜行（idx20 起行号≠索引）。
     */
    @Test
    void dragSwapSequenceSwapsTwoSlots() {
        Env env = new Env();
        // starter 布局缩影：row0=idx10, row1=idx11, row2=idx20（索引≠行号）。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 10, "skill", "CS_jh_xfz01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 11, "skill", "CS_jh_xfz02"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 20, "skill", "CS_wd_tjq01"));
        env.drain();

        Connection connection = new Connection(env.channel);
        // put_other_short(self=10, other=11)
        env.flow.handleRemoveShortcut(connection, env.session, message(201, 10));
        env.flow.handleSetShortcut(connection, env.session, message(200, 10, "skill", "CS_jh_xfz02"));
        env.flow.handleRemoveShortcut(connection, env.session, message(201, 11));
        env.flow.handleSetShortcut(connection, env.session, message(200, 11, "skill", "CS_jh_xfz01"));

        List<byte[]> frames = env.drain();
        // 0x12(行0 删 idx10) 0x11(idx10=xfz02) 0x12(行0 删 idx11) 0x11(idx11=xfz01)
        assertEquals(4, frames.size());
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(0, delRowNumber(frames.get(0)));
        assertEquals(0x11, frames.get(1)[0]);
        assertEquals(0x12, frames.get(2)[0]);
        assertEquals(0, delRowNumber(frames.get(2)), "idx11 删时已因前序删行前移到行0");
        assertEquals(0x11, frames.get(3)[0]);

        List<PlayerActor.PlayerShortcut> snapshot = env.session.player.shortcutSnapshot();
        assertEquals(3, snapshot.size(), "交换不丢行");
        assertEquals("CS_jh_xfz02",
                snapshot.stream().filter(s -> s.index() == 10).findFirst().orElseThrow().id());
        assertEquals("CS_jh_xfz01",
                snapshot.stream().filter(s -> s.index() == 11).findFirst().orElseThrow().id());
        assertEquals("CS_wd_tjq01",
                snapshot.stream().filter(s -> s.index() == 20).findFirst().orElseThrow().id(),
                "无关栏位不受牵连");
    }

    /** 拖出释放（game_hand.lua gamehand_left_mouse_up）：201(索引) 移除该栏。 */
    @Test
    void dragOutRemovesShortcutByIndex() {
        Env env = new Env();
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 20, "skill", "CS_wd_tjq01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 21, "skill", "CS_wd_tjq02"));
        env.drain();

        // idx21 在行1：按索引删，0x12 行号取行1（旧按行号解释会拿 21 当行号落空）。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 21));
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x12, frames.get(0)[0]);
        assertEquals(1, delRowNumber(frames.get(0)));
        List<PlayerActor.PlayerShortcut> snapshot = env.session.player.shortcutSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals(20, snapshot.get(0).index());
    }

    @Test
    void invalidSetShortcutRejectedWithoutFrames() {
        Env env = new Env();
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, -3, "skill", "CS_jh_sfgp01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "", "CS_jh_sfgp01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", ""));
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, -1));
        assertNull(env.channel.readOutbound(), "非法参数零帧");
        assertTrue(env.session.player.shortcutSnapshot().isEmpty());
    }
}
