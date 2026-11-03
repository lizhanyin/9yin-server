package com.nineyin.server.game.avatar.skin;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 风物志皮肤目录：风物志=装备的皮肤外观，协议侧官方名是 Card（CardRec 收集
 * 位图 / UseCardRec 挂载行 / custom 180 on_card_msg），数据源为客户端资源
 * rule/card.ini（节号=皮肤 ID），与 GM 目录 game_cards 表同源
 * （config_id/name/item_type/main_type/sub_type/level/flag/male_model_data）。
 *
 * <p>ModelData 形如 {@code Weapon,blade_card_001}，成套皮肤为多段
 * {@code Cloth,obj\...\b_cloth_1_2;Pants,...;Shoes,...}——按 {@code ;} 拆部件、
 * {@code ,} 拆「部件,模型」。表经 {@link ResourceCache} 按目录缓存；GBK 混排
 * 资源必须 Latin-1 无损读（同 equipment.ini 约定）。
 */
public final class SkinCatalog {

    /** 皮肤单个部件模型（part: Weapon/Cloth/Hat/Pants/Shoes/Waist/back/Cloak/Mount/…）。 */
    public record PartModel(String part, String model) {
    }

    /** 皮肤条目：male/female 为部件模型列表（客户端按性别取列）。 */
    public record Skin(int id, String configId, List<PartModel> male, List<PartModel> female,
                       int itemType, int mainType, int subType, int level, int flag) {

        /** 指定性别的部件模型列（无该性别数据回退另一性别）。 */
        public List<PartModel> models(int sex) {
            if (sex == 1 && !female.isEmpty()) {
                return female;
            }
            return male;
        }

        /** 指定性别覆盖的部件名集合（衣橱换装同部件互斥替换用）。 */
        public java.util.Set<String> parts(int sex) {
            java.util.Set<String> parts = new java.util.HashSet<>();
            for (PartModel partModel : models(sex)) {
                parts.add(partModel.part().toLowerCase(java.util.Locale.ROOT));
            }
            return parts;
        }

        /** 纯发型皮肤：只含 Hat 部件（GM「风物志头发」过滤口径，helmet 头模）。 */
        public boolean hairOnly() {
            if (male.isEmpty() && female.isEmpty()) {
                return false;
            }
            List<PartModel> models = male.isEmpty() ? female : male;
            for (PartModel partModel : models) {
                if (!partModel.part().equalsIgnoreCase("Hat")) {
                    return false;
                }
            }
            return true;
        }
    }

    /** rule 目录（card.ini 所在）。 */
    private final Path ruleRoot;
    private final Map<Integer, Skin> skins = new TreeMap<>();

    public static SkinCatalog load(Path ruleRoot) {
        SkinCatalog catalog = new SkinCatalog(ruleRoot);
        ResourceCache.ini(ruleRoot.resolve("card.ini"), StandardCharsets.ISO_8859_1,
                (section, key, value) -> {
                    Integer id = parseSectionId(section);
                    if (id == null) {
                        return;
                    }
                    Skin current = catalog.skins.get(id);
                    String configId = current == null ? "" : current.configId();
                    List<PartModel> male = current == null ? List.of() : current.male();
                    List<PartModel> female = current == null ? List.of() : current.female();
                    int itemType = current == null ? 0 : current.itemType();
                    int mainType = current == null ? 0 : current.mainType();
                    int subType = current == null ? 0 : current.subType();
                    int level = current == null ? 0 : current.level();
                    int flag = current == null ? 0 : current.flag();
                    switch (key.toLowerCase(java.util.Locale.ROOT)) {
                        case "configid" -> configId = value;
                        case "malemodeldata" -> male = parseModels(value);
                        case "femalemodeldata" -> female = parseModels(value);
                        case "itemtype" -> itemType = parseInt(value, itemType);
                        case "maintype" -> mainType = parseInt(value, mainType);
                        case "subtype" -> subType = parseInt(value, subType);
                        case "level" -> level = parseInt(value, level);
                        case "flag" -> flag = parseInt(value, flag);
                        default -> {
                        }
                    }
                    catalog.skins.put(id, new Skin(id, configId, male, female,
                            itemType, mainType, subType, level, flag));
                });
        return catalog;
    }

    /** 常驻缓存（serverRoot 变化自动换表，同 EquipmentResolver.loadDefault）。 */
    public static SkinCatalog loadDefault() {
        Path ruleRoot = ServerPaths.projectPath("resources", "modern", "share", "rule");
        return ResourceCache.of(ruleRoot, () -> load(ruleRoot));
    }

    private SkinCatalog(Path ruleRoot) {
        this.ruleRoot = ruleRoot;
    }

    /** 目录是否可用（card.ini 缺失/未解析时为空目录，GM 动作据此报错）。 */
    public boolean isEmpty() {
        return skins.isEmpty();
    }

    public Skin byId(int id) {
        return skins.get(id);
    }

    /** 全量皮肤（ID 升序）。 */
    public List<Skin> all() {
        return new ArrayList<>(skins.values());
    }

    /** 最大皮肤 ID（收集位图行数上界）。 */
    public int maxId() {
        int max = 0;
        for (int id : skins.keySet()) {
            max = Math.max(max, id);
        }
        return max;
    }

    Path ruleRoot() {
        return ruleRoot;
    }

    /** "Weapon,blade_card_001;Cloth,xxx" → [(Weapon,blade_card_001),(Cloth,xxx)]。 */
    static List<PartModel> parseModels(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<PartModel> models = new ArrayList<>();
        for (String segment : value.split(";")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int comma = trimmed.indexOf(',');
            if (comma <= 0 || comma >= trimmed.length() - 1) {
                continue; // 非法段跳过（部件或模型缺失）
            }
            models.add(new PartModel(trimmed.substring(0, comma).trim(),
                    trimmed.substring(comma + 1).trim()));
        }
        return List.copyOf(models);
    }

    private static Integer parseSectionId(String section) {
        try {
            return Integer.valueOf(section.trim());
        } catch (NumberFormatException e) {
            return null; // 非数字节（card.ini 内目前全为数字节，防御性跳过）
        }
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
