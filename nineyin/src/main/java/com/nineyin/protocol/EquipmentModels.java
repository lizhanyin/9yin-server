package com.nineyin.protocol;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 装备 config → 外观模型路径解析（对齐 Go equipmentResolver）：
 * equipment.ini 的 [config].ArtPack → itemartstatic.ini 的 [ArtPack].Male/FemaleModel。
 * 建角外观 config（cloth_b0001 等）必须解析成 obj\char\... 真实模型路径；
 * 直接下发 config 名或编造路径时客户端在角色等待界面加载不到模型资源。
 */
public final class EquipmentModels {

    private static final Charset GBK = Charset.forName("GBK");

    /** equipment.ini 段的展示字段（物品 view 帧需要，缺省对齐参考实现）。 */
    public record ItemStatic(String photo, int colorLevel, int level,
                             int maxBuildCount, int effectPackId, String equipType) {
    }

    private static volatile boolean loaded;
    private static final Map<String, String> ART_BY_ITEM = new HashMap<>();
    private static final Map<String, String> MALE_MODELS = new HashMap<>();
    private static final Map<String, String> FEMALE_MODELS = new HashMap<>();
    private static final Map<String, String> PHOTOS = new HashMap<>();
    private static final Map<String, ItemStatic> STATICS = new HashMap<>();

    private EquipmentModels() {
    }

    /** 空 config 或已是模型路径（含分隔符）原样返回；否则按 ArtPack 解析，链路缺失回退 config。 */
    public static String resolve(String config, byte sex) {
        if (config.isEmpty() || config.contains("\\") || config.contains("/")) {
            return config;
        }
        ensureLoaded();
        String art;
        String model;
        synchronized (EquipmentModels.class) {
            art = ART_BY_ITEM.get(config.toLowerCase(Locale.ROOT));
            model = sex == 1 && art != null ? FEMALE_MODELS.get(art) : null;
            if (model == null && art != null) {
                model = MALE_MODELS.get(art);
            }
        }
        return model != null ? model : config;
    }

    /**
     * 物品 view 帧的展示字段（icon 路径/颜色/等级/精炼/特效包）。
     * equipment.ini 的 [config] 字段 + ArtPack→itemartstatic.ini 的 Photo；
     * 缺失段返回 null（调用方省略可选属性）。
     */
    public static ItemStatic itemStatic(String config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        ensureLoaded();
        synchronized (EquipmentModels.class) {
            return STATICS.get(config.toLowerCase(Locale.ROOT));
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (EquipmentModels.class) {
            if (loaded) {
                return;
            }
            Path itemDir = shareRoot().resolve("item");
            Map<String, String[]> raw = new HashMap<>();
            parse(itemDir.resolve("equipment.ini"), (section, key, value) -> {
                if (key.equals("artpack")) {
                    ART_BY_ITEM.put(section, value);
                }
                String[] fields = raw.computeIfAbsent(section, s -> new String[5]);
                switch (key) {
                    case "colorlevel" -> fields[0] = value;
                    case "level" -> fields[1] = value;
                    case "maxbuildcount" -> fields[2] = value;
                    case "effectpackid" -> fields[3] = value;
                    case "equiptype" -> fields[4] = value;
                    default -> {
                    }
                }
            });
            parse(itemDir.resolve("itemartstatic.ini"), (section, key, value) -> {
                if (key.equals("malemodel")) {
                    MALE_MODELS.put(section, value);
                } else if (key.equals("femalemodel")) {
                    FEMALE_MODELS.put(section, value);
                } else if (key.equals("photo")) {
                    PHOTOS.put(section, value);
                }
            });
            for (Map.Entry<String, String[]> e : raw.entrySet()) {
                String[] f = e.getValue();
                String art = ART_BY_ITEM.get(e.getKey());
                String photo = art != null ? PHOTOS.get(art) : null;
                STATICS.put(e.getKey(), new ItemStatic(photo,
                        parseInt(f[0], 1), parseInt(f[1], 0),
                        parseInt(f[2], 2), parseInt(f[3], 0),
                        f[4] != null ? f[4] : ""));
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
            Path candidate = dir.resolve("resources").resolve("modern").resolve("share");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Path.of("resources", "modern", "share");
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
        } catch (IOException e) {
            // 解析失败保持空表：resolve 回退 config 原值（与 Go 文件缺失时行为一致）
        }
    }

    private interface IniConsumer {
        void accept(String section, String key, String value);
    }
}
