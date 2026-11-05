package com.nineyin.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.util.ServerPaths;

/**
 * 技能效果 Lua 契约（effects_data 汇总表 / 逐技能覆盖优先级 / on_hit 运行期钩子）：
 * 临时根 fixture（formula 拷贝 + 手写 effects_data + 5 个 TST_* 逐技能脚本）驱动，
 * AfterAll 还原仓库根并 reload，不污染其它测试类的根定位。
 */
class LuaSkillOverlayTest {

    private static Path repoRoot;
    private static Path tempRoot;

    @BeforeAll
    static void buildTempScriptRoot() throws Exception {
        repoRoot = Path.of("").toAbsolutePath().normalize().getParent();
        Assumptions.assumeTrue(Files.isRegularFile(
                repoRoot.resolve(Path.of("scripts", "combat", "formula.lua"))),
                "formula.lua missing");
        tempRoot = Files.createTempDirectory("skill-overlay-root");
        Path combat = tempRoot.resolve(Path.of("scripts", "combat"));
        Path skill = tempRoot.resolve(Path.of("scripts", "skill"));
        Files.createDirectories(combat);
        Files.createDirectories(skill);
        Files.copy(repoRoot.resolve(Path.of("scripts", "combat", "formula.lua")),
                combat.resolve("formula.lua"));
        write(skill.resolve("effects_data.lua"), String.join("\n",
                "local E = {}",
                "E[\"TST_data01\"] = {",
                "  damage = { amount = 100, waves = 2 },",
                "  self_buffs = { { config_id = \"buf_TST_data01\", static_data = 9001, level = 1, life_ms = 5000 } },",
                "  target_effects = { { config_id = \"buf_TST_data01_t\", static_data = 9002, level = 1, life_ms = 8000, control = true } },",
                "  by_level = { [5] = { damage_amount = 200, self_life_ms = 7000 } },",
                "}",
                "E[\"TST_both\"] = {",
                "  damage = { amount = 11, waves = 1 },",
                "  self_buffs = { { config_id = \"buf_TST_both\", static_data = 9999, life_ms = 1000 } },",
                "}",
                "E[\"TST_bad_target\"] = {",
                "  target_effects = { { config_id = \"buf_x\", static_data = 1, life_ms = 100 } },",
                "}",
                "return E"));
        write(skill.resolve("TST_both.lua"), String.join("\n",
                "local M = {}",
                "function M.compile(def)",
                "  return { damage = { amount = 22, waves = 3 } }",
                "end",
                "function M.on_hit(ctx)",
                "  return { ratio = ctx.ratio * 2 }",
                "end",
                "return M"));
        write(skill.resolve("TST_clamp.lua"), String.join("\n",
                "local M = {}",
                "function M.on_hit(ctx)",
                "  return { ratio = 500 }",
                "end",
                "return M"));
        write(skill.resolve("TST_nilhook.lua"), String.join("\n",
                "local M = {}",
                "function M.on_hit(ctx)",
                "  return nil",
                "end",
                "return M"));
        write(skill.resolve("TST_broken.lua"), String.join("\n",
                "local M = {}",
                "function M.compile(def)",
                "  return { red_armor = true }",
                "end",
                "function M.on_hit(ctx)",
                "  error('boom')",
                "end",
                "return M"));
        ServerPaths.overrideServerRoot(tempRoot);
        LuaScriptService.getInstance().reload();
        Assumptions.assumeTrue(LuaScriptService.getInstance().luaAvailable(),
                "lua engine unavailable in this env");
    }

    @AfterAll
    static void restoreRepoRoot() throws IOException {
        ServerPaths.overrideServerRoot(repoRoot);
        LuaScriptService.getInstance().reload();
        if (tempRoot != null) {
            try (var paths = Files.walk(tempRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 临时目录清理尽力而为
                    }
                });
            }
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    /** 汇总表条目解析 + by_level 按级差集（整数键 rawGetI 路径）。 */
    @Test
    void dataEntryParsesWithByLevel() {
        LuaScriptService service = LuaScriptService.getInstance();
        LuaScriptService.SkillOverlay level1 = service.skillOverlay("TST_data01", 1, null);
        assertNotNull(level1);
        assertEquals(100, level1.damageAmount());
        assertEquals(2, level1.damageWaves());
        assertEquals(1, level1.selfBuffs().size());
        assertEquals("buf_TST_data01", level1.selfBuffs().get(0).configId());
        assertEquals(9001, level1.selfBuffs().get(0).staticData());
        assertEquals(5000, level1.selfBuffs().get(0).lifeMs());
        assertEquals(1, level1.targetEffects().size());
        assertTrue(level1.targetEffects().get(0).control());
        assertFalse(level1.targetEffects().get(0).frame());
        assertEquals(8000, level1.targetEffects().get(0).lifeMs());

        LuaScriptService.SkillOverlay level5 = service.skillOverlay("TST_data01", 5, null);
        assertEquals(200, level5.damageAmount(), "by_level damage_amount");
        assertEquals(2, level5.damageWaves(), "waves 未差集 → 沿用基准");
        assertEquals(7000, level5.selfBuffs().get(0).lifeMs(), "by_level self_life_ms");
        assertEquals(8000, level5.targetEffects().get(0).lifeMs(), "target life 未差集 → 原值");

        // 缺级（如 level 3）→ 无差集，基准原样
        LuaScriptService.SkillOverlay level3 = service.skillOverlay("TST_data01", 3, null);
        assertEquals(100, level3.damageAmount());
        assertEquals(5000, level3.selfBuffs().get(0).lifeMs());
    }

    /** 逐技能脚本优先级：M.compile 整组取代汇总表条目。 */
    @Test
    void perSkillFileBeatsDataEntry() {
        LuaScriptService service = LuaScriptService.getInstance();
        LuaScriptService.SkillOverlay both = service.skillOverlay("TST_both", 1, null);
        assertNotNull(both);
        assertEquals(22, both.damageAmount(), "compile damage wins over data 11");
        assertEquals(3, both.damageWaves());
        assertTrue(both.selfBuffs().isEmpty(), "compile 未给 self_buffs → 不并数据面的 buff");
    }

    /** on_hit：倍率调整 / 上限 100 / nil 返回 → null。 */
    @Test
    void onHitAdjustClampAndNil() {
        LuaScriptService service = LuaScriptService.getInstance();
        assertTrue(service.skillHasOnHit("TST_both"));
        LuaScriptService.SkillHitAdjust adjust = service.skillOnHit("TST_both", 1.5f, 1, 0.5);
        assertNotNull(adjust);
        assertEquals(3.0f, adjust.ratio(), 1e-6);

        assertTrue(service.skillHasOnHit("TST_clamp"));
        assertEquals(100f, service.skillOnHit("TST_clamp", 1.0f, 1, 0.5).ratio(), 1e-6);

        assertTrue(service.skillHasOnHit("TST_nilhook"));
        assertNull(service.skillOnHit("TST_nilhook", 1.0f, 1, 0.5));

        assertFalse(service.skillHasOnHit("TST_data01"), "数据面条目无钩子");
        assertNull(service.skillOnHit("TST_data01", 1.0f, 1, 0.5));
    }

    /** 坏脚本隔离：on_hit 运行期异常只禁该技能，公式引擎与其它脚本不受影响。 */
    @Test
    void brokenScriptIsolated() {
        LuaScriptService service = LuaScriptService.getInstance();
        assertTrue(service.skillHasOnHit("TST_broken"));
        assertNull(service.skillOnHit("TST_broken", 1.0f, 1, 0.5), "error('boom') → null");
        assertNull(service.skillOnHit("TST_broken", 1.0f, 1, 0.5), "禁用后持续 null");
        assertTrue(service.luaAvailable(), "公式引擎不因坏脚本 markBroken");
        assertNotNull(service.skillOnHit("TST_both", 2.0f, 1, 0.5), "其它脚本不受牵连");
        // compile 契约不受 on_hit 禁用影响
        LuaScriptService.SkillOverlay overlay = service.skillOverlay("TST_broken", 1, null);
        assertEquals(Boolean.TRUE, overlay.redArmor());
    }

    /** 白名单校验：control/frame 全 false 的 target_effects 条目丢弃。 */
    @Test
    void invalidTargetEffectDropped() {
        LuaScriptService service = LuaScriptService.getInstance();
        LuaScriptService.SkillOverlay bad = service.skillOverlay("TST_bad_target", 1, null);
        assertNotNull(bad);
        assertTrue(bad.targetEffects().isEmpty(), "无 control/frame 的目标效果丢弃");
        assertNull(bad.damageAmount());
    }
}
