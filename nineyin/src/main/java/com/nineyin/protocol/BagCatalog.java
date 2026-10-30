package com.nineyin.protocol;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 扩容包目录（tool_item.ini 的 script=Bag 段，2026-10-31 F 参考服抓包钉死）：
 * - 四族页向：ITEMBAG(道具 ViewID=1)/EQUIPBAG(装备 2)/STUFFBAG(材料 3)/TASKBAG(任务 4)
 * - MaxHardiness = 有效时长（小时）：720=30天、168=7天，与抓包 expires 完全吻合
 * 加载与 EquipmentModels 同规则：NINEYIN_SERVER_ROOT 优先，其次 cwd 向上找 resources/modern/share。
 */
public final class BagCatalog {

    private static final Charset GBK = Charset.forName("GBK");

    /** 扩容包静态信息（页向 1-4 + 时长小时）。 */
    public record BagItem(int page, int durationHours) {
    }

    private static volatile boolean loaded;
    private static final Map<String, BagItem> BAGS = new HashMap<>();

    private BagCatalog() {
    }

    /** 是否扩容包 config。 */
    public static boolean isBag(String configId) {
        return bagOf(configId) != null;
    }

    /** 取扩容包信息；缺失/未加载返回 null。 */
    public static BagItem bagOf(String configId) {
        if (configId == null || configId.isEmpty()) {
            return null;
        }
        ensureLoaded();
        synchronized (BagCatalog.class) {
            return BAGS.get(configId.toLowerCase(Locale.ROOT));
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (BagCatalog.class) {
            if (loaded) {
                return;
            }
            // 先按段收集三键（键序不定），结束后按 script=Bag 过滤定型
            Map<String, String[]> raw = new HashMap<>();
            parse(shareRoot().resolve("item").resolve("tool_item.ini"), (section, key, value) -> {
                String[] f = raw.computeIfAbsent(section, s -> new String[3]);
                switch (key) {
                    case "viewid" -> f[0] = value;
                    case "maxhardiness" -> f[1] = value;
                    case "script" -> f[2] = value;
                    default -> {
                    }
                }
            });
            for (Map.Entry<String, String[]> e : raw.entrySet()) {
                String[] f = e.getValue();
                if (f[2] == null || !"Bag".equalsIgnoreCase(f[2])) {
                    continue;
                }
                int page = parseInt(f[0], 0);
                int hours = parseInt(f[1], 0);
                if (page >= 1 && page <= 4 && hours > 0) {
                    BAGS.put(e.getKey(), new BagItem(page, hours));
                }
            }
            loaded = true;
        }
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private interface IniConsumer {
        void accept(String section, String key, String value);
    }

    private static void parse(Path ini, IniConsumer consumer) {
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
                    section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && !section.isEmpty()) {
                    consumer.accept(section,
                            line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                            line.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) {
            // 与 EquipmentModels 同策略：解析失败保持空目录，调用方回退默认值
        }
    }

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
            Path candidate = dir.resolve("resources").resolve("modern").resolve("share");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Path.of("resources", "modern", "share");
    }
}
