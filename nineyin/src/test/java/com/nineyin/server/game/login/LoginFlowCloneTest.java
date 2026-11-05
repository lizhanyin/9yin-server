package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.session.SessionMachine;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.fight.FightState;
import com.nineyin.server.world.npc.NpcResolver;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.scene.NpcViewport;
import com.nineyin.server.world.scene.SceneLifecycle;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 副本三动作与波次链：enter_clone（cloneinfo 校验 + 0x0C 重入链 + CloneRun
 * 会话实例）、reenter/destroy 状态机；波次门控按 NpcProgress 累积揭示
 * （≤当前波可见、无波次恒可见）、副本目录击杀不复活（killOverride 认领）、
 * 当前波战斗怪全灭自动推进（未物化怪视为存活阻挡）。
 */
class LoginFlowCloneTest {

    /** 本机 Go 参考仓根（share 资源；cloneinfo/目录可选，缺资源按用例跳过）。 */
    private static final Path GO_SERVER_ROOT = Path.of(
            System.getenv().getOrDefault("GO_SERVER_ROOT",
                    "E:/Workspace/project/game/9yin-go-server1"));

    private static final String CLONE003 = "ini\\scene\\clone003";

    /** 进本链需要 cloneinfo/creator 资源：本类运行期间借用 Go 仓根，结束后还原（防类顺序干扰后续用例的 ServerPaths 视角）。 */
    private static Path previousRoot;

    @BeforeAll
    static void useGoResourcesWhenPresent() {
        if (Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/clone"))) {
            previousRoot = com.nineyin.util.ServerPaths.serverRoot();
            // 先按 LuaFormulaParityTest 同款项目根预热 LuaScriptService 单例：
            // 重入收尾链会触发懒初始化，若发生在借用的 Go 仓根下（无 formula.lua）
            // 会把 fallback 态带进同 JVM 的后续 script 用例；natives 二次重建不可行。
            com.nineyin.util.ServerPaths.overrideServerRoot(
                    Path.of("").toAbsolutePath().normalize().getParent());
            com.nineyin.server.script.LuaScriptService.getInstance();
            com.nineyin.util.ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
        }
    }

    @AfterAll
    static void restoreServerRoot() throws Exception {
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
        // 借用根下的目录编译会给 LuaScriptService 单例缓存打进“无脚本”NONE 哨兵
        //（缓存不按 root 分键），后续 script 用例会拿到错误覆盖——清空待其按
        // 自己的 root 重读（同 SceneSwitchTest 反射注入 inventoryStore 先例）。
        com.nineyin.server.script.LuaScriptService service =
                com.nineyin.server.script.LuaScriptService.getInstance();
        for (String cache : new String[]{"overlayCache", "itemUseCache"}) {
            java.lang.reflect.Field field =
                    com.nineyin.server.script.LuaScriptService.class.getDeclaredField(cache);
            field.setAccessible(true);
            ((java.util.Map<?, ?>) field.get(service)).clear();
        }
    }

    private static boolean cloneInfoAvailable() {
        return Files.isRegularFile(
                GO_SERVER_ROOT.resolve("resources/modern/share/clone/cloneinfo.ini"));
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
            session.selected = new Roles.RoleSnapshot(1, 0, "李逍遥", false, 7L, 1L,
                    new Roles.Appearance(1, List.of()), born04Location(7L));
            session.world = new SceneLifecycle(connection, scheduler);
            session.world.begin("ini\\scene\\born04_QianDengZhen", "born04");
            toInWorld(session.machine);
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, scheduler);
        }

        GmCommand enqueue(String action, Map<String, Object> params) {
            GmCommand command = new GmCommand(session.machine.id(), action, new JSONObject(params));
            hub.enqueue(command);
            return command;
        }

        byte[] readFrame() {
            return channel.readOutbound();
        }

        /** 跑完 750ms 延迟帧任务 + 目标场景 0x09 收尾（相位回 InWorld）。 */
        void completeReentry() {
            scheduler.tasks.remove(0).run();
            assertTrue(flow.dispatch(connection, session, new byte[]{0x09}), "目标场景 0x09 收尾应成功");
            assertEquals(SessionMachine.Phase.IN_WORLD, session.machine.phase());
            while (readFrame() != null) {
                // 排空收尾帧
            }
        }
    }

    private static Roles.Location born04Location(long version) {
        return new Roles.Location(new Roles.Scene("ini\\scene\\born04_QianDengZhen", "born04"),
                new Roles.Position(377.371f, 20.269f, 1021.644f, 2.930f), version);
    }

    private static void toInWorld(SessionMachine machine) {
        assertNull(machine.loginCompleted(true));
        assertNull(machine.roleChosen());
        assertNull(machine.ready());
        assertNull(machine.activity());
        assertEquals(SessionMachine.Phase.IN_WORLD, machine.phase());
    }

    /** 合成目录摆位：properties 只带战斗物化需要的 MaxHP/HP/Resource。 */
    private static NpcSpawn spawn(String configId, String scriptClass, String progress,
                                  float x, float z) {
        NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                "clone003/Test.xml#" + configId, configId, scriptClass, null);
        resolved.properties.put("MaxHP", PropertyValues.Value.ofInt32(100));
        resolved.properties.put("HP", PropertyValues.Value.ofInt32(100));
        resolved.properties.put("Resource", PropertyValues.Value.ofString("monv000000"));
        if (progress != null) {
            resolved.extensions.put("creator.NpcProgress", progress);
        }
        return new NpcSpawn(resolved, x, 10f, z, 0f);
    }

    private static LoginSession.CloneRun cloneRun(int maxWave, Roles.Scene scene) {
        return new LoginSession.CloneRun(scene, "暮色之村", 2, maxWave,
                born04Location(7L), new Roles.Position(0f, 10f, 0f, 0f));
    }

    @Test
    void cloneCommandsValidateStateAndConfig() {
        Env env = new Env();
        GmCommand unknown = env.enqueue("enter_clone",
                Map.of("cloneConfig", "ini\\scene\\nonesuch", "value", 1));
        GmCommand reenter = env.enqueue("reenter_clone", Map.of());
        GmCommand destroy = env.enqueue("destroy_clone", Map.of());

        env.flow.drainGmCommands(env.connection, env.session);

        assertFalse(unknown.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(unknown.awaitReply(java.time.Duration.ofMillis(1)).message().contains("未知副本"));
        assertFalse(reenter.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(reenter.awaitReply(java.time.Duration.ofMillis(1)).message().contains("没有已打开的副本"));
        assertFalse(destroy.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertNull(env.readFrame(), "失败命令不应写帧");

        // 已开实例时二次 enter 拒绝（防覆盖回城点快照）。
        env.session.cloneRun = cloneRun(1, new Roles.Scene(CLONE003, "clone003"));
        GmCommand dup = env.enqueue("enter_clone", Map.of("cloneConfig", CLONE003, "value", 1));
        env.flow.drainGmCommands(env.connection, env.session);
        assertFalse(dup.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(dup.awaitReply(java.time.Duration.ofMillis(1)).message().contains("已有打开的副本"));
    }

    @Test
    void enterCloneSwitchesAndDestroyReturnsToSnapshot() {
        org.junit.jupiter.api.Assumptions.assumeTrue(cloneInfoAvailable(),
                "cloneinfo.ini 不可用，跳过");
        Env env = new Env();
        GmCommand enter = env.enqueue("enter_clone",
                Map.of("cloneConfig", CLONE003, "value", 2));

        env.flow.drainGmCommands(env.connection, env.session);

        assertTrue(enter.awaitReply(java.time.Duration.ofMillis(1)).ok(), "进本应成功");
        assertTrue(enter.awaitReply(java.time.Duration.ofMillis(1)).message()
                .contains("副本「暮色之村」"), enter.awaitReply(java.time.Duration.ofMillis(1)).message());
        byte[] exitFrame = env.readFrame();
        assertNotNull(exitFrame, "应写出 0x0C");
        assertEquals(0x0C, exitFrame[0] & 0xFF);
        LoginSession.CloneRun run = env.session.cloneRun;
        assertNotNull(run, "应建立会话副本实例");
        assertEquals(1, run.wave);
        assertEquals(2, run.difficulty);
        assertEquals("ini\\scene\\born04_QianDengZhen", run.returnLocation.scene().config(),
                "回城点应快照进本前场景");
        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertEquals(CLONE003, snapshot.sceneConfig);
        assertEquals("clone003", snapshot.sceneResource);
        assertTrue(snapshot.lastAction.contains("今日第 1 次进入"), snapshot.lastAction);

        env.completeReentry();

        // reenter：已在副本场景 → 直接回报进度，不切图不写帧。
        GmCommand reenter = env.enqueue("reenter_clone", Map.of());
        env.flow.drainGmCommands(env.connection, env.session);
        assertTrue(reenter.awaitReply(java.time.Duration.ofMillis(1)).ok());
        assertTrue(reenter.awaitReply(java.time.Duration.ofMillis(1)).message().contains("已在"));
        assertNull(env.readFrame(), "已在副本时重进不应写帧");

        // destroy：回 born04 快照点并清实例。
        GmCommand destroy = env.enqueue("destroy_clone", Map.of());
        env.flow.drainGmCommands(env.connection, env.session);
        assertTrue(destroy.awaitReply(java.time.Duration.ofMillis(1)).ok(), "销毁应成功");
        assertNull(env.session.cloneRun, "销毁后实例应清空");
        byte[] backFrame = env.readFrame();
        assertNotNull(backFrame);
        assertEquals(0x0C, backFrame[0] & 0xFF);
        snapshot = env.hub.get(env.session.machine.id());
        assertEquals("born04", snapshot.sceneResource);
        assertTrue(snapshot.lastAction.contains("已销毁"), snapshot.lastAction);
    }

    @Test
    void waveGateRevealsCumulativelyAndAdvancesOnWaveCleared() {
        Env env = new Env();
        Roles.Scene scene = new Roles.Scene(CLONE003, "clone003");
        env.session.world.begin(scene.config(), scene.resource());
        // 目录：0=波1战斗(近) 1=波1战斗(近，待找齐) 2=波2战斗 3=波2物件 4=无波次Boss。
        List<NpcSpawn> catalog = List.of(
                spawn("mobA", "attacknpc", "1", 0f, 0f),
                spawn("mobB", "attacknpc", "1", 1f, 0f),
                spawn("wave2Mob", "attacknpc", "2", 2f, 0f),
                spawn("wave2Box", "commonnpc", "2", 3f, 0f),
                spawn("boss", "bossnpc", null, 4f, 0f));
        env.session.sceneNpcs = catalog;
        env.session.fight = new FightState();
        env.session.cloneRun = cloneRun(2, scene);
        Roles.Position origin = new Roles.Position(0f, 10f, 0f, 0f);

        NpcViewport.synchronizeNpcViewport(env.session.world, catalog,
                env.session.activeNpcs, origin, 500f, env.flow.cloneWaveGate(env.session));
        assertEquals(Set.of(0, 1, 4), env.session.activeNpcs,
                "波1 + 无波次可见；波2 战斗/物件均不上线");

        // 击杀 mobA（对象号=下标+2）：认领不复活；但 mobB 未物化 → 波不动。
        assertTrue(env.session.fight.selectTarget(env.session.world, 2, 1), "物化 mobA");
        FightState.NpcCombat mobA = env.session.fight.livingSelectedTarget(env.session.world).combat();
        assertTrue(env.session.fight.applyDamage(mobA, 9999, Instant.now()).killed());
        assertTrue(env.flow.claimCloneKill(env.session, 2), "副本目录击杀应被认领（不复活）");
        assertEquals(1, env.session.cloneRun.wave, "同波还有未物化怪时不得推进");

        // 物化并击杀 mobB → 波1 全灭 → 推进波2（战斗怪+物件上线，尸体保留）。
        assertTrue(env.session.fight.selectTarget(env.session.world, 3, 1), "物化 mobB");
        FightState.NpcCombat mobB = env.session.fight.livingSelectedTarget(env.session.world).combat();
        env.session.fight.applyDamage(mobB, 9999, Instant.now());
        assertTrue(env.flow.claimCloneKill(env.session, 3));
        assertEquals(2, env.session.cloneRun.wave, "波1 全灭应推进到波2");
        assertEquals(Set.of(0, 1, 2, 3, 4), env.session.activeNpcs, "波2 摆位应上线（累积揭示）");
        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertTrue(snapshot.lastAction.contains("第 2/2 波"), snapshot.lastAction);

        // 击杀波2战斗怪 → 最终波全灭 → 通关标记（波次不再推进）。
        assertTrue(env.session.fight.selectTarget(env.session.world, 4, 1), "物化 wave2Mob");
        FightState.NpcCombat wave2Mob = env.session.fight.livingSelectedTarget(env.session.world).combat();
        env.session.fight.applyDamage(wave2Mob, 9999, Instant.now());
        assertTrue(env.flow.claimCloneKill(env.session, 4));
        assertTrue(env.session.cloneRun.cleared, "最终波全灭应标记通关");
        assertEquals(2, env.session.cloneRun.wave);
        snapshot = env.hub.get(env.session.machine.id());
        assertTrue(snapshot.lastAction.contains("最终波已全灭"), snapshot.lastAction);
    }

    @Test
    void killClaimRequiresCloneSceneAndCatalogRange() {
        Env env = new Env();
        Roles.Scene scene = new Roles.Scene(CLONE003, "clone003");
        env.session.sceneNpcs = List.of(spawn("mobA", "attacknpc", "1", 0f, 0f));
        env.session.fight = new FightState();
        // 实例在、但当前世界仍是 born04 → 不认领（复活走常规 15s）。
        env.session.cloneRun = cloneRun(1, scene);
        assertFalse(env.flow.claimCloneKill(env.session, 2), "不在副本场景不应认领");

        // 在副本场景但对象号越出目录小号段（GM 摆放 900000+）→ 不认领。
        env.session.world.begin(scene.config(), scene.resource());
        assertFalse(env.flow.claimCloneKill(env.session, 900_000), "GM 摆放不应认领");
        // 门控对非副本会话恒真（5 参旧签名等价）。
        env.session.cloneRun = null;
        assertTrue(env.flow.cloneWaveGate(env.session).test(env.session.sceneNpcs.get(0)),
                "无实例时门控应恒真");
    }
}
