package com.nineyin.server.game.avatar.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 身份装/时装目录测试：fashion_suit.ini 段解析、faction→段名映射
 * （force_/newschool_ 同名直达，旧门派补 school_ 前缀）、时装四段合集。
 * 真实目录可用时（参考服 modern 树）加验 [school_shaolin] 实际清单。
 */
class FashionSuitCatalogTest {

    @TempDir
    Path tempDir;

    private FashionSuitCatalog stubCatalog() {
        try {
            Files.writeString(tempDir.resolve("fashion_suit.ini"), ""
                    + "[base_info]\r\n"
                    + "shengfen=school,force,newschool,shijia,guild,life,activity\r\n"
                    + "fashion=hat,coat,shoes,suit\r\n"
                    + "school=school_shaolin,school_wudang\r\n"
                    + "force=force_yihua\r\n"
                    + "\r\n"
                    + "[school_shaolin]\r\nr=shaolin01\r\nr=shaolin02\r\nr=newsl01\r\n\r\n"
                    + "[school_wudang]\r\nr=wudang01\r\n\r\n"
                    + "[force_yihua]\r\nr=yihua01\r\nr=yihua02\r\n\r\n"
                    + "[shijia]\r\nr=cloth_origin_01\r\n\r\n"
                    + "[hat]\r\nr=HELMET_SZ_001\r\n\r\n"
                    + "[coat]\r\nr=CLOTH_SZ_001\r\nr=CLOTH_SZ_002\r\n\r\n"
                    + "[shoes]\r\nr=SHOES_SZ_001\r\n\r\n"
                    + "[suit]\r\nr=FASHION_SUIT_B001\r\n\r\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return FashionSuitCatalog.load(tempDir);
    }

    @Test
    void factionMapsDirectlyForForceAndNewschool() {
        FashionSuitCatalog catalog = stubCatalog();
        assertEquals(List.of("yihua01", "yihua02"), catalog.identitySuitsOf("force_yihua"));
    }

    @Test
    void oldSchoolFactionGetsSchoolPrefix() {
        FashionSuitCatalog catalog = stubCatalog();
        assertEquals(List.of("shaolin01", "shaolin02", "newsl01"), catalog.identitySuitsOf("shaolin"));
    }

    @Test
    void unknownFactionAndBlankReturnEmpty() {
        FashionSuitCatalog catalog = stubCatalog();
        assertTrue(catalog.identitySuitsOf("mingjiao").isEmpty());
        assertTrue(catalog.identitySuitsOf("").isEmpty());
        assertTrue(catalog.identitySuitsOf(null).isEmpty());
    }

    @Test
    void fashionSuitsMergeFourSectionsInOrder() {
        FashionSuitCatalog catalog = stubCatalog();
        assertEquals(List.of("HELMET_SZ_001", "CLOTH_SZ_001", "CLOTH_SZ_002",
                "SHOES_SZ_001", "FASHION_SUIT_B001"), catalog.fashionSuits());
        assertTrue(catalog.isFashionSuit("CLOTH_SZ_001"));
        assertFalse(catalog.isFashionSuit("shaolin01"), "身份装不属于时装目录");
        assertFalse(catalog.isFashionSuit(null));
    }

    @Test
    void allIdentitySuitsCoverSchoolForceAndPlainSections() {
        FashionSuitCatalog catalog = stubCatalog();
        List<String> all = catalog.allIdentitySuits();
        assertTrue(all.contains("shaolin01"));
        assertTrue(all.contains("yihua01"));
        assertTrue(all.contains("cloth_origin_01"), "shijia 通用段也是身份装组");
        assertFalse(all.contains("CLOTH_SZ_001"), "时装四段不算身份装");
    }

    @Test
    void realCatalogParsesSchoolSections() {
        Path goRoot = Path.of("G:/pojie/ai/9yin-go-server1");
        Path file = goRoot.resolve(Path.of("resources", "modern", "ini", "form", "fashion_suit.ini"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(file),
                "参考服 modern 树不可用，跳过真实目录断言");
        FashionSuitCatalog catalog = FashionSuitCatalog.load(file.getParent());
        assertFalse(catalog.isEmpty());
        List<String> shaolin = catalog.identitySuitsOf("shaolin");
        assertEquals(14, shaolin.size(), "[school_shaolin] 14 个 configid（shaolin01-10+s_shaolin_1_b+newsl01-03）");
        assertTrue(shaolin.contains("shaolin01"));
        assertTrue(shaolin.contains("newsl01"));
        assertFalse(catalog.identitySuitsOf("force_yihua").isEmpty(), "势力段同名直达");
        assertFalse(catalog.fashionSuits().isEmpty(), "时装四段非空");
    }
}
