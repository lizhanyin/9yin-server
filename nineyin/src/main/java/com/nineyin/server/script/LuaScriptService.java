package com.nineyin.server.script;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.lua55.Lua55;

import com.nineyin.server.game.item.DropPacks;
import com.nineyin.util.ServerPaths;

/**
 * Lua 脚本引擎接线（scripts/ 目录，与 share 同法经 {@link ServerPaths} 定位）。
 *
 * <p>契约见 docs/skill-script-framework.md：scripts/combat/formula.lua 的 M.calc(c)
 * 为伤害公式权威实现（Java 兜底 {@link CombatFormulas} 同常量同掷骰顺序）；
 * scripts/skill/&lt;技能ID&gt;.lua 的 M.compile(def) 返回编译期增量覆盖
 * （self_buff_lease_ms/self_buff_slot 等，缺省沿用 INI 编译效果），
 * M.on_hit(ctx) 为出招运行期倍率钩子（返回 {ratio=...}，每调用 Java 预掷 1 枚）；
 * scripts/skill/effects_data.lua 为全技能效果汇总表（生成器产出，
 * {@link #skillOverlay} 对无逐技能脚本的技能按 by_level 差集取值）；
 * scripts/neigong/&lt;buffId&gt;.lua 的 M.on_attack/on_victim/on_parry/on_periodic/on_tick(ctx)
 * 为内功特效运行期钩子（每次事件调用，返回指令白名单解析，坏脚本按脚本隔离禁用）。
 *
 * <p>降级方言：natives 加载失败（LinkageError）/脚本文件缺失/执行异常 → 伤害公式回退
 * Java、技能覆盖返回 null，服务端不因脚本层不可用而拒绝出招。
 * 单 Lua 状态 + 全局锁（脚本是纯函数，无并发语义需求）；
 * {@link #reload()} 重建状态实现 GM 热重载。
 */
public final class LuaScriptService {

    private static final Logger LOG = Logger.getLogger(LuaScriptService.class.getName());

    private static final LuaScriptService INSTANCE = new LuaScriptService();

    public static LuaScriptService getInstance() {
        return INSTANCE;
    }

    /** 技能脚本 compile 覆盖（键面即当前契约；null 字段 = 不覆盖）。 */
    public record SkillOverlay(Long selfBuffLeaseMs, Integer selfBuffSlot,
                                Boolean redArmor, java.util.List<StanceVariant> stanceCycle,
                                Integer damageAmount, Integer damageWaves,
                                java.util.List<SelfBuff> selfBuffs,
                                java.util.List<TargetEffect> targetEffects) {

        boolean isEmpty() {
            return selfBuffLeaseMs == null && selfBuffSlot == null && redArmor == null
                    && (stanceCycle == null || stanceCycle.isEmpty())
                    && damageAmount == null && damageWaves == null
                    && (selfBuffs == null || selfBuffs.isEmpty())
                    && (targetEffects == null || targetEffects.isEmpty());
        }
    }

    /** self_buffs[] 项：多自 buff（config_id/static_data/level/life_ms/slot，slot=0 自动找槽）。 */
    public record SelfBuff(String configId, long staticData, int level, long lifeMs, int slot) {
    }

    /**
     * target_effects[] 项（命中目标效果）：control=true 控制到租约终点（有害精确
     * buff 同语义）；frame=true 加发 msg169 目标 debuff 帧（默认关）。
     */
    public record TargetEffect(String configId, long staticData, int level, long lifeMs,
                               boolean control, boolean frame) {
    }

    /** stance_cycle.variants[] 项：架势轮换双态（如太极拳四式 守6013/攻6055）。 */
    public record StanceVariant(String id, long staticData, int level, long lifeMs, int slot) {
    }

    /** M.compile(def) 的 def 输入（INI 编译出的 buf_&lt;技能ID&gt; 精确 buff 快照）。 */
    public record ExactBuff(String configId, long staticData, int level, long lifetimeMs) {
    }

    /** M.on_use(ctx) 的 ctx 输入快照（随机数 Java 预掷：rand.r_drop/r_proc）。 */
    public record ItemUseContext(int amount, long now, int hp, int maxHp, int mp, int maxMp,
                                 long silver, double rDrop, double rProc) {
    }

    /** 限时属性指令（同 configId 源替换，life_ms &gt; 0；面板限时属性未接，先解析占位）。 */
    public record TempStat(int attackAdd, int defenseAdd, int maxHpAdd, long lifeMs) {
    }

    /**
     * M.on_use 输出指令（字段白名单 + 强校验，非法条目丢弃 + WARNING）：
     * heal_hp/heal_mp 回资源、open_drop_id+rolls 按 drops_mvp.json 权重开包
     * （Java 解析）、grants 确定性给物品、temp_stat 限时属性、consume 是否消耗 1 个。
     */
    public record ItemUse(int healHp, int healMp, String openDropId, int rolls,
                          List<DropPacks.GrantItem> grants, TempStat tempStat, boolean consume) {

        boolean isEmpty() {
            return healHp <= 0 && healMp <= 0 && openDropId == null
                    && (grants == null || grants.isEmpty()) && tempStat == null;
        }
    }

    /**
     * 内功运行期钩子 M.on_attack/on_victim/on_parry/on_periodic/on_tick(ctx) 的 ctx 快照
     * （scripts/neigong/&lt;buffId&gt;.lua；rand.r_proc Java 预掷同 formula.lua 约定）。
     * self/target/attacker 为嵌套表：self={hp,max_hp,mp,max_mp,attack}、
     * target/attacker={oid,hp,max_hp}（语料实际使用字段集）。
     * state 为空（首次触发尚无回喂）时 ctx 不设该键——Lua 读 nil，脚本须 ctx.state or {} 兜底。
     */
    public record NeiGongCtx(int bookLevel, int bookMaxLevel, int storyStage, long nowMs,
                             double rProc, Map<String, Object> state,
                             int damage, int incoming, boolean parried, boolean crit,
                             long sinceLastMs, int selfHp, int selfMaxHp, int selfMp, int selfMaxMp,
                             int selfAttack, int targetOid, int targetHp, int targetMaxHp,
                             int attackerOid) {
    }

    /**
     * 内功钩子返回指令（白名单解析，非法条目 WARN 丢弃）：extra_damage{amount,kind}
     * 附伤（kind 现管线统一外功，仅透传）、reduce_pct 比例减免（0..1）、
     * shield{amount,life_ms} 护盾、heal/reflect/mp_restore 回血/反弹/回蓝、
     * state 扁平表（跨调用由 Java 持久，内存态）、parry_reduce{internal,external}
     * （当前管线格挡零伤，解析占位）。
     */
    public record NeiGongResult(int extraDamage, String extraDamageKind, double reducePct,
                                int shieldAmount, long shieldLifeMs, int heal, int reflect,
                                int mpRestore, Map<String, Object> state,
                                int parryReduceInternal, int parryReduceExternal) {

        /** 全空指令（nil 语义）——分发器据此不推进 since_last_ms 节拍。 */
        public boolean isEmpty() {
            return extraDamage <= 0 && reducePct <= 0 && shieldAmount <= 0 && heal <= 0
                    && reflect <= 0 && mpRestore <= 0
                    && (state == null || state.isEmpty());
        }
    }

    private final Object lock = new Object();
    private Lua lua;
    private boolean broken;
    private String brokenReason = "";
    private final Map<String, SkillOverlay> overlayCache = new ConcurrentHashMap<>();
    private final Map<String, ItemUse> itemUseCache = new ConcurrentHashMap<>();
    /** 内功钩子存在性缓存（buffId@hook → 模块已装载且钩子为函数）。 */
    private final Map<String, Boolean> neigongHookCache = new ConcurrentHashMap<>();
    /** 坏脚本禁用集（运行期异常按脚本隔离，不 markBroken 整个引擎）。 */
    private final java.util.Set<String> neigongBroken = ConcurrentHashMap.newKeySet();
    /** 技能 on_hit 钩子存在性缓存（skillId@hook → 模块已装载且钩子为函数）。 */
    private final Map<String, Boolean> skillHookCache = new ConcurrentHashMap<>();
    /** 技能脚本禁用集（on_hit 运行期异常按脚本隔离）。 */
    private final java.util.Set<String> skillBroken = ConcurrentHashMap.newKeySet();

    private LuaScriptService() {
        synchronized (lock) {
            initLocked();
        }
    }

    public boolean luaAvailable() {
        synchronized (lock) {
            return lua != null && !broken;
        }
    }

    /** 伤害公式：Lua 权威，异常/不可用回退 {@link CombatFormulas#calc}。 */
    public CombatFormulas.Result calc(CombatFormulas.Input input) {
        synchronized (lock) {
            if (lua != null && !broken) {
                try {
                    CombatFormulas.Result result = calcLocked(input);
                    if (result != null) {
                        return result;
                    }
                } catch (RuntimeException e) {
                    markBroken("formula calc: " + e);
                }
            }
        }
        return CombatFormulas.calc(input);
    }

    /**
     * 技能编译覆盖。解析优先级：逐技能 scripts/skill/&lt;ID&gt;.lua 的 M.compile →
     * 汇总表 effects_data.lua 条目（by_level[level] 差集修补）→ null（沿用 INI 默认）。
     * 无脚本/引擎不可用 → null；缓存键 id@level（伤害/租约随级变，必须分级缓存）。
     */
    public SkillOverlay skillOverlay(String skillId, int level, ExactBuff exactBuff) {
        if (skillId == null || skillId.isEmpty() || level < 1) {
            return null;
        }
        String cacheKey = skillId + "@" + level;
        SkillOverlay cached = overlayCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            cached = overlayCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            if (lua == null || broken) {
                return null;
            }
            Path script = scriptPath("skill", skillId + ".lua");
            if (Files.isRegularFile(script)) {
                String global = "SkillModule_" + skillId.replaceAll("[^A-Za-z0-9_]", "_");
                try {
                    if (!runModuleLocked(script, global)) {
                        overlayCache.put(cacheKey, NONE);
                        return NONE;
                    }
                    SkillOverlay overlay = callCompileLocked(global, exactBuff);
                    overlayCache.put(cacheKey, overlay);
                    return overlay;
                } catch (RuntimeException e) {
                    markBroken("skill overlay " + skillId + ": " + e);
                    return null;
                }
            }
            // 无逐技能脚本 → 汇总表条目（生成器产出；条目级异常 WARN 降级 INI，不动公式引擎）
            try {
                SkillOverlay fromData = dataOverlayLocked(skillId, level);
                SkillOverlay result = fromData == null || fromData.isEmpty() ? NONE : fromData;
                overlayCache.put(cacheKey, result);
                return result;
            } catch (RuntimeException e) {
                LOG.warning("effects_data entry " + skillId + "@" + level
                        + " unreadable, falls back to INI compile: " + e);
                overlayCache.put(cacheKey, NONE);
                return NONE;
            }
        }
    }

    /**
     * 道具使用效果（scripts/item/&lt;configId&gt;.lua 的 M.on_use(ctx)）：
     * 返回 null = 无脚本 / on_use 返回 nil / 引擎不可用 → 调用方走 INI 数据回退
     * （ItemUseCatalog）；非空即脚本指令（可能全空效果 + consume，由调用方裁决）。
     */
    public ItemUse itemUse(String configId, ItemUseContext ctx) {
        if (configId == null || configId.isEmpty()) {
            return null;
        }
        ItemUse cached = itemUseCache.get(configId);
        if (cached != null) {
            return cached == ITEM_NONE ? null : cached;
        }
        synchronized (lock) {
            cached = itemUseCache.get(configId);
            if (cached != null) {
                return cached == ITEM_NONE ? null : cached;
            }
            if (lua == null || broken) {
                return null;
            }
            Path script = scriptPath("item", configId + ".lua");
            if (!Files.isRegularFile(script)) {
                itemUseCache.put(configId, ITEM_NONE);
                return null;
            }
            String global = "ItemModule_" + configId.replaceAll("[^A-Za-z0-9_]", "_");
            try {
                if (!runModuleLocked(script, global)) {
                    itemUseCache.put(configId, ITEM_NONE);
                    return null;
                }
                ItemUse instructions = callOnUseLocked(global, configId, ctx);
                itemUseCache.put(configId, instructions);
                return instructions;
            } catch (RuntimeException e) {
                markBroken("item use " + configId + ": " + e);
                return null;
            }
        }
    }

    /**
     * 内功钩子存在性（scripts/neigong/&lt;buffId&gt;.lua 装载成功且 hook 为函数）：
     * 调度器据此决定是否消费随机数/拉起任务——无脚本玩家 RNG 序列零扰动。
     * 结果按 buffId@hook 缓存，reload 后重算。
     */
    public boolean neigongHasHook(String buffId, String hook) {
        if (buffId == null || buffId.isEmpty() || hook == null || hook.isEmpty()) {
            return false;
        }
        String key = buffId + "@" + hook;
        Boolean cached = neigongHookCache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            cached = neigongHookCache.get(key);
            if (cached != null) {
                return cached;
            }
            boolean has = false;
            if (lua != null && !broken && !neigongBroken.contains(buffId)) {
                try {
                    has = ensureNeigongModuleLocked(buffId)
                            && hookIsFunctionLocked(neigongGlobal(buffId), hook);
                } catch (RuntimeException e) {
                    disableNeigongScript(buffId, "probe " + hook + ": " + e);
                }
            }
            neigongHookCache.put(key, has);
            return has;
        }
    }

    /**
     * 内功运行期钩子调用（每次事件 pCall，模块全局仅装载一次）：
     * 无脚本/无钩子/坏脚本/引擎不可用 → null，调用方静默跳过（特效降级不阻断战斗）。
     * 脚本异常只禁用该 buffId，绝不 markBroken 公式引擎。
     */
    public NeiGongResult neigongHook(String buffId, String hook, NeiGongCtx ctx) {
        if (buffId == null || buffId.isEmpty() || hook == null || ctx == null) {
            return null;
        }
        if (!neigongHasHook(buffId, hook)) {
            return null;
        }
        synchronized (lock) {
            if (lua == null || broken || neigongBroken.contains(buffId)) {
                return null;
            }
            try {
                return callNeigongHookLocked(buffId, hook, ctx);
            } catch (RuntimeException e) {
                disableNeigongScript(buffId, hook + ": " + e);
                return null;
            }
        }
    }

    /** GM reload_scripts：重建引擎并清覆盖缓存（失败会回退 Java 管线而非抛出）。 */
    public void reload() {
        synchronized (lock) {
            overlayCache.clear();
            itemUseCache.clear();
            neigongHookCache.clear();
            neigongBroken.clear();
            skillHookCache.clear();
            skillBroken.clear();
            closeQuietly();
            initLocked();
            LOG.info("lua scripts reloaded: available=" + (lua != null && !broken));
        }
    }

    // ------------------------------------------------------------- 技能 on_hit 运行期钩子

    /** M.on_hit(ctx) 返回 {ratio=...}（本波倍率调整；夹 [0,100]）。 */
    public record SkillHitAdjust(float ratio) {
    }

    /** on_hit 存在性（scripts/skill/<ID>.lua 已装载且钩子为函数；无文件/坏脚本 → false）。 */
    public boolean skillHasOnHit(String skillId) {
        return skillHookPresence(skillId, "on_hit");
    }

    /**
     * 调 M.on_hit(ctx)：ctx={ratio, level, rand={r_adjust}}（r_adjust Java 预掷，
     * 每调用恒 1 枚——无钩子技能 RNG 序列零扰动）；返回 null = 无调整。
     * 坏脚本按脚本隔离禁用，绝不 markBroken。
     */
    public SkillHitAdjust skillOnHit(String skillId, float ratio, int level, double rAdjust) {
        if (skillId == null || skillId.isEmpty() || !skillHasOnHit(skillId)) {
            return null;
        }
        synchronized (lock) {
            if (lua == null || broken || skillBroken.contains(skillId)) {
                return null;
            }
            try {
                return callSkillHookLocked(skillId, ratio, level, rAdjust);
            } catch (RuntimeException e) {
                disableSkillScript(skillId, "on_hit: " + e);
                return null;
            }
        }
    }

    /** 钩子存在性探测与缓存（skillId@hook → 模块已装载且钩子为函数）。 */
    private boolean skillHookPresence(String skillId, String hook) {
        if (skillId == null || skillId.isEmpty() || hook == null || hook.isEmpty()) {
            return false;
        }
        String key = skillId + "@" + hook;
        Boolean cached = skillHookCache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            cached = skillHookCache.get(key);
            if (cached != null) {
                return cached;
            }
            boolean has = false;
            if (lua != null && !broken && !skillBroken.contains(skillId)) {
                try {
                    has = ensureSkillModuleLocked(skillId)
                            && hookIsFunctionLocked(skillGlobal(skillId), hook);
                } catch (RuntimeException e) {
                    disableSkillScript(skillId, "probe " + hook + ": " + e);
                }
            }
            skillHookCache.put(key, has);
            return has;
        }
    }

    private static String skillGlobal(String skillId) {
        return "SkillModule_" + skillId.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** 模块已在当前 Lua 状态则复用，否则装载 scripts/skill/<ID>.lua（无文件 → false）。 */
    private boolean ensureSkillModuleLocked(String skillId) throws RuntimeException {
        String global = skillGlobal(skillId);
        lua.getGlobal(global);
        boolean ready = lua.isTable(-1);
        lua.pop(1);
        if (ready) {
            return true;
        }
        Path script = scriptPath("skill", skillId + ".lua");
        if (!Files.isRegularFile(script)) {
            return false;
        }
        return runModuleLocked(script, global);
    }

    /** 按脚本隔离禁用（WARN 一次），不 markBroken——一个坏脚本不废掉公式引擎。 */
    private void disableSkillScript(String skillId, String reason) {
        if (skillBroken.add(skillId)) {
            LOG.warning("skill script " + skillId + " disabled (" + reason + ")");
        }
    }

    /** 调 M.on_hit 并解析 {ratio=...}（非表/非法值 → null）。 */
    private SkillHitAdjust callSkillHookLocked(String skillId, float ratio, int level,
                                               double rAdjust) throws RuntimeException {
        lua.getGlobal(skillGlobal(skillId));
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return null;
        }
        lua.getField(-1, "on_hit");
        if (!lua.isFunction(-1)) {
            lua.pop(2);
            return null;
        }
        lua.createTable(0, 4);
        setNum("ratio", ratio);
        setNum("level", level);
        lua.createTable(0, 1);
        setNum("r_adjust", rAdjust);
        lua.setField(-2, "rand");
        lua.pCall(1, 1);
        if (!lua.isTable(-1)) {
            lua.pop(2); // 返回值 + 模块全局
            return null;
        }
        try {
            lua.getField(-1, "ratio");
            double value = lua.isNumber(-1) ? lua.toNumber(-1) : -1;
            if (value <= 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return new SkillHitAdjust((float) Math.min(100.0, value));
        } finally {
            lua.pop(3); // ratio 值 + 返回表 + 模块全局
        }
    }

    // ------------------------------------------------------------------ internal

    /** 无覆盖哨兵（缓存命中区分"没脚本"与"没查过"）。 */
    private static final SkillOverlay NONE =
            new SkillOverlay(null, null, null, java.util.List.of(), null, null,
                    java.util.List.of(), java.util.List.of());

    /** item 域无脚本/nil 返回哨兵（对调用方表现为 null → INI 回退）。 */
    private static final ItemUse ITEM_NONE =
            new ItemUse(0, 0, null, 1, java.util.List.of(), null, true);

    private void initLocked() {
        broken = false;
        brokenReason = "";
        try {
            Lua created = new Lua55();
            created.openLibraries();
            lua = created;
            Path formula = scriptPath("combat", "formula.lua");
            if (Files.isRegularFile(formula)) {
                runModuleLocked(formula, "FormulaModule");
            } else {
                broken = true;
                brokenReason = "formula.lua missing";
            }
            // 汇总表（生成器产出；装载失败仅降级 INI 编译，不碰公式引擎）
            Path effectsData = scriptPath("skill", "effects_data.lua");
            if (Files.isRegularFile(effectsData)) {
                try {
                    if (runModuleLocked(effectsData, "SkillEffectsData")) {
                        LOG.info("effects_data.lua loaded (all-skill effect table)");
                    } else {
                        LOG.warning("effects_data.lua did not return a table;"
                                + " skill effects fall back to INI compile");
                    }
                } catch (RuntimeException e) {
                    LOG.warning("effects_data.lua load failed,"
                            + " skill effects fall back to INI compile: " + e);
                }
            }
            LOG.info("lua engine ready (lua55), formula=" + (broken ? "fallback: " + brokenReason : "loaded"));
        } catch (LinkageError e) {
            lua = null;
            broken = true;
            brokenReason = "natives: " + e;
            LOG.warning("lua natives unavailable, combat falls back to Java formulas: " + e);
        } catch (RuntimeException e) {
            closeQuietly();
            lua = null;
            broken = true;
            brokenReason = "init: " + e;
            LOG.warning("lua init failed, combat falls back to Java formulas: " + e);
        }
    }

    /** 运行脚本源并把 chunk 返回的 local M 绑定到全局名（脚本以 return M 收尾，须包裹捕获）。 */
    private boolean runModuleLocked(Path script, String global) throws RuntimeException {
        String source = readScript(script);
        if (source == null) {
            return false;
        }
        lua.load(global + " = (function()\n" + source + "\nend)()\n");
        lua.pCall(0, 0);
        lua.getGlobal(global);
        boolean ok = lua.isTable(-1);
        lua.pop(1);
        return ok;
    }

    private CombatFormulas.Result calcLocked(CombatFormulas.Input input) throws RuntimeException {
        lua.getGlobal("FormulaModule");
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return null;
        }
        lua.getField(-1, "calc");
        lua.createTable(0, 20);
        setNum("attack", input.attack());
        setNum("defense", input.defense());
        setNum("pierce", input.pierce());
        setNum("internal_conversion", input.internalConversion());
        setNum("accuracy", input.accuracy());
        setNum("evasion", input.evasion());
        setNum("hit_rate_bonus", input.hitRateBonus());
        setNum("dodge_rate_bonus", input.dodgeRateBonus());
        setNum("crit_value", input.critValue());
        setNum("resilience", input.resilience());
        setNum("crit_rate_bonus", input.critRateBonus());
        setNum("crit_rate_reduction", input.critRateReduction());
        setNum("crit_damage_bonus", input.critDamageBonus());
        setNum("crit_damage_reduction", input.critDamageReduction());
        setNum("damage_reduction", input.damageReduction());
        setNum("block_rating", input.blockRating());
        setNum("ratio", input.ratio());
        lua.push(input.parrying());
        lua.setField(-2, "parrying");
        // rand = {r_miss, r_crit}：Java 预掷，脚本不掷骰
        lua.createTable(2, 0);
        lua.push(input.missRoll());
        lua.rawSetI(-2, 1);
        lua.push(input.critRoll());
        lua.rawSetI(-2, 2);
        lua.setField(-2, "rand");
        lua.pCall(1, 1);
        if (!lua.isTable(-1)) {
            lua.pop(2);
            return null;
        }
        long damage = readInt("damage");
        boolean miss = readBool("miss");
        boolean crit = readBool("crit");
        boolean parried = readBool("parried");
        double reduction = readNum("reduction_pct");
        lua.pop(2);
        return new CombatFormulas.Result(damage, miss, crit, parried, reduction);
    }

    private SkillOverlay callCompileLocked(String global, ExactBuff exactBuff) throws RuntimeException {
        lua.getGlobal(global);
        lua.getField(-1, "compile");
        lua.createTable(0, 4);
        if (exactBuff != null) {
            lua.createTable(0, 4);
            lua.push(exactBuff.configId());
            lua.setField(-2, "config_id");
            lua.push(exactBuff.staticData());
            lua.setField(-2, "static_data");
            lua.push(exactBuff.level());
            lua.setField(-2, "level");
            lua.push(exactBuff.lifetimeMs());
            lua.setField(-2, "lifetime_ms");
            lua.setField(-2, "exact_buff");
        }
        lua.pCall(1, 1);
        if (!lua.isTable(-1)) {
            lua.pop(2);
            return NONE;
        }
        Long lease = readOptionalLong("self_buff_lease_ms");
        Integer slot = readOptionalInt("self_buff_slot");
        Boolean redArmor = readOptionalBool("red_armor");
        List<StanceVariant> stance = readStanceCycle();
        EffectSet set = readEffectSetLocked(global);
        lua.pop(2);
        if (lease == null && slot == null && redArmor == null && stance.isEmpty() && set.isEmpty()) {
            return NONE;
        }
        return new SkillOverlay(lease, slot, redArmor, stance,
                set.damageAmount(), set.damageWaves(), set.selfBuffs(), set.targetEffects());
    }

    // ------------------------------------------------------------- 汇总表 effects_data

    /** effects_data.lua 条目（栈顶为条目 table；无条目/无表 → null）。 */
    private SkillOverlay dataOverlayLocked(String skillId, int level) throws RuntimeException {
        lua.getGlobal("SkillEffectsData");
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return null;
        }
        lua.getField(-1, skillId);
        if (!lua.isTable(-1)) {
            lua.pop(2);
            return null;
        }
        EffectSet set = readEffectSetLocked("effects_data:" + skillId);
        set = applyByLevelLocked(level, set);
        lua.pop(2);
        return set.isEmpty() ? null : new SkillOverlay(null, null, null, List.of(),
                set.damageAmount(), set.damageWaves(), set.selfBuffs(), set.targetEffects());
    }

    /** 伤害/自 buff/目标效果三键的共享解析（compile 返回表与汇总表条目同构）。 */
    private record EffectSet(Integer damageAmount, Integer damageWaves,
                             List<SelfBuff> selfBuffs, List<TargetEffect> targetEffects) {

        boolean isEmpty() {
            return damageAmount == null && damageWaves == null
                    && selfBuffs.isEmpty() && targetEffects.isEmpty();
        }
    }

    /** 栈顶 table 读 damage/self_buffs/target_effects（白名单；非法条目 WARN 丢弃）。 */
    private EffectSet readEffectSetLocked(String source) {
        Integer damageAmount = null;
        Integer damageWaves = null;
        lua.getField(-1, "damage");
        if (lua.isTable(-1)) {
            long amount = readIntFieldAtTop("amount");
            long waves = readIntFieldAtTop("waves");
            if (amount > 0) {
                damageAmount = (int) Math.min(Integer.MAX_VALUE, amount);
                damageWaves = (int) Math.max(0, Math.min(Integer.MAX_VALUE, waves));
            } else {
                LOG.warning(source + ": damage.amount " + amount + " invalid, ignored");
            }
        }
        lua.pop(1);
        List<SelfBuff> selfBuffs = readSelfBuffsLocked(source);
        List<TargetEffect> targetEffects = readTargetEffectsLocked(source);
        return new EffectSet(damageAmount, damageWaves, selfBuffs, targetEffects);
    }

    /** self_buffs = {{config_id=,static_data=,level=,life_ms=,slot=}}（≤8 条）。 */
    private List<SelfBuff> readSelfBuffsLocked(String source) {
        lua.getField(-1, "self_buffs");
        try {
            if (!lua.isTable(-1)) {
                return List.of();
            }
            int count = lua.rawLength(-1);
            List<SelfBuff> out = new ArrayList<>(Math.min(count, 8));
            for (int i = 1; i <= count && i <= 8; i++) {
                lua.rawGetI(-1, i);
                if (!lua.isTable(-1)) {
                    lua.pop(1);
                    continue;
                }
                String configId = readStringFieldAtTop("config_id");
                long staticData = readIntFieldAtTop("static_data");
                int level = (int) Math.max(1, readIntFieldAtTop("level"));
                long lifeMs = readIntFieldAtTop("life_ms");
                int slot = (int) readIntFieldAtTop("slot");
                lua.pop(1);
                if (configId.isEmpty() || staticData <= 0) {
                    LOG.warning(source + ": invalid self_buffs entry ignored");
                    continue;
                }
                out.add(new SelfBuff(configId, staticData, level, lifeMs, slot));
            }
            return out;
        } finally {
            lua.pop(1);
        }
    }

    /** target_effects = {{config_id=,static_data=,level=,life_ms=,control=,frame=}}（≤8 条）。 */
    private List<TargetEffect> readTargetEffectsLocked(String source) {
        lua.getField(-1, "target_effects");
        try {
            if (!lua.isTable(-1)) {
                return List.of();
            }
            int count = lua.rawLength(-1);
            List<TargetEffect> out = new ArrayList<>(Math.min(count, 8));
            for (int i = 1; i <= count && i <= 8; i++) {
                lua.rawGetI(-1, i);
                if (!lua.isTable(-1)) {
                    lua.pop(1);
                    continue;
                }
                String configId = readStringFieldAtTop("config_id");
                long staticData = readIntFieldAtTop("static_data");
                int level = (int) Math.max(1, readIntFieldAtTop("level"));
                long lifeMs = readIntFieldAtTop("life_ms");
                lua.getField(-1, "control");
                boolean control = lua.toBoolean(-1);
                lua.pop(1);
                lua.getField(-1, "frame");
                boolean frame = lua.toBoolean(-1);
                lua.pop(1);
                lua.pop(1);
                if (configId.isEmpty() || staticData <= 0 || (!control && !frame)) {
                    LOG.warning(source + ": invalid target_effects entry ignored"
                            + " (need config_id/static_data and control or frame)");
                    continue;
                }
                out.add(new TargetEffect(configId, staticData, level, lifeMs, control, frame));
            }
            return out;
        } finally {
            lua.pop(1);
        }
    }

    /**
     * by_level[level] 差集修补（整数键须 rawGetI；缺级/无表 → 原样返回；EffectSet 重建）。
     * 字段存在性按 nil 判别——damage_amount 显式 0 有效（该级无伤害，如
     * damage_calculate[61] 的负段），不得当"未提供"沿用基准。
     */
    private EffectSet applyByLevelLocked(int level, EffectSet base) {
        lua.getField(-1, "by_level");
        try {
            if (!lua.isTable(-1)) {
                return base;
            }
            lua.rawGetI(-1, level);
            try {
                if (!lua.isTable(-1)) {
                    return base;
                }
                Long amount = readOptionalLong("damage_amount");
                Long waves = readOptionalLong("damage_waves");
                Long selfLife = readOptionalLong("self_life_ms");
                Long targetLife = readOptionalLong("target_life_ms");
                Integer newAmount = amount == null ? base.damageAmount()
                        : (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount));
                Integer newWaves = waves == null ? base.damageWaves()
                        : (int) Math.max(0, Math.min(Integer.MAX_VALUE, waves));
                List<SelfBuff> self = base.selfBuffs();
                if (selfLife != null && selfLife > 0 && !self.isEmpty()) {
                    SelfBuff first = self.get(0);
                    self = new ArrayList<>(self);
                    self.set(0, new SelfBuff(first.configId(), first.staticData(),
                            first.level(), selfLife, first.slot()));
                }
                List<TargetEffect> targets = base.targetEffects();
                if (targetLife != null && targetLife > 0 && !targets.isEmpty()) {
                    TargetEffect first = targets.get(0);
                    targets = new ArrayList<>(targets);
                    targets.set(0, new TargetEffect(first.configId(), first.staticData(),
                            first.level(), targetLife, first.control(), first.frame()));
                }
                return new EffectSet(newAmount, newWaves, self, targets);
            } finally {
                lua.pop(1);
            }
        } finally {
            lua.pop(1);
        }
    }

    /** 调 M.on_use(ctx) 并解析返回指令表（无 on_use / nil 返回 → ITEM_NONE 走 INI 回退）。 */
    private ItemUse callOnUseLocked(String global, String configId,
                                    ItemUseContext ctx) throws RuntimeException {
        lua.getGlobal(global);
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return ITEM_NONE;
        }
        lua.getField(-1, "on_use");
        if (!lua.isFunction(-1)) {
            lua.pop(2);
            return ITEM_NONE;
        }
        // ctx = {item_id, amount, now, self={hp,max_hp,mp,max_mp,silver}, rand={r_drop,r_proc}}
        lua.createTable(0, 3);
        lua.push(configId);
        lua.setField(-2, "item_id");
        setNum("amount", ctx.amount());
        setNum("now", ctx.now());
        lua.createTable(0, 5);
        setNum("hp", ctx.hp());
        setNum("max_hp", ctx.maxHp());
        setNum("mp", ctx.mp());
        setNum("max_mp", ctx.maxMp());
        setNum("silver", ctx.silver());
        lua.setField(-2, "self");
        lua.createTable(0, 2);
        setNum("r_drop", ctx.rDrop());
        setNum("r_proc", ctx.rProc());
        lua.setField(-2, "rand");
        lua.pCall(1, 1);
        if (!lua.isTable(-1)) {
            lua.pop(2);
            return ITEM_NONE;
        }
        ItemUse out = parseItemUseLocked(configId);
        lua.pop(2);
        return out;
    }

    /** 解析栈顶 on_use 返回表（指令白名单，非法条目丢弃 + WARNING，不打断使用链）。 */
    private ItemUse parseItemUseLocked(String configId) {
        int healHp = positiveField("heal_hp");
        int healMp = positiveField("heal_mp");
        String openDropId = null;
        int rolls = 1;
        lua.getField(-1, "open_box");
        if (lua.isTable(-1)) {
            lua.getField(-1, "drop_id");
            String dropId = lua.isString(-1) ? lua.toString(-1).trim() : "";
            lua.pop(1);
            if (dropId.isEmpty()) {
                LOG.warning("item script " + configId + ": open_box needs drop_id");
            } else {
                openDropId = dropId;
                rolls = (int) readIntFieldAtTop("rolls");
                if (rolls < 1 || rolls > 10) {
                    LOG.warning("item script " + configId + ": open_box rolls " + rolls
                            + " outside 1..10, clamped");
                    rolls = Math.max(1, Math.min(10, rolls));
                }
            }
        }
        lua.pop(1);
        List<DropPacks.GrantItem> grants = readGrantItems(configId);
        TempStat tempStat = readTempStat(configId);
        lua.getField(-1, "consume");
        boolean consume = !lua.isBoolean(-1) || lua.toBoolean(-1);
        lua.pop(1);
        return new ItemUse(healHp, healMp, openDropId, rolls, grants, tempStat, consume);
    }

    /** grant_items = {{id="xx", amount=2}, ...}（最多 16 条，非法条目丢弃）。 */
    private List<DropPacks.GrantItem> readGrantItems(String configId) {
        lua.getField(-1, "grant_items");
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return List.of();
        }
        int count = lua.rawLength(-1);
        List<DropPacks.GrantItem> out = new ArrayList<>(Math.min(count, 16));
        for (int i = 1; i <= count && i <= 16; i++) {
            lua.rawGetI(-1, i);
            if (!lua.isTable(-1)) {
                lua.pop(1);
                continue;
            }
            lua.getField(-1, "id");
            String id = lua.isString(-1) ? lua.toString(-1).trim() : "";
            lua.pop(1);
            int amount = (int) readIntFieldAtTop("amount");
            lua.pop(1);
            if (id.isEmpty() || amount < 1 || amount > 999) {
                LOG.warning("item script " + configId + ": invalid grant_items entry ignored");
                continue;
            }
            out.add(new DropPacks.GrantItem(id, amount));
        }
        lua.pop(1);
        return out;
    }

    /** temp_stat = {attack_add, defense_add, max_hp_add, life_ms}（life_ms&gt;0 且至少一个 *_add）。 */
    private TempStat readTempStat(String configId) {
        lua.getField(-1, "temp_stat");
        try {
            if (!lua.isTable(-1)) {
                return null;
            }
            long life = readIntFieldAtTop("life_ms");
            int attack = (int) readIntFieldAtTop("attack_add");
            int defense = (int) readIntFieldAtTop("defense_add");
            int maxHp = (int) readIntFieldAtTop("max_hp_add");
            if (life <= 0 || (attack == 0 && defense == 0 && maxHp == 0)) {
                LOG.warning("item script " + configId
                        + ": temp_stat needs life_ms>0 and at least one *_add");
                return null;
            }
            return new TempStat(attack, defense, maxHp, life);
        } finally {
            lua.pop(1);
        }
    }

    // ------------------------------------------------------------------ neigong internal

    private static String neigongGlobal(String buffId) {
        return "NeigongModule_" + buffId.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** 模块全局已在当前 Lua 状态则复用，否则装载脚本文件（无文件 → false）。 */
    private boolean ensureNeigongModuleLocked(String buffId) throws RuntimeException {
        String global = neigongGlobal(buffId);
        lua.getGlobal(global);
        boolean ready = lua.isTable(-1);
        lua.pop(1);
        if (ready) {
            return true;
        }
        Path script = scriptPath("neigong", buffId + ".lua");
        if (!Files.isRegularFile(script)) {
            return false;
        }
        return runModuleLocked(script, global);
    }

    private boolean hookIsFunctionLocked(String global, String hook) throws RuntimeException {
        lua.getGlobal(global);
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return false;
        }
        lua.getField(-1, hook);
        boolean isFunction = lua.isFunction(-1);
        lua.pop(2);
        return isFunction;
    }

    /** 按脚本隔离禁用（WARN 一次），不 markBroken——一本坏脚本不废掉公式引擎。 */
    private void disableNeigongScript(String buffId, String reason) {
        if (neigongBroken.add(buffId)) {
            LOG.warning("neigong script " + buffId + " disabled (" + reason + ")");
        }
    }

    /** 调 M.&lt;hook&gt;(ctx) 并解析返回指令表（无钩子/nil 返回 → null）。 */
    private NeiGongResult callNeigongHookLocked(String buffId, String hook,
                                                NeiGongCtx ctx) throws RuntimeException {
        lua.getGlobal(neigongGlobal(buffId));
        lua.getField(-1, hook);
        if (!lua.isFunction(-1)) {
            lua.pop(2);
            return null;
        }
        buildNeigongCtxTable(ctx);
        lua.pCall(1, 1);
        if (!lua.isTable(-1)) {
            lua.pop(2);
            return null;
        }
        NeiGongResult result = parseNeigongLocked(buffId);
        lua.pop(2);
        return result;
    }

    /** ctx 建表（顶层 + self/target/attacker/rand/state 嵌套；调用前栈顶须为函数）。 */
    private void buildNeigongCtxTable(NeiGongCtx ctx) {
        lua.createTable(0, 16);
        setNum("book_level", ctx.bookLevel());
        setNum("book_max_level", ctx.bookMaxLevel());
        setNum("story_stage", ctx.storyStage());
        setNum("now", ctx.nowMs());
        setNum("damage", ctx.damage());
        setNum("incoming", ctx.incoming());
        lua.push(ctx.parried());
        lua.setField(-2, "parried");
        lua.push(ctx.crit());
        lua.setField(-2, "crit");
        setNum("since_last_ms", ctx.sinceLastMs());
        lua.createTable(0, 5);
        setNum("hp", ctx.selfHp());
        setNum("max_hp", ctx.selfMaxHp());
        setNum("mp", ctx.selfMp());
        setNum("max_mp", ctx.selfMaxMp());
        setNum("attack", ctx.selfAttack());
        lua.setField(-2, "self");
        lua.createTable(0, 3);
        setNum("oid", ctx.targetOid());
        setNum("hp", ctx.targetHp());
        setNum("max_hp", ctx.targetMaxHp());
        lua.setField(-2, "target");
        lua.createTable(0, 1);
        setNum("oid", ctx.attackerOid());
        lua.setField(-2, "attacker");
        lua.createTable(0, 1);
        setNum("r_proc", ctx.rProc());
        lua.setField(-2, "rand");
        Map<String, Object> state = ctx.state();
        if (state != null && !state.isEmpty()) {
            lua.createTable(0, Math.min(state.size(), 16));
            for (Map.Entry<String, Object> entry : state.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Number number) {
                    lua.push(number.doubleValue());
                } else if (value instanceof Boolean bool) {
                    lua.push(bool);
                } else if (value instanceof String text) {
                    lua.push(text);
                } else {
                    continue;
                }
                lua.setField(-2, entry.getKey());
            }
            lua.setField(-2, "state");
        }
    }

    /** 解析栈顶钩子返回表（白名单；非法条目 WARN 丢弃，不打断战斗链）。 */
    private NeiGongResult parseNeigongLocked(String buffId) {
        int extraDamage = 0;
        String extraKind = "";
        lua.getField(-1, "extra_damage");
        if (lua.isTable(-1)) {
            extraDamage = (int) readIntFieldAtTop("amount");
            lua.getField(-1, "kind");
            extraKind = lua.isString(-1) ? lua.toString(-1).trim() : "";
            lua.pop(1);
        }
        lua.pop(1);
        double reducePct = readNum("reduce_pct");
        if (reducePct < 0 || reducePct >= 1) {
            if (reducePct != 0) {
                LOG.warning("neigong script " + buffId + ": reduce_pct " + reducePct
                        + " outside [0,1), clamped");
            }
            reducePct = Math.max(0, Math.min(0.99, reducePct));
        }
        int shieldAmount = 0;
        long shieldLifeMs = 0;
        lua.getField(-1, "shield");
        if (lua.isTable(-1)) {
            shieldAmount = (int) readIntFieldAtTop("amount");
            shieldLifeMs = readIntFieldAtTop("life_ms");
            if (shieldAmount <= 0 || shieldLifeMs <= 0) {
                LOG.warning("neigong script " + buffId
                        + ": shield needs amount>0 and life_ms>0, dropped");
                shieldAmount = 0;
                shieldLifeMs = 0;
            }
        }
        lua.pop(1);
        int heal = positiveField("heal");
        int reflect = positiveField("reflect");
        int mpRestore = positiveField("mp_restore");
        int parryInternal = 0;
        int parryExternal = 0;
        lua.getField(-1, "parry_reduce");
        if (lua.isTable(-1)) {
            parryInternal = (int) readIntFieldAtTop("internal");
            parryExternal = (int) readIntFieldAtTop("external");
        }
        lua.pop(1);
        Map<String, Object> state = null;
        lua.getField(-1, "state");
        if (lua.isTable(-1)) {
            state = readFlatTableAt(lua.getTop(), buffId);
        }
        lua.pop(1);
        return new NeiGongResult(extraDamage, extraKind, reducePct, shieldAmount, shieldLifeMs,
                heal, reflect, mpRestore, state, parryInternal, parryExternal);
    }

    /**
     * 读栈顶 index 处扁平表（string 键 → number/string/boolean，最多 16 条；
     * 整数数值归一为 Long）。嵌套表/越界条目 WARN 跳过。
     */
    private Map<String, Object> readFlatTableAt(int index, String buffId) {
        Map<String, Object> out = new java.util.HashMap<>();
        lua.pushNil();
        int scanned = 0;
        boolean capped = false;
        while (lua.next(index) != 0) {
            if (++scanned > 16) {
                lua.pop(1); // 值弹出，键留给下一轮——容量截断时再补弹
                capped = true;
                break;
            }
            if (lua.isString(-2)) {
                String key = lua.toString(-2);
                Object value = null;
                if (lua.isNumber(-1)) {
                    double d = lua.toNumber(-1);
                    value = d == Math.floor(d) && !Double.isInfinite(d)
                            ? (Object) (long) d : (Object) d;
                } else if (lua.isString(-1)) {
                    value = lua.toString(-1);
                } else if (lua.isBoolean(-1)) {
                    value = lua.toBoolean(-1);
                }
                if (value == null) {
                    LOG.warning("neigong script " + buffId + ": state[" + key
                            + "] unsupported value type, dropped");
                } else {
                    out.put(key, value);
                }
            }
            lua.pop(1); // 弹值留键，续遍历
        }
        if (capped) {
            lua.pop(1); // 补弹残留键，栈平衡
            LOG.warning("neigong script " + buffId + ": state entries capped at 16");
        }
        return out;
    }

    /** 栈顶 table 的正整数控件（负值/缺失 → 0，超 int 上限钳制）。 */
    private int positiveField(String field) {
        double v = readNum(field);
        if (v <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(v + 1e-9));
    }

    /** stance_cycle.variants[]（栈顶为 compile 返回 table；缺失/为空 → 空表）。 */
    private List<StanceVariant> readStanceCycle() {
        lua.getField(-1, "stance_cycle");
        if (!lua.isTable(-1)) {
            lua.pop(1);
            return List.of();
        }
        lua.getField(-1, "variants");
        if (!lua.isTable(-1)) {
            lua.pop(2);
            return List.of();
        }
        int count = lua.rawLength(-1);
        List<StanceVariant> variants = new ArrayList<>(Math.min(count, 8));
        for (int i = 1; i <= count && i <= 8; i++) {
            lua.rawGetI(-1, i);
            if (!lua.isTable(-1)) {
                lua.pop(1);
                continue;
            }
            lua.getField(-1, "id");
            String id = lua.isString(-1) ? lua.toString(-1) : "";
            lua.pop(1);
            long staticData = readIntFieldAtTop("static_data");
            int level = (int) readIntFieldAtTop("level");
            long lifeMs = readIntFieldAtTop("life_ms");
            int slot = (int) readIntFieldAtTop("slot");
            lua.pop(1);
            if (!id.isEmpty() && staticData > 0) {
                variants.add(new StanceVariant(id, staticData, level, lifeMs, slot));
            }
        }
        lua.pop(2);
        return variants;
    }

    private long readIntFieldAtTop(String field) {
        lua.getField(-1, field);
        long value = lua.isNumber(-1) ? (long) Math.floor(lua.toNumber(-1) + 1e-9) : 0;
        lua.pop(1);
        return value;
    }

    /** 栈顶 table 的字符串字段（trim；缺失/非串 → ""）。 */
    private String readStringFieldAtTop(String field) {
        lua.getField(-1, field);
        String value = lua.isString(-1) ? lua.toString(-1).trim() : "";
        lua.pop(1);
        return value;
    }

    private Boolean readOptionalBool(String field) {
        lua.getField(-1, field);
        try {
            if (lua.isNil(-1)) {
                return null;
            }
            return lua.toBoolean(-1);
        } finally {
            lua.pop(1);
        }
    }

    /** 读取栈顶 table 的数值字段（读取后弹回），字段缺失返回 0。 */
    private double readNum(String field) {
        lua.getField(-1, field);
        double value = lua.isNumber(-1) ? lua.toNumber(-1) : 0;
        lua.pop(1);
        return value;
    }

    private long readInt(String field) {
        return (long) Math.floor(readNum(field) + 1e-9);
    }

    private boolean readBool(String field) {
        lua.getField(-1, field);
        boolean value = lua.toBoolean(-1);
        lua.pop(1);
        return value;
    }

    private Long readOptionalLong(String field) {
        lua.getField(-1, field);
        try {
            if (lua.isNil(-1) || !lua.isNumber(-1)) {
                return null;
            }
            return (long) Math.floor(lua.toNumber(-1) + 1e-9);
        } finally {
            lua.pop(1);
        }
    }

    private Integer readOptionalInt(String field) {
        Long value = readOptionalLong(field);
        return value == null ? null : value.intValue();
    }

    private void setNum(String field, double value) {
        lua.push(value);
        lua.setField(-2, field);
    }

    private static Path scriptPath(String... parts) {
        return ServerPaths.projectPath("scripts").resolve(String.join("/", parts));
    }

    private static String readScript(Path script) {
        try {
            String data = Files.readString(script, StandardCharsets.UTF_8);
            return !data.isEmpty() && data.charAt(0) == '﻿' ? data.substring(1) : data;
        } catch (Exception e) {
            LOG.warning("read lua script " + script + ": " + e);
            return null;
        }
    }

    private void markBroken(String reason) {
        broken = true;
        brokenReason = reason;
        LOG.warning("lua engine marked broken (" + reason + "), falling back to Java formulas");
    }

    private void closeQuietly() {
        if (lua != null) {
            try {
                lua.close();
            } catch (Exception ignored) {
                // 关闭失败只影响内存回收
            }
            lua = null;
        }
    }
}
