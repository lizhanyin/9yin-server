package com.nineyin.server.game.avatar.task;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 真实 share 资源加载冒烟（资源缺失时整类跳过）：主任务表 15000+ 条、
 * NPC 任务归属 4000+、千灯镇样例 NPC（FuncNpc00609 接任务 50）。
 */
class TaskCatalogRealShareTest {

    private static final Path SHARE = Path.of("E:/Workspace/九阴服务端/resources/modern/share");

    @BeforeAll
    static void needsRealShare() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(SHARE.resolve(Path.of("task", "task", "task"))),
                "本机 share 任务表不可用，跳过真实数据冒烟");
    }

    @Test
    void loadsRealShareTaskTables() {
        TaskCatalog catalog = TaskCatalog.loadShare(SHARE);
        assertTrue(catalog.taskCount() > 10_000, "主任务表全量（实测 ~15377 条），got " + catalog.taskCount());

        // 千灯镇 city_commonnpc 探索锚点：FuncNpc00609 TaskCanAccept 含 50。
        TaskCatalog.NpcTasks npc609 = catalog.npcTasks("FuncNpc00609");
        assertTrue(npc609.accept().contains(50), "FuncNpc00609 可接 50（探索报告锚点）");

        TaskCatalog.TaskDef task50 = catalog.task(50);
        assertNotNull(task50, "任务 50 配置存在");
        assertNotNull(catalog.prize(50), "任务 50 奖励配置存在");
    }

    /** 二期进度表冒烟：huntlist/interactivelist 全量加载，subfunction hunter/interact 步骤可解析率高。 */
    @Test
    void loadsRealShareProgressTables() {
        TaskCatalog catalog = TaskCatalog.loadShare(SHARE);
        assertTrue(catalog.huntCount() > 5_000, "huntlist 全量（subfunction hunter ~6031 步），got " + catalog.huntCount());
        assertTrue(catalog.interactCount() > 5_000, "interactivelist 全量（subfunction interact ~10385 步），got " + catalog.interactCount());

        // 解析率：hunter/interact 步骤的 TypeID 应能落到进度表（个别跨表缺失容忍 <2%）。
        int hunterSteps = 0;
        int hunterResolved = 0;
        int interactSteps = 0;
        int interactResolved = 0;
        for (java.util.Map.Entry<Integer, java.util.List<TaskCatalog.Step>> entry
                : catalog.stepsByTask().entrySet()) {
            for (TaskCatalog.Step step : entry.getValue()) {
                if ("hunter".equalsIgnoreCase(step.type())) {
                    hunterSteps++;
                    if (catalog.hunt(parseInt(step.typeId())) != null) {
                        hunterResolved++;
                    }
                } else if ("interact".equalsIgnoreCase(step.type())) {
                    interactSteps++;
                    if (catalog.interact(parseInt(step.typeId())) != null) {
                        interactResolved++;
                    }
                }
            }
        }
        assertTrue(hunterSteps > 3_000, "hunter 步骤全量（实测 ~6031），got " + hunterSteps);
        assertTrue(interactSteps > 8_000, "interact 步骤全量（实测 ~10385），got " + interactSteps);
        // 实测：interact 解析率 ~97.9%（221 个 TypeID 无 interactivelist 行——官方数据
        // 存在跨表引用/缺失，未解析步骤服务端不门控不计数），阈值取 95% 防数据漂移。
        assertTrue(hunterResolved * 100L / hunterSteps >= 95,
                "hunter→huntlist 解析率 ≥95%：resolved=" + hunterResolved + "/" + hunterSteps);
        assertTrue(interactResolved * 100L / interactSteps >= 95,
                "interact→interactivelist 解析率 ≥95%：resolved=" + interactResolved + "/" + interactSteps);

        // 锚点：昆仑 75708 的 hunter 步骤 TypeID=75701 → huntlist newmp_klzyb_002 ×1。
        TaskCatalog.HuntDef hunt75701 = catalog.hunt(75701);
        assertNotNull(hunt75701, "huntlist 75701 配置存在");
        assertTrue("newmp_klzyb_002".equals(hunt75701.npcId()) && hunt75701.count() == 1,
                "75701 = 杀 newmp_klzyb_002 ×1，got " + hunt75701.npcId() + "×" + hunt75701.count());
        assertTrue(catalog.steps(75708).stream().anyMatch(s ->
                "hunter".equalsIgnoreCase(s.type()) && "75701".equals(s.typeId())),
                "任务 75708 含 hunter 步骤 75701");
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
