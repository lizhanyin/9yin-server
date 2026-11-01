package com.nineyin.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nineyin.cache.ResourceCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 共用配置缓存：同键单次加载、ini/idres 解析、text BOM 容错。 */
class ResourceCacheTest {

    @Test
    void ofLoadsOncePerKey() {
        ResourceCache.invalidateAll();
        List<String> key = new ArrayList<>(List.of("k"));
        ResourceCache.of("probe-key", () -> "value-" + key.size());
        String first = ResourceCache.of("probe-key", () -> "value-" + (key.size() + 1));
        assertEquals("value-1", first);
        assertSame(first, ResourceCache.of("probe-key", () -> "other"));
        ResourceCache.invalidateAll();
        assertNotEquals(first, ResourceCache.of("probe-key", () -> "other"));
    }

    @Test
    void iniParsesSectionsAndComments(@TempDir Path tempDir) throws Exception {
        Path ini = tempDir.resolve("probe.ini");
        java.nio.file.Files.writeString(ini, """
                ; 注释
                # 注释
                [3618]
                ArtPack=1402
                Name=中文名

                [other]
                key = spaced value
                """);
        List<String[]> rows = new ArrayList<>();
        ResourceCache.ini(ini, StandardCharsets.UTF_8, (s, k, v) -> rows.add(new String[]{s, k, v}));
        assertEquals(3, rows.size());
        assertEquals("3618", rows.get(0)[0]);
        assertEquals("ArtPack", rows.get(0)[1]);
        assertEquals("1402", rows.get(0)[2]);
        assertEquals("中文名", rows.get(1)[2]);
        assertEquals("other", rows.get(2)[0]);
        assertEquals("spaced value", rows.get(2)[2]);
        // 解析基于缓存文本重复执行（读盘只一次）；结果抑制由调用方表缓存负责。
        List<String[]> second = new ArrayList<>();
        ResourceCache.ini(ini, StandardCharsets.UTF_8, (s, k, v) -> second.add(new String[]{s, k, v}));
        assertEquals(3, second.size());
    }

    @Test
    void idresLowerCasedWithBom(@TempDir Path tempDir) throws Exception {
        Path idres = tempDir.resolve("probe.idres");
        java.nio.file.Files.write(idres, ("﻿sn_001=千灯镇\ncity05=成都\ncity05=重复取首值\nbadline\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> values = ResourceCache.idresLowerCased(idres);
        assertEquals("千灯镇", values.get("sn_001"));
        assertEquals("成都", values.get("city05"));
    }

    @Test
    void missingFilesYieldEmpty(@TempDir Path tempDir) {
        assertEquals("", ResourceCache.text(tempDir.resolve("absent.ini"), StandardCharsets.UTF_8));
        assertEquals(Map.of(), ResourceCache.idresLowerCased(tempDir.resolve("absent.idres")));
    }
}
