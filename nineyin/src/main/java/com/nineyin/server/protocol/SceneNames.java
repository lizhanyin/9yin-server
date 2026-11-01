package com.nineyin.server.protocol;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.nineyin.util.ServerPaths;
import com.nineyin.commom.cache.ResourceCache;

/**
 * 角色选择界面所在场景显示名（选角 0x04 rolePara 的 f24），数据源：
 * share/rule/scenes.ini（GBK，config/resource → 场景 id）+
 * text/chineses/stringname.idres（UTF-8，键=ini\scene\xxx 小写，如
 * "ini\scene\school09_yihuagong=移花宫"、"city05=成都"）。
 * scene.idres 仅有子区域（area_*）别名，主场景名不依赖它，暂不加载。
 *
 * <p>缺失时回退 scene_name_NNN 客户端本地化键（N=scenes.ini 场景 id）。
 * 全部表经 {@link ResourceCache} 按目录缓存，首次使用后不再读盘。
 */
public final class SceneNames {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String FALLBACK_KEY = "scene_name_017";

    private static final Map<String, String> BOOK_INTRO_CODE = Map.of(
            "book1", "qdz",
            "book2", "yyz",
            "book4", "cd",
            "book5", "jm",
            "book8", "wmp");

    /** 一次性构建的表组（ResourceCache 缓存值，随 shareRoot 路径区分）。 */
    private record Tables(Map<String, Integer> idByConfig,
                          Map<String, Integer> idByResource,
                          Map<String, String> nameByKey) {

        static Tables build(Path shareRoot) {
            Map<String, Integer> idByConfig = new HashMap<>();
            Map<String, Integer> idByResource = new HashMap<>();
            ResourceCache.ini(shareRoot.resolve("rule").resolve("scenes.ini"), GBK, (section, key, value) -> {
                if (!section.chars().allMatch(Character::isDigit) || !key.equalsIgnoreCase("Config")) {
                    return;
                }
                int id;
                try {
                    id = Integer.parseInt(section);
                } catch (NumberFormatException e) {
                    return;
                }
                idByConfig.put(value.toLowerCase(Locale.ROOT), id);
                int slash = Math.max(value.lastIndexOf('\\'), value.lastIndexOf('/'));
                String base = slash >= 0 ? value.substring(slash + 1) : value;
                idByResource.putIfAbsent(base.toLowerCase(Locale.ROOT), id);
            });
            Map<String, String> nameByKey = new HashMap<>(
                    ResourceCache.idresLowerCased(shareRoot.getParent()
                            .resolve("text").resolve("chineses").resolve("stringname.idres")));
            return new Tables(idByConfig, idByResource, nameByKey);
        }
    }

    private SceneNames() {
    }

    private static Tables tables() {
        Path shareRoot = ServerPaths.projectPath("resources", "modern", "share");
        return ResourceCache.of("scene-names:" + shareRoot, () -> Tables.build(shareRoot));
    }

    /** 角色所在（下线）场景显示名；表缺失时回退 scene_name_NNN 键（客户端本地化）。 */
    public static String sceneDisplayName(String config, String resource) {
        Tables tables = tables();
        String configLower = config == null ? "" : config.toLowerCase(Locale.ROOT);
        String resourceLower = resource == null ? "" : resource.toLowerCase(Locale.ROOT);
        String name = tables.nameByKey().get(configLower);
        if (name == null || name.isEmpty()) {
            name = tables.nameByKey().get(resourceLower);
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        Integer id = sceneId(tables, configLower, resourceLower);
        return id != null ? String.format("scene_name_%03d", id) : FALLBACK_KEY;
    }

    /** 剧本标题本地化键；未知剧本回退 book 原值。 */
    public static String bookIntroKey(String book) {
        String code = BOOK_INTRO_CODE.get(book);
        return code != null ? "introduction_" + code + "01_title" : book;
    }

    private static Integer sceneId(Tables tables, String configLower, String resourceLower) {
        if (!configLower.isEmpty()) {
            Integer id = tables.idByConfig().get(configLower);
            if (id != null) {
                return id;
            }
        }
        if (!resourceLower.isEmpty()) {
            return tables.idByResource().get(resourceLower);
        }
        return null;
    }
}
