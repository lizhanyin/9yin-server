package com.nineyin.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void cloneSceneDetectedByResource() {
        assertTrue(SceneManager.isCloneScene("clone003"));
        assertTrue(SceneManager.isCloneScene("Clone003_New"));
        assertTrue(SceneManager.isCloneScene("adv023_clone"));
        assertFalse(SceneManager.isCloneScene("born04"));
        assertFalse(SceneManager.isCloneScene("city02"));
    }

    /** 副本模式：保留 SubSceneNo 摆放、展开 *_creator（显式 position + amount 圆周）、解析波次/重生。 */
    @Test
    void creatorLoaderCloneModeExpandsWaves() throws Exception {
        Path dir = tempDir.resolve("npc_creator/clone003");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("file.ini"), """
                [file]
                0=AttackNpc.xml
                1=AttackNpc_Creator.xml
                """, GBK);
        Files.writeString(dir.resolve("AttackNpc.xml"), """
                <root><staticcreator>
                  <item No="a1" x="10" y="1" z="-10" ay="1" id="NpcClone003005" NpcProgress="2" RefreshTime="-1.0" PatrolMode="1"/>
                  <item No="a2" x="20" y="1" z="-20" id="NpcClone003023" NpcProgress="3" RefreshTime="3.0"/>
                  <item No="a3" x="30" y="1" z="-30" id="NpcClone003036" NpcProgress="7" RefreshTime="30000.0"/>
                  <item No="a4" x="40" y="1" z="-40" id="NpcClone003007" SubSceneNo="5"/>
                  <item No="a5" x="50" y="1" z="-50" id="BossClone003004"/>
                  <item No="a6" x="60" y="1" z="-60" id="hd_event_2024"/>
                </staticcreator></root>
                """, GBK);
        Files.writeString(dir.resolve("AttackNpc_Creator.xml"), """
                <root><randomcreator>
                  <creator No="c1" x="100" y="2" z="-100" radius="3.0" NpcProgress="2" WakeupRange="10.0">
                    <item No="1" id="NpcClone003005" ay="1.5" amount="1" refreshtime="-1">
                      <position x="101" z="-101" ay="0.7"/>
                    </item>
                    <item No="2" id="NpcClone003015" ay="2.0" amount="2" refreshtime="3.0"/>
                  </creator>
                </randomcreator></root>
                """, GBK);

        // 副本模式：static 6 条全保留（含 SubSceneNo/活动 id）+ creator 展开 3 条
        List<NpcInstance> clone = SceneCreatorLoader.load("clone003", tempDir.resolve("npc_creator"), true);
        assertEquals(9, clone.size());
        NpcInstance wave7 = clone.stream().filter(n -> "NpcClone003036".equals(n.configId)).findFirst().orElseThrow();
        assertEquals(7, wave7.npcProgress);
        assertEquals(30000L, wave7.refreshMs, "30000 视为毫秒");
        NpcInstance seconds = clone.stream().filter(n -> "NpcClone003023".equals(n.configId)).findFirst().orElseThrow();
        assertEquals(3000L, seconds.refreshMs, "3.0 视为秒");
        assertEquals(3, seconds.npcProgress);
        // creator 显式 position：坐标与 ay 用 position 值，y 用 creator 值
        NpcInstance explicit = clone.stream()
                .filter(n -> "NpcClone003005".equals(n.configId) && n.x == 101f).findFirst().orElseThrow();
        assertEquals(-101f, explicit.z);
        assertEquals(2f, explicit.y);
        assertEquals(0.7f, explicit.orient, 0.001f);
        assertEquals(2, explicit.npcProgress, "creator 级 NpcProgress 生效");
        // creator 无 position：amount=2 圆周展开（半径 3*0.65=1.95，首点角 0°）
        List<NpcInstance> synth = clone.stream()
                .filter(n -> "NpcClone003015".equals(n.configId)).toList();
        assertEquals(2, synth.size());
        assertEquals(3000L, synth.get(0).refreshMs);
        assertEquals(2, synth.get(0).npcProgress);
        assertEquals(101.95f, synth.get(0).x, 0.01f);
        assertEquals(-100f, synth.get(0).z, 0.01f);

        // 世界模式：SubSceneNo 静态摆放跳过、attack 系 _creator 照常展开（野外随机刷怪），
        // 活动 id 不在加载器剥离（SceneManager 职责）
        List<NpcInstance> world = SceneCreatorLoader.load("clone003", tempDir.resolve("npc_creator"), false);
        assertEquals(8, world.size(), "5 条常驻静态 + creator 展开 3 条");
        assertTrue(world.stream().noneMatch(n -> n.x == 40f), "SubSceneNo 静态摆放在世界模式跳过");
        assertEquals(2, world.stream().filter(n -> "NpcClone003015".equals(n.configId)).count(),
                "amount=2 圆周展开条目应加载");
        assertTrue(world.stream().anyMatch(n -> "NpcClone003005".equals(n.configId) && n.x == 101f),
                "creator 显式 position 条目应加载");
    }

    /** 世界模式（野外图）：attacknpc_creator 随机刷怪点展开，SubSceneNo 副本子摆位与采集系 _creator 跳过。 */
    @Test
    void creatorLoaderWorldModeExpandsWildAttackCreators() throws Exception {
        Path dir = tempDir.resolve("npc_creator/scene08_test");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("file.ini"), """
                [file]
                0=FuncNpc.xml
                1=AttackNpc_Creator.xml
                2=GatherNpc_Creator.xml
                """, GBK);
        Files.writeString(dir.resolve("FuncNpc.xml"), """
                <root><item x="1" y="1" z="1" id="npc_guard_001"/></root>
                """, GBK);
        Files.writeString(dir.resolve("AttackNpc_Creator.xml"), """
                <root><randomcreator>
                  <creator No="c1" x="1000" y="39" z="400" radius="3.0" WakeupRange="10.0" ChaseRange="30.0">
                    <item No="1" id="monf080100" amount="2" refreshtime="30000" desc="马贼"/>
                  </creator>
                  <creator No="c2" x="271" y="30" z="-330" radius="3.0" SubSceneNo="232">
                    <item No="1" id="monf080200" amount="2" refreshtime="30000"/>
                  </creator>
                  <creator No="c3" x="300" y="30" z="-400" radius="3.0">
                    <item No="1" id="monf080300" amount="1" refreshtime="30000" SubSceneNo="234"/>
                  </creator>
                </randomcreator></root>
                """, GBK);
        Files.writeString(dir.resolve("GatherNpc_Creator.xml"), """
                <root><randomcreator>
                  <creator No="g1" x="50" y="1" z="50" radius="2.0">
                    <item No="1" id="gather_herbs_01" amount="4"/>
                  </creator>
                </randomcreator></root>
                """, GBK);

        List<NpcInstance> npcs = SceneCreatorLoader.load("scene08_test", tempDir.resolve("npc_creator"));
        // 1 个功能 NPC + c1 圆周展开 2 条；c2/c3（SubSceneNo）与采集 creator 不加载
        assertEquals(3, npcs.size());
        List<NpcInstance> wild = npcs.stream()
                .filter(n -> "monf080100".equals(n.configId)).toList();
        assertEquals(2, wild.size(), "amount=2 圆周展开");
        assertTrue(wild.get(0).monster, "attack 文件 → 怪物");
        assertEquals(30000L, wild.get(0).refreshMs, "refreshtime=30000 视为毫秒");
        assertEquals(1001.95f, wild.get(0).x, 0.01f, "半径 3*0.65 圆周首点");
        assertEquals(400f, wild.get(0).z, 0.01f);
        assertEquals(39f, wild.get(0).y, "y 取 creator 值");
        assertTrue(npcs.stream().noneMatch(n -> n.configId.startsWith("monf0802") || n.configId.startsWith("monf0803")),
                "SubSceneNo 副本子摆位在世界模式跳过");
        assertTrue(npcs.stream().noneMatch(n -> n.configId.startsWith("gather")),
                "采集系 _creator 在世界模式仍跳过");
    }

    /** 副本场景世界：不剥离 clone/event 摆放，dungeonProgress 从 1 开始，resetWorld 重建。 */
    @Test
    void cloneWorldKeepsPlacementsAndTracksProgress() throws Exception {
        Path share = buildCloneShareRoot();
        SceneManager mgr = new SceneManager(share);
        SceneWorld world = mgr.world("clone003");
        assertTrue(world.cloneScene);
        assertEquals(1, world.dungeonProgress);
        assertEquals(9, world.npcs.size(), "副本模式全量保留（含 hd_event_2024 与 SubSceneNo 摆放）");
        assertTrue(world.npcs.stream().anyMatch(n -> "hd_event_2024".equals(n.configId)));
        assertTrue(world.npcs.stream().anyMatch(n -> "NpcClone003007".equals(n.configId) && n.x == 40f));

        // 动态生成/清理
        NpcInstance dyn = new NpcInstance();
        dyn.configId = "BoxClone003001";
        dyn.tag = "twilight_box";
        dyn.x = dyn.homeX = -79f;
        dyn.y = dyn.homeY = 36f;
        dyn.z = dyn.homeZ = -35f;
        int oid = world.addDynamic(dyn);
        assertTrue(oid >= 900001);
        assertEquals(10, world.npcs.size());
        assertEquals(1, world.removeTagged("twilight_box"));
        assertEquals(9, world.npcs.size());

        // 重置：进度与 kill 状态随世界一起重建
        world.dungeonProgress = 7;
        mgr.resetWorld("clone003");
        SceneWorld fresh = mgr.world("clone003");
        assertNotSame(world, fresh);
        assertEquals(1, fresh.dungeonProgress);
        assertEquals(9, fresh.npcs.size());
    }

    /** clone003 同构 fixture：static + creator + npcconfig 模板。 */
    private Path buildCloneShareRoot() throws Exception {
        Path share = tempDir.resolve("share");
        Path npcconfig = share.resolve("npc/npcconfig/worldnpc");
        Files.createDirectories(npcconfig);
        Files.writeString(npcconfig.resolve("clonenpc.txt"),
                "编号\t脚本\t名字\t资源\nID\tscript\tName\tResource\n"
                        + "NpcClone003005\tcommonnpc\t纵火者\tclonenpc\\a005\n"
                        + "NpcClone003015\tcommonnpc\t纵火帮凶\tclonenpc\\a015\n"
                        + "NpcClone003023\tcommonnpc\t官差\tclonenpc\\a023\n"
                        + "NpcClone003036\tcommonnpc\t锦衣卫刀\tclonenpc\\a036\n"
                        + "NpcClone003007\tcommonnpc\t暗器高手\tclonenpc\\a007\n"
                        + "BossClone003004\tbossnpc\t叶楼凯\tclonenpc\\b004\n"
                        + "hd_event_2024\tcommonnpc\t活动怪\tclonenpc\\act\n"
                        + "BoxClone003001\tboxnpc\t钥匙箱\tclonenpc\\box\n",
                GBK);
        Path creator = share.resolve("creator/npc_creator/clone003");
        Files.createDirectories(creator);
        Files.writeString(creator.resolve("file.ini"), """
                [file]
                0=AttackNpc.xml
                1=AttackNpc_Creator.xml
                """, GBK);
        Files.writeString(creator.resolve("AttackNpc.xml"), """
                <root><staticcreator>
                  <item No="a1" x="10" y="1" z="-10" id="NpcClone003005" NpcProgress="2" RefreshTime="-1.0"/>
                  <item No="a2" x="20" y="1" z="-20" id="NpcClone003023" NpcProgress="3" RefreshTime="3.0"/>
                  <item No="a3" x="30" y="1" z="-30" id="NpcClone003036" NpcProgress="7" RefreshTime="30000.0"/>
                  <item No="a4" x="40" y="1" z="-40" id="NpcClone003007" SubSceneNo="5"/>
                  <item No="a5" x="50" y="1" z="-50" id="BossClone003004"/>
                  <item No="a6" x="60" y="1" z="-60" id="hd_event_2024"/>
                </staticcreator></root>
                """, GBK);
        Files.writeString(creator.resolve("AttackNpc_Creator.xml"), """
                <root><randomcreator>
                  <creator No="c1" x="100" y="2" z="-100" radius="3.0" NpcProgress="2">
                    <item No="1" id="NpcClone003005" amount="1" refreshtime="-1">
                      <position x="101" z="-101"/>
                    </item>
                    <item No="2" id="NpcClone003015" amount="2" refreshtime="3.0"/>
                  </creator>
                </randomcreator></root>
                """, GBK);
        return share;
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

    /** 真实 share 数据冒烟：NINEYIN_SERVER_ROOT 指向含 resources/modern/share 的根时启用。 */
    @Test
    void realClone003PlacementsSmoke() {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        Assumptions.assumeTrue(env != null && !env.isEmpty(), "NINEYIN_SERVER_ROOT 未设置，跳过真实数据冒烟");
        Path share = Path.of(env, "resources", "modern", "share");
        Assumptions.assumeTrue(Files.isDirectory(share.resolve("creator/npc_creator/clone003")),
                "share 数据缺 clone003 摆放目录");

        SceneManager mgr = new SceneManager(share);
        SceneWorld world = mgr.world("clone003");
        assertTrue(world.cloneScene, "clone003 应识别为副本场景");
        assertTrue(world.npcs.size() >= 300,
                "clone003 全量摆位应 ≥300，实际 " + world.npcs.size());
        long wave2 = world.npcs.stream().filter(n -> n.npcProgress == 2).count();
        assertTrue(wave2 >= 15, "NpcProgress=2 波（纵火者/帮凶）应 ≥15，实际 " + wave2);
        long wave7 = world.npcs.stream().filter(n -> n.npcProgress == 7).count();
        assertTrue(wave7 >= 3, "NpcProgress=7 波（锦衣卫刀）应 ≥3，实际 " + wave7);
        assertTrue(world.npcs.stream().anyMatch(n -> "BossClone003004".equals(n.configId)),
                "叶楼凯等 Boss 常驻摆位");
        long refreshed = world.npcs.stream().filter(n -> n.refreshMs > 0).count();
        assertTrue(refreshed >= 10, "宝箱/水桶等 RefreshTime 摆位应 ≥10，实际 " + refreshed);
    }

    /** 真实 share 数据冒烟：野外大地图随机刷怪点应展开（scene01 静态 42 + creator 736 条 ≈ 1082）。 */
    @Test
    void realWildSceneRandomCreatorsSmoke() {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        Assumptions.assumeTrue(env != null && !env.isEmpty(), "NINEYIN_SERVER_ROOT 未设置，跳过真实数据冒烟");
        Path share = Path.of(env, "resources", "modern", "share");
        Assumptions.assumeTrue(Files.isDirectory(share.resolve("creator/npc_creator/scene01_youyunshiliuzhou")),
                "share 数据缺 scene01 摆放目录");

        SceneManager mgr = new SceneManager(share);
        SceneWorld world = mgr.world("scene01_youyunshiliuzhou");
        assertFalse(world.cloneScene, "scene01 应识别为野外场景");
        assertTrue(world.npcs.size() >= 1000,
                "scene01 静态+随机刷怪应 ≥1000，实际 " + world.npcs.size());
        long monsters = world.npcs.stream().filter(n -> n.monster).count();
        assertTrue(monsters >= 900, "怪物（monf 系）应 ≥900，实际 " + monsters);
        long refreshed = world.npcs.stream().filter(n -> n.refreshMs > 0).count();
        assertTrue(refreshed >= 900, "带 RefreshTime 重生的随机刷怪点应 ≥900，实际 " + refreshed);

        // scene08 混有 630 个 SubSceneNo 副本子摆位 creator，野外加载时应被剔除
        SceneWorld saiwei = mgr.world("scene08_saiwaidamo");
        assertFalse(saiwei.cloneScene);
        assertTrue(saiwei.npcs.size() >= 800, "scene08 野外刷怪应 ≥800，实际 " + saiwei.npcs.size());
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
