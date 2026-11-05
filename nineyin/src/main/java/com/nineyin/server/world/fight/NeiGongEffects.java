package com.nineyin.server.world.fight;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.script.LuaScriptService;
import com.nineyin.server.world.scene.SceneLifecycle;

/**
 * 内功特效运行期钩子分发（scripts/neigong/&lt;buffId&gt;.lua，战斗二期接线）：
 * 装备书 {@code progress.book(curNeiGong).buffId} → Lua 钩子，ctx 喂
 * {@code book_level/book_max_level/story_stage/now/rand.r_proc/state} 等，
 * 返回指令白名单（extra_damage/shield/reduce_pct/heal/reflect/mp_restore/state）落副作用。
 *
 * <ul>
 *   <li>attacker 侧：{@link #onAttack}（settleTargets/多段每拍，命中前附伤）。</li>
 *   <li>defender 侧：{@link #onVictim}（NpcCombatAi 每跳；格挡走 on_parry 分支——
 *       当前管线格挡零伤，伤害类指令无意义，只落 state/heal/mp/shield）。</li>
 *   <li>周期：{@link #ensureTicker}（战斗拉起时探测 on_periodic/on_tick 才建 1s 任务，
 *       脱战自停、flag 复位待下次拉起；周期门由脚本 ctx.since_last_ms 自理）。</li>
 * </ul>
 *
 * <p>降级契约（docs/skill-script-framework.md）：无书/无脚本/坏脚本/引擎不可用 →
 * 静默零效果，出招与伤害链永不被阻断；无钩子书不消费随机数（既有 RNG 序列零扰动）。
 * state 为会话内存态（6~20s 互斥/周期窗口），不落 FacultyStore 快照。
 */
final class NeiGongEffects {

    private static final Logger LOG = LoggerFactory.getLogger(NeiGongEffects.class);

    /** 周期钩子节拍（脚本内 INTERVAL/CD 以 since_last_ms 计，1s 只是采样粒度）。 */
    private static final long TICK_PERIOD_MS = 1_000;

    /** 受击侧结算产物：applied=reduce_pct 折算后待落血值（护盾在 applyNpcDamage 内扣），reflect=反弹。 */
    record VictimOutcome(int applied, int reflect) {

        static final VictimOutcome NONE = new VictimOutcome(0, 0);
    }

    private NeiGongEffects() {
    }

    /**
     * attacker 侧钩子（玩家命中结算前逐目标调）：返回附伤值（0=无效果）。
     * heal/mp_restore/state 就地落；shield/reflect 在 attacker 语境忽略（defender 指令）。
     */
    static int onAttack(SkillCastHandler.CastContext context, FightState.HitTarget target,
                        int damage, boolean crit) {
        PlayerActor player = context.player();
        PlayerProgress.Book book = equippedBook(player);
        if (book == null) {
            return 0;
        }
        String hook = attackHookOf(book.buffId);
        if (hook == null) {
            return 0; // 无钩子书零随机数消耗
        }
        double rProc = context.random().nextDouble();
        long nowMs = context.clock().instant().toEpochMilli();
        LuaScriptService.NeiGongResult result = LuaScriptService.getInstance().neigongHook(
                book.buffId, hook, ctx(player, book, nowMs, rProc, damage, 0, false, crit, target, 0));
        if (result == null) {
            return 0;
        }
        applyCommon(context, player, book, result);
        return result.extraDamage();
    }

    /**
     * defender 侧钩子（NpcCombatAi 每跳一次）：非格挡走 on_victim（reduce_pct 折算
     * incoming、shield/heal/mp/reflect/state 落地）；格挡走 on_parry（零伤管线，仅
     * state/heal/mp/shield）。返回折算后待落血与反弹值，护盾吸收由 applyNpcDamage 执行。
     */
    static VictimOutcome onVictim(SkillCastHandler.CastContext context,
                                  SceneLifecycle.CombatNpcInfo info, FightState.NpcCombat combat,
                                  int incoming, boolean parried) {
        PlayerActor player = context.player();
        PlayerProgress.Book book = equippedBook(player);
        if (book == null) {
            return new VictimOutcome(incoming, 0); // 无书/无 buffId：伤害原样透传（NONE.applied=0 是格挡语义）
        }
        long nowMs = context.clock().instant().toEpochMilli();
        FightState.HitTarget target = new FightState.HitTarget(info, combat);
        if (parried) {
            if (LuaScriptService.getInstance().neigongHasHook(book.buffId, "on_parry")) {
                double rProc = context.random().nextDouble();
                LuaScriptService.NeiGongResult result = LuaScriptService.getInstance().neigongHook(
                        book.buffId, "on_parry",
                        ctx(player, book, nowMs, rProc, 0, 0, true, false, target, info.id()));
                if (result != null) {
                    applyCommon(context, player, book, result);
                }
            }
            return VictimOutcome.NONE; // 格挡零伤：无减免/反弹语义
        }
        String hook = victimHookOf(book.buffId);
        if (hook == null) {
            return new VictimOutcome(incoming, 0);
        }
        double rProc = context.random().nextDouble();
        LuaScriptService.NeiGongResult result = LuaScriptService.getInstance().neigongHook(
                book.buffId, hook,
                ctx(player, book, nowMs, rProc, 0, incoming, false, false, target, info.id()));
        if (result == null) {
            return new VictimOutcome(incoming, 0);
        }
        applyCommon(context, player, book, result);
        int applied = incoming;
        if (result.reducePct() > 0) {
            applied = (int) Math.round(applied * (1 - result.reducePct()));
        }
        return new VictimOutcome(applied, result.reflect());
    }

    /** 反弹落地（victim 路径结算后调）：NPC 掉血帧 + 击杀走 onNpcKilled 常规链。 */
    static void applyReflect(SkillCastHandler.CastContext context,
                             SceneLifecycle.CombatNpcInfo info, FightState.NpcCombat combat,
                             int reflect) {
        if (reflect <= 0 || combat.actor.isDead()) {
            return;
        }
        FightState.DamageOutcome outcome =
                context.fight().applyDamage(combat, reflect, context.clock().instant());
        if (!outcome.applied()) {
            return;
        }
        Actor.State state = combat.state();
        context.connection().writeFrame(FightMessages.npcCombatFrame(
                info.id(), info.ownerId(), state.hp(), state.maxHp(), state.logicState(),
                state.hp() <= 0));
        if (outcome.killed()) {
            SkillCastHandler.onNpcKilled(context, new FightState.HitTarget(info, combat));
        }
    }

    /**
     * 周期 ticker 拉起（playerEngaged 调）：书带 on_periodic/on_tick 才建任务——
     * 无周期书零额外调度，不污染既有帧序测试。1s 节拍自续，脱战/换书即停并复位 flag。
     */
    static void ensureTicker(SkillCastHandler.CastContext context) {
        AtomicBoolean started = context.fight().neigongTickerStarted();
        if (!started.compareAndSet(false, true)) {
            return;
        }
        PlayerProgress.Book book = equippedBook(context.player());
        if (book == null || !hasPeriodicHook(book.buffId)) {
            started.set(false);
            return;
        }
        context.scheduler().schedule(
                () -> periodicTick(context, started), TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private static void periodicTick(SkillCastHandler.CastContext context, AtomicBoolean started) {
        boolean keep = false;
        try {
            PlayerActor player = context.player();
            keep = context.connection().channel().isActive() && player.fighting();
            if (keep) {
                PlayerProgress.Book book = equippedBook(player);
                if (book == null) {
                    keep = false;
                } else {
                    runPeriodicHook(context, player, book);
                }
            }
        } catch (RuntimeException e) {
            LOG.info("neigong periodic tick failed: {}", e.toString());
        }
        if (keep) {
            context.scheduler().schedule(
                    () -> periodicTick(context, started), TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
        } else {
            started.set(false); // 脱战自停；下次 playerEngaged 重新拉起
        }
    }

    private static void runPeriodicHook(SkillCastHandler.CastContext context, PlayerActor player,
                                        PlayerProgress.Book book) {
        LuaScriptService svc = LuaScriptService.getInstance();
        String hook = null;
        if (svc.neigongHasHook(book.buffId, "on_periodic")) {
            hook = "on_periodic";
        } else if (svc.neigongHasHook(book.buffId, "on_tick")) {
            hook = "on_tick";
        }
        if (hook == null) {
            return; // 换书后无周期钩子：本拍空转（keep 已算过，下拍重探）
        }
        double rProc = context.random().nextDouble();
        long nowMs = context.clock().instant().toEpochMilli();
        LuaScriptService.NeiGongResult result = svc.neigongHook(book.buffId, hook,
                ctx(player, book, nowMs, rProc, 0, 0, false, false, null, 0));
        if (result != null) {
            applyCommon(context, player, book, result);
        }
    }

    // ------------------------------------------------------------------ internal

    /** 装备书（curNeiGong → Book；无书/无 buffId → null，分发器全程判空降级）。 */
    private static PlayerProgress.Book equippedBook(PlayerActor player) {
        PlayerProgress progress = player.progress();
        PlayerProgress.Book book = progress.book(progress.curNeiGong);
        return book != null && !book.buffId.isEmpty() ? book : null;
    }

    /** attacker 侧钩子名：人工脚本 on_attack 优先，生成桩 on_hit 别名兜底。 */
    private static String attackHookOf(String buffId) {
        LuaScriptService svc = LuaScriptService.getInstance();
        if (svc.neigongHasHook(buffId, "on_attack")) {
            return "on_attack";
        }
        return svc.neigongHasHook(buffId, "on_hit") ? "on_hit" : null;
    }

    /** defender 侧钩子名：on_victim 优先，on_be_hit 别名兜底。 */
    private static String victimHookOf(String buffId) {
        LuaScriptService svc = LuaScriptService.getInstance();
        if (svc.neigongHasHook(buffId, "on_victim")) {
            return "on_victim";
        }
        return svc.neigongHasHook(buffId, "on_be_hit") ? "on_be_hit" : null;
    }

    private static boolean hasPeriodicHook(String buffId) {
        LuaScriptService svc = LuaScriptService.getInstance();
        return svc.neigongHasHook(buffId, "on_periodic")
                || svc.neigongHasHook(buffId, "on_tick");
    }

    /** 九阴志全服阶段（脚本档位门 cap=3+stage-1；九阴志系统落地前的可调旋钮）。 */
    private static int storyStage() {
        return Integer.getInteger("nineyin.neigong.storyStage", 1);
    }

    /** ctx 快照（self 面板口径 attack=meleeDamageRange 下限；since_last_ms 由最近有效触发推）。 */
    private static LuaScriptService.NeiGongCtx ctx(PlayerActor player, PlayerProgress.Book book,
                                                   long nowMs, double rProc, int damage, int incoming,
                                                   boolean parried, boolean crit,
                                                   FightState.HitTarget target, int attackerOid) {
        Actor.State self = player.actorState();
        int[] range = player.meleeDamageRange();
        int targetOid = 0;
        int targetHp = 0;
        int targetMaxHp = 0;
        if (target != null) {
            targetOid = target.info().id();
            Actor.State state = target.combat().state();
            targetHp = state.hp();
            targetMaxHp = state.maxHp();
        }
        long lastFireAt = player.neigongLastFireAt(book.configId);
        return new LuaScriptService.NeiGongCtx(
                book.level, book.maxLevel, storyStage(), nowMs, rProc,
                player.neigongStateOf(book.configId),
                damage, incoming, parried, crit,
                lastFireAt < 0 ? -1 : nowMs - lastFireAt,
                self.hp(), self.maxHp(), self.mp(), self.maxMp(), range[0],
                targetOid, targetHp, targetMaxHp, attackerOid);
    }

    /** 指令通用落地：state 回写、有效触发节拍、护盾注入、回血/回蓝（0x10 vitalUpdate）。 */
    private static void applyCommon(SkillCastHandler.CastContext context, PlayerActor player,
                                    PlayerProgress.Book book,
                                    LuaScriptService.NeiGongResult result) {
        if (!result.isEmpty()) {
            player.markNeigongFired(book.configId);
        }
        if (result.state() != null) {
            player.storeNeigongState(book.configId, result.state());
        }
        if (result.shieldAmount() > 0) {
            player.addNeiGongShield(result.shieldAmount(), result.shieldLifeMs());
        }
        if (result.heal() > 0 || result.mpRestore() > 0) {
            if (player.restore(result.heal(), result.mpRestore())) {
                context.connection().writeFrame(player.vitalUpdate());
            }
        }
    }
}
