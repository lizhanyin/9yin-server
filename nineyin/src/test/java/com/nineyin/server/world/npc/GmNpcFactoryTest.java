package com.nineyin.server.world.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * GM 摆放工厂（真实 share 表）：模板克隆 + 属性覆盖走场景目录同一条
 * resolve 链——木桩（AttackNpc，血量/移速覆盖）与宝箱（CommonNpc，改名）
 * 的 schema 属性按官方表线型解析。参考服 share 不可用时跳过（同
 * LoginFlowGmCommandTest.mountSkin 的 assumeTrue 方言）。
 */
class GmNpcFactoryTest {

    private static final Path GO_ROOT = Path.of("E:/Workspace/project/game/9yin-go-server1");

    private static Path configRoot() {
        Path root = GO_ROOT.resolve(Path.of("resources", "modern", "share", "npc", "npcconfig"));
        return Files.isDirectory(root.resolve(Path.of("worldnpc"))) ? root : null;
    }

    @Test
    void dummyTemplateClonesWithHpAndSpeedOverrides() throws Exception {
        Path root = configRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null,
                "参考服 npcconfig 不可用，跳过 GM 摆放工厂断言");
        NpcSpawn dummy = GmNpcFactory.spawnFromTemplate(root, "worldnpc/attacknpc.txt",
                "monv010100", 11.5f, 2.0f, 30.25f, 1.5f,
                Map.of("MaxHP", "567890", "HP", "567890", "WalkSpeed", "0", "RunSpeed", "0"));
        assertEquals("monv010100", dummy.resolved.configId);
        assertEquals("AttackNpc", dummy.resolved.scriptClass);
        assertEquals(567890, dummy.resolved.properties.get("MaxHP").i32());
        assertEquals(567890, dummy.resolved.properties.get("HP").i32());
        assertEquals(0f, dummy.resolved.properties.get("WalkSpeed").f32(), 0f,
                "木桩移速覆盖为 0");
        assertEquals(0f, dummy.resolved.properties.get("RunSpeed").f32(), 0f);
        assertEquals(11.5f, dummy.x, 0f);
        assertEquals(30.25f, dummy.z, 0f);
        // 战斗快照口径：CombatNpcInfo 按 MaxHP 物化，模板名「木桩」非空。
        assertTrue(dummy.resolved.properties.containsKey("Resource"));
        assertTrue(dummy.resolved.properties.containsKey("Name"));
    }

    @Test
    void chestTemplateClonesWithRename() throws Exception {
        Path root = configRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null,
                "参考服 npcconfig 不可用，跳过 GM 摆放工厂断言");
        NpcSpawn chest = GmNpcFactory.spawnFromTemplate(root, "worldnpc/monsternpc_commonnpc.txt",
                "stormbox_10", 5f, 0f, 6f, 0f, Map.of("Name", "镀银宝箱"));
        assertEquals("stormbox_10", chest.resolved.configId);
        assertEquals("CommonNpc", chest.resolved.scriptClass,
                "宝箱为非战斗对象（点击走对话链而非战斗选中）");
        assertEquals("镀银宝箱", chest.resolved.properties.get("Name").text());
        assertNotNull(chest.resolved.properties.get("Resource"), "宝箱模型存在");
    }

    @Test
    void unknownTemplateFailsWithClearMessage() {
        Path root = configRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null,
                "参考服 npcconfig 不可用，跳过 GM 摆放工厂断言");
        try {
            GmNpcFactory.spawnFromTemplate(root, "worldnpc/attacknpc.txt",
                    "no_such_npc", 0, 0, 0, 0, Map.of());
            org.junit.jupiter.api.Assertions.fail("未知模板应抛 IOException");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("no_such_npc"));
        }
    }
}
