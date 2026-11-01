package com.nineyin.game.avatar.equipment;

import com.nineyin.game.ServerPaths;
import com.nineyin.cache.ResourceCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 装备 ConfigID → 模型路径解析器，对齐 Go cmd/protocol-probe/appearance.go loadEquipmentResolver：
 * item/equipment.ini 的 ArtPack + item/itemartstatic.ini 的 MaleModel/FemaleModel。
 *
 * <p>表经 {@link ResourceCache} 按目录缓存：11MB equipment.ini 只在首次解析，
 * 后续登录/创角直接命中缓存。
 */
public final class EquipmentResolver {

    public record Models(String male, String female) {
    }

    private final Map<String, String> artByItem;
    private final Map<String, Models> models;

    public static EquipmentResolver load(Path itemRoot) {
        // Go 逐字节透传：GBK/UTF-8 混排资源必须无损读（Latin-1 每字节一字符），
        // UTF-8 解码器遇 GBK 字节会中途抛 MalformedInputException 截断整表。
        Map<String, String> artByItem = new HashMap<>();
        ResourceCache.ini(itemRoot.resolve("equipment.ini"), StandardCharsets.ISO_8859_1,
                (section, key, value) -> {
                    if (key.equals("ArtPack")) {
                        artByItem.put(section, value);
                    }
                });
        Map<String, Models> models = new HashMap<>();
        ResourceCache.ini(itemRoot.resolve("itemartstatic.ini"), StandardCharsets.ISO_8859_1,
                (section, key, value) -> {
                    Models current = models.getOrDefault(section, new Models("", ""));
                    if (key.equals("MaleModel")) {
                        models.put(section, new Models(value, current.female()));
                    } else if (key.equals("FemaleModel")) {
                        models.put(section, new Models(current.male(), value));
                    }
                });
        return new EquipmentResolver(artByItem, models);
    }

    /** Go defaultModernShareRoot/item；结果常驻缓存（serverRoot 变化自动换表）。 */
    public static EquipmentResolver loadDefault() {
        return ResourceCache.of(ServerPaths.projectPath("resources", "modern", "share", "item"),
                () -> load(ServerPaths.projectPath("resources", "modern", "share", "item")));
    }

    private EquipmentResolver(Map<String, String> artByItem, Map<String, Models> models) {
        this.artByItem = artByItem;
        this.models = models;
    }

    /** Go resolve：已含反斜杠或空 → 原样；ArtPack → 模型；男路径优先级 male/female。 */
    public String resolve(String config, int sex) {
        if (config == null || config.isEmpty() || config.contains("\\")) {
            return config == null ? "" : config;
        }
        String art = artByItem.get(config);
        Models item = models.get(art);
        if (item == null) {
            return config;
        }
        if (sex == 1 && !item.female().isEmpty()) {
            return item.female();
        }
        return item.male().isEmpty() ? config : item.male();
    }
}
