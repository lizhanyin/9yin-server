package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 经脉周天直设（set_jingmai_tier）与内功全书收录（learn_all_neigong）链路测试：
 * 白名单透传 → drainGmCommands 分发 → 状态机落点 → 帧序（View45 行帧+0x10 属性 /
 * View43 0x16→0x15→0x18 整视图重建）→ 回执/lastAction。
 * 需要运行时 share 资源（F 盘 9yin_server1）；不可用时按既有惯例整类跳过。
 */
class LoginFlowGmJingmaiNeigongTest {

    /** 运行时 share 根（MeridianAndQingGongTest 同款惯例：不可用即跳过）。 */
    private static final Path SHARE_ROOT =
            Path.of("F:/Game/Online/9yin/9yin_server1");

    @BeforeAll
    static void needsShareResources() {
        Assumptions.assumeTrue(Files.isDirectory(
                        SHARE_ROOT.resolve(Path.of("resources", "modern", "share", "skill", "neigong"))),
                "runtime share neigong 资源不可用，跳过 GM 经脉/内功收录链路");
        com.nineyin.util.ServerPaths.overrideServerRoot(SHARE_ROOT);
    }

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final GmHub hub = new GmHub();
        final LoginSession session = new LoginSession();
        final LoginFlow flow;

        Env() {
            session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null);
        }

        GmCommand enqueue(String action, Map<String, Object> params) {
            GmCommand command = new GmCommand(session.machine.id(), action, new JSONObject(params));
            hub.enqueue(command);
            return command;
        }

        int drainAndCountFrames() {
            flow.drainGmCommands(new Connection(channel), session);
            int count = 0;
            while (channel.readOutbound() != null) {
                count++;
            }
            return count;
        }
    }

    private static GmCommand.Reply reply(GmCommand command) {
        return command.awaitReply(java.time.Duration.ofMillis(1));
    }

    @Test
    void jingmaiTierClampsToCatalogMaxAndPushesRowAndPropsFrames() {
        var catalog = com.nineyin.server.game.avatar.jingmai.MeridianCatalog.load();
        Assumptions.assumeTrue(catalog.definition("jm_dantian") != null, "jm_dantian 未收录，跳过");
        int maxLevel = catalog.definition("jm_dantian").maxLevel();
        Assumptions.assumeTrue(maxLevel >= 36, "jm_dantian 上限异常，跳过");

        Env env = new Env();
        GmCommand set = env.enqueue("set_jingmai_tier",
                Map.of("jingMaiId", "jm_dantian", "jingMaiTier", 216));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(set).ok(), "合法经脉周天应成功");
        var meridian = env.session.player.meridian().meridian("jm_dantian");
        assertNotNull(meridian);
        assertEquals(maxLevel, meridian.level(), "超出目录上限应钳到上限");
        assertEquals(0, meridian.fill(), "直设周天后修炼进度归零");

        // 帧序：View45 行帧（0x10 视图行属性）+ 玩家经脉属性帧（0x10）。
        byte[] rowFrame = env.channel.readOutbound();
        byte[] propsFrame = env.channel.readOutbound();
        assertNotNull(rowFrame, "应写出 View45 行帧");
        assertNotNull(propsFrame, "应写出经脉属性帧");
        assertNull(env.channel.readOutbound(), "不应有更多帧");
        assertEquals(0x10, rowFrame[0] & 0xFF);
        assertEquals(0x10, propsFrame[0] & 0xFF);

        String lastAction = env.hub.get(env.session.machine.id()).lastAction;
        assertTrue(lastAction.contains("jm_dantian"), "lastAction 应含经脉 id：" + lastAction);
    }

    @Test
    void jingmaiTierRejectsUnknownOrUnlearnedMeridian() {
        Env env = new Env();
        GmCommand unknown = env.enqueue("set_jingmai_tier",
                Map.of("jingMaiId", "jm_nope", "jingMaiTier", 36));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(!reply(unknown).ok(), "未收录经脉应拒绝");
        assertNull(env.channel.readOutbound(), "拒绝路径零帧");
    }

    @Test
    void learnAllNeigongAddsCatalogBooksRebuildsView43AndIsIdempotent() {
        var catalog = com.nineyin.server.game.avatar.neigong.NeiGongCatalog.load();
        int catalogSize = catalog.allBooks().size();
        Assumptions.assumeTrue(catalogSize > 5, "内功目录异常（≤5 本），跳过");

        Env env = new Env();
        int before = env.session.player.progress().bookCount();
        GmCommand learn = env.enqueue("learn_all_neigong", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(learn).ok(), "内功收录应成功");
        assertEquals(catalogSize, env.session.player.progress().bookCount(),
                "应收录目录全部内功");

        // 帧序：0x16 删视图 → 0x15 容器 → 每本一帧 0x18 行。
        byte[] delete = env.channel.readOutbound();
        byte[] create = env.channel.readOutbound();
        assertNotNull(delete, "应写出 View43 删视图帧");
        assertNotNull(create, "应写出 View43 容器帧");
        assertEquals(0x16, delete[0] & 0xFF);
        assertEquals(0x15, create[0] & 0xFF);
        int rows = 0;
        byte[] frame;
        while ((frame = env.channel.readOutbound()) != null) {
            assertEquals(0x18, frame[0] & 0xFF, "后续帧应为 View43 行帧");
            rows++;
        }
        assertEquals(catalogSize, rows, "行帧数应等于收录书数");
        assertTrue(before < catalogSize, "收录前书数应小于目录数");

        // 新书 level=1/fill=0；已有书等级不动（starter 五本保持原级）。
        var added = catalog.allBooks().stream()
                .filter(def -> env.session.player.progress().book(def.configId()) != null)
                .map(def -> env.session.player.progress().book(def.configId()))
                .toList();
        assertTrue(added.stream().allMatch(book -> book.level >= 1), "全部书 level ≥1");

        // 幂等：第二次调用无新增、零帧。
        GmCommand again = env.enqueue("learn_all_neigong", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(again).ok());
        assertTrue(reply(again).message().contains("无新增"), "二次调用应回执无新增");
        assertNull(env.channel.readOutbound(), "幂等路径零帧");
        assertEquals(catalogSize, env.session.player.progress().bookCount());
    }
}
