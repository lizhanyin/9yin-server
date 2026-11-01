package com.nineyin.world.npc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.nineyin.protocol.NpcSchema;
import com.nineyin.role.Roles;

/**
 * 每场景 NPC creator 目录懒加载注册表，逐行移植 Go scene_catalog_registry.go +
 * npc_catalog.go 的模板表共享/选择逻辑。全部场景共享同一套 npcconfig 模板表，
 * creator 只携带摆放与实例覆盖。
 */
public final class SceneNpcRegistry {

    /** Go loadNPCSceneSpawnsFromTables 的统计。 */
    public record CatalogStats(int creatorInstances, int resolved, int unresolved, int ambiguous) {
    }

    public record CatalogResult(List<NpcSpawn> spawns, CatalogStats stats) {
    }

    private final List<NpcTemplateTable> tables;
    private final Path creatorRoot;
    private final List<NpcSchema.FieldSpec> schema;
    private final Map<String, String> dirs;
    private final Map<String, CatalogResult> catalogs = new HashMap<>();
    private final Map<String, CatalogStats> stats = new HashMap<>();

    private SceneNpcRegistry(List<NpcTemplateTable> tables, Path creatorRoot,
                             List<NpcSchema.FieldSpec> schema, Map<String, String> dirs) {
        this.tables = tables;
        this.creatorRoot = creatorRoot;
        this.schema = schema;
        this.dirs = dirs;
    }

    /** Go newSceneNPCRegistry：启动加载全部模板表 + creator 目录清单。 */
    public static SceneNpcRegistry open(Path tableDir, Path creatorRoot, List<NpcSchema.FieldSpec> schema)
            throws IOException {
        List<NpcTemplateTable> tables = loadTemplateTables(tableDir);
        Map<String, String> dirs = new HashMap<>();
        try (Stream<Path> entries = Files.list(creatorRoot)) {
            entries.filter(Files::isDirectory)
                    .forEach(dir -> dirs.put(dir.getFileName().toString().toLowerCase(Locale.ROOT),
                            dir.getFileName().toString()));
        }
        return new SceneNpcRegistry(tables, creatorRoot, schema, dirs);
    }

    /** Go loadNPCTemplateTables：npcconfig 子目录全部 .txt（根目录直接子文件是分类字典，跳过）。 */
    static List<NpcTemplateTable> loadTemplateTables(Path tableDir) throws IOException {
        List<Path> tablePaths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tableDir)) {
            walk.filter(path -> Files.isRegularFile(path)
                    && path.getParent() != null && !path.getParent().equals(tableDir)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .forEach(tablePaths::add);
        }
        tablePaths.sort(Comparator.comparing(Path::toString));
        List<NpcTemplateTable> tables = new ArrayList<>(tablePaths.size());
        for (Path path : tablePaths) {
            tables.add(NpcTemplateTable.load(path));
        }
        return tables;
    }

    public CatalogResult catalogFor(Roles.Scene scene) throws IOException {
        String folder = resolveFolder(scene);
        synchronized (this) {
            CatalogResult cached = catalogs.get(folder);
            if (cached != null) {
                return cached;
            }
        }
        CatalogResult loaded = loadSceneSpawns(schema, tables, creatorRoot.resolve(folder));
        synchronized (this) {
            catalogs.put(folder, loaded);
            stats.put(folder, loaded.stats());
        }
        return loaded;
    }

    /** Go loadNPCSceneSpawnsFromTables：file.ini 清单 → creator XML → 模板选择 → 解析。 */
    static CatalogResult loadSceneSpawns(List<NpcSchema.FieldSpec> schema,
                                         List<NpcTemplateTable> tables, Path creatorDir) throws IOException {
        List<Path> creatorPaths = sceneCreatorPaths(creatorDir);
        int creatorInstances = 0;
        int resolved = 0;
        int unresolved = 0;
        int[] ambiguous = {0};
        List<NpcSpawn> spawns = new ArrayList<>(256);
        for (Path creatorPath : creatorPaths) {
            String creatorName = creatorPath.getFileName().toString();
            NpcCreator.Catalog catalog = NpcCreator.load(creatorPath);
            creatorInstances += catalog.instances.size();
            for (NpcCreator.Instance instance : catalog.instances) {
                NpcTemplateTable table = selectTemplateTable(tables, creatorName, instance.templateId,
                        found -> {
                            if (found) {
                                ambiguous[0]++;
                            }
                        });
                if (table == null) {
                    unresolved++;
                    continue;
                }
                NpcResolver.ResolvedNpc npc;
                try {
                    npc = NpcResolver.resolve(schema, table, instance, NpcResolver.spawnDefaultsCompatV1());
                } catch (RuntimeException e) {
                    if (unresolved == 0) {
                        System.out.println("[resolver-first-failure] " + creatorName
                                + " " + instance.templateId + ": " + e);
                    }
                    unresolved++;
                    continue;
                }
                spawns.add(NpcSpawn.fromInstance(npc, instance.transform));
                resolved++;
            }
        }
        if (spawns.isEmpty()) {
            throw new IOException("no NPC creator instance could be resolved in " + creatorDir);
        }
        return new CatalogResult(List.copyOf(spawns),
                new CatalogStats(creatorInstances, resolved, unresolved, ambiguous[0]));
    }

    private interface AmbiguousSink {
        void accept(boolean ambiguous);
    }

    /** Go selectNPCTemplateTable：LookupAll 恰一行的表集合内按偏好名挑选。 */
    static NpcTemplateTable selectTemplateTable(List<NpcTemplateTable> tables, String creatorName,
                                                String configId, AmbiguousSink sink) {
        List<NpcTemplateTable> matches = new ArrayList<>(2);
        for (NpcTemplateTable table : tables) {
            if (table.lookupAll(configId).size() == 1) {
                matches.add(table);
            }
        }
        if (matches.isEmpty()) {
            sink.accept(false);
            return null;
        }
        for (String preferred : preferredNpcTables(creatorName)) {
            for (NpcTemplateTable table : matches) {
                String base = Path.of(table.source).getFileName().toString();
                if (base.equalsIgnoreCase(preferred)) {
                    return table;
                }
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        sink.accept(true);
        return null;
    }

    /** Go preferredNPCTables。 */
    static List<String> preferredNpcTables(String creatorName) {
        String name = creatorName.toLowerCase(Locale.ROOT);
        if (name.startsWith("attacknpc")) {
            return List.of("attacknpc.txt", "bossnpc.txt", "monsternpc_commonnpc.txt");
        }
        if (name.equals("commonnpc.xml") || name.equals("funcnpc.xml")) {
            return List.of("city_commonnpc.txt", "scene_commonnpc.txt", "other_commonnpc.txt");
        }
        return List.of("other_commonnpc.txt", "scene_commonnpc.txt", "city_commonnpc.txt");
    }

    /** Go sceneCreatorPaths：file.ini 清单（缺文件容错、EventTrigger.xml 排除）或保守全扫。 */
    static List<Path> sceneCreatorPaths(Path creatorDir) throws IOException {
        Path manifestPath = creatorDir.resolve("file.ini");
        if (Files.isRegularFile(manifestPath)) {
            List<Path> creatorPaths = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String line : Files.readAllLines(manifestPath, java.nio.charset.Charset.forName("GBK"))) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String rawName = trimmed.substring(eq + 1).trim();
                if (!rawName.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    continue;
                }
                if (rawName.equalsIgnoreCase("eventtrigger.xml")) {
                    continue;
                }
                Path path = creatorDir.resolve(rawName);
                String key = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
                if (seen.contains(key) || !Files.isRegularFile(path)) {
                    continue;
                }
                seen.add(key);
                creatorPaths.add(path);
            }
            if (creatorPaths.isEmpty()) {
                throw new IOException("scene creator manifest " + manifestPath + " declares no XML creator");
            }
            creatorPaths.sort(Comparator.comparing(Path::toString));
            return creatorPaths;
        }
        List<Path> creatorPaths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(creatorDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")
                            && !path.getFileName().toString().equalsIgnoreCase("eventtrigger.xml"))
                    .forEach(creatorPaths::add);
        }
        if (creatorPaths.isEmpty()) {
            throw new IOException("scene creator " + creatorDir + " contains no XML creator");
        }
        creatorPaths.sort(Comparator.comparing(Path::toString));
        return creatorPaths;
    }

    // ---- 场景 → creator 目录解析（Go resolveFolder + 候选链） ----

    /** 主城/门派客户端资源目录与 creator 目录的别名对。 */
    private static final Map<String, String> PRIMARY_SCENE_FOLDER = Map.ofEntries(
            Map.entry("scene01", "scene01_youyunshiliuzhou"),
            Map.entry("clone004_wl_1", "clone004_wl"),
            Map.entry("city01", "city01_yanjing"),
            Map.entry("city02", "city02_suzhou"),
            Map.entry("city03", "city03_jinling"),
            Map.entry("city04", "city04_luoyang"),
            Map.entry("city05", "city05_chengdu"),
            Map.entry("school01", "school01_jinyiwei"),
            Map.entry("school02", "school02_gaibang"),
            Map.entry("school03", "school03_junzitang"),
            Map.entry("school04", "school04_jilegu"),
            Map.entry("school05", "school05_tangmen"),
            Map.entry("school06", "school06_emei"),
            Map.entry("school07", "school07_wudang"),
            Map.entry("school08", "school08_shaolin"),
            Map.entry("school09", "school09_yihuagong"),
            Map.entry("school10", "school10_taohuadao"),
            Map.entry("school11", "school11_wugenmen"),
            Map.entry("school12", "school12_wuxianjiao"),
            Map.entry("school13", "school13_xuedaomen"),
            Map.entry("school14", "school14_gumupai"),
            Map.entry("school15", "school15_nianluoba"),
            Map.entry("school17", "school17_huashanpai"),
            Map.entry("school18", "school18_damo"),
            Map.entry("school19", "school19_shenshuigong"),
            Map.entry("school20", "school20_mingjiao"),
            Map.entry("school21", "school21_shenjiying"),
            Map.entry("school22", "school22_tianshan"),
            Map.entry("school23", "school23_xingmiaoge"),
            Map.entry("school24", "school24_kunlun"),
            Map.entry("school25", "school25_tianya"),
            Map.entry("school26", "school26_xingtianbao"),
            Map.entry("school28", "school28_wanghui"),
            Map.entry("school30", "school30_wuxu"),
            Map.entry("school31", "school31_shenjihui"));

    /** 共享地形但 creator 不同的副本变体：按权威 scene config 先行指定。 */
    private static final Map<String, String> PREFERRED_SCENE_CONFIG_FOLDER = Map.of(
            "ini\\scene\\clone002_yx", "clone002_yx");

    public String resolveFolder(Roles.Scene scene) throws IOException {
        String configKey = scene.config().trim().toLowerCase(Locale.ROOT);
        String preferred = PREFERRED_SCENE_CONFIG_FOLDER.get(configKey);
        if (preferred != null && dirs.containsKey(preferred)) {
            return dirs.get(preferred);
        }
        for (String candidate : sceneResourceCandidates(scene)) {
            String folder = dirs.get(candidate);
            if (folder != null) {
                return folder;
            }
            String primary = PRIMARY_SCENE_FOLDER.get(candidate);
            if (primary != null && dirs.containsKey(primary)) {
                return dirs.get(primary);
            }
        }
        for (String candidate : sceneResourceCandidates(scene)) {
            List<String> matches = new ArrayList<>();
            for (Map.Entry<String, String> dir : dirs.entrySet()) {
                if (dir.getKey().startsWith(candidate + "_")) {
                    matches.add(dir.getValue());
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                matches.sort(String::compareTo);
                throw new IOException("scene " + scene + " matches multiple NPC creator folders " + matches
                        + "; add an explicit resource mapping");
            }
        }
        throw new IOException("no NPC creator folder for scene resource=" + scene.resource()
                + " config=" + scene.config());
    }

    /** Go sceneResourceCandidates：resource + config 基名（去 scene_ 前缀）。 */
    static List<String> sceneResourceCandidates(Roles.Scene scene) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        addCandidate(seen, result, scene.resource());
        String config = scene.config().replace("/", "\\");
        String base = Path.of(config).getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        addCandidate(seen, result, base);
        return result;
    }

    private static void addCandidate(java.util.Set<String> seen, List<String> result, String value) {
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("scene_")) {
            cleaned = cleaned.substring("scene_".length());
        }
        if (!cleaned.isEmpty() && seen.add(cleaned)) {
            result.add(cleaned);
        }
    }
}
