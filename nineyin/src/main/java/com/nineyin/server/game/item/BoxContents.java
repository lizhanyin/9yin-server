package com.nineyin.server.game.item;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 箱子→内容物对照表（share/drop/box_contents.json，固定全发语义）。
 *
 * <p>背景：官方 drop_box_* 掉落包正文从未随客户端发布，drops_mvp.json 绝大多数包是
 * 合成药品阶梯（开箱=随机药品）。本表按客户端 desc 描述文案重建（"内含菩提子*40、
 * 佛经书卷*1"），生成器 tools/boxgen_rebuild.py；data/giftbox.json 老重建表仅补缺。
 *
 * <p>结构：{@code boxes.{configId} = [{item, min, max}, ...]}；命中即逐条全发
 * {@code max} 个（min=max 描述数量），未命中返回 null 由调用方回落 DropPacks。
 * 文件缺失/空表容错为空目录（开箱链按原 drops_mvp/FALLBACK_LADDER 走）。
 */
public final class BoxContents {

    private static final Logger log = LoggerFactory.getLogger(BoxContents.class);

    private final Map<String, List<DropPacks.GrantItem>> boxes = new HashMap<>();

    /** 解析 box_contents.json；文件缺失保持空目录（查询恒 null，回落原链）。 */
    public static BoxContents load(Path json) {
        BoxContents contents = new BoxContents();
        if (!Files.isRegularFile(json)) {
            log.warn("box contents unavailable ({}): not a regular file", json);
            return contents;
        }
        Map<?, ?> root = DropPacks.JSON.parse(ResourceCache.text(json, StandardCharsets.UTF_8));
        Object pkg = root == null ? null : root.get("boxes");
        if (pkg instanceof Map<?, ?> bm) {
            for (Map.Entry<?, ?> e : bm.entrySet()) {
                if (!(e.getKey() instanceof String cfg) || !(e.getValue() instanceof List<?> entries)) {
                    continue;
                }
                List<DropPacks.GrantItem> list = new ArrayList<>();
                for (Object o : entries) {
                    if (!(o instanceof Map<?, ?> m) || !(m.get("item") instanceof String item)
                            || item.isBlank()) {
                        continue;
                    }
                    int max = m.get("max") instanceof Number n ? n.intValue() : 1;
                    int min = m.get("min") instanceof Number n ? n.intValue() : max;
                    if (min < 1 || max < min || max > 999) {
                        continue;
                    }
                    list.add(new DropPacks.GrantItem(item, max));
                }
                if (!list.isEmpty()) {
                    contents.boxes.put(cfg.trim().toLowerCase(Locale.ROOT), List.copyOf(list));
                }
            }
        }
        log.info("box contents ready: {} boxes from {}", contents.boxes.size(), json);
        return contents;
    }

    /** 常驻缓存（键加前缀避免与其他消费方互污）。 */
    public static BoxContents loadDefault() {
        Path json = ServerPaths.projectPath("resources", "modern", "share", "drop", "box_contents.json");
        return ResourceCache.of("box-contents:" + json, () -> load(json));
    }

    /** 按箱子 configId 查描述内容物；命中返回全发清单（不可变），未命中 null。 */
    public List<DropPacks.GrantItem> byBox(String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        return boxes.get(configId.trim().toLowerCase(Locale.ROOT));
    }

    public int size() {
        return boxes.size();
    }

    private BoxContents() {
    }
}
