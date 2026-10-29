package com.nineyin.protocol;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 角色选择界面显示名解析：
 * 场景中文名 = text/chineses/desc.idres 的 sn_NNN（编号来自 share/rule/scenes.ini 的场景 id，
 * sn_001=千灯镇 sn_004=鸡鸣驿 sn_007=君子堂 sn_017=成都）——对齐旧 Go 服务 f[24] 直发中文。
 * 剧本标题键 = introduction_<code>01_title（book1=qdz/千灯镇 book2=yyz/烟雨庄 book4=cd/成都
 * book5=jm/鸡鸣驿 book8=wmp/无门派），客户端本地化显示——对齐旧 Go 服务 f[25]（抓包
 * book5→introduction_jm01_title）。f[32] 保持 book 原值。
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
    private static final Map<Integer, String> SCENE_NAME_BY_ID = new HashMap<>();

    private SceneNames() {
    }

    /** 角色所在（下线）场景的中文显示名；sn 表缺失时回退 scene_name_NNN 键。 */
    public static String sceneDisplayName(String config, String resource) {
        ensureLoaded();
        Integer id = sceneId(config, resource);
        if (id != null) {
            String name = SCENE_NAME_BY_ID.get(id);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return id != null ? String.format("scene_name_%03d", id) : FALLBACK_KEY;
    }

    /** 剧本标题本地化键；未知剧本回退 book 原值。 */
    public static String bookIntroKey(String book) {
        String code = BOOK_INTRO_CODE.get(book);
        return code != null ? "introduction_" + code + "01_title" : book;
    }

    private static Integer sceneId(String config, String resource) {
        if (config != null && !config.isEmpty()) {
            Integer id = ID_BY_CONFIG.get(config.toLowerCase(Locale.ROOT));
            if (id != null) {
                return id;
            }
        }
        if (resource != null && !resource.isEmpty()) {
            return ID_BY_RESOURCE.get(resource.toLowerCase(Locale.ROOT));
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
            Path shareRoot = shareRoot();
            loadScenesIni(shareRoot.resolve("rule").resolve("scenes.ini"));
            loadDescIdres(shareRoot.getParent()
                    .resolve("text").resolve("chineses").resolve("desc.idres"));
            loaded = true;
        }
    }

    private static void loadScenesIni(Path ini) {
        if (!Files.isRegularFile(ini)) {
            return;
        }
        try {
            String data = new String(Files.readAllBytes(ini), GBK);
            String section = "";
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
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

    /** desc.idres（UTF-8，key=value 行）：sn_NNN=场景中文名。 */
    private static void loadDescIdres(Path idres) {
        if (!Files.isRegularFile(idres)) {
            return;
        }
        try {
            String data = new String(Files.readAllBytes(idres),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!data.isEmpty() && data.charAt(0) == '﻿') {
                data = data.substring(1);
            }
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (!key.startsWith("sn_")) {
                    continue;
                }
                try {
                    int id = Integer.parseInt(key.substring(3));
                    SCENE_NAME_BY_ID.putIfAbsent(id, line.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                    // 非 sn_NNN 形态跳过
                }
            }
        } catch (Exception ignored) {
            // 表缺失：sceneDisplayName 回退 scene_name_NNN
        }
    }

    /** 与 SceneManager.locateShareRoot 同规则：NINEYIN_SERVER_ROOT 优先，其次从 cwd 向上找。 */
    private static Path shareRoot() {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        if (env != null && !env.isEmpty()) {
            Path p = Path.of(env, "resources", "modern", "share");
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("resources").resolve("modern", "share");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Path.of("resources", "modern", "share");
    }
}
