package com.nineyin.game;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/** 真实数据探针：对着部署 share 根抽查 varprop 解析与 Go 种子值一致（NEIGONG_PROBE_ROOT 未设时跳过）。 */
class NeigongEffectCatalogProbeTest {

    @Test
    void probeRealShareRoot() {
        String root = System.getenv("NEIGONG_PROBE_ROOT");
        Assumptions.assumeTrue(root != null && !root.isEmpty(),
                "NEIGONG_PROBE_ROOT not set; probe skipped");
        NeigongEffectCatalog catalog = NeigongEffectCatalog.load(Path.of(root));
        // ng_wd_001（StaticData 5）30 级：Go 内联种子值
        NeigongEffectCatalog.Effect wd30 = catalog.effect(5, 30);
        System.out.println("wd30=" + (wd30 == null ? "null" : wd30));
        org.junit.jupiter.api.Assertions.assertNotNull(wd30, "ng_wd_001 level 30 must resolve");
        org.junit.jupiter.api.Assertions.assertEquals("buf_ng_wd_001", wd30.buffId());
        org.junit.jupiter.api.Assertions.assertEquals(5954, wd30.buffStaticData());
        org.junit.jupiter.api.Assertions.assertEquals(3, wd30.buffLevel());
        org.junit.jupiter.api.Assertions.assertEquals(45, wd30.stat("StrAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(31, wd30.stat("StaAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(13, wd30.stat("DexAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(46, wd30.stat("IngAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(13, wd30.stat("SpiAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(1630, wd30.stat("MaxHPAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(490, wd30.stat("MaxMPAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(840, wd30.stat("MaxParryAdd"));
        org.junit.jupiter.api.Assertions.assertEquals(45, wd30.stat("MinMagicDefAdd"));
        // ng_yh_001（StaticData 114）30 级抽查
        NeigongEffectCatalog.Effect yh30 = catalog.effect(114, 30);
        System.out.println("yh30=" + (yh30 == null ? "null" : yh30));
        org.junit.jupiter.api.Assertions.assertNotNull(yh30, "ng_yh_001 level 30 must resolve");
        org.junit.jupiter.api.Assertions.assertEquals(13073, yh30.buffStaticData());
        org.junit.jupiter.api.Assertions.assertEquals(4050, yh30.stat("MaxHPAdd"));
        // 内功属性三件套：BelongType→太极/阴柔/阳刚、nWuXing、neigong.ini School
        System.out.println("attr(wd)=" + catalog.attributeOf(5, "ng_wd_001")
                + " wuxing=" + catalog.wuXingOf("ng_wd_001")
                + " school=" + catalog.schoolOf("ng_wd_001"));
        org.junit.jupiter.api.Assertions.assertEquals("太极", catalog.attributeOf(5, "ng_wd_001"));
        org.junit.jupiter.api.Assertions.assertEquals(5, catalog.wuXingOf("ng_wd_001"));
        org.junit.jupiter.api.Assertions.assertEquals("ng_wd", catalog.schoolOf("ng_wd_001"));
        org.junit.jupiter.api.Assertions.assertEquals("阴柔", catalog.attributeOf(114, "ng_yh_001"),
                "ng_yh_ 前缀/BelongType 任一路径应得阴柔");
        // WuXing 枚举对照（客户端 ui_wuxue_prop_{N}）：门派组 100% 吻合抽查
        org.junit.jupiter.api.Assertions.assertEquals(5, catalog.wuXingOf("ng_wd_001"), "两仪护心功=太极(5)");
        org.junit.jupiter.api.Assertions.assertEquals(6, catalog.wuXingOf("ng_yh_001"), "幻玉神功=阴柔(6)");
        org.junit.jupiter.api.Assertions.assertEquals(7, catalog.wuXingOf("ng_xj_001"), "三清真气=阳刚(7)");
        org.junit.jupiter.api.Assertions.assertEquals(5, NeigongEffectCatalog.WUXING_TAIJI);
        org.junit.jupiter.api.Assertions.assertEquals(6, NeigongEffectCatalog.WUXING_YINROU);
        org.junit.jupiter.api.Assertions.assertEquals(7, NeigongEffectCatalog.WUXING_YANGGANG);
    }
}
