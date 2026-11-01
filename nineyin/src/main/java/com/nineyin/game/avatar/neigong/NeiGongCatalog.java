package com.nineyin.game.avatar.neigong;

import com.nineyin.game.ServerPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 现代内功效果目录，逐行对齐 Go cmd/protocol-probe/neigong_effects.go：
 * skill/neigong 的 static/varprop + buff_new.ini + modifypack 两表 →
 * StaticData+level 的 Buff/属性加成/SkillModify。
 */
public final class NeiGongCatalog {

    public record Modifier(String target, String name, double value, int mode) {
    }

    private static final class VarProp {
        int level;
        String buffId = "";
        int buffLevel;
        final Map<String, Integer> direct = new HashMap<>();
        final List<Integer> propPacks = new ArrayList<>();
        final List<Integer> skillPacks = new ArrayList<>();
    }

    private record Range(int min, int max) {
    }

    public record Effect(int staticData, String buffId, int buffLevel,
                         Map<String, Integer> stats, Map<String, List<Modifier>> skillModifiers) {
    }

    private final Map<Integer, Range> ranges = new HashMap<>();
    private final Map<Integer, VarProp> varProps = new HashMap<>();
    private final Map<String, Integer> buffStatic = new HashMap<>();
    private final Map<Integer, List<Modifier>> propPacks = new HashMap<>();
    private final Map<Integer, List<Modifier>> skillPacks = new HashMap<>();

    private static volatile NeiGongCatalog cached;

    /** Go loadModernNeiGongCatalog 的 sync.Once 惰性单例。 */
    public static NeiGongCatalog load() {
        NeiGongCatalog catalog = cached;
        if (catalog != null) {
            return catalog;
        }
        synchronized (NeiGongCatalog.class) {
            if (cached == null) {
                Path shareRoot = ServerPaths.projectPath("resources", "modern", "share");
                NeiGongCatalog fresh = new NeiGongCatalog();
                fresh.loadAll(shareRoot);
                cached = fresh;
            }
            return cached;
        }
    }

    /** 测试注入。 */
    public static void override(NeiGongCatalog catalog) {
        synchronized (NeiGongCatalog.class) {
            cached = catalog;
        }
    }

    private void loadAll(Path shareRoot) {
        parseIni(shareRoot.resolve(Path.of("skill", "neigong", "neigong_static.ini")), (section, key, value) -> {
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            Range range = ranges.getOrDefault(id, new Range(0, 0));
            switch (key) {
                case "MinVarPropNo" -> ranges.put(id, new Range(parseIntOrDefault(value), range.max()));
                case "MaxVarPropNo" -> ranges.put(id, new Range(range.min(), parseIntOrDefault(value)));
                default -> {
                }
            }
        });
        parseIni(shareRoot.resolve(Path.of("skill", "neigong", "neigong_varprop.ini")), (section, key, value) -> {
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            VarProp record = varProps.computeIfAbsent(id, ignored -> new VarProp());
            switch (key) {
                case "Level" -> record.level = parseIntOrDefault(value);
                case "BufferID" -> record.buffId = value.trim();
                case "BufferLevel" -> record.buffLevel = parseIntOrDefault(value);
                case "@PropModifyPackRec" -> record.propPacks.add(parseIntOrDefault(value));
                case "@SkillModifyPackRec" -> record.skillPacks.add(parseIntOrDefault(value));
                case "StrAdd", "StaAdd", "DexAdd", "IngAdd", "SpiAdd" -> record.direct.put(key, parseIntOrDefault(value));
                default -> {
                }
            }
        });
        parseIni(shareRoot.resolve(Path.of("skill", "buff_new.ini")), (section, key, value) -> {
            if (!key.equals("StaticData")) {
                return;
            }
            try {
                buffStatic.put(section, Integer.parseUnsignedInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // Go 会报错；此处资源已知规整，跳过等价于失败装载。
            }
        });
        loadModifierPacks(shareRoot.resolve(Path.of("modifypack", "proppack.ini")), propPacks);
        loadModifierPacks(shareRoot.resolve(Path.of("modifypack", "skillpack.ini")), skillPacks);
    }

    /** Go effect：按 StaticData 区间找 level 匹配 varprop，聚合直接加成 + PropPack。 */
    public Effect effect(int staticData, int level) {
        Range entry = ranges.get(staticData);
        if (entry == null || entry.min() <= 0 || entry.max() < entry.min()) {
            throw new IllegalStateException("missing modern neigong StaticData=" + staticData + " range");
        }
        VarProp record = null;
        for (int id = entry.min(); id <= entry.max(); id++) {
            VarProp candidate = varProps.get(id);
            if (candidate != null && candidate.level == level) {
                record = candidate;
                break;
            }
        }
        if (record == null) {
            throw new IllegalStateException("StaticData=" + staticData + " has no varprop at level=" + level);
        }
        Map<String, Integer> stats = new HashMap<>();
        Map<String, List<Modifier>> skillModifiers = new HashMap<>();
        for (Map.Entry<String, Integer> direct : record.direct.entrySet()) {
            stats.merge(direct.getKey(), direct.getValue(), Integer::sum);
        }
        int resolvedStatic = 0;
        if (!record.buffId.isEmpty()) {
            Integer mapped = buffStatic.get(record.buffId);
            if (mapped == null || mapped == 0) {
                throw new IllegalStateException("BufferID=" + record.buffId + " has no StaticData in buff_new.ini");
            }
            resolvedStatic = mapped;
        }
        for (int id : record.propPacks) {
            for (Modifier modifier : propPacks.getOrDefault(id, List.of())) {
                if (modifier.target().isEmpty() && modifier.mode() == 0) {
                    stats.merge(modifier.name(), (int) modifier.value(), Integer::sum);
                }
            }
        }
        for (int id : record.skillPacks) {
            for (Modifier modifier : skillPacks.getOrDefault(id, List.of())) {
                if (modifier.target().isEmpty()) {
                    continue;
                }
                skillModifiers.computeIfAbsent(modifier.target(), ignored -> new ArrayList<>()).add(modifier);
            }
        }
        return new Effect(resolvedStatic, record.buffId, record.buffLevel, stats, skillModifiers);
    }

    private static void loadModifierPacks(Path path, Map<Integer, List<Modifier>> destination) {
        parseIni(path, (section, key, value) -> {
            if (!key.equals("r")) {
                return;
            }
            Integer id = parseInt(section);
            if (id == null) {
                return;
            }
            Modifier modifier = parseModifier(value);
            if (modifier != null) {
                destination.computeIfAbsent(id, ignored -> new ArrayList<>()).add(modifier);
            }
        });
    }

    /** Go parseModernModifier：prop 包 3 元组 / skill 包 4 元组，第二元可否解析为数字区分。 */
    private static Modifier parseModifier(String value) {
        String[] parts = value.split(",", -1);
        int head = 0;
        while (head < parts.length && parts[head].isBlank()) {
            head++;
        }
        String[] tokens = new String[parts.length - head];
        for (int i = head; i < parts.length; i++) {
            tokens[i - head] = parts[i].trim();
        }
        if (tokens.length < 3) {
            return null;
        }
        Double second = tryParseDouble(tokens[1]);
        if (second != null) {
            return new Modifier("", tokens[0], second, (int) parseLongOrZero(tokens[2]));
        }
        if (tokens.length < 4) {
            return null;
        }
        Double third = tryParseDouble(tokens[2]);
        if (third == null) {
            return null;
        }
        return new Modifier(tokens[0], tokens[1], third, (int) parseLongOrZero(tokens[3]));
    }

    private static Double tryParseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLongOrZero(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    interface IniConsumer {
        void accept(String section, String key, String value);
    }

    static void parseIni(Path path, IniConsumer consume) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        // 资源为 GBK/UTF-8 混排：Latin-1 无损逐字节（键值均为 ASCII）。
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.ISO_8859_1))) {
            String section = "";
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0 || section.isEmpty()) {
                    continue;
                }
                consume.accept(section, line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            // Go 对缺文件返回 err；此处目录级缺省资源视作空表（best-effort 同 refreshAllModernEffects）。
        }
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOrDefault(String text) {
        Integer parsed = parseInt(text);
        return parsed == null ? 0 : parsed;
    }
}
