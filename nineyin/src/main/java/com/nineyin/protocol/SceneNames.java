package com.nineyin.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.nineyin.game.ServerPaths;

/**
 * 角色选择界面所在场景显示名（选角 0x04 rolePara 的 f24），数据源三件：
 * share/rule/scenes.ini（GBK，config/resource → 场景 id）、
 * text/chineses/stringname.idres（UTF-8，键=ini\scene\xxx 小写 / resource，如
 * "ini\scene\school09_yihuagong=移花宫"、"city05=成都"）、
 * text/chineses/scene.idres（UTF-8，场景别名表）。
 *
 * <p>解析顺序：stringname（config 键 → resource 键）→ scene.idres（同序）→
 * 缺失时回退 scene_name_NNN 客户端本地化键（N=scenes.ini 场景 id）。
 */
public final class SceneNames {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String FALLBACK_KEY = "scene_name_017";

    private static final Map<String, String> BOOK_INTRO_CODE = Map.of(
            "book1", "qdz",
            "book2", "yyz",
            "book4", "cd",
            "book5", "jm",
            "book8", "wmp");

    private static volatile boolean loaded;
    private static final Map<String, Integer> ID_BY_CONFIG = new HashMap<>();
    private static final Map<String, Integer> ID_BY_RESOURCE = new HashMap<>();
    private static final Map<String, String> NAME_BY_KEY = new HashMap<>();

    private SceneNames() {
    }

    /** 角色所在（下线）场景显示名；表缺失时回退 scene_name_NNN 键（客户端本地化）。 */
    public static String sceneDisplayName(String config, String resource) {
        ensureLoaded();
        String configLower = config == null ? "" : config.toLowerCase(Locale.ROOT);
        String resourceLower = resource == null ? "" : resource.toLowerCase(Locale.ROOT);
        String name = NAME_BY_KEY.get(configLower);
        if (name == null || name.isEmpty()) {
            name = NAME_BY_KEY.get(resourceLower);
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        Integer id = sceneId(configLower, resourceLower);
        return id != null ? String.format("scene_name_%03d", id) : FALLBACK_KEY;
    }

    /** 剧本标题本地化键；未知剧本回退 book 原值。 */
    public static String bookIntroKey(String book) {
        String code = BOOK_INTRO_CODE.get(book);
        return code != null ? "introduction_" + code + "01_title" : book;
    }

    private static Integer sceneId(String configLower, String resourceLower) {
        if (!configLower.isEmpty()) {
            Integer id = ID_BY_CONFIG.get(configLower);
            if (id != null) {
                return id;
            }
        }
        if (!resourceLower.isEmpty()) {
            return ID_BY_RESOURCE.get(resourceLower);
        }
        return null;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (SceneNames.class) {
            if (loaded) {
                return;
            }
            Path shareRoot = ServerPaths.projectPath("resources", "modern", "share");
            loadScenesIni(shareRoot.resolve("rule").resolve("scenes.ini"));
            Path textRoot = shareRoot.getParent().resolve("text").resolve("chineses");
            // stringname 优先（含 ini\scene\xxx 主场景名），scene.idres 作别名兜底。
            loadIdres(textRoot.resolve("stringname.idres"));
            loadIdres(textRoot.resolve("scene.idres"));
            loaded = true;
        }
    }

    /** scenes.ini（GBK）：纯数字 section=场景 id，Config=ini\scene\xxx。 */
    private static void loadScenesIni(Path ini) {
        if (!Files.isRegularFile(ini)) {
            return;
        }
        try {
            String data = new String(Files.readAllBytes(ini), GBK);
            String section = "";
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                        || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && section.chars().allMatch(Character::isDigit)
                        && line.substring(0, eq).trim().equalsIgnoreCase("Config")) {
                    String cfg = line.substring(eq + 1).trim();
                    int id;
                    try {
                        id = Integer.parseInt(section);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    ID_BY_CONFIG.put(cfg.toLowerCase(Locale.ROOT), id);
                    int slash = Math.max(cfg.lastIndexOf('\\'), cfg.lastIndexOf('/'));
                    String base = slash >= 0 ? cfg.substring(slash + 1) : cfg;
                    ID_BY_RESOURCE.putIfAbsent(base.toLowerCase(Locale.ROOT), id);
                }
            }
        } catch (Exception ignored) {
            // 表缺失：sceneDisplayName 回退 scene_name_NNN
        }
    }

    /** idres（UTF-8 可带 BOM，key=value）：键统一小写入表（查找用小写键）。 */
    private static void loadIdres(Path idres) {
        if (!Files.isRegularFile(idres)) {
            return;
        }
        try {
            String data = new String(Files.readAllBytes(idres), StandardCharsets.UTF_8);
            if (!data.isEmpty() && data.charAt(0) == '﻿') {
                data = data.substring(1);
            }
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                if (!value.isEmpty()) {
                    NAME_BY_KEY.putIfAbsent(key, value);
                }
            }
        } catch (Exception ignored) {
            // 表缺失：sceneDisplayName 回退 scene_name_NNN
        }
    }
}
