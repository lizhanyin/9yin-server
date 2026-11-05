package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.wuxue.SkillLevelStore;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.session.SessionMachine;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.server.world.fight.CombatSkillCatalog;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 武学套路两动作：learn_all_taolu（已学全部升 varprop 满级 + 行帧刷
 * Level/MaxLevel/修为 + data/武学等级.json 落档重登保持 + CanUse 批按新级重建）、
 * forget_all_taolu（View40 整视图重开回登录基线集、清档、摘除失效快捷栏）。
 */
class LoginFlowTaoluTest {

    /** 本机 Go 参考仓根（share/skill 表源）；缺资源按用例跳过。 */
    private static final Path GO_SERVER_ROOT = Path.of(
            System.getenv().getOrDefault("GO_SERVER_ROOT",
                    "E:/Workspace/project/game/9yin-go-server1"));

    private static final long ROLE_ID = 1L;

    private static Path previousRoot;
    private static Path tempRoot;

    @BeforeAll
    static void prepareResourceRoot() throws IOException {
        Path skillSource = GO_SERVER_ROOT.resolve("resources/modern/share/skill");
        Assumptions.assumeTrue(Files.isDirectory(skillSource), "Go 仓 skill 表不可用，跳过");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        tempRoot = Files.createTempDirectory("nineyin-taolu-");
        Path skillTarget = tempRoot.resolve(Path.of("resources", "modern", "share", "skill"));
        copyTree(skillSource, skillTarget);
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
    }

    @AfterAll
    static void restoreServerRoot() throws IOException {
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
        if (tempRoot != null) {
            try (Stream<Path> walk = Files.walk(tempRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 清理失败不判失败（临时目录）。
                    }
                });
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path from : walk.toList()) {
                Path to = target.resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to);
                }
            }
        }
    }

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final GmHub hub = new GmHub();
        final LoginSession session = new LoginSession();
        final LoginFlowTest.ManualScheduler scheduler = new LoginFlowTest.ManualScheduler();
        final LoginFlow flow;
        final Connection connection;

        Env() {
            connection = new Connection(channel);
            session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
            session.visual = new RoleVisual(0, "icon\\players\\boy01_create.png",
                    "face", "obj\\char\\b_hair\\b_hair1", "cloth", "pants", "shoes", "action");
            session.selected = new Roles.RoleSnapshot(ROLE_ID, 0, "李逍遥", false, 7L, 1L,
                    new Roles.Appearance(1, List.of()), new Roles.Location(
                            new Roles.Scene("ini\\scene\\born04_QianDengZhen", "born04"),
                            new Roles.Position(377.371f, 20.269f, 1021.644f, 2.930f), 7L));
            session.world = new SceneLifecycle(connection, scheduler);
            session.world.begin("ini\\scene\\born04_QianDengZhen", "born04");
            assertNull(session.machine.loginCompleted(true));
            assertNull(session.machine.roleChosen());
            assertNull(session.machine.ready());
            assertNull(session.machine.activity());
            assertEquals(SessionMachine.Phase.IN_WORLD, session.machine.phase());
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, scheduler);
        }

        /** 三技种子：tjq01（满级 19）+ 非starter 技 sfgp01 + 打坐（满级 1 不动）。 */
        void seedLearnedSkills() {
            session.learnedSkills = new LinkedHashMap<>(Map.of(
                    "CS_wd_tjq01", 1, "CS_jh_sfgp01", 1, "zs_default_01", 1));
            session.skillRows = new LinkedHashMap<>(Map.of(
                    "CS_wd_tjq01", 1, "CS_jh_sfgp01", 2, "zs_default_01", 3));
        }

        GmCommand enqueue(String action) {
            GmCommand command = new GmCommand(session.machine.id(), action, new JSONObject());
            hub.enqueue(command);
            return command;
        }

        List<Integer> drainFrameOpcodes() {
            java.util.List<Integer> opcodes = new java.util.ArrayList<>();
            byte[] frame;
            while ((frame = channel.readOutbound()) != null) {
                opcodes.add(frame[0] & 0xFF);
            }
            return opcodes;
        }
    }

    @Test
    void learnAllTaoluMaxesLevelsAndPersists() throws IOException {
        Env env = new Env();
        Assumptions.assumeTrue(CombatSkillCatalog.load().maxLevelOf("CS_wd_tjq01") == 19,
                "tjq01 varprop 满级应为 19（skill_static 表形态不符则跳过）");
        env.seedLearnedSkills();

        GmCommand command = env.enqueue("learn_all_taolu");
        env.flow.drainGmCommands(env.connection, env.session);

        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).ok(), "学满应成功");
        assertEquals(19, env.session.learnedSkills.get("CS_wd_tjq01"), "tjq01 应升至 19");
        assertEquals(1, env.session.learnedSkills.get("zs_default_01"), "打坐满级 1 不动");
        // 行级 0x10 等级帧（level>1 的行才发；打坐不发）。
        List<Integer> opcodes = env.drainFrameOpcodes();
        long levelFrames = opcodes.stream().filter(op -> op == 0x10).count();
        assertTrue(levelFrames >= 1, "应至少为 tjq01 发一帧 0x10 等级行，实际=" + opcodes);
        // 落档：data/武学等级.json 含满级档值。
        Path store = tempRoot.resolve(Path.of("data", "武学等级.json"));
        assertTrue(Files.isRegularFile(store), "应写出武学等级档: " + store);
        JSONObject roles = com.alibaba.fastjson2.JSON.parseObject(
                Files.readString(store)).getJSONObject("roles");
        assertEquals(19, roles.getJSONObject(Long.toString(ROLE_ID))
                .getIntValue("CS_wd_tjq01"), "档值应为 19");
        // 登录合并口径：档值 >1 生效，未档技能回 1。
        Map<String, Integer> merged = env.flow.learnedSkillLevels(ROLE_ID, List.of(
                new RoleInventoryStore.Skill("CS_wd_tjq01", "wuxue", 81121, 1000),
                new RoleInventoryStore.Skill("zs_default_01", "wuxue", 22, 1000)));
        assertEquals(19, merged.get("CS_wd_tjq01"));
        assertEquals(1, merged.get("zs_default_01"));
        assertTrue(env.hub.get(env.session.machine.id()).lastAction.contains("门武学升至满级"));
    }

    @Test
    void learnAllTaoluFailsWithoutSkills() {
        Env env = new Env();
        env.session.learnedSkills = new LinkedHashMap<>();
        GmCommand command = env.enqueue("learn_all_taolu");
        env.flow.drainGmCommands(env.connection, env.session);
        assertFalse(command.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).message().contains("尚未学会"));
        assertNull(env.channel.readOutbound(), "失败命令不应写帧");
    }

    @Test
    void forgetAllTaoluResetsToStarterSetAndClearsArchive() throws IOException {
        Env env = new Env();
        Assumptions.assumeTrue(CombatSkillCatalog.load().maxLevelOf("CS_wd_tjq01") > 1,
                "skill 表形态不符则跳过");
        env.seedLearnedSkills();
        env.session.player.setShortcut(40, "skill", "CS_jh_sfgp01"); // 非starter技：遗忘后应摘除
        env.enqueue("learn_all_taolu");
        env.flow.drainGmCommands(env.connection, env.session);
        assertTrue(Files.readString(tempRoot.resolve(Path.of("data", "武学等级.json")))
                .contains("CS_wd_tjq01"), "前置：learn 应先落档");
        env.drainFrameOpcodes();

        GmCommand command = env.enqueue("forget_all_taolu");
        env.flow.drainGmCommands(env.connection, env.session);

        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).ok(), "遗忘应成功");
        assertTrue(command.awaitReply(java.time.Duration.ofMillis(1)).message()
                .contains("初始套路集"), command.awaitReply(java.time.Duration.ofMillis(1)).message());
        // starter 模式基线 = 完整 starter 集（五件套+花神+心佛+太极），全部 1 级。
        assertEquals(com.nineyin.server.game.starter.StarterSkills.starterSkillIds(),
                List.copyOf(env.session.learnedSkills.keySet()));
        assertTrue(env.session.learnedSkills.values().stream().allMatch(level -> level == 1),
                "遗忘后等级应全部回 1");
        assertEquals(env.session.learnedSkills.size(), env.session.skillRows.size());
        // View40 整视图重开：0x16 → 0x15 → 0x18×N → CanUse 批（0x10）。
        List<Integer> opcodes = env.drainFrameOpcodes();
        assertEquals(0x16, opcodes.get(0), "应先整视图删除");
        assertEquals(0x15, opcodes.get(1), "随后重建容器");
        assertEquals(env.session.learnedSkills.size(),
                opcodes.stream().filter(op -> op == 0x18).count(), "每技能一行 0x18");
        assertTrue(opcodes.stream().filter(op -> op == 0x10).count()
                        >= env.session.skillRows.size(),
                "CanUse 初始批应逐行推");
        // 档清空 + 失效快捷栏摘除（sfgp01 非 starter 集）。
        assertEquals(0, SkillLevelStore.open(
                        tempRoot.resolve(Path.of("data", "武学等级.json"))).level(ROLE_ID, "CS_wd_tjq01"),
                "遗忘应清档");
        assertTrue(env.session.player.shortcutSnapshot().stream()
                        .noneMatch(shortcut -> "CS_jh_sfgp01".equals(shortcut.id())),
                "指向已遗忘技能的快捷栏槽应摘除");
        assertTrue(env.hub.get(env.session.machine.id()).lastAction.contains("已删除全部武学"));
    }

    @Test
    void skillLevelStorePersistsOnlyRaisedLevels() throws IOException {
        Path path = tempRoot.resolve(Path.of("data", "武学等级-single.json"));
        SkillLevelStore store = SkillLevelStore.open(path);
        assertEquals(0, store.level(ROLE_ID, "CS_wd_tjq01"), "无档应为 0");
        store.apply(ROLE_ID, Map.of("CS_wd_tjq01", 19, "zs_default_01", 1));
        assertEquals(19, store.level(ROLE_ID, "CS_wd_tjq01"));
        assertEquals(0, store.level(ROLE_ID, "zs_default_01"), "1 级不落档（默认即 1）");
        // 重开（重启语义）：档值保留。
        SkillLevelStore reopened = SkillLevelStore.open(path);
        assertEquals(19, reopened.level(ROLE_ID, "CS_wd_tjq01"));
        reopened.clear(ROLE_ID);
        assertEquals(0, SkillLevelStore.open(path).level(ROLE_ID, "CS_wd_tjq01"), "清档后应回 0");
    }
}
