package com.nineyin.server.game.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 秘籍目录解析（GBK 双表：tool_item script=SkillBook/SkillPage/SkillScroll →
 * LearnSkillPack → item_learn_skill_static TragetID 多目标 ','/';' 混合分隔；
 * 分层字段 SkillMaxLevel/AddLevel/AddMaxLevel/UseCondition）+ 使用条件表
 * （rule/condition.ini：Type3 修炼上限区间 / Type1 门派编码）。
 */
class SkillBookCatalogTest {

    @TempDir
    Path temp;

    private Path writeIni(String name, String content) throws IOException {
        Path file = temp.resolve(name);
        Files.write(file, content.getBytes(Charset.forName("GBK")));
        return file;
    }

    @Test
    void resolvesTargetsAndTierFields() throws IOException {
        writeIni("tool_item.ini", ""
                + "[book_a]\r\n"
                + "script=SkillBook\r\n"
                + "LearnSkillPack=3837\r\n"
                + "SkillMaxLevel=9\r\n"
                + "Name=瞬劈直砍入门篇\r\n"
                + "[book_b]\r\n"
                + "script=SkillScroll\r\n"
                + "LearnSkillPack=7283\r\n"
                + "AddLevel=1\r\n"
                + "AddMaxLevel=36\r\n"
                + "[page_c]\r\n"
                + "script=SkillPage\r\n"
                + "LearnSkillPack=6365\r\n"
                + "[page_d2]\r\n"
                + "script=SkillPage\r\n"
                + "LearnSkillPack=3837\r\n"
                + "SkillMaxLevel=2\r\n"
                + "UseCondition=12193\r\n"
                + "[plain_d]\r\n"
                + "script=BoxItem\r\n"
                + "DropID=whatever\r\n"
                + "[nopack_e]\r\n"
                + "script=SkillBook\r\n");
        writeIni("item_learn_skill_static.ini", ""
                + "[-1]\r\n"
                + "TargetLevel=1\r\n"
                + "[3837]\r\n"
                + "TragetID=CS_base_pgd01\r\n"
                + "[7283]\r\n"
                + "TragetID=CS_dh_txxhjp08\r\n"
                + "[6365]\r\n"
                + "TragetID=qinggong_20,qinggong_21,qinggong_22;QG_JH_gj_001\r\n");
        SkillBookCatalog catalog = SkillBookCatalog.load(temp);

        assertEquals(4, catalog.bookCount());
        SkillBookCatalog.BookEntry a = catalog.resolve("book_a");
        assertEquals(java.util.List.of("CS_base_pgd01"), a.targets());
        assertEquals(9, a.skillMaxLevel());
        assertEquals(0, a.addLevel());
        assertEquals(0, a.useCondition());
        SkillBookCatalog.BookEntry b = catalog.resolve("BOOK_B");
        assertEquals(java.util.List.of("CS_dh_txxhjp08"), b.targets());
        assertEquals(0, b.skillMaxLevel());
        assertEquals(1, b.addLevel());
        assertEquals(36, b.addMaxLevel());
        // 多目标：',' 与 ';' 混合分隔，去空保序；无分层字段=纯学会型
        SkillBookCatalog.BookEntry c = catalog.resolve("page_c");
        assertEquals(java.util.List.of(
                "qinggong_20", "qinggong_21", "qinggong_22", "QG_JH_gj_001"),
                c.targets());
        assertEquals(0, c.skillMaxLevel());
        SkillBookCatalog.BookEntry d2 = catalog.resolve("page_d2");
        assertEquals(2, d2.skillMaxLevel());
        assertEquals(12193, d2.useCondition());
        assertNull(catalog.resolve("plain_d"), "非学习类 script 不收");
        assertNull(catalog.resolve("nopack_e"), "无 LearnSkillPack 不收");
        assertNull(catalog.resolve("missing"));
    }

    @Test
    void missingTablesYieldEmptyCatalog() throws IOException {
        SkillBookCatalog catalog = SkillBookCatalog.load(temp);
        assertEquals(0, catalog.bookCount());
        assertNull(catalog.resolve("book_a"));
    }

    @Test
    void learnScriptDispatch() {
        assertTrue(SkillBookCatalog.isLearnScript("SkillBook"));
        assertTrue(SkillBookCatalog.isLearnScript("skillpage"));
        assertTrue(SkillBookCatalog.isLearnScript("SkillScroll"));
        assertFalse(SkillBookCatalog.isLearnScript("BoxItem"));
        assertFalse(SkillBookCatalog.isLearnScript(""));
        assertFalse(SkillBookCatalog.isLearnScript(null));
        assertNotNull(SkillBookCatalog.loadDefault());
    }

    @Test
    void gateTableParsesContainerAndSchool() throws IOException {
        Files.createDirectories(temp.resolve(Path.of("rule")));
        writeIni(Path.of("rule", "condition.ini").toString(), ""
                + "[-1]\r\n"
                + "Type=0\r\n"
                + "[12193]\r\n"
                + "Type=3\r\n"
                + "Name=SkillContainer\r\n"
                + "Para1=CS_em_jdmz01\r\n"
                + "Para2=MaxLevel\r\n"
                + "min=1\r\n"
                + "max=1\r\n"
                + "[13579]\r\n"
                + "Type=3\r\n"
                + "Name=NeiGongContainer\r\n"
                + "Para1=ng_em_001\r\n"
                + "Para2=MaxLevel\r\n"
                + "min=18\r\n"
                + "max=29\r\n"
                + "[933]\r\n"
                + "Type=1\r\n"
                + "Name=School\r\n"
                + "Para1==\r\n"
                + "min=school_emei\r\n");
        SkillGateTable table = SkillGateTable.load(temp);

        assertEquals(3, table.size());
        SkillGateTable.Gate page2 = table.gate(12193);
        assertTrue(page2.containerCapInRange(1));
        assertFalse(page2.containerCapInRange(0));
        assertFalse(page2.containerCapInRange(2));
        assertNull(page2.schoolCode());
        // 区间门：18~29 内放行、17/30 拒
        SkillGateTable.Gate tier = table.gate(13579);
        assertTrue(tier.containerCapInRange(18));
        assertTrue(tier.containerCapInRange(29));
        assertFalse(tier.containerCapInRange(17));
        assertFalse(tier.containerCapInRange(30));
        // 门派门：期望编码直读
        assertEquals("school_emei", table.gate(933).schoolCode());
        assertNull(table.gate(42), "缺席条件返回 null（调用方放行）");
    }

    @Test
    void gateTableMissingFileTolerated() throws IOException {
        SkillGateTable table = SkillGateTable.load(temp);
        assertEquals(0, table.size());
        assertNull(table.gate(12193));
    }
}
