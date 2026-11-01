package com.nineyin.game.avatar.equipment;

import com.nineyin.game.ServerPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 装备 ConfigID → 模型路径解析器，对齐 Go cmd/protocol-probe/appearance.go loadEquipmentResolver：
 * item/equipment.ini 的 ArtPack + item/itemartstatic.ini 的 MaleModel/FemaleModel。
 */
public final class EquipmentResolver {

    private final Map<String, String> artByItem = new HashMap<>();
    private final Map<String, Models> models = new HashMap<>();

    public record Models(String male, String female) {
    }

    public static EquipmentResolver load(Path itemRoot) {
        EquipmentResolver resolver = new EquipmentResolver();
        parseIni(itemRoot.resolve("equipment.ini"), (section, key, value) -> {
            if (key.equals("ArtPack")) {
                resolver.artByItem.put(section, value);
            }
        });
        parseIni(itemRoot.resolve("itemartstatic.ini"), (section, key, value) -> {
            Models current = resolver.models.getOrDefault(section, new Models("", ""));
            if (key.equals("MaleModel")) {
                resolver.models.put(section, new Models(value, current.female()));
            } else if (key.equals("FemaleModel")) {
                resolver.models.put(section, new Models(current.male(), value));
            }
        });
        return resolver;
    }

    /** Go defaultModernShareRoot/item。 */
    public static EquipmentResolver loadDefault() {
        return load(ServerPaths.projectPath("resources", "modern", "share", "item"));
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

    interface IniConsumer {
        void accept(String section, String key, String value);
    }

    private static void parseIni(Path path, IniConsumer consume) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        // Go 逐字节透传：GBK/UTF-8 混排资源必须无损读（Latin-1 每字节一字符），
        // UTF-8 解码器遇 GBK 字节会中途抛 MalformedInputException 截断整表。
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.ISO_8859_1))) {
            String section = "";
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    continue;
                }
                if (section.isEmpty()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    consume.accept(section, line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            // Go parseINI 静默跳过读失败。
        }
    }
}
