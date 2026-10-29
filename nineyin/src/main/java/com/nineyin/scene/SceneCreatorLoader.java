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
    private static final Pattern CREATOR_OPEN_TAG = Pattern.compile("<creator\\b[^>]*>");
    private static final Pattern POSITION_TAG = Pattern.compile("<position\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(\\w+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

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

    /**
     * 清单/目录里跳过的非永久摆放文件。*_creator（randomcreator）副本模式全保留；
     * 世界模式仅放行 attack 系（attacknpc_creator = 野外随机刷怪点），采集/宝箱系仍跳过。
     */
    private static boolean skippedName(String lowName, boolean cloneScene) {
        if (lowName.contains("_creator") && (cloneScene || lowName.contains("attack"))) {
            return false;
        }
        return lowName.contains("eventtrigger") || lowName.contains("_creator")
                || lowName.contains("taskgather") || lowName.contains("boxnpc")
                || lowName.contains("lightnpc") || lowName.contains("guildbuilding");
    }

    public static List<NpcInstance> load(String sceneResource, Path creatorRoot) {
        return load(sceneResource, creatorRoot, false);
    }

    /**
     * cloneScene=true 为副本模式：保留 SubSceneNo 副本摆放、展开 *_creator 的
     * randomcreator（显式 position 优先，无 position 时按中心+半径圆周展开），
     * 并解析 NpcProgress 波次门控与 RefreshTime 重生间隔。
     * 世界模式下 attacknpc_creator（野外随机刷怪点）同样展开，但跳过带
     * SubSceneNo 的副本子摆位；其余 *_creator（采集/宝箱）不进本方法。
     */
    public static List<NpcInstance> load(String sceneResource, Path creatorRoot, boolean cloneScene) {
        List<NpcInstance> npcs = new ArrayList<>();
        Path dir = creatorDirForResource(sceneResource, creatorRoot);
        if (!Files.isDirectory(dir)) {
            log.warn("npc creator dir missing: {}", dir);
            return npcs;
        }
        List<Path> ordered = manifest(dir, cloneScene);
        if (ordered.isEmpty()) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .filter(n -> n.toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .filter(n -> !skippedName(n.toString().toLowerCase(Locale.ROOT), cloneScene))
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
            if (fname.contains("_creator")) {
                int[] oid = {nextOid};
                expandRandomCreators(content, isAttack, npcs, oid, cloneScene);
                nextOid = oid[0];
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
                if (!cloneScene && sub > 0.5f) {
                    continue; // 副本摆放（副本模式下保留）
                }
                NpcInstance n = build(nextOid++, cfg, x, y, z, attr, isAttack);
                npcs.add(n);
            }
        }
        if (npcs.isEmpty()) {
            log.warn("npc creator loaded 0 items from {} (files={}, check XML format/manifest)", dir,
                    ordered.size());
        } else {
            log.info("npc {} loaded dir={} count={}", cloneScene ? "clone placements" : "permanent",
                    dir, npcs.size());
        }
        return npcs;
    }

    private static NpcInstance build(int oid, String cfg, float x, float y, float z,
                                     MapAttr attr, boolean isAttack) {
        NpcInstance n = new NpcInstance();
        n.objectId = oid;
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
        n.shopId = attr.getOrElse("ShopID", attr.getOrElse("ShopId", ""));
        n.depotId = attr.getOrElse("DepotID", attr.getOrElse("DepotId", ""));
        n.doorPackageId = attr.getOrElse("DoorPackageID", attr.getOrElse("DoorPackageId", ""));
        n.playerTransferPosition = attr.getOrElse("PlayerTransferPosition", "");
        n.springRange = attr.floatOr("SpringRange", 0);
        n.npcProgress = (int) attr.floatOr("NpcProgress", 0);
        n.refreshMs = refreshMillis(attr.get("RefreshTime"));
        return n;
    }

    /**
     * randomcreator 展开：&lt;creator x y z radius NpcProgress&gt; 内的 &lt;item id amount&gt;
     * 带 &lt;position x z ay&gt; 用显式坐标；无 position 时按 amount 在中心+radius*0.65
     * 圆周均匀展开（对齐 Go expandRandomCreatorItem 的确定性分布）。
     * 世界模式跳过带 SubSceneNo 的副本子摆位（scene08 等野外目录混有副本 creator）。
     */
    private static void expandRandomCreators(String content, boolean isAttack,
                                             List<NpcInstance> out, int[] oid, boolean cloneScene) {
        Matcher creators = CREATOR_OPEN_TAG.matcher(content);
        while (creators.find()) {
            MapAttr creatorAttr = parseAttrs(creators.group());
            if (!cloneScene && creatorAttr.floatOr("SubSceneNo", 0) > 0.5f) {
                continue;
            }
            int bodyStart = creators.end();
            int bodyEnd = content.indexOf("</creator>", bodyStart);
            if (bodyEnd < 0) {
                bodyEnd = content.length();
            }
            String body = content.substring(bodyStart, bodyEnd);
            List<int[]> itemTags = new ArrayList<>();
            Matcher items = ITEM_TAG.matcher(body);
            while (items.find()) {
                itemTags.add(new int[]{items.start(), items.end()});
            }
            for (int i = 0; i < itemTags.size(); i++) {
                MapAttr item = parseAttrs(body.substring(itemTags.get(i)[0], itemTags.get(i)[1]));
                String cfg = item.get("id");
                if (cfg == null || cfg.isEmpty()) {
                    continue;
                }
                if (!cloneScene && item.floatOr("SubSceneNo", 0) > 0.5f) {
                    continue;
                }
                int chunkEnd = i + 1 < itemTags.size() ? itemTags.get(i + 1)[0] : body.length();
                String chunk = body.substring(itemTags.get(i)[1], chunkEnd);
                List<float[]> positions = new ArrayList<>();
                Matcher pos = POSITION_TAG.matcher(chunk);
                while (pos.find()) {
                    MapAttr pa = parseAttrs(pos.group());
                    Float px = pa.floatOf("x");
                    Float pz = pa.floatOf("z");
                    if (px != null && pz != null) {
                        float ay = pa.floatOf("ay") != null ? pa.floatOf("ay")
                                : item.floatOr("ay", 0);
                        positions.add(new float[]{px, pz, ay});
                    }
                }
                int amount = (int) item.floatOr("amount", 1);
                if (amount <= 0) {
                    amount = 1;
                }
                if (positions.isEmpty() && amount > 0) {
                    float cx = creatorAttr.floatOr("x", 0);
                    float cz = creatorAttr.floatOr("z", 0);
                    float radius = creatorAttr.floatOr("radius", 0);
                    for (int k = 0; k < amount; k++) {
                        double angle = 2 * Math.PI * k / amount;
                        positions.add(new float[]{
                                (float) (cx + radius * 0.65 * Math.cos(angle)),
                                (float) (cz + radius * 0.65 * Math.sin(angle)),
                                item.floatOr("ay", 0)});
                    }
                }
                // item 级 NpcProgress/RefreshTime 优先，缺省回落 creator 级
                int progress = (int) item.floatOr("NpcProgress", 0);
                if (progress == 0) {
                    progress = (int) creatorAttr.floatOr("NpcProgress", 0);
                }
                long refresh = refreshMillis(item.get("refreshtime") != null
                        ? item.get("refreshtime") : item.get("RefreshTime"));
                if (refresh < 0) {
                    refresh = refreshMillis(creatorAttr.get("RefreshTime"));
                }
                for (float[] p : positions) {
                    NpcInstance n = build(oid[0]++, cfg, p[0], creatorAttr.floatOr("y", 0), p[1],
                            mergeAttr(item, p[2]), isAttack);
                    n.npcProgress = progress;
                    n.refreshMs = refresh;
                    out.add(n);
                }
            }
        }
    }

    /** position/ay 合并：item 属性为底，ay 用 position 展开 override。 */
    private static MapAttr mergeAttr(MapAttr item, float ay) {
        java.util.HashMap<String, String> map = new java.util.HashMap<>(item.map());
        map.put("ay", String.valueOf(ay));
        return new MapAttr(map);
    }

    /** RefreshTime：-1=未配置；<=1000 视为秒，否则毫秒（水桶 3.0→3s，大门 30000→30s）。 */
    static long refreshMillis(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        try {
            float v = Float.parseFloat(raw.trim());
            if (v < 0) {
                return -1;
            }
            return v <= 1000f ? (long) (v * 1000) : (long) v;
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static List<Path> manifest(Path dir, boolean cloneScene) {
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
                if (name.isEmpty() || skippedName(name.toLowerCase(Locale.ROOT), cloneScene)) {
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
            String v = m.group(3) != null ? m.group(3) : m.group(4);
            map.put(m.group(1), v != null ? v : "");
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
