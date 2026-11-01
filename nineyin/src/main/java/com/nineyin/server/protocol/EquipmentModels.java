package com.nineyin.server.protocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;

/**
 * equipment.ini 物品静态展示字段（三期背包/纸娃娃行需要），移植 bak EquipmentModels：
 * [config].ColorLevel/Level/MaxBuildCount/EffectPackID/EquipType +
 * ArtPack→itemartstatic.ini 的 Photo。模型路径解析复用 {@link
 * EquipmentResolver}。
 */
public final class EquipmentModels {

    /** 物品 view 帧展示字段（photo 可空=省略可选属性）。 */
    public record ItemStatic(String photo, int colorLevel, int level,
                             int maxBuildCount, int effectPackId, String equipType) {
    }

    private static volatile boolean loaded;
    private static final Map<String, String> ART_BY_ITEM = new HashMap<>();
    private static final Map<String, String> PHOTOS = new HashMap<>();
    private static final Map<String, ItemStatic> STATICS = new HashMap<>();

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
            Path itemDir = ServerPaths.projectPath("resources", "modern", "share", "item");
            Map<String, String[]> raw = new HashMap<>();
            ResourceCache.ini(itemDir.resolve("equipment.ini"), StandardCharsets.ISO_8859_1,
                    (section, key, value) -> {
                        String normalized = section.toLowerCase(Locale.ROOT);
                        if (key.equals("artpack")) {
                            ART_BY_ITEM.put(normalized, value);
                        }
                        String[] fields = raw.computeIfAbsent(normalized, s -> new String[5]);
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
            ResourceCache.ini(itemDir.resolve("itemartstatic.ini"), StandardCharsets.ISO_8859_1,
                    (section, key, value) -> {
                        if (key.equals("photo")) {
                            PHOTOS.put(section, value);
                        }
                    });
            for (Map.Entry<String, String[]> entry : raw.entrySet()) {
                String[] f = entry.getValue();
                String art = ART_BY_ITEM.get(entry.getKey());
                String photo = art != null ? PHOTOS.get(art) : null;
                STATICS.put(entry.getKey(), new ItemStatic(photo,
                        parseInt(f[0], 1), parseInt(f[1], 0),
                        parseInt(f[2], 2), parseInt(f[3], 0),
                        f[4] != null ? f[4] : ""));
            }
            loaded = true;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private EquipmentModels() {
    }
}
