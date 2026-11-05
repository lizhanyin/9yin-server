package com.nineyin.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.script.LuaScriptService.NeiGongCtx;
import com.nineyin.server.script.LuaScriptService.NeiGongResult;
import com.nineyin.util.ServerPaths;

/**
 * scripts/neigong/&lt;buffId&gt;.lua 运行期钩子数据桥：四个锚点脚本（附伤+互斥 state /
 * 受击护盾 / 周期门 / 招架档位门）走真实 Lua55 引擎；无脚本 → null（战斗链静默降级）。
 */
class LuaNeigongHookTest {

    @BeforeAll
    static void pointScriptsAtRepoRoot() {
        // 同 LuaItemUseTest：surefire cwd=nineyin 模块目录，scripts/ 在仓库根。
        ServerPaths.overrideServerRoot(
                Path.of("").toAbsolutePath().normalize().getParent());
        LuaScriptService.getInstance().reload();
    }

    private static NeiGongCtx ctx(int bookLevel, int bookMax, int stage, long now,
                                  double rProc, Map<String, Object> state) {
        return ctx(bookLevel, bookMax, stage, now, rProc, state, false, -1);
    }

    private static NeiGongCtx ctx(int bookLevel, int bookMax, int stage, long now,
                                  double rProc, Map<String, Object> state,
                                  boolean parried, long sinceLastMs) {
        return new NeiGongCtx(bookLevel, bookMax, stage, now, rProc, state,
                0, 0, parried, false, sinceLastMs,
                5000, 10000, 3000, 6000, 150,
                2, 5000, 10000, 1);
    }

    private static LuaScriptService service() {
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");
        return service;
    }

    @Test
    void bh001AttackProcAndMutexStateWindow() {
        LuaScriptService service = service();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "neigong", "buf_ng_bh_001.lua")),
                "buf_ng_bh_001 anchor script missing");
        assertTrue(service.neigongHasHook("buf_ng_bh_001", "on_attack"));
        assertTrue(service.neigongHasHook("buf_ng_bh_001", "on_victim"));
        assertFalse(service.neigongHasHook("buf_ng_bh_001", "on_parry"));

        // 层1（book 1/7）：proc 掷中 → 附伤 30 + 天时窗口 state
        NeiGongResult proc = service.neigongHook("buf_ng_bh_001", "on_attack",
                ctx(1, 7, 1, 100_000, 0.05, Map.of()));
        assertNotNull(proc);
        assertEquals(30, proc.extraDamage());
        assertEquals("yang", proc.extraDamageKind());
        assertNotNull(proc.state());
        assertEquals("tianshi", proc.state().get("mode"));
        assertEquals(106_000L, proc.state().get("until_at"));

        // 窗口内再打：不掷 proc（r_proc=0.99 必不中）也稳定附伤（state 回喂）
        NeiGongResult window = service.neigongHook("buf_ng_bh_001", "on_attack",
                ctx(1, 7, 1, 102_000, 0.99, proc.state()));
        assertNotNull(window);
        assertEquals(30, window.extraDamage());

        // 地利互斥：state.mode=dili 未过期 → on_attack 静默
        NeiGongResult mutex = service.neigongHook("buf_ng_bh_001", "on_attack",
                ctx(1, 7, 1, 102_000, 0.01, Map.of("mode", "dili", "until_at", 105_000L)));
        assertNull(mutex);
    }

    @Test
    void wd001VictimProcShield() {
        LuaScriptService service = service();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "neigong", "buf_ng_wd_001.lua")),
                "buf_ng_wd_001 anchor script missing");
        // 层3（book 3/4）：10% proc 掷中 → 吸收 210 点 / 12s
        NeiGongResult proc = service.neigongHook("buf_ng_wd_001", "on_victim",
                ctx(3, 4, 1, 100_000, 0.09, Map.of()));
        assertNotNull(proc);
        assertEquals(210, proc.shieldAmount());
        assertEquals(12_000L, proc.shieldLifeMs());
        // 不中 → null
        assertNull(service.neigongHook("buf_ng_wd_001", "on_victim",
                ctx(3, 4, 1, 100_000, 0.99, Map.of())));
    }

    @Test
    void em002TickIntervalGate() {
        LuaScriptService service = service();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "neigong", "buf_ng_em_002.lua")),
                "buf_ng_em_002 anchor script missing");
        assertTrue(service.neigongHasHook("buf_ng_em_002", "on_tick"));
        // 冷却未到（19.9s）→ nil（since_last_ms 由分发器按最近有效触发推，此处直喂）
        assertNull(service.neigongHook("buf_ng_em_002", "on_tick",
                ctx(1, 4, 1, 200_000, 0.01, Map.of("last_at", 180_100L), false, 19_900L)));
        // 到点（20s，层1 吸收 120）→ 护盾 + state.last_at 推进
        NeiGongResult fire = service.neigongHook("buf_ng_em_002", "on_tick",
                ctx(1, 4, 1, 200_000, 0.01, Map.of("last_at", 180_000L), false, 20_000L));
        assertNotNull(fire);
        assertEquals(120, fire.shieldAmount());
        assertEquals(20_000L, fire.shieldLifeMs());
        assertEquals(200_000L, fire.state().get("last_at"));
    }

    @Test
    void em001ParryTierGatedByStoryStage() {
        LuaScriptService service = service();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "neigong", "buf_ng_em_001.lua")),
                "buf_ng_em_001 anchor script missing");
        // 满级书但九阴志阶段1 → 档位帽 3：平减 22 外功（层3 值）
        NeiGongResult tier3 = service.neigongHook("buf_ng_em_001", "on_parry",
                ctx(4, 4, 1, 100_000, 0.01, Map.of()));
        assertNotNull(tier3);
        assertEquals(22, tier3.parryReduceExternal());
        assertEquals(45, tier3.parryReduceInternal());
        // 阶段2 → 档4：on_parry 让位（比例减免在 on_victim parried 场景）
        assertNull(service.neigongHook("buf_ng_em_001", "on_parry",
                ctx(4, 4, 2, 100_000, 0.01, Map.of())));
        NeiGongResult tier4 = service.neigongHook("buf_ng_em_001", "on_victim",
                ctx(4, 4, 2, 100_000, 0.01, Map.of(), true, -1));
        assertNotNull(tier4);
        assertEquals(0.70, tier4.reducePct(), 1e-9);
        // 档3（阶段1）的 on_victim 即便 parried 也不生效（reduce=0）
        assertNull(service.neigongHook("buf_ng_em_001", "on_victim",
                ctx(4, 4, 1, 100_000, 0.01, Map.of(), true, -1)));
    }

    @Test
    void missingScriptYieldsNullAndNoHook() {
        LuaScriptService service = service();
        assertFalse(service.neigongHasHook("buf_ng_no_such", "on_victim"));
        assertNull(service.neigongHook("buf_ng_no_such", "on_victim",
                ctx(1, 4, 1, 0, 0.5, Map.of())));
    }
}
