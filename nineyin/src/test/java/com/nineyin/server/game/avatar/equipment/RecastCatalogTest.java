package com.nineyin.server.game.avatar.equipment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 洗练池目录（senior_*.ini 四池）：行格式（weapon dire 带 PropName、prop dire 带
 * 内功系+PropName、rand 平铺且重复=权重）、精确节→家族前缀→整池回退、
 * 包号→档序列映射与 WEAPON/PROP 归类（招式开光只重掷 weapon 池词条）。
 */
class RecastCatalogTest {

    @Test
    void poolsParseFamilyFallbackAndClassification(@TempDir Path tmp) throws Exception {
        RecastCatalog catalog = writePools(tmp);

        // 家族前缀：去尾部 _数字 段；尾段非数字原样返回（无前缀可回退）。
        assertEquals("cloth_liangu_dragon_kf", RecastCatalog.familyPrefix("cloth_liangu_dragon_kf_60701"));
        assertEquals("blade", RecastCatalog.familyPrefix("blade_50302"));
        assertEquals("blade_50302_mj", RecastCatalog.familyPrefix("blade_50302_mj"));

        // 归类：weapon 池 / prop 池 / 未知（老全池随机词条）。
        assertEquals(RecastCatalog.PoolKind.WEAPON, catalog.classify(11));
        assertEquals(RecastCatalog.PoolKind.PROP, catalog.classify(21));
        assertEquals(RecastCatalog.PoolKind.UNKNOWN, catalog.classify(999));

        // 定向/抛光档序列：包号所在 dire 行（升序，末位=最高档）。
        assertArrayEquals(new int[] {11, 12, 13}, catalog.direRowOf(11));
        assertArrayEquals(new int[] {21, 22, 23}, catalog.direRowOf(21));
        assertNull(catalog.direRowOf(999));

        // 精确节：保留重复（官方权重）。
        assertEquals(java.util.List.of(11, 12, 13, 11), catalog.randPool("blade_50302", true));
        assertEquals(java.util.List.of(21, 22, 24, 25), catalog.randPool("amulet_50302", false));

        // 家族前缀回退：blade_50999 无精确节 → 合并 blade_* 节。
        assertEquals(java.util.List.of(11, 12, 13, 11, 14, 15), catalog.randPool("blade_50999", true));

        // 仍没有 → 整池兜底（防具走 prop 整池）。
        assertEquals(java.util.List.of(21, 22, 24, 25),
                catalog.randPool("cloth_99999", false));
        // 招式开光池恒走 weapon 文件（前缀回退）。
        assertEquals(java.util.List.of(11, 12, 13, 11, 14, 15), catalog.weaponPool("blade_50999"));
    }

    @Test
    void emptyPoolsReportEmpty(@TempDir Path tmp) throws Exception {
        RecastCatalog catalog = RecastCatalog.load(
                tmp.resolve("wd.ini"), tmp.resolve("wr.ini"),
                tmp.resolve("pd.ini"), tmp.resolve("pr.ini"));
        assertTrue(catalog.isEmpty(), "四个空文件应视为空池");
        assertEquals(java.util.List.of(), catalog.randPool("blade_50302", true));
        assertNull(catalog.direRowOf(11));
    }

    /** 临时四池（行格式对齐真机抽样：weapon/prop 各 dire+rand）。 */
    private static RecastCatalog writePools(Path tmp) throws Exception {
        Files.write(tmp.resolve("wd.ini"), ("""
                [blade_50302]
                r=MeleeDamage,11,12,13
                r=AIPhyDef,31,32

                [blade_50401]
                r=MeleeDamage,14,15
                """).getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("wr.ini"), ("""
                [blade_50302]
                r=11,12,13,11

                [blade_50401]
                r=14,15
                """).getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("pd.ini"), ("""
                [amulet_50302]
                r=tj,SpiAdd,21,22,23
                r=yg,StrAdd,24,25
                """).getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("pr.ini"), ("""
                [amulet_50302]
                r=tj,21,22
                r=yg,24,25
                """).getBytes(StandardCharsets.UTF_8));
        return RecastCatalog.load(
                tmp.resolve("wd.ini"), tmp.resolve("wr.ini"),
                tmp.resolve("pd.ini"), tmp.resolve("pr.ini"));
    }
}
