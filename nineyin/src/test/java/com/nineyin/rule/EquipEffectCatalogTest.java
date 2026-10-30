package com.nineyin.rule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 装备特效目录（§3.17 四跳链路装载）：包→buff→static→varprop 的逐跳解析与等级取行。 */
class EquipEffectCatalogTest {

    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    private EquipEffectCatalog catalog() throws Exception {
        Path skill = tempDir.resolve("skill");
        Path rule = tempDir.resolve("rule");
        Files.createDirectories(skill);
        Files.createDirectories(rule);
        Files.writeString(skill.resolve("buff_new.ini"), """
                [eqp_ImmStun]
                StaticData=5885
                script=Buffer

                [eqp_WpResParry]
                StaticData=5866
                script=Buffer

                [eqp_wp_06]
                StaticData=11143
                script=Buffer
                """, GBK);
        Files.writeString(skill.resolve("buff_static.ini"), """
                [5885]
                MinVarPropNo=110221
                MaxVarPropNo=110222
                Visible=0
                CantCustomRemove=1

                [5866]
                MinVarPropNo=109701
                MaxVarPropNo=109706

                [11143]
                MinVarPropNo=200001
                MaxVarPropNo=200002
                """, GBK);
        Files.writeString(skill.resolve("buff_varprop.ini"), """
                [110221]
                Level=1
                EffectRatio=100
                Immunity=33

                [110222]
                Level=2
                Immunity=34

                [109701]
                Level=1
                @PropModifyPackRec=95021

                [109706]
                Level=6
                @PropModifyPackRec=95021;95061
                Immunity=38

                [200001]
                Level=1
                EffectRatio=100
                """, GBK);
        Files.writeString(rule.resolve("equip_special_effect_pack.ini"), """
                [10013]
                EquipAddBuff=eqp_ImmStun,1

                [1647]
                EquipAddBuff=eqp_WpResParry,6;eqp_wp_06,1
                """, GBK);
        EquipEffectCatalog catalog = new EquipEffectCatalog();
        catalog.load(tempDir);
        return catalog;
    }

    @Test
    void parsesPackToBuffChainWithLevels() throws Exception {
        EquipEffectCatalog catalog = catalog();
        assertEquals(2, catalog.size());
        List<EquipEffectCatalog.EquipBuff> buffs = catalog.buffsOfPack(10013);
        assertEquals(1, buffs.size());
        assertEquals("eqp_ImmStun", buffs.get(0).buffId());
        assertEquals(1, buffs.get(0).level());
        assertEquals(5885, buffs.get(0).staticData());
        assertEquals(110221, buffs.get(0).minVarProp());
        assertEquals(110222, buffs.get(0).maxVarProp());
        // 双 buff 包（分号分隔；等级取 EquipAddBuff 列）
        List<EquipEffectCatalog.EquipBuff> dual = catalog.buffsOfPack(1647);
        assertEquals(2, dual.size());
        assertEquals(6, dual.get(0).level());
        assertTrue(catalog.buffsOfPack(999).isEmpty(), "未知包返回空表");
    }

    @Test
    void resolvesVarpropEffectsByLevelWithFloorFallback() throws Exception {
        EquipEffectCatalog catalog = catalog();
        // 精确级：eqp_ImmStun@1 → Immunity=33
        assertEquals(33, catalog.immunitiesOf("eqp_ImmStun", 1).iterator().next());
        // 精确级：eqp_WpResParry@6 → 双 proppack + Immunity=38
        assertEquals(List.of(95021, 95061), catalog.propPacksOf("eqp_WpResParry", 6));
        assertTrue(catalog.immunitiesOf("eqp_WpResParry", 6).contains(38));
        // floor 回退：@5 无精确行 → 取 ≤5 最大级（Level=1 行）
        assertEquals(List.of(95021), catalog.propPacksOf("eqp_WpResParry", 5));
        // 高于全部等级 → 取最大级行
        assertTrue(catalog.immunitiesOf("eqp_ImmStun", 9).contains(34));
        // 无效果行（eqp_wp_06 只有 EffectRatio）
        assertTrue(catalog.propPacksOf("eqp_wp_06", 1).isEmpty());
        assertTrue(catalog.immunitiesOf("eqp_wp_06", 1).isEmpty());
    }

    /** 真实服务器表装载冒烟（NINEYIN_SERVER_ROOT 未设置时跳过）：563 包 + 实测值抽查。 */
    @Test
    void loadsRealCatalogUnderServerRoot() {
        String root = System.getenv("NINEYIN_SERVER_ROOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null && !root.isEmpty(),
                "NINEYIN_SERVER_ROOT 未设置，跳过真实表装载");
        EquipEffectCatalog catalog = new EquipEffectCatalog();
        catalog.load(java.nio.file.Path.of(root, "resources", "modern", "share"));
        org.junit.jupiter.api.Assertions.assertTrue(catalog.size() > 500,
                "真实表 563 包，实得 " + catalog.size());
        // 实测：helmet 10013=eqp_ImmStun@1（Immunity=33）；1647 双 buff
        var buffs = catalog.buffsOfPack(10013);
        org.junit.jupiter.api.Assertions.assertEquals("eqp_ImmStun", buffs.get(0).buffId());
        org.junit.jupiter.api.Assertions.assertTrue(
                catalog.immunitiesOf("eqp_ImmStun", 1).contains(33), "头盔免疫位 33");
        org.junit.jupiter.api.Assertions.assertEquals(2, catalog.buffsOfPack(1647).size(), "sword_60702 双 buff");
    }
}
