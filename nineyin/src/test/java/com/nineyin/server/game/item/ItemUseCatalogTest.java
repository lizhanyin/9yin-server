package com.nineyin.server.game.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.item.ItemUseCatalog.UseEntry;
import com.nineyin.server.game.item.ItemUseCatalog.UseKind;

/**
 * INI 回退目录：giftbox.ini / tool_item.ini（BoxItem DropID、CompensateItem
 * 多 ref 去重全发）/ item_use_skill_static.ini 档位钳制 + 回血阶梯；缺文件容错。
 */
class ItemUseCatalogTest {

    @TempDir
    Path dir;

    private void write(String name, String content, Charset charset) throws IOException {
        Files.writeString(dir.resolve(name), content, charset);
    }

    private ItemUseCatalog loadFixture() throws IOException {
        write("giftbox.ini", """
                ; 项目自建礼包表
                [box_a]
                script=BoxItem
                DropID=pack_a

                [no_drop]
                Name=缺 DropID 应跳过
                """, java.nio.charset.StandardCharsets.UTF_8);
        write("tool_item.ini", """
                [Box_qycdu_009]
                Name=成都奇遇
                ItemType=2159
                script=CompensateItem
                @CompensateItemRec=18658,qy_chengdu_009
                @CompensateItemRec=18659,box_qycdu_009
                @CompensateItemRec=18659,box_qycdu_009

                [qy_chengdu_009]
                Name=包裹
                ItemType=2102
                script=BoxItem
                DropID=qy_chengdu_009

                [tool_x]
                script=ToolItem
                DropID=非箱类不应收录
                """, Charset.forName("GBK"));
        write("item_use_skill_static.ini", """
                [2101]
                SkillID=yaopinhp1
                SkillLevel=1

                [2102]
                SkillID=yaopinhp1
                SkillLevel=12

                [-1]
                FieldMap=非技能节
                """, Charset.forName("GBK"));
        return ItemUseCatalog.load(dir);
    }

    @Test
    void giftboxEntryWinsByConfigId() throws IOException {
        ItemUseCatalog catalog = loadFixture();
        UseEntry entry = catalog.resolve("box_a", 0);
        assertEquals(UseKind.BOX, entry.kind());
        assertEquals(java.util.List.of("pack_a"), entry.dropIds());
        assertEquals("pack_a", catalog.resolve("BOX_A", 0).dropIds().get(0));   // 大小写不敏感
    }

    @Test
    void compensateItemGrantsAllRefsDedupedInOrder() throws IOException {
        ItemUseCatalog catalog = loadFixture();
        UseEntry entry = catalog.resolve("Box_qycdu_009", 2159);
        assertEquals(UseKind.BOX, entry.kind());
        assertEquals(java.util.List.of("qy_chengdu_009", "box_qycdu_009"), entry.dropIds());
    }

    @Test
    void toolItemBoxResolvedByDropId() throws IOException {
        ItemUseCatalog catalog = loadFixture();
        assertEquals(java.util.List.of("qy_chengdu_009"),
                catalog.resolve("qy_chengdu_009", 0).dropIds());
        assertNull(catalog.resolve("tool_x", 0));   // 非 BoxItem/CompensateItem 不收录
    }

    @Test
    void useSkillLadderAndClamp() throws IOException {
        ItemUseCatalog catalog = loadFixture();
        UseEntry entry = catalog.resolve("unknown_item", 2101);
        assertEquals(UseKind.USE_SKILL, entry.kind());
        assertEquals("yaopinhp1", entry.skillId());
        assertEquals(100, ItemUseCatalog.fallbackHealHp(entry.skillLevel()));
        assertEquals(50, ItemUseCatalog.fallbackHealMp(entry.skillLevel()));
        assertEquals(9, catalog.resolve("unknown_item", 2102).skillLevel());   // 12 → 9
        assertEquals(3000, ItemUseCatalog.fallbackHealHp(9));
        assertEquals(100, ItemUseCatalog.fallbackHealHp(0));                    // 钳到 L1
        assertNull(catalog.resolve("unknown_item", 9999));
    }

    @Test
    void emptyRootYieldsEmptyCatalog() throws IOException {
        Path empty = dir.resolve("empty-" + System.nanoTime());
        Files.createDirectories(empty);
        ItemUseCatalog catalog = ItemUseCatalog.load(empty);
        assertEquals(0, catalog.boxCount());
        assertEquals(0, catalog.useSkillTypeCount());
        assertNull(catalog.resolve("box_a", 2101));
        assertTrue(catalog.boxCount() >= 0);
    }
}
