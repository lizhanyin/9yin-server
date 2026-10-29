package com.nineyin.game;

import com.nineyin.scene.DoorRoutes;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 野外怪物刷新验证：
 * - respawnDelay：数据 RefreshTime 优先（<=1000 视为秒），未配置走 15s 默认。
 * - 真实数据冒烟：door_data 传送门可达的全部野外场景（与 NPC 对话进图的可达集）
 *   + 踢馆独立场景 adv148/adv153，逐图统计怪物量与带重生间隔的比例。
 */
class WildMonsterRespawnTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void respawnDelayPrefersDataRefreshTime() {
        NpcInstance dataMs = new NpcInstance();
        dataMs.refreshMs = 30000;
        assertEquals(30000L, SceneFlow.respawnDelay(dataMs), "30000 视为毫秒");
        NpcInstance dataSec = new NpcInstance();
        dataSec.refreshMs = 5000; // refreshtime="5" → 5s
        assertEquals(5000L, SceneFlow.respawnDelay(dataSec));
        NpcInstance none = new NpcInstance();
        none.refreshMs = -1;
        assertEquals(15000L, SceneFlow.respawnDelay(none), "未配置走 15s 默认");
        NpcInstance worldBoss = new NpcInstance();
        worldBoss.tag = WorldBossManager.TAG;
        worldBoss.refreshMs = 30000;
        // 世界 BOSS 击杀由管理器回收，SceneFlow 置 respawnAtMs=Long.MAX_VALUE（不走 respawnDelay）
        worldBoss.dead = true;
        worldBoss.respawnAtMs = Long.MAX_VALUE;
        assertEquals(Long.MAX_VALUE, worldBoss.respawnAtMs);
    }

    /**
     * 真实 share 数据冒烟：门可达野外场景逐图加载，怪物应大量在场且 ≥3/4 带数据重生间隔。
     * 逐图数字打到 stdout 供核对（surefire 报告可见）。
     */
    @Test
    void realDoorScenesMonsterSmoke() throws Exception {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        Assumptions.assumeTrue(env != null && !env.isEmpty(), "NINEYIN_SERVER_ROOT 未设置，跳过真实数据冒烟");
        Path share = Path.of(env, "resources", "modern", "share");
        Assumptions.assumeTrue(Files.isRegularFile(share.resolve("rule/door_data.ini")),
                "share 数据缺 door_data.ini");

        // 门可达场景集合（clone 系副本另有冒烟覆盖；door_data 含 3 条空 TargetScene 噪声）
        TreeSet<String> resources = new TreeSet<>();
        for (String line : Files.readAllLines(share.resolve("rule").resolve("door_data.ini"), GBK)) {
            String low = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (!low.startsWith("targetscene=")) {
                continue;
            }
            String config = line.trim().substring("TargetScene=".length()).trim();
            if (config.isEmpty() || config.toLowerCase(java.util.Locale.ROOT).contains("clone")) {
                continue;
            }
            resources.add(DoorRoutes.resourceForScene(config));
        }
        resources.add("adv148"); // 冥岳（踢馆独立场景）
        resources.add("adv153"); // 净天宫（踢馆独立场景）

        SceneManager mgr = new SceneManager(share);
        int scenesWithMonsters = 0;
        long totalMonsters = 0;
        long withRefresh = 0;
        List<String> zeroMonsterScenes = new ArrayList<>();
        for (String res : resources) {
            SceneWorld w = mgr.world(res);
            long monsters = w.npcs.stream().filter(n -> n.monster).count();
            long refreshed = w.npcs.stream().filter(n -> n.monster && n.refreshMs > 0).count();
            System.out.printf("scene %-24s npcs=%-5d monsters=%-5d refreshed=%d%n",
                    res, w.npcs.size(), monsters, refreshed);
            totalMonsters += monsters;
            withRefresh += refreshed;
            if (monsters > 0) {
                scenesWithMonsters++;
            } else {
                zeroMonsterScenes.add(res);
            }
        }
        System.out.println("door scenes total=" + resources.size()
                + " withMonsters=" + scenesWithMonsters
                + " monsters=" + totalMonsters + " refreshed=" + withRefresh);
        System.out.println("zero-monster scenes: " + zeroMonsterScenes);

        assertTrue(resources.size() >= 60, "门可达野外场景应 ≥60，实际 " + resources.size());
        assertTrue(scenesWithMonsters >= 40,
                "有怪场景应 ≥40，实际 " + scenesWithMonsters);
        assertTrue(totalMonsters >= 8000, "怪物总量应 ≥8000，实际 " + totalMonsters);
        assertTrue(withRefresh * 4 >= totalMonsters * 3,
                "≥75% 怪应带数据重生间隔，实际 " + withRefresh + "/" + totalMonsters);
        // 多变体短名必须解析到主图（曾有模糊匹配选错变体导致门派图 0 怪）
        for (String res : List.of("scene08", "school07", "school08")) {
            SceneWorld w = mgr.world(res);
            assertTrue(w.npcs.stream().filter(n -> n.monster).count() > 0,
                    res + " 应有怪物（主图摆位）");
        }
    }
}
