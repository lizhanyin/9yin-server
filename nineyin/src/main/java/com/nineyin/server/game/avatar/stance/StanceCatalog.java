package com.nineyin.server.game.avatar.stance;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

/**
 * 站姿目录：客户端 {@code ini/b_action_index.ini} 节名集合（引擎基座动作，
 * 如 stand / khbd_stand_01 / TJstand）。GM「待机站姿」下发前校验动作名命中，
 * 防止拼错动作名把客户端 Blend 到空动作（表现为僵直）。
 */
public final class StanceCatalog {

    private final Set<String> actions = new HashSet<>();

    public static StanceCatalog load(Path modernRoot) {
        StanceCatalog catalog = new StanceCatalog();
        ResourceCache.ini(modernRoot.resolve("ini").resolve("b_action_index.ini"),
                java.nio.charset.StandardCharsets.UTF_8, (section, key, value) -> {
                    if (!section.startsWith("//") && !section.startsWith(";")) {
                        catalog.actions.add(section);
                    }
                });
        return catalog;
    }

    /** 常驻缓存（同 GmCatalog.idleStances 数据源）。 */
    public static StanceCatalog loadDefault() {
        Path modernRoot = ServerPaths.projectPath("resources", "modern");
        return ResourceCache.of("stance-catalog:" + modernRoot, () -> load(modernRoot));
    }

    private StanceCatalog() {
    }

    /** 动作名是否存在于 b_action_index.ini。 */
    public boolean exists(String action) {
        return action != null && actions.contains(action.trim());
    }

    public int size() {
        return actions.size();
    }
}
