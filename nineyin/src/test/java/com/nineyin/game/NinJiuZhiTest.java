package com.nineyin.game;

import com.alibaba.fastjson.JSON;
import com.nineyin.protocol.BootstrapPackets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 九阴志全局门控：阶段单调性、事件点自动推进、五类门控与状态落盘。 */
class NinJiuZhiTest {

    @Test
    void defaultsWithoutDataAreAllOpenAtStage1() {
        NinJiuZhi nz = NinJiuZhi.alwaysOpen();
        assertEquals(1, nz.stage());
        assertTrue(nz.npcOpen("anything"));
        assertTrue(nz.sceneOpen("book1"));
        assertTrue(nz.taskOpen(1346));
        assertTrue(nz.isOpen(NinJiuZhi.Category.INNER_BOOKS, "ng_wd_001"));
        assertEquals(List.of("book1", "book2"), nz.filterOpen(NinJiuZhi.Category.SCENES,
                List.of("book1", "book2")));
    }

    @Test
    void setStageIsMonotonic() {
        NinJiuZhi nz = NinJiuZhi.alwaysOpen();
        assertNull(nz.setStage(3));
        assertEquals(3, nz.stage());
        assertNotNull(nz.setStage(2));   // 只增不减
        assertEquals(3, nz.stage());
        assertNotNull(nz.setStage(99));  // 越界
    }

    @Test
    void eventsAccumulateAndAutoAdvance() {
        NinJiuZhi nz = NinJiuZhi.alwaysOpen();
        for (int i = 0; i < 10; i++) {
            nz.onKill();
        }
        assertEquals(2, nz.stage());     // 默认阈值 2 阶 = 10 点
        for (int i = 0; i < 20; i++) {
            nz.addEvent("task");
        }
        assertEquals(3, nz.stage());     // 3 阶 = 30 点累计
        assertTrue(nz.status().contains("kill:10"), nz.status());
    }

    @Test
    void resetRestoresInitialStageAndPoints() {
        NinJiuZhi nz = NinJiuZhi.alwaysOpen();
        nz.setStage(5);
        nz.reset();
        assertEquals(1, nz.stage());
        assertTrue(nz.status().contains("累计 0 点"), nz.status());
    }

    @Test
    void stageListenerFiresOnAdvanceAndReset(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ninjiuzhi_requirements.json"), """
                {"initial_stage": 2, "thresholds": {"3": 5}}
                """);
        NinJiuZhi nz = NinJiuZhi.load(dir);
        assertEquals(2, nz.stage());
        StringBuilder fired = new StringBuilder();
        nz.addListener((o, n) -> fired.append(o).append("->").append(n).append(';'));
        for (int i = 0; i < 5; i++) {
            nz.onKill();
        }
        assertEquals("2->3;", fired.toString());
        nz.reset();
        assertEquals("2->3;3->2;", fired.toString());
    }

    @Test
    void requirementsGateFiveCategories(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ninjiuzhi_requirements.json"), """
                {"npcs": {"npc_locked": 3},
                 "scenes": {"book8": 2},
                 "tasks": {"1346": 2},
                 "inner_books": {"ng_wd_001": 4},
                 "features": {"smelting": 5}}
                """);
        NinJiuZhi nz = NinJiuZhi.load(dir);
        assertTrue(nz.npcOpen("npc_free"));
        assertFalse(nz.npcOpen("npc_locked"));
        assertFalse(nz.taskOpen(1346));
        assertFalse(nz.sceneOpen("book8"));
        nz.setStage(3);
        assertTrue(nz.npcOpen("npc_locked"));
        assertTrue(nz.sceneOpen("book8"));
        assertTrue(nz.taskOpen(1346));                 // 1346 要求 2 阶，3 阶已开放
        assertFalse(nz.isOpen(NinJiuZhi.Category.INNER_BOOKS, "ng_wd_001"));
        assertFalse(nz.isOpen(NinJiuZhi.Category.FEATURES, "smelting"));
        assertEquals(List.of("book1", "book8"),
                nz.filterOpen(NinJiuZhi.Category.SCENES, List.of("book1", "book8")));
    }

    @Test
    void statePersistsAcrossLoads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ninjiuzhi_requirements.json"),
                "{\"initial_stage\": 1}");
        NinJiuZhi first = NinJiuZhi.load(dir);
        first.setStage(4);
        first.onKill();
        long revision = first.revision();

        NinJiuZhi second = NinJiuZhi.load(dir);
        assertEquals(4, second.stage());
        assertTrue(second.status().contains("kill:1"), second.status());
        assertEquals(revision, second.revision());

        // 落盘结构与 data-import 的 ninjiuzhi.json 同构
        var saved = JSON.parseObject(Files.readString(dir.resolve("ninjiuzhi.json")));
        assertEquals(4, saved.getIntValue("stage"));
        assertEquals(1, saved.getIntValue("active"));
        assertEquals(1, saved.getJSONObject("events").getLongValue("kill"));
    }

    @Test
    void functionUnlockFramesRespectTaskGating() {
        // 1346 剔除后仍生成帧（位图行按剩余任务计算），不抛异常即布局合法
        List<byte[]> frames = BootstrapPackets.functionUnlockFrames(t -> t != 1346);
        assertFalse(frames.isEmpty());
    }
}
