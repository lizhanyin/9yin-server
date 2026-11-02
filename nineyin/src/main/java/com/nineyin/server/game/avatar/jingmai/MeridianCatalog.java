package com.nineyin.server.game.avatar.jingmai;

import com.nineyin.util.ServerPaths;
import com.nineyin.commom.cache.ResourceCache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经脉目录（share/skill/jingmai）：jingmai.ini（ConfigID→StaticData/ItemType）+
 * jingmai_static.ini（StaticData→Min/MaxVarPropNo，差值+1=等级上限）。
 *
 * <p>升级所需真气取平曲线 750/周天（F 盘 52795 抓包 View45 全 14 行 Level=1、
 * TotalFillValue=750 实证——wuxuelevelinfo.ini 的正经曲线是 20100+/级，F 端未采用）；
 * 修炼速度取 faculty/wuxuelevelinfo.ini 第 3 列（经脉恒 43/分钟，同文件内功列 172）。
 */
public final class MeridianCatalog {

    /** 单条经脉定义：StaticData（View45 属性）与等级上限（varprop 区间宽）。 */
    public record Definition(String configId, int staticData, int itemType, int maxLevel) {
    }

    /** F 盘 52795 抓包 View45 的 14 行授予顺序：丹田 + 十二正经 + 阴跷·隐/阳跷·隐。 */
    public static final List<String> STARTER_MERIDIANS = List.of(
            "jm_dantian",
            "jm_zuyangming",
            "jm_zushaoyin",
            "jm_shoutaiyin",
            "jm_shoutaiyang",
            "jm_zutaiyin",
            "jm_zushaoyang",
            "jm_zujueyin",
            "jm_shoushaoyang",
            "jm_shouyangming",
            "jm_zutaiyang",
            "jm_shoushaoyin",
            "jm_yinqiao_wei",
            "jm_yangqiao_wei");

    /** F 端平曲线：每周天所需真气（View45 TotalFillValue 实证）。 */
    public static final int TOTAL_FILL_PER_LEVEL = 750;
    /** 修炼速度（真气/分钟）：wuxuelevelinfo.ini 经脉段第 3 列恒 43。 */
    public static final int ZHENQI_PER_MINUTE = 43;
    /** 战斗真气系数（zhenqi.ini damage_ratio=0.1）：每点伤害折算真气。 */
    public static final double COMBAT_ZHENQI_RATIO = 0.1;

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Integer> speedByConfig = new HashMap<>();

    /** 目录级缓存（serverRoot 变化自动换表，同 NeiGongCatalog）。 */
    public static MeridianCatalog load() {
        return ResourceCache.of("meridian-catalog:" + ServerPaths.projectPath("resources", "modern", "share"),
                () -> loadShare(ServerPaths.projectPath("resources", "modern", "share")));
    }

    /** 整目录解析（测试注入临时 share 根用）。 */
    public static MeridianCatalog loadShare(Path shareRoot) {
        MeridianCatalog catalog = new MeridianCatalog();
        catalog.loadDefinitions(shareRoot);
        catalog.loadSpeeds(shareRoot);
        return catalog;
    }

    private void loadDefinitions(Path shareRoot) {
        Map<Integer, int[]> ranges = new HashMap<>(); // staticData → [min,max]
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "jingmai", "jingmai_static.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    Integer id = parseInt(section);
                    if (id == null) {
                        return;
                    }
                    int[] range = ranges.computeIfAbsent(id, ignored -> new int[2]);
                    switch (key) {
                        case "MinVarPropNo" -> range[0] = parseIntOrDefault(value);
                        case "MaxVarPropNo" -> range[1] = parseIntOrDefault(value);
                        default -> {
                        }
                    }
                });
        ResourceCache.ini(shareRoot.resolve(Path.of("skill", "jingmai", "jingmai.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (section.isBlank() || section.startsWith("-")) {
                        return;
                    }
                    Definition existing = definitions.get(section);
                    String configId = existing == null ? section : existing.configId();
                    int staticData = existing == null ? 0 : existing.staticData();
                    int itemType = existing == null ? 1003 : existing.itemType();
                    switch (key) {
                        case "StaticData" -> staticData = parseIntOrDefault(value);
                        case "ItemType" -> itemType = parseIntOrDefault(value);
                        default -> {
                        }
                    }
                    definitions.put(section, new Definition(configId, staticData, itemType, 0));
                });
        // 等级上限 = jingmai_static 的 varprop 区间宽（丹田 [141001,141180]→180、正经→216）。
        for (Map.Entry<String, Definition> entry : definitions.entrySet()) {
            int[] range = ranges.get(entry.getValue().staticData());
            int maxLevel = 0;
            if (range != null && range[1] >= range[0] && range[0] > 0) {
                maxLevel = range[1] - range[0] + 1;
            }
            Definition raw = entry.getValue();
            entry.setValue(new Definition(raw.configId(), raw.staticData(), raw.itemType(), maxLevel));
        }
    }

    private void loadSpeeds(Path shareRoot) {
        ResourceCache.ini(shareRoot.resolve(Path.of("faculty", "wuxuelevelinfo.ini")),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    if (!key.equals("r") || !section.startsWith("jm_")) {
                        return;
                    }
                    String[] parts = value.split(",");
                    if (parts.length < 3) {
                        return;
                    }
                    Integer speed = parseInt(parts[2].trim());
                    if (speed != null && speedByConfig.getOrDefault(section, 0) < speed) {
                        speedByConfig.put(section, speed);
                    }
                });
    }

    public Definition definition(String configId) {
        return configId == null ? null : definitions.get(configId);
    }

    /** 已收录的全部经脉（jingmai.ini 声明序）。 */
    public List<Definition> all() {
        return new ArrayList<>(definitions.values());
    }

    /** 每周天所需真气（平曲线；抓包实证与等级无关）。 */
    public int totalFillPerLevel(String configId) {
        return TOTAL_FILL_PER_LEVEL;
    }

    /** 修炼速度（真气/分钟）：wuxuelevelinfo 第 3 列，缺省 {@link #ZHENQI_PER_MINUTE}。 */
    public int zhenQiPerMinute(String configId) {
        Integer speed = configId == null ? null : speedByConfig.get(configId);
        return speed != null && speed > 0 ? speed : ZHENQI_PER_MINUTE;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseIntOrDefault(String text) {
        Integer value = parseInt(text);
        return value == null ? 0 : value;
    }

    private MeridianCatalog() {
    }
}
