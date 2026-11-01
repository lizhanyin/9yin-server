package com.nineyin.game.npc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.game.ServerPaths;
import com.nineyin.protocol.NpcMessages;
import com.nineyin.protocol.NpcSchema;
import com.nineyin.role.Roles;

/**
 * 与 Go golden_dump_test.go NPC 段相同的固定输入：Chengdu 目录 spawns[0] 固定
 * 变换 → 0x0D/0x20/0x10/0x0E/0x1F 帧逐字节一致；目录解析统计与
 * npc_catalog_stats.json 对齐（模板表/creator 解析器分歧的兜底网）。
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

    private static int goldenStat(String key) throws Exception {
        try (InputStream in = NpcGoldenTest.class.getResourceAsStream("/golden/npc_catalog_stats.json")) {
            Objects.requireNonNull(in);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"" + key + "\":\\s*(\\d+)").matcher(json);
            assertTrue(matcher.find(), "stat " + key);
            return Integer.parseInt(matcher.group(1));
        }
    }

    @Test
    void catalogStatsMatchGolden() throws Exception {
        assertEquals(goldenStat("creator_instances"), chengdu.stats().creatorInstances(), "creator_instances");
        assertEquals(goldenStat("resolved"), chengdu.stats().resolved(), "resolved");
        assertEquals(goldenStat("unresolved"), chengdu.stats().unresolved(), "unresolved");
        assertEquals(goldenStat("ambiguous"), chengdu.stats().ambiguous(), "ambiguous");
        assertEquals(goldenStat("objects"), chengdu.spawns().size(), "objects");
    }

    @Test
    void npcAddObjectMatchesGolden() {
        NpcSpawn spawn = chengdu.spawns().get(0).cloneSpawn();
        spawn.x = 340.16f;
        spawn.y = 47.979f;
        spawn.z = 657.284f;
        spawn.orient = 2.588f;
        spawn.synchronizeTransform();
        byte[] frame = NpcMessages.npcAddObject(2, spawn);
        assertEquals(goldenBytes("npc_add_object.bin").length, frame.length);
        assertArrayEquals(goldenBytes("npc_add_object.bin"), frame);
    }

    @Test
    void sceneMotionFramesMatchGolden() throws Exception {
        assertArrayEquals(golden("server_moving.bin"),
                NpcMessages.serverMoving(2, 1,
                        NpcMessages.motionFromDest(341.66f, 47.979f, 658.784f, 2.588f)));
        assertArrayEquals(golden("scene_object_transform.bin"),
                NpcMessages.sceneObjectTransformProperties(2, 1, 341.66f, 47.979f, 658.784f, 2.588f));
        assertArrayEquals(golden("scene_remove_object.bin"),
                NpcMessages.sceneRemoveObject(2, 1));
        assertArrayEquals(golden("server_location_npc.bin"),
                NpcMessages.serverLocation(2, 1, 341.66f, 47.979f, 658.784f, 2.588f));
    }

    private byte[] goldenBytes(String name) {
        try {
            return golden(name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void defaultSpawnSceneResolvesChengduFolder() throws Exception {
        // Go resolveFolder：city05 直命中目录名。
        assertEquals("city05_chengdu",
                registry.resolveFolder(new Roles.Scene("ini\\scene\\city05_ChengDu", "city05")));
    }
}
