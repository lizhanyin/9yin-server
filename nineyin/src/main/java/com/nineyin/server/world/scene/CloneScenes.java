package com.nineyin.server.world.scene;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.util.ServerPaths;

/**
 * GM 副本目录：{@code share/clone/cloneinfo.ini}（GBK）——节名即场景 config
 * （如 {@code ini\scene\clone003}），Desc 为副本显示名；Easy/Difficult/Hell
 * PowerLevel 只是推荐功力，服务端不消费。难度/次数/掉落计分等准入协议
 * （CloneWeekLimitCount 等）原版走客户端 custom 消息，本期未复原，GM
 * enter_clone 仅以本表做存在性校验与显示名。
 *
 * <p>波次号来自 creator 表条目的 {@code NpcProgress} 属性（NpcResolver 已收进
 * {@code extensions["creator.NpcProgress"]}），如 clone003 暮色之村 5 波。
 *
 * <p>表经 {@link ResourceCache} 按目录缓存（serverRoot 变化自动换表，同
 * SceneNames 键策略）。
 */
public final class CloneScenes {

    /** 副本条目：config 节名原样 + 显示名。 */
    public record CloneInfo(String config, String desc) {
    }

    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 入口坐标 override：share/relive/sencerelive.ini 各 [cloneXXX] 节 ReliveCJ
     * 行原值（x,y,z,orient）。其余副本（含 clone003）走 {@link #entryPosition}
     * 的目录兜底——首个功能型 NPC（村口/剧情起点）坐标。
     */
    private static final Map<String, Roles.Position> ENTRY_OVERRIDES = Map.of(
            "ini\\scene\\clone001", new Roles.Position(40.987f, 42.736f, 449.768f, 0.570f),
            "ini\\scene\\clone007", new Roles.Position(1120.946f, 13.479f, 1498.714f, 1.598f),
            "ini\\scene\\clone011", new Roles.Position(741.465f, 48.770f, 586.660f, 4.180f));

    /** 一次性构建的表（ResourceCache 缓存值，随 shareRoot 路径区分）。 */
    private record Tables(Map<String, CloneInfo> byConfig) {

        static Tables build(Path shareRoot) {
            Map<String, CloneInfo> out = new HashMap<>();
            ResourceCache.ini(shareRoot.resolve("clone").resolve("cloneinfo.ini"), GBK,
                    (section, key, value) -> {
                        if (!section.toLowerCase(Locale.ROOT).startsWith("ini\\scene\\")
                                || !key.equalsIgnoreCase("desc") || value.isEmpty()) {
                            return;
                        }
                        out.putIfAbsent(section.toLowerCase(Locale.ROOT),
                                new CloneInfo(section, value));
                    });
            return new Tables(out);
        }
    }

    private static Tables tables() {
        Path shareRoot = ServerPaths.projectPath("resources", "modern", "share");
        return ResourceCache.of("clone-scenes:" + shareRoot, () -> Tables.build(shareRoot));
    }

    private CloneScenes() {
    }

    /** 按场景 config 查副本（大小写/斜杠不敏感）；未知返回 null（调用方回 fail 文案）。 */
    public static CloneInfo info(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        return tables().byConfig().get(normalizeConfig(config));
    }

    /** creator.NpcProgress 扩展 → 波次号；缺失/非正整数返回 null（恒可见）。 */
    public static Integer waveOf(NpcSpawn spawn) {
        String raw = spawn.resolved.extensions.get("creator.NpcProgress");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int wave = Integer.parseInt(raw.trim());
            return wave > 0 ? wave : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 目录最大波次（无任何 NpcProgress 条目时为 1：全量恒可见）。 */
    public static int maxWave(List<NpcSpawn> catalog) {
        int max = 1;
        for (NpcSpawn spawn : catalog) {
            Integer wave = waveOf(spawn);
            if (wave != null && wave > max) {
                max = wave;
            }
        }
        return max;
    }

    /**
     * 进本出生点：override 表（sencerelive 实证值）→ 目录首个功能型 NPC
     * （FuncNpc 来源、非 EventNpc，通常是村口/剧情起点，如 clone003 赵子亟的
     * 老婆）→ 首个非 EventNpc 条目 → 原点兜底。
     */
    public static Roles.Position entryPosition(Roles.Scene scene, List<NpcSpawn> catalog) {
        Roles.Position override = scene == null ? null
                : ENTRY_OVERRIDES.get(normalizeConfig(scene.config()));
        if (override != null) {
            return override;
        }
        NpcSpawn firstFunctional = null;
        NpcSpawn firstVisible = null;
        for (NpcSpawn spawn : catalog) {
            if (firstVisible == null && visible(spawn)) {
                firstVisible = spawn;
            }
            if (firstFunctional == null && visible(spawn)
                    && spawn.resolved.instanceKey != null
                    && spawn.resolved.instanceKey.toLowerCase(Locale.ROOT).contains("funcnpc")) {
                firstFunctional = spawn;
                break;
            }
        }
        NpcSpawn anchor = firstFunctional != null ? firstFunctional : firstVisible;
        return anchor == null ? new Roles.Position(0f, 0f, 0f, 0f)
                : new Roles.Position(anchor.x, anchor.y, anchor.z, anchor.orient);
    }

    /** Go npcVisibleInLocalViewport 同口径：EventNpc 不上线（NpcViewport 包私有，这里复刻）。 */
    private static boolean visible(NpcSpawn spawn) {
        String scriptClass = spawn.resolved.scriptClass == null ? "" : spawn.resolved.scriptClass;
        return !scriptClass.trim().equalsIgnoreCase("EventNpc");
    }

    /** config 键归一：小写 + 正斜杠 → 反斜杠（与 gm-web /api/clones 节名对齐）。 */
    private static String normalizeConfig(String config) {
        return config.trim().toLowerCase(Locale.ROOT).replace('/', '\\');
    }
}
