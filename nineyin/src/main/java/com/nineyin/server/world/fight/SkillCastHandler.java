package com.nineyin.server.world.fight;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.script.CombatFormulas;
import com.nineyin.server.script.LuaScriptService;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.world.scene.SceneLifecycle;

/**
 * msg211 USE_SKILL 出招编排（战斗一期），帧序逐位对齐 62433 技能抓包
 * （docs/combat-skill-211.md §3）：个人CD行 → active_skill_rec → GCD 行 → 玩家全量 0x10
 * → msg337 → msg20 → [每目标 0x10/0x13/24/0x2D/18/205，首目标后插 buff 帧] →
 * 动作总时长后 0x12 + State="stand"。
 *
 * <p>被拒（未学/未编译/无目标/门控失败/消息畸形）：静默吞掉只记日志（抓包 35 发 12 中实证）。
 * 伤害经 formula.lua（Lua 权威，Java 兜底）；击杀不删对象，15s 后满血重生。
 */
public final class SkillCastHandler {

    private static final Logger LOG = Logger.getLogger(SkillCastHandler.class.getName());

    public static final int CUSTOM_USE_SKILL = 211;
    /** GCD 固定类别 50000（抓包；个人行 team=0xFFFF，GCD 行 team=skill_static.CoolDownTeam）。 */
    public static final int GCD_CATEGORY = 50000;
    public static final int PERSONAL_CD_TEAM = 0xFFFF;
    /**
     * 无 CoolDownTeam 技能的 GCD 行回退组：客户端 gui.lua SetCommonTeamCoolRange(50000,50001,51000)
     * 只认 50001-51000（其中 50001-50100 无特效段）——0xFFFF 落在组段之外，改用 50001。
     */
    public static final int GCD_TEAM_FALLBACK = 50001;
    /** View40 已学技能容器（CanUse 行帧寻址）。 */
    public static final int SKILL_VIEW_ID = 40;
    /** 战斗闲时退出窗口：最近一次命中后 N 毫秒无战斗则回 NORMAL（抓包实证战斗态跨招持续）。 */
    public static final long COMBAT_IDLE_EXIT_MS = 10_000;
    /**
     * 单体技能射程兜底（米）：attack_targetshape 对近战单体多为「非远程以自己为目标」
     * （无半径可取），用宽松近战身位兜底防跨图命中；形状技能射程即其 HitShape 半径。
     */
    public static final float SELECTED_RANGE_METERS = 8.0f;

    private static double square(float value) {
        return (double) value * value;
    }

    /** 出招上下文（LoginFlow 装配；learnedSkills 为 View40 已学集 id→level，skillRows 为 id→View40 行号）。 */
    public record CastContext(
            Connection connection, PlayerActor player, SceneLifecycle world, FightState fight,
            Map<String, Integer> learnedSkills, Map<String, Integer> skillRows,
            ScheduledExecutorService scheduler, Clock clock, Random random) {
    }

    /** 暂存的已生效 buff（帧与到期清理用）。 */
    private record StagedBuff(int slot, long staticData, String configId, Instant expiresAt) {
    }

    public static void handle(CastContext context, CustomC2S.Message custom) {
        List<CustomC2S.Value> values = custom.values;
        if (values.size() < 2 || values.get(0).exactInt32() != CUSTOM_USE_SKILL) {
            return;
        }
        String skillId = values.get(1).textOrNull();
        if (skillId == null || skillId.isEmpty()) {
            LOG.info("reject malformed USE_SKILL");
            return;
        }
        // 施法者坐标/朝向（扇形与 msg337 用；宽容解析同 Go useSkillCastTransform）
        float x = floatValue(values, 2);
        float y = floatValue(values, 3);
        float z = floatValue(values, 4);
        float orient = floatValue(values, 5);

        Integer learned = context.learnedSkills().get(skillId);
        if (learned == null || learned < 1) {
            LOG.info("reject unlearned skill id=" + skillId);
            return;
        }
        CombatSkill skill = CombatSkillCatalog.load().definition(skillId, learned);
        if (skill == null) {
            LOG.info("reject learned skill without compiled level id=" + skillId + " level=" + learned);
            return;
        }
        FightState.SelectedTarget selected = null;
        if (skill.requiresTarget()) {
            selected = context.fight().livingSelectedTarget(context.world());
            if (selected == null) {
                LOG.info("reject skill id=" + skillId + " without living selected target");
                return;
            }
            // 攻击距离闸门（bug.txt「攻击距离正常」项）：单体/以目标为中心的技能校验
            // 施法者(211 上报坐标)→目标距离，超距静默拒（客户端面板变红提示为本地行为）。
            // 射程 = max(HitShape 半径, 近战兜底 8 米)；形状技能（以己圆/扇/矩）天然以
            // 施法者坐标为圆心，半径即射程，不走此闸。
            SceneLifecycle.CombatNpcInfo targetInfo = context.world().combatNpcInfo(selected.objectId());
            if (targetInfo != null) {
                double distance = Math.sqrt(square(x - targetInfo.x()) + square(y - targetInfo.y())
                        + square(z - targetInfo.z()));
                double maxRange = Math.max(skill.areaRadius(), SELECTED_RANGE_METERS);
                if (distance > maxRange) {
                    LOG.info("reject skill id=" + skillId + " out of range " + (int) distance
                            + "m > " + (int) maxRange + "m");
                    return;
                }
            }
        }
        Instant now = Instant.ofEpochMilli(context.clock().instant().toEpochMilli());
        long targetKey = selected == null ? 0
                : (selected.objectId() & 0xFFFFFFFFL) | ((long) selected.ownerId() << 32);
        PlayerActor.SkillGate gate = context.player().beginSkillUse(skill, targetKey, now);
        if (!gate.allowed()) {
            LOG.info("reject skill id=" + skillId + ": " + gate.reason());
            return;
        }

        Connection connection = context.connection();
        PlayerActor player = context.player();
        String playerName = player.snapshotName();

        // CD 双行 + active_skill_rec（顺序按抓包）
        long start = now.toEpochMilli();
        connection.writeFrame(FightMessages.cooldownRow(PlayerActor.PLAYER_OBJECT_ID,
                PlayerActor.PLAYER_OWNER_ID, skill.cooldownCategory(), start,
                start + skill.personalCdMs(), PERSONAL_CD_TEAM));
        connection.writeFrame(FightMessages.activeSkillRow(PlayerActor.PLAYER_OBJECT_ID,
                PlayerActor.PLAYER_OWNER_ID, skill.id()));
        connection.writeFrame(FightMessages.cooldownRow(PlayerActor.PLAYER_OBJECT_ID,
                PlayerActor.PLAYER_OWNER_ID, GCD_CATEGORY, start,
                start + skill.publicCdMs(),
                skill.cooldownTeam() > 0 ? skill.cooldownTeam() : GCD_TEAM_FALLBACK));
        // CanUse 置 0（View40 行；不置则真机技能图标恒灰）；个人 CD 尽时置 1——
        // 出招技能自身不吃公共 GCD（同套路不触发 7s，bug.txt；2026-11-02 前
        // max(个人,GCD) 把 cd<7s 的技能图标也压到 7s，已改回个人 CD）
        Integer row = context.skillRows().get(skill.id());
        if (row != null && row > 0) {
            connection.writeFrame(FightMessages.skillRowCanUse(SKILL_VIEW_ID, row, false));
            long canUseAt = skill.personalCdMs();
            // 大括号块保证解析到 schedule(Runnable)：单表达式 lambda 会因返回值落到 Callable 重载
            context.scheduler().schedule(() -> {
                context.connection().writeFrame(
                        FightMessages.skillRowCanUse(SKILL_VIEW_ID, row, true));
            }, Math.max(canUseAt, 1), TimeUnit.MILLISECONDS);
        }
        // 玩家全量属性面（抓包 188 项 = stateProperties）
        connection.writeFrame(player.vitalUpdate());
        // msg337 施法者位置/朝向回显（值取 211 请求，官方每招恒发 62433）。
        connection.writeFrame(FightMessages.casterTransformSync(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, x, y, z, orient));
        connection.writeFrame(FightMessages.skillAction(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, skill.id()));

        List<StagedBuff> staged = stageSkillBuffs(context, skill, now);
        List<FightState.HitTarget> targets = resolveTargets(context, skill, x, y, z, orient);
        if (skill.hitFrameMs().isEmpty()) {
            // 单段技能：原路径（即时结算，帧序对齐 62433 抓包）。
            List<Hit> hits = settleTargets(context, skill, targets, now);
            // 命中才进战斗（62433 抓包：出招 vital 仍 LogicState=0，命中后切 1）
            if (hits.stream().anyMatch(hit -> hit.damage() > 0) && player.enterCombat(now)) {
                connection.writeFrame(player.logicStateFrame());
            }
            emitPerTargetSequence(context, skill, hits, playerName, staged);
            applyTargetControl(context, skill, hits, now);
            feedMeridianZhenQi(context, hits);
        } else {
            // 多段技能（zhaoshi trigger_effect 帧位）：每段一次全套命中帧
            // （52795 抓包实证：每段恒 [NPC 0x10, 0x13, msg24, 0x2D, msg18, msg205]，
            // 同招各段伤害值相同=每招掷一次、段间重复；死亡目标后续段跳过）。
            scheduleMultiHits(context, skill, targets, staged, hitTimes(skill), playerName);
        }
        scheduleEndOfAction(context, skill, staged);
        scheduleCombatIdleExit(context);
        LOG.info("accepted skill id=" + skill.id() + " mp=" + player.actorState().mp()
                + " sp=" + player.sp() + " targets=" + targets.size()
                + (skill.hitFrameMs().isEmpty() ? "" : " ticks=" + skill.hitFrameMs().size())
                + " personal_cd=" + skill.personalCdMs() + "ms public_cd=" + skill.publicCdMs() + "ms");
    }

    /**
     * 战斗真气（zhenqi.ini damage_ratio=0.1）：本次出招伤害合计折算真气入账当前修炼
     * 经脉，变化即推 View45 行 CurFillValue + 玩家 ZQ 属性帧（修炼进度随打怪可见增长）。
     */
    private static void feedMeridianZhenQi(CastContext context, List<Hit> hits) {
        int damage = 0;
        for (Hit hit : hits) {
            if (hit.damage() > 0) {
                damage += hit.damage();
            }
        }
        feedZhenQiAmount(context, damage);
    }

    /** 战斗真气按伤害合计入账（多段路径末段一次性喂入整招累计）。 */
    private static void feedZhenQiAmount(CastContext context, int damage) {
        if (damage <= 0) {
            return;
        }
        var state = context.player().meridian();
        var result = state.addCombatZhenQi(damage);
        if (!result.changed()) {
            return;
        }
        String current = state.curJingMai();
        var meridian = state.meridian(current);
        int row = state.rowOf(current);
        if (meridian == null || row <= 0) {
            return;
        }
        if (result.leveledUp().isEmpty()) {
            context.connection().writeFrame(
                    com.nineyin.server.game.avatar.jingmai.JingMaiMessages.progressFrame(
                            row, meridian.fill()));
        } else {
            context.connection().writeFrame(
                    com.nineyin.server.game.avatar.jingmai.JingMaiMessages.levelUpFrame(
                            row, meridian.level(), meridian.fill(),
                            com.nineyin.server.game.avatar.jingmai.MeridianCatalog.load()
                                    .totalFillPerLevel(current)));
        }
        context.connection().writeFrame(
                com.nineyin.server.game.avatar.jingmai.JingMaiMessages.playerPropsFrame(state));
    }

    /**
     * 战斗闲时退出：最近一次命中后 {@link #COMBAT_IDLE_EXIT_MS} 无新命中 → 回 NORMAL 并推帧。
     * 每次命中都会重排本任务（enterCombat 刷新 lastCombatAt），多个挂起任务幂等无害。
     */
    private static void scheduleCombatIdleExit(CastContext context) {
        context.scheduler().schedule(() -> {
            if (context.player().exitCombatIfIdle(
                    java.time.Duration.ofMillis(COMBAT_IDLE_EXIT_MS), context.clock().instant())) {
                context.connection().writeFrame(context.player().logicStateFrame());
                LOG.info("combat idle exit -> LogicState=NORMAL");
            }
        }, COMBAT_IDLE_EXIT_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 伤害

    /** 单次命中的结算产物（damage=0 表示未命中/未结算，整段不发帧）。 */
    private record Hit(FightState.HitTarget target, int damage) {
    }

    /** 出招时刻的目标解析（单体/以己形状/以目标为中心形状；无伤害效果返回空表）。 */
    private static List<FightState.HitTarget> resolveTargets(CastContext context, CombatSkill skill,
                                                             float x, float y, float z, float orient) {
        boolean hasDamage = skill.effects().stream()
                .anyMatch(effect -> effect.kind() == CombatSkill.EffectKind.DAMAGE && effect.amount() > 0);
        if (!hasDamage) {
            return List.of();
        }
        List<FightState.HitTarget> targets = new ArrayList<>(4);
        switch (skill.targetMode()) {
            case SELECTED -> {
                FightState.SelectedTarget target =
                        context.fight().livingSelectedTarget(context.world());
                if (target != null) {
                    targets.add(new FightState.HitTarget(
                            context.world().combatNpcInfo(target.objectId()), target.combat()));
                }
            }
            case CASTER_CIRCLE, CASTER_SECTOR, CASTER_RECTANGLE ->
                    targets.addAll(context.fight().shapedTargets(context.world(), x, y, z, orient, skill));
            case SELECTED_AREA -> {
                FightState.SelectedTarget target =
                        context.fight().livingSelectedTarget(context.world());
                if (target == null) {
                    LOG.info("area skill id=" + skill.id() + " has no selected center; no targets settled");
                    break;
                }
                SceneLifecycle.CombatNpcInfo info = context.world().combatNpcInfo(target.objectId());
                if (info != null) {
                    targets.addAll(context.fight().shapedTargets(context.world(),
                            info.x(), info.y(), info.z(), info.orient(), skill));
                }
            }
            default -> {
            }
        }
        return targets;
    }

    /** 单段结算：逐目标掷值落血（未命中记 0 值命中，发帧阶段整段跳过）。 */
    private static List<Hit> settleTargets(CastContext context, CombatSkill skill,
                                           List<FightState.HitTarget> targets, Instant now) {
        List<Hit> hits = new ArrayList<>(4);
        for (FightState.HitTarget target : targets) {
            int damage = rollDamage(context, skill);
            if (damage > 0) {
                FightState.DamageOutcome outcome =
                        context.fight().applyDamage(target.combat(), damage, now);
                if (outcome.applied()) {
                    if (outcome.killed()) {
                        scheduleRespawn(context, target);
                    }
                    hits.add(new Hit(target, damage));
                    continue;
                }
            }
            hits.add(new Hit(target, 0)); // 未命中：HP 不动，发帧阶段整段跳过
        }
        return hits;
    }

    /** 目标控制效果（击中者受控到 buff 租约终点；多段路径在首段命中后调用）。 */
    private static void applyTargetControl(CastContext context, CombatSkill skill,
                                           List<Hit> hits, Instant now) {
        for (CombatSkill.Effect effect : skill.effects()) {
            if (effect.kind() == CombatSkill.EffectKind.TARGET_CONTROL
                    && effect.buff() != null && effect.buff().lifetimeMs() > 0
                    && hits.stream().anyMatch(hit -> hit.damage() > 0)) {
                Instant until = now.plusMillis(effect.buff().lifetimeMs());
                for (Hit hit : hits) {
                    if (hit.damage() > 0) {
                        context.fight().control(hit.target().combat(), until);
                    }
                }
                LOG.info("target control skill=" + skill.id() + " buff=" + effect.buff().configId()
                        + " targets=" + hits.size());
            }
        }
    }

    /** 多段调度时刻：trigger_effect 帧位毫秒（升序、去重；首段至少 1ms 后落拍）。 */
    private static List<Long> hitTimes(CombatSkill skill) {
        List<Long> times = new ArrayList<>(skill.hitFrameMs().size());
        long previous = -1;
        for (long frameMs : skill.hitFrameMs()) {
            long at = Math.max(frameMs, 1);
            if (at == previous) {
                continue;
            }
            times.add(at);
            previous = at;
        }
        return times;
    }

    /**
     * 多段结算（52795 抓包实证形态）：出招时解析目标+掷一次伤害值（同招各段各目标同值），
     * 按 trigger_effect 帧位逐段调度——每段对仍存活目标发全套命中帧；首段命中进战斗+
     * 插 buff 帧+目标控制并刷新战斗活跃时刻；末段后按整招累计伤害喂经脉真气。
     */
    private static void scheduleMultiHits(CastContext context, CombatSkill skill,
                                          List<FightState.HitTarget> targets,
                                          List<StagedBuff> staged, List<Long> times,
                                          String playerName) {
        int damage = rollDamage(context, skill);
        if (targets.isEmpty() || damage <= 0) {
            // 无目标/未掷中：buff 帧照发（单段路径同款兜底），零命中零帧。
            if (!staged.isEmpty()) {
                emitBuffFrames(context, staged, playerName);
            }
            return;
        }
        long[] landedTotal = {0};
        boolean[] firstTick = {true};
        List<StagedBuff> pendingBuffs = new ArrayList<>(staged);
        long lastAt = times.get(times.size() - 1);
        for (int tick = 0; tick < times.size(); tick++) {
            long at = times.get(tick);
            final boolean lastTick = at == lastAt;
            context.scheduler().schedule(() -> {
                Instant now = context.clock().instant();
                List<Hit> hits = new ArrayList<>(targets.size());
                for (FightState.HitTarget target : targets) {
                    Actor.State state = target.combat().state();
                    if (state.hp() <= 0) {
                        continue; // 前段已击杀：后续段跳过（不发帧）
                    }
                    FightState.DamageOutcome outcome =
                            context.fight().applyDamage(target.combat(), damage, now);
                    if (!outcome.applied()) {
                        continue;
                    }
                    if (outcome.killed()) {
                        scheduleRespawn(context, target);
                    }
                    hits.add(new Hit(target, damage));
                }
                if (hits.isEmpty()) {
                    return;
                }
                if (firstTick[0]) {
                    firstTick[0] = false;
                    if (context.player().enterCombat(now)) {
                        context.connection().writeFrame(context.player().logicStateFrame());
                    }
                    applyTargetControl(context, skill, hits, now);
                } else {
                    context.player().touchCombat(now); // 后续段刷新活跃时刻，防段间闲退
                }
                emitPerTargetSequence(context, skill, hits, playerName, pendingBuffs);
                pendingBuffs.clear();
                landedTotal[0] += (long) damage * hits.size();
                if (lastTick) {
                    feedZhenQiAmount(context, (int) Math.min(landedTotal[0], Integer.MAX_VALUE));
                }
            }, at, TimeUnit.MILLISECONDS);
        }
    }

    /** formula.lua（Lua 权威/Java 兜底）：attack=装备+内功掷值，守方侧当前取缺省。 */
    private static int rollDamage(CastContext context, CombatSkill skill) {
        int[] range = context.player().meleeDamageRange();
        int roll = range[0];
        if (range[1] > range[0]) {
            roll += context.random().nextInt(range[1] - range[0] + 1);
        }
        CombatFormulas.Input input = new CombatFormulas.Input(
                roll, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                0, 0,
                skill.damageRatio(), false,
                context.random().nextDouble(), context.random().nextDouble());
        CombatFormulas.Result result = LuaScriptService.getInstance().calc(input);
        return result.miss() ? 0 : (int) Math.min(result.damage(), Integer.MAX_VALUE);
    }

    private static void emitPerTargetSequence(CastContext context, CombatSkill skill,
                                              List<Hit> hits, String playerName,
                                              List<StagedBuff> staged) {
        Connection connection = context.connection();
        boolean buffFramesSent = staged.isEmpty();
        boolean anyHit = false;
        for (Hit hit : hits) {
            if (hit.damage() <= 0) {
                continue; // 未命中目标：整段不发（HP 无变化）
            }
            anyHit = true;
            FightState.HitTarget target = hit.target();
            Actor.State state = target.combat().state();
            SceneLifecycle.CombatNpcInfo info = target.info();
            connection.writeFrame(FightMessages.npcCombatFrame(info.id(), info.ownerId(),
                    state.hp(), state.maxHp(), state.logicState(), state.hp() <= 0));
            connection.writeFrame(FightMessages.fightRelationThreat(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    target.combat().threat()));
            connection.writeFrame(FightMessages.skillDamageFloat(info.id(), info.ownerId(),
                    hit.damage(), skill.id(), skill.script(), info.modelOrFallback(), playerName));
            connection.writeFrame(FightMessages.hitEffect(info.id(), info.ownerId(),
                    state.maxHp() <= 0 ? 0 : state.hp() * 100 / state.maxHp()));
            connection.writeFrame(FightMessages.hurtAction(info.id(), info.ownerId(),
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
            if (!buffFramesSent) {
                emitBuffFrames(context, staged, playerName);
                buffFramesSent = true;
            }
            connection.writeFrame(FightMessages.hitConfirm(PlayerActor.PLAYER_OBJECT_ID,
                    PlayerActor.PLAYER_OWNER_ID, skill.id(), info.id(), info.ownerId()));
        }
        if (!anyHit && !buffFramesSent) {
            emitBuffFrames(context, staged, playerName);
        }
    }

    /** formula.lua（Lua 权威/Java 兜底）：attack=装备+内功掷值，守方侧当前取缺省。 */
    private static void emitBuffFrames(CastContext context, List<StagedBuff> staged, String playerName) {
        for (StagedBuff buff : staged) {
            context.connection().writeFrame(FightMessages.skillBuff(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    buff.configId(), playerName));
            context.connection().writeFrame(context.player().buffSlotFrame(buff.slot(),
                    context.player().bufferInfo(buff.slot(), context.clock().instant())));
        }
    }

    // ------------------------------------------------------------------ buff/收尾

    /** Go stagePlayerSkillEffects：自 buff / 架势轮换 / 红霸体（租约=动作总时长）。 */
    private static List<StagedBuff> stageSkillBuffs(CastContext context, CombatSkill skill, Instant now) {
        List<StagedBuff> staged = new ArrayList<>(2);
        PlayerActor player = context.player();
        for (CombatSkill.Effect effect : skill.effects()) {
            switch (effect.kind()) {
                case PLAYER_BUFF -> staged.add(activate(context, effect.buff(), now));
                case STANCE_CYCLE -> {
                    CombatSkill.Buff variant = player.nextStanceVariant(effect.buffVariants());
                    StagedBuff buff = activate(context, variant, now);
                    if (buff != null) {
                        player.noteStanceSlot(variant.preferredSlot(), variant.staticData());
                        staged.add(buff);
                    }
                }
                case RED_ARMOR -> staged.add(activate(context, new CombatSkill.Buff(
                        CombatSkillCatalog.RED_ARMOR_BUFF_CONFIG, CombatSkillCatalog.RED_ARMOR_STATIC_DATA,
                        1, skill.totalActionDurationMs(), CombatSkillCatalog.RED_ARMOR_BUFF_SLOT), now));
                default -> {
                }
            }
        }
        staged.removeIf(buff -> buff == null);
        return staged;
    }

    private static StagedBuff activate(CastContext context, CombatSkill.Buff buff, Instant now) {
        if (buff == null || buff.staticData() <= 0) {
            return null;
        }
        PlayerActor player = context.player();
        int slot = buff.preferredSlot();
        if (slot <= 0) {
            slot = player.freeBuffSlot();
            if (slot <= 0) {
                LOG.info("no free BuffInfo slot for " + buff.configId());
                return null;
            }
        }
        long lifetime = buff.lifetimeMs() > 0 ? buff.lifetimeMs() : CombatSkillCatalog.DEFAULT_BUFF_LEASE_MS;
        Instant expiresAt = now.plusMillis(lifetime);
        player.activateBuff(slot, buff.staticData(), buff.configId(), buff.level(), expiresAt);
        return new StagedBuff(slot, buff.staticData(), buff.configId(), expiresAt);
    }

    /** 动作总时长后：0x12 删 active_skill_rec + State="stand"；buff 到期清槽发空帧。 */
    private static void scheduleEndOfAction(CastContext context, CombatSkill skill, List<StagedBuff> staged) {
        long delay = Math.max(skill.totalActionDurationMs(), 1);
        context.scheduler().schedule(() -> {
            if (context.player().clearCurrentSkill(skill.id())) {
                context.connection().writeFrame(FightMessages.activeSkillRowRemove(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
                // State=stand 复位帧停发（2026-11-02 真机反馈）：空中出招时客户端收到
                // State=stand 会终止空中状态并把模型贴地（人被瞬移到地面）。官方抓包
                // 每招收尾都发（12/12，但那次全程地面战斗暴露不了）；动作时间线由客户
                // 端自行走完复位，若真机反馈地面出招收尾卡姿势，再按落地条件恢复。
            }
        }, delay, TimeUnit.MILLISECONDS);
        for (StagedBuff buff : staged) {
            long until = buff.expiresAt().toEpochMilli() - context.clock().instant().toEpochMilli();
            context.scheduler().schedule(() -> {
                if (context.player().clearBuffSlot(buff.slot())) {
                    context.connection().writeFrame(
                            context.player().buffSlotFrame(buff.slot(), ""));
                }
            }, Math.max(until, 1), TimeUnit.MILLISECONDS);
        }
    }

    private static void scheduleRespawn(CastContext context, FightState.HitTarget hit) {
        synchronized (context.fight()) {
            if (hit.combat().respawnScheduled) {
                return;
            }
            hit.combat().respawnScheduled = true;
        }
        context.scheduler().schedule(() -> {
            boolean respawned = context.fight().respawnIfDue(hit.combat(),
                    context.clock().instant());
            if (respawned) {
                Actor.State state = hit.combat().state();
                SceneLifecycle.CombatNpcInfo info = context.world().combatNpcInfo(hit.info().id());
                int ownerId = info == null ? 1 : info.ownerId();
                context.connection().writeFrame(FightMessages.npcCombatFrame(
                        hit.info().id(), ownerId, state.hp(), state.maxHp(),
                        state.logicState(), false));
                LOG.info("npc respawned id=" + hit.info().id());
            }
        }, FightState.RESPAWN_MS, TimeUnit.MILLISECONDS);
    }

    /** tag=4 才是 float32（Go useSkillCastTransform 同型宽容：错型取 0）。 */
    private static float floatValue(List<CustomC2S.Value> values, int index) {
        if (values.size() <= index) {
            return 0;
        }
        CustomC2S.Value value = values.get(index);
        return value.tag == 4 ? value.float32 : 0;
    }
}
