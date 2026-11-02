package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @BeforeAll
    static void loadCatalog() {
        Assumptions.assumeTrue(Files.isDirectory(
                GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill ini missing (needs Go repo resources)");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
        catalog = CombatSkillCatalog.load();
        Assumptions.assumeTrue(catalog.indexedCount() > 0, "catalog compiled nothing");
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
}
