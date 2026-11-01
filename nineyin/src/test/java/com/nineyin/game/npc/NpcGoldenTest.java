package com.nineyin.game.npc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.game.ServerPaths;
import com.nineyin.protocol.NpcMessages;
import com.nineyin.world.npc.NpcSpawn;
import com.nineyin.protocol.NpcSchema;
import com.nineyin.protocol.SnapshotGoldens;
import com.nineyin.role.Roles;
import com.nineyin.world.npc.SceneNpcRegistry;

/**
 * Chengdu 目录 + NPC 帧的官方方言基准：解析栈（TSV/XML/Resolver）统计快照
 * 兜底分歧；0x20/0x0E/0x1F 仍对齐 Go golden（无属性索引）。
 */
class NpcGoldenTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    private static SceneNpcRegistry registry;
    private static SceneNpcRegistry.CatalogResult chengdu;

    @BeforeAll
    static void loadChengduCatalog() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/npc/npcconfig")),
                "Go 仓库 share 资源不可用，跳过 NPC golden");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
        Path share = GO_SERVER_ROOT.resolve("resources/modern/share");
        registry = SceneNpcRegistry.open(share.resolve("npc/npcconfig"),
                share.resolve("creator/npc_creator"), NpcSchema.visibleNpcModernV1());
        chengdu = registry.catalogFor(new Roles.Scene("ini\\scene\\city05_ChengDu", "city05"));
    }

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = NpcGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void catalogStatsMatchOfficialSnapshot() {
        SceneNpcRegistry.CatalogStats stats = chengdu.stats();
        // 官方方言基线：Go 严格逐字段中止 -> 官方线型逐字段容错，resolved 5322->5355。
        assertTrue(stats.resolved() > 5000, "resolved=" + stats.resolved());
        assertEquals(stats.creatorInstances(), stats.resolved() + stats.unresolved(),
                "resolved+unresolved=creator 实例数");
        SnapshotGoldens.assertOrRegenText("npc_catalog_stats_official.json",
                "{\"creator_instances\":%d,\"resolved\":%d,\"unresolved\":%d,\"ambiguous\":%d,\"objects\":%d}"
                        .formatted(stats.creatorInstances(), stats.resolved(), stats.unresolved(),
                                stats.ambiguous(), chengdu.spawns().size()));
    }

    @Test
    void npcAddObjectMatchesOfficialSnapshot() {
        NpcSpawn spawn = chengdu.spawns().get(0).cloneSpawn();
        spawn.x = 340.16f;
        spawn.y = 47.979f;
        spawn.z = 657.284f;
        spawn.orient = 2.588f;
        spawn.synchronizeTransform();
        SnapshotGoldens.assertOrRegen("npc_add_object_official.bin",
                NpcMessages.npcAddObject(2, spawn));
    }

    @Test
    void sceneMotionFramesMatchGolden() throws Exception {
        assertArrayEquals(golden("server_moving.bin"),
                NpcMessages.serverMoving(2, 1,
                        NpcMessages.motionFromDest(341.66f, 47.979f, 658.784f, 2.588f)));
        assertArrayEquals(golden("scene_remove_object.bin"),
                NpcMessages.sceneRemoveObject(2, 1));
        assertArrayEquals(golden("server_location_npc.bin"),
                NpcMessages.serverLocation(2, 1, 341.66f, 47.979f, 658.784f, 2.588f));
    }

    @Test
    void defaultSpawnSceneResolvesChengduFolder() throws Exception {
        assertEquals("city05_chengdu",
                registry.resolveFolder(new Roles.Scene("ini\\scene\\city05_ChengDu", "city05")));
    }
}
