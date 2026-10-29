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
    private final DoorRoutes doorRoutes = new DoorRoutes();

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

    /** 丢弃场景缓存（GM 重置副本用）：下次 world() 会从数据重建。 */
    public void resetWorld(String sceneResource) {
        worlds.remove(sceneResource.toLowerCase(Locale.ROOT));
    }

    /** 副本场景（clone 系资源）：摆位/门控按副本规则加载。 */
    public static boolean isCloneScene(String sceneResource) {
        return sceneResource != null && sceneResource.toLowerCase(Locale.ROOT).contains("clone");
    }

    private SceneWorld loadWorld(String sceneResource) {
        boolean cloneScene = isCloneScene(sceneResource);
        SceneWorld world = new SceneWorld(sceneResource, cloneScene);
        templates.load(shareRoot.resolve("npc").resolve("npcconfig"));
        doorRoutes.load(shareRoot.resolve("rule").resolve("door_data.ini"));
        world.npcs.addAll(SceneCreatorLoader.load(sceneResource,
                shareRoot.resolve("creator").resolve("npc_creator"), cloneScene));
        PatrolPaths.load(shareRoot.resolve("creator").resolve("mappath"), sceneResource)
                .forEach(world.pathTable::put);
        if (!cloneScene) {
            int before = world.npcs.size();
            world.npcs.removeIf(n -> isActivityId(n.configId));
            log.info("activity/event NPCs stripped: {} -> {}", before, world.npcs.size());
        }
        PatrolPaths.assignRoutes(world.npcs, world.pathTable);
        for (NpcInstance n : world.npcs) {
            NpcTemplate tmpl = templates.lookup(n.configId);
            n.maxHp = tmpl.maxHp() > 0 ? tmpl.maxHp() : 1000;
            n.hp = n.maxHp;
            String script = tmpl.scriptClass().toLowerCase(Locale.ROOT);
            if (script.contains("attack") || script.contains("boss")) {
                n.monster = true;
            }
            DoorRoutes.Route route = doorRoutes.routeFor(tmpl, n,
                    n.doorPackageId, n.playerTransferPosition);
            if (route != null) {
                world.portals.add(new SceneWorld.Portal(n.objectId, route));
            }
        }
        return world;
    }

    /** 传送门路由（door_data.ini）。 */
    public DoorRoutes doorRoutes() {
        return doorRoutes;
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
