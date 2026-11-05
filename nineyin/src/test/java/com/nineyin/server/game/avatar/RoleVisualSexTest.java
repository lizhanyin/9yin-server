package com.nineyin.server.game.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.server.game.avatar.equipment.EquipmentResolver;

/**
 * 性别推断（RoleVisual.inferSex/resolve）：DB 无独立性别列，男女只体现在
 * role_appearances.appearance 串前缀（photo=girl|boy、路径段 g_|b_、
 * _g0xxx|_b0xxx）——线上实证 role 665=girl01/g_hair9/cloth_g0003。
 */
class RoleVisualSexTest {

    @TempDir
    Path tempRoot;

    private Path previousRoot;

    @BeforeEach
    void setUp() throws Exception {
        Path itemDir = tempRoot.resolve(Path.of("resources", "modern", "share", "item"));
        Files.createDirectories(itemDir);
        // 桩表：同 ArtPack 男女各一模型，验证 resolve 按推断性别取列。
        Files.writeString(itemDir.resolve("equipment.ini"), ""
                + "[cloth_g0003]\r\nArtPack=7\r\n"
                + "[cloth_b0002]\r\nArtPack=7\r\n\r\n");
        Files.writeString(itemDir.resolve("itemartstatic.ini"), ""
                + "[7]\r\nMaleModel=obj\\m\\cloth\r\nFemaleModel=obj\\f\\cloth\r\n\r\n");
        previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
    }

    @AfterEach
    void tearDown() {
        if (previousRoot != null) {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    private static List<String> appearance(String... values) {
        return Arrays.asList(values);
    }

    /** 线上 role 665 实值：photo=girl01 → sex=1，衣装按女性模型解析。 */
    @Test
    void femaleRole665InfersFemale() {
        List<String> appearance = appearance("book2", "icon\\players\\girl01_create.png",
                "33333", "1", "obj\\char\\g_hair\\g_hair9",
                "cloth_g0003", "pants_g0003", "shoes_g0003", "school_junzitang");
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        assertEquals(1, visual.sex(), "girl01 photo 应判女");
        assertEquals("obj\\f\\cloth", visual.cloth(), "衣装应取 FemaleModel");
    }

    /** 线上 role 618 实值：全路径段 b_* → sex=0，模型原样（含反斜杠不查表）。 */
    @Test
    void maleRole618InfersMale() {
        List<String> appearance = appearance("book2", "icon\\players\\boy01_create.png",
                "33333", "1", "obj\\char\\b_shizhuang033\\b_Szhelmet033",
                "obj\\char\\b_festival_1_106\\b_cloth_1_106",
                "obj\\char\\b_festival_1_106\\b_pants_1_106",
                "obj\\char\\b_festival_1_106\\b_shoes_1_106");
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        assertEquals(0, visual.sex());
        assertEquals("obj\\char\\b_festival_1_106\\b_cloth_1_106", visual.cloth());
    }

    /** photo 缺失时逐级回退：hair 路径段 g_ → 女。 */
    @Test
    void hairSegmentIsFallbackEvidence() {
        List<String> appearance = appearance("book1", "", "", "",
                "obj\\char\\g_hair\\g_hair2", "", "", "");
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        assertEquals(1, visual.sex(), "g_hair 路径段应判女");
        assertEquals("obj\\char\\g_hair\\g_hair2", visual.hair());
    }

    /** 女性角色空部位回退女默认（photo=girl + hair/衣裤鞋全空）。 */
    @Test
    void femaleDefaultsWhenPartsEmpty() {
        List<String> appearance = appearance("book1", "icon\\players\\girl01_create.png",
                "", "", "", "", "", "");
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        assertEquals(1, visual.sex());
        assertEquals("icon\\players\\girl01_create.png", visual.photo(), "photo 用存值");
        assertEquals("obj\\char\\g_hair\\g_hair1", visual.hair(), "空发型回退女默认");
        assertEquals("cloth_g0001", visual.cloth(), "空衣装回退女初始装");
        assertEquals("pants_g0001", visual.pants());
        assertEquals("shoes_g0001", visual.shoes());
    }

    /** photo/hair 全缺、只剩裸衣装配置名：cloth_g 前缀 → 女。 */
    @Test
    void bareClothConfigIsLastEvidence() {
        List<String> appearance = appearance("book1", "", "", "", "", "cloth_g0003", "", "");
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        assertEquals(1, visual.sex(), "cloth_g0003 应判女");
    }

    /** 空外观（线上 role 674 等）：无证据 → 男（与历史行为一致）。 */
    @Test
    void emptyAppearanceStaysMale() {
        RoleVisual visual = RoleVisual.resolve(List.of(), EquipmentResolver.loadDefault());
        assertEquals(0, visual.sex());
        assertEquals("icon\\players\\boy01_create.png", visual.photo());
        assertEquals("cloth_b0001", visual.cloth());
    }
}
