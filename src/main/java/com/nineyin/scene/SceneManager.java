package com.nineyin.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景管理：定位服务器资源根（NINEYIN_SERVER_ROOT → 向上探测 resources/modern/share），
 * 加载模板表 → creator 摆放 → mappath 巡逻 → 活动怪过滤 → 绑定路线。
 * 每场景一个共享 SceneWorld（key = 资源名小写）。
 */
public final class SceneManager {

    private static final Logger log = LoggerFactory.getLogger(SceneManager.class);

    private final NpcTemplateRepository templates = new NpcTemplateRepository();
    private final Map<String, SceneWorld> worlds = new ConcurrentHashMap<>();
    private final Path shareRoot;

    public SceneManager() {
        this(locateShareRoot());
    }

    public SceneManager(Path shareRoot) {
        this.shareRoot = shareRoot;
    }

    /** 环境变量优先，其次从 cwd 向上找 resources/modern/share。 */
    public static Path locateShareRoot() {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        if (env != null && !env.isEmpty()) {
            Path p = Path.of(env, "resources", "modern", "share");
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("resources").resolve("modern").resolve("share");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Path.of("resources", "modern", "share");
    }

    public NpcTemplateRepository templates() {
        return templates;
    }

    public SceneWorld world(String sceneResource) {
        String key = sceneResource.toLowerCase(Locale.ROOT);
        return worlds.computeIfAbsent(key, k -> loadWorld(sceneResource));
    }

    private SceneWorld loadWorld(String sceneResource) {
        SceneWorld world = new SceneWorld(sceneResource);
        templates.load(shareRoot.resolve("npc").resolve("npcconfig"));
        world.npcs.addAll(SceneCreatorLoader.load(sceneResource,
                shareRoot.resolve("creator").resolve("npc_creator")));
        PatrolPaths.load(shareRoot.resolve("creator").resolve("mappath"), sceneResource)
                .forEach(world.pathTable::put);
        int before = world.npcs.size();
        world.npcs.removeIf(n -> isActivityId(n.configId));
        log.info("activity/event NPCs stripped: {} -> {}", before, world.npcs.size());
        PatrolPaths.assignRoutes(world.npcs, world.pathTable);
        return world;
    }

    /** 活动/剧情/护送/副本类 NPC 不作为场景常驻单位。 */
    public static boolean isActivityId(String id) {
        String low = id.toLowerCase(Locale.ROOT);
        return low.contains("hd_") || low.contains("event") || low.contains("escort_")
                || low.contains("clone") || low.contains("funnpc")
                || low.contains("sweetemploy") || low.contains("weibing")
                || (low.startsWith("boss_") && low.contains("kill"))
                || low.contains("japan") || low.startsWith("qy_")
                || low.contains("offline")
                || low.contains("2024") || low.contains("2025") || low.contains("2026");
    }
}
