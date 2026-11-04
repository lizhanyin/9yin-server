package com.nineyin.server.world.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.PlayerActor;

/**
 * 2026-11-04 对拍钉死项的回归（docs/combat-traffic-compare.md §D2/§D3/§D8）：
 *
 * <ul>
 *   <li>锁定判据：script=SkillLock ∨ CanRush ∨ (TargetType=4 ∧ DamageType≠0)。
 *       myjf01/04/08（Normal+TT4+DT3）参考服 wire 实证带 aim；chz03（TT4+DT0）
 *       为 §13 反例保护；myjf05（TT8）空目标。</li>
 *   <li>武技族资格：LevelupRelevance=wuji_* 出 169 段；+PackCategory=2（怒招）
 *       才出 c1=71 连击段（myjf08 有、myjf03 无）。</li>
 *   <li>收尾 (id, epoch) 配对：被顶掉的旧招定时器空转、同技能复读不误删新招。</li>
 * </ul>
 */
class LockCriterionAndEpochTest {

    private static CombatSkill skill(String script, int targetType, int damageType,
                                     int canRush, int packCategory, String relevance) {
        return new CombatSkill("X", 1, script, 1,
                1, 0, "TL", 1000, 7000,
                0, 0, 0,
                100, 1,
                CombatSkill.TargetMode.SELECTED, 5f, 0f, 0f, 0f,
                canRush != 0, 0f, true,
                targetType, damageType, relevance, packCategory,
                "act", 500L, List.of(), List.of(), List.of(), Set.of());
    }

    @Test
    void lockCriterionMatrix() {
        // myjf01/04/08：SkillNormal + CanRush=0 + TT4 + DT3 → 参考服 55866 实证带 aim
        assertTrue(skill("SkillNormal", 4, 3, 0, 0, "").lockSkill(), "myjf01/08 形态须锁定");
        // chz03：TT4 + DT0 → §13 反例保护（空目标）
        assertFalse(skill("SkillNormal", 4, 0, 0, 0, "").lockSkill(), "chz03 形态不锁定");
        // myjf05：TT8 → 空目标
        assertFalse(skill("SkillNormal", 8, 3, 0, 0, "").lockSkill(), "TT8 形态不锁定");
        // 老规则两支保持
        assertTrue(skill("SkillLock", 8, 0, 0, 0, "").lockSkill(), "SkillLock 脚本锁定");
        assertTrue(skill("SkillNormal", 8, 0, 1, 0, "").lockSkill(), "CanRush 冲锋锁定");
        // yqq02 = SkillLock+TT4+DT0 → 走 script 支锁定
        assertTrue(skill("SkillLock", 4, 0, 1, 0, "").lockSkill());
        // 缺 TargetType 数据（0）回落 script/CanRush 判
        assertFalse(skill("SkillNormal", 0, 3, 0, 0, "").lockSkill(), "无 TT 数据不冒进");
    }

    @Test
    void scriptLabelFollowsLock() {
        assertEquals("SkillLock", skill("SkillNormal", 4, 3, 0, 0, "").scriptLabel(),
                "TT4 锁定族飘字标签改 SkillLock（参考服对 myjf01/08 实证）");
        assertEquals("SkillNormal", skill("SkillNormal", 4, 0, 0, 0, "").scriptLabel());
        assertEquals("SkillNormal", skill("SkillNormal", 8, 3, 0, 0, "").scriptLabel());
    }

    @Test
    void wujiQualification() {
        CombatSkill myjf08 = skill("SkillNormal", 4, 3, 0, 2, "wuji_CS_jh_myjf08");
        assertTrue(myjf08.wujiRelevance());
        assertTrue(myjf08.wujiCombo(), "wuji_* + PackCategory=2（怒招）才出连击段");
        CombatSkill myjf03 = skill("SkillLock", 4, 0, 1, 0, "wuji_CS_jh_myjf03");
        assertTrue(myjf03.wujiRelevance(), "myjf03 同为 wuji 族（出 169 段）");
        assertFalse(myjf03.wujiCombo(), "非怒招不出 c1=71 连击段");
        assertFalse(skill("SkillNormal", 4, 3, 0, 2, "").wujiRelevance());
    }

    @Test
    void endOfActionEpochPairing() {
        PlayerActor player = PlayerActor.create("tester", 1, java.time.Clock.systemUTC());
        Instant now = Instant.now();
        CombatSkill a = skill("SkillNormal", 8, 0, 0, 0, "");
        CombatSkill b = skill("SkillNormal", 8, 0, 0, 0, "");
        assertTrue(player.beginSkillUse(a, 0, now).allowed());
        long epochA = player.currentSkillEpoch();
        // 1-3s 连招：B 在 A 收尾定时器触发前顶掉 A
        assertTrue(player.beginSkillUse(b, 0, now.plusMillis(1500)).allowed());
        long epochB = player.currentSkillEpoch();
        assertFalse(player.clearCurrentSkill("X", epochA), "旧定时器按 epoch 空转（不误删新招行）");
        assertTrue(player.clearCurrentSkill("X", epochB), "新招自己的定时器照常删行");
        // 同技能快速复读：id 相同，靠 epoch 区分（b 的个人 CD 1s 已过）
        assertTrue(player.beginSkillUse(a, 0, now.plusMillis(2600)).allowed());
        long epochA2 = player.currentSkillEpoch();
        assertFalse(player.clearCurrentSkill("X", epochA), "复读后旧代次不得删新招");
        assertTrue(player.clearCurrentSkill("X", epochA2));
        assertEquals("", player.currentSkillId());
    }

    /** 生产树目录级校验（F 盘 Java 服资源在才跑）：编译产物携带 TT/DT/wuji 字段。 */
    @Test
    void catalogCompilesLockFieldsFromProductionTree() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(
                        java.nio.file.Path.of("F:/Game/Online/9yin/9yin_server_new/resources/modern/share/skill")),
                "生产 share/skill 资源不可用，跳过");
        com.nineyin.util.ServerPaths.overrideServerRoot(
                java.nio.file.Path.of("F:/Game/Online/9yin/9yin_server_new"));
        CombatSkillCatalog catalog = CombatSkillCatalog.load();
        assertTrue(catalog.definition("CS_jh_myjf01", 1).lockSkill(), "myjf01 TT4+DT3 锁定");
        assertTrue(catalog.definition("CS_jh_myjf04", 1).lockSkill(), "myjf04 TT4+DT3 锁定");
        assertTrue(catalog.definition("CS_jh_myjf08", 1).lockSkill(), "myjf08 TT4+DT3 锁定");
        assertFalse(catalog.definition("CS_jh_myjf05", 1).lockSkill(), "myjf05 TT8 空目标");
        CombatSkill chz03 = catalog.definition("CS_jh_chz03", 1);
        if (chz03 != null) {
            assertFalse(chz03.lockSkill(), "chz03 TT4+DT0 空目标（§13 反例保护）");
        }
        CombatSkill myjf08 = catalog.definition("CS_jh_myjf08", 1);
        assertTrue(myjf08 != null && myjf08.wujiCombo(), "myjf08 wuji_*+PackCategory=2 连击段");
        assertEquals("SkillLock", myjf08.scriptLabel(), "myjf08 飘字标签重分类");
    }
}
