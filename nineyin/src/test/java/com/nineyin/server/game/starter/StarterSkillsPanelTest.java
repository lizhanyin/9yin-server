package com.nineyin.server.game.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.protocol.PropertyTable;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.protocol.WireType;
import com.nineyin.util.ServerPaths;

/**
 * 四期技能/内功属性面：skill_init 目录解析与覆盖、View40 行零值特效族、
 * View43 书行五维 Add。断言走 0x18 帧字节（u16 索引 + 官方表线型）。
 */
class StarterSkillsPanelTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    @BeforeAll
    static void pinServerRoot() {
        // CoolDownTime 显示源 = varprop（CombatSkillCatalog）；目录缺失时该断言跳过。
        Assumptions.assumeTrue(Files.isDirectory(
                GO_SERVER_ROOT.resolve(Path.of("resources", "modern", "share", "skill"))),
                "share/skill ini missing (needs Go repo resources)");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
    }

    @TempDir
    Path tempDir;

    @Test
    void skillInitTableParsesSectionsWithDefaults() throws IOException {
        Path ini = tempDir.resolve("skill_init.ini");
        Files.write(ini, ("[CS_test_01]\r\n"
                + "staticData=12636\r\n"
                + "itemType=1000\r\n"
                + "level=2\r\n"
                + "maxlevel=19\r\n"
                + "total=156550\r\n"
                + "coolDown=8000\r\n"
                + "pauseTime=50*1000.0/30\r\n"
                + "\r\n"
                + "[empty_section]\r\n").getBytes(StandardCharsets.ISO_8859_1));
        SkillInitTable table = SkillInitTable.loadIni(ini);
        assertEquals(1, table.size(), "空节不进表");
        SkillInitTable.Row row = table.row("CS_test_01");
        assertEquals(12636, row.staticData());
        assertEquals(1000, row.itemType());
        assertEquals(2, row.level());
        assertEquals(19, row.maxLevel());
        assertEquals(156550, row.totalFill());
        assertEquals(8000, row.coolDownMs());
        // 空节（无 staticData）与未知 id → null（回退内联）；缺文件 → 空表
        assertNull(table.row("empty_section"));
        assertNull(table.row("nope"));
        assertNull(SkillInitTable.loadIni(tempDir.resolve("missing.ini")).row("CS_test_01"));
    }

    @Test
    void starterRowsCarryBakSemanticEffectFamily() {
        StarterSkills.LearnedSkill skill = new StarterSkills.LearnedSkill(
                "CS_yhwq_hsqs01", 12636, 1000, 1, 11, 0, 156550, (float) (43 * 1000.0 / 30));
        // 无目录行：内联值 + bak 语义属性族 + 目录补集（VarPropNo/消耗族/形状参数；
        // 空目录 WuXing 缺省 5=太极）
        byte[] fallback = SceneMessages.viewAdd(StarterSkills.VIEWPORT_SKILL, 1,
                StarterSkills.skillRowProperties(skill, null));
        assertEquals(29, count(fallback), "24 原属性 + 补集 5（AConsumeMP+4 形状参数；"
                + "hsqs01 无 AConsumeHP/SP/QGP 不发）");
        assertEquals(12636, intValueOf(fallback, "StaticData"));
        assertEquals(11, intValueOf(fallback, "MaxLevel"));
        assertEquals(1, byteValueOf(fallback, "Level"), "等级走 Level(1465,BYTE)");
        assertEquals(9, intValueOf(fallback, "UseLimit"), "UseLimit 缺目录值回落 9（bak 语义）");
        assertEquals(252701, intValueOf(fallback, "VarPropNo"), "VarPropNo=L1 varprop 行号");
        assertEquals(21, intValueOf(fallback, "AConsumeMP"), "AConsumeMP=21（consume 15604）");
        assertEquals(20.0f, floatValueOf(fallback, "TargetShapePara2"), 0.0001f,
                "TargetShapePara2=20（targetshape 17）");
        assertEquals(1, byteValueOf(fallback, "Quality"), "Quality=1");
        // WuXing：wuxuebaseinfo 目录值（其他测试可能已把 serverRoot 覆盖到 go-server1 真
        // share——花神七式真实值 2=柔），空目录缺省 5=太极；此处只钉枚举域 1..7。
        int wuXing = intValueOf(fallback, "WuXing");
        assertTrue(wuXing >= 1 && wuXing <= 7, "WuXing 枚举域 1..7，实际=" + wuXing);
        assertEquals(1.0f, floatValueOf(fallback, "MasDamageMul"), 0.0001f, "倍率=1.0 非 0");
        assertEquals(1.0f, floatValueOf(fallback, "HPHealMul"), 0.0001f);
        assertEquals(5, intValueOf(fallback, "Flow"));
        assertEquals(10000, intValueOf(fallback, "LeadTime"));
        assertEquals(1500, intValueOf(fallback, "LeadSepTime"));
        // 目录行覆盖等级/上限/total/CD
        SkillInitTable.Row row = new SkillInitTable.Row("CS_yhwq_hsqs01", 3, 12699, 1001,
                21, 8000, 200000);
        byte[] enriched = SceneMessages.viewAdd(StarterSkills.VIEWPORT_SKILL, 1,
                StarterSkills.skillRowProperties(skill, row));
        assertEquals(12699, intValueOf(enriched, "StaticData"));
        assertEquals(3, byteValueOf(enriched, "Level"));
        assertEquals(21, intValueOf(enriched, "MaxLevel"));
        assertEquals(200000, intValueOf(enriched, "TotalFillValue"));
        assertEquals(8000, intValueOf(enriched, "CoolDownTime"));
        // 官方路径：skill_init cooldown 全 0（row=null 或 cooldown=0）→ varprop 按级 CD
        // （抓包 59719 View40：hsqs01=2000；下发 0 会让客户端技能 CD 文本兜底显示 1s）。
        assertEquals(2000, intValueOf(fallback, "CoolDownTime"),
                "无目录行时 CoolDownTime 走 varprop（hsqs01 L1=2000）");
        SkillInitTable.Row zeroCd = new SkillInitTable.Row("CS_yhwq_hsqs01", 1, 12636, 1000,
                11, 0, 156550);
        assertEquals(2000, intValueOf(SceneMessages.viewAdd(StarterSkills.VIEWPORT_SKILL, 1,
                StarterSkills.skillRowProperties(skill, zeroCd)), "CoolDownTime"),
                "skill_init cooldown=0 视为未设置，回落 varprop");
    }

    @Test
    void bookFrameCarriesSchoolLevelByteAndWuXing() {
        com.nineyin.server.game.avatar.neigong.PlayerProgress progress =
                com.nineyin.server.game.avatar.neigong.PlayerProgress.create(
                        java.time.Clock.systemUTC());
        byte[] frame = progress.bookFrame(1);
        assertEquals(0x18, frame[0] & 0xFF);
        assertEquals(43, frame[1] | frame[2] << 8);
        assertEquals(16, count(frame), "bak 16 属性布局");
        // ng_wd_001 内联种子：strAdd=45 / staAdd=31 / 太极 5 / 门派 ng_wd / 等级 30(BYTE)
        assertEquals(45, intValueOf(frame, "StrAdd"));
        assertEquals(31, intValueOf(frame, "StaAdd"));
        assertEquals(5, intValueOf(frame, "WuXing"), "太极=5（ui_wuxue_prop_5）");
        assertEquals(30, byteValueOf(frame, "Level"), "等级走 Level(1465,BYTE)");
        assertEquals("ng_wd", stringValueOf(frame, "School"));
        // 运功书属性伴生显示源（0x10）
        assertTrue(progress.properties().stream()
                .anyMatch(p -> p.name().equals("CurNeiGongWuXing")), "0x10 含 CurNeiGongWuXing");
    }

    @Test
    void catalogResolvesAttributeSchoolWuXing() throws IOException {
        Path share = tempDir.resolve("share");
        Files.createDirectories(share.resolve("skill").resolve("neigong"));
        Files.createDirectories(share.resolve("faculty"));
        write(share, "skill/neigong/neigong_static.ini", """
                [1002]
                MinVarPropNo=1
                MaxVarPropNo=10
                BelongType=ng_mp_tj

                [114]
                BelongType=ng_mp_yr
                """);
        write(share, "skill/neigong/neigong.ini", """
                [wd_section]
                QName=ng_wd_001
                School=ng_wd
                """);
        write(share, "faculty/wuxuebaseinfo.ini", """
                [ng_wd_001]
                nWuXing=5

                [CS_yhwq_hsqs01]
                nWuXing=1
                """);
        com.nineyin.server.game.avatar.neigong.NeiGongCatalog catalog =
                com.nineyin.server.game.avatar.neigong.NeiGongCatalog.loadShare(share);
        assertEquals("太极", catalog.attributeOf(1002, "ng_wd_001"));
        assertEquals("阴柔", catalog.attributeOf(114, "ng_yh_001"));
        assertEquals("阴柔", catalog.attributeOf(0, "ng_yh_999"), "移花前缀特判阴柔");
        assertEquals("", catalog.attributeOf(0, "ng_xx_001"), "未知归属空串");
        assertEquals("ng_wd", catalog.schoolOf("ng_wd_001"), "QName→School");
        assertEquals("", catalog.schoolOf("ng_unknown"));
        assertEquals(5, catalog.wuXingOf("ng_wd_001"));
        assertEquals(1, catalog.wuXingOf("CS_yhwq_hsqs01"), "技能刚=1");
        assertEquals(0, catalog.wuXingOf("nope"));
    }

    private static void write(Path share, String relative, String content) throws IOException {
        Path file = share.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ------------------------------------------------------------- 帧解析辅助

    private static int count(byte[] frame) {
        return frame[5] | frame[6] << 8;
    }

    /** 0x18 帧内按表索引找 INT32/FLOAT32 属性原始位（视图行=玩家域索引；字符串按 u32 长度前缀跳长）。 */
    private static int bitsOf(byte[] frame, String name) {
        WireType type = PropertyTable.playerWireType(name);
        if (type != WireType.INT32 && type != WireType.FLOAT32) {
            throw new IllegalArgumentException("not int32/float32: " + name);
        }
        int wanted = PropertyTable.playerIdx(name);
        int offset = 7;
        for (int i = 0; i < count(frame); i++) {
            int index = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
            offset += 2;
            int size = sizeOf(frame, offset, index);
            if (index == wanted) {
                return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                        | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
            }
            offset += size;
        }
        throw new IllegalStateException("property absent in frame: " + name);
    }

    private static int intValueOf(byte[] frame, String name) {
        return bitsOf(frame, name);
    }

    private static float floatValueOf(byte[] frame, String name) {
        return Float.intBitsToFloat(bitsOf(frame, name));
    }

    /** 0x18 帧内按玩家域索引找 BYTE 属性值。 */
    private static int byteValueOf(byte[] frame, String name) {
        if (PropertyTable.playerWireType(name) != WireType.BYTE) {
            throw new IllegalArgumentException("not byte: " + name);
        }
        int wanted = PropertyTable.playerIdx(name);
        int offset = 7;
        for (int i = 0; i < count(frame); i++) {
            int index = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
            offset += 2;
            if (index == wanted) {
                return frame[offset] & 0xFF;
            }
            offset += sizeOf(frame, offset, index);
        }
        throw new IllegalStateException("property absent in frame: " + name);
    }

    /** 0x18 帧内按玩家域索引找 STRING 属性值（u32 长度前缀 + UTF-8 字节 + NUL）。 */
    private static String stringValueOf(byte[] frame, String name) {
        int wanted = PropertyTable.playerIdx(name);
        int offset = 7;
        for (int i = 0; i < count(frame); i++) {
            int index = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
            offset += 2;
            int size = sizeOf(frame, offset, index);
            if (index == wanted) {
                int length = u32(frame, offset);
                byte[] data = new byte[Math.max(0, length - 1)];
                System.arraycopy(frame, offset + 4, data, 0, data.length);
                return new String(data, StandardCharsets.UTF_8);
            }
            offset += size;
        }
        throw new IllegalStateException("property absent in frame: " + name);
    }

    /** 条目负载长度：按表行自身线型码（索引=位置，同名多块线型可不同）。 */
    private static int sizeOf(byte[] frame, int offset, int index) {
        PropertyTable.Entry entry = PropertyTable.byIndex(index);
        if (entry == null || PropertyTable.wireTypeOf(entry.typeCode()) == null) {
            return 4;
        }
        return switch (WireType.of(entry.typeCode())) {
            case BYTE -> 1;
            case WORD -> 2;
            case INT32, FLOAT32 -> 4;
            case INT64, OBJECT -> 8;
            case STRING, WIDE_STRING -> 4 + u32(frame, offset);
        };
    }

    private static int u32(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
    }
}
