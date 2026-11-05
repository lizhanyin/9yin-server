package com.nineyin.server.game.avatar.origin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

/**
 * 身份（origin）目录与奖励表（GM origin_activate_all / C2S 700·702·706 数据源）：
 * share/task/origin/ 四张官方 INI（全 ASCII 字段，ISO-8859-1 读）。
 * <ul>
 *   <li>origin.ini —— 1197 个身份（数字节名 = 身份 id；ActiveConditions/GetConditions/
 *       Level/Line/Material/Type1..4 为身份成长语义，本期 GM 直挂/领奖不消费）。</li>
 *   <li>prize_item.ini —— 身份奖励物品：[身份 id] r=configId,数量（r 重复键，
 *       单身份最多 4 条）。</li>
 *   <li>prize_title.ini —— 身份奖励称号：[身份 id] r=称号 id（title_id 与身份链
 *       同空间，授予 role_titles 所有权）。</li>
 *   <li>prize_prop.ini —— 身份奖励属性：[身份 id] r=属性,值（本期只消费
 *       repute_* 阵营 = 声望点，如 repute_jianghu,10）。</li>
 * </ul>
 * 生产表经 {@link ResourceCache#of} 按数据根缓存（WorldBossRefreshTable 同款）；
 * 文件缺失 → 空表 + present=false（调用方 fail 提示，不抛异常）。
 */
public final class OriginCatalog {

    /** 身份奖励物品（prize_item 一条 r）。 */
    public record ItemPrize(String configId, int count) {
    }

    /** 身份奖励属性（prize_prop 一条 r；本期只消费 repute_* 阵营）。 */
    public record PropPrize(String prop, long value) {
    }

    private final boolean present;
    private final List<Integer> originIds;
    private final Map<Integer, List<ItemPrize>> itemPrizes;
    private final Map<Integer, List<Integer>> titlePrizes;
    private final Map<Integer, List<PropPrize>> propPrizes;

    /** 生产加载：share/task/origin 根，按路径缓存。 */
    public static OriginCatalog load() {
        Path root = ServerPaths.projectPath("resources", "modern", "share", "task", "origin");
        return ResourceCache.of("origin-catalog:" + root.toAbsolutePath().normalize(),
                () -> build(root));
    }

    /** 测试注入：指定 share/task/origin 根（直读不缓存，临时目录可重复构建）。 */
    static OriginCatalog load(Path root) {
        return build(root);
    }

    private static OriginCatalog build(Path root) {
        boolean present = Files.isRegularFile(root.resolve("origin.ini"));
        List<Integer> originIds = new ArrayList<>();
        Map<Integer, List<ItemPrize>> itemPrizes = new HashMap<>();
        Map<Integer, List<Integer>> titlePrizes = new HashMap<>();
        Map<Integer, List<PropPrize>> propPrizes = new HashMap<>();
        if (present) {
            ResourceCache.ini(root.resolve("origin.ini"), StandardCharsets.ISO_8859_1,
                    (section, key, value) -> {
                        Integer id = numericSection(section);
                        if (id != null && !originIds.contains(id)) {
                            originIds.add(id);
                        }
                    });
            originIds.sort(Integer::compareTo);
            // r 为重复键：每条 r 触发一次回调（jhscenebossrefreshpoint Point= 同款）。
            ResourceCache.ini(root.resolve("prize_item.ini"), StandardCharsets.ISO_8859_1,
                    (section, key, value) -> {
                        Integer id = numericSection(section);
                        String[] parts = value == null ? new String[0] : value.split(",");
                        if (id == null || !"r".equalsIgnoreCase(key) || parts.length < 1) {
                            return;
                        }
                        Integer count = parts.length > 1
                                ? parseIntOrNull(parts[1].trim()) : Integer.valueOf(1);
                        if (parts[0].trim().isEmpty() || count == null || count <= 0) {
                            return; // 坏行丢弃（数量非正数/非数字）
                        }
                        itemPrizes.computeIfAbsent(id, unused -> new ArrayList<>())
                                .add(new ItemPrize(parts[0].trim(), count));
                    });
            ResourceCache.ini(root.resolve("prize_title.ini"), StandardCharsets.ISO_8859_1,
                    (section, key, value) -> {
                        Integer id = numericSection(section);
                        Integer title = value == null ? null : parseIntOrNull(value.trim());
                        if (id == null || !"r".equalsIgnoreCase(key) || title == null) {
                            return;
                        }
                        titlePrizes.computeIfAbsent(id, unused -> new ArrayList<>()).add(title);
                    });
            ResourceCache.ini(root.resolve("prize_prop.ini"), StandardCharsets.ISO_8859_1,
                    (section, key, value) -> {
                        Integer id = numericSection(section);
                        String[] parts = value == null ? new String[0] : value.split(",");
                        if (id == null || !"r".equalsIgnoreCase(key) || parts.length < 2) {
                            return;
                        }
                        Long prizeValue = parseLongOrNull(parts[1].trim());
                        if (parts[0].trim().isEmpty() || prizeValue == null) {
                            return;
                        }
                        propPrizes.computeIfAbsent(id, unused -> new ArrayList<>())
                                .add(new PropPrize(parts[0].trim(), prizeValue));
                    });
        }
        return new OriginCatalog(present, originIds, itemPrizes, titlePrizes, propPrizes);
    }

    private OriginCatalog(boolean present, List<Integer> originIds,
                          Map<Integer, List<ItemPrize>> itemPrizes,
                          Map<Integer, List<Integer>> titlePrizes,
                          Map<Integer, List<PropPrize>> propPrizes) {
        this.present = present;
        this.originIds = originIds;
        this.itemPrizes = itemPrizes;
        this.titlePrizes = titlePrizes;
        this.propPrizes = propPrizes;
    }

    public boolean present() {
        return present;
    }

    /** 全部身份 id（数字升序；Origin_Active 行序 = 此序）。 */
    public List<Integer> originIds() {
        return originIds;
    }

    public boolean contains(int originId) {
        // originIds 升序，二分即可；表量级 1197 直接 contains 也够。
        return java.util.Collections.binarySearch(originIds, originId) >= 0;
    }

    public List<ItemPrize> itemPrizes(int originId) {
        return itemPrizes.getOrDefault(originId, List.of());
    }

    public List<Integer> titlePrizes(int originId) {
        return titlePrizes.getOrDefault(originId, List.of());
    }

    public List<PropPrize> propPrizes(int originId) {
        return propPrizes.getOrDefault(originId, List.of());
    }

    private static Integer numericSection(String section) {
        return section == null ? null : parseIntOrNull(section.trim());
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
