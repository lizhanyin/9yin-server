package com.nineyin.server.game.avatar.equipment;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 洗练池目录（share/item/senior_*.ini 四池，与 gm-web /api/recast-affixes 同源）：
 * 武器走 senior_weapon_{dire,rand}、防具/首饰走 senior_prop_{dire,rand}。
 *
 * <p>dire 行 = 同属性族的数值档序列（低位→高位）：weapon {@code r=PropName,id,id,…}、
 * prop {@code r=<内功系>,PropName,id,id,…}（tj/yg/yr 等，重掷不跨系）；rand 行 =
 * 平铺随机池：weapon {@code r=id,id,…}、prop {@code r=<内功系>,id,id,…}——重复即官方权重，
 * 随机取必须按列表均匀抽（等价按重复数加权）。节名 = 装备模板 ConfigID；没有精确节时
 * 按家族前缀（去尾部 {@code _数字} 段）回退，仍没有才兜底整池。
 *
 * <p>池内 id 都是 proppack.ini 节号（{@link EquipAffixCatalog} 同源）——
 * weapon 池 id 在面板口径里即「招式词条（SkillPack）」，prop 池即「普通词条（PropBuilt）」
 * （console 洗练板块两组列表与此对应）。
 */
public final class RecastCatalog {

    /** 词条池归属（面板两组列表口径；未知 = 老随机词条来自全 proppack 池）。 */
    public enum PoolKind { WEAPON, PROP, UNKNOWN }

    /** 节 → 随机池（保留重复 = 权重）。 */
    private final Map<String, List<Integer>> weaponRand = new HashMap<>();
    private final Map<String, List<Integer>> propRand = new HashMap<>();
    /** 包号 → 同属性族档序列（升序，末位 = 最高档/抛光）。 */
    private final Map<Integer, int[]> weaponDire = new HashMap<>();
    private final Map<Integer, int[]> propDire = new HashMap<>();
    private final List<Integer> weaponRandAll = new ArrayList<>();
    private final List<Integer> propRandAll = new ArrayList<>();
    private final Set<Integer> weaponPacks = new HashSet<>();
    private final Set<Integer> propPacks = new HashSet<>();

    /** 目录级缓存（serverRoot 变化自动换表，EquipAffixCatalog 同方言）。 */
    public static RecastCatalog load() {
        return ResourceCache.of("recast-pools:" + ServerPaths.projectPath("resources", "modern", "share"),
                () -> {
                    RecastCatalog catalog = new RecastCatalog();
                    Path itemRoot = ServerPaths.projectPath("resources", "modern", "share", "item");
                    catalog.loadFile(itemRoot.resolve("senior_weapon_dire.ini"), true, true);
                    catalog.loadFile(itemRoot.resolve("senior_weapon_rand.ini"), true, false);
                    catalog.loadFile(itemRoot.resolve("senior_prop_dire.ini"), false, true);
                    catalog.loadFile(itemRoot.resolve("senior_prop_rand.ini"), false, false);
                    return catalog;
                });
    }

    /** 显式路径装载（测试注入临时 ini 用）。 */
    static RecastCatalog load(Path weaponDire, Path weaponRand, Path propDire, Path propRand) {
        RecastCatalog catalog = new RecastCatalog();
        catalog.loadFile(weaponDire, true, true);
        catalog.loadFile(weaponRand, true, false);
        catalog.loadFile(propDire, false, true);
        catalog.loadFile(propRand, false, false);
        return catalog;
    }

    /**
     * 单文件解析：行内正整数按序收集——dire 行首列 PropName/内功系非数字自然跳过；
     * dire 把每个 id 映射到所在行（putIfAbsent，同 id 多节取首见，内容相同）。
     */
    private void loadFile(Path ini, boolean weapon, boolean dire) {
        ResourceCache.ini(ini, StandardCharsets.UTF_8, (section, key, value) -> {
            if (!key.equals("r")) {
                return;
            }
            List<Integer> ids = new ArrayList<>(8);
            for (String part : value.split(",")) {
                Integer id = parseInt(part);
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            int[] row = null;
            if (dire) {
                row = new int[ids.size()];
                for (int i = 0; i < ids.size(); i++) {
                    row[i] = ids.get(i);
                }
            }
            List<Integer> sectionPool = null;
            if (!dire) {
                sectionPool = (weapon ? weaponRand : propRand)
                        .computeIfAbsent(section, ignored -> new ArrayList<>());
            }
            for (Integer id : ids) {
                if (weapon) {
                    weaponPacks.add(id);
                    if (dire) {
                        weaponDire.putIfAbsent(id, row);
                    } else {
                        weaponRandAll.add(id);
                        sectionPool.add(id);
                    }
                } else {
                    propPacks.add(id);
                    if (dire) {
                        propDire.putIfAbsent(id, row);
                    } else {
                        propRandAll.add(id);
                        sectionPool.add(id);
                    }
                }
            }
        });
    }

    /**
     * 随机洗练池：精确节 → 家族前缀节并集 → 整池兜底（weaponItem 决定兜底走哪个文件；
     * 装备 ConfigID 只会在自己类型文件里有节，两段匹配实际互斥）。
     */
    public List<Integer> randPool(String configId, boolean weaponItem) {
        List<Integer> pool = sectionPool(weaponRand, configId);
        if (pool == null) {
            pool = sectionPool(propRand, configId);
        }
        if (pool == null || pool.isEmpty()) {
            List<Integer> all = weaponItem ? weaponRandAll : propRandAll;
            return all.isEmpty() ? List.of() : all;
        }
        return pool;
    }

    /** 招式开光池：恒走 weapon 文件（精确 → 前缀 → 整池）。 */
    public List<Integer> weaponPool(String configId) {
        List<Integer> pool = sectionPool(weaponRand, configId);
        if (pool == null || pool.isEmpty()) {
            return weaponRandAll.isEmpty() ? List.of() : weaponRandAll;
        }
        return pool;
    }

    private static List<Integer> sectionPool(Map<String, List<Integer>> pools, String configId) {
        if (configId == null || configId.isEmpty()) {
            return null;
        }
        List<Integer> exact = pools.get(configId);
        if (exact != null) {
            return exact;
        }
        String prefix = familyPrefix(configId);
        if (prefix.length() == configId.length()) {
            return null; // 尾段不是数字，无前缀可回退
        }
        List<Map.Entry<String, List<Integer>>> matches = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : pools.entrySet()) {
            if (e.getKey().startsWith(prefix + "_") && !e.getValue().isEmpty()) {
                matches.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(java.util.Map.Entry.comparingByKey()); // 确定性合并序
        List<Integer> merged = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : matches) {
            merged.addAll(e.getValue());
        }
        return merged;
    }

    /** 家族前缀：去尾部 {@code _数字} 段（cloth_liangu_dragon_kf_60701 → cloth_liangu_dragon_kf）。 */
    static String familyPrefix(String configId) {
        int us = configId.lastIndexOf('_');
        if (us <= 0 || us == configId.length() - 1) {
            return configId;
        }
        for (int i = us + 1; i < configId.length(); i++) {
            char c = configId.charAt(i);
            if (c < '0' || c > '9') {
                return configId;
            }
        }
        return configId.substring(0, us);
    }

    /** 词条池归属（招式开光只重掷 WEAPON 词条）。 */
    public PoolKind classify(int packId) {
        if (weaponPacks.contains(packId)) {
            return PoolKind.WEAPON;
        }
        return propPacks.contains(packId) ? PoolKind.PROP : PoolKind.UNKNOWN;
    }

    /** 定向/抛光的同属性族档序列（weapon 优先；未收录返回 null = 保留原词条）。 */
    public int[] direRowOf(int packId) {
        int[] row = weaponDire.get(packId);
        return row != null ? row : propDire.get(packId);
    }

    public boolean isEmpty() {
        return weaponPacks.isEmpty() && propPacks.isEmpty();
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RecastCatalog() {
    }
}
