package com.nineyin.game;

import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.NpcTemplate;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 世界 BOSS 定时刷新（share/npc 四表 + rule/maplist.ini 场景号映射）：
 * - normalbossrefreshinfo + normalbossrefreshtime：门派/固定场景 BOSS，
 *   每条刷新点在时间档的每日时刻从 bosslist 随机刷一只到固定坐标。
 * - jhscenebossrefreshinfo + jhscenebossrefreshpoint：江湖世界 BOSS，
 *   每日刷新窗口从随机场景 + 随机坐标点生成，存在时段结束未死则消失。
 * BOSS 以动态单位（tag=worldboss）入共享 SceneWorld，玩家进入/在同图即见；
 * 击杀后不自动重生（SceneFlow 跳过 respawn），由本管理器移除并等下个时间档。
 */
public final class WorldBossManager {

    public static final String TAG = "worldboss";

    private static final Logger log = LoggerFactory.getLogger(WorldBossManager.class);
    private static final Charset GBK = Charset.forName("GBK");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 生成/击杀回调：由服务器侧广播给同图会话与全服通告。 */
    public interface Listener {

        void onSpawned(SceneWorld world, NpcInstance boss, String notice);

        void onDefeated(SceneWorld world, NpcInstance boss, String notice);

        void onDespawned(SceneWorld world, NpcInstance boss);
    }

    /** 固定刷新点（normalbossrefreshinfo 一节）。 */
    public static final class NormalEntry {
        public final String id;
        public final int timeId;
        public final List<String> bossIds;
        public final int sceneNo;
        public final float x;
        public final float y;
        public final float z;
        public final int maxNumber;
        public final String notice;
        LocalDateTime nextDue;

        NormalEntry(String id, int timeId, List<String> bossIds, int sceneNo,
                    float x, float y, float z, int maxNumber, String notice) {
            this.id = id;
            this.timeId = timeId;
            this.bossIds = bossIds;
            this.sceneNo = sceneNo;
            this.x = x;
            this.y = y;
            this.z = z;
            this.maxNumber = maxNumber;
            this.notice = notice;
        }
    }

    /** 江湖随机 BOSS（jhscenebossrefreshinfo 一节）。 */
    public static final class JhEntry {
        public final String bossId;
        public final int week;
        final int spawnMinuteOfDay;
        final int despawnMinuteOfDay;
        public final List<Integer> scenes;
        public final String notice;
        LocalDateTime nextDue;
        LocalDateTime nextDespawn;

        JhEntry(String bossId, int week, int spawnMinuteOfDay, int despawnMinuteOfDay,
                List<Integer> scenes, String notice) {
            this.bossId = bossId;
            this.week = week;
            this.spawnMinuteOfDay = spawnMinuteOfDay;
            this.despawnMinuteOfDay = despawnMinuteOfDay;
            this.scenes = scenes;
            this.notice = notice;
        }
    }

    /** normalbossrefreshtime 时间档。 */
    static final class TimeRule {
        final int type;
        // type=1：起止日期内每天的 hour×min 时刻（须落在每日窗口内）
        final int beginYear;
        final int beginMonth;
        final int beginDay;
        final int endYear;
        final int endMonth;
        final int endDay;
        final int windowBeginMinute;
        final int windowEndMinute;
        // type=2：年/月/日 集合内每天的 hour×min 时刻
        final int[] years;
        final int[] months;
        final int[] days;
        final int[] hours;
        final int[] minutes;

        TimeRule(int type, int beginYear, int beginMonth, int beginDay,
                 int endYear, int endMonth, int endDay,
                 int windowBeginMinute, int windowEndMinute,
                 int[] years, int[] months, int[] days, int[] hours, int[] minutes) {
            this.type = type;
            this.beginYear = beginYear;
            this.beginMonth = beginMonth;
            this.beginDay = beginDay;
            this.endYear = endYear;
            this.endMonth = endMonth;
            this.endDay = endDay;
            this.windowBeginMinute = windowBeginMinute;
            this.windowEndMinute = windowEndMinute;
            this.years = years;
            this.months = months;
            this.days = days;
            this.hours = hours;
            this.minutes = minutes;
        }

        /** 该时刻是否命中本档（分钟精度）。 */
        boolean matches(LocalDateTime t) {
            LocalDate d = t.toLocalDate();
            if (type == 1) {
                if (d.isBefore(LocalDate.of(beginYear, beginMonth, beginDay))
                        || d.isAfter(LocalDate.of(endYear, endMonth, endDay))) {
                    return false;
                }
            } else {
                if (!contains(years, d.getYear()) || !contains(months, d.getMonthValue())
                        || !contains(days, d.getDayOfMonth())) {
                    return false;
                }
            }
            for (int h : hours) {
                for (int m : minutes) {
                    int minuteOfDay = h * 60 + m;
                    if (t.getHour() * 60 + t.getMinute() == minuteOfDay
                            && minuteOfDay >= windowBeginMinute && minuteOfDay <= windowEndMinute) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** after 之后（含跨天）的下一个命中时刻；找不到（超出日期界）返回 null。 */
        LocalDateTime nextFire(LocalDateTime after) {
            LocalDateTime cursor = after.plusMinutes(1).withSecond(0).withNano(0);
            for (int i = 0; i < 366 * 2; i++) {
                if (type == 1 && cursor.toLocalDate()
                        .isAfter(LocalDate.of(endYear, endMonth, endDay).plusDays(1))) {
                    return null;
                }
                if (type == 2 && cursor.getYear() > (years.length > 0 ? years[years.length - 1] : 0)) {
                    return null;
                }
                LocalDateTime candidate = fireAtOrAfterOnDay(cursor);
                if (candidate != null && candidate.isAfter(after)) {
                    return candidate;
                }
                cursor = cursor.toLocalDate().plusDays(1).atStartOfDay();
            }
            return null;
        }

        /** cursor 当天 >= cursor 的最早命中时刻。 */
        private LocalDateTime fireAtOrAfterOnDay(LocalDateTime cursor) {
            for (int h : hours) {
                for (int m : minutes) {
                    int minuteOfDay = h * 60 + m;
                    if (minuteOfDay < windowBeginMinute || minuteOfDay > windowEndMinute) {
                        continue;
                    }
                    LocalDateTime t = cursor.toLocalDate().atTime(LocalTime.of(h, m));
                    if (!t.isBefore(cursor) && matchesDate(t.toLocalDate())) {
                        return t;
                    }
                }
            }
            return null;
        }

        private boolean matchesDate(LocalDate d) {
            if (type == 1) {
                return !d.isBefore(LocalDate.of(beginYear, beginMonth, beginDay))
                        && !d.isAfter(LocalDate.of(endYear, endMonth, endDay));
            }
            return contains(years, d.getYear()) && contains(months, d.getMonthValue())
                    && contains(days, d.getDayOfMonth());
        }

        private static boolean contains(int[] arr, int v) {
            for (int x : arr) {
                if (x == v) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Live {
        final String key;
        final String resource;
        final int objectId;
        final NpcInstance boss;

        Live(String key, String resource, int objectId, NpcInstance boss) {
            this.key = key;
            this.resource = resource;
            this.objectId = objectId;
            this.boss = boss;
        }
    }

    private final SceneManager sceneManager;
    private final Random random = new Random();
    private final Map<String, NormalEntry> normalEntries = new LinkedHashMap<>();
    private final Map<String, JhEntry> jhEntries = new LinkedHashMap<>();
    private final Map<Integer, TimeRule> timeRules = new HashMap<>();
    private final Map<Integer, List<float[]>> jhPoints = new HashMap<>();
    private final Map<Integer, String> sceneNoToResource = new TreeMap<>();
    private final Map<String, Live> liveByKey = new HashMap<>();
    private volatile Listener listener;
    private volatile boolean loaded;

    public WorldBossManager(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void load(Path shareRoot) {
        if (loaded) {
            return;
        }
        loaded = true;
        loadMapList(shareRoot.resolve("rule").resolve("maplist.ini"));
        loadTimeRules(shareRoot.resolve("npc").resolve("normalbossrefreshtime.ini"));
        loadNormalEntries(shareRoot.resolve("npc").resolve("normalbossrefreshinfo.ini"));
        loadJhEntries(shareRoot.resolve("npc").resolve("jhscenebossrefreshinfo.ini"));
        loadJhPoints(shareRoot.resolve("npc").resolve("jhscenebossrefreshpoint.ini"));
        LocalDateTime now = LocalDateTime.now();
        normalEntries.values().forEach(e -> e.nextDue = nextNormalFire(e, now));
        jhEntries.values().forEach(e -> scheduleNextJh(e, now));
        log.info("world boss loaded: normal entries={} (time rules={}), jh bosses={} "
                        + "(point scenes={}), maplist scenes={}",
                normalEntries.size(), timeRules.size(), jhEntries.size(),
                jhPoints.size(), sceneNoToResource.size());
    }

    /** 主 tick：到点生成、存在期结束回收、击杀回收。时间显式传入便于测试。 */
    public synchronized void tick(LocalDateTime now) {
        if (!loaded) {
            return;
        }
        for (NormalEntry e : normalEntries.values()) {
            if (e.nextDue != null && !now.isBefore(e.nextDue)) {
                spawnNormal(e);
                e.nextDue = nextNormalFire(e, now);
            }
        }
        for (JhEntry e : jhEntries.values()) {
            if (e.nextDespawn != null && !now.isBefore(e.nextDespawn)) {
                despawnJh(e, "jh:" + e.bossId);
                e.nextDespawn = null;
            }
            if (e.nextDue != null && !now.isBefore(e.nextDue)) {
                spawnJh(e, now);
                scheduleNextJh(e, now);
            }
        }
        reapDeadBosses();
    }

    /** GM：无视时间档立即生成全部刷新点（占用中的跳过），返回生成数。 */
    public synchronized int forceSpawnAll() {
        int spawned = 0;
        for (NormalEntry e : normalEntries.values()) {
            if (spawnNormal(e)) {
                spawned++;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (JhEntry e : jhEntries.values()) {
            if (spawnJh(e, now)) {
                spawned++;
            }
        }
        return spawned;
    }

    /** GM：清空全部在场世界 BOSS。 */
    public synchronized int clearAll() {
        int removed = 0;
        for (Live live : new ArrayList<>(liveByKey.values())) {
            SceneWorld world = sceneManager.worldOrNull(live.resource);
            if (world != null && world.removeNpc(live.objectId)) {
                removed++;
            }
        }
        liveByKey.clear();
        jhEntries.values().forEach(e -> e.nextDespawn = null);
        return removed;
    }

    public synchronized int liveCount() {
        return liveByKey.size();
    }

    public synchronized String describe() {
        return "世界BOSS：在场 " + liveByKey.size() + "（固定点 " + normalEntries.size()
                + " 条 / 江湖 " + jhEntries.size() + " 只）";
    }

    // ---------- 加载 ----------

    private void loadMapList(Path file) {
        for (Map.Entry<String, Map<String, List<String>>> sec : iniSections(file).entrySet()) {
            for (Map.Entry<String, List<String>> kv : sec.getValue().entrySet()) {
                try {
                    sceneNoToResource.put(Integer.parseInt(kv.getKey().trim()),
                            kv.getValue().get(0).trim());
                } catch (NumberFormatException ignored) {
                    // 注释行/非编号键
                }
            }
        }
    }

    private void loadTimeRules(Path file) {
        for (Map.Entry<String, Map<String, List<String>>> sec : iniSections(file).entrySet()) {
            Map<String, List<String>> v = sec.getValue();
            int type = intOr(v, "type", 1);
            int[] hours = intCsv(v.get("hour"), new int[0]);
            int[] minutes = intCsv(v.get("min"), new int[0]);
            TimeRule rule = new TimeRule(type,
                    intOr(v, "beginyear", 2000), intOr(v, "beginmonth", 1), intOr(v, "beginday", 1),
                    intOr(v, "endyear", 3000), intOr(v, "endmonth", 1), intOr(v, "endday", 1),
                    intOr(v, "everydaybeginhour", 0) * 60 + intOr(v, "everydaybeginmin", 0),
                    intOr(v, "everydayendhour", 24) * 60 + intOr(v, "everydayendmin", 0),
                    intCsv(v.get("year"), new int[0]),
                    intCsv(v.get("month"), new int[0]),
                    intCsv(v.get("day"), new int[0]),
                    hours, minutes);
            try {
                timeRules.put(Integer.parseInt(sec.getKey().trim()), rule);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void loadNormalEntries(Path file) {
        for (Map.Entry<String, Map<String, List<String>>> sec : iniSections(file).entrySet()) {
            Map<String, List<String>> v = sec.getValue();
            List<String> bossList = new ArrayList<>();
            for (String s : firstOr(v, "bosslist", "").split(";")) {
                if (!s.isBlank()) {
                    bossList.add(s.trim());
                }
            }
            if (bossList.isEmpty()) {
                continue;
            }
            float[] pos = floatCsv3(firstOr(v, "position", ""));
            if (pos == null) {
                log.warn("normalboss entry {} invalid position, skipped", sec.getKey());
                continue;
            }
            try {
                normalEntries.put(sec.getKey().trim(), new NormalEntry(sec.getKey().trim(),
                        intOr(v, "timeid", 1), bossList,
                        Integer.parseInt(firstOr(v, "scene", "0").trim()),
                        pos[0], pos[1], pos[2],
                        intOr(v, "maxnumber", 1),
                        firstOr(v, "notice", "")));
            } catch (NumberFormatException e) {
                log.warn("normalboss entry {} invalid scene, skipped", sec.getKey());
            }
        }
    }

    private void loadJhEntries(Path file) {
        for (Map.Entry<String, Map<String, List<String>>> sec : iniSections(file).entrySet()) {
            Map<String, List<String>> v = sec.getValue();
            List<Integer> scenes = new ArrayList<>();
            for (String s : firstOr(v, "scene", "").split(",")) {
                try {
                    scenes.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            if (scenes.isEmpty()) {
                continue;
            }
            jhEntries.put(sec.getKey().trim(), new JhEntry(sec.getKey().trim(),
                    intOr(v, "week", 0),
                    intOr(v, "minhour", 0) * 60 + intOr(v, "minminute", 0),
                    intOr(v, "maxhour", 24) * 60 + intOr(v, "maxminute", 0),
                    scenes, firstOr(v, "notice", "")));
        }
    }

    private void loadJhPoints(Path file) {
        for (Map.Entry<String, Map<String, List<String>>> sec : iniSections(file).entrySet()) {
            List<float[]> points = new ArrayList<>();
            for (String p : sec.getValue().getOrDefault("point", List.of())) {
                float[] xyz = floatCsv3(p);
                if (xyz != null) {
                    points.add(xyz);
                }
            }
            try {
                jhPoints.put(Integer.parseInt(sec.getKey().trim()), points);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // ---------- 生成/回收 ----------

    private LocalDateTime nextNormalFire(NormalEntry e, LocalDateTime after) {
        TimeRule rule = timeRules.get(e.timeId);
        return rule != null ? rule.nextFire(after) : null;
    }

    private void scheduleNextJh(JhEntry e, LocalDateTime after) {
        LocalDateTime cursor = after;
        for (int i = 0; i < 8; i++) {
            LocalDateTime t = cursor.toLocalDate().atTime(minuteOfDayTime(e.spawnMinuteOfDay));
            if (t.isAfter(cursor) && weekMatches(e, t)) {
                e.nextDue = t;
                return;
            }
            cursor = cursor.toLocalDate().plusDays(1).atStartOfDay();
        }
        e.nextDue = null;
    }

    private static boolean weekMatches(JhEntry e, LocalDateTime t) {
        return e.week == 0 || t.getDayOfWeek().getValue() == e.week;
    }

    private boolean spawnNormal(NormalEntry e) {
        String key = "normal:" + e.id;
        if (liveByKey.containsKey(key)) {
            return false;
        }
        String resource = sceneNoToResource.get(e.sceneNo);
        if (resource == null) {
            log.debug("worldboss entry {} scene {} unmapped in maplist, skipped", e.id, e.sceneNo);
            return false;
        }
        String bossId = e.bossIds.get(random.nextInt(e.bossIds.size()));
        SceneWorld world = sceneManager.world(resource);
        NpcInstance boss = buildBoss(bossId, e.x, e.y, e.z);
        int oid = world.addDynamic(boss);
        liveByKey.put(key, new Live(key, world.sceneResource, oid, boss));
        notifySpawned(world, boss, resource);
        return true;
    }

    private boolean spawnJh(JhEntry e, LocalDateTime now) {
        String key = "jh:" + e.bossId;
        if (liveByKey.containsKey(key)) {
            return false;
        }
        List<Integer> candidates = new ArrayList<>(e.scenes);
        while (!candidates.isEmpty()) {
            int sceneNo = candidates.remove(random.nextInt(candidates.size()));
            List<float[]> points = jhPoints.get(sceneNo);
            String resource = sceneNoToResource.get(sceneNo);
            if (points == null || points.isEmpty() || resource == null) {
                continue;
            }
            float[] p = points.get(random.nextInt(points.size()));
            SceneWorld world = sceneManager.world(resource);
            NpcInstance boss = buildBoss(e.bossId, p[0], p[1], p[2]);
            int oid = world.addDynamic(boss);
            liveByKey.put(key, new Live(key, world.sceneResource, oid, boss));
            // 存在时段：生成日当天 despawn 时刻（24:00 = 次日零点前回收）；已过则顺延次日
            LocalDate day = now.toLocalDate();
            LocalDateTime despawn = day.atTime(minuteOfDayTime(e.despawnMinuteOfDay));
            if (!despawn.isAfter(now)) {
                despawn = day.plusDays(1).atTime(minuteOfDayTime(e.despawnMinuteOfDay));
            }
            e.nextDespawn = despawn;
            notifySpawned(world, boss, resource);
            return true;
        }
        log.warn("jh boss {} has no usable scene/point, skipped this cycle", e.bossId);
        return false;
    }

    /** 分钟数 → LocalTime（1440 归一到 23:59，避免 24:00 越界）。 */
    private static LocalTime minuteOfDayTime(int minuteOfDay) {
        int m = Math.min(23 * 60 + 59, Math.max(0, minuteOfDay));
        return LocalTime.of(m / 60, m % 60);
    }

    private void despawnJh(JhEntry e, String key) {
        Live live = liveByKey.remove(key);
        if (live == null) {
            return;
        }
        SceneWorld world = sceneManager.worldOrNull(live.resource);
        if (world != null && world.removeNpc(live.objectId) && listener != null) {
            listener.onDespawned(world, live.boss);
        }
    }

    /** 击杀回收：死亡的 BOSS 从场景移除，空出刷新位。 */
    private void reapDeadBosses() {
        for (Live live : new ArrayList<>(liveByKey.values())) {
            SceneWorld world = sceneManager.worldOrNull(live.resource);
            if (world == null) {
                liveByKey.remove(live.key);
                continue;
            }
            NpcInstance n = world.npcById(live.objectId);
            if (n == null) {
                liveByKey.remove(live.key);
                continue;
            }
            if (n.dead) {
                world.removeNpc(live.objectId);
                liveByKey.remove(live.key);
                if (listener != null) {
                    listener.onDefeated(world, n, "世界BOSS【" + bossName(n) + "】已被江湖群豪击败！");
                }
            }
        }
    }

    private void notifySpawned(SceneWorld world, NpcInstance boss, String resource) {
        log.info("world boss spawned: {} at {} ({},{},{})",
                boss.configId, world.sceneResource, boss.x, boss.y, boss.z);
        if (listener != null) {
            listener.onSpawned(world, boss,
                    "世界BOSS降世：【" + bossName(boss) + "】现身于 " + resource);
        }
    }

    private String bossName(NpcInstance boss) {
        NpcTemplate t = sceneManager.templates().lookup(boss.configId);
        return t != NpcTemplate.MISSING && !t.name().isBlank() ? t.name() : boss.configId;
    }

    private NpcInstance buildBoss(String configId, float x, float y, float z) {
        NpcInstance n = new NpcInstance();
        n.configId = configId;
        n.x = n.homeX = x;
        n.y = n.homeY = y;
        n.z = n.homeZ = z;
        n.monster = true;
        n.tag = TAG;
        n.refreshMs = -1;
        NpcTemplate t = sceneManager.templates().lookup(configId);
        n.maxHp = t.maxHp() > 0 ? t.maxHp() : 500000;
        n.hp = n.maxHp;
        n.attack = t.attack();
        n.defense = t.defense();
        n.accuracy = t.accuracy();
        n.evasion = t.evasion();
        return n;
    }

    // ---------- ini 解析（GBK，节内同名键多值） ----------

    private static Map<String, Map<String, List<String>>> iniSections(Path file) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            log.warn("world boss table missing: {}", file);
            return out;
        }
        try {
            Map<String, List<String>> section = null;
            String sectionId = "";
            for (String raw : new String(Files.readAllBytes(file), GBK).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                        || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    flushSection(out, sectionId, section);
                    sectionId = line.substring(1, line.length() - 1).trim();
                    section = new LinkedHashMap<>();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && section != null) {
                    section.computeIfAbsent(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                            k -> new ArrayList<>()).add(line.substring(eq + 1).trim());
                }
            }
            flushSection(out, sectionId, section);
        } catch (IOException e) {
            log.warn("world boss table read failed: {}", file, e);
        }
        return out;
    }

    private static void flushSection(Map<String, Map<String, List<String>>> out,
                                     String id, Map<String, List<String>> section) {
        if (section != null && !section.isEmpty()) {
            out.put(id, section);
        }
    }

    private static String firstOr(Map<String, List<String>> v, String key, String def) {
        List<String> list = v.get(key);
        return list != null && !list.isEmpty() ? list.get(0) : def;
    }

    private static int intOr(Map<String, List<String>> v, String key, int def) {
        try {
            return Integer.parseInt(firstOr(v, key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int[] intCsv(List<String> lines, int[] def) {
        if (lines == null) {
            return def;
        }
        List<Integer> out = new ArrayList<>();
        for (String line : lines) {
            for (String s : line.split(";")) {
                try {
                    out.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static float[] floatCsv3(String raw) {
        String[] parts = raw.split(",");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new float[]{Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    synchronized Map<String, NormalEntry> normalEntriesForTest() {
        return new LinkedHashMap<>(normalEntries);
    }

    synchronized Map<String, JhEntry> jhEntriesForTest() {
        return new LinkedHashMap<>(jhEntries);
    }

    synchronized Map<Integer, String> mapListForTest() {
        return new TreeMap<>(sceneNoToResource);
    }
}
