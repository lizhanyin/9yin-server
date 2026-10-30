package com.nineyin.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 学习表解析（§3.16：total 列随 LearnedSkill 保留，供 choose 目录整推行使用）。 */
class SkillInitTableTest {

    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    @Test
    void parsesTotalColumnAndKeepsFileOrder() throws Exception {
        Path ini = tempDir.resolve("skill_init.ini");
        Files.writeString(ini, """
                [Clone007_bingkuai]
                staticData=1643
                itemType=1000
                level=1
                maxlevel=1
                total=156550
                coolDown=0

                [CS_yhwq_hsqs01]
                staticData=12636
                itemType=1000
                level=1
                maxlevel=1
                total=156550
                coolDown=1500
                """, GBK);
        SkillInitTable table = new SkillInitTable();
        table.load(ini);
        assertEquals(2, table.size());
        assertFalse(table.usingBuiltInFallback());
        List<SkillInitTable.LearnedSkill> all = new ArrayList<>(table.all());
        assertEquals(List.of("Clone007_bingkuai", "CS_yhwq_hsqs01"),
                all.stream().map(SkillInitTable.LearnedSkill::configId).toList(),
                "all() 保持文件节序（目录整推行序来源）");
        assertEquals(156550, all.get(0).totalFill());
        assertEquals(1500, all.get(1).coolDownMs());
        assertEquals(1643, all.get(0).staticData());
    }

    @Test
    void missingFileFallsBackToBuiltInStarterSet() throws Exception {
        SkillInitTable table = new SkillInitTable();
        table.load(tempDir.resolve("absent.ini"));
        assertTrue(table.usingBuiltInFallback());
        assertEquals(com.nineyin.protocol.SkillPackets.STARTER_SKILLS.size(), table.size());
        // 回退集无 total 显示值（ready 才下发，非目录行）
        assertEquals(0, table.all().iterator().next().totalFill());
    }
}
