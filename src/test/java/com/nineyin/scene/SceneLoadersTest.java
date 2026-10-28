package com.nineyin.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用合成 fixture（与真实资源同构：GBK、双表头 TSV、XML item、mappath ini）验证加载链。 */
class SceneLoadersTest {

    @TempDir
    Path tempDir;

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void npcTemplateLoaderParsesGbkTsv() throws Exception {
        Path root = tempDir.resolve("npcconfig/worldnpc");
        Files.createDirectories(root);
        // 首行中文表头，次行英文字段名，数据行含中文名
        String tsv = "编号\t脚本\t名字\t资源\t等级\t最大生命\t武器类型\n"
                + "ID\tscript\tName\tResource\tLevel\tMaxHP\tWeaponMode\n"
                + "npc_qd_001\tcommonnpc\t张铁匠\tworldnpc\\qd_tiejiang\t10\t500\t0\n"
                + "npc_qd_002\tattacknpc\t山贼\tworldnpc\\qd_shanzei\t12\t800\t1\n";
        Files.writeString(root.resolve("city_commonnpc.txt"), tsv, GBK);

        NpcTemplateRepository repo = new NpcTemplateRepository();
        repo.load(tempDir.resolve("npcconfig"));
        assertEquals(2, repo.size());

        NpcTemplate t = repo.lookup("npc_qd_001");
        assertEquals("张铁匠", t.name(), "中文名必须按 GBK 解码");
        assertEquals("worldnpc\\qd_tiejiang", t.resource());
        assertEquals("commonnpc", t.scriptClass());
        assertEquals(10, t.level());
        assertEquals(500, t.maxHp());
        // 路径形式回退查找
        assertEquals("张铁匠", repo.lookup("obj\\npc\\npc_qd_001").name());
    }

    @Test
    void creatorLoaderReadsItemsAndSkips() throws Exception {
        Path dir = tempDir.resolve("npc_creator/born04_qiandengzhen");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("file.ini"), """
                [file]
                1=FuncNpc.xml
                2=AttackNpc.xml
                3=EventTrigger.xml
                """, GBK);
        Files.writeString(dir.resolve("FuncNpc.xml"), """
                <root>
                  <item x="100.5" y="1.0" z="200.25" ay="3.14" id="npc_qd_001" BornAction="talk"/>
                  <item x="110" y="1" z="210" id="npc_qd_002" PathId="AI_test_001"/>
                  <item x="999" y="1" z="999" id="npc_hidden" SubSceneNo="1"/>
                </root>
                """, GBK);
        Files.writeString(dir.resolve("AttackNpc.xml"), """
                <root>
                  <item x="150" y="2" z="250" id="mon_test_001" PathId="AI_test_001"/>
                </root>
                """, GBK);
        Files.writeString(dir.resolve("EventTrigger.xml"), "<root><item x='1' y='1' z='1' id='evt_1'/></root>", GBK);

        List<NpcInstance> npcs = SceneCreatorLoader.load("born04", tempDir.resolve("npc_creator"));
        // EventTrigger 被清单过滤，SubSceneNo 副本摆放被跳过
        assertEquals(3, npcs.size());
        NpcInstance first = npcs.get(0);
        assertEquals(10001, first.objectId);
        assertEquals("npc_qd_001", first.configId);
        assertEquals(100.5f, first.x);
        assertEquals(3.14f, first.orient);
        assertEquals("talk", first.bornAction);
        assertFalse(first.monster);
        NpcInstance monster = npcs.get(2);
        assertTrue(monster.monster, "attack 文件 + mon 前缀 → 怪物");
        assertEquals("AI_test_001", monster.pathId);
    }

    @Test
    void mappathLoaderAndRouteBinding() throws Exception {
        Path mappath = tempDir.resolve("mappath");
        Files.createDirectories(mappath);
        Files.writeString(mappath.resolve("born04_npc.ini"), """
                ; comment
                [AI_test_001]
                r=,100,1,200,0,3000,,walk,,1,
                r=,110,1,210,1.5,200
                r=,110,1,210,0.5,200
                r=,120,1,220,0,200
                [Other_Path]
                r=,0,0,0,0,200
                """, GBK);

        Map<String, List<Waypoint>> table = PatrolPaths.load(mappath, "born04_YanYuZhuang");
        assertEquals(2, table.size());
        List<Waypoint> route = table.get("AI_test_001");
        assertEquals(4, route.size(), "原始表不去重，绑定阶段才去重");

        // 绑定：NPC 家在 (101,1,201) → 吸附最近点 (100,1,200)
        NpcInstance n = new NpcInstance();
        n.configId = "npc_qd_002";
        n.homeX = 101;
        n.homeY = 1;
        n.homeZ = 201;
        n.x = 101;
        n.z = 201;
        n.pathId = "AI_test_001";
        PatrolPaths.assignRoutes(List.of(n), table);
        assertEquals(3, n.route.size());
        assertEquals(100f, n.x, 0.001f, "应吸附到最近路点");

        // 太远的 NPC 原地站立（不发明路线）
        NpcInstance far = new NpcInstance();
        far.configId = "npc_far";
        far.pathId = "AI_test_001";
        far.homeX = 5000;
        far.homeZ = 5000;
        PatrolPaths.assignRoutes(List.of(far), table);
        assertTrue(far.route.isEmpty());

        // 步进：开腿后 routeIdx 指向目的路点，patrolSign = travel+wait 毫秒
        assertTrue(PatrolPaths.stepPatrol(n));
        assertEquals(1, n.routeIdx);
        assertTrue(n.patrolSign >= 700 + 200);
        PatrolPaths.confirmArrive(n);
        assertEquals(110f, n.x, 0.001f);
        assertEquals(210f, n.z, 0.001f);
    }

    @Test
    void activityIdFilter() {
        assertTrue(SceneManager.isActivityId("hd_2024_event_npc"));
        assertTrue(SceneManager.isActivityId("Escort_Guard_01"));
        assertTrue(SceneManager.isActivityId("qy_odd_99"));
        assertFalse(SceneManager.isActivityId("npc_qd_001"));
        assertFalse(SceneManager.isActivityId("worldnpc_leader"));
    }

    @Test
    void sceneWorldTicksPatrolLegs() throws Exception {
        Path share = buildShareRoot();
        SceneManager mgr = new SceneManager(share);
        SceneWorld world = mgr.world("born04");
        assertEquals(3, world.npcs.size());

        long now = 1000;
        world.tick(now);
        // 有路线的 NPC（npc_qd_002 + mon_test_001）各开一腿
        assertEquals(2, world.legs.size());
        SceneWorld.PendingLeg leg = world.legs.get(10002);
        // 10√2 m @2.5m/s ≈ 5657ms + wait 200ms
        long legMs = leg.arriveAtMs - now;
        assertTrue(legMs >= 5600 && legMs <= 6000, "leg duration " + legMs);
        assertEquals(110f, leg.x);

        // 到达：位置落位，并立即开向下个路点的第二条腿
        world.tick(now + legMs + 1);
        NpcInstance walker = world.npcById(10002);
        assertEquals(110f, walker.x, 0.001f);
        assertEquals(210f, walker.z, 0.001f);
        SceneWorld.PendingLeg nextLeg = world.legs.get(10002);
        assertEquals(120f, nextLeg.x, "到达后应立即开新腿指向 (120,220)");
    }

    /** 构造完整 share 根：npcconfig + creator + mappath（book1 → born04）。 */
    private Path buildShareRoot() throws Exception {
        Path share = tempDir.resolve("share");
        Path npcconfig = share.resolve("npc/npcconfig/worldnpc");
        Files.createDirectories(npcconfig);
        Files.writeString(npcconfig.resolve("city_commonnpc.txt"),
                "编号\t脚本\t名字\t资源\nID\tscript\tName\tResource\n"
                        + "npc_qd_001\tcommonnpc\t张铁匠\tworldnpc\\qd_tiejiang\n"
                        + "npc_qd_002\tcommonnpc\t李药商\tworldnpc\\qd_yaoshang\n"
                        + "mon_test_001\tattacknpc\t山贼\tworldnpc\\qd_shanzei\n"
                        + "hd_act_001\tcommonnpc\t活动怪\tworldnpc\\act\n",
                GBK);
        Path creator = share.resolve("creator/npc_creator/born04_qiandengzhen");
        Files.createDirectories(creator);
        Files.writeString(creator.resolve("file.ini"), "1=FuncNpc.xml\n", GBK);
        Files.writeString(creator.resolve("FuncNpc.xml"), """
                <root>
                  <item x="100" y="1" z="200" ay="0" id="npc_qd_001" BornAction="talk"/>
                  <item x="101" y="1" z="201" id="npc_qd_002" PathId="AI_test_001"/>
                  <item x="119" y="2" z="219" id="mon_test_001" PathId="AI_test_001"/>
                  <item x="10" y="1" z="10" id="hd_act_001"/>
                </root>
                """, GBK);
        Path mappath = share.resolve("creator/mappath");
        Files.createDirectories(mappath);
        Files.writeString(mappath.resolve("born04_npc.ini"), """
                [AI_test_001]
                r=,100,1,200,0,3000
                r=,110,1,210,1.5,200
                r=,120,1,220,0,200
                """, GBK);
        return share;
    }
}
