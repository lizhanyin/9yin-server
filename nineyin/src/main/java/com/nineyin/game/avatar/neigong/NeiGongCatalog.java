package com.nineyin.game.avatar.neigong;

import com.nineyin.game.ServerPaths;
import com.nineyin.cache.ResourceCache;

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

    /** Go loadModernNeiGongCatalog 的 sync.Once 等价：目录级缓存（serverRoot 变化自动换表）。 */
    public static NeiGongCatalog load() {
        return ResourceCache.of("neigong-catalog:" + ServerPaths.projectPath("resources", "modern", "share"), () -> {
            NeiGongCatalog catalog = new NeiGongCatalog();
            catalog.loadAll(ServerPaths.projectPath("resources", "modern", "share"));
            return catalog;
        });
    }

    private void loadAll(Path shareRoot) {
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "neigong", "neigong_static.ini")), java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
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
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "neigong", "neigong_varprop.ini")), java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
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
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "buff_new.ini")), java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
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
        ResourceCache.ini(path, java.nio.charset.StandardCharsets.ISO_8859_1, (section, key, value) -> {
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

    // INI 逐行解析走 ResourceCache.ini（Latin-1 无损：GBK/UTF-8 混排资源的键值均为 ASCII），
    // 表按文件路径缓存，重复 load 不再重读。

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
