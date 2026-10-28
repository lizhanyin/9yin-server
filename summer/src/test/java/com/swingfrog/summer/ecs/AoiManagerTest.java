package com.swingfrog.summer.ecs;

import com.swingfrog.summer.ecs.aoi.AoiManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AoiManagerTest {

    @Test
    void nearbyQueryFiltersByRadiusAndSortsByDistance() {
        AoiManager aoi = new AoiManager(50);
        aoi.addEntity(1, 0, 0, 0);
        aoi.addEntity(2, 30, 0, 30);    // 距离 ~42
        aoi.addEntity(3, 100, 0, 0);    // 距离 100
        aoi.addEntity(4, 10, 0, 0);     // 距离 10

        List<Long> near = aoi.queryNearby(0, 0, 45, 10, null);
        assertEquals(List.of(1L, 4L, 2L), near); // 0, 10, 42 升序

        List<Long> capped = aoi.queryNearby(0, 0, 45, 1, null);
        assertEquals(List.of(1L), capped);
    }

    @Test
    void yCoordinateIgnoredInDistance() {
        AoiManager aoi = new AoiManager(50);
        aoi.addEntity(1, 0, 5000, 0); // Y 不参与判定
        assertEquals(List.of(1L), aoi.queryNearby(0, 0, 10, 10, null));
    }

    @Test
    void watcherDiffDetectsEnterAndLeave() {
        AoiManager aoi = new AoiManager(50);
        aoi.addWatcher(100, 0, 0, 0, 100);
        aoi.addEntity(1, 10, 0, 10);
        aoi.addEntity(2, 500, 0, 500);

        AoiManager.Diff diff = aoi.updateWatcher(100, 0, 0, 0);
        assertTrue(diff.entered().contains(1L));
        assertFalse(diff.entered().contains(2L));
        assertFalse(diff.entered().contains(100L), "watcher must not see itself");

        // 实体 1 走远 → leave
        aoi.moveEntity(1, 400, 0, 400);
        AoiManager.Diff diff2 = aoi.updateWatcher(100, 0, 0, 0);
        assertTrue(diff2.left().contains(1L));

        // 观察者自己走过去 → enter
        AoiManager.Diff diff3 = aoi.updateWatcher(100, 390, 0, 390);
        assertTrue(diff3.entered().contains(1L));
    }

    @Test
    void removedEntityLeavesWatchers() {
        AoiManager aoi = new AoiManager(50);
        aoi.addWatcher(100, 0, 0, 0, 100);
        aoi.addEntity(1, 5, 0, 5);
        aoi.updateWatcher(100, 0, 0, 0);

        aoi.removeEntity(1);
        AoiManager.Diff diff = aoi.updateWatcher(100, 0, 0, 0);
        assertTrue(diff.left().contains(1L));
    }

    @Test
    void entityCrossesGridCells() {
        AoiManager aoi = new AoiManager(10);
        aoi.addEntity(1, 5, 0, 5);
        aoi.moveEntity(1, 95, 0, 95);
        // 跨格后仍能按新位置查到
        assertEquals(List.of(1L), aoi.queryNearby(95, 95, 5, 10, null));
        assertTrue(aoi.queryNearby(5, 5, 5, 10, null).isEmpty());
    }
}
