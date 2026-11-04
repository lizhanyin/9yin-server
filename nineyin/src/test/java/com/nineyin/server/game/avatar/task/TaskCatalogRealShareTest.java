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
}
