package com.nineyin.server.game.avatar.mount;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 坐骑目录：道具 → 坐骑包 → 模型串三段映射，客户端引擎按玩家属性
 * {@code Mount}（非空=骑乘中）加载 {@code ini\npc\<Mount>.ini}，坐骑总览
 * UI（form_new_allmount）按 {@code NewRideRec[3,3,6,1]} 行 + {@code NewRideID}
 * 渲染。映射链（客户端资源取证）：
 *
 * <ul>
 *   <li>tool_item.ini {@code script=Mount} 段：configId → Name/MountType/
 *       RideSkillID/SpeedQuality/ValidTimeMinute/{@code ArtPack}=坐骑包 id；</li>
 *   <li>itemartstatic.ini {@code [ArtPack]} 段：MaleModel/FemaleModel =
 *       {@code npc\ride_XXX} 模型串（即 Mount 属性值，如 9583 →
 *       npc\ride_camel_0104）；</li>
 *   <li>share/ride/new_ride.ini {@code [ArtPack]} 段：MountType 兜底 +
 *       MultiRide/type（展示背景）。</li>
 * </ul>
 *
 * <p>表经 {@link ResourceCache} 按目录缓存；tool_item.ini 中文名走 GBK，
 * itemartstatic/new_ride 模型路径为 ASCII（Latin-1 无损读，同 equipment.ini 约定）。
 */
public final class MountCatalog {

    /** 坐骑条目（packId=ArtPack=NewRideRec col0；model=Mount 属性值）。 */
    public record Mount(String configId, String name, int packId, int mountType,
                        String rideSkillIds, float speedQuality, int validTimeMinute,
                        String model) {
    }

    private static final Charset GBK = Charset.forName("GBK");

    private final Map<String, Mount> byConfig = new HashMap<>();
    private final Map<Integer, Mount> byPack = new HashMap<>();
    private final Map<Integer, String> models = new HashMap<>();
    private final Map<Integer, Integer> newRideTypes = new HashMap<>();

    public static MountCatalog load(Path itemRoot, Path ruleRoot) {
        MountCatalog catalog = new MountCatalog();
        // itemartstatic.ini：pack → npc\ride_XXX（先装模型表）。
        ResourceCache.ini(itemRoot.resolve("itemartstatic.ini"), StandardCharsets.ISO_8859_1,
                (section, key, value) -> {
                    Integer pack = parseSectionId(section);
                    if (pack == null) {
                        return;
                    }
                    if (key.toLowerCase(Locale.ROOT).equals("malemodel") && !value.isEmpty()) {
                        catalog.models.put(pack, value);
                    }
                });
        // new_ride.ini：pack → MountType 兜底。
        ResourceCache.ini(ruleRoot.resolve("ride").resolve("new_ride.ini"),
                StandardCharsets.ISO_8859_1, (section, key, value) -> {
                    Integer pack = parseSectionId(section);
                    if (pack == null) {
                        return;
                    }
                    if (key.toLowerCase(Locale.ROOT).equals("mounttype")) {
                        catalog.newRideTypes.put(pack, parseInt(value, 0));
                    }
                });
        // tool_item.ini script=Mount：config → 坐骑条目。
        ResourceCache.ini(itemRoot.resolve("tool_item.ini"), GBK, (section, key, value) -> {
            if (section.startsWith("//") || section.startsWith(";")) {
                return; // 注释节
            }
            Entry entry = catalog.entries.computeIfAbsent(
                    section.toLowerCase(Locale.ROOT), s -> new Entry(section));
            switch (key.toLowerCase(Locale.ROOT)) {
                case "name" -> entry.name = value;
                case "script" -> entry.script = value;
                case "mounttype" -> entry.mountType = parseInt(value, 0);
                case "rideskillid" -> entry.rideSkillIds = value;
                case "speedquality" -> entry.speedQuality = parseFloat(value, 0f);
                case "validtimeminute" -> entry.validTimeMinute = parseInt(value, 0);
                case "artpack" -> entry.artPack = parseInt(value, 0);
                default -> {
                }
            }
        });
        for (Entry entry : catalog.entries.values()) {
            if (!"Mount".equalsIgnoreCase(entry.script) || entry.artPack <= 0) {
                continue;
            }
            String model = catalog.models.get(entry.artPack);
            if (model == null || model.isEmpty()) {
                continue; // 无模型映射的坐骑（itemartstatic 缺行）不可骑
            }
            int mountType = entry.mountType > 0
                    ? entry.mountType : catalog.newRideTypes.getOrDefault(entry.artPack, 0);
            Mount mount = new Mount(entry.section, entry.name, entry.artPack, mountType,
                    entry.rideSkillIds, entry.speedQuality, entry.validTimeMinute, model);
            catalog.byConfig.put(entry.section.toLowerCase(Locale.ROOT), mount);
            catalog.byPack.putIfAbsent(entry.artPack, mount);
        }
        return catalog;
    }

    /** 建档中间态（tool_item 逐键合并）。 */
    private static final class Entry {
        final String section;
        String name = "";
        String script = "";
        String rideSkillIds = "";
        int mountType;
        float speedQuality;
        int validTimeMinute;
        int artPack;

        Entry(String section) {
            this.section = section;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** 常驻缓存（同 EquipmentResolver.loadDefault 口径）。 */
    public static MountCatalog loadDefault() {
        Path itemRoot = ServerPaths.projectPath("resources", "modern", "share", "item");
        Path ruleRoot = ServerPaths.projectPath("resources", "modern", "share");
        return ResourceCache.of("mount-catalog:" + itemRoot, () -> load(itemRoot, ruleRoot));
    }

    private MountCatalog() {
    }

    /** 道具 configId（大小写不敏感）→ 坐骑；非坐骑道具/未知返回 null。 */
    public Mount byConfig(String configId) {
        if (configId == null || configId.isEmpty()) {
            return null;
        }
        return byConfig.get(configId.trim().toLowerCase(Locale.ROOT));
    }

    /** 坐骑包 id（NewRideRec col0 / ArtPack）→ 坐骑。 */
    public Mount byPack(int packId) {
        return byPack.get(packId);
    }

    /** 坐骑包 → 模型串（无道具行也能解析 NewRideRec 历史行）。 */
    public String modelOf(int packId) {
        return models.get(packId);
    }

    public boolean isEmpty() {
        return byConfig.isEmpty();
    }

    public List<Mount> all() {
        return List.copyOf(byConfig.values());
    }

    private static Integer parseSectionId(String section) {
        try {
            return Integer.parseInt(section.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return (int) Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
