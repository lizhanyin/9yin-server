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
 * C2S 200/201 快捷栏编辑链路（menu2 rebuildShortcutRecord 全量重建方言）：
 * 任一有效变更 = 按变更前行数从 preCount-1 递减到 0 逐行 0x12 清空 + 0x11 全量重推；
 * 201 上行的是**可见快捷栏索引**（shortcut_game.lua remove_shortcut(index) 全调用点）；
 * 幂等/未命中零帧。交换四连（put_other_short）后两栏互换、无关行不受牵连。
 * 增量 del 中段行号在真机客户端方言下会误删/漏删（幽灵+重叠），故弃用。
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

    /** 帧序列的操作简写（D=0x12 行号，A=0x11 行）。 */
    private static String dialect(List<byte[]> frames) {
        StringBuilder sb = new StringBuilder();
        for (byte[] f : frames) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(f[0] == 0x12 ? "D" : "A");
        }
        return sb.toString();
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

        // 替换 index=12（重建方言）：0x12(行0) 清空 + 0x11 全量重推（1 行）。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 12, "skill", "CS_jh_sfgp04"));
        frames = env.drain();
        assertEquals("D A", dialect(frames), "替换 = 清空 + 全量重推");
        assertEquals(0, delRowNumber(frames.get(0)), "清空从 preCount-1=0 起");
        assertEquals(0x11, frames.get(1)[0]);
        var snapshot = env.session.player.shortcutSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals("CS_jh_sfgp04", snapshot.get(0).id());

        // 201 按可见索引删（客户端 remove_shortcut(index)）：0x12(行0) 清空，无行可重推。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 12));
        frames = env.drain();
        assertEquals("D", dialect(frames));
        assertEquals(0, delRowNumber(frames.get(0)));
        assertTrue(env.session.player.shortcutSnapshot().isEmpty());
        // 再删同索引静默；删不存在的索引也静默。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 12));
        assertNull(env.channel.readOutbound(), "缺行删除零帧");
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 0));
        assertNull(env.channel.readOutbound(), "未命中索引零帧");
    }

    /**
     * 替换后新行落表尾（服务端内存语义不变），帧走全量重建：preCount-1 递减到 0
     * 的 0x12 降序清空 + 0x11 全量重推。降序保证每个行号在删行收缩后仍有效。
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

        // 替换中间行 idx11：清空 2..0 + 重推 3 行，快照行序变为 [10, 12, 11]。
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 11, "skill", "CS_wd_tjq01"));
        List<byte[]> frames = env.drain();
        assertEquals("D D D A A A", dialect(frames), "3 行表变更 = 3 删 + 3 增");
        assertEquals(2, delRowNumber(frames.get(0)), "清空首帧 = preCount-1");
        assertEquals(1, delRowNumber(frames.get(1)));
        assertEquals(0, delRowNumber(frames.get(2)), "清空末帧 = 行0");
        List<PlayerActor.PlayerShortcut> snapshot = env.session.player.shortcutSnapshot();
        assertEquals(List.of(10, 12, 11),
                snapshot.stream().map(PlayerActor.PlayerShortcut::index).toList(),
                "替换行移表尾（追加语义）");
        assertEquals("CS_wd_tjq01", snapshot.get(2).id());

        // 删 idx12：清空 2..0 + 重推剩余 2 行。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 12));
        frames = env.drain();
        assertEquals("D D D A A", dialect(frames));
        assertEquals(2, delRowNumber(frames.get(0)));
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
        // put_other_short(self=10, other=11)，每步全量重建。
        env.flow.handleRemoveShortcut(connection, env.session, message(201, 10));
        env.flow.handleSetShortcut(connection, env.session, message(200, 10, "skill", "CS_jh_xfz02"));
        env.flow.handleRemoveShortcut(connection, env.session, message(201, 11));
        env.flow.handleSetShortcut(connection, env.session, message(200, 11, "skill", "CS_jh_xfz01"));

        List<byte[]> frames = env.drain();
        // 201(10)：清 2..0 + 推 2 行；200(10)：清 1..0 + 推 3 行；
        // 201(11)：清 2..0 + 推 2 行；200(11)：清 1..0 + 推 3 行。
        assertEquals("D D D A A D D A A A D D D A A D D A A A", dialect(frames),
                "交换四连 = 四次全量重建");

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

    /** 拖出释放（game_hand.lua gamehand_left_mouse_up）：201(索引) 清空重推去掉该栏。 */
    @Test
    void dragOutRemovesShortcutByIndex() {
        Env env = new Env();
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 20, "skill", "CS_wd_tjq01"));
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 21, "skill", "CS_wd_tjq02"));
        env.drain();

        // 按索引删 idx21：清 1..0 + 重推剩余 1 行（旧增量方言单帧 0x12 真机不收敛）。
        env.flow.handleRemoveShortcut(new Connection(env.channel), env.session, message(201, 21));
        List<byte[]> frames = env.drain();
        assertEquals("D D A", dialect(frames));
        assertEquals(1, delRowNumber(frames.get(0)), "清空首帧 = preCount-1");
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

    /**
     * item 快捷方式逐字透传（实例稳定 UID 语义，2026-11-06 定稿）：客户端上行的
     * UID 原样入库原样下发——UID 由物品行携带（入包生成/穿脱携带），与槽位无关，
     * 服务端无需任何翻译/重推。
     */
    @Test
    void itemShortcutUidPassthroughWithoutStore() {
        Env env = new Env();
        String uid = "4501131ef54b2063";
        env.flow.handleSetShortcut(new Connection(env.channel), env.session,
                message(200, 300, "item", uid));
        List<byte[]> frames = env.drain();
        assertEquals(1, frames.size());
        assertEquals(0x11, frames.get(0)[0]);
        assertEquals(uid, env.session.player.shortcutSnapshot().get(0).id(), "原样透传");
    }
}
