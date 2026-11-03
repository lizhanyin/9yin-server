package com.nineyin.server.game.avatar.equipment;

import com.nineyin.util.ServerPaths;
import com.nineyin.commom.cache.ResourceCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 装备 ConfigID → 模型路径解析器，对齐 Go cmd/protocol-probe/appearance.go loadEquipmentResolver：
 * item/equipment.ini 的 ArtPack + item/itemartstatic.ini 的 MaleModel/FemaleModel。
 *
 * <p>另收套装四件字段 HatArtPack/PlantsArtPack/ShoesArtPack（身份装 ItemType=180
 * 整套与「套装式时装」FashionCoat 携带，-1=无该件），供
 * {@link com.nineyin.server.game.avatar.appearance.AppearanceSync} 按部件解析。
 *
 * <p>表经 {@link ResourceCache} 按目录缓存：11MB equipment.ini 只在首次解析，
 * 后续登录/创角直接命中缓存。
 */
public final class EquipmentResolver {

    public record Models(String male, String female) {
    }

    /** 套装四件 ArtPack（下标 0=主 ArtPack 1=Hat 2=Plants(裤) 3=Shoes）。 */
    private record Packs(String cloth, String hat, String pants, String shoes) {
    }

    private static final Packs NO_PACKS = new Packs("", "", "", "");

    private final Map<String, Packs> packs;
    private final Map<String, Models> models;

    public static EquipmentResolver load(Path itemRoot) {
        // Go 逐字节透传：GBK/UTF-8 混排资源必须无损读（Latin-1 每字节一字符），
        // UTF-8 解码器遇 GBK 字节会中途抛 MalformedInputException 截断整表。
        Map<String, Packs> packs = new HashMap<>();
        ResourceCache.ini(itemRoot.resolve("equipment.ini"), StandardCharsets.ISO_8859_1,
                (section, key, value) -> {
                    Packs current = packs.getOrDefault(section, NO_PACKS);
                    switch (key) {
                        case "ArtPack" -> packs.put(section, new Packs(value, current.hat(),
                                current.pants(), current.shoes()));
                        case "HatArtPack" -> packs.put(section, new Packs(current.cloth(), value,
                                current.pants(), current.shoes()));
                        case "PlantsArtPack" -> packs.put(section, new Packs(current.cloth(), current.hat(),
                                value, current.shoes()));
                        case "ShoesArtPack" -> packs.put(section, new Packs(current.cloth(), current.hat(),
                                current.pants(), value));
                        default -> {
                        }
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
        return new EquipmentResolver(packs, models);
    }

    /** Go defaultModernShareRoot/item；结果常驻缓存（serverRoot 变化自动换表）。 */
    public static EquipmentResolver loadDefault() {
        return ResourceCache.of(ServerPaths.projectPath("resources", "modern", "share", "item"),
                () -> load(ServerPaths.projectPath("resources", "modern", "share", "item")));
    }

    private EquipmentResolver(Map<String, Packs> packs, Map<String, Models> models) {
        this.packs = packs;
        this.models = models;
    }

    /** Go resolve：已含反斜杠或空 → 原样；ArtPack → 模型；男路径优先级 male/female。 */
    public String resolve(String config, int sex) {
        if (config == null || config.isEmpty() || config.contains("\\")) {
            return config == null ? "" : config;
        }
        Packs itemPacks = packs.get(config);
        String art = itemPacks == null ? null : itemPacks.cloth();
        Models item = models.get(art);
        if (item == null) {
            return config;
        }
        if (sex == 1 && !item.female().isEmpty()) {
            return item.female();
        }
        return item.male().isEmpty() ? config : item.male();
    }

    /** 套装帽部件模型（HatArtPack；无该件/-1/未知配置返回空串）。 */
    public String resolveHat(String config, int sex) {
        return resolvePack(config, sex, Packs::hat);
    }

    /** 套装裤部件模型（PlantsArtPack；无该件/-1/未知配置返回空串）。 */
    public String resolvePants(String config, int sex) {
        return resolvePack(config, sex, Packs::pants);
    }

    /** 套装鞋部件模型（ShoesArtPack；无该件/-1/未知配置返回空串）。 */
    public String resolveShoes(String config, int sex) {
        return resolvePack(config, sex, Packs::shoes);
    }

    private String resolvePack(String config, int sex, java.util.function.Function<Packs, String> pick) {
        if (config == null || config.isEmpty() || config.contains("\\")) {
            return "";
        }
        Packs itemPacks = packs.get(config);
        String art = itemPacks == null ? "" : pick.apply(itemPacks);
        if (art.isEmpty() || art.equals("-1")) {
            return "";
        }
        Models item = models.get(art);
        if (item == null) {
            return "";
        }
        if (sex == 1 && !item.female().isEmpty()) {
            return item.female();
        }
        return item.male();
    }
}
