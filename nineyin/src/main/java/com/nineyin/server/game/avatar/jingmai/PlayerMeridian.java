package com.nineyin.server.game.avatar.jingmai;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家经脉修炼状态机（修炼经脉/修炼周天/修炼进度/经脉总等级四件套的权威源）。
 *
 * <p>对齐 PlayerProgress.advanceFaculty 的整分钟结算模型：每分钟给当前修炼经脉
 * 结算 {@link MeridianCatalog#zhenQiPerMinute} 真气；战斗伤害按
 * zhenqi.ini damage_ratio=0.1 即时折算真气（同一 CurFillValue 池）。
 * 真气满 {@link MeridianCatalog#TOTAL_FILL_PER_LEVEL} → 周天(Level)+1、
 * JingMaiTotalLevel=ΣLevel。今日额度 ZQPLValue 只做展示累计（私服不设硬停——
 * 官方 ZhenQiDayValue=500/日 的硬闸在私服节奏下近乎不可测，先放宽后续可再收紧）。
 *
 * <p>显示契约（客户端 form_main_player.set_jingmaiinfo_tips / F 盘 52795 抓包）：
 * 修炼经脉=玩家属性 CurJingMai；修炼周天=View45 行 Level；修炼进度=View45 行
 * CurFillValue/TotalFillValue；经脉总等级=玩家属性 JingMaiTotalLevel。
 */
public final class PlayerMeridian {

    /** 单条已学经脉的修炼状态。 */
    public static final class Meridian {
        final String configId;
        int level;
        int fill;

        Meridian(String configId, int level, int fill) {
            this.configId = configId;
            this.level = level;
            this.fill = fill;
        }

        public String configId() {
            return configId;
        }

        public int level() {
            return level;
        }

        public int fill() {
            return fill;
        }
    }

    /** 一秒 tick 的产物：哪些行变化/是否升级（调用方据此发帧）。 */
    public record TickResult(boolean changed, List<String> leveledUp, boolean dayRolled) {
        static final TickResult NONE = new TickResult(false, List.of(), false);
    }

    /** 持久化契约（MeridianStore JSON/DB 同构）。 */
    public record Snapshot(String curJingMai, String lastJingMai, String plDay, int plValue,
                           boolean capEnabled, int capValue,
                           List<Meridian> meridians) {
    }

    private final MeridianCatalog catalog;
    private final Clock clock;
    /** 已学经脉（授予序 = View45 行序，F 盘抓包同序）。 */
    private final LinkedHashMap<String, Meridian> meridians = new LinkedHashMap<>();
    private String curJingMai = "";
    private String lastJingMai = "";
    /** 今日已获真气（ZQPLValue；UTC 日切）。 */
    private String plDay = "";
    private int plValue;
    /**
     * 每日真气硬闸（GM set_zhenqi_cap）：开启后当日已获真气达到 {@link #capValue}
     * 即停止入账（被动/战斗同闸）；ZhenQiDayValue 属性随开关显示 capValue。
     * 默认关闭（私服节奏，见 docs/jingmai-cd-qinggong.md 遗留项）。
     */
    private boolean capEnabled;
    private int capValue = JingMaiMessages.ZHENQI_DAY_VALUE;
    private Instant lastAdvance;

    public static PlayerMeridian create(MeridianCatalog catalog, Clock clock) {
        PlayerMeridian state = new PlayerMeridian(catalog, clock);
        for (String configId : MeridianCatalog.STARTER_MERIDIANS) {
            MeridianCatalog.Definition definition = catalog.definition(configId);
            if (definition != null) {
                state.meridians.put(configId, new Meridian(configId, 1, 0));
            }
        }
        return state;
    }

    private PlayerMeridian(MeridianCatalog catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询

    public String curJingMai() {
        return curJingMai;
    }

    public String lastJingMai() {
        return lastJingMai;
    }

    /** 经脉总等级 = ΣLevel（玩家属性 JingMaiTotalLevel）。 */
    public int totalLevel() {
        int total = 0;
        for (Meridian meridian : meridians.values()) {
            total += meridian.level;
        }
        return total;
    }

    public Meridian meridian(String configId) {
        return configId == null ? null : meridians.get(configId);
    }

    public List<Meridian> learned() {
        return new ArrayList<>(meridians.values());
    }

    /** View45 行号（1 起，与 0x18 viewAdd 的 objectIndex 一致）。 */
    public int rowOf(String configId) {
        int row = 0;
        for (String key : meridians.keySet()) {
            row++;
            if (key.equals(configId)) {
                return row;
            }
        }
        return 0;
    }

    public int plValue() {
        rollDayIfNeeded(clock.instant());
        return plValue;
    }

    public int totalFill(String configId) {
        return catalog.totalFillPerLevel(configId);
    }

    // ------------------------------------------------------------------ 每日硬闸（GM set_zhenqi_cap）

    public boolean capEnabled() {
        return capEnabled;
    }

    public int capValue() {
        return capValue;
    }

    /** 玩家属性 ZhenQiDayValue 口径：开闸显示阈值，关闸回落官方缺省 500。 */
    public int zhenQiDayValue() {
        return capEnabled ? capValue : JingMaiMessages.ZHENQI_DAY_VALUE;
    }

    /** GM 设置是否开启 + 每日阈值（阈值 ≥1；关闭时阈值仍保存，重开沿用）。 */
    public void setDailyCap(boolean enabled, int value) {
        if (value >= 1) {
            capValue = value;
        }
        capEnabled = enabled;
    }

    /**
     * GM set_jingmai_tier：直设周天等级（钳 1..目录上限；fill 归零走新周天起点）。
     * 返回受理前旧等级；未学该经脉返回 -1（调用方先校验目录收录）。
     */
    public int setLevel(String configId, int level) {
        Meridian meridian = configId == null ? null : meridians.get(configId);
        if (meridian == null) {
            return -1;
        }
        MeridianCatalog.Definition definition = catalog.definition(configId);
        int maxLevel = definition == null ? 0 : definition.maxLevel();
        int target = maxLevel > 0 ? Math.clamp(level, 1, maxLevel) : Math.max(1, level);
        int old = meridian.level;
        meridian.level = target;
        meridian.fill = 0;
        return old;
    }

    // ------------------------------------------------------------------ 修炼

    /**
     * 设置/停止修炼（C2S 150 sub=1）。空串=停止；未学经脉拒绝（静默，调用方记日志）。
     * 返回是否受理（状态变化或幂等重复设置同一条）。
     */
    public boolean setCultivating(String configId) {
        if (configId != null && !configId.isEmpty()) {
            if (!meridians.containsKey(configId)) {
                return false;
            }
            if (configId.equals(curJingMai)) {
                return true; // 幂等
            }
            if (!curJingMai.isEmpty()) {
                lastJingMai = curJingMai;
            }
            curJingMai = configId;
            lastAdvance = clock.instant();
            return true;
        }
        // 停止修炼：当前经脉转 LastJingMai
        if (!curJingMai.isEmpty()) {
            lastJingMai = curJingMai;
            curJingMai = "";
        }
        return true;
    }

    /**
     * 1s ticker 推进（facultyTicker 同拍）：整分钟结算真气、溢出升级。
     * 未修炼/未满整分钟零帧（TickResult.NONE）。
     */
    public TickResult tick() {
        if (curJingMai.isEmpty()) {
            return TickResult.NONE;
        }
        Instant now = clock.instant();
        if (lastAdvance == null) {
            lastAdvance = now;
            return TickResult.NONE;
        }
        long elapsed = java.time.Duration.between(lastAdvance, now).toMillis();
        if (elapsed < 60_000) {
            return TickResult.NONE;
        }
        long minutes = elapsed / 60_000;
        lastAdvance = now.minusMillis(elapsed % 60_000);
        return applyZhenQi(curJingMai, (int) Math.min(minutes * catalog.zhenQiPerMinute(curJingMai),
                Integer.MAX_VALUE));
    }

    /** 战斗真气（SkillCastHandler 每命中调用；damage×damage_ratio，向下取整≥1）。 */
    public TickResult addCombatZhenQi(int damage) {
        if (damage <= 0 || curJingMai.isEmpty()) {
            return TickResult.NONE;
        }
        long gain = Math.round(damage * MeridianCatalog.COMBAT_ZHENQI_RATIO);
        return applyZhenQi(curJingMai, (int) Math.max(1, Math.min(gain, Integer.MAX_VALUE)));
    }

    /** 真气入账（溢出连升，封顶 maxLevel）+今日额度累计；硬闸开启时按剩余额度截断。 */
    private TickResult applyZhenQi(String configId, int amount) {
        if (amount <= 0) {
            return TickResult.NONE;
        }
        Meridian meridian = meridians.get(configId);
        if (meridian == null) {
            return TickResult.NONE;
        }
        Instant now = clock.instant();
        boolean dayRolled = rollDayIfNeeded(now);
        if (capEnabled) {
            int headroom = Math.max(0, capValue - plValue);
            if (headroom <= 0) {
                return TickResult.NONE; // 当日额度已用尽：硬闸拦截（零帧静默）
            }
            amount = Math.min(amount, headroom);
        }
        plValue = Math.addExact(plValue, Math.min(amount, Integer.MAX_VALUE - plValue));
        int total = catalog.totalFillPerLevel(configId);
        List<String> leveledUp = new ArrayList<>(1);
        meridian.fill += amount;
        while (total > 0 && meridian.fill >= total
                && (catalog.definition(configId) == null
                        || meridian.level < catalog.definition(configId).maxLevel())) {
            meridian.fill -= total;
            meridian.level++;
            leveledUp.add(configId);
        }
        if (total > 0 && meridian.fill >= total) {
            meridian.fill = total; // 满级封顶
        }
        return new TickResult(true, leveledUp, dayRolled);
    }

    /** UTC 日切（ZhenQiDayLast/ZQPLValue 语义：新的一天清零今日已获）。 */
    private boolean rollDayIfNeeded(Instant now) {
        String today = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        if (today.equals(plDay)) {
            return false;
        }
        plDay = today;
        plValue = 0;
        return true;
    }

    // ------------------------------------------------------------------ 存档

    public Snapshot snapshot() {
        return new Snapshot(curJingMai, lastJingMai, plDay, plValue, capEnabled, capValue, learned());
    }

    /** 登录恢复（缺省条目以等级 1 补齐，保证 View45 行集稳定）。 */
    public void restore(Snapshot snapshot, Instant now) {
        curJingMai = "";
        lastJingMai = "";
        meridians.clear();
        capEnabled = false;
        capValue = JingMaiMessages.ZHENQI_DAY_VALUE;
        if (snapshot != null) {
            for (Meridian meridian : snapshot.meridians()) {
                MeridianCatalog.Definition definition = catalog.definition(meridian.configId());
                if (definition == null) {
                    continue;
                }
                int maxLevel = Math.max(1, definition.maxLevel());
                meridians.put(meridian.configId(), new Meridian(meridian.configId(),
                        Math.clamp(meridian.level, 1, maxLevel), Math.max(0, meridian.fill())));
            }
            lastJingMai = snapshot.lastJingMai() == null ? "" : snapshot.lastJingMai();
            String saved = snapshot.curJingMai();
            if (saved != null && !saved.isEmpty() && meridians.containsKey(saved)) {
                curJingMai = saved;
            }
            plDay = snapshot.plDay() == null ? "" : snapshot.plDay();
            plValue = Math.max(0, snapshot.plValue());
            if (snapshot.capValue() >= 1) {
                capValue = snapshot.capValue();
            }
            capEnabled = snapshot.capEnabled();
        }
        for (String configId : MeridianCatalog.STARTER_MERIDIANS) {
            if (catalog.definition(configId) != null) {
                meridians.putIfAbsent(configId, new Meridian(configId, 1, 0));
            }
        }
        lastAdvance = now;
    }
}
