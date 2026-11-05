package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.script.LuaScriptService;
import com.nineyin.util.ServerPaths;

/**
 * 全技能效果 Lua 数据面 parity 钉子：同一份 share 表，两个根各编一次——
 * 根 A 无 scripts（引擎 broken → INI 兜底编译）、根 B 含本仓 scripts
 * （effects_data.lua → Lua 权威编译），全部技能×采样等级逐字段对照。
 * 任何 diff = 生成器产出/契约解析/目录合并三处之一与 INI 语义不一致。
 */
class SkillEffectDataParityTest {

    /** 表根优先取运行根（effects_data 生成源，cf 族等已随 11-05 更表重调）；缺省回落 GO 仓。 */
    private static final Path LIVE_SERVER_ROOT = Path.of("F:/Game/Online/9yin/9yin_server_new");
    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    private static Path repoRoot;
    private static Path iniOnlyRoot;
    private static Path luaRoot;

    @BeforeAll
    static void buildTwoRoots() throws Exception {
        repoRoot = Path.of("").toAbsolutePath().normalize().getParent();
        Path tables = LIVE_SERVER_ROOT;
        if (!Files.isDirectory(tables.resolve(Path.of("resources", "modern", "share", "skill")))) {
            tables = GO_SERVER_ROOT;
        }
        Assumptions.assumeTrue(Files.isDirectory(
                tables.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill ini missing (needs live/Go repo resources)");
        Assumptions.assumeTrue(Files.isRegularFile(
                repoRoot.resolve(Path.of("scripts", "skill", "effects_data.lua"))),
                "scripts/skill/effects_data.lua missing");
        iniOnlyRoot = Files.createTempDirectory("parity-ini-root");
        junction(iniOnlyRoot.resolve("resources"), tables.resolve("resources"));
        Files.createDirectories(iniOnlyRoot.resolve(Path.of("scripts", "skill"))); // 无任何脚本
        luaRoot = Files.createTempDirectory("parity-lua-root");
        junction(luaRoot.resolve("resources"), tables.resolve("resources"));
        junction(luaRoot.resolve("scripts"), repoRoot.resolve("scripts"));
    }

    @AfterAll
    static void restoreRepoRoot() {
        ServerPaths.overrideServerRoot(repoRoot);
        LuaScriptService.getInstance().reload();
    }

    /** Windows 目录 junction（免管理员）；失败则跳过本类。 */
    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).inheritIO().start();
        Assumptions.assumeTrue(process.waitFor() == 0, "mklink /J 不可用，跳过（link=" + link + "）");
    }

    private static Map<String, String> snapshotAll(CombatSkillCatalog catalog) {
        Map<String, String> out = new TreeMap<>();
        for (int level : new int[]{1, 2, 5}) {
            for (String id : catalog.indexedIds()) {
                CombatSkill skill = catalog.definition(id, level);
                if (skill == null) {
                    continue; // 该级无 varprop：两根同样跳过
                }
                out.put(id + "@" + level, describe(skill));
            }
        }
        return out;
    }

    /** 效果相关字段的稳定摘要（damage/段数/效果三元组；CD/形状与脚本无关不比对）。 */
    private static String describe(CombatSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("d=").append(skill.baseDamage()).append('/').append(skill.damageWaves());
        for (CombatSkill.Effect effect : skill.effects()) {
            sb.append('|').append(effect.kind());
            if (effect.buff() != null) {
                sb.append(':').append(effect.buff().configId())
                        .append('/').append(effect.buff().staticData())
                        .append('/').append(effect.buff().lifetimeMs())
                        .append('/').append(effect.buff().preferredSlot());
            }
            if (effect.kind() == CombatSkill.EffectKind.TARGET_CONTROL) {
                sb.append('/').append(effect.control()).append('/').append(effect.frame());
            }
            if (effect.buffVariants() != null) {
                for (CombatSkill.Buff variant : effect.buffVariants()) {
                    sb.append('/').append(variant.configId()).append('#')
                            .append(variant.staticData()).append('#')
                            .append(variant.lifetimeMs()).append('#')
                            .append(variant.preferredSlot());
                }
            }
        }
        return sb.toString();
    }

    @Test
    void luaDataCompileMatchesIniFallbackForAllSkills() throws Exception {
        // 手写逐技能脚本（M.compile/M.on_hit）= 有意的覆盖面，不计 parity
        //（其行为由 CombatSkillCatalogTest.luaOverlaysMigrateGoConstants 断言）。
        java.util.Set<String> handwritten = new java.util.HashSet<>();
        try (var files = Files.list(repoRoot.resolve(Path.of("scripts", "skill")))) {
            files.filter(p -> p.getFileName().toString().endsWith(".lua")
                            && !"effects_data.lua".equals(p.getFileName().toString()))
                    .forEach(p -> handwritten.add(
                            p.getFileName().toString().substring(0,
                                    p.getFileName().toString().length() - 4)));
        }
        assertTrue(handwritten.size() <= 32, "handwritten overlay set sanity: " + handwritten);

        // 根 A：INI 兜底（无脚本 → 引擎 broken，公式/覆盖全走 Java/INI）
        ServerPaths.overrideServerRoot(iniOnlyRoot);
        LuaScriptService.getInstance().reload();
        CombatSkillCatalog iniCatalog = CombatSkillCatalog.load();
        Assumptions.assumeTrue(iniCatalog.indexedCount() > 0, "catalog compiled nothing");
        Map<String, String> ini = snapshotAll(iniCatalog);

        // 根 B：Lua 权威（effects_data + 逐技能脚本 + formula）
        ServerPaths.overrideServerRoot(luaRoot);
        LuaScriptService.getInstance().reload();
        Assumptions.assumeTrue(LuaScriptService.getInstance().luaAvailable(),
                "lua engine unavailable in this env");
        CombatSkillCatalog luaCatalog = CombatSkillCatalog.load();
        Map<String, String> lua = snapshotAll(luaCatalog);

        assertEquals(ini.size(), lua.size(), "same skills compile under both roots");
        int diffs = 0;
        int overrides = 0;
        StringBuilder first = new StringBuilder();
        for (Map.Entry<String, String> entry : ini.entrySet()) {
            String other = lua.get(entry.getKey());
            boolean differs = other == null || !other.equals(entry.getValue());
            if (!differs) {
                continue;
            }
            if (handwritten.contains(entry.getKey().split("@")[0])) {
                overrides++; // 手写覆盖面：by-design 差异
                continue;
            }
            if (diffs < 10) {
                first.append(entry.getKey()).append("\n  ini=").append(entry.getValue())
                        .append("\n  lua=").append(other).append('\n');
            }
            diffs++;
        }
        assertEquals(0, diffs, "Lua 数据面与 INI 兜底编译不一致:\n" + first);
        assertTrue(overrides > 0, "handwritten overrides actually engaged: " + overrides);
        assertTrue(ini.size() > 5_000, "coverage sanity: " + ini.size());
    }
}
