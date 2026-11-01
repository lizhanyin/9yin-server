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

import com.nineyin.util.ServerPaths;

/**
 * Lua 脚本引擎接线（scripts/ 目录，与 share 同法经 {@link ServerPaths} 定位）。
 *
 * <p>契约见 docs/skill-script-framework.md：scripts/combat/formula.lua 的 M.calc(c)
 * 为伤害公式权威实现（Java 兜底 {@link CombatFormulas} 同常量同掷骰顺序）；
 * scripts/skill/&lt;技能ID&gt;.lua 的 M.compile(def) 返回编译期增量覆盖
 * （self_buff_lease_ms/self_buff_slot 等，缺省沿用 INI 编译效果）。
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
                                Boolean redArmor, java.util.List<StanceVariant> stanceCycle) {

        boolean isEmpty() {
            return selfBuffLeaseMs == null && selfBuffSlot == null
                    && redArmor == null && (stanceCycle == null || stanceCycle.isEmpty());
        }
    }

    /** stance_cycle.variants[] 项：架势轮换双态（如太极拳四式 守6013/攻6055）。 */
    public record StanceVariant(String id, long staticData, int level, long lifeMs, int slot) {
    }

    /** M.compile(def) 的 def 输入（INI 编译出的 buf_&lt;技能ID&gt; 精确 buff 快照）。 */
    public record ExactBuff(String configId, long staticData, int level, long lifetimeMs) {
    }

    private final Object lock = new Object();
    private Lua lua;
    private boolean broken;
    private String brokenReason = "";
    private final Map<String, SkillOverlay> overlayCache = new ConcurrentHashMap<>();

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

    /** 技能编译覆盖：无脚本/引擎不可用 → null（沿用 INI 默认）。 */
    public SkillOverlay skillOverlay(String skillId, ExactBuff exactBuff) {
        if (skillId == null || skillId.isEmpty()) {
            return null;
        }
        String cacheKey = skillId + "@" + (exactBuff == null ? 0 : exactBuff.level());
        SkillOverlay cached = overlayCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            if (cached != null) {
                return cached;
            }
            if (lua == null || broken) {
                return null;
            }
            Path script = scriptPath("skill", skillId + ".lua");
            if (!Files.isRegularFile(script)) {
                overlayCache.put(cacheKey, NONE);
                return NONE;
            }
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
    }

    /** GM reload_scripts：重建引擎并清覆盖缓存（失败会回退 Java 管线而非抛出）。 */
    public void reload() {
        synchronized (lock) {
            overlayCache.clear();
            closeQuietly();
            initLocked();
            LOG.info("lua scripts reloaded: available=" + (lua != null && !broken));
        }
    }

    // ------------------------------------------------------------------ internal

    /** 无覆盖哨兵（缓存命中区分"没脚本"与"没查过"）。 */
    private static final SkillOverlay NONE = new SkillOverlay(null, null, null, java.util.List.of());

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
        lua.pop(2);
        if (lease == null && slot == null && redArmor == null && stance.isEmpty()) {
            return NONE;
        }
        return new SkillOverlay(lease, slot, redArmor, stance);
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
