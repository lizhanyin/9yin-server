package com.nineyin.server.game.avatar.panel;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 装备特效目录（bak 四跳链路，武器走 EffectPackID 无词缀时的主要属性来源）：
 * rule/equip_special_effect_pack.ini [pack].EquipAddBuff=buffId,level[;…]
 *   → skill/buff_new.ini [buffId].StaticData
 *   → skill/buff_static.ini [行].Min/MaxVarPropNo（行为标志段）
 *   → skill/buff_varprop.ini [区间行]：@PropModifyPackRec=proppack 节号（属性效果）、
 *     Immunity=免疫位。
 * 属性类效果经 proppack 复用词缀累积器（{@link PanelState}）；
 * EventProcess（脚本）与 @SkillModifyPackRec 留待脚本/技能修改系统接入。
 */
public final class EquipEffectCatalog {

    /** 一个生效的装备 buff（level 取 EquipAddBuff 列；varprop 区间来自 buff_static）。 */
    public record EquipBuff(String buffId, int level, int staticData, int minVarProp, int maxVarProp) {
    }

    private final Map<Integer, List<EquipBuff>> packs = new HashMap<>();
    /** buffId → Level → varprop 行（等级内取行，用于属性/免疫解析）。 */
    private final Map<String, TreeMap<Integer, Map<String, String>>> varProps = new HashMap<>();

    /** 目录级缓存（serverRoot 变化自动换表），同 EquipAffixCatalog.load 的方言。 */
    public static EquipEffectCatalog load() {
        return ResourceCache.of("equip-effect:" + ServerPaths.projectPath("resources", "modern", "share"), () -> {
            EquipEffectCatalog catalog = new EquipEffectCatalog();
            catalog.loadAll(ServerPaths.projectPath("resources", "modern", "share"));
            return catalog;
        });
    }

    /** 整目录解析（测试注入临时 share 根用）。 */
    static EquipEffectCatalog loadShare(Path shareRoot) {
        EquipEffectCatalog catalog = new EquipEffectCatalog();
        catalog.loadAll(shareRoot);
        return catalog;
    }

    private void loadAll(Path shareRoot) {
        // 跳1：buff_new.ini buffId → StaticData
        Map<String, Integer> staticDataOf = new HashMap<>();
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "buff_new.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (key.equals("StaticData") && !section.isEmpty()) {
                        staticDataOf.putIfAbsent(section, parseIntOrDefault(value));
                    }
                });
        // 跳2：buff_static.ini StaticData → varprop 行区间
        Map<Integer, int[]> ranges = new HashMap<>();
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "buff_static.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    Integer id = parseInt(section);
                    if (id == null) {
                        return;
                    }
                    int[] range = ranges.computeIfAbsent(id, ignored -> new int[]{-1, -1});
                    switch (key) {
                        case "MinVarPropNo" -> range[0] = parseIntOrDefault(value);
                        case "MaxVarPropNo" -> range[1] = parseIntOrDefault(value);
                        default -> {
                        }
                    }
                });
        // 跳3：buff_varprop.ini 行号 → 整行键值（Level/@PropModifyPackRec/Immunity）
        Map<Integer, Map<String, String>> rowsByNo = new HashMap<>();
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "buff_varprop.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    Integer id = parseInt(section);
                    if (id != null) {
                        rowsByNo.computeIfAbsent(id, ignored -> new HashMap<>()).put(key, value);
                    }
                });
        // 跳4：equip_special_effect_pack.ini 包号 → 生效 buff 列表
        ResourceCache.ini(shareRoot.resolve(Path.of("rule", "equip_special_effect_pack.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (!key.equals("EquipAddBuff")) {
                        return;
                    }
                    Integer packId = parseInt(section);
                    if (packId == null) {
                        return;
                    }
                    List<EquipBuff> list = new ArrayList<>(2);
                    for (String part : value.split(";")) {
                        String[] cols = part.split(",");
                        if (cols.length < 2 || cols[0].isBlank()) {
                            continue;
                        }
                        String buffId = cols[0].strip();
                        int level = Math.max(1, parseIntOrDefault(cols[1]));
                        int staticData = staticDataOf.getOrDefault(buffId, 0);
                        int[] range = ranges.getOrDefault(staticData, new int[]{-1, -1});
                        list.add(new EquipBuff(buffId, level, staticData, range[0], range[1]));
                        if (range[0] >= 0) {
                            TreeMap<Integer, Map<String, String>> byLevel =
                                    varProps.computeIfAbsent(buffId, ignored -> new TreeMap<>());
                            for (int n = range[0]; n <= range[1]; n++) {
                                Map<String, String> row = rowsByNo.get(n);
                                if (row != null) {
                                    byLevel.putIfAbsent(Math.max(1, parseIntOrDefault(
                                            row.getOrDefault("Level", "1"))), row);
                                }
                            }
                        }
                    }
                    if (!list.isEmpty()) {
                        packs.putIfAbsent(packId, List.copyOf(list));
                    }
                });
    }

    /** 特效包的生效 buff 列表；未知包返回空表。 */
    public List<EquipBuff> buffsOfPack(int packId) {
        return packs.getOrDefault(packId, List.of());
    }

    public int size() {
        return packs.size();
    }

    /** varprop 行引用的 proppack 节号（@PropModifyPackRec，可 ;/, 多值；空=无属性效果）。 */
    public List<Integer> propPacksOf(String buffId, int level) {
        return intValues(rowAt(buffId, level), "@PropModifyPackRec");
    }

    /** 免疫位集合（varprop 行 Immunity 值，可能多值）。 */
    public Set<Integer> immunitiesOf(String buffId, int level) {
        return Set.copyOf(intValues(rowAt(buffId, level), "Immunity"));
    }

    /** 等级对应 varprop 行：精确级优先，缺省取 ≤level 的最大级，再缺省取最大级。 */
    private Map<String, String> rowAt(String buffId, int level) {
        TreeMap<Integer, Map<String, String>> byLevel = varProps.get(buffId);
        if (byLevel == null || byLevel.isEmpty()) {
            return null;
        }
        Map<String, String> exact = byLevel.get(level);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Integer, Map<String, String>> floor = byLevel.floorEntry(level);
        return floor != null ? floor.getValue() : byLevel.lastEntry().getValue();
    }

    private static List<Integer> intValues(Map<String, String> row, String key) {
        if (row == null) {
            return List.of();
        }
        String raw = row.get(key);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(2);
        for (String part : raw.split("[;,]")) {
            int value = parseIntOrDefault(part);
            if (value > 0) {
                out.add(value);
            }
        }
        return out;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOrDefault(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private EquipEffectCatalog() {
    }
}
