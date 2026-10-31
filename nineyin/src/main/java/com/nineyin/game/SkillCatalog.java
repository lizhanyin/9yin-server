package com.nineyin.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗技能编译目录（对照参考 exe 的 skill 编译链，证据串
 * `modern universal skill catalog ready indexed=%d executable_level1=%d skipped=%v action_files=%d`）。
 * 数据链：skill_new.ini（StaticData/script/AddCoolDownTime）
 * → skill_static.ini（CoolDownCategory/Team、Min/MaxVarPropNo、IsDamage、TaoLu）
 * → skill_normal_varprop.ini / skill_lock_varprop.ini（按等级取 Consume/DamageCalculate/
 *   HitShapePkg/CoolDownTime/AddCoolDownTime/HeightDiff）
 * → skill_consume.ini（AConsumeMP/SP/QGP）+ damage_calculate.ini（r 行第 3 段求和=基础伤害）
 * → attack_hitshape.ini（HitShape → 目标模式/半径/角度/宽度）
 * → ini/action/zhaoshi_player*.ini（数字键动作时间轴 → State 动作名与后续段）
 * → buff_new/buff_static/buff_varprop（buf_&lt;skillId&gt; 精确 Buff）。
 * 仅收录 script=SkillNormal/SkillLock 且全链可编译的技能；按 (id, level) 惰性编译并缓存。
 */
public final class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);
    private static final Charset GBK = Charset.forName("GBK");
    private static final long DEFAULT_BUFF_LEASE_MS = 24L * 60 * 60 * 1000;

    private static final String[] ACTION_FILES = {
            "zhaoshi_player.ini", "zhaoshi_player_2.ini",
            "zhaoshi_player_dodge.ini", "zhaoshi_player_parry.ini"};

    // ---------- 定义模型 ----------

    public enum TargetMode {
        SELF, SELECTED, CASTER_CIRCLE, CASTER_SECTOR, CASTER_RECTANGLE, SELECTED_AREA
    }

    public enum EffectKind {
        DAMAGE, PLAYER_BUFF, TARGET_CONTROL, RED_ARMOR, STANCE_CYCLE
    }

    /** zhaoshi 时间轴段：at 为相对起手的毫秒偏移。 */
    public record Segment(String action, long atMs, long durationMs) {
    }

    /** 资源表编译出的 Buff（buf_&lt;skillId&gt;）。 */
    public record BuffDef(String configId, int staticData, int level, long lifetimeMs, int preferredSlot) {
    }

    /** 一个技能效果：伤害 / 自身 Buff / 目标控制 / 红霸体 / 架势轮换。 */
    public record Effect(EffectKind kind, int amount, BuffDef buff, List<BuffDef> buffVariants) {

        static Effect damage(int amount) {
            return new Effect(EffectKind.DAMAGE, amount, null, null);
        }

        static Effect buff(BuffDef def) {
            return new Effect(EffectKind.PLAYER_BUFF, 0, def, null);
        }

        static Effect control(BuffDef def) {
            return new Effect(EffectKind.TARGET_CONTROL, 0, def, null);
        }

        static Effect stance(List<BuffDef> variants) {
            return new Effect(EffectKind.STANCE_CYCLE, 0, null, variants);
        }

        static Effect redArmor() {
            return new Effect(EffectKind.RED_ARMOR, 0, null, null);
        }
    }

    /** 编译产物：211 释放校验/计费/CD/动作与效果结算的全部权威参数。 */
    public record Definition(String id, int staticData, String script, String taoLu, int level,
                             int cooldownCategory, int cooldownTeam,
                             long personalCdMs, long publicCdMs,
                             int mpCost, int spCost, int qgCost, int baseDamage,
                             boolean requiresTarget, TargetMode targetMode,
                             float areaRadius, float areaHeight, float sectorAngle, float areaWidth,
                             float targetRangeMeters,
                             String actionName, long actionDurationMs,
                             List<Segment> followupActions, List<Effect> effects) {

        /** 起手 + 全部后续段的总时长（技能态清理/红霸体租约用）。 */
        public long totalActionDurationMs() {
            long total = actionDurationMs;
            for (Segment s : followupActions) {
                total = Math.max(total, s.atMs() + s.durationMs());
            }
            return total;
        }
    }

    /** 已实机验证的效果 overlay（exe 内嵌 buf_CS_wd_tjq04_1/2 等 ConfigID 佐证架势轮换仍在）。 */
    private record Overlay(long selfBuffLeaseMs, int preferredSlot, boolean redArmor, List<BuffDef> stance) {
    }

    private static final Map<String, Overlay> VERIFIED_OVERLAYS = buildOverlays();

    private static Map<String, Overlay> buildOverlays() {
        Map<String, Overlay> m = new LinkedHashMap<>();
        m.put("CS_yhwq_hsqs04", new Overlay(35_000L, PlayerActor.BUFF_SLOT_YIHUA, false, null));
        m.put("CS_yhwq_hsqs05", new Overlay(0, PlayerActor.BUFF_SLOT_YIHUA_05, true, null));
        List<BuffDef> stance = List.of(
                new BuffDef("buf_CS_wd_tjq04_1", 6013, 1, 24L * 60 * 60 * 1000, PlayerActor.BUFF_SLOT_TAIJI_STANCE),
                new BuffDef("buf_CS_wd_tjq04_2", 6055, 1, 24L * 60 * 60 * 1000, PlayerActor.BUFF_SLOT_TAIJI_STANCE));
        m.put("CS_wd_tjq04", new Overlay(0, PlayerActor.BUFF_SLOT_TAIJI_STANCE, false, stance));
        m.put("CS_wd_tjq05", new Overlay(0, PlayerActor.BUFF_SLOT_TAIJI_05, false, null));
        m.put("CS_wd_tjq07", new Overlay(0, PlayerActor.BUFF_SLOT_TAIJI_07, false, null));
        m.put("CS_wd_tjq08", new Overlay(0, 0, true, null));
        return Collections.unmodifiableMap(m);
    }

    // ---------- ini 载入 ----------

    /** 节 → 有序 key/value 对（保留重复键，查找取最后一次出现；键忽略大小写）。 */
    static Map<String, List<String[]>> loadIni(Path file) throws IOException {
        Map<String, List<String[]>> table = new LinkedHashMap<>();
        String data = new String(Files.readAllBytes(file), GBK);
        String section = "";
        for (String rawLine : data.split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                table.computeIfAbsent(section, k -> new ArrayList<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && !section.isEmpty()) {
                table.get(section).add(new String[]{
                        line.substring(0, eq).trim(), line.substring(eq + 1).trim()});
            }
        }
        return table;
    }

    private static String value(List<String[]> fields, String key) {
        if (fields == null) {
            return "";
        }
        String found = "";
        for (String[] f : fields) {
            if (f[0].equalsIgnoreCase(key)) {
                found = f[1];
            }
        }
        return found;
    }

    private static int intValue(List<String[]> fields, String key) {
        try {
            return (int) Long.parseLong(value(fields, key).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float floatValue(List<String[]> fields, String key) {
        try {
            return Float.parseFloat(value(fields, key).trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static String sectionKey(long id) {
        return Long.toString(id);
    }

    private final Map<String, List<String[]>> skillNew = new LinkedHashMap<>();
    private final Map<String, List<String[]>> skillStatic = new LinkedHashMap<>();
    private final Map<String, List<String[]>> normalVar = new LinkedHashMap<>();
    private final Map<String, List<String[]>> lockVar = new LinkedHashMap<>();
    private final Map<String, List<String[]>> consume = new LinkedHashMap<>();
    private final Map<String, List<String[]>> damage = new LinkedHashMap<>();
    private final Map<String, List<String[]>> hitShape = new LinkedHashMap<>();
    private final Map<String, List<String[]>> targetShape = new LinkedHashMap<>();
    private final Map<String, List<String[]>> actions = new LinkedHashMap<>();
    private final Map<String, List<String[]>> buffNew = new LinkedHashMap<>();
    private final Map<String, List<String[]>> buffStatic = new LinkedHashMap<>();
    private final Map<String, List<String[]>> buffVar = new LinkedHashMap<>();

    private final Map<String, Definition> levelOne = new TreeMap<>();
    private final Map<String, Boolean> indexed = new TreeMap<>();
    private final Map<String, Integer> skipReasons = new TreeMap<>();
    private final Map<String, Optional<Definition>> cache = new ConcurrentHashMap<>();
    private int useLimitPacks;

    /** 加载 modern 根（resources/modern）：share/skill/*.ini + ini/action/zhaoshi_player*.ini。 */
    public void load(Path modernRoot) {
        Path skillRoot = modernRoot.resolve("share").resolve("skill");
        Path actionRoot = modernRoot.resolve("ini").resolve("action");
        // 逐文件容错：缺表按空表处理（fixture/裁剪数据可用，实际语义缺失会在编译计数中体现）
        loadOptional(skillNew, skillRoot.resolve("skill_new.ini"), "skill_new.ini");
        loadOptional(skillStatic, skillRoot.resolve("skill_static.ini"), "skill_static.ini");
        loadOptional(normalVar, skillRoot.resolve("skill_normal_varprop.ini"), "skill_normal_varprop.ini");
        loadOptional(lockVar, skillRoot.resolve("skill_lock_varprop.ini"), "skill_lock_varprop.ini");
        loadOptional(consume, skillRoot.resolve("skill_consume.ini"), "skill_consume.ini");
        loadOptional(damage, skillRoot.resolve("damage_calculate.ini"), "damage_calculate.ini");
        loadOptional(hitShape, skillRoot.resolve("attack_hitshape.ini"), "attack_hitshape.ini");
        loadOptional(targetShape, skillRoot.resolve("attack_targetshape.ini"), "attack_targetshape.ini");
        loadOptional(buffNew, skillRoot.resolve("buff_new.ini"), "buff_new.ini");
        loadOptional(buffStatic, skillRoot.resolve("buff_static.ini"), "buff_static.ini");
        loadOptional(buffVar, skillRoot.resolve("buff_varprop.ini"), "buff_varprop.ini");
        if (skillNew.isEmpty()) {
            log.warn("skill resources unavailable under {}: skill_new.ini missing; catalog stays empty", skillRoot);
            return;
        }
        int actionFiles = 0;
        for (String name : ACTION_FILES) {
            try {
                actions.putAll(loadIni(actionRoot.resolve(name)));
                actionFiles++;
            } catch (IOException e) {
                log.warn("action file unavailable: {}", actionRoot.resolve(name));
            }
        }
        loadUseLimitPacks(skillRoot.resolve("skill_use_limit.ini"));

        List<String> ids = new ArrayList<>(skillNew.keySet());
        ids.sort(Comparator.naturalOrder());
        for (String id : ids) {
            String script = value(skillNew.get(id), "script");
            if (!script.equalsIgnoreCase("SkillNormal") && !script.equalsIgnoreCase("SkillLock")) {
                skipReasons.merge("unsupported_script", 1, Integer::sum);
                continue;
            }
            indexed.put(id, Boolean.TRUE);
            try {
                Definition def = compile(id, 1);
                levelOne.put(id, def);
                cache.put(cacheKey(id, 1), Optional.of(def));
            } catch (CompileError e) {
                skipReasons.merge(reasonOf(e.getMessage()), 1, Integer::sum);
            }
        }
        log.info("modern universal skill catalog ready indexed={} executable_level1={} skipped={} action_files={}",
                indexed.size(), levelOne.size(), skipReasons, actionFiles);
    }

    private void loadOptional(Map<String, List<String[]>> target, Path file, String name) {
        try {
            target.putAll(loadIni(file));
        } catch (IOException e) {
            log.warn("skill resource {} unavailable ({}); treated as empty", name, e.toString());
        }
    }

    /** skill_use_limit.ini 包表（武器使用限制）：本轮仅装载计数对齐日志，按技能的绑定待钉死。 */
    private void loadUseLimitPacks(Path file) {
        if (!Files.isRegularFile(file)) {
            log.warn("skill_use_limit unavailable ({}); weapon UseLimit gates disabled", file);
            return;
        }
        try {
            useLimitPacks = loadIni(file).size();
            log.info("skill_use_limit ready packs={}", useLimitPacks);
        } catch (IOException e) {
            log.warn("skill_use_limit unavailable ({}); weapon UseLimit gates disabled", e.toString());
        }
    }

    public int useLimitPacks() {
        return useLimitPacks;
    }

    // ---------- 编译链 ----------

    private static final class CompileError extends RuntimeException {
        CompileError(String message) {
            super(message);
        }
    }

    private static String reasonOf(String message) {
        if (message.contains("unsupported script")) {
            return "unsupported_script";
        }
        if (message.contains("no StaticData")) {
            return "missing_static_id";
        }
        if (message.contains("no skill_static")) {
            return "missing_static";
        }
        if (message.contains("varprop")) {
            return "missing_level_varprop";
        }
        if (message.contains("action timeline")) {
            return "missing_player_action";
        }
        return "other";
    }

    /** (id, level) → 定义；未收录/不可编译返回 null。 */
    public Definition definition(String id, int level) {
        if (id == null || level < 1) {
            return null;
        }
        Optional<Definition> cached = cache.get(cacheKey(id, level));
        if (cached != null) {
            return cached.orElse(null);
        }
        Definition def = null;
        if (indexed.containsKey(id)) {
            try {
                def = compile(id, level);
            } catch (CompileError e) {
                log.debug("compile skill {} level={} failed: {}", id, level, e.getMessage());
            }
        }
        cache.put(cacheKey(id, level), Optional.ofNullable(def));
        return def;
    }

    private static String cacheKey(String id, int level) {
        return id + "#" + level;
    }

    public int indexedCount() {
        return indexed.size();
    }

    public int levelOneCount() {
        return levelOne.size();
    }

    public Map<String, Integer> skipReasons() {
        return Collections.unmodifiableMap(skipReasons);
    }

    private Definition compile(String id, int level) {
        if (level < 1) {
            throw new CompileError(id + " has invalid level " + level);
        }
        List<String[]> newFields = skillNew.get(id);
        int staticData = intValue(newFields, "StaticData");
        if (staticData <= 0) {
            throw new CompileError(id + " has no StaticData");
        }
        String script = value(newFields, "script");
        if (!script.equalsIgnoreCase("SkillNormal") && !script.equalsIgnoreCase("SkillLock")) {
            throw new CompileError(id + " uses unsupported script \"" + script + "\"");
        }
        List<String[]> staticFields = skillStatic.get(sectionKey(staticData));
        if (staticFields == null || staticFields.isEmpty()) {
            throw new CompileError(id + " has no skill_static section " + staticData);
        }
        List<String[]> varProp = levelVarProp(staticFields, script, level);
        if (varProp == null || varProp.isEmpty()) {
            throw new CompileError(id + " has no level-" + level + " varprop");
        }
        List<String[]> consumeFields = consume.get(sectionKey(intValue(varProp, "Consume")));
        int baseDamage = compileDamage(damage.get(sectionKey(intValue(varProp, "DamageCalculate"))));
        boolean isDamage = intValue(staticFields, "IsDamage") != 0;
        List<String[]> hitShapeFields = hitShape.get(sectionKey(intValue(varProp, "HitShapePkg")));
        TargetShape shape = compileTargetMode(hitShapeFields, isDamage);
        float targetRange = compileTargetRange(
                targetShape.get(sectionKey(intValue(varProp, "TargetShapePkg"))));
        Timeline timeline = compileActions(actions.get(id));
        if (timeline.actionName().isEmpty()) {
            throw new CompileError(id + " has no player action timeline");
        }
        long publicCd = intValue(varProp, "AddCoolDownTime");
        if (publicCd <= 0) {
            publicCd = intValue(newFields, "AddCoolDownTime");
        }
        long personalCd = intValue(varProp, "CoolDownTime");
        if (publicCd <= 0) {
            publicCd = personalCd;
        }
        List<Effect> effects = new ArrayList<>(3);
        if (baseDamage > 0) {
            effects.add(Effect.damage(baseDamage));
        }
        BuffDef exact = compileExactBuff(id, level);
        if (exact != null) {
            Overlay overlay = VERIFIED_OVERLAYS.get(id);
            if (overlay != null && overlay.selfBuffLeaseMs() > 0) {
                exact = new BuffDef(exact.configId(), exact.staticData(), exact.level(),
                        overlay.selfBuffLeaseMs(), overlay.preferredSlot());
            } else if (overlay != null) {
                exact = new BuffDef(exact.configId(), exact.staticData(), exact.level(),
                        exact.lifetimeMs(), overlay.preferredSlot());
            }
            boolean harmful = intValue(buffStatic.get(sectionKey(exact.staticData())), "IsDamage") != 0;
            effects.add(harmful ? Effect.control(exact) : Effect.buff(exact));
        }
        Overlay overlay = VERIFIED_OVERLAYS.get(id);
        if (overlay != null && overlay.stance() != null) {
            effects.add(Effect.stance(overlay.stance()));
        }
        if (overlay != null && overlay.redArmor()) {
            effects.add(Effect.redArmor());
        }
        return new Definition(id, staticData, script, value(staticFields, "TaoLu"), level,
                intValue(staticFields, "CoolDownCategory"), intValue(staticFields, "CoolDownTeam"),
                personalCd, publicCd,
                intValue(consumeFields, "AConsumeMP"), intValue(consumeFields, "AConsumeSP"),
                intValue(consumeFields, "AConsumeQGP"), baseDamage,
                shape.mode() == TargetMode.SELECTED || shape.mode() == TargetMode.SELECTED_AREA,
                shape.mode(), shape.radius(), floatValue(varProp, "HeightDiff"),
                shape.sectorAngle(), shape.areaWidth(), targetRange,
                timeline.actionName(), timeline.durationMs(),
                timeline.followups(), Collections.unmodifiableList(effects));
    }

    private List<String[]> levelVarProp(List<String[]> staticFields, String script, int level) {
        Map<String, List<String[]>> table = script.equalsIgnoreCase("SkillLock") ? lockVar : normalVar;
        int min = intValue(staticFields, "MinVarPropNo");
        int max = intValue(staticFields, "MaxVarPropNo");
        for (int id = min; id <= max && id > 0; id++) {
            List<String[]> fields = table.get(sectionKey(id));
            if (intValue(fields, "Level") == level) {
                return fields;
            }
        }
        return null;
    }

    /** damage_calculate.ini：r=494,JujDamage,113,1,1 → 各 r 行取第 3 段正值求和为基础伤害。 */
    private static int compileDamage(List<String[]> fields) {
        if (fields == null) {
            return 0;
        }
        double total = 0;
        for (String[] f : fields) {
            if (!f[0].equalsIgnoreCase("r")) {
                continue;
            }
            String[] parts = f[1].split(",");
            if (parts.length < 3) {
                continue;
            }
            try {
                double v = Double.parseDouble(parts[2].trim());
                if (v > 0) {
                    total += v;
                }
            } catch (NumberFormatException ignore) {
                // 与参考实现一致：非数值段跳过
            }
        }
        return (int) Math.round(total);
    }

    private record TargetShape(TargetMode mode, float radius, float sectorAngle, float areaWidth) {
    }

    /** attack_hitshape.ini：HitShape 1=圆形、2/11=目标区域、3/9/10=扇形、4/12=矩形，其余=单体。 */
    private static TargetShape compileTargetMode(List<String[]> fields, boolean isDamage) {
        if (!isDamage) {
            return new TargetShape(TargetMode.SELF, 0, 0, 0);
        }
        float para1 = floatValue(fields, "HitShapePara1");
        float para2 = floatValue(fields, "HitShapePara2");
        return switch (intValue(fields, "HitShape")) {
            case 1 -> new TargetShape(TargetMode.CASTER_CIRCLE, para2, 0, 0);
            case 2, 11 -> new TargetShape(TargetMode.SELECTED_AREA, para2, 0, 0);
            case 3, 9, 10 -> new TargetShape(TargetMode.CASTER_SECTOR, para2, para1, 0);
            case 4, 12 -> new TargetShape(TargetMode.CASTER_RECTANGLE, para2, 0, para1);
            default -> new TargetShape(TargetMode.SELECTED, 0, 0, 0);
        };
    }

    /**
     * attack_targetshape.ini：TargetShape=1（以自己为圆心半径 N 米选取目标）时
     * TargetShapePara2 即施法射程（参考服 range gate 的 max 值来源：3/4/6/8/15/20/25 米）；
     * 0=非远程（以自己为目标）与其余形状暂不设门（返回 0 不校验）。
     */
    private static float compileTargetRange(List<String[]> fields) {
        if (fields == null || intValue(fields, "TargetShape") != 1) {
            return 0f;
        }
        return Math.max(0f, floatValue(fields, "TargetShapePara2"));
    }

    private record Timeline(String actionName, long durationMs, List<Segment> followups) {
    }

    /**
     * zhaoshi_player*.ini：数字键 = 顺序，值 "action;?;endFrame;inBlend;..."（30fps）。
     * 首段为起手 State 动作，其余按 前段时长 − 入融 叠加排期（参考实现同式）。
     */
    private static Timeline compileActions(List<String[]> fields) {
        if (fields == null) {
            return new Timeline("", 0, List.of());
        }
        record Raw(int order, String action, double endFrame, double inBlend) {
        }
        List<Raw> raw = new ArrayList<>(3);
        for (String[] f : fields) {
            int order;
            try {
                order = Integer.parseInt(f[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            String[] parts = f[1].split(";");
            if (parts.length < 5 || parts[0].trim().isEmpty()) {
                continue;
            }
            double endFrame = parseDoubleOr(parts[2], 0);
            double inBlend = parseDoubleOr(parts[3], 0);
            raw.add(new Raw(order, parts[0].trim(), endFrame, inBlend));
        }
        raw.sort(Comparator.comparingInt(Raw::order));
        if (raw.isEmpty()) {
            return new Timeline("", 0, List.of());
        }
        long duration = frameMs(raw.get(0).endFrame());
        long at = 0;
        List<Segment> followups = new ArrayList<>(raw.size() - 1);
        for (int i = 1; i < raw.size(); i++) {
            at += frameMs(raw.get(i - 1).endFrame()) - (long) Math.max(raw.get(i).inBlend(), 0) * 1000L;
            followups.add(new Segment(raw.get(i).action(), at, frameMs(raw.get(i).endFrame())));
        }
        return new Timeline(raw.get(0).action(), duration, Collections.unmodifiableList(followups));
    }

    private static long frameMs(double frames) {
        return (long) (frames / 30.0 * 1000.0);
    }

    private static double parseDoubleOr(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    /** buf_&lt;skillId&gt; 精确 Buff：buff_new StaticData/LifeTime + 同级 buff_varprop LifeTime 覆盖。 */
    private BuffDef compileExactBuff(String skillId, int level) {
        List<String[]> buffNewFields = buffNew.get("buf_" + skillId);
        int staticData = intValue(buffNewFields, "StaticData");
        if (staticData <= 0) {
            return null;
        }
        List<String[]> staticFields = buffStatic.get(sectionKey(staticData));
        long lifetime = intValue(buffNewFields, "LifeTime");
        int min = intValue(staticFields, "MinVarPropNo");
        int max = intValue(staticFields, "MaxVarPropNo");
        for (int id = min; id <= max && id > 0; id++) {
            List<String[]> fields = buffVar.get(sectionKey(id));
            if (intValue(fields, "Level") != level) {
                continue;
            }
            int ms = intValue(fields, "LifeTime");
            if (ms > 0) {
                lifetime = ms;
            }
            break;
        }
        if (lifetime <= 0) {
            lifetime = DEFAULT_BUFF_LEASE_MS;
        }
        return new BuffDef("buf_" + skillId, staticData, level, lifetime, 0);
    }
}
