package com.nineyin.server.world.fight;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.world.scene.SceneLifecycle;

/**
 * NPC 反击 AI（2026-11-04 对拍 docs/combat-traffic-compare.md §D1）：玩家命中战斗 NPC
 * 后该 NPC 以固定周期反击玩家，帧序按参考服 55866（追风士 jn_mon053_02）逐位对齐：
 *
 * <ul>
 *   <li>每跳：msg337(NPC 位置/朝向) + msg20(NPC, 技能, 目标=玩家)。</li>
 *   <li>命中（玩家未格挡）：msg24 十值 c1=7 形态（名义攻击力 20）+ 0x2D(玩家 HPRatio)
 *       ——玩家血条由 0x2D 的 HPRatio 驱动，参考服不随 NPC 命中发玩家 HP 面板。</li>
 *   <li>被格挡：零帧静默挡下（参考服 30 次 NPC 出招仅 3 次伤害飘字），回怒走
 *       {@code RAGE_TAKEN_*}（未格挡 +1/格挡 +2，SkillCastHandler 预留常量在此接线）。</li>
 *   <li>无 msg18/msg205/fight_relation（参考服 NPC 命中不带这三族帧）。</li>
 *   <li>实伤 1 点（参考服名义 20 经玩家防御折算为 1；玩家 HP 千级，不构成威胁压力）。</li>
 * </ul>
 *
 * <p>循环存活条件：NPC 存活、玩家在线存活、距离 ≤ {@link #ATTACK_RANGE_METERS}。
 * 任一不满足即停（再次被玩家命中时重新拉起）。玩家位置取最近一次出招上报坐标
 * （211 的 x/y/z；本服 msg15 不落位置态，战斗中坐标由出招刷新足够）。
 */
final class NpcCombatAi {

    private static final Logger LOG = LoggerFactory.getLogger(NpcCombatAi.class);

    /** 参考服追风士通用攻击技（monv030101 族实测；模型无关的近身挥击动画）。 */
    static final String NPC_ATTACK_SKILL = "jn_mon053_02";
    /** 反击周期（参考服 55866：单 NPC 出招间隔 ~2.2s）。 */
    static final long ATTACK_PERIOD_MS = 2_200;
    /** 反击近身判定（米）。 */
    static final float ATTACK_RANGE_METERS = 6.0f;
    /** 每击实伤（参考服折算后恒 1；名义 20 只出现在 msg24 尾值）。 */
    static final int DAMAGE_PER_HIT = 1;

    private final SkillCastHandler.CastContext context;
    /** npcId → 是否已有待触发的下一跳（防重复拉起）。 */
    private final ConcurrentHashMap<Integer, AtomicBoolean> pending = new ConcurrentHashMap<>();
    private volatile float playerX;
    private volatile float playerY;
    private volatile float playerZ;

    private NpcCombatAi(SkillCastHandler.CastContext context) {
        this.context = context;
    }

    /**
     * 玩家命中目标后拉起反击循环（FightState 持有实例；targets 至少一个存活 NPC 才有意义）。
     */
    static void playerEngaged(SkillCastHandler.CastContext context, List<FightState.HitTarget> targets,
                              float x, float y, float z) {
        FightState fight = context.fight();
        NpcCombatAi ai = fight.npcAi();
        if (ai == null || ai.context.player() != context.player()) {
            ai = new NpcCombatAi(context);
            fight.attachNpcAi(ai);
        }
        ai.updatePlayerPos(x, y, z);
        for (FightState.HitTarget target : targets) {
            // GM 伙伴（bot_* 域）只掉血不还手——主人误伤不触发反击循环。
            if (!target.combat().actor.isDead() && !fight.isCompanion(target.info().id())) {
                ai.ensureLoop(target.info().id());
            }
        }
    }

    private void updatePlayerPos(float x, float y, float z) {
        playerX = x;
        playerY = y;
        playerZ = z;
    }

    private void ensureLoop(int npcId) {
        AtomicBoolean gate = pending.computeIfAbsent(npcId, id -> new AtomicBoolean());
        if (!gate.compareAndSet(false, true)) {
            return;
        }
        context.scheduler().schedule(() -> tick(npcId, gate), ATTACK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void tick(int npcId, AtomicBoolean gate) {
        try {
            SceneLifecycle.CombatNpcInfo info = context.world().combatNpcInfo(npcId);
            FightState.NpcCombat combat = context.fight().npc(npcId);
            PlayerActor player = context.player();
            boolean valid = info != null && info.combat()
                    && combat != null && !combat.actor.isDead()
                    && !context.fight().isCompanion(npcId) // 伙伴永不反击（防御位：playerEngaged 已跳过）
                    && context.connection().channel().isActive()
                    && player.actorState().hp() > 0
                    && player.fighting(); // 战斗结束即收手（闲时退出不被攻击帧拖住）
            if (valid) {
                float dx = info.x() - playerX;
                float dz = info.z() - playerZ;
                valid = dx * dx + dz * dz <= ATTACK_RANGE_METERS * ATTACK_RANGE_METERS;
            }
            if (!valid) {
                pending.remove(npcId);
                return;
            }
            // 出招两帧（参考服 NPC 出招形态：337 + 20，目标=玩家）
            context.connection().writeFrame(FightMessages.casterTransformSync(
                    info.id(), info.ownerId(), info.x(), info.y(), info.z(), info.orient()));
            context.connection().writeFrame(FightMessages.skillAction(
                    info.id(), info.ownerId(), NPC_ATTACK_SKILL,
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
            boolean parried = player.parrying();
            int rage = parried ? SkillCastHandler.RAGE_TAKEN_PARRIED
                    : SkillCastHandler.RAGE_TAKEN_UNPARRIED;
            if (!parried && player.applyNpcDamage(DAMAGE_PER_HIT)) {
                int hp = player.actorState().hp();
                int maxHp = Math.max(1, player.actorState().maxHp());
                context.connection().writeFrame(FightMessages.npcDamageFloat(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, DAMAGE_PER_HIT,
                        info.id(), info.ownerId(), info.modelOrFallback(),
                        player.snapshotName(), npcDisplayName(info)));
                context.connection().writeFrame(FightMessages.hitEffect(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                        hp * 100 / maxHp));
                // 被击技能锁（Go 500ms）：受击瞬间打断施法节奏的官方语义，接口已备。
                player.lockSkillsUntil(Instant.ofEpochMilli(
                        context.clock().instant().toEpochMilli() + 500));
            }
            int spBefore = player.sp();
            PlayerActor.SPUpdate update = player.addSP(rage);
            if (update.sp() != spBefore) {
                context.connection().writeFrame(update.frame());
            }
        } catch (RuntimeException e) {
            LOG.info("npc attack tick id={} failed: {}", npcId, e.toString());
        }
        // 续拍（gate 保持占用直达失效，保持节拍不被 ensureLoop 抢先重排）
        context.scheduler().schedule(() -> tick(npcId, gate), ATTACK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private static String npcDisplayName(SceneLifecycle.CombatNpcInfo info) {
        return info.name() == null || info.name().isEmpty()
                ? info.modelOrFallback() : info.name();
    }
}
