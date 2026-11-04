package com.nineyin.server.game.avatar.panel;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 装备词缀属性包目录（share/modifypack/proppack.ini，~3.7 万节，bak 真机验证的装备加成链）。
 * 每节 = 一个词缀包；装备的 propBuilt 串（"1,id;1,id"）引用节号；
 * `r=,PropName,Value,Flag` 行给出属性修正（可多行，Flag 语义未钉死，累积时忽略）。
 * 值按 double 解析后截断（实测 AirRushDistAdd 0.4 等小数行，脏行跳过会整包丢失）。
 */
public final class EquipAffixCatalog {

    /** 一行属性修正（PropName 如 StrAdd/PhyDefAdd/MaxHPAdd…，见 {@link PanelState} 映射）。 */
    public record Mod(String prop, int value, int flag) {
    }

    private final Map<Integer, List<Mod>> packs = new HashMap<>();

    /** 目录级缓存（serverRoot 变化自动换表），同 NeiGongCatalog.load 的方言。 */
    public static EquipAffixCatalog load() {
        return ResourceCache.of("equip-affix:" + ServerPaths.projectPath("resources", "modern", "share"), () -> {
            EquipAffixCatalog catalog = new EquipAffixCatalog();
            catalog.loadAll(ServerPaths.projectPath("resources", "modern", "share"));
            return catalog;
        });
    }

    private void loadAll(Path shareRoot) {
        // loadIni 是静态工厂（解析结果在返回值），必须并入本实例——
        // 此前返回值被丢弃导致 load() 恒为空表（recast_add_affix 校验/词缀累积全链失效）。
        packs.putAll(loadIni(shareRoot.resolve(Path.of("modifypack", "proppack.ini"))).packs);
    }

    /** 单文件解析（测试注入临时 ini 用；ResourceCache 按路径缓存文本）。 */
    static EquipAffixCatalog loadIni(Path ini) {
        EquipAffixCatalog catalog = new EquipAffixCatalog();
        ResourceCache.ini(ini, StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (!key.equals("r")) {
                        return;
                    }
                    Integer id = parseInt(section);
                    if (id == null) {
                        return;
                    }
                    // r=,PropName,Value,Flag（首列固定空；多 r= 行并存）
                    String[] cols = value.split(",", -1);
                    int head = 0;
                    while (head < cols.length && cols[head].isBlank()) {
                        head++;
                    }
                    if (cols.length - head < 3 || cols[head].isBlank()) {
                        return;
                    }
                    try {
                        Mod mod = new Mod(cols[head].trim(),
                                (int) Double.parseDouble(cols[head + 1].trim()),
                                (int) Double.parseDouble(cols[head + 2].trim()));
                        catalog.packs.computeIfAbsent(id, ignored -> new ArrayList<>(2)).add(mod);
                    } catch (NumberFormatException ignored) {
                        // 脏行跳过
                    }
                });
        return catalog;
    }

    /** 词缀包的属性行；未知包号返回空表。 */
    public List<Mod> modsOf(int packId) {
        List<Mod> mods = packs.get(packId);
        return mods != null ? mods : List.of();
    }

    /** 全部词缀包号（GM 生成武器/防具随机词条用）。 */
    public List<Integer> packIds() {
        return new ArrayList<>(packs.keySet());
    }

    public int size() {
        return packs.size();
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private EquipAffixCatalog() {
    }
}
