package com.nineyin.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * creator 摆放加载：npc_creator/&lt;场景目录&gt;/ 下按 file.ini 清单读 XML，
 * 扫描 &lt;item x y z ay id PathId BornAction PatrolMode .../&gt; 标签。
 * 清单缺失时全量扫 XML 并按 common/func &gt; item &gt; attack 排序。
 */
public final class SceneCreatorLoader {

    private static final Logger log = LoggerFactory.getLogger(SceneCreatorLoader.class);

    private static final Pattern ITEM_TAG = Pattern.compile("<item\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");

    /** 已知资源 → npc_creator 子目录映射，缺失时模糊前缀匹配。 */
    private static Path creatorDirForResource(String sceneResource, Path creatorRoot) {
        String res = sceneResource.toLowerCase(Locale.ROOT);
        String folder = switch (res) {
            case "born01" -> "born01_mohezhen";
            case "born02" -> "born02_erengu";
            case "born03" -> "born03_yanyuzhuang";
            case "born04" -> "born04_qiandengzhen";
            case "city02" -> "city02_suzhou";
            case "city04" -> "city04_luoyang";
            case "city05" -> "city05_chengdu";
            case "school09" -> "school09_gaibang";
            default -> res;
        };
        Path direct = creatorRoot.resolve(folder);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        if (Files.isDirectory(creatorRoot)) {
            try (Stream<Path> dirs = Files.list(creatorRoot)) {
                return dirs.filter(Files::isDirectory)
                        .filter(d -> fileName(d).toLowerCase(Locale.ROOT).startsWith(res))
                        .findFirst()
                        .orElse(direct);
            } catch (IOException ignored) {
            }
        }
        return direct;
    }

    private static String fileName(Path p) {
        return p.getFileName().toString();
    }

    /** 清单/目录里跳过的非永久摆放文件。 */
    private static boolean skippedName(String lowName) {
        return lowName.contains("eventtrigger") || lowName.contains("_creator")
                || lowName.contains("taskgather") || lowName.contains("boxnpc")
                || lowName.contains("lightnpc") || lowName.contains("guildbuilding");
    }

    public static List<NpcInstance> load(String sceneResource, Path creatorRoot) {
        List<NpcInstance> npcs = new ArrayList<>();
        Path dir = creatorDirForResource(sceneResource, creatorRoot);
        if (!Files.isDirectory(dir)) {
            log.warn("npc creator dir missing: {}", dir);
            return npcs;
        }
        List<Path> ordered = manifest(dir);
        if (ordered.isEmpty()) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .filter(n -> n.toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .filter(n -> !skippedName(n.toString().toLowerCase(Locale.ROOT)))
                        .map(dir::resolve)
                        .sorted(Comparator.comparingInt(SceneCreatorLoader::fileScore))
                        .forEach(ordered::add);
            } catch (IOException ignored) {
            }
        }

        int nextOid = 10001;
        for (Path path : ordered) {
            String fname = fileName(path).toLowerCase(Locale.ROOT);
            boolean isAttack = fname.contains("attack");
            String content;
            try {
                // 字节读入 + 替换式解码：真实 XML 混有 UTF-8/GBK 中文（desc 等属性），
                // 属性扫描只关心 ASCII 字段，解码失败的位置替换即可（对齐 C++ 字节扫描行为）
                content = new String(Files.readAllBytes(path), Charset.forName("GBK"));
            } catch (IOException e) {
                continue;
            }
            Matcher items = ITEM_TAG.matcher(content);
            while (items.find()) {
                String tag = items.group();
                MapAttr attr = parseAttrs(tag);
                Float x = attr.floatOf("x");
                Float y = attr.floatOf("y");
                Float z = attr.floatOf("z");
                if (x == null || y == null || z == null) {
                    continue;
                }
                String cfg = attr.getOrElse("id", attr.get("ConfigID"));
                if (cfg == null || cfg.isEmpty()) {
                    continue;
                }
                float sub = attr.floatOr("SubSceneNo", 0);
                if (sub > 0.5f) {
                    continue; // 副本摆放
                }
                NpcInstance n = new NpcInstance();
                n.objectId = nextOid++;
                n.configId = cfg;
                n.x = x;
                n.y = y;
                n.z = z;
                n.orient = attr.floatOr("ay", 0);
                n.homeX = x;
                n.homeY = y;
                n.homeZ = z;
                n.monster = isAttack;
                String cl = cfg.toLowerCase(Locale.ROOT);
                if (cl.contains("monster") || cl.contains("boss") || cl.startsWith("mon")) {
                    n.monster = true;
                }
                n.pathId = attr.getOrElse("PathId", attr.getOrElse("PathID", ""));
                n.bornAction = attr.getOrDefault("BornAction", "");
                n.patrolMode = (int) attr.floatOr("PatrolMode", 0);
                npcs.add(n);
            }
        }
        log.info("npc permanent loaded dir={} count={}", dir, npcs.size());
        return npcs;
    }

    private static int fileScore(Path p) {
        String n = fileName(p).toLowerCase(Locale.ROOT);
        if (n.contains("commonnpc")) {
            return 0;
        }
        if (n.contains("funcnpc")) {
            return 1;
        }
        if (n.contains("itemnpc")) {
            return 2;
        }
        if (n.contains("attack")) {
            return 10;
        }
        return 5;
    }

    private static List<Path> manifest(Path dir) {
        List<Path> ordered = new ArrayList<>();
        Path file = dir.resolve("file.ini");
        if (!Files.isRegularFile(file)) {
            return ordered;
        }
        try {
            for (String line : new String(Files.readAllBytes(file), Charset.forName("GBK")).split("\n")) {
                if (line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#') {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = line.substring(eq + 1).trim();
                if (name.isEmpty() || skippedName(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Path p = dir.resolve(name);
                if (Files.isRegularFile(p)) {
                    ordered.add(p);
                }
            }
        } catch (IOException e) {
            log.warn("file.ini read failed: {}", file, e);
        }
        return ordered;
    }

    private static MapAttr parseAttrs(String tag) {
        java.util.HashMap<String, String> map = new java.util.HashMap<>();
        Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return new MapAttr(map);
    }

    private record MapAttr(java.util.HashMap<String, String> map) {

        String get(String key) {
            return map.get(key);
        }

        String getOrElse(String key, String fallback) {
            String v = map.get(key);
            return v != null ? v : fallback;
        }

        String getOrDefault(String key, String def) {
            return map.getOrDefault(key, def);
        }

        Float floatOf(String key) {
            String v = map.get(key);
            if (v == null || v.isEmpty()) {
                return null;
            }
            try {
                return Float.parseFloat(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        float floatOr(String key, float def) {
            Float v = floatOf(key);
            return v != null ? v : def;
        }
    }
}
