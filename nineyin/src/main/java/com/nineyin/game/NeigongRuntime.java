package com.nineyin.game;

import com.nineyin.scene.NpcInstance;
import com.nineyin.script.NeigongScriptBridge;
import com.nineyin.script.ScriptHost;
import com.nineyin.server.FxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.value.LuaValue;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内功特效运行时（scripts/neigong/&lt;buffId&gt;.lua 的钩子触发与指令执行）。
 *
 * <p>挂点：on_attack ← SceneFlow.settleSkillHit（命中结算、并入同一血量批）；
 * on_victim/on_parry ← AiEventEngine.castSkill（applyDamage 前，免伤/招架平减/护盾/反弹）；
 * on_tick ← SceneFlow.tick（周期触发，含被动表重算）。纯函数契约与技能脚本一致：
 * 输入只读快照 + Java 预掷 rand.r_proc，输出指令白名单解析（NeigongScriptBridge），
 * 非法条目丢弃 + WARN，脚本错误绝不打断战斗结算。
 *
 * <p>层数档位映射在脚本侧完成：Java 只传书本修炼等级（book_level/book_max_level），
 * 脚本按 meta.max_tier 自行换算。客户端 buff 帧暂不发（buf_ng_* 的 static_data 目录
 * 缺失），全部效果为服务器侧结算。
 */
public final class NeigongRuntime {

    private static final Logger log = LoggerFactory.getLogger(NeigongRuntime.class);

    /** 周期钩子状态里的上次触发时刻字段（约定 state.last_at，毫秒）。 */
    static final String STATE_LAST_AT = "last_at";

    private final ScriptHost scriptHost;
    /** 被动缓存键（actor → "buffId|bookLevel"；书本未升级不重算，WeakHashMap 随会话回收）。 */
    private final Map<PlayerActor, String> passiveKeys = new WeakHashMap<>();

    public NeigongRuntime(ScriptHost scriptHost) {
        this.scriptHost = scriptHost;
    }

    /** 运行内功解析（无脚本/无宿主返回 null）。 */
    private record Running(String buffId, LuaValue module) {
    }

    private Running running(PlayerActor actor) {
        if (scriptHost == null || !scriptHost.available()) {
            return null;
        }
        String bookId = runningBookId(actor);
        LuaValue module = scriptHost.neigongModule("buf_" + bookId);
        return module == null ? null : new Running("buf_" + bookId, module);
    }

    /** 当前运行的内功书本（faculty 快照；无库回退 BootstrapCurNeigong 常量）。 */
    static String runningBookId(PlayerActor actor) {
        if (actor.faculty != null && actor.faculty.curNeigong != null
                && !actor.faculty.curNeigong.isEmpty()) {
            return actor.faculty.curNeigong;
        }
        return PlayerActor.BootstrapCurNeigong.CUR;
    }

    private record BookLevel(int level, int maxLevel) {
    }

    /** 运行书本的修炼等级（书本不在快照里按 1 级计）。 */
    static BookLevel bookLevelOf(PlayerActor actor) {
        String bookId = runningBookId(actor);
        if (actor.faculty != null) {
            for (FacultyState.Book b : actor.faculty.books) {
                if (bookId.equals(b.configId)) {
                    return new BookLevel(Math.max(1, b.level), Math.max(1, b.maxLevel));
                }
            }
        }
        return new BookLevel(1, 1);
    }

    private static LuaValue hook(LuaValue module, String name) {
        LuaValue fn = module.get(name);
        return fn != null && fn.type() == Lua.LuaType.FUNCTION ? fn : null;
    }

    // ---------- on_attack（玩家命中 NPC，结算后） ----------

    /**
     * 攻击命中钩子：返回应并入本次结算的附加伤害（0 = 无）；
     * heal/mp_restore/shield/state 指令就地执行（hp/mp 变化即时下发 vitalUpdate）。
     */
    public int onAttackLanded(FxSession fx, PlayerActor actor, NpcInstance npc,
                              int damage, boolean crit, int wave, int waves, long now) {
        Running ng = running(actor);
        if (ng == null) {
            return 0;
        }
        LuaValue fn = hook(ng.module(), "on_attack");
        if (fn == null) {
            return 0;
        }
        BookLevel book = bookLevelOf(actor);
        Map<String, Object> ctx = NeigongScriptBridge.attackContext(book.level(), book.maxLevel(),
                wave, waves, damage, crit, now,
                actor.hp, actor.maxHp(), actor.mp, actor.maxMp(), actor.combatStats().attack(),
                npc.objectId, npc.configId, npc.hp, npc.maxHp,
                actor.neigongStateOf(ng.buffId()), ThreadLocalRandom.current().nextDouble());
        NeigongScriptBridge.Instructions instr =
                NeigongScriptBridge.parseInstructions(ng.buffId(), scriptHost.callMap(fn, ctx));
        if (instr == null) {
            return 0;
        }
        executeSelf(fx, actor, ng.buffId(), instr, now);
        if (instr.extraDamage() != null) {
            log.info("neigong {} on_attack extra damage {} ({}) -> npc {}",
                    ng.buffId(), instr.extraDamage().amount(), instr.extraDamage().kind(), npc.configId);
            return instr.extraDamage().amount();
        }
        return 0;
    }

    // ---------- on_victim / on_parry（NPC 攻击玩家，applyDamage 前） ----------

    /**
     * 受击/招架调整：依次应用 招架额外平减（on_parry，仅 parryActive）→
     * on_victim 免伤比例 → 内功护盾池，返回最终应进血量层的伤害。
     * reflect/heal/shield/state 指令就地执行（vitalUpdate 由调用方在 applyDamage 后统一下发）。
     */
    public int adjustIncoming(FxSession fx, PlayerActor actor, NpcInstance attacker,
                              int incoming, boolean crit, boolean parried,
                              boolean parryActive, int parryEndurance, long now) {
        int adjusted = incoming;
        Running ng = running(actor);
        if (ng == null) {
            return actor.consumeAbsorb(adjusted, now);
        }
        BookLevel book = bookLevelOf(actor);
        if (parryActive) {
            LuaValue fn = hook(ng.module(), "on_parry");
            if (fn != null) {
                Map<String, Object> ctx = NeigongScriptBridge.parryContext(book.level(), book.maxLevel(),
                        adjusted, crit, now, parryEndurance,
                        actor.hp, actor.maxHp(), actor.mp, actor.maxMp(),
                        attacker.objectId, attacker.configId,
                        actor.neigongStateOf(ng.buffId()), ThreadLocalRandom.current().nextDouble());
                NeigongScriptBridge.Instructions instr =
                        NeigongScriptBridge.parseInstructions(ng.buffId(), scriptHost.callMap(fn, ctx));
                if (instr != null) {
                    if (instr.parryReduce() != null
                            && (instr.parryReduce().external() > 0 || instr.parryReduce().internal() > 0)) {
                        // 当前 NPC→玩家管线全为外功伤害，平减取外功值（内功值待伤害分系后接入）
                        int flat = instr.parryReduce().external();
                        adjusted = Math.max(0, adjusted - flat);
                        log.info("neigong {} on_parry flat -{} -> incoming {}", ng.buffId(), flat, adjusted);
                    }
                    executeSelf(fx, actor, ng.buffId(), instr, now);
                    if (instr.reflect() != null) {
                        reflectToNpc(ng.buffId(), attacker, instr.reflect());
                    }
                }
            }
        }
        LuaValue fn = hook(ng.module(), "on_victim");
        if (fn != null) {
            Map<String, Object> ctx = NeigongScriptBridge.victimContext(book.level(), book.maxLevel(),
                    adjusted, crit, parried, actor.controlled(now), now,
                    actor.hp, actor.maxHp(), actor.mp, actor.maxMp(),
                    attacker.objectId, attacker.configId,
                    actor.neigongStateOf(ng.buffId()), ThreadLocalRandom.current().nextDouble());
            NeigongScriptBridge.Instructions instr =
                    NeigongScriptBridge.parseInstructions(ng.buffId(), scriptHost.callMap(fn, ctx));
            if (instr != null) {
                if (instr.reducePct() > 0) {
                    adjusted = (int) Math.round(adjusted * (1 - instr.reducePct()));
                    log.info("neigong {} on_victim -{}% -> incoming {}",
                            ng.buffId(), String.format("%.0f", instr.reducePct() * 100), adjusted);
                }
                executeSelf(fx, actor, ng.buffId(), instr, now);
                if (instr.reflect() != null) {
                    reflectToNpc(ng.buffId(), attacker, instr.reflect());
                }
            }
        }
        int remaining = actor.consumeAbsorb(adjusted, now);
        if (remaining < adjusted) {
            log.info("neigong absorb consumed {} (pool left {})", adjusted - remaining,
                    actor.absorbRemaining(now));
        }
        return remaining;
    }

    /** 反弹伤害直扣 NPC 血量（死亡与掉落仍由玩家结算路径处理；此处只记账）。 */
    private void reflectToNpc(String buffId, NpcInstance npc, NeigongScriptBridge.DamageInstr reflect) {
        int before = npc.hp;
        npc.hp = Math.max(0, npc.hp - reflect.amount());
        log.info("neigong {} reflect {} ({}) -> npc {} hp {}->{}",
                buffId, reflect.amount(), reflect.kind(), npc.configId, before, npc.hp);
    }

    // ---------- on_tick（每秒，周期触发 + 被动重算） ----------

    /** 周期钩子：返回是否有 hp/mp 变化（调用方并入 vitalUpdate 节流）。 */
    public boolean onTick(FxSession fx, PlayerActor actor, long now) {
        refreshPassive(actor, now);
        Running ng = running(actor);
        if (ng == null) {
            return false;
        }
        LuaValue fn = hook(ng.module(), "on_tick");
        if (fn == null) {
            return false;
        }
        Map<String, Object> state = actor.neigongStateOf(ng.buffId());
        Object lastAt = state.get(STATE_LAST_AT);
        long sinceLast = lastAt instanceof Number n ? now - n.longValue() : -1;
        BookLevel book = bookLevelOf(actor);
        Map<String, Object> ctx = NeigongScriptBridge.tickContext(book.level(), book.maxLevel(),
                now, sinceLast, actor.hp, actor.maxHp(), actor.mp, actor.maxMp(),
                state, ThreadLocalRandom.current().nextDouble());
        NeigongScriptBridge.Instructions instr =
                NeigongScriptBridge.parseInstructions(ng.buffId(), scriptHost.callMap(fn, ctx));
        return instr != null && executeSelf(fx, actor, ng.buffId(), instr, now);
    }

    // ---------- passive（常驻被动属性） ----------

    /**
     * 被动重算（书本/等级变化才重调 passive 钩子；切换内功时清旧脚本状态）。
     * hp/mp 上限变化不即时裁剪当前值（下一跳恢复自然夹住）。
     */
    public void refreshPassive(PlayerActor actor, long now) {
        Running ng = running(actor);
        String key;
        if (ng == null) {
            key = "";
        } else {
            BookLevel book = bookLevelOf(actor);
            key = ng.buffId() + "|" + book.level();
        }
        String cached = passiveKeys.get(actor);
        if (cached != null && cached.equals(key)) {
            return;
        }
        if (cached != null) {
            actor.neigongState.clear();   // 切换内功：旧 buff 的叠层/计数状态作废
        }
        passiveKeys.put(actor, key);
        if (ng == null) {
            actor.neigongPassive = null;
            return;
        }
        LuaValue fn = hook(ng.module(), "passive");
        if (fn == null) {
            actor.neigongPassive = null;
            return;
        }
        BookLevel book = bookLevelOf(actor);
        Map<String, Object> ctx = NeigongScriptBridge.passiveContext(book.level(), book.maxLevel());
        NeigongScriptBridge.PassiveStats stats =
                NeigongScriptBridge.parsePassive(ng.buffId(), scriptHost.callMap(fn, ctx));
        actor.neigongPassive = stats;
        log.info("neigong passive refreshed role={} neigong={} level={} stats={}",
                actor.name, ng.buffId(), book.level(), stats);
    }

    // ---------- 指令执行 ----------

    /** 自身向指令（heal/mp_restore/shield/state）：返回是否有 hp/mp 变化。 */
    private boolean executeSelf(FxSession fx, PlayerActor actor, String buffId,
                                NeigongScriptBridge.Instructions instr, long now) {
        boolean vital = false;
        if (instr.heal() > 0) {
            int healed = actor.restoreHp(instr.heal());
            if (healed > 0) {
                log.info("neigong {} heal {} (hp={})", buffId, healed, actor.hp);
                vital = true;
            }
        }
        if (instr.mpRestore() > 0) {
            int restored = actor.restoreMp(instr.mpRestore());
            if (restored > 0) {
                log.info("neigong {} mp_restore {} (mp={})", buffId, restored, actor.mp);
                vital = true;
            }
        }
        if (instr.shieldAmount() > 0) {
            actor.grantAbsorb(instr.shieldAmount(), instr.shieldLifeMs(), now);
            log.info("neigong {} shield +{} for {}ms (pool={})", buffId,
                    instr.shieldAmount(), instr.shieldLifeMs(), actor.absorbRemaining(now));
        }
        if (instr.state() != null) {
            actor.setNeigongState(buffId, instr.state());
        }
        if (vital && fx != null) {
            fx.send(actor.vitalUpdate());
        }
        return vital;
    }
}
