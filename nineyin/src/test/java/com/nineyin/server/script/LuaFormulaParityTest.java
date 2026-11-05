package com.nineyin.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.util.ServerPaths;

/**
 * Lua 引擎 + 伤害公式双实现一致性：先钉死 lua55 natives 可加载（shade/测试环境），
 * 再网格对照 formula.lua 与 {@link CombatFormulas} 同输入同输出（掷骰由 Java 预掷）。
 */
class LuaFormulaParityTest {

    @BeforeAll
    static void pointScriptsAtRepoRoot() {
        // surefire cwd=nineyin 模块目录；scripts/ 在仓库根（与 LoginFlowTest 覆盖 GO 根同法）。
        // 必须 reload：全量跑时单例引擎可能继承前序类在其它根的装载（无 effects_data），
        // 数据面断言（xfz01 等）依赖本仓 scripts（同 LuaNeigongHookTest 范式）。
        ServerPaths.overrideServerRoot(
                Path.of("").toAbsolutePath().normalize().getParent());
        LuaScriptService.getInstance().reload();
    }

    @Test
    void lua55NativesLoadAndFormulaModuleBinds() {
        assertTrue(LuaScriptService.getInstance().luaAvailable(),
                "lua55 natives must load (luajava + lua55-platform:natives-desktop)");
    }

    @Test
    void formulaParityGrid() {
        Assumptions.assumeTrue(Files.isRegularFile(formulaScript()), "formula.lua missing");
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");
        double[] attacks = {0, 137, 943.5, 5000};
        double[] defenses = {0, 99, 100, 900, 5000};
        double[][] rolls = {{0.5, 0.5}, {0.999, 0.001}, {0.001, 0.999}};
        int compared = 0;
        for (double attack : attacks) {
            for (double defense : defenses) {
                for (boolean parrying : new boolean[]{false, true}) {
                    for (double[] roll : rolls) {
                        CombatFormulas.Input input = input(attack, defense, parrying, roll[0], roll[1]);
                        CombatFormulas.Result java = CombatFormulas.calc(input);
                        CombatFormulas.Result lua = service.calc(input);
                        assertEquals(java.damage(), lua.damage(), "damage a=" + attack + " d=" + defense);
                        assertEquals(java.miss(), lua.miss(), "miss");
                        assertEquals(java.crit(), lua.crit(), "crit");
                        assertEquals(java.parried(), lua.parried(), "parried");
                        assertEquals(java.reductionPct(), lua.reductionPct(), 1e-9, "reduction");
                        compared++;
                    }
                }
            }
        }
        assertTrue(compared > 100, "grid must cover enough cases, got " + compared);
    }

    @Test
    void knownOutcomes() {
        // 外防 0 → 无减伤；无暴击（r_crit=1）无闪避（r_miss=1）；
        // 模板含 pierce=12/ratio=1.4/暴伤 0.5/平减 100：(100+12)*1.4=156.8 → 156 − 10 = 146
        CombatFormulas.Input plain = input(100, 0, false, 1, 1);
        assertEquals(146, CombatFormulas.calc(plain).damage());

        // 完全招架（化劲 ≤ 格挡值）：无穿透伤害，不吃保底
        CombatFormulas.Input fullParry = new CombatFormulas.Input(
                100, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 500, 1, true, 1, 1);
        assertEquals(0, CombatFormulas.calc(fullParry).damage());
        assertTrue(CombatFormulas.calc(fullParry).parried());

        // 必闪避（闪避 100/命中 0 → 未命中率≈24%，r_miss=0）
        CombatFormulas.Input dodge = new CombatFormulas.Input(
                100, 0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, false, 0, 1);
        assertTrue(CombatFormulas.calc(dodge).miss());
        assertEquals(0, CombatFormulas.calc(dodge).damage());

        // 保底 1 点（大额平减吃光伤害）
        CombatFormulas.Input floorOne = new CombatFormulas.Input(
                10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 500, 0, 1, false, 1, 1);
        assertEquals(1, CombatFormulas.calc(floorOne).damage());
    }

    @Test
    void skillOverlayContract() {
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "skill", "CS_yhwq_hsqs04.lua")), "skill scripts missing");
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");

        LuaScriptService.SkillOverlay yihua = service.skillOverlay("CS_yhwq_hsqs04", 1,
                new LuaScriptService.ExactBuff("buf_CS_yhwq_hsqs04", 15101, 1, 86_400_000));
        assertEquals(35_000L, yihua.selfBuffLeaseMs());
        assertEquals(5, yihua.selfBuffSlot());

        // 无逐技能脚本但汇总表有条目 → effects_data 数据面（INI 编译同值，分级缓存）
        LuaScriptService.SkillOverlay xfz = service.skillOverlay("CS_jh_xfz01", 1, null);
        assertEquals(119, xfz.damageAmount());
        assertEquals(1, xfz.damageWaves());
        LuaScriptService.SkillOverlay xfz3 = service.skillOverlay("CS_jh_xfz01", 3, null);
        assertEquals(203, xfz3.damageAmount());

        // 太极四式：架势轮换双变体（守6013/攻6055，槽7）；八式：红霸体；五式：槽8
        LuaScriptService.SkillOverlay stance = service.skillOverlay("CS_wd_tjq04", 1, null);
        assertEquals(2, stance.stanceCycle().size());
        assertEquals(6013, stance.stanceCycle().get(0).staticData());
        assertEquals(6055, stance.stanceCycle().get(1).staticData());
        assertEquals(7, stance.stanceCycle().get(0).slot());
        assertEquals(Boolean.TRUE, service.skillOverlay("CS_wd_tjq08", 1, null).redArmor());
        assertEquals(8, service.skillOverlay("CS_wd_tjq05", 1, null).selfBuffSlot());
    }

    private static CombatFormulas.Input input(double attack, double defense,
                                              boolean parrying, double missRoll, double critRoll) {
        return new CombatFormulas.Input(
                attack, defense, 12, 800, 50, 30, 0, 0, 200, 150, 0, 0, 0.5, 0.2, 100, 600,
                1.4, parrying, missRoll, critRoll);
    }

    private static Path formulaScript() {
        return ServerPaths.projectPath("scripts", "combat", "formula.lua");
    }
}
