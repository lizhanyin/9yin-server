package com.nineyin.server.game.avatar.equipment;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GM 发放物品查找目录：item/equipment.ini（装备，EquipType 优先）+
 * item/tool_item.ini（道具）合并，configId 大小写不敏感。
 *
 * <p>两表均为 GBK（gm-web GmCatalog 同口径），Name 供背包行 name 字段落库。
 * 展示静态字段（ColorLevel/Photo…）仍走 {@link com.nineyin.server.protocol.EquipmentModels}。
 */
public final class ToolItemCatalog {

    /** 发放/模板生成需要的物品字段（viewId 决定背包页；equipType 空为不可穿戴道具；cardId=风物志皮肤号，script=CardItem 专用）。 */
    public record ItemInfo(String configId, String name, int viewId, String script,
                           int itemType, int maxAmount, String equipType,
                           int colorLevel, int needSex, int artPack, int cardId) {

        /** 风物志卡片道具（使用后收录进衣橱）。 */
        public boolean isSkinCard() {
            return cardId > 0;
        }
    }

    private static final Charset GBK = Charset.forName("GBK");

    private final Map<String, ItemInfo> items = new HashMap<>();

    public static ToolItemCatalog load(Path itemRoot) {
        ToolItemCatalog catalog = new ToolItemCatalog();
        Map<String, String[]> raw = new HashMap<>();
        ResourceCache.ini(itemRoot.resolve("equipment.ini"), GBK, (section, key, value) -> {
            if (section.startsWith("//") || section.startsWith(";")) {
                return; // 注释节
            }
            String normalized = section.toLowerCase(Locale.ROOT);
            String[] fields = raw.computeIfAbsent(normalized, s -> new String[11]);
            fields[0] = section;
            merge(fields, key, value);
        });
        ResourceCache.ini(itemRoot.resolve("tool_item.ini"), GBK, (section, key, value) -> {
            if (section.startsWith("//") || section.startsWith(";")) {
                return;
            }
            String normalized = section.toLowerCase(Locale.ROOT);
            String[] fields = raw.computeIfAbsent(normalized, s -> new String[11]);
            fields[0] = section;
            merge(fields, key, value);
        });
        for (Map.Entry<String, String[]> entry : raw.entrySet()) {
            String[] f = entry.getValue();
            catalog.items.put(entry.getKey(), new ItemInfo(
                    f[0],
                    f[1] == null ? "" : f[1],
                    parseInt(f[2], 0),
                    f[3] == null ? "" : f[3],
                    parseInt(f[4], 0),
                    parseInt(f[5], 0),
                    f[6] == null ? "" : f[6],
                    parseInt(f[7], 1),
                    parseInt(f[8], 2),
                    parseInt(f[9], 0),
                    parseInt(f[10], 0)));
        }
        return catalog;
    }

    private static void merge(String[] fields, String key, String value) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "name" -> fields[1] = value;
            case "viewid" -> fields[2] = value;
            case "script" -> fields[3] = value;
            case "itemtype" -> fields[4] = value;
            case "maxamount" -> fields[5] = value;
            case "equiptype" -> fields[6] = value;
            case "colorlevel" -> fields[7] = value;
            case "needsex" -> fields[8] = value;
            case "artpack" -> fields[9] = value;
            case "cardid" -> fields[10] = value;
            default -> {
            }
        }
    }

    /** 常驻缓存（键加前缀：同 item 目录还有 EquipmentResolver，裸路径键会互相污染）。 */
    public static ToolItemCatalog loadDefault() {
        Path itemRoot = ServerPaths.projectPath("resources", "modern", "share", "item");
        return ResourceCache.of("tool-item-catalog:" + itemRoot, () -> load(itemRoot));
    }

    private ToolItemCatalog() {
    }

    /** configId（大小写不敏感）→ 物品信息；未知返回 null。 */
    public ItemInfo lookup(String configId) {
        if (configId == null || configId.isEmpty()) {
            return null;
        }
        return items.get(configId.trim().toLowerCase(Locale.ROOT));
    }

    /** 全量物品（模板生成筛选用；equipment.ini + tool_item.ini 合集）。 */
    public java.util.List<ItemInfo> all() {
        return java.util.List.copyOf(items.values());
    }

    /** 皮肤号 → 卡片道具（rule/card.ini 的 id ↔ tool_item.ini [card_item_*].CardID）。 */
    public ItemInfo cardItemBySkinId(int skinId) {
        for (ItemInfo info : items.values()) {
            if (info.cardId() == skinId) {
                return info;
            }
        }
        return null;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
