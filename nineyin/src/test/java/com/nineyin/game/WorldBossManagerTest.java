package com.nineyin.game;

import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界 BOSS 定时刷新：合成 fixture（GBK ini + bossnpc 模板）验证
 * 表加载、时间档调度、生成/防重、击杀回收、存在期回收与 GM 强制生成。
 */
class WorldBossManagerTest {

    @TempDir
    Path tempDir;

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void loadsTablesAndComputesSchedule() throws Exception {
        SceneManager mgr = new SceneManager(buildShareRoot());
        WorldBossManager boss = new WorldBossManager(mgr);
        boss.load(share());

        assertEquals(1, boss.normalEntriesForTest().size());
        assertEquals(1, boss.jhEntriesForTest().size());
        assertEquals("school07", boss.mapListForTest().get(11));
        assertEquals("scene13", boss.mapListForTest().get(90));
        assertEquals("scene20", boss.mapListForTest().get(364));

        // 时间档 hour=10;22 min=0;30 → nextDue 落在 10:00/10:30/22:00/22:30 之一
        WorldBossManager.NormalEntry e = boss.normalEntriesForTest().get("1");
        assertNotNull(e.nextDue);
        int minuteOfDay = e.nextDue.getHour() * 60 + e.nextDue.getMinute();
        assertTrue(minuteOfDay == 600 || minuteOfDay == 630
                        || minuteOfDay == 1320 || minuteOfDay == 1350,
                "nextDue=" + e.nextDue);
        assertTrue(e.nextDue.isAfter(LocalDateTime.now().withSecond(0).withNano(0).minusMinutes(1)));

        // 江湖 BOSS：每日 12:00 刷新
        WorldBossManager.JhEntry jh = boss.jhEntriesForTest().get("bossnewworld002");
        assertNotNull(jh.nextDue);
        assertEquals(12 * 60, jh.nextDue.getHour() * 60 + jh.nextDue.getMinute());
    }

    @Test
    void normalBossSpawnsAtDueTimeAndReapsOnDeath() throws Exception {
        SceneManager mgr = new SceneManager(buildShareRoot());
        WorldBossManager boss = new WorldBossManager(mgr);
        boss.load(share());
        // 隔离：江湖 BOSS 时间档推远，只验证固定点
        boss.jhEntriesForTest().get("bossnewworld002").nextDue = LocalDateTime.now().plusYears(1);

        WorldBossManager.NormalEntry e = boss.normalEntriesForTest().get("1");
        LocalDateTime due = e.nextDue;
        boss.tick(due);
        assertEquals(1, boss.liveCount(), "到点生成 1 只");
        SceneWorld world = mgr.world("school07");
        NpcInstance npc = world.npcs.stream()
                .filter(n -> WorldBossManager.TAG.equals(n.tag)).findFirst().orElseThrow();
        assertTrue(npc.monster);
        assertEquals(896.4f, npc.x, 0.001f);
        assertEquals(-6.3f, npc.y, 0.001f);
        assertEquals(391.1f, npc.z, 0.001f);
        assertEquals(mgr.templates().lookup(npc.configId).maxHp(), npc.maxHp, "bossnpc 模板 MaxHP 生效");
        assertTrue(npc.maxHp >= 800000);
        assertTrue(npc.configId.equals("bossworld001_hd") || npc.configId.equals("bossnewworld001"));

        // 占用中不重复生成
        boss.tick(due.plusMinutes(1));
        assertEquals(1, boss.liveCount());

        // 击杀 → 回收，空出刷新位
        npc.dead = true;
        boss.tick(due.plusMinutes(2));
        assertEquals(0, boss.liveCount());
        assertTrue(world.npcs.stream().noneMatch(n -> WorldBossManager.TAG.equals(n.tag)),
                "死亡 BOSS 从场景移除");
    }

    @Test
    void jhBossRandomScenePointAndDespawnWindow() throws Exception {
        SceneManager mgr = new SceneManager(buildShareRoot());
        WorldBossManager boss = new WorldBossManager(mgr);
        boss.load(share());
        // 隔离：固定点时间档推远，只验证江湖 BOSS
        boss.normalEntriesForTest().get("1").nextDue = LocalDateTime.now().plusYears(1);

        WorldBossManager.JhEntry jh = boss.jhEntriesForTest().get("bossnewworld002");
        boss.tick(jh.nextDue);
        assertEquals(1, boss.liveCount());

        NpcInstance npc = null;
        SceneWorld world = null;
        for (String res : List.of("scene13", "scene20")) {
            SceneWorld w = mgr.worldOrNull(res);
            if (w == null) {
                continue;
            }
            NpcInstance hit = w.npcs.stream()
                    .filter(n -> WorldBossManager.TAG.equals(n.tag)).findFirst().orElse(null);
            if (hit != null) {
                npc = hit;
                world = w;
                break;
            }
        }
        assertNotNull(npc, "随机场景之一应有 BOSS");
        assertEquals("bossnewworld002", npc.configId);
        boolean onPoint = (npc.x == 1008f && npc.z == 1684f) || (npc.x == 1573f && npc.z == 798f)
                || (npc.x == 1f && npc.z == 3f);
        assertTrue(onPoint, "坐标应为随机刷新点之一: " + npc.x + "," + npc.z);

        // 存在时段结束（24:00 → 23:59 归一）未死 → 消失
        assertNotNull(jh.nextDespawn);
        boss.tick(jh.nextDespawn.plusMinutes(1));
        assertEquals(0, boss.liveCount());
        assertTrue(world.npcs.stream().noneMatch(n -> WorldBossManager.TAG.equals(n.tag)));
    }

    @Test
    void forceSpawnAndClearAll() throws Exception {
        SceneManager mgr = new SceneManager(buildShareRoot());
        WorldBossManager boss = new WorldBossManager(mgr);
        boss.load(share());

        assertEquals(2, boss.forceSpawnAll(), "1 固定点 + 1 江湖");
        assertEquals(2, boss.liveCount());
        assertEquals(0, boss.forceSpawnAll(), "占用中跳过");
        assertEquals(2, boss.clearAll());
        assertEquals(0, boss.liveCount());
        assertFalse(mgr.world("school07").npcs.stream()
                .anyMatch(n -> WorldBossManager.TAG.equals(n.tag)));
    }

    /** 真实 share 数据冒烟：43 固定刷新点 + 2 江湖 BOSS，强制生成应大量入场。 */
    @Test
    void realWorldBossTablesSmoke() {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        Assumptions.assumeTrue(env != null && !env.isEmpty(), "NINEYIN_SERVER_ROOT 未设置，跳过真实数据冒烟");
        Path share = Path.of(env, "resources", "modern", "share");
        Assumptions.assumeTrue(Files.isRegularFile(share.resolve("npc/normalbossrefreshinfo.ini")),
                "share 数据缺世界 BOSS 表");

        SceneManager mgr = new SceneManager(share);
        WorldBossManager boss = new WorldBossManager(mgr);
        boss.load(share);
        assertTrue(boss.normalEntriesForTest().size() >= 40, "固定刷新点应 43 条");
        assertEquals(2, boss.jhEntriesForTest().size());
        assertEquals("school07", boss.mapListForTest().get(11), "武当（门派世界 BOSS 场景）");
        assertEquals("scene13", boss.mapListForTest().get(90));
        assertEquals("scene20", boss.mapListForTest().get(364));

        int spawned = boss.forceSpawnAll();
        assertTrue(spawned >= 30, "43 固定点 + 2 江湖应至少 30 个可用，实际 " + spawned);
        assertEquals(spawned, boss.liveCount());
        assertTrue(boss.clearAll() >= 30);
        assertEquals(0, boss.liveCount());
    }

    private Path share() {
        return tempDir.resolve("share");
    }

    /** bossnpc 模板 + 四张刷新表 + maplist。 */
    private Path buildShareRoot() throws Exception {
        Path share = share();
        Path npcconfig = Files.createDirectories(share.resolve("npc/npcconfig/worldnpc"));
        Files.writeString(npcconfig.resolve("bossnpc.txt"),
                "编号\t脚本\t名字\t资源\t等级\t最大生命\t攻击\n"
                        + "ID\tscript\tName\tResource\tLevel\tMaxHP\tAttack\n"
                        + "bossworld001_hd\tbossnpc\t三爪雪猿\tboss\\b001\t80\t800000\t5000\n"
                        + "bossnewworld001\tbossnpc\t三爪雪猿\tboss\\b001\t160\t900000\t6000\n"
                        + "bossnewworld002\tbossnpc\t万年火龟\tboss\\b002\t160\t950000\t6500\n",
                GBK);
        Path npcDir = Files.createDirectories(share.resolve("npc"));
        Files.writeString(npcDir.resolve("normalbossrefreshtime.ini"), """
                [1]
                type=1
                beginyear=2000
                beginmonth=1
                beginday=1
                endyear=3000
                endmonth=1
                endday=1
                everydaybeginhour=0
                everydaybeginmin=0
                everydayendhour=23
                everydayendmin=59
                hour=10;22
                min=0;30
                """, GBK);
        Files.writeString(npcDir.resolve("normalbossrefreshinfo.ini"), """
                [1]
                timeid=1
                bosslist=bossworld001_hd;bossnewworld001
                scene=11
                position=896.4,-6.3,391.1
                maxnumber=5
                notice=30360
                """, GBK);
        Files.writeString(npcDir.resolve("jhscenebossrefreshinfo.ini"), """
                [bossnewworld002]
                Week=0
                MinHour=12
                MinMinute=0
                MaxHour=24
                MaxMinute=0
                Scene=90,364
                """, GBK);
        Files.writeString(npcDir.resolve("jhscenebossrefreshpoint.ini"), """
                [90]
                Point=1008,81,1684
                Point=1573,79,798
                [364]
                Point=1,2,3
                """, GBK);
        Path rule = Files.createDirectories(share.resolve("rule"));
        Files.writeString(rule.resolve("maplist.ini"), """
                [MapList]
                11=school07
                90=scene13
                364=scene20
                """, GBK);
        return share;
    }
}
