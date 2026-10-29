package com.nineyin.protocol;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 场景名本地化键（share/rule/scenes.ini）：[id].Config=ini\scene\born04_QianDengZhen。
 * 角色等待界面背景按角色所在（下线）场景取名，键形如 scene_name_017（Go 抓包样本：
 * 下线在 city05_ChengDu=场景 17 时 f[24]=scene_name_017）。
 */
public final class SceneNames {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String FALLBACK_KEY = "scene_name_017";

    private static volatile boolean loaded;
    private static final Map<String, Integer> ID_BY_CONFIG = new HashMap<>();
    private static final Map<String, Integer> ID_BY_RESOURCE = new HashMap<>();

    private SceneNames() {
    }

    /** 场景 config（ini\scene\...）或资源名（born04）→ scene_name_NNN；未知回退 017。 */
    public static String sceneNameKey(String config, String resource) {
        ensureLoaded();
        Integer id = null;
        if (config != null && !config.isEmpty()) {
            id = ID_BY_CONFIG.get(config.toLowerCase(Locale.ROOT));
        }
        if (id == null && resource != null && !resource.isEmpty()) {
            id = ID_BY_RESOURCE.get(resource.toLowerCase(Locale.ROOT));
        }
        return id != null ? String.format("scene_name_%03d", id) : FALLBACK_KEY;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (SceneNames.class) {
            if (loaded) {
                return;
            }
            Path ini = shareRoot().resolve("rule").resolve("scenes.ini");
            if (Files.isRegularFile(ini)) {
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
                        if (eq > 0 && section.chars().allMatch(Character::isDigit)) {
                            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                            if (!key.equals("config")) {
                                continue;
                            }
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
                    // 表缺失/解析失败：sceneNameKey 回退 017
                }
            }
            loaded = true;
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
