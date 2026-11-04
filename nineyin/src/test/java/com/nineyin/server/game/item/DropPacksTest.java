package com.nineyin.server.game.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * drops_mvp.json 权重包：固定 {@link DoubleSupplier} 钉死掷骰序列——抽中条目与
 * 数量分骰、同物品跨 roll 合并、rolls 钳制、脏条目跳过、缺文件容错。
 */
class DropPacksTest {

    @TempDir
    Path dir;

    private static final String PACKS = """
            {
              "version": 1,
              "packages": {
                "p1": [
                  { "item": "a", "min": 1, "max": 1, "weight": 60 },
                  { "item": "b", "min": 2, "max": 5, "weight": 40 }
                ],
                "bad": [
                  { "item": "", "min": 1, "max": 1, "weight": 10 },
                  { "item": "w0", "min": 1, "max": 1, "weight": 0 },
                  { "item": "lo0", "min": 0, "max": 1, "weight": 10 },
                  { "item": "rev", "min": 5, "max": 2, "weight": 10 },
                  { "item": "big", "min": 1, "max": 1000, "weight": 10 },
                  { "item": "ok", "min": 1, "max": 1, "weight": 5 }
                ]
              }
            }
            """;

    /** 顺序吐出预置随机数的供应器（超出时报错防静默）。 */
    private static DoubleSupplier rolls(double... values) {
        Queue<Double> queue = new ArrayDeque<>();
        for (double v : values) {
            queue.add(v);
        }
        return () -> {
            Double next = queue.poll();
            if (next == null) {
                throw new IllegalStateException("roll rand exhausted");
            }
            return next;
        };
    }

    private DropPacks load(String json) throws IOException {
        Path file = dir.resolve("drops-" + System.nanoTime() + ".json");
        Files.writeString(file, json);
        return DropPacks.load(file);
    }

    @Test
    void weightedPickAndAmountAreSeparateRolls() throws IOException {
        DropPacks packs = load(PACKS);
        // r=0.0 → cursor 0 → 首 entry a；数量恒 1
        List<DropPacks.GrantItem> a = packs.roll("p1", 1, rolls(0.0, 0.0));
        assertEquals(1, a.size());
        assertEquals("a", a.get(0).configId());
        assertEquals(1, a.get(0).amount());
        // r=0.6 → cursor 60：60-60=0 不中 a，0-40<0 → b；数量 r2=0.0 → min=2，r2=0.999 → 5
        List<DropPacks.GrantItem> b = packs.roll("p1", 1, rolls(0.6, 0.0));
        assertEquals("b", b.get(0).configId());
        assertEquals(2, b.get(0).amount());
        assertEquals(5, packs.roll("p1", 1, rolls(0.6, 0.999)).get(0).amount());
    }

    @Test
    void sameItemMergesAcrossRolls() throws IOException {
        DropPacks packs = load(PACKS);
        List<DropPacks.GrantItem> out = packs.roll("p1", 3,
                rolls(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).configId());
        assertEquals(3, out.get(0).amount());
    }

    @Test
    void rollsClampedToTen() throws IOException {
        DropPacks packs = load(PACKS);
        List<Double> drawn = new ArrayList<>();
        DoubleSupplier recorder = () -> {
            drawn.add(0.0);
            return 0.0;
        };
        List<DropPacks.GrantItem> out = packs.roll("p1", 99, recorder);
        assertEquals(20, drawn.size());   // 10 roll × 2 骰
        assertEquals(10, out.get(0).amount());
    }

    @Test
    void unknownDropIdFallsBackToLadderAndMissingFileTolerated() throws IOException {
        DropPacks packs = load(PACKS);
        // 缺包兜底（2026-11-04 真机"使用无效果"根因）：不再静默空返，按 FALLBACK_LADDER 开
        //（总权重 100：r=0.0 → 首 entry yaopin_00100，数量 r2=0.0 → min=1）。
        List<DropPacks.GrantItem> out = packs.roll("no_such_pack", 1, rolls(0.0, 0.0));
        assertEquals(1, out.size());
        assertEquals("yaopin_00100", out.get(0).configId());
        assertEquals(1, out.get(0).amount());
        // has() 仍只认 authored 包（缺包可追）
        assertFalse(packs.has("no_such_pack"));
        // 缺文件容错不变：空目录 + 空白 dropId 仍空返
        DropPacks missing = DropPacks.load(dir.resolve("absent.json"));
        assertEquals(0, missing.size());
        assertFalse(missing.has("p1"));
        assertTrue(missing.roll("", 1, rolls(0.0, 0.0)).isEmpty());
    }

    @Test
    void invalidEntriesSkippedButPackKeptIfAnyValid() throws IOException {
        DropPacks packs = load(PACKS);
        assertTrue(packs.has("bad"));
        List<DropPacks.GrantItem> out = packs.roll("bad", 1, rolls(0.99, 0.0));
        assertEquals("ok", out.get(0).configId());
    }
}
