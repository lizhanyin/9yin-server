package com.nineyin.game;

import com.nineyin.db.JsonRoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PvP 结算链（第三批）：oid 区间判据、PK 门（tips_pkmode 语义）、
 * 玩家目标解析（同 world/存活过滤）、settleSelectedPlayer 伤害/招架/死亡全链
 * （双 EmbeddedChannel 裸会话 + 固定掷骰，照 AiEventEngineTest 确定性套路）。
 */
class PvpSettlementTest {

    @TempDir
    Path tempDir;

    private SceneFlow sceneFlow;
    private SceneManager sceneManager;
    private EmbeddedChannel chA;
    private EmbeddedChannel chB;
    private LoginSession sa;
    private LoginSession sb;
    private SceneFlow.SceneState stA;
    private SceneFlow.SceneState stB;
    private SceneWorld world;

    @BeforeEach
    void setup() throws Exception {
        Path share = tempDir.resolve("share");
        Files.createDirectories(share.resolve("creator/npc_creator/pvp_arena"));
        sceneManager = new SceneManager(share);
        sceneFlow = new SceneFlow(sceneManager, new JsonRoleStore(tempDir.resolve("roles.json")),
                new QinggongCatalog(), null, null, null, null);
        world = sceneManager.world("pvp_arena");

        chA = new EmbeddedChannel();
        chB = new EmbeddedChannel();
        FxSession fxA = FxSession.attach(chA);
        FxSession fxB = FxSession.attach(chB);
        sa = loginOf(fxA, "甲大侠", 500001);
        sb = loginOf(fxB, "乙大侠", 500002);
        stA = stateOf(chA);
        stB = stateOf(chB);
        sceneFlow.setSessionsSource(() -> List.of(fxA, fxB));
        sceneFlow.useCombatRngForTest(new Random(42));
    }

    private LoginSession loginOf(FxSession fx, String name, int oid) {
        LoginSession s = new LoginSession(fx);
        s.roleName = name;
        s.playerObjectId = oid;
        s.actor = new PlayerActor();
        s.pkMode = 1;   // 善恶模式（默认放行 PK 门）
        s.x = 100;
        s.y = 1;
        s.z = 100;
        fx.channel().attr(LoginFlow.SESSION).set(s);
        return s;
    }

    private SceneFlow.SceneState stateOf(EmbeddedChannel ch) {
        SceneFlow.SceneState st = new SceneFlow.SceneState();
        st.world = world;
        st.npcsSent = true;
        ch.attr(SceneFlow.STATE).set(st);
        return st;
    }

    /** 最小伤害技 Definition（单体 SELECTED、requiresTarget）。 */
    private static SkillCatalog.Definition atkDef() {
        return new SkillCatalog.Definition("test_pvp_atk", 900099, "SkillNormal", "NOSET", 1,
                71001, 0, 0, 0, 0, 0, 0, 113,
                true, SkillCatalog.TargetMode.SELECTED,
                0, 0, 0, 0, 8, 1,
                "zhaoshi_pvp", 1000, List.of(), List.of());
    }

    // ---------- oid 区间 ----------

    @Test
    void playerOidRange() {
        assertFalse(SceneFlow.isPlayerOid(500000));
        assertTrue(SceneFlow.isPlayerOid(500001));
        assertTrue(SceneFlow.isPlayerOid(900000));
        assertFalse(SceneFlow.isPlayerOid(900001));
        assertFalse(SceneFlow.isPlayerOid(1));
        assertFalse(SceneFlow.isPlayerOid(10001));
    }

    // ---------- PK 门（tips_pkmode 语义） ----------

    @Test
    void pvpGatePeaceModeRejected() {
        sa.pkMode = 0;
        String gate = SceneFlow.pvpGateMessage(sa, sb);
        assertNotNull(gate);
        assertTrue(gate.contains("和平"));
    }

    @Test
    void pvpGateVictimProtectionRejected() {
        sb.pkProtectOn = true;
        assertNotNull(SceneFlow.pvpGateMessage(sa, sb));
        sb.pkProtectOn = false;
    }

    @Test
    void pvpGateKillingModesPass() {
        sa.pkMode = 1;
        assertNull(SceneFlow.pvpGateMessage(sa, sb));
        sa.pkMode = 2;
        assertNull(SceneFlow.pvpGateMessage(sa, sb));
    }

    // ---------- 玩家目标解析 ----------

    @Test
    void resolvePlayerTargetMatchesOidWorldAndLiveness() {
        SceneFlow.PlayerTarget hit = sceneFlow.resolvePlayerTargetByOid(stA, 500002);
        assertNotNull(hit);
        assertEquals(sb, hit.session());

        // 异 world / 死亡 / 未知 oid → null
        SceneFlow.SceneState otherWorld = new SceneFlow.SceneState();
        otherWorld.world = sceneManager.world("another_scene");
        otherWorld.npcsSent = true;
        chB.attr(SceneFlow.STATE).set(otherWorld);
        assertNull(sceneFlow.resolvePlayerTargetByOid(stA, 500002));
        chB.attr(SceneFlow.STATE).set(stB);

        sb.actor.hp = 0;
        assertNull(sceneFlow.resolvePlayerTargetByOid(stA, 500002));
        sb.actor.hp = sb.actor.maxHp();

        assertNull(sceneFlow.resolvePlayerTargetByOid(stA, 999999));
    }

    // ---------- settleSelectedPlayer：伤害/帧/死亡 ----------

    @Test
    void settleDamagesVictimAndEmitsVitalsBothSides() {
        int hpBefore = sb.actor.hp;
        var summary = sceneFlow.settleSelectedPlayer(
                sa.fx, sa, stA, atkDef(), 500002, System.currentTimeMillis(), 1.0, 0, 1);
        if (summary.damage() > 0) {
            assertTrue(sb.actor.hp < hpBefore || sb.actor.hitHp > 0,
                    "伤害应进血量层或瘀血层");
        }
        // 受害者自机 vitalUpdate + 攻击者侧 peerCombatUpdate（双发模式）
        assertNotNull(chB.readOutbound(), "victim 应收到 vitalUpdate");
        assertNotNull(chA.readOutbound(), "attacker 应收到 peer 版生命帧");
    }

    @Test
    void settleLoopUntilDeathThenEmpty() {
        sb.actor.hp = 30;   // 低血量快速致死（固定种子序列确定）
        boolean died = false;
        for (int i = 0; i < 200 && !died; i++) {
            sceneFlow.settleSelectedPlayer(sa.fx, sa, stA, atkDef(), 500002,
                    System.currentTimeMillis(), 1.0, 0, 1);
            died = sb.actor.hp <= 0;
        }
        assertTrue(died, "固定掷骰下 200 次内应致死");
        // 死亡后波次空结算（resolvePlayerTargetByOid 存活过滤）
        var s = sceneFlow.settleSelectedPlayer(sa.fx, sa, stA, atkDef(), 500002,
                System.currentTimeMillis(), 1.0, 1, 1);
        assertEquals(0, s.damage());
        assertEquals(0, s.targets());
    }

    @Test
    void parryDrainsVictimEndurance() {
        sb.actor.parrying = true;
        sb.actor.parryEndurance = 500;
        int before = sb.actor.parryEndurance;
        for (int i = 0; i < 50 && sb.actor.parryEndurance == before; i++) {
            sceneFlow.settleSelectedPlayer(sa.fx, sa, stA, atkDef(), 500002,
                    System.currentTimeMillis(), 1.0, 0, 1);
        }
        assertTrue(sb.actor.parryEndurance < before, "招架命中应消耗耐力");
    }

    // ---------- 选中玩家：坐标回填 + 血条单播 ----------

    @Test
    void playerSelectionBackfillsCoordinates() {
        sb.x = 123.5f;
        sb.z = 321.5f;
        stA.selectedOid = 500002;
        stA.selectedX = 0;
        stA.selectedZ = 0;
        sceneFlow.onPlayerSelected(sa.fx, stA, 500002);
        assertEquals(123.5f, stA.selectedX, 1e-6);
        assertEquals(321.5f, stA.selectedZ, 1e-6);
        assertNotNull(chA.readOutbound(), "选中者应收到真实血条帧");
    }
}
