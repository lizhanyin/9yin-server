package com.nineyin.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 装备特效目录（§3.17 四跳链路的装载层）：
 * rule/equip_special_effect_pack.ini [pack].EquipAddBuff=buffId,level[;…]
 *   → skill/buff_new.ini [buffId].StaticData
 *   → skill/buff_static.ini [行].Min/MaxVarPropNo（行为标志段）
 *   → skill/buff_varprop.ini [区间行]：@PropModifyPackRec=proppack 节号（属性效果）、
 *     Immunity=免疫位（33+ 按等级升位）。
 * 参考实测：563 包 / 258 个 eqp_* buff；属性类效果经 proppack 复用词缀累积器，
 * EventProcess（脚本）与 @SkillModifyPackRec 留待脚本/技能修改系统接入。
 */
public final class EquipEffectCatalog {

    private static final Logger log = LoggerFactory.getLogger(EquipEffectCatalog.class);

    /** 一个生效的装备 buff（level 取 EquipAddBuff 列；varprop 区间来自 buff_static）。 */
    public record EquipBuff(String buffId, int level, int staticData, int minVarProp, int maxVarProp) {
    }

    private final Map<Integer, List<EquipBuff>> packs = new HashMap<>();
    /** buffId → Level → varprop 行（等级内取行，用于属性/免疫解析）。 */
    private final Map<String, TreeMap<Integer, Map<String, String>>> varProps = new HashMap<>();

    public void load(Path shareRoot) {
        Map<String, Integer> staticDataOf = new HashMap<>();
        for (Map<String, String> sec : AiRuleData.sections(shareRoot.resolve("skill").resolve("buff_new.ini"))) {
            String id = sec.get("");
            if (id != null && !id.isEmpty()) {
                staticDataOf.put(id, parseInt(sec.get("StaticData"), 0));
            }
        }
        Map<Integer, int[]> ranges = new HashMap<>();
        for (Map<String, String> sec : AiRuleData.sections(shareRoot.resolve("skill").resolve("buff_static.ini"))) {
            String id = sec.get("");
            if (!isNumeric(id)) {
                continue;
            }
            int mn = parseInt(sec.get("MinVarPropNo"), -1);
            int mx = parseInt(sec.get("MaxVarPropNo"), -1);
            if (mn >= 0 && mx >= mn) {
                ranges.put(Integer.parseInt(id), new int[]{mn, mx});
            }
        }
        Map<Integer, Map<String, String>> rowsByNo = new HashMap<>();
        for (Map<String, String> sec : AiRuleData.sections(shareRoot.resolve("skill").resolve("buff_varprop.ini"))) {
            String id = sec.get("");
            if (isNumeric(id)) {
                rowsByNo.put(Integer.parseInt(id), sec);
            }
        }
        for (Map<String, String> sec : AiRuleData.sections(
                shareRoot.resolve("rule").resolve("equip_special_effect_pack.ini"))) {
            String id = sec.get("");
            if (!isNumeric(id)) {
                continue;
            }
            List<EquipBuff> list = new ArrayList<>(2);
            for (String part : sec.getOrDefault("EquipAddBuff", "").split(";")) {
                String[] cols = part.split(",");
                if (cols.length < 2 || cols[0].isBlank()) {
                    continue;
                }
                String buffId = cols[0].strip();
                int level = Math.max(1, parseInt(cols[1], 1));
                int sd = staticDataOf.getOrDefault(buffId, 0);
                int[] range = ranges.getOrDefault(sd, new int[]{-1, -1});
                list.add(new EquipBuff(buffId, level, sd, range[0], range[1]));
                if (range[0] >= 0) {
                    TreeMap<Integer, Map<String, String>> byLevel =
                            varProps.computeIfAbsent(buffId, k -> new TreeMap<>());
                    for (int n = range[0]; n <= range[1]; n++) {
                        Map<String, String> row = rowsByNo.get(n);
                        if (row != null) {
                            byLevel.put(Math.max(1, parseInt(row.get("Level"), 1)), row);
                        }
                    }
                }
            }
            if (!list.isEmpty()) {
                packs.put(Integer.parseInt(id), List.copyOf(list));
            }
        }
        log.info("equip effect catalog ready packs={} buffs={} file={}",
                packs.size(), varProps.size(), shareRoot.resolve("rule").resolve("equip_special_effect_pack.ini"));
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
            int v = parseInt(part, 0);
            if (v > 0) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isNumeric(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return (int) Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
