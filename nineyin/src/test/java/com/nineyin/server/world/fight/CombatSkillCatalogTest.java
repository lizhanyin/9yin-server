package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.script.LuaScriptService;
import com.nineyin.util.ServerPaths;

/**
 * 技能目录编译：share/skill 11 表 + zhaoshi 动作线 → {@link CombatSkill}。
 * 断言面对齐 F 盘 62433 技能抓包的 CD 映射表（docs/combat-skill-211.md §5）与
 * Go verifiedSkillEffectOverlays→Lua overlay 迁移（6013/6055 架势、35s 花神租约等）。
 */
class CombatSkillCatalogTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    private static CombatSkillCatalog catalog;
    private static Path combinedRoot;

    /** 表根优先取运行根（effects_data 生成源，cf 族等已随 11-05 更表重调）；缺省回落 GO 仓。 */
    private static final Path LIVE_SERVER_ROOT = Path.of("F:/Game/Online/9yin/9yin_server_new");

    /**
     * 单根拼装（同 NeiGongEffectsFlowTest）：resources→运行根/GO 仓（表）、scripts→本仓
     * （含 effects_data.lua 汇总表）——目录编译跑在「Lua 权威 + INI 兜底」的完整
     * 生产形态下。AfterAll 还原仓库根并 reload。
     */
    @BeforeAll
    static void loadCatalog() throws Exception {
        Path scriptsRoot = Path.of("").toAbsolutePath().normalize().getParent();
        Path tables = LIVE_SERVER_ROOT;
        if (!Files.isDirectory(tables.resolve(Path.of("resources", "modern", "share", "skill")))) {
            tables = GO_SERVER_ROOT;
        }
        Assumptions.assumeTrue(Files.isDirectory(
                tables.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill ini missing (needs live/Go repo resources)");
        Assumptions.assumeTrue(Files.isRegularFile(
                scriptsRoot.resolve(Path.of("scripts", "skill", "effects_data.lua"))),
                "scripts/skill/effects_data.lua missing");
        combinedRoot = Files.createTempDirectory("catalog-root");
        junction(combinedRoot.resolve("resources"), tables.resolve("resources"));
        junction(combinedRoot.resolve("scripts"), scriptsRoot.resolve("scripts"));
        ServerPaths.overrideServerRoot(combinedRoot);
        com.nineyin.server.script.LuaScriptService.getInstance().reload();
        catalog = CombatSkillCatalog.load();
        Assumptions.assumeTrue(catalog.indexedCount() > 0, "catalog compiled nothing");
    }

    @AfterAll
    static void restoreRepoRoot() {
        if (combinedRoot != null) {
            ServerPaths.overrideServerRoot(
                    Path.of("").toAbsolutePath().normalize().getParent());
            com.nineyin.server.script.LuaScriptService.getInstance().reload();
        }
    }

    /** Windows 目录 junction（免管理员）；失败则跳过本类。 */
    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).inheritIO().start();
        Assumptions.assumeTrue(process.waitFor() == 0, "mklink /J 不可用，跳过（link=" + link + "）");
    }

    @Test
    void catalogSweepCompilesExecutableLevelOne() {
        assertTrue(catalog.indexedCount() >= 100, "indexed=" + catalog.indexedCount());
        assertTrue(catalog.levelOneCount() >= 100, "executable=" + catalog.levelOneCount());
        // starter 23 技能全部可编（View40 已学集）
        for (String id : new String[]{"CS_yhwq_hsqs01", "CS_yhwq_hsqs07",
                "CS_jh_xfz01", "CS_jh_xfz07", "CS_wd_tjq01", "CS_wd_tjq08"}) {
            assertNotNull(catalog.definition(id, 1), id + " must compile at level 1");
        }
    }

    /** 抓包 CD 映射表逐行：category/个人CD/GCD/team（sfgp 族抓包 12 中样本）。 */
    @Test
    void cooldownMappingMatchesCapture() {
        assertCd("CS_jh_sfgp01", 8837, 8000);
        assertCd("CS_jh_sfgp02", 8838, 10000);
        assertCd("CS_jh_sfgp04", 8848, 2000);
        assertCd("CS_jh_sfgp07", 8852, 25000);
        assertCd("CS_jh_sfgp08", 8864, 10000);
    }

    /** View40 行 CoolDownTime 显示源 = varprop 按级值（127-0-0-1-59719 抓包 17/17 对齐）。 */
    @Test
    void personalCdMsMatchesView40Capture() {
        assertEquals(2000, catalog.personalCdMs("CS_jh_jysg01", 1));
        assertEquals(1000, catalog.personalCdMs("CS_wd_tjq04", 1));
        assertEquals(15000, catalog.personalCdMs("CS_wd_tjq05", 1));
        assertEquals(40000, catalog.personalCdMs("CS_wd_tjq07", 1));
        assertEquals(5000, catalog.personalCdMs("zs_default_01", 1));
        // 与 definition 编译的个人 CD 同源（出招 cooldown_rec 个人行时长 = 显示值）
        assertEquals(catalog.definition("CS_jh_jysg01", 1).personalCdMs(),
                catalog.personalCdMs("CS_jh_jysg01", 1));
        assertEquals(0, catalog.personalCdMs("no_such_skill", 1));
        assertEquals(0, catalog.personalCdMs(null, 1));
    }

    private static void assertCd(String id, int category, long personalCdMs) {
        CombatSkill skill = catalog.definition(id, 1);
        assertNotNull(skill, id + " compiles");
        assertEquals(category, skill.cooldownCategory(), id + " CoolDownCategory");
        assertEquals(personalCdMs, skill.personalCdMs(), id + " personal CD");
        assertEquals(7000, skill.publicCdMs(), id + " GCD (AddCoolDownTime)");
        assertEquals(50369, skill.cooldownTeam(), id + " CoolDownTeam (GCD row team)");
        assertEquals("CS_jh_sfgp", skill.taolu(), id + " TaoLu（同套路共享）");
    }

    @Test
    void sfgp04DamageAndShapeCompile() {
        CombatSkill skill = catalog.definition("CS_jh_sfgp04", 1);
        assertTrue(skill.baseDamage() > 0, "compiled damage sum");
        assertTrue(skill.damageWaves() >= 1);
        assertTrue(skill.damageRatio() > 0);
        assertTrue(!skill.actionName().isEmpty(), "zhaoshi timeline action");
        assertTrue(skill.totalActionDurationMs() > 0);
        assertTrue(skill.effects().stream().anyMatch(e -> e.kind() == CombatSkill.EffectKind.DAMAGE));
    }

    /** Lua overlay 迁移：架势轮换（6013/6055 槽7）、花神租约 35s 槽5、红霸体。 */
    @Test
    void luaOverlaysMigrateGoConstants() {
        Assumptions.assumeTrue(LuaScriptService.getInstance().luaAvailable(), "lua engine unavailable");

        CombatSkill stance = catalog.definition("CS_wd_tjq04", 1);
        CombatSkill.Effect cycle = stance.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.STANCE_CYCLE).findFirst().orElse(null);
        assertNotNull(cycle, "tjq04 stance cycle");
        assertEquals(2, cycle.buffVariants().size());
        assertEquals(6013, cycle.buffVariants().get(0).staticData());
        assertEquals(6055, cycle.buffVariants().get(1).staticData());
        assertEquals(7, cycle.buffVariants().get(0).preferredSlot());

        CombatSkill yihua = catalog.definition("CS_yhwq_hsqs04", 1);
        CombatSkill.Effect buff = yihua.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.PLAYER_BUFF).findFirst().orElse(null);
        assertNotNull(buff, "hsqs04 self buff");
        assertEquals(15101, buff.buff().staticData(), "ini StaticData");
        assertEquals(35_000, buff.buff().lifetimeMs(), "lua lease overlay");
        assertEquals(5, buff.buff().preferredSlot(), "lua slot overlay");

        CombatSkill yihua05 = catalog.definition("CS_yhwq_hsqs05", 1);
        assertTrue(yihua05.effects().stream().anyMatch(e -> e.kind() == CombatSkill.EffectKind.RED_ARMOR));
        assertEquals(6, yihua05.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.PLAYER_BUFF)
                .findFirst().orElseThrow().buff().preferredSlot());
    }

    /**
     * effects_data 数据驱动编译（全技能效果接入 Lua 的主体路径）：
     * tjq01 伤害/目标控制（有害精确 buff → target_effects control=true，frame=false
     * 与 INI 行为逐位一致）+ by_level 按级伤害；hsqs04 手写覆盖（35s）胜过数据面 24h。
     */
    @Test
    void dataDrivenEffectsCompile() {
        CombatSkill tjq01 = catalog.definition("CS_wd_tjq01", 1);
        assertNotNull(tjq01, "tjq01 compiles");
        assertEquals(112, tjq01.baseDamage(), "effects_data damage amount");
        assertEquals(1, tjq01.damageWaves());
        CombatSkill.Effect control = tjq01.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.TARGET_CONTROL)
                .findFirst().orElse(null);
        assertNotNull(control, "tjq01 target control effect (harmful exact buff)");
        assertTrue(control.control());
        assertFalse(control.frame(), "数据驱动 control 不发 169 帧（INI 行为一致）");
        assertEquals("buf_CS_wd_tjq01", control.buff().configId());
        assertEquals(6069, control.buff().staticData());
        assertEquals(10_000, control.buff().lifetimeMs());
        assertEquals(1, tjq01.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.TARGET_CONTROL).count(),
                "数据面 target_effects 整组取代，不与 exact 路径叠加");

        // by_level 按级伤害（L5=205/L10=341；L1 基准 112）
        assertEquals(205, catalog.definition("CS_wd_tjq01", 5).baseDamage());
        assertEquals(341, catalog.definition("CS_wd_tjq01", 10).baseDamage());

        // sfgp01：自 buff 走数据面（5000ms 与 INI 同值，行为不变）
        CombatSkill sfgp01 = catalog.definition("CS_jh_sfgp01", 1);
        assertEquals(302, sfgp01.baseDamage());
        CombatSkill.Effect selfBuff = sfgp01.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.PLAYER_BUFF)
                .findFirst().orElseThrow();
        assertEquals(27299, selfBuff.buff().staticData());
        assertEquals(5000, selfBuff.buff().lifetimeMs());

        // hsqs04 手写脚本优先：数据面 24h → 手写租约 35s
        CombatSkill hsqs04 = catalog.definition("CS_yhwq_hsqs04", 1);
        assertEquals(35_000, hsqs04.effects().stream()
                .filter(e -> e.kind() == CombatSkill.EffectKind.PLAYER_BUFF)
                .findFirst().orElseThrow().buff().lifetimeMs());
    }
}
