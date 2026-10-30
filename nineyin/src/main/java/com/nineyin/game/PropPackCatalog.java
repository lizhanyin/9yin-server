package com.nineyin.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 词缀属性包目录（share/modifypack/proppack.ini，GBK，~3.7 万节）。
 * 参考服证据串：`proppack catalog ready packs=%d file=%s`、
 * `proppack.ini unavailable (%v); equip PropModify bonuses disabled`——
 * 每节 = 一个词缀包，装备的 propBuilt 串（"1,id;1,id"）引用节号；
 * `r=,PropName,Value,Flag` 行给出属性修正（可多行，Flag 0/1 语义未钉死，累积时忽略）。
 */
public final class PropPackCatalog {

    /** 一行属性修正（PropName 如 StrAdd/PhyDefAdd/MaxHPAdd…，见 §3.7 映射表）。 */
    public record Mod(String prop, int value, int flag) {
    }

    private static final Logger log = LoggerFactory.getLogger(PropPackCatalog.class);

    private final Map<Integer, List<Mod>> packs = new HashMap<>();

    /** 解析词缀包表；文件缺失/不可读保持空目录（装备加成链整体禁用，日志对齐参考服）。 */
    public static PropPackCatalog load(Path ini) {
        PropPackCatalog catalog = new PropPackCatalog();
        if (!Files.isRegularFile(ini)) {
            log.warn("proppack.ini unavailable ({}): not a regular file; equip PropModify bonuses disabled", ini);
            return catalog;
        }
        try {
            String data = new String(Files.readAllBytes(ini), Charset.forName("GBK"));
            String section = "";
            List<Mod> mods = null;
            int rows = 0;
            for (String raw : data.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (mods != null && !mods.isEmpty() && section.chars().allMatch(Character::isDigit)) {
                        catalog.packs.putIfAbsent(Integer.parseInt(section), mods);
                    }
                    section = line.substring(1, line.length() - 1);
                    mods = new ArrayList<>(2);
                    continue;
                }
                if (mods == null || !line.startsWith("r=")) {
                    continue;
                }
                // r=,PropName,Value,Flag（首列固定空；多 r= 行并存）
                String[] cols = line.substring(2).split(",", -1);
                if (cols.length < 4 || cols[1].isEmpty()) {
                    continue;
                }
                try {
                    mods.add(new Mod(cols[1].trim(), Integer.parseInt(cols[2].trim()),
                            Integer.parseInt(cols[3].trim())));
                    rows++;
                } catch (NumberFormatException e) {
                    // 脏行跳过
                }
            }
            if (mods != null && !mods.isEmpty() && section.chars().allMatch(Character::isDigit)) {
                catalog.packs.putIfAbsent(Integer.parseInt(section), mods);
            }
            log.info("proppack catalog ready packs={} file={}", catalog.packs.size(), ini);
            log.debug("proppack rows={} parsed", rows);
        } catch (IOException e) {
            log.warn("proppack.ini unavailable ({}): {}; equip PropModify bonuses disabled",
                    ini, e.getMessage());
        }
        return catalog;
    }

    /** 词缀包的属性行；未知包号返回空表。 */
    public List<Mod> modsOf(int packId) {
        List<Mod> mods = packs.get(packId);
        return mods != null ? mods : List.of();
    }

    public int size() {
        return packs.size();
    }
}
