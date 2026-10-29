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

    private static volatile boolean loaded;
    private static final Map<String, String> ART_BY_ITEM = new HashMap<>();
    private static final Map<String, String> MALE_MODELS = new HashMap<>();
    private static final Map<String, String> FEMALE_MODELS = new HashMap<>();

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

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (EquipmentModels.class) {
            if (loaded) {
                return;
            }
            Path itemDir = shareRoot().resolve("item");
            parse(itemDir.resolve("equipment.ini"), (section, key, value) -> {
                if (key.equals("artpack")) {
                    ART_BY_ITEM.put(section, value);
                }
            });
            parse(itemDir.resolve("itemartstatic.ini"), (section, key, value) -> {
                if (key.equals("malemodel")) {
                    MALE_MODELS.put(section, value);
                } else if (key.equals("femalemodel")) {
                    FEMALE_MODELS.put(section, value);
                }
            });
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
