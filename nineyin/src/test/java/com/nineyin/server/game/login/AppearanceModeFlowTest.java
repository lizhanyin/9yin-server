package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 三套外观显示切换流程（V4）：custom 61（客户端 radio）与 GM
 * set_showequip_type 落 data/appearance.json + 推 0x10；皮肤挂载期间有效类型
 * 恒 2；越界类型拒绝；grant_identity_suits 无 inventory DB 明确失败。
 * LoginFlow 须在覆盖根之后构造（appearanceStore 的 data/appearance.json
 * 路径在构造期解析）。
 */
class AppearanceModeFlowTest {

    @TempDir
    Path tempRoot;

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private GmHub hub;
    private Path previousRoot;

    @BeforeEach
    void setUp() {
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        channel = new EmbeddedChannel();
        hub = new GmHub();
        session = new LoginSession();
        session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
        session.visual = new RoleVisual(0, "photo", "face", "hair",
                "cloth_base", "pants_base", "shoes_base", "0h");
        session.selected = roleSnapshot();
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.name = "李逍遥";
        hub.attach(snapshot.sessionId, snapshot);
        flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null);
    }

    @AfterEach
    void tearDown() {
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    private void switchType(int type) {
        CustomC2S.Message message = CustomC2S.parseDirect(
                CustomMessages.customIntMessage(61, CustomMessages.Value.ofInt32(type)));
        flow.handleShowEquipType(new Connection(channel), session, message);
    }

    @Test
    void custom61SwitchesPersistsAndPushesFrame() throws Exception {
        switchType(2);
        assertEquals(2, session.showEquipTypePref);
        assertEquals(2, LoginFlow.effectiveShowEquipType(session));
        assertTrue(Files.exists(tempRoot.resolve(Path.of("data", "appearance.json"))));
        byte[] frame = channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "切换推 0x10 外观属性帧");
        assertNull(channel.readOutbound(), "只推一帧");

        // 越界类型拒绝且不写帧不落档。
        switchType(3);
        assertEquals(2, session.showEquipTypePref, "越界值不改偏好");
        assertNull(channel.readOutbound());
    }

    @Test
    void effectiveTypePrefersMountedSkinOverPref() {
        switchType(1);
        assertEquals(1, LoginFlow.effectiveShowEquipType(session));
        // 挂载皮肤后有效类型恒 2（皮肤模型优先），卸载回落偏好。
        session.skinState = new com.nineyin.server.game.avatar.skin.SkinStore.State(
                List.of(1), List.of(1));
        assertEquals(2, LoginFlow.effectiveShowEquipType(session));
        session.skinState = new com.nineyin.server.game.avatar.skin.SkinStore.State(
                List.of(1), List.of());
        assertEquals(1, LoginFlow.effectiveShowEquipType(session), "卸载皮肤回落存档偏好");
    }

    @Test
    void gmSetShowEquipTypeAppliesWithReply() {
        GmCommand ok = new GmCommand(session.machine.id(), "set_showequip_type",
                new com.alibaba.fastjson2.JSONObject(Map.of("type", 1)));
        GmCommand bad = new GmCommand(session.machine.id(), "set_showequip_type",
                new com.alibaba.fastjson2.JSONObject(Map.of("type", 9)));
        hub.enqueue(ok);
        hub.enqueue(bad);
        flow.drainGmCommands(new Connection(channel), session);

        assertTrue(ok.awaitReply(java.time.Duration.ofMillis(1000)).ok());
        assertTrue(ok.awaitReply(java.time.Duration.ofMillis(1)).message().contains("身份装"));
        assertEquals(1, session.showEquipTypePref);
        assertTrue(channel.readOutbound() != null, "成功动作写帧");
        assertFalse(bad.awaitReply(java.time.Duration.ofMillis(1000)).ok(), "越界 type 失败");
        assertNull(channel.readOutbound(), "失败动作不写帧");
    }

    /** grant_identity_suits 无 inventory DB（含 fashion_suit.ini 目录缺失）明确失败。 */
    @Test
    void grantIdentitySuitsFailsWithoutInventoryStore() {
        GmCommand command = new GmCommand(session.machine.id(), "grant_identity_suits",
                new com.alibaba.fastjson2.JSONObject(Map.of()));
        hub.enqueue(command);
        flow.drainGmCommands(new Connection(channel), session);
        assertFalse(command.awaitReply(java.time.Duration.ofMillis(1000)).ok());
        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).message()
                .contains("物品 DB 未接入"));
        assertNull(channel.readOutbound());
    }

    private static com.nineyin.server.db.role.Roles.RoleSnapshot roleSnapshot() {
        return new com.nineyin.server.db.role.Roles.RoleSnapshot(
                618, 0, "李逍遥", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
    }
}
