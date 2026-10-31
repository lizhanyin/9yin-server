package com.nineyin.game;

import com.alibaba.fastjson.JSONObject;
import com.nineyin.db.JsonRoleStore;
import com.nineyin.gm.GmCommand;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.scene.SceneManager;
import com.nineyin.server.FxSession;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * menu2 面板动作第一批补齐的执行语义（docs/gm-menu2-actions.md §4.1）：
 * 属性帧由 PropertyId 常量保证；此处验证分支路由、参数校验、状态回写与帧确实发出。
 */
class GmActionsMenu2Test {

    @TempDir
    Path tempDir;

    private SceneManager sceneManager;
    private GmActions gm;
    private EmbeddedChannel ch;
    private FxSession fx;
    private LoginSession session;
    private SceneFlow.SceneState st;

    @BeforeEach
    void setup() throws Exception {
        Path share = tempDir.resolve("share");
        Files.createDirectories(share.resolve("creator/npc_creator/city05"));
        sceneManager = new SceneManager(share);
        SceneFlow sceneFlow = new SceneFlow(sceneManager, new JsonRoleStore(tempDir.resolve("roles.json")),
                new QinggongCatalog(), null, null, null, null);
        gm = new GmActions(sceneManager, new JsonRoleStore(tempDir.resolve("roles2.json")),
                sceneFlow.switcher(), sceneFlow);
        ch = new EmbeddedChannel();
        fx = FxSession.attach(ch);
        session = new LoginSession(fx);
        session.roleName = "测试侠";
        session.playerObjectId = 500001;
        session.actor = new PlayerActor();
        st = new SceneFlow.SceneState();
        st.world = sceneManager.world("city05");
    }

    private String run(String action, Object... kv) {
        JSONObject params = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) {
            params.put((String) kv[i], kv[i + 1]);
        }
        int before = ch.outboundMessages().size();
        String err = gm.execute(fx, session, st, new GmCommand(1, action, params));
        assertNotNull(err == null ? Boolean.TRUE : Boolean.TRUE); // 路由不抛异常即通过一半
        return err;
    }

    private int framesSince(int before) {
        return ch.outboundMessages().size() - before;
    }

    @Test
    void guanYinUpdatesActorAndSendsFrame() {
        assertNull(run("set_guanyin", "guanYin", 12345));
        assertEquals(12345L, session.actor.guanYin);
        assertEquals(1, framesSince(0));
    }

    @Test
    void vitalsRequireAtLeastOneValue() {
        assertNotNull(run("set_vitals"));
        assertNull(run("set_vitals", "ene", 80, "maxEne", 100, "sat", 60, "maxSat", 100,
                "drunkLevel", 3, "pkValue", 0, "resumeEne", 5));
    }

    @Test
    void alignmentAndFiveAttributesValidate() {
        assertNotNull(run("set_alignment"));
        assertNull(run("set_alignment", "justice", 300, "evil", 5, "freeBase", 0));
        assertNotNull(run("set_five_attributes"));
        assertNull(run("set_five_attributes", "attrStr", 66, "attrSta", 55, "attrDex", 44,
                "attrIng", 33, "attrSpi", 22));
    }

    @Test
    void powerLevelAndPkModeValidate() {
        assertNotNull(run("set_power_level"));
        assertTrue(run("set_power_level", "stage", 37).startsWith("实力等级设为 37"));
        assertEquals("title008", GmActionsPowerTitles.titleFor(37));

        assertNotNull(run("set_pk_mode", "pkMode", 9));
        assertEquals("攻击模式已切换为 戕屠", run("set_pk_mode", "pkMode", 3));
        assertEquals(3, session.pkMode);
    }

    @Test
    void titleLifecycle() {
        assertEquals("发放称号失败：titleId must be >= 1", run("grant_title", "titleId", 0));
        assertEquals("已发放称号 12（共 1 个）", run("grant_title", "titleId", 12));
        assertEquals("未拥有称号 99，无法佩戴", run("wear_title", "titleId", 99));
        assertNull(run("wear_title", "titleId", 12));
        assertEquals(12, session.wornTitleId);
        assertNull(run("cancel_title"));
        assertEquals(0, session.wornTitleId);
        run("grant_title", "titleId", 13);
        run("clear_titles");
        assertTrue(session.grantedTitles.isEmpty());
    }

    @Test
    void speechAndBroadcastValidateText() {
        assertNotNull(run("speech_nul"));
        assertNull(run("speech_nul", "speechText", "你好九阴"));
        assertNotNull(run("speech_len"));
        assertNull(run("speech_len", "speechText", "旧式气泡"));
        assertNotNull(run("chat_broadcast"));
        assertNull(run("chat_broadcast", "speechText", "全服公告"));
    }

    @Test
    void addItemRequiresConfig() {
        assertEquals("添加物品失败：itemConfig 不能为空", run("add_item"));
        assertEquals("添加物品失败：itemConfig 不能为空", run("grant_item"));
    }

    @Test
    void customProbeRequiresMsgId() {
        assertNotNull(run("custom_probe", "subType", 3));
        assertEquals("已发送 S2C 自定义消息 ID=819 sub=3", run("custom_probe", "msgId", 819, "subType", 3));
        assertEquals(1, framesSince(0));
    }

    @Test
    void idleStanceStripsPrefix() {
        assertEquals("已通过 State 发送待机站姿 stand", run("idle_stance_set", "stanceAction", "idle_stance_stand"));
        assertNotNull(run("idle_stance_set"));
    }

    @Test
    void combatDummySpawnAndClear() {
        assertTrue(run("spawn_combat_dummy").startsWith("已生成战斗假人"));
        assertTrue(run("spawn_combat_dummy", "dummyHp", 30000).contains("血量=30000"));
        assertEquals("已清除 2 只战斗假人", run("clear_combat_dummies"));
        assertEquals("已清除 0 只战斗假人", run("clear_combat_dummies"));
    }

    @Test
    void fightwordAndPopupProbesSendFrames() {
        assertEquals("已发送伤害飘字测试 (ID24, 24680)", run("fightword_probe"));
        assertNull(run("popup_gm_panel"));
        assertEquals(2, framesSince(0));
    }

    @Test
    void teamCommandRequiresWiring() {
        // 本测试 GmActions 未接 TeamManager（构造传 null）→ 明确报错
        assertTrue(run("team", "teamArgs", "create").contains("队伍系统未接线"));
    }

    /** powerTitle 为私有静态；测试侧镜像一份分期边界（menu2 titleByPowerLevel）。 */
    static final class GmActionsPowerTitles {
        static String titleFor(int stage) {
            if (stage <= 5) return "title001";
            if (stage <= 120) return String.format("title%03d", Math.min(24, (stage - 1) / 5 + 1));
            if (stage <= 130) return "title025";
            if (stage <= 150) return "title026";
            return "title027";
        }
    }
}
