package com.nineyin.gm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易 INI 解析：[节] + key=value，键统一小写访问（源文件 script/Script 混用），
 * 同节内重复键保留全部取值（senior_*.ini 每节几十行 r=）；支持 ; # 注释与 UTF-8 BOM。
 * 节与键均保持文件内出现顺序。
 */
public final class IniFile {

    private final Map<String, Map<String, List<String>>> sections = new LinkedHashMap<>();

    public static IniFile load(Path path, Charset charset) {
        IniFile ini = new IniFile();
        if (path == null || !Files.isRegularFile(path)) {
            return ini;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(path), charset);
        } catch (IOException e) {
            return ini;
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Map<String, List<String>> current = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = ini.sections.computeIfAbsent(
                        line.substring(1, line.length() - 1).trim(), k -> new LinkedHashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || current == null) {
                continue;
            }
            current.computeIfAbsent(line.substring(0, eq).trim().toLowerCase(), k -> new ArrayList<>())
                    .add(line.substring(eq + 1).trim());
        }
        return ini;
    }

    /** 节名 →（小写键 → 全部取值，保序）。 */
    public Map<String, Map<String, List<String>>> sections() {
        return sections;
    }

    /** 首个取值；无返回 null。 */
    public String value(String section, String key) {
        List<String> values = values(section, key);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 键的全部取值（重复键场景）。 */
    public List<String> values(String section, String key) {
        Map<String, List<String>> sec = sections.get(section);
        if (sec == null) {
            return List.of();
        }
        return sec.getOrDefault(key.toLowerCase(), List.of());
    }

    public int intValue(String section, String key, int def) {
        String v = value(section, key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private IniFile() {
    }
}
