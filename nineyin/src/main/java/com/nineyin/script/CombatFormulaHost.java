package com.nineyin.script;

import com.nineyin.game.CombatFormulas;
import com.nineyin.game.CombatStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import party.iroiro.luajava.value.LuaValue;

import java.util.Map;
import java.util.Random;

/**
 * 伤害公式分发：scripts/combat/formula.lua 的 M.calc 优先
 * （系数/掷骰顺序热更不重启），脚本缺失或运行错误回退 {@link CombatFormulas}
 * （Java 常量实现保留为对照与兜底，行为不漂移）。
 *
 * <p>随机数由 Java 预掷传入 rand={r_miss, r_crit}（脚本侧不掷骰，
 * 同输入可复现，JUnit 锚定 Lua/Java 对照）。
 */
public final class CombatFormulaHost {

    private static final Logger log = LoggerFactory.getLogger(CombatFormulaHost.class);

    private final ScriptHost host;

    public CombatFormulaHost(ScriptHost host) {
        this.host = host;
    }

    /** 与 CombatFormulas.calc 同签名的分发入口（技能结算与 NPC 出招共用）。 */
    public CombatFormulas.DamageResult calc(CombatStats attacker, CombatStats defender,
                                            double skillRatio, Random rng, boolean defenderParrying) {
        double rMiss = rng.nextDouble();
        double rCrit = rng.nextDouble();
        if (host != null) {
            LuaValue fn = host.formulaFunction();
            if (fn != null) {
                CombatFormulas.DamageResult scripted = evalFormula(fn, attacker, defender,
                        skillRatio, rMiss, rCrit, defenderParrying);
                if (scripted != null) {
                    return scripted;
                }
            }
        }
        return CombatFormulas.calc(attacker, defender, skillRatio, rMiss, rCrit, defenderParrying);
    }

    private CombatFormulas.DamageResult evalFormula(LuaValue fn, CombatStats attacker,
                                                    CombatStats defender, double ratio,
                                                    double rMiss, double rCrit, boolean parrying) {
        Map<String, Object> input = SkillScriptBridge.formulaInput(
                attacker, defender, ratio, rMiss, rCrit, parrying);
        Object ret = host.callMap(fn, input);
        CombatFormulas.DamageResult result = ret != null
                ? SkillScriptBridge.parseDamageResult(ret) : null;
        if (result == null) {
            log.warn("formula.lua produced no valid result; java fallback used");
        }
        return result;
    }
}
