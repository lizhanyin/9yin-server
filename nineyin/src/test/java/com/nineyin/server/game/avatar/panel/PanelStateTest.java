package com.nineyin.server.game.avatar.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.PropertyValues;

/**
 * 面板实时状态四期单测：词缀累积向量（抓包实证 12592=StaAdd,39 / 62995=PhyDef,475）、
 * 特效包链、幂等重算与身份块按名输出。
 */
class PanelStateTest {

    @TempDir
    Path tempDir;

    private static final String PROPPACK = """
            [12592]
            r=,StaAdd,39,0

            [62995]
            r=,PhyDef,475,0

            [30001]
            r=,MaxHPAdd,120,0
            r=,PhyDefAdd,35,0

            [30002]
            r=,MeleePower,77,0

            [30003]
            r=,SomeUnknownProp,9,0

            [dirty]
            r=,NotANumber,abc,0
            """;

    @Test
    void affixCatalogParsesPackRows() throws IOException {
        Path ini = tempDir.resolve("proppack.ini");
        Files.write(ini, PROPPACK.getBytes(StandardCharsets.ISO_8859_1));
        EquipAffixCatalog catalog = EquipAffixCatalog.loadIni(ini);
        assertEquals(5, catalog.size(), "数字节 5 个（12592/62995/30001/30002/30003；dirty 节丢弃）");
        assertEquals(List.of(new EquipAffixCatalog.Mod("StaAdd", 39, 0)), catalog.modsOf(12592));
        assertEquals(new EquipAffixCatalog.Mod("PhyDef", 475, 0), catalog.modsOf(62995).get(0));
        assertTrue(catalog.modsOf(99999).isEmpty(), "未知包号=空表");
    }

    @Test
    void recomputeAccumulatesAndIsIdempotent() throws IOException {
        Path ini = tempDir.resolve("proppack.ini");
        Files.write(ini, PROPPACK.getBytes(StandardCharsets.ISO_8859_1));
        EquipAffixCatalog catalog = EquipAffixCatalog.loadIni(ini);
        PanelState panel = new PanelState();
        // 抓包实证形态：同包号堆叠 3×12592（体质+117）+ 3×62995（物防+1425）
        List<String> propBuilts = List.of("1,12592;1,12592;1,12592", "1,62995;1,62995;1,62995");
        panel.recomputeEquipBonuses(catalog, propBuilts);
        assertEquals(117, panel.equipBonus().staAdd(), "3×StaAdd39");
        assertEquals(1425, panel.equipBonus().phyDef(), "3×PhyDef475（无后缀变体同累积器）");
        // 幂等：重复调用不翻倍
        panel.recomputeEquipBonuses(catalog, propBuilts);
        assertEquals(117, panel.equipBonus().staAdd());
        assertEquals(1425, panel.equipBonus().phyDef());
        // 未覆盖词缀（MeleePower→有映射；SomeUnknownProp→忽略）与多行包
        panel.recomputeEquipBonuses(catalog, List.of("1,30001;1,30002;1,30003"));
        assertEquals(120, panel.equipBonus().maxHpAdd());
        assertEquals(35, panel.equipBonus().phyDef(), "PhyDefAdd 同累积器");
        assertEquals(77, panel.equipBonus().meleePowerAdd(), "MeleePower 无后缀变体");
        // 清空：无词缀时归零
        panel.recomputeEquipBonuses(catalog, java.util.Arrays.asList("", null));
        assertEquals(0, panel.equipBonus().staAdd());
        assertEquals(0, panel.equipBonus().phyDef());
    }

    @Test
    void equipEffectChainAppliesPropPacksAndImmunity() throws IOException {
        Path share = tempDir.resolve("share");
        Files.createDirectories(share.resolve("skill"));
        Files.createDirectories(share.resolve("rule"));
        write(share, "skill/buff_new.ini", """
                [eqp_test]
                StaticData=9001
                """);
        write(share, "skill/buff_static.ini", """
                [9001]
                MinVarPropNo=500
                MaxVarPropNo=501
                """);
        write(share, "skill/buff_varprop.ini", """
                [500]
                Level=1
                @PropModifyPackRec=12592
                Immunity=3

                [501]
                Level=3
                @PropModifyPackRec=30001;62995
                """);
        write(share, "rule/equip_special_effect_pack.ini", """
                [7001]
                EquipAddBuff=eqp_test,3
                """);
        write(share, "modifypack/proppack.ini", PROPPACK);

        EquipEffectCatalog effects = EquipEffectCatalog.loadShare(share);
        assertEquals(1, effects.size());
        assertEquals(1, effects.buffsOfPack(7001).size());
        assertEquals(0, effects.buffsOfPack(9999).size());
        // level=3 → floor 到 [501] 行：两个属性包 + 无 Immunity
        assertEquals(List.of(30001, 62995), effects.propPacksOf("eqp_test", 3));
        assertTrue(effects.immunitiesOf("eqp_test", 3).isEmpty());
        // level=1 → 精确 [500] 行：属性包 12592 + Immunity 3
        assertEquals(List.of(12592), effects.propPacksOf("eqp_test", 1));
        assertTrue(effects.immunitiesOf("eqp_test", 1).contains(3));

        EquipAffixCatalog affixes = EquipAffixCatalog.loadIni(share.resolve("modifypack/proppack.ini"));
        PanelState panel = new PanelState();
        panel.recomputeEquipBonuses(affixes, List.of("1,12592"));
        panel.applyEquipEffects(effects, affixes, List.of(7001));
        // 词缀层 39 + 特效层（level3 → 30001+62995 → MaxHPAdd120 + PhyDefAdd35 + PhyDef475）
        assertEquals(39, panel.equipBonus().staAdd(), "词缀层保留");
        assertEquals(120, panel.equipBonus().maxHpAdd(), "特效层经 proppack 复用累积器");
        assertEquals(475 + 35, panel.equipBonus().phyDef(), "多包叠加（PhyDef475+PhyDefAdd35）");
        assertTrue(panel.equipImmunities().isEmpty(), "level=3 行无 Immunity");
    }

    @Test
    void identityBlockWritesOfficialNamesWithSeedValues() {
        PanelState panel = new PanelState();
        panel.powerLevel = 137;
        panel.justice = 3200;
        panel.evil = 21;
        panel.pkValue = 5;
        List<PropertyValues.Indexed> properties = panel.identityProperties();
        Map<String, PropertyValues.Value> byName = new java.util.HashMap<>();
        for (PropertyValues.Indexed property : properties) {
            byName.put(property.name(), property.value());
        }
        assertEquals(PropertyTable.idx("PowerLevel"), byNameIndex(properties, "PowerLevel"));
        assertEquals(137, byName.get("PowerLevel").i32());
        assertEquals("title026", byName.get("LevelTitle").text(), "137→title026 档位");
        assertEquals(3200, byName.get("Justice").i32());
        assertEquals(21, byName.get("Evil").i32());
        assertEquals(5, byName.get("PKValue").i32(), "官方表键名 PKValue");
        assertEquals(0, byName.get("PKMode").i32(), "官方表键名 PKMode（缺省 0）");
        assertEquals(15, properties.size(), "身份块 15 项");
        // 种子：151 → title027（与选人界面同档位映射）
        assertEquals("title027", com.nineyin.server.protocol.LoginMessages.roleListTitle(
                PanelState.SEED_POWER_LEVEL));
    }

    private static int byNameIndex(List<PropertyValues.Indexed> properties, String name) {
        for (PropertyValues.Indexed property : properties) {
            if (property.name().equals(name)) {
                return property.int16Index();
            }
        }
        return -1;
    }

    @Test
    void emptyCatalogsDisableBonusesGracefully() {
        PanelState panel = new PanelState();
        panel.recomputeEquipBonuses(EquipAffixCatalog.loadIni(tempDir.resolve("missing.ini")), null);
        panel.applyEquipEffects(null, null, null);
        assertEquals(0, panel.equipBonus().staAdd());
        assertTrue(panel.equipImmunities().isEmpty());
        assertFalse(panel.equipImmunities().contains(1));
    }

    private static void write(Path share, String relative, String content) throws IOException {
        Path file = share.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
    }
}
