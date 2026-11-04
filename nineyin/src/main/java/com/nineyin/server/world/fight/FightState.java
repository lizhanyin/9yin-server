package com.nineyin.server.world.fight;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.world.scene.SceneLifecycle;

/**
 * 单连接战斗态（Go sceneLifecycle 的 combatActors/combatStates/selectedObject 子集，
 * 战斗一期：NPC 只被打不还手）。每 LoginSession 一份，选角/切图时重建。
 *
 * <ul>
 *   <li>NPC 战斗 actor：懒建（首击时按 SceneLifecycle 快照的 MaxHP 物化），死亡不删对象
 *       （抓包 0x0E=0），15s 后回满血重生（0x10 属性帧，由回调发）。</li>
 *   <li>threat 即 fight_relation_rec 的累计伤害格（0x13 u32 值），死亡清零。</li>
 *   <li>选中目标：0x07/431 写入，需 combat NPC 且存活才可用于出招校验。</li>
 * </ul>
 */
public final class FightState {

    /** Go combat_ai 默认重生间隔。 */
    public static final long RESPAWN_MS = 15_000;

    /** 单 NPC 战斗态。 */
    public static final class NpcCombat {

        final Actor actor;
        long threat;
        Instant respawnAt;
        boolean respawnScheduled;
        Instant controlledUntil;

        NpcCombat(int id, int ownerId, int maxHp) {
            this.actor = new Actor(id, ownerId, "", maxHp, 1);
        }

        public int id() {
            return actor.snapshot().objectId();
        }

        public Actor.State state() {
            return actor.snapshot();
        }

        public long threat() {
            return threat;
        }

        public boolean controlled(Instant now) {
            return controlledUntil != null && now.isBefore(controlledUntil);
        }
    }

    public record DamageOutcome(boolean applied, int hpAfter, boolean killed, long threat) {
    }

    private final Map<Integer, NpcCombat> npcs = new HashMap<>();
    private int selectedObjectId;
    private int selectedOwner;
    /** NPC 反击 AI（懒挂，玩家首次命中目标时创建；选角/切图随 FightState 重建）。 */
    private volatile NpcCombatAi npcAi;

    /** 0x07/431 选中：须为 combat NPC 且存活（Go selectedCombatTarget 语义）。 */
    public synchronized boolean selectTarget(SceneLifecycle world, int objectId, int ownerId) {
        if (world == null) {
            return false;
        }
        SceneLifecycle.CombatNpcInfo info = world.combatNpcInfo(objectId);
        if (info == null || !info.combat()) {
            return false;
        }
        NpcCombat combat = ensureLocked(objectId, info);
        if (combat.actor.isDead()) {
            return false;
        }
        selectedObjectId = objectId;
        selectedOwner = ownerId;
        return true;
    }

    public synchronized void clearSelection() {
        selectedObjectId = 0;
        selectedOwner = 0;
    }

    /** 选中目标存活且仍是 combat NPC（出招校验；Go selectedCombatTarget）。 */
    public synchronized SelectedTarget livingSelectedTarget(SceneLifecycle world) {
        if (selectedObjectId == 0 || world == null) {
            return null;
        }
        SceneLifecycle.CombatNpcInfo info = world.combatNpcInfo(selectedObjectId);
        if (info == null || !info.combat() || info.ownerId() != selectedOwner) {
            return null;
        }
        NpcCombat combat = ensureLocked(selectedObjectId, info);
        if (combat.actor.isDead()) {
            return null;
        }
        return new SelectedTarget(selectedObjectId, selectedOwner, combat);
    }

    public record SelectedTarget(int objectId, int ownerId, NpcCombat combat) {
    }

    /** 面积技能命中目标：圆/扇/矩（Go applyShapedSkillDamage 的几何判定，XZ 平面）。 */
    public synchronized java.util.List<HitTarget> shapedTargets(SceneLifecycle world,
                                                                float cx, float cy, float cz, float orient,
                                                                CombatSkill skill) {
        java.util.List<HitTarget> hits = new java.util.ArrayList<>(4);
        float radius = skill.areaRadius();
        if (radius <= 0) {
            return hits;
        }
        float radiusSquared = radius * radius;
        for (SceneLifecycle.CombatNpcInfo info : world.combatNpcs()) {
            NpcCombat combat = ensureLocked(info.id(), info);
            if (combat.actor.isDead()) {
                continue;
            }
            float dx = info.x() - cx;
            float dz = info.z() - cz;
            float dy = Math.abs(info.y() - cy);
            if (skill.areaHeight() > 0 && dy > skill.areaHeight()) {
                continue;
            }
            if (skill.targetMode() == CombatSkill.TargetMode.CASTER_RECTANGLE
                    || skill.targetMode() == CombatSkill.TargetMode.GROUND_RECTANGLE) {
                double facingX = Math.cos(orient);
                double facingZ = Math.sin(orient);
                double forward = dx * facingX + dz * facingZ;
                double side = -dx * facingZ + dz * facingX;
                if (forward < 0 || forward > radius || Math.abs(side) > skill.areaWidth() / 2.0) {
                    continue;
                }
            } else if (dx * dx + dz * dz > radiusSquared) {
                continue;
            } else if (skill.targetMode() == CombatSkill.TargetMode.CASTER_SECTOR
                    && skill.sectorAngle() > 0) {
                double targetAngle = Math.atan2(dz, dx);
                double delta = Math.atan2(
                        Math.sin(targetAngle - orient), Math.cos(targetAngle - orient));
                if (Math.abs(delta) > skill.sectorAngle() * Math.PI / 360.0) {
                    continue;
                }
            }
            hits.add(new HitTarget(info, combat));
        }
        return hits;
    }

    public record HitTarget(SceneLifecycle.CombatNpcInfo info, NpcCombat combat) {
    }

    /** 结算伤害：threat 累计；死亡清 threat 并排 respawnAt（帧由调用方发）。 */
    public synchronized DamageOutcome applyDamage(NpcCombat combat, int amount, Instant now) {
        if (!combat.actor.applyDamage(amount)) {
            return new DamageOutcome(false, combat.actor.snapshot().hp(), false, combat.threat);
        }
        combat.threat += amount;
        Actor.State state = combat.actor.snapshot();
        if (state.hp() <= 0) {
            combat.threat = 0;
            combat.respawnAt = now.plus(Duration.ofMillis(RESPAWN_MS));
        }
        return new DamageOutcome(true, state.hp(), state.hp() <= 0, combat.threat);
    }

    /** 目标控制（TargetControl 效果：击中者受控到 until）。 */
    public synchronized void control(NpcCombat combat, Instant until) {
        if (until != null && (combat.controlledUntil == null || until.isAfter(combat.controlledUntil))) {
            combat.controlledUntil = until;
        }
    }

    /** 重生结算（调度器到期调用）：回满血；调用方发 NPC 属性帧。 */
    public synchronized boolean respawnIfDue(NpcCombat combat, Instant now) {
        if (combat.respawnAt == null || now.isBefore(combat.respawnAt)) {
            return false;
        }
        combat.actor.respawnFull();
        combat.respawnAt = null;
        return true;
    }

    private NpcCombat ensureLocked(int id, SceneLifecycle.CombatNpcInfo info) {
        NpcCombat combat = npcs.get(id);
        if (combat == null) {
            combat = new NpcCombat(id, info.ownerId(), info.maxHp());
            npcs.put(id, combat);
        }
        return combat;
    }

    /** NPC 战斗态查询（反击 AI 用；未物化返回 null）。 */
    public synchronized NpcCombat npc(int id) {
        return npcs.get(id);
    }

    /** 反击 AI 实例（无则 null）。 */
    public NpcCombatAi npcAi() {
        return npcAi;
    }

    /** 挂反击 AI（playerEngaged 换会话时重建）。 */
    public void attachNpcAi(NpcCombatAi ai) {
        this.npcAi = ai;
    }
}
