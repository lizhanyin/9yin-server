package com.nineyin.script;

import com.nineyin.game.CombatFormulas;
import com.nineyin.game.CombatStats;
import com.nineyin.game.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.value.LuaValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lua 脚本宿主测试：native Lua 5.5 冒烟（Java 25 + LuaJava native 加载）、
 * 沙箱边界、模块加载、异常降级、指令解析，以及 formula.lua 与 Java 公式的逐输入对照
 * （仓库根 scripts/ 不存在时对照用例跳过）。
 */
class ScriptHostTest {

    @Test
    void nativeSmokeEval() {
        try (ScriptHost host = new ScriptHost()) {
            host.load(null);
            assertFalse(host.available(), "无脚本根时可用标志应为 false");
        }
        try (ScriptHost host = new ScriptHost()) {
            host.load(scriptsRoot());
            assumeTrue(host.available(), "native Lua 不可用（加载失败）");
            LuaValue[] ret = host.eval("return 1 + 1");
            assertNotNull(ret);
            assertEquals(2, ret[0].toInteger());
        }
    }

    @Test
    void sandboxBlocksDangerousGlobals() {
        try (ScriptHost host = new ScriptHost()) {
            host.load(scriptsRoot());
            assumeTrue(host.available());
            for (String dangerous : new String[]{"io", "os", "debug", "package", "require", "java"}) {
                LuaValue[] ret = host.eval("return " + dangerous);
                assertNotNull(ret, dangerous + " 求值不应崩溃");
                assertEquals(Lua.LuaType.NIL, ret[0].type(), dangerous + " 必须被沙箱移除");
            }
            for (String allowed : new String[]{"string", "table", "math", "pcall", "pairs", "tostring"}) {
                LuaValue[] ret = host.eval("return type(" + allowed + ")");
                assertNotNull(ret);
                assertFalse("nil".equals(ret[0].toString()), allowed + " 应可用");
            }
        }
    }

    @Test
    void loadsModulesFromScriptsRoot(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill");
        Path combatDir = tmp.resolve("combat");
        Files.createDirectories(skillDir);
        Files.createDirectories(combatDir);
        Files.writeString(skillDir.resolve("demo_skill.lua"),
                "local M = {}\nfunction M.compile(def) return { red_armor = true } end\nreturn M\n");
        Files.writeString(combatDir.resolve("formula.lua"),
                "local M = {}\nfunction M.calc(c) return { damage = 7 } end\nreturn M\n");
        Files.writeString(skillDir.resolve("broken.lua"), "local M = {}\nreturn " + "(nil + 1)");
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assertTrue(host.available());
            assertNotNull(host.skillModule("demo_skill"), "合法模块应加载");
            assertNull(host.skillModule("broken"), "语法错误模块应被跳过");
            assertNotNull(host.formulaFunction(), "formula.lua 的 calc 应加载");
            assertEquals(2, host.loadedCount(), "1 技能脚本 + 1 公式脚本");
        }
    }

    @Test
    void callDegradesToNullOnScriptError(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("boom.lua"),
                "local M = {}\nfunction M.compile(def) error('boom') end\nreturn M\n");
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assertTrue(host.available());
            LuaValue fn = host.skillModule("boom").get("compile");
            assertNull(host.callMap(fn, Map.of("id", "boom")), "脚本 error() 必须降级为 null");
        }
    }

    @Test
    void overlayParsingEndToEnd(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("ov.lua"), """
                local M = {}
                function M.compile(def)
                  return {
                    self_buff_lease_ms = 35000,
                    self_buff_slot = 5,
                    red_armor = true,
                    stance_cycle = { variants = {
                      { id = "buf_a", static_data = 6013, level = 1, life_ms = 86400000, slot = 7 },
                      { id = "buf_b", static_data = 6055, level = 1, life_ms = 86400000, slot = 7 },
                    } },
                  }
                end
                return M
                """);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assertTrue(host.available());
            LuaValue fn = host.skillModule("ov").get("compile");
            Object ret = host.callMap(fn, Map.of("id", "ov"));
            assertNotNull(ret);
            SkillCatalog.ScriptOverlay overlay = SkillScriptBridge.parseOverlay("ov", ret);
            assertNotNull(overlay);
            assertEquals(35000, overlay.selfBuffLeaseMs());
            assertEquals(5, overlay.preferredSlot());
            assertTrue(overlay.redArmor());
            assertEquals(2, overlay.stance().size());
            assertEquals(6013, overlay.stance().get(0).staticData());
            assertEquals(7, overlay.stance().get(0).preferredSlot());
            assertEquals(86400000L, overlay.stance().get(1).lifetimeMs());
            assertNull(overlay.effectsOverride(), "未提供 effects = 沿用默认编译");
        }
    }

    @Test
    void effectsParsingAppliesWhitelist(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("fx.lua"), """
                local M = {}
                function M.compile(def)
                  return { effects = {
                    { kind = "damage", amount = 120 },
                    { kind = "player_buff", buff = { id = "buf_x", static_data = 99, level = 1, life_ms = 5000 } },
                    { kind = "unknown_kind", amount = 1 },
                    { kind = "red_armor" },
                  } }
                end
                return M
                """);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            LuaValue fn = host.skillModule("fx").get("compile");
            Object ret = host.callMap(fn, Map.of());
            SkillCatalog.ScriptOverlay overlay = SkillScriptBridge.parseOverlay("fx", ret);
            assertNotNull(overlay.effectsOverride());
            assertEquals(3, overlay.effectsOverride().size(), "非法 kind 条目被丢弃");
            assertEquals(SkillCatalog.EffectKind.DAMAGE, overlay.effectsOverride().get(0).kind());
            assertEquals(120, overlay.effectsOverride().get(0).amount());
            assertEquals(SkillCatalog.EffectKind.PLAYER_BUFF, overlay.effectsOverride().get(1).kind());
            assertEquals(SkillCatalog.EffectKind.RED_ARMOR, overlay.effectsOverride().get(2).kind());
        }
    }

    /** Java→Lua 参数转换契约：嵌套 Map/List 必须转成 Lua table（ctx/hit/rand 都依赖）。 */
    @Test
    void nestedContainersConvertToLuaTables(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("conv.lua"), """
                local M = {}
                function M.check(m)
                  return {
                    inner = type(m.inner),
                    list = type(m.list),
                    first = (m.list and m.list[1]) or 'nil',
                    k = (m.inner and m.inner.k) or 'nil',
                  }
                end
                return M
                """);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assertTrue(host.available());
            LuaValue fn = host.skillModule("conv").get("check");
            Map<String, Object> input = Map.of("inner", Map.of("k", "v"), "list", List.of(7));
            Object ret = host.callMap(fn, input);
            assertNotNull(ret);
            @SuppressWarnings("unchecked") Map<String, Object> out = (Map<String, Object>) ret;
            assertEquals("table", out.get("inner"), "嵌套 Map 应转 Lua table");
            assertEquals("table", out.get("list"), "嵌套 List 应转 Lua table");
            assertEquals(7, ((Number) out.get("first")).longValue(), "List 元素按 1 基索引可取");
            assertEquals("v", out.get("k"));
        }
    }

    /** formula.lua 与 CombatFormulas 逐输入对照（scripts/combat/formula.lua 缺失时跳过）。 */
    @Test
    void formulaMatchesJavaImplementation() {
        Path root = scriptsRoot();
        assumeTrue(root != null && Files.isRegularFile(root.resolve("combat").resolve("formula.lua")),
                "仓库根 scripts/combat/formula.lua 不可见，跳过对照");
        try (ScriptHost host = new ScriptHost()) {
            host.load(root);
            assumeTrue(host.available());
            host.reload();   // 确认热重载路径同样可用
            assertTrue(host.hasFormula());
            CombatFormulaHost formulas = new CombatFormulaHost(host);
            CombatStats plain = CombatStats.of(150, 0);
            CombatStats tanky = new CombatStats(300, 7000, 120, 0.25f, 0.1f, 0.05f, 0.05f,
                    200, 150, 0.05f, 0.02f, 260, 80, 100, 120, 900);
            CombatStats defender = CombatStats.defender(2000, 50);
            CombatStats parrier = new CombatStats(0, 0, 0, 0f, 0f, 0f, 0f, 0, 80, 0f, 0f, 25, 60, 0, 0, 0);
            CombatStats noConversion = new CombatStats(0, 0, 0, 0f, 0f, 0f, 0f, 0, 0, 0f, 0f,
                    0, 0, 0, 0, 0);
            // (攻击方, 守方, 倍率, rMiss, rCrit, 招架)
            List<Object[]> cases = List.of(
                    new Object[]{plain, defender, 1.0, 0.99, 0.99, false},
                    new Object[]{plain, defender, 2.4, 0.5, 0.01, false},
                    new Object[]{tanky, defender, 1.13, 0.05, 0.5, false},
                    new Object[]{plain, parrier, 1.0, 0.99, 0.99, true},
                    new Object[]{noConversion, parrier, 1.0, 0.99, 0.99, true},
                    new Object[]{tanky, noConversion, 0.5, 0.01, 0.02, false});
            for (Object[] c : cases) {
                CombatStats atk = (CombatStats) c[0];
                CombatStats def = (CombatStats) c[1];
                double ratio = (Double) c[2];
                double rMiss = (Double) c[3];
                double rCrit = (Double) c[4];
                boolean parrying = (Boolean) c[5];
                CombatFormulas.DamageResult java = CombatFormulas.calc(atk, def, ratio, rMiss, rCrit, parrying);
                LuaValue fn = host.formulaFunction();
                Object ret = host.callMap(fn, SkillScriptBridge.formulaInput(
                        atk, def, ratio, rMiss, rCrit, parrying));
                assertNotNull(ret, "公式脚本不应出错");
                CombatFormulas.DamageResult lua = SkillScriptBridge.parseDamageResult(ret);
                assertNotNull(lua, "公式脚本必须产出 damage");
                assertEquals(java.damage(), lua.damage(), "damage 一致 " + atk + "→" + def);
                assertEquals(java.miss(), lua.miss());
                assertEquals(java.crit(), lua.crit());
                assertEquals(java.parried(), lua.parried());
                assertEquals(java.reductionPct(), lua.reductionPct(), 1e-9);
            }
        }
    }

    /** 仓库根 scripts/（surefire cwd=nineyin/，向上查找命中 9yin-server/scripts）。 */
    private static Path scriptsRoot() {
        return ScriptHost.locateScriptsRoot();
    }
}
