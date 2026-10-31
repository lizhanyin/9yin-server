package com.nineyin.game;

import com.nineyin.db.RoleInventoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装备词缀加成链（§3.7）：proppack.ini 解析 → propBuilt 引用 → equip*Add 累积 →
 * combatStats/有效上限叠加。应用面只含文档化换算（PhyVa/MeleePower→攻击、
 * PhyDef→防御、Dex/PhyHit→命中、Dodge→闪避、Str→暴击值、MaxHP/MaxMP→上限）。
 */
class EquipBonusTest {

    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    private PropPackCatalog catalog() throws Exception {
        Path ini = tempDir.resolve("proppack.ini");
        Files.writeString(ini, """
                [2]
                Comment=臂力+2
                r=,StrAdd,2,0

                [3]
                Comment=体魄装
                r=,PhyDefAdd,5,0
                r=,MaxHPAdd,100,0

                [4]
                Comment=身法/闪避/攻击混合
                r=,DexAdd,8,0
                r=,DodgeAdd,6,0
                r=,PhyVaAdd,20,0
                r=,MeleePowerAdd,10,0
                r=,MaxMPAdd,50,0

                [6392]
                Comment=概率词缀（累积层不覆盖）
                r=,PhyDefProbAdd,30,1
                r=,MagicHitAdd,30,1

                [12592]
                Comment=实测包：体质（商弘护腿 3 连叠）
                r=,StaAdd,39,0

                [62995]
                Comment=实测包：物理防御（后缀省略变体）
                r=,PhyDef,475,0

                [95061]
                Comment=实测包：小数值词条
                r=,AirRushDistAdd,0.4,0
                """, GBK);
        return PropPackCatalog.load(ini);
    }

    private static RoleInventoryStore.Equipped affixed(String propBuilt) {
        return new RoleInventoryStore.Equipped(1, "eq_test", 2, "Weapon", 100, 100, propBuilt);
    }

    @Test
    void parsesPackRowsIncludingMultiRowSections() throws Exception {
        PropPackCatalog catalog = catalog();
        assertEquals(7, catalog.size());
        assertEquals(1, catalog.modsOf(2).size());
        assertEquals("StrAdd", catalog.modsOf(2).get(0).prop());
        assertEquals(2, catalog.modsOf(2).get(0).value());
        assertEquals(2, catalog.modsOf(3).size(), "多 r= 行并存");
        assertEquals(0, catalog.modsOf(999).size(), "未知包号返回空表");
        // 小数值词条（实测 5174 行）：按 double 解析后截断保留，不再整行丢弃
        assertEquals(1, catalog.modsOf(95061).size(), "小数值行保留");
        assertEquals(0, catalog.modsOf(95061).get(0).value(), "0.4 截断为 0");
    }

    /** 实测形态（商弘护腿 leg_60702）：同包号重复叠加 + PhyDef 后缀省略变体入防御。 */
    @Test
    void accumulatesRepeatedPacksAndSuffixlessPhyDef() throws Exception {
        PropPackCatalog catalog = catalog();
        PlayerActor actor = new PlayerActor();
        actor.recomputeEquipBonuses(catalog, List.of(
                affixed("1,12592;1,12592;1,12592;1,62995;1,62995;1,62995")));
        assertEquals(117, actor.equipStaAdd, "3×StaAdd39 叠加");
        assertEquals(1425, actor.equipPhyDef, "3×PhyDef475 叠加（此前 PhyDef 名未映射丢失）");
    }

    /** 装备特效（§3.18）：EffectPackID → buff varprop 的 @PropModifyPackRec 叠加 + 免疫位收集。 */
    @Test
    void appliesEquipEffectBuffStatPacksAndImmunities() throws Exception {
        PropPackCatalog packs = catalog();
        com.nineyin.rule.EquipEffectCatalog effects = effectCatalog();
        PlayerActor actor = new PlayerActor();
        // 词缀层 475 + 特效层（1647: eqp_test_a@6 的 PPR=62995 再叠 475；b@1 免疫位 33）
        actor.recomputeEquipBonuses(packs, List.of(affixed("1,62995")));
        actor.applyEquipEffects(effects, packs, List.of(1647));
        assertEquals(950, actor.equipPhyDef, "词缀 475 + 特效 varprop proppack 475 叠加");
        assertEquals(java.util.Set.of(33), actor.equipImmunities(), "eqp_test_b@1 免疫位");
        // 重算归零后再走属性+免疫双效包（63000: a@1 → PPR 62995 + Immunity 38）
        actor.recomputeEquipBonuses(packs, List.of());
        actor.applyEquipEffects(effects, packs, List.of(63000));
        assertEquals(475, actor.equipPhyDef);
        assertEquals(java.util.Set.of(38), actor.equipImmunities(), "a@1 行带 Immunity=38");
        // null 目录清空免疫位
        actor.applyEquipEffects(null, packs, List.of(1647));
        assertTrue(actor.equipImmunities().isEmpty());
    }

    /** 特效目录 fixture（带属性词条的 63000 包用于 PlayerActor 叠加断言）。 */
    private com.nineyin.rule.EquipEffectCatalog effectCatalog() throws Exception {
        Path skill = tempDir.resolve("skill");
        Path rule = tempDir.resolve("rule");
        Files.createDirectories(skill);
        Files.createDirectories(rule);
        Files.writeString(skill.resolve("buff_new.ini"), """
                [eqp_test_a]
                StaticData=7001
                [eqp_test_b]
                StaticData=7002
                """, GBK);
        Files.writeString(skill.resolve("buff_static.ini"), """
                [7001]
                MinVarPropNo=300001
                MaxVarPropNo=300006
                [7002]
                MinVarPropNo=300101
                MaxVarPropNo=300102
                """, GBK);
        Files.writeString(skill.resolve("buff_varprop.ini"), """
                [300001]
                Level=1
                @PropModifyPackRec=62995
                Immunity=38
                [300006]
                Level=6
                @PropModifyPackRec=62995
                [300101]
                Level=1
                Immunity=33
                """, GBK);
        Files.writeString(rule.resolve("equip_special_effect_pack.ini"), """
                [1647]
                EquipAddBuff=eqp_test_a,6;eqp_test_b,1
                [10013]
                EquipAddBuff=eqp_test_b,1
                [63000]
                EquipAddBuff=eqp_test_a,1
                """, GBK);
        com.nineyin.rule.EquipEffectCatalog catalog = new com.nineyin.rule.EquipEffectCatalog();
        catalog.load(tempDir);
        return catalog;
    }

    @Test
    void accumulatesDocumentedConversionsIntoCombatStatsAndCaps() throws Exception {
        PropPackCatalog catalog = catalog();
        PlayerActor actor = new PlayerActor();
        actor.recomputeEquipBonuses(catalog, List.of(
                affixed("1,2;1,3"), affixed("1,4;1,6392")));
        // 累积：Str+2、PhyDef+5、MaxHP+100、Dex+8、Dodge+6、PhyVa+20、MeleePower+10、MaxMP+50、MagicHit+30
        assertEquals(2, actor.equipStrAdd);
        assertEquals(5, actor.equipPhyDef);
        assertEquals(8, actor.equipDexAdd);
        assertEquals(6, actor.equipDodge);
        assertEquals(20, actor.equipPhyVa);
        assertEquals(10, actor.equipMeleePowerAdd);
        assertEquals(30, actor.equipMagicHit, "MagicHitAdd 累积但不应用");
        // 有效上限（含五维换算：HP += 7×体魄 + 2×臂力；MP += 4×内息 + 罡气；基础五维各 30）
        assertEquals(1374, actor.maxHp());
        assertEquals(700, actor.maxMp());
        // combatStats 叠加（基础字段不动）
        CombatStats stats = actor.combatStats();
        assertEquals(actor.attack + 30, stats.attack(), "PhyVa+MeleePower → 攻击");
        assertEquals(actor.defense + 5, stats.defense());
        assertEquals(actor.accuracy + 8, stats.accuracy(), "Dex 1:1 → 命中；MagicHit 不入命中");
        assertEquals(actor.evasion + 6, stats.evasion());
        assertEquals(actor.critValue + 2, stats.critValue(), "Str 1:1 → 暴击值");
    }

    @Test
    void recomputeIsIdempotentAndResetsOnEmpty() throws Exception {
        PropPackCatalog catalog = catalog();
        PlayerActor actor = new PlayerActor();
        // 两件装备各带同词缀：跨装备叠加（对齐 Go 逐件累积；基础五维换算 270 常在）
        actor.recomputeEquipBonuses(catalog, List.of(affixed("1,3"), affixed("1,3")));
        assertEquals(1470, actor.maxHp(), "两件同词缀装备叠加");
        // 同一装备列表重算：幂等不重复计
        actor.recomputeEquipBonuses(catalog, List.of(affixed("1,3")));
        assertEquals(1370, actor.maxHp());
        actor.recomputeEquipBonuses(catalog, List.of(affixed("1,3")));
        assertEquals(1370, actor.maxHp());
        actor.recomputeEquipBonuses(catalog, List.of());
        assertEquals(1270, actor.maxHp(), "卸空后回到基础+五维换算");
        assertEquals(650, actor.maxMp());
        assertEquals(0, actor.equipPhyDef);
        // null 目录（缺失装载）同样清零不抛
        actor.recomputeEquipBonuses(null, List.of(affixed("1,3")));
        assertEquals(0, actor.equipPhyDef);
    }

    @Test
    void dirtyPropBuiltRowsAreSkipped() throws Exception {
        PropPackCatalog catalog = catalog();
        PlayerActor actor = new PlayerActor();
        actor.recomputeEquipBonuses(catalog, List.of(
                affixed(""), affixed(null), affixed("x"), affixed("1,zz"), affixed("1,999")));
        assertEquals(1270, actor.maxHp(), "脏行全部跳过，无加成（基础+五维换算）");
    }

    /** 真实服务器表装载冒烟（NINEYIN_SERVER_ROOT 未设置时跳过）：~3.7 万节 + 已知节抽查。 */
    @Test
    void loadsRealCatalogUnderServerRoot() {
        String root = System.getenv("NINEYIN_SERVER_ROOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null && !root.isEmpty(),
                "NINEYIN_SERVER_ROOT 未设置，跳过真实表装载");
        PropPackCatalog catalog = PropPackCatalog.load(java.nio.file.Path.of(
                root, "resources", "modern", "share", "modifypack", "proppack.ini"));
        org.junit.jupiter.api.Assertions.assertTrue(catalog.size() > 30_000,
                "真实表约 3.7 万节，实得 " + catalog.size());
        // 已知节抽查：[2]=StrAdd+2 / [3]=StrAdd+3（真实表头样例）
        org.junit.jupiter.api.Assertions.assertEquals("StrAdd", catalog.modsOf(2).get(0).prop());
        org.junit.jupiter.api.Assertions.assertEquals(2, catalog.modsOf(2).get(0).value());
        org.junit.jupiter.api.Assertions.assertEquals(3, catalog.modsOf(3).get(0).value());
    }
}
