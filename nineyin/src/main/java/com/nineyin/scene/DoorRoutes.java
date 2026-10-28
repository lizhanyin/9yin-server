package com.nineyin.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 传送门（share/rule/door_data.ini，GBK）：DoorPackageID → 目标场景/坐标 + 触发点。
 * ScriptClass=Door 走 DoorPackageID 跨图路由；GotoDoor 走模板 PlayerTransferPosition 场景内传送。
 * 资源名推导对齐 Go resourceForDoorScene（city/school/born/scene 前缀取首段）。
 */
public final class DoorRoutes {

    private static final Logger log = LoggerFactory.getLogger(DoorRoutes.class);

    public record Position(float x, float y, float z, float orient) {
    }

    public static final class Target {
        public final String config;
        public final Position position;
        public final Position movePoint;

        Target(String config, Position position, Position movePoint) {
            this.config = config;
            this.position = position;
            this.movePoint = movePoint;
        }
    }

    /** 一条可执行的路由：同图传送或跨图切换。 */
    public record Route(boolean sameScene, String config, String resource,
                        Position position, Position trigger, float triggerRadius, String source) {
    }

    private final Map<String, Target> targets = new HashMap<>();
    private volatile boolean loaded;

    public synchronized void load(Path doorDataIni) {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(doorDataIni)) {
            log.warn("door_data.ini missing: {}", doorDataIni);
            return;
        }
        try {
            String data = new String(Files.readAllBytes(doorDataIni), Charset.forName("GBK"));
            Map<String, String> section = null;
            String sectionId = "";
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                        || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    flush(sectionId, section);
                    sectionId = line.substring(1, line.length() - 1).trim();
                    section = new HashMap<>();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && section != null) {
                    section.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                            line.substring(eq + 1).trim());
                }
            }
            flush(sectionId, section);
        } catch (IOException e) {
            log.warn("door_data.ini read failed: {}", doorDataIni, e);
        }
        log.info("door routes total={} from {}", targets.size(), doorDataIni);
    }

    private void flush(String id, Map<String, String> values) {
        if (values == null) {
            return;
        }
        String config = values.get("targetscene");
        if (config == null || config.isEmpty()) {
            return;
        }
        Position position = coords(values.get("targetx"), values.get("targety"),
                values.get("targetz"), values.get("targetorient"));
        Position movePoint = coords(values.get("movetargetx"), values.get("movetargety"),
                values.get("movetargetz"), "0");
        if (position == null || movePoint == null) {
            return;
        }
        targets.put(id, new Target(config, position, movePoint));
    }

    /** 模板（或 creator 覆盖）解析出的门路由；非门 NPC 返回 null。 */
    public Route routeFor(NpcTemplate template, NpcInstance npc, String creatorOverridePackage,
                          String creatorOverrideTransfer) {
        String scriptClass = template.scriptClass().trim().toLowerCase(Locale.ROOT);
        switch (scriptClass) {
            case "door" -> {
                String packageId = creatorOverridePackage != null && !creatorOverridePackage.isEmpty()
                        ? creatorOverridePackage : template.doorPackageId();
                if (packageId.isEmpty() || "0".equals(packageId)) {
                    return null;
                }
                Target target = targets.get(packageId);
                if (target == null) {
                    return null;
                }
                String resource = resourceForScene(target.config);
                return new Route(false, target.config, resource, target.position,
                        target.movePoint, triggerRadius(template), "DoorPackageID=" + packageId);
            }
            case "gotodoor" -> {
                String transfer = creatorOverrideTransfer != null && !creatorOverrideTransfer.isEmpty()
                        ? creatorOverrideTransfer : template.playerTransferPosition();
                Position position = parseTransfer(transfer);
                if (position == null) {
                    return null;
                }
                return new Route(true, "", "", position,
                        new Position(npc.x, npc.y, npc.z, npc.orient),
                        triggerRadius(template), "PlayerTransferPosition");
            }
            default -> {
                return null;
            }
        }
    }

    private static float triggerRadius(NpcTemplate template) {
        float radius = template.springRange();
        return radius > 0 && radius <= 100 ? radius : 8f;
    }

    /** PlayerTransferPosition="x,y,z,orient"。 */
    public static Position parseTransfer(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().replace("\"", "").split(",");
        if (parts.length != 4) {
            return null;
        }
        return coords(parts[0], parts[1], parts[2], parts[3]);
    }

    private static Position coords(String x, String y, String z, String orient) {
        try {
            return new Position(Float.parseFloat(x.trim()), Float.parseFloat(y.trim()),
                    Float.parseFloat(z.trim()), Float.parseFloat(orient.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    /** city/school/born/scene 前缀取首段作资源名，其余整名（Go resourceForDoorScene）。 */
    public static String resourceForScene(String config) {
        String normalized = config.trim().replace("/", "\\");
        String base = normalized;
        int slash = Math.max(base.lastIndexOf('\\'), base.lastIndexOf('/'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String lower = base.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"city", "school", "born", "scene"}) {
            if (lower.startsWith(prefix)) {
                int sep = lower.indexOf('_');
                if (sep > 0) {
                    return lower.substring(0, sep);
                }
            }
        }
        return lower;
    }
}
