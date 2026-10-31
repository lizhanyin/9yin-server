package com.nineyin.script;

import com.nineyin.game.FacultyState;
import com.nineyin.game.NeigongRuntime;
import com.nineyin.game.PlayerActor;
import com.nineyin.scene.NpcInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.value.LuaValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 内功 Lua 脚本测试：宿主加载（scripts/neigong/）、指令白名单解析（NeigongScriptBridge）、
 * 运行时挂点（NeigongRuntime 免伤/护盾/状态往返/被动层），以及仓库 138 个内功脚本的
 * 全量加载冒烟（模块齐全 + 每个模块至少暴露一个钩子；仓库根不可见时跳过）。
 */
class NeigongScriptTest {

    // ---------- 宿主加载 ----------

    @Test
    void loadsNeigongModulesFromScriptsRoot(@TempDir Path tmp) throws Exception {
        Path neigongDir = tmp.resolve("neigong");
        Files.createDirectories(neigongDir);
        Files.writeString(neigongDir.resolve("buf_ng_t_demo.lua"), """
                local M = {}
                M.meta = { id = "buf_ng_t_demo", name = "demo", category = "victim", max_tier = 2 }
                function M.on_victim(ctx) return { reduce_pct = 0.2 } end
                return M
                """);
        Files.writeString(neigongDir.resolve("broken.lua"), "local M = {}\nreturn " + "(nil + 1)");
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assertTrue(host.available());
            assertNotNull(host.neigongModule("buf_ng_t_demo"), "合法内功模块应加载");
            assertNull(host.neigongModule("broken"), "语法错误模块应被跳过");
            assertEquals(1, host.loadedCount());
        }
    }

    // ---------- 指令白名单解析 ----------

    @Test
    void instructionParsingAppliesWhitelist() {
        Map<String, Object> valid = Map.of(
                "reduce_pct", 0.08,
                "extra_damage", Map.of("amount", 120, "kind", "yang"),
                "reflect", Map.of("amount", 35, "kind", "external"),
                "heal", 100,
                "mp_restore", 50,
                "shield", Map.of("amount", 250, "life_ms", 10000),
                "parry_reduce", Map.of("internal", 15, "external", 12),
                "state", Map.of("stacks", 2));
        NeigongScriptBridge.Instructions instr =
                NeigongScriptBridge.parseInstructions("buf_ng_t_demo", valid);
        assertNotNull(instr);
        assertEquals(0.08, instr.reducePct(), 1e-9);
        assertEquals(120, instr.extraDamage().amount());
        assertEquals("yang", instr.extraDamage().kind());
        assertEquals(35, instr.reflect().amount());
        assertEquals(100, instr.heal());
        assertEquals(50, instr.mpRestore());
        assertEquals(250, instr.shieldAmount());
        assertEquals(10000, instr.shieldLifeMs());
        assertEquals(15, instr.parryReduce().internal());
        assertEquals(12, instr.parryReduce().external());
        assertEquals(2, ((Number) instr.state().get("stacks")).longValue());

        // 非法条目：负值/未知元素系/缺字段 → 丢弃但保留其余
        Map<String, Object> mixed = Map.of(
                "reduce_pct", -0.5,
                "extra_damage", Map.of("amount", 10, "kind", "dark"),
                "shield", Map.of("amount", 0, "life_ms", 1000),
                "heal", 7);
        NeigongScriptBridge.Instructions partial =
                NeigongScriptBridge.parseInstructions("buf_ng_t_demo", mixed);
        assertNotNull(partial);
        assertEquals(0, partial.reducePct(), 1e-9);
        assertNull(partial.extraDamage(), "未知元素系应丢弃");
        assertNull(partial.shieldAmount() > 0 ? partial : null, "无效护盾应丢弃");
        assertEquals(0, partial.shieldAmount());
        assertEquals(7, partial.heal());

        assertNull(NeigongScriptBridge.parseInstructions("buf_ng_t_demo", null));
        assertNull(NeigongScriptBridge.parseInstructions("buf_ng_t_demo", "not a table"));
        assertNull(NeigongScriptBridge.parseInstructions("buf_ng_t_demo", Map.of()),
                "全空指令返回 null（isEmpty 语义）");
    }

    @Test
    void passiveParsingMapsStatFields() {
        Map<String, Object> stats = Map.of(
                "max_mp_add", 100, "crit_damage_bonus", 0.2, "attack_add", 5,
                "crit_rate_bonus", 1.5,   // 超过 1 应夹取
                "damage_reduction_add", 20);
        NeigongScriptBridge.PassiveStats passive =
                NeigongScriptBridge.parsePassive("buf_ng_t_demo", stats);
        assertNotNull(passive);
        assertEquals(100, passive.maxMpAdd());
        assertEquals(0.2, passive.critDamageBonus(), 1e-6);
        assertEquals(5, passive.attackAdd());
        assertEquals(1.0, passive.critRateBonus(), 1e-9, "暴击率加成夹 [0,1]");
        assertEquals(20, passive.damageReductionAdd());
        assertNull(NeigongScriptBridge.parsePassive("buf_ng_t_demo", Map.of()),
                "全零被动返回 null");
    }

    // ---------- 运行时（确定性 fixture） ----------

    /** 确定性内功：受击固定减 20%；被动内力 +100；攻击回写 state 计数。 */
    private static final String FIXTURE = """
            local M = {}
            M.meta = { id = "buf_ng_t_fix", name = "fixture", category = "victim", max_tier = 4 }
            function M.on_victim(ctx)
              return { reduce_pct = 0.2, state = { hits = (ctx.state.hits or 0) + 1 } }
            end
            function M.on_attack(ctx)
              return { state = { swings = (ctx.state.swings or 0) + 1 } }
            end
            function M.passive(ctx)
              return { max_mp_add = 100 }
            end
            return M
            """;

    private static PlayerActor fixtureActor() {
        PlayerActor actor = new PlayerActor();
        FacultyState faculty = FacultyState.bootstrap();
        faculty.curNeigong = "ng_t_fix";   // 书本不在快照 → bookLevelOf 回退 (1,1)，fixture 不依赖层数
        actor.faculty = faculty;
        return actor;
    }

    @Test
    void runtimeAppliesVictimReductionAndState(@TempDir Path tmp) throws Exception {
        Path neigongDir = tmp.resolve("neigong");
        Files.createDirectories(neigongDir);
        Files.writeString(neigongDir.resolve("buf_ng_t_fix.lua"), FIXTURE);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assumeTrue(host.available());
            NeigongRuntime rt = new NeigongRuntime(host);
            PlayerActor actor = fixtureActor();
            NpcInstance attacker = new NpcInstance();
            attacker.objectId = 10001;
            long now = System.currentTimeMillis();
            // 100 来袭 → 脚本减 20% → 80
            assertEquals(80, rt.adjustIncoming(null, actor, attacker,
                    100, false, false, false, 100, now));
            // state 往返：on_victim 每次调用 hits +1
            assertEquals(1, ((Number) actor.neigongStateOf("buf_ng_t_fix").get("hits")).longValue());
            rt.adjustIncoming(null, actor, attacker, 100, false, false, false, 100, now);
            assertEquals(2, ((Number) actor.neigongStateOf("buf_ng_t_fix").get("hits")).longValue());
        }
    }

    @Test
    void runtimeShieldConsumesBeforeHp(@TempDir Path tmp) throws Exception {
        Path neigongDir = tmp.resolve("neigong");
        Files.createDirectories(neigongDir);
        Files.writeString(neigongDir.resolve("buf_ng_t_fix.lua"), FIXTURE);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assumeTrue(host.available());
            NeigongRuntime rt = new NeigongRuntime(host);
            PlayerActor actor = fixtureActor();
            NpcInstance attacker = new NpcInstance();
            long now = System.currentTimeMillis();
            // 护盾 50：100 → 减 20% = 80 → 穿盾 50 → 30 进血量层
            actor.grantAbsorb(50, 10000, now);
            assertEquals(30, rt.adjustIncoming(null, actor, attacker,
                    100, false, false, false, 100, now));
            assertEquals(0, actor.absorbRemaining(now), "护盾应耗尽");
            // 血量层落账：applyDamage(30)
            int hpBefore = actor.hp;
            actor.applyDamage(30);
            assertEquals(hpBefore - 30 + (int) (30 * 0.25), actor.hp,
                    "25% 进瘀血层，其余扣血量（瘀血未满时）");
        }
    }

    @Test
    void runtimeAttackHookWritesStateAndPassiveLayer(@TempDir Path tmp) throws Exception {
        Path neigongDir = tmp.resolve("neigong");
        Files.createDirectories(neigongDir);
        Files.writeString(neigongDir.resolve("buf_ng_t_fix.lua"), FIXTURE);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assumeTrue(host.available());
            NeigongRuntime rt = new NeigongRuntime(host);
            PlayerActor actor = fixtureActor();
            NpcInstance npc = new NpcInstance();
            npc.objectId = 20001;
            npc.hp = npc.maxHp = 1000;
            long now = System.currentTimeMillis();
            assertEquals(0, rt.onAttackLanded(null, actor, npc, 100, false, 1, 1, now),
                    "fixture 无附加伤害");
            assertEquals(1, ((Number) actor.neigongStateOf("buf_ng_t_fix").get("swings")).longValue());
            // 被动层：内力上限 +100
            int mpBefore = actor.maxMp();
            rt.refreshPassive(actor, now);
            assertEquals(mpBefore + 100, actor.maxMp());
            // 再刷新（书本未变）不重复叠加
            rt.refreshPassive(actor, now);
            assertEquals(mpBefore + 100, actor.maxMp());
        }
    }

    // ---------- 九阴志档位门 ----------

    /** 档位门 fixture：4 档被动 attack_add = 10×档；on_attack 回传 story_stage 作回蓝。 */
    private static final String STORY_FIXTURE = """
            local M = {}
            M.meta = { id = "buf_ng_t_story", name = "story", category = "passive", max_tier = 4 }
            M.levels = { [1] = {v = 10}, [2] = {v = 20}, [3] = {v = 30}, [4] = {v = 40} }
            local function tier_of(ctx)
              local mt = M.meta.max_tier
              local bl, bm = ctx.book_level or 1, ctx.book_max_level or mt
              local cap = 3 + (ctx.story_stage or 1) - 1
              return math.max(1, math.min(mt, math.ceil(bl * mt / bm), cap))
            end
            function M.passive(ctx)
              return { attack_add = M.levels[tier_of(ctx)].v }
            end
            function M.on_attack(ctx)
              return { mp_restore = ctx.story_stage or 0 }
            end
            return M
            """;

    @Test
    void storyStageGatesHighTiers(@TempDir Path tmp) throws Exception {
        Path neigongDir = tmp.resolve("neigong");
        Files.createDirectories(neigongDir);
        Files.writeString(neigongDir.resolve("buf_ng_t_story.lua"), STORY_FIXTURE);
        try (ScriptHost host = new ScriptHost()) {
            host.load(tmp);
            assumeTrue(host.available());
            NeigongRuntime rt = new NeigongRuntime(host);
            PlayerActor actor = new PlayerActor();
            FacultyState faculty = FacultyState.bootstrap();
            faculty.curNeigong = "ng_t_story";   // 书本不在快照 → (1,1) → 未钳制档位 = 4
            actor.faculty = faculty;
            long now = System.currentTimeMillis();
            // 阶段 1：高层特效档位（>3 档）未开放，钳到 3 档
            rt.setStoryStage(() -> 1);
            rt.refreshPassive(actor, now);
            assertNotNull(actor.neigongPassive);
            assertEquals(30, actor.neigongPassive.attackAdd(), "九阴志阶段1最高3档");
            // 阶段 2：4 档开放（阶段推进重算被动）
            rt.setStoryStage(() -> 2);
            rt.refreshPassive(actor, now);
            assertEquals(40, actor.neigongPassive.attackAdd(), "九阴志阶段2开放4档");
            // 运行钩子 ctx 透传 story_stage：on_attack 回蓝 = 阶段值
            NpcInstance npc = new NpcInstance();
            npc.objectId = 30001;
            npc.hp = npc.maxHp = 1000;
            actor.mp = actor.maxMp() - 10;
            rt.onAttackLanded(null, actor, npc, 10, false, 1, 1, now);
            assertEquals(actor.maxMp() - 10 + 2, actor.mp, "story_stage 经指令生效（回蓝=阶段值）");
        }
    }

    // ---------- 仓库 138 门全量冒烟 ----------

    @Test
    void realNeigongCatalogLoadsAndExposesHooks() throws Exception {
        Path root = ScriptHost.locateScriptsRoot();
        Path neigongRoot = root == null ? null : root.resolve("neigong");
        assumeTrue(neigongRoot != null && Files.isDirectory(neigongRoot),
                "仓库根 scripts/neigong/ 不可见，跳过全量冒烟");
        try (ScriptHost host = new ScriptHost()) {
            host.load(root);
            assumeTrue(host.available());
            int files;
            try (var list = Files.list(neigongRoot)) {
                files = (int) list.filter(p -> p.getFileName().toString().endsWith(".lua")).count();
            }
            assertTrue(files >= 138, "仓库应包含 138 门内功脚本，实际 " + files);
            // 逐门校验：meta 表存在且 id 与文件名一致；至少一个钩子为函数
            try (var list = Files.list(neigongRoot)) {
                list.filter(p -> p.getFileName().toString().endsWith(".lua")).forEach(p -> {
                    String name = p.getFileName().toString();
                    String buffId = name.substring(0, name.length() - 4);
                    LuaValue module = host.neigongModule(buffId);
                    assertNotNull(module, buffId + " 应加载（语法错误会被跳过）");
                    LuaValue meta = module.get("meta");
                    assertNotNull(meta, buffId + " 缺 meta");
                    assertEquals(Lua.LuaType.TABLE, meta.type(), buffId + " meta 应为 table");
                    assertTrue(meta.get("id").toString().contains(buffId),
                            buffId + " meta.id 应与文件名一致，实际 " + meta.get("id"));
                    String category = meta.get("category").toString();
                    assertTrue(category.contains("attack") || category.contains("victim")
                                    || category.contains("parry") || category.contains("tick")
                                    || category.contains("passive") || category.contains("stack"),
                            buffId + " category 非法: " + category);
                    boolean hasHook = isFunction(module, "on_attack") || isFunction(module, "on_victim")
                            || isFunction(module, "on_parry") || isFunction(module, "on_tick")
                            || isFunction(module, "passive");
                    LuaValue levels = module.get("levels");
                    boolean hasLevels = levels != null && levels.type() == Lua.LuaType.TABLE;
                    assertTrue(hasHook || hasLevels,
                            buffId + " 至少应暴露一个钩子或 levels 参数表");
                });
            }
            assertFalse(host.loadedCount() < files, "存在加载失败的内功脚本");
        }
    }

    private static boolean isFunction(LuaValue module, String field) {
        LuaValue fn = module.get(field);
        return fn != null && fn.type() == Lua.LuaType.FUNCTION;
    }
}
