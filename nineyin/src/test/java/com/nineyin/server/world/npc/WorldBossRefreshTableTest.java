package com.nineyin.server.world.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 世界 BOSS 刷新表（worldboss_* 数据源）：合成目录全量断言（门派点/江湖池/点池/
 * 缺表回退），参考服真实表抽样断言（scene 11 武当 5 点、bossnewworld001 场景池、
 * 场景 90 点池——缺失 assumeTrue 跳过，同 GmNpcFactoryTest 方言）。
 */
class WorldBossRefreshTableTest {

    private static final Charset GBK = Charset.forName("GBK");

    /** 参考资源候选根（LoginFlowGmCommandTest.RESOURCE_ROOTS 同款）。 */
    private static final List<Path> RESOURCE_ROOTS = List.of(
            Path.of("E:/Workspace/project/game/9yin-go-server1"),
            Path.of("G:/pojie/ai/9yin-go-server1"),
            Path.of("F:/Game/Online/9yin/9yin_server_new"));

    private static Path referenceNpcRoot() {
        for (Path root : RESOURCE_ROOTS) {
            Path npc = root.resolve(Path.of("resources", "modern", "share", "npc"));
            if (Files.isRegularFile(npc.resolve("normalbossrefreshinfo.ini"))) {
                return npc;
            }
        }
        return null;
    }

    @Test
    void parsesSyntheticTablesFromTempDir(@TempDir Path tempRoot) throws Exception {
        Files.createDirectories(tempRoot);
        Files.writeString(tempRoot.resolve("normalbossrefreshinfo.ini"), """
                [1]
                timeid=1
                bosslist=bossworld001_hd;bossworld002_hd;bossworld004_hd
                scene=11
                position=896.4,-6.3,391.1
                maxnumber=5
                notice=30360

                [2]
                timeid=1
                bosslist=bossworld001_hd;bossworld002_hd
                scene=12
                position=1001.7,-11.5,867.6
                maxnumber=5
                """, GBK);
        Files.writeString(tempRoot.resolve("jhscenebossrefreshinfo.ini"), """
                [bossnewworld001]
                Week=0
                MinHour=12
                Scene=11,364,365
                Prize=box_bossnewworld001_01

                [bossnewworld002]
                Week=0
                Scene=90,354
                """, GBK);
        Files.writeString(tempRoot.resolve("jhscenebossrefreshpoint.ini"), """
                [11]
                Point=1008,81,1684
                Point=-1010,49.5,1677

                [364]
                Point=-1433,450.4,-602
                """, GBK);

        WorldBossRefreshTable table = WorldBossRefreshTable.load(tempRoot);
        assertTrue(table.present(), "合成表可解析");

        // 门派固定点：bosslist 分号拆分 + position 三列浮点，按节号升序、按场景过滤。
        List<WorldBossRefreshTable.NormalPoint> scene11 = table.normalPointsForScene(11);
        assertEquals(1, scene11.size(), "场景 11 一个门派点");
        WorldBossRefreshTable.NormalPoint point = scene11.get(0);
        assertEquals(1, point.id());
        assertEquals(List.of("bossworld001_hd", "bossworld002_hd", "bossworld004_hd"),
                point.bossList());
        assertEquals(896.4f, point.x(), 0.001f);
        assertEquals(-6.3f, point.y(), 0.001f);
        assertEquals(391.1f, point.z(), 0.001f);
        assertEquals(1, table.normalPointsForScene(12).size(), "场景 12 一个门派点");

        // 江湖 BOSS：Scene 池命中过滤。
        List<WorldBossRefreshTable.JhBoss> jh11 = table.jhBossesForScene(11);
        assertEquals(1, jh11.size(), "bossnewworld001 池含场景 11");
        assertEquals("bossnewworld001", jh11.get(0).bossId());
        assertEquals(1, table.jhBossesForScene(365).size(), "365 在 bossnewworld001 池内");
        assertEquals(0, table.jhBossesForScene(999).size(), "无池场景不命中任何江湖 BOSS");
        assertEquals(1, table.jhBossesForScene(90).size(), "90 命中 bossnewworld002");

        // 随机点池：Point= 重复键全部入池。
        List<float[]> pool11 = table.jhPointsForScene(11);
        assertEquals(2, pool11.size(), "场景 11 两个随机点");
        assertEquals(1008f, pool11.get(0)[0], 0.001f);
        assertEquals(49.5f, pool11.get(1)[1], 0.001f);
        assertEquals(1, table.jhPointsForScene(364).size());
        assertTrue(table.jhPointsForScene(12).isEmpty(), "无点池场景返回空表");
    }

    @Test
    void missingDirYieldsEmptyTable(@TempDir Path tempRoot) {
        WorldBossRefreshTable table = WorldBossRefreshTable.load(tempRoot);
        assertFalse(table.present(), "目录无刷新表 → 不在场");
        assertTrue(table.normalPointsForScene(11).isEmpty());
        assertTrue(table.jhBossesForScene(364).isEmpty());
        assertTrue(table.jhPointsForScene(90).isEmpty());
    }

    @Test
    void parsesReferenceServerTables() {
        Path npcRoot = referenceNpcRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(npcRoot != null,
                "参考服刷新表不可用，跳过世界 BOSS 表断言");
        WorldBossRefreshTable table = WorldBossRefreshTable.load(npcRoot);
        assertTrue(table.present());

        // 武当（scene 11）5 个门派固定点，bosslist 为 bossworld* 官方模板。
        List<WorldBossRefreshTable.NormalPoint> scene11 = table.normalPointsForScene(11);
        assertEquals(5, scene11.size(), "武当 5 个固定点");
        assertTrue(scene11.get(0).bossList().contains("bossworld001_hd"));
        for (WorldBossRefreshTable.NormalPoint point : scene11) {
            assertFalse(point.bossList().isEmpty());
        }

        // 江湖 BOSS 场景池：bossnewworld001 覆盖 364-367；门派场景不在江湖池。
        List<WorldBossRefreshTable.JhBoss> jh364 = table.jhBossesForScene(364);
        assertTrue(jh364.stream().anyMatch(boss -> boss.bossId().equals("bossnewworld001")),
                "364 命中 bossnewworld001");
        assertTrue(table.jhBossesForScene(11).isEmpty(), "门派场景无江湖 BOSS");

        // 点池：场景 90 十个随机点。
        assertTrue(table.jhPointsForScene(90).size() >= 10, "场景 90 点池充足");
        float[] first = table.jhPointsForScene(90).get(0);
        assertEquals(3, first.length);
    }
}
