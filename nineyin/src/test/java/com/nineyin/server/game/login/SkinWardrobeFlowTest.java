package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.skin.SkinCatalog;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 风物志衣橱（custom 180）状态机测试：使用收录 → 挂载换装（同部件互斥、
 * 异部件叠加）→ 卸下；卡片形态（背包 CardItem）经 GM 发放后由 30/180 收录。
 * 资源来自参考服 share（card.ini 复制进临时根，SkinStore 落档不污染仓库）。
 */
class SkinWardrobeFlowTest {

    private static final Path GO_ROOT = Path.of("E:/Workspace/project/game/9yin-go-server1");

    @TempDir
    Path tempRoot;

    private EmbeddedChannel channel;
    private LoginSession session;
    private LoginFlow flow;
    private SkinCatalog catalog;
    private Path previousRoot;

    @BeforeEach
    void setUp() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(GO_ROOT.resolve(Path.of("resources", "modern", "share", "rule", "card.ini"))),
                "参考服 share 资源不可用，跳过衣橱状态机断言");
        Path ruleDir = tempRoot.resolve(Path.of("resources", "modern", "share", "rule"));
        Files.createDirectories(ruleDir);
        Files.copy(GO_ROOT.resolve(Path.of("resources", "modern", "share", "rule", "card.ini")),
                ruleDir.resolve("card.ini"));
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);

        channel = new EmbeddedChannel();
        session = new LoginSession();
        session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
        session.visual = com.nineyin.server.game.avatar.RoleVisual.resolve(
                java.util.List.of(), com.nineyin.server.game.avatar.equipment.EquipmentResolver.load(
                        tempRoot.resolve(Path.of("resources", "modern", "share", "item"))));
        session.selected = new com.nineyin.server.db.role.Roles.RoleSnapshot(
                618, 0, "李逍遥", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
        GmHub hub = new GmHub();
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.name = "李逍遥";
        hub.attach(snapshot.sessionId, snapshot);
        flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null);
        catalog = SkinCatalog.load(ruleDir);
    }

    @AfterEach
    void tearDown() {
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    private void wardrobe(int skinId) {
        CustomC2S.Message message = CustomC2S.parseDirect(
                CustomMessages.customIntMessage(180, CustomMessages.Value.ofInt32(skinId)));
        flow.handleWardrobeCard(new Connection(channel), session, message);
        channel.releaseOutbound(); // 帧方言由 SkinMessagesTest 覆盖，此处只验状态机
    }

    private List<Integer> mounted() {
        return session.skinState == null ? List.of() : session.skinState.mounted();
    }

    private List<Integer> collected() {
        return session.skinState == null ? List.of() : session.skinState.collected();
    }

    @Test
    void collectMountUnmountStateMachine() {
        // 未收录 → 收录（不自动挂载——衣橱内玩家自行换装）
        wardrobe(1);
        assertEquals(List.of(1), collected());
        assertEquals(List.of(), mounted());
        // 已收录未挂载 → 挂载
        wardrobe(1);
        assertEquals(List.of(1), mounted());
        // 已挂载 → 卸下
        wardrobe(1);
        assertEquals(List.of(), mounted());
        assertEquals(List.of(1), collected(), "卸下不清图鉴");
        // 存档落临时根（重登恢复源）
        assertTrue(Files.exists(tempRoot.resolve(Path.of("data", "skins.json"))));
    }

    @Test
    void samePartReplacedDifferentPartStacked() {
        // 皮肤 1=Weapon blade_card_001、2=Weapon sword_card_011（card.ini 钉死）。
        org.junit.jupiter.api.Assumptions.assumeTrue(catalog.byId(1) != null && catalog.byId(2) != null,
                "card.ini 首两节应为武器皮肤");
        assertTrue(catalog.byId(1).parts(0).contains("weapon"));
        assertTrue(catalog.byId(2).parts(0).contains("weapon"));

        wardrobe(1); // 收录
        wardrobe(1); // 挂载
        assertEquals(List.of(1), mounted());
        wardrobe(2); // 收录 2
        wardrobe(2); // 挂载 2：同部件（武器）互斥替换 1
        assertEquals(List.of(2), mounted(), "同部件（武器）换装互斥替换");

        // 发型卡（Hat 部件）叠加在武器皮肤之上（GM 面板「风物志头发」口径）。
        SkinCatalog.Skin hair = catalog.all().stream().filter(SkinCatalog.Skin::hairOnly).findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(hair != null, "目录无纯发型卡，跳过叠加断言");
        wardrobe(hair.id()); // 收录
        wardrobe(hair.id()); // 挂载：异部件叠加
        assertEquals(List.of(2, hair.id()), mounted(), "异部件（武器+发型）叠加");
        assertTrue(mounted().contains(2), "已挂武器外形不被发型卡替换");

        // 再挂另一把武器：替换 2，发型保留。
        SkinCatalog.Skin otherWeapon = catalog.all().stream()
                .filter(s -> !s.hairOnly() && s.parts(0).contains("weapon") && s.id() != 2)
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(otherWeapon != null, "目录无第三把武器卡，跳过");
        wardrobe(otherWeapon.id()); // 收录
        wardrobe(otherWeapon.id()); // 挂载：替换武器位
        assertFalse(mounted().contains(2), "武器位被新武器替换");
        assertTrue(mounted().contains(hair.id()), "发型位保留");
    }

    @Test
    void unknownSkinIgnored() {
        wardrobe(999_999);
        assertEquals(List.of(), collected());
        assertEquals(List.of(), mounted());
    }
}
