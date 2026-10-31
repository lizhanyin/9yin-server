package com.nineyin.game;

import com.nineyin.scene.SceneManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 技能编译目录与学习表：fixture ini 链的编译断言 + 真实 share 数据抽查（缺数据时跳过）。 */
class SkillCatalogTest {

    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    private Path buildModernRoot() throws Exception {
        Path skill = tempDir.resolve("share/skill");
        Path action = tempDir.resolve("ini/action");
        Files.createDirectories(skill);
        Files.createDirectories(action);
        Files.writeString(skill.resolve("skill_new.ini"), """
                [test_self]
                StaticData=900001
                script=SkillNormal
                ItemType=1000
                AddCoolDownTime=7000

                [test_atk]
                StaticData=900002
                script=SkillNormal
                ItemType=1000

                [test_bad_script]
                StaticData=900003
                script=SkillPassive
                """, GBK);
        Files.writeString(skill.resolve("skill_static.ini"), """
                [900001]
                MinVarPropNo=800001
                MaxVarPropNo=800005
                CoolDownCategory=12446
                CoolDownTeam=50267
                IsDamage=0
                TaoLu=test

                [900002]
                MinVarPropNo=800011
                MaxVarPropNo=800015
                CoolDownCategory=12447
                CoolDownTeam=0
                IsDamage=1
                TaoLu=test

                [900003]
                MinVarPropNo=800021
                MaxVarPropNo=800025
                """, GBK);
        Files.writeString(skill.resolve("skill_normal_varprop.ini"), """
                [800001]
                Level=1
                Consume=15608
                HitShapePkg=1
                CoolDownTime=15000

                [800002]
                Level=2
                Consume=15608
                HitShapePkg=1
                CoolDownTime=14000

                [800011]
                Level=1
                Consume=15608
                HitShapePkg=1
                TargetShapePkg=7
                HitCount=3
                CoolDownTime=15000
                DamageCalculate=23033
                """, GBK);
        Files.writeString(skill.resolve("skill_consume.ini"), """
                [15608]
                AConsumeMP=11
                AConsumeSP=3
                AConsumeQGP=5
                """, GBK);
        Files.writeString(skill.resolve("damage_calculate.ini"), """
                [23033]
                r=494,JujDamage,113,1,1,
                r=495,JujDamage,27,1,1,
                """, GBK);
        Files.writeString(skill.resolve("attack_hitshape.ini"), """
                [1]
                HitShape=0
                HitShapePara1=0
                HitShapePara2=0
                """, GBK);
        Files.writeString(skill.resolve("attack_targetshape.ini"), """
                [7]
                TargetShape=1
                TargetShapePara1=0
                TargetShapePara2=6
                """, GBK);
        Files.writeString(skill.resolve("buff_new.ini"), """
                [buf_test_self]
                StaticData=15105
                LifeTime=5000
                """, GBK);
        Files.writeString(skill.resolve("buff_static.ini"), """
                [15105]
                IsDamage=0
                MinVarPropNo=296913
                MaxVarPropNo=296913
                """, GBK);
        Files.writeString(skill.resolve("buff_varprop.ini"), """
                [296913]
                Level=1
                LifeTime=6000
                """, GBK);
        Files.writeString(action.resolve("zhaoshi_player.ini"), """
                [test_self]
                1=self_act;0;30;-1;-1;1
                2=self_act2;0;20;0;0;1

                [test_atk]
                1=atk_act;0;43;-1;-1;1
                """, GBK);
        return tempDir;
    }

    @Test
    void compilesFixtureSkillChain() throws Exception {
        SkillCatalog catalog = new SkillCatalog();
        catalog.load(buildModernRoot());
        assertEquals(2, catalog.indexedCount(), "仅 SkillNormal/SkillLock 入索引");
        assertEquals(1, catalog.skipReasons().getOrDefault("unsupported_script", 0));
        assertEquals(2, catalog.levelOneCount());

        SkillCatalog.Definition self = catalog.definition("test_self", 1);
        assertNotNull(self);
        assertEquals(900001, self.staticData());
        assertEquals(11, self.mpCost());
        assertEquals(3, self.spCost());
        assertEquals(5, self.qgCost());
        assertEquals(15000, self.personalCdMs());
        assertEquals(7000, self.publicCdMs(), "varprop 无 AddCoolDownTime → skill_new AddCoolDownTime");
        assertEquals(12446, self.cooldownCategory());
        assertEquals(50267, self.cooldownTeam());
        assertEquals(SkillCatalog.TargetMode.SELF, self.targetMode());
        assertTrue(self.actionName().equals("self_act"));
        assertEquals(1, self.followupActions().size(), "第二段动作排期");
        assertEquals(1000, self.followupActions().get(0).atMs(), "30 帧起手 - 0 入融 = 1000ms");
        assertTrue(self.effects().stream().anyMatch(e -> e.kind() == SkillCatalog.EffectKind.PLAYER_BUFF),
                "buf_test_self 编译为自身 Buff");
        SkillCatalog.Effect buffEffect = self.effects().stream()
                .filter(e -> e.kind() == SkillCatalog.EffectKind.PLAYER_BUFF).findFirst().orElseThrow();
        assertEquals(15105, buffEffect.buff().staticData());
        assertEquals(6000, buffEffect.buff().lifetimeMs(), "同级 buff_varprop LifeTime 覆盖 buff_new");

        // 二级按等级惰性编译
        SkillCatalog.Definition selfL2 = catalog.definition("test_self", 2);
        assertNotNull(selfL2);
        assertEquals(14000, selfL2.personalCdMs());

        SkillCatalog.Definition atk = catalog.definition("test_atk", 1);
        assertNotNull(atk);
        assertEquals(140, atk.baseDamage(), "113 + 27 两行 r 求和");
        assertTrue(atk.requiresTarget());
        assertEquals(SkillCatalog.TargetMode.SELECTED, atk.targetMode());
        assertEquals(15000, atk.publicCdMs(), "无 AddCoolDownTime → 回退个人 CD");

        // 施法射程（attack_targetshape.ini）：TargetShape=1 圆形 → Para2；无 pkg → 0 不设门
        assertEquals(6f, atk.targetRangeMeters(), "TargetShapePkg=7 → 半径 6 米");
        assertEquals(0f, self.targetRangeMeters(), "test_self 无 TargetShapePkg → 不校验");

        // 多跳波次（HitCount）：显式 3；缺省（0/无字段）回退 1
        assertEquals(3, atk.hitCount(), "HitCount 编译进定义");
        assertEquals(1, self.hitCount(), "无 HitCount → 单发");

        assertNull(catalog.definition("test_bad_script", 1));
        assertNull(catalog.definition("no_such_skill", 1));
        assertNull(catalog.definition("test_self", 0));
    }

    @Test
    void skillInitTableLoadsAndFallsBack() throws Exception {
        buildModernRoot();
        SkillInitTable table = new SkillInitTable();
        table.load(tempDir.resolve("share/skill/skill_init.ini"));
        // buildModernRoot 未写 skill_init.ini → 回退内置 23 技
        assertTrue(table.usingBuiltInFallback());
        assertEquals(23, table.size());
        assertEquals(1, table.level("CS_wd_tjq01"));
        assertEquals(0, table.level("test_self"), "回退集不含 fixture 技能");

        Path ini = tempDir.resolve("share/skill/skill_init.ini");
        Files.writeString(ini, """
                [test_self]
                staticData=900001
                itemType=1000
                level=1
                maxlevel=1
                coolDown=0
                """, GBK);
        SkillInitTable loaded = new SkillInitTable();
        loaded.load(ini);
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.level("test_self"));
        assertEquals(0, loaded.level("CS_wd_tjq01"), "文件存在时以内置集为回退的行为关闭");
    }

    /** 真实 share 数据抽查（需要 NINEYIN_SERVER_ROOT 或仓库上层 resources/modern/share）。 */
    @Test
    void realShareDataSpotChecks() {
        Path shareRoot = SceneManager.locateShareRoot();
        assumeTrue(Files.isRegularFile(shareRoot.resolve("skill").resolve("skill_new.ini")),
                "真实 share 数据不可见，跳过：" + shareRoot);
        SkillCatalog catalog = new SkillCatalog();
        catalog.load(shareRoot.getParent());
        assertTrue(catalog.indexedCount() > 1000, "全量索引，当前数据 1.7 万+");
        assertTrue(catalog.levelOneCount() > 500, "一级可执行面");

        // 花神五式：MP 11、个人 CD 15s、公共 CD 7s、自身增益（IsDamage=0）+ 红霸体 overlay
        SkillCatalog.Definition hsqs05 = catalog.definition("CS_yhwq_hsqs05", 1);
        assertNotNull(hsqs05, "CS_yhwq_hsqs05 必须可编译（参考实现核心用例）");
        assertEquals(11, hsqs05.mpCost());
        assertEquals(15000, hsqs05.personalCdMs());
        assertEquals(7000, hsqs05.publicCdMs());
        assertEquals(SkillCatalog.TargetMode.SELF, hsqs05.targetMode());
        assertTrue(hsqs05.effects().stream().anyMatch(e -> e.kind() == SkillCatalog.EffectKind.PLAYER_BUFF));
        assertTrue(hsqs05.effects().stream().anyMatch(e -> e.kind() == SkillCatalog.EffectKind.RED_ARMOR));

        // 花神一式：一级编译伤害 113（damage_calculate 23033 单行）
        SkillCatalog.Definition hsqs01 = catalog.definition("CS_yhwq_hsqs01", 1);
        assertNotNull(hsqs01);
        assertEquals(113, hsqs01.baseDamage());
        assertTrue(hsqs01.requiresTarget());

        // 太极四式：架势轮换（守/攻两个 Buff 变体）
        SkillCatalog.Definition tjq04 = catalog.definition("CS_wd_tjq04", 1);
        assertNotNull(tjq04);
        assertTrue(tjq04.effects().stream().anyMatch(
                        e -> e.kind() == SkillCatalog.EffectKind.STANCE_CYCLE && e.buffVariants().size() == 2),
                "架势轮换 overlay");

        SkillInitTable table = new SkillInitTable();
        table.load(shareRoot.resolve("skill").resolve("skill_init.ini"));
        assertTrue(table.size() > 10000, "全量学习表");
        assertEquals(1, table.level("CS_wd_tjq01"));
    }
}
