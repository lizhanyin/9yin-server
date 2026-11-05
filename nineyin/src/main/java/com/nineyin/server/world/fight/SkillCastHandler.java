package com.nineyin.server.world.fight;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.protocol.SceneMessages;
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
 * 伤害经 formula.lua（Lua 权威，Java 兜底）；击杀默认不删对象 15s 后满血重生，
 * 会话层击杀接管钩子（世界 BOSS）认领的目标改为消失不重生。
 */
public final class SkillCastHandler {

    /** slf4j（落盘）：JUL Logger 从不进 logback 文件——出招受理/拒绝曾全程不可见（63296 取证盲区）。 */
    private static final Logger LOG = LoggerFactory.getLogger(SkillCastHandler.class);

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
    /** 圈定技能落点宽容射程（米）：客户端按 TargetShape 本地限距，服务端仅防跨图。 */
    public static final float GROUND_RANGE_METERS = 30.0f;
    /** 冲锋招无 RushRange 时的射程回落（skill_static 实测 RushRange∈{8,15,30}）。 */
    public static final float RUSH_RANGE_FALLBACK_METERS = 15.0f;
    /**
     * 官方回怒规则（2026-11-03 用户确认）：怒气增长只与单位时间打击次数有关、与伤害
     * 高低无关——实招命中未格挡目标 +2/次（多段招每段每目标各算=叠加速更快）。
     */
    public static final int RAGE_PER_HIT_DEALT = 2;
    /** 官方被击回怒（预留：本阶段无 NPC 攻击玩家路径，接线时用）：未格挡 +1/格挡 +2。 */
    public static final int RAGE_TAKEN_UNPARRIED = 1;
    public static final int RAGE_TAKEN_PARRIED = 2;
    /** 武技连击 NPC debuff 租约（参考服 55866 myjf08 逐跳滚动 ~2s 量级）。 */
    public static final long WUJI_DEBUFF_LEASE_MS = 2_000;
    /** 冲锋暂存窗口（IDA stagePreCast msg25 skill_pause 恒 30000ms；超时未落地作废）。 */
    public static final long RUSH_PENDING_WINDOW_MS = 30_000;
    /**
     * 冲锋到达半径（米，2026-11-05 用户拍板时序「到达限制阈值时下发冲锋标记、到达
     * 目标位置后才正式触发特效伤害」）：按下时距目标超过本半径 → 只发冲锋标记
     * （msg25 暂存+msg24 c61 疾跑去目标位，不结算）；msg15/212 上报进入本半径
     * （=到达目标身位）才落地续招正式出招。取值依据：61653 实证客户端疾跑终点
     * =目标位、到过 1m 内；近战身位 8m 偏远（8m 出伤仍是「阈值处释放」），碰撞
     * 停距 ~1-3m，5m 兼大体型 NPC 停距余量。
     */
    public static final float RUSH_ARRIVAL_RADIUS_METERS = 5.0f;
    /** 战斗闲时退出扑空后的重查周期（毫秒）。 */
    public static final long COMBAT_IDLE_RETRY_MS = 2_000;

    private static double square(float value) {
        return (double) value * value;
    }

    /**
     * 出招上下文（LoginFlow 装配；learnedSkills 为 View40 已学集 id→level，skillRows 为 id→View40 行号）。
     * npcKillOverride：击杀接管钩子（世界 BOSS 消失不重生）——认领目标对象号并返回
     * true 时跳过 15s 重生，由会话层善后；null = 全部走默认重生。
     */
    public record CastContext(
            Connection connection, PlayerActor player, SceneLifecycle world, FightState fight,
            Map<String, Integer> learnedSkills, Map<String, Integer> skillRows,
            ScheduledExecutorService scheduler, Clock clock, Random random,
            IntPredicate npcKillOverride) {

        /** 兼容构造（无击杀接管）：既有装配点与测试沿用 9 参签名。 */
        public CastContext(Connection connection, PlayerActor player, SceneLifecycle world, FightState fight,
                           Map<String, Integer> learnedSkills, Map<String, Integer> skillRows,
                           ScheduledExecutorService scheduler, Clock clock, Random random) {
            this(connection, player, world, fight, learnedSkills, skillRows, scheduler, clock, random, null);
        }
    }

    /** 暂存的已生效 buff（帧与到期清理用）。 */
    private record StagedBuff(int slot, long staticData, String configId, Instant expiresAt) {
    }

    public static void handle(CastContext context, CustomC2S.Message custom) {
        handleValues(context, custom.values);
    }

    /** 值列表直驱（冲锋落地续招重组 211 复用同一条受理链）。 */
    static void handleValues(CastContext context, List<CustomC2S.Value> values) {
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
        // 211 长格式（111B，圈定/指向/冲锋技能；短格式 87B 无这些值——宽容解析得 0/空）：
        // idx 6-8 方向点 tx/ty/tz（指向型非零）、idx 9 空串、idx 10 目标对象
        // （tag8，int64=objId|owner<<32）、idx 11-13 目标坐标。docs/combat-skill-211.md §1。
        CastPoint aim = new CastPoint(floatValue(values, 11), floatValue(values, 12),
                floatValue(values, 13));
        CastPoint dir = new CastPoint(floatValue(values, 6), floatValue(values, 7),
                floatValue(values, 8));
        int aimObjId = 0;
        int aimOwnerId = 0;
        if (values.size() > 10 && values.get(10).tag == 8) {
            aimObjId = (int) (values.get(10).int64 & 0xFFFFFFFFL);
            aimOwnerId = (int) (values.get(10).int64 >>> 32);
        }

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
        // 套路武器限制（skill_use_limit ItemType 规则；拳/腿套路空集不闸）：不持对应
        // 武器静默拒（官方语义同 CD/超距族——客户端本地图标本就按武器置灰）。
        int equippedWeapon = context.player().equippedWeaponItemType();
        if (!skill.weaponAllowed(equippedWeapon)) {
            LOG.info("reject skill id=" + skillId + ": weapon type " + equippedWeapon
                    + " not allowed, requires " + skill.weaponItemTypes());
            return;
        }
        // 冲锋位移上限（CanRush 且 RushRange>0；无值回落 15 米）——圈定落点宽容用。
        double rushRange = skill.canRush()
                ? Math.max(skill.rushRangeM(), RUSH_RANGE_FALLBACK_METERS) : 0;
        // 出招射程闸（参考服 exe 61653 抓包实证，2026-11-05）：CanRush 且 RushRange>0
        // → RushRange（冲锋位移上限即判定值）；否则 TargetShapePara2（技能真实射程
        // ——myjf02：CanRush 无 RushRange，TSP2=6，目标 6.73m>6 → msg25 暂存+msg24
        // c61 起跑）；全无值回落 max(HitShape 半径, 近战身位 8m)。
        CombatSkillCatalog.SkillRowData rowData = CombatSkillCatalog.load()
                .skillRowData(skill.id(), skill.level());
        float shapeRange = rowData != null ? rowData.targetShapePara2() : 0;
        double castRange;
        if (skill.canRush() && skill.rushRange() > 0) {
            castRange = skill.rushRange();
        } else if (shapeRange > 0) {
            castRange = shapeRange;
        } else {
            castRange = Math.max(skill.areaRadius(), SELECTED_RANGE_METERS);
        }
        // 打坐（zs_default_01）：UseLimit=9「无法在战斗状态使用」——战斗中静默拒
        // （与 CD/超距族同语义）；受理后切打坐态（下方 toggle）。
        boolean sitcrossSkill = CombatSkillCatalog.SITCROSS_SKILL_ID.equals(skill.id());
        if (sitcrossSkill && context.player().actorState().logicState() == Actor.LOGIC_STATE_FIGHTING) {
            LOG.info("reject sitcross skill id=" + skill.id() + " while fighting");
            return;
        }
        FightState.SelectedTarget selected = null;
        CastPoint castPoint = null;
        if (skill.requiresTarget()) {
            selected = context.fight().livingSelectedTarget(context.world());
            if (selected == null) {
                LOG.info("reject skill id=" + skillId + " without living selected target");
                return;
            }
            // 攻击距离闸门：单体/以目标为中心的技能校验施法者(211 上报坐标)→目标距离。
            // 射程=castRange（真实射程模型见上方注释）；形状技能（以己圆/扇/矩）天然以
            // 施法者坐标为圆心，半径即射程，不走此闸。超距冲锋走暂存链（有客户端反馈），
            // 其余超距静默拒（客户端面板变红提示为本地行为）。
            SceneLifecycle.CombatNpcInfo targetInfo = context.world().combatNpcInfo(selected.objectId());
            if (targetInfo != null) {
                double distance = Math.sqrt(square(x - targetInfo.x()) + square(y - targetInfo.y())
                        + square(z - targetInfo.z()));
                // 冲锋标记两段时序（2026-11-05 用户拍板：到达限制阈值时下发冲锋的
                // 标记，到达目标位置后才正式触发技能特效造成伤害）：锁定+冲锋族
                // ①距目标超过到达半径（5m）→ 只发标记=暂存（msg25[30000,skill_pause]
                // + msg24 c1=61 疾跑去目标位，参考服 exe 61653 实证链路），不结算，
                // 也不吃 castRange 拒（TSP2 可小于到达半径，如 cqgf01=3m——否则
                // (TSP2,5m] 成静默死区）；msg15/212 上报进入到达半径才落地续招。
                // ②已在目标身位（≤5m）→ 直接受理正式出招。此前「进 castRange 即
                // 续招」=阈值处就出伤害（真机反馈「到达阈值就释放」），已按本时序修正。
                // 官服（正版抓包 op2）无此链、337 终点驱动本地冲——如需官服语义可
                // -Dnineyin.rush.stage=false 关回（60m 防跨图滥用兜底闸）。
                boolean stagedRushFamily = skill.canRush()
                        && "skilllock".equalsIgnoreCase(skill.script())
                        && rushStageEnabled();
                if (stagedRushFamily) {
                    if (distance > RUSH_ARRIVAL_RADIUS_METERS) {
                        stageRushCast(context, skill, targetInfo, distance);
                        return;
                    }
                } else {
                    double maxRange = castRange;
                    if (!rushStageEnabled() && skill.lockSkill()) {
                        maxRange = Math.max(maxRange, 60.0);
                    }
                    if (distance > maxRange) {
                        LOG.info("reject skill id=" + skillId + " out of range " + (int) distance
                                + "m > " + (int) maxRange + "m");
                        return;
                    }
                }
            }
        } else if (skill.targetMode() == CombatSkill.TargetMode.GROUND_AREA
                || skill.targetMode() == CombatSkill.TargetMode.GROUND_RECTANGLE) {
            // 圈定范围/腾空圈定（bug.txt:48-49）：免选中目标，落点=长格式目标对象/坐标。
            castPoint = resolveCastPoint(context, aimObjId, aimOwnerId, aim, dir);
            if (castPoint == null) {
                // 无任何落点线索：放行出招但伤害空集（Go "has no center; no targets
                // settled" 同款——客户端本地必有圈指示，正常不会走到）。
                LOG.info("ground skill id=" + skillId + " has no cast point; no targets settled");
            } else {
                double distance = Math.sqrt(square(x - castPoint.x()) + square(y - castPoint.y())
                        + square(z - castPoint.z()));
                double maxRange = Math.max(GROUND_RANGE_METERS, rushRange);
                if (distance > maxRange) {
                    LOG.info("reject ground skill id=" + skillId + " cast point out of range "
                            + (int) distance + "m > " + (int) maxRange + "m");
                    return;
                }
            }
        }
        Instant now = Instant.ofEpochMilli(context.clock().instant().toEpochMilli());
        // Go skill_combat.go：无目标技能的 CurSkillTarget 恒为自己（targetID 默认
        // playerObjectID，requiresTarget 才覆盖）；参考服 exe n=188 面板实证
        // CurSkillTarget=obj(玩家)。空对象会让客户端自目标动作门（读 CurSkill*
        // 元组 + ModifySkillLockTime）误判，轻功空中出招被打断拉回地面。
        long targetKey = (PlayerActor.PLAYER_OBJECT_ID & 0xFFFFFFFFL)
                | ((long) PlayerActor.PLAYER_OWNER_ID << 32);
        if (selected != null) {
            targetKey = (selected.objectId() & 0xFFFFFFFFL) | ((long) selected.ownerId() << 32);
        }
        // 顶掉补删（2026-11-04 对拍 §D8）：GCD 不硬闸，1-3s 连招会把上一招的收尾定时器
        // 顶掉（clearCurrentSkill(旧id) 失败 → 29 加 18 删）。新招受理时上一招仍挂着的，
        // 立即补发它的 0x12+清除帧——add/del 恒 1:1（参考服 8/8）；旧定时器按 epoch 失配空转。
        String supersededSkill = context.player().currentSkillId();
        PlayerActor.SkillGate gate = context.player().beginSkillUse(skill, targetKey, now);
        if (!gate.allowed()) {
            LOG.info("reject skill id=" + skillId + ": " + gate.reason());
            return;
        }
        Connection connection = context.connection();
        if (!supersededSkill.isEmpty()) {
            connection.writeFrame(FightMessages.activeSkillRowRemove(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
            connection.writeFrame(context.player().skillStateClearUpdate());
            LOG.info("close superseded skill id=" + supersededSkill + " early (delete row + clear frame)");
        }

        PlayerActor player = context.player();
        String playerName = player.snapshotName();
        // 本招代次（收尾定时器配对用；beginSkillUse 已自增）。
        long castEpoch = player.currentSkillEpoch();
        // 打坐受理：切 LogicState=102/0（toggle，出招面板 LogicState 随帧下发；回复由
        // LoginFlow 1s ticker 的 sitcrossTick 结算）。动作客户端原生（msg20 自带 id 即可）。
        if (sitcrossSkill) {
            boolean sitting = player.sitting();
            player.applySitcross(!sitting);
            LOG.info("sitcross skill accepted -> LogicState=" + (sitting ? 0 : 102));
        } else if (player.sitting()) {
            // 打坐中出任意武法=先起立再出招（2026-11-04 真机 63296 实证：客户端此路
            // 不发 240/2 本地打断，服务端不退坐则 LogicState=102 随出招面板回发、
            // sitcrossTick 持续结算，武法被坐姿压住不出）。起立先回全量 0x10 vital
            // ——参考服 52905 抓包：坐/立迁移恒走 188 属性全量面板（LogicState 为首
            // 属性、唯一变更项），非 6 值技能元组；随后出招面板照常。命中进战斗的
            // 1 态由 enterCombat 后续自推。
            player.applySitcross(false);
            // 2026-11-05：起立面板改 188 名瘦集（castVitalUpdate 与参考 52905 #5470
            // 逐名一致）——全量 vitalUpdate 夹带 Mount/CurRideMount 等登录域属性，
            // 重推 Mount 族会清客户端动作/移动态（打坐动画打断根因）。
            connection.writeFrame(player.castVitalUpdate());
            LOG.info("stand up from sitcross on cast skill id=" + skill.id()
                    + " (full vital 0x10, ref 52905 shape)");
        }

        // buff 先入租约再写全量面板（参考服 exe 形态：出招 buff 只随 0x10 面板的
        // BufferInfoN 下发，无独立 msg169/槽帧——127-0-0-1-50768 三招 customs 无 169、
        // 无独立槽帧实证；Go stagePlayerSkillEffects 亦在 vitalUpdate 之前）。
        List<StagedBuff> staged = stageSkillBuffs(context, skill, now);

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
        // CanUse 置 0（View40 行；不置则真机技能图标恒灰）；个人 CD 尽时置 1。
        // 官方 CanUse 值恒 1、不按资源门控（52795 抓包：怒招行 SP=26<消耗 50 仍推 1，
        // 出招/CD 尽也均不推）——资源灰由客户端按 SP+行内 AConsumeSP 自算，服务端
        // 只做出招置 0 + 周期保活批（LoginFlow ticker）。出招技能自身不吃公共 GCD
        // （同套路不触发 7s，bug.txt；2026-11-02 前 max(个人,GCD) 把 cd<7s 的技能
        // 图标也压到 7s，已改回个人 CD）
        Integer row = context.skillRows().get(skill.id());
        if (row != null && row > 0) {
            connection.writeFrame(FightMessages.skillRowCanUse(SKILL_VIEW_ID, row, false));
            long canUseAt = skill.personalCdMs();
            // 大括号块保证解析到 schedule(Runnable)：单表达式 lambda 会因返回值落到 Callable 重载
            context.scheduler().schedule(() -> {
                context.connection().writeFrame(FightMessages.skillRowCanUse(
                        SKILL_VIEW_ID, row, true));
            }, Math.max(canUseAt, 1), TimeUnit.MILLISECONDS);
        }
        // 玩家全量属性面（参考服出招瘦集 = CAST_PANEL_NAMES；登录面板的移动模式/
        // 外观属性官方不随出招重发，重推 Mount='' 等会把空中人物打回地面）。
        connection.writeFrame(player.castVitalUpdate());
        // msg337 施法者位置/朝向回显=211 上报原地（官服每招恒发）。**冲锋技服务端同样
        // 只锚原地、不携终点**——op2 抓包字节级实证：同一出招的 0x1F 与 337 的 xyz 三元
        // 组完全相同且恒=上报原地（此前"337=终点"为手算浮点误读，已勘误）。冲锋位移=
        // 客户端本地执行（按技能 lock data 自播冲刺动画，官服全程无玩家位移帧）；服务端
        // 若在 337/0x1F 携终点，客户端按权威位置直接贴坐标=瞬移无动画（2026-11-05 真机
        // 两轮反馈）。冲锋技在 337 前补 0x1F 原地锚定（官服冲锋簇帧序含此对帧）。
        if (skill.canRush() && skill.rushRange() > 0) {
            connection.writeFrame(SceneMessages.serverLocation(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    new com.nineyin.server.db.role.Roles.Position(x, y, z, orient)));
        }
        connection.writeFrame(FightMessages.casterTransformSync(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, x, y, z, orient));
        ActionTarget actionTarget = resolveActionTarget(context, skill, selected, aimObjId, aimOwnerId);
        connection.writeFrame(FightMessages.skillAction(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, skill.id(),
                actionTarget.objectId(), actionTarget.ownerId()));

        List<FightState.HitTarget> targets = resolveTargets(context, skill, x, y, z, orient, castPoint);
        // NPC 反击 AI 拉起（§D1）：打中过谁，谁开始还手（循环自续，失效自停）。
        if (!targets.isEmpty()) {
            NpcCombatAi.playerEngaged(context, targets, x, y, z);
        }
        if (skill.hitFrameMs().isEmpty()) {
            // 单段技能：原路径（即时结算，帧序对齐 62433 抓包）。
            List<Hit> hits = settleTargets(context, skill, targets, now);
            // 命中才进战斗（62433 抓包：出招 vital 仍 LogicState=0，命中后切 1）
            if (hits.stream().anyMatch(hit -> hit.damage() > 0) && player.enterCombat(now)) {
                connection.writeFrame(player.logicStateFrame());
            }
            emitPerTargetSequence(context, skill, hits, playerName);
            emitWujiSequence(context, skill, hits, 0);
            awardRagePerHit(context, hits);
            applyTargetControl(context, skill, hits, now);
            feedMeridianZhenQi(context, hits);
        } else {
            // 多段技能（zhaoshi trigger_effect 帧位）：每段一次全套命中帧
            // （52795 抓包实证：每段恒 [NPC 0x10, 0x13, msg24, 0x2D, msg18, msg205]，
            // 同招各段伤害值相同=每招掷一次、段间重复；死亡目标后续段跳过）。
            scheduleMultiHits(context, skill, targets, hitTimes(skill), playerName);
        }
        scheduleEndOfAction(context, skill, staged, castEpoch);
        scheduleCombatIdleExit(context);
        LOG.info("accepted skill id=" + skill.id() + " mp=" + player.actorState().mp()
                + " sp=" + player.sp() + " targets=" + targets.size()
                + (skill.hitFrameMs().isEmpty() ? "" : " ticks=" + skill.hitFrameMs().size())
                + " personal_cd=" + skill.personalCdMs() + "ms public_cd=" + skill.publicCdMs() + "ms");
    }

    /**
     * 冲锋暂存链开关（**默认开**，2026-11-05 用户拍板对齐参考服 exe 61653 抓包：超射程
     * 冲锋下发 msg25 暂存+msg24 c61 起跑而非静默拒/直接受理）；官服（正版）无此链，
     * -Dnineyin.rush.stage=false 可关回官服语义（337 终点驱动客户端本地冲）。
     */
    static boolean rushStageEnabled() {
        return Boolean.parseBoolean(System.getProperty("nineyin.rush.stage", "true"));
    }

    /**
     * 冲锋暂存（IDA stagePreCast 逐帧对齐 + 参考服 61653 抓包实测：myjf02 目标
     * 6.73m&gt;TSP2 6m → msg25[30000,skill_pause]+msg24[玩家,61,目标位置,2,
     * qg_fastruning_s_2h_01]）：CurSkillID 帧 + msg25 暂存 + msg24 c1=61 疾跑
     * （目的地=目标位置）+ 暂存出招 30s。RushTarget/PendingSkill 两属性名不在
     * 0x09 协商表（exe 编码失败静默跳过）——同样不发。CD/资源在落地续招时才结。
     */
    private static void stageRushCast(CastContext context, CombatSkill skill,
                                      SceneLifecycle.CombatNpcInfo target, double distance) {
        Connection connection = context.connection();
        context.player().stagePendingCast(skill.id(), target.id(), target.ownerId(),
                context.clock().instant().plusMillis(RUSH_PENDING_WINDOW_MS));
        connection.writeFrame(context.player().curSkillIdFrame(skill.id()));
        connection.writeFrame(FightMessages.skillPauseFrame((int) RUSH_PENDING_WINDOW_MS));
        connection.writeFrame(FightMessages.dashRushFrame(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                target.x(), target.y(), target.z()));
        context.player().markRushInFlight();
        LOG.info("rush staged skill id=" + skill.id() + " dist=" + (int) distance
                + "m dest=(" + (int) target.x() + "," + (int) target.y() + "," + (int) target.z() + ")");
    }

    /**
     * C2S msg212 冲锋落地（IDA handleCastDashLanding 逐帧对齐；官服抓包无此消息，
     * 暂存链默认开启时触发续招，-Dnineyin.rush.stage=false 时仅回显）：
     * {@code [212, f32 x, f32 y, f32 z, (f32 朝向)]} → 清冲锋态 + msg337 回显新坐标 +
     * msg26 解除 skill_pause + 取暂存出招（30s 过期作废）→ 重组 211 值列表（10 值短形，
     * 无目标对象——目标回落选中态）重入受理链正式出招。
     */
    public static void handleDashLanding(CastContext context, CustomC2S.Message custom) {
        List<CustomC2S.Value> values = custom.values;
        if (values.size() < 5 || values.get(1).tag != 4 || values.get(2).tag != 4
                || values.get(3).tag != 4) {
            LOG.info("reject dash landing malformed values=" + values.size());
            return;
        }
        float orient = values.size() > 4 && values.get(4).tag == 4 ? values.get(4).float32 : 0f;
        resumePendingCastAt(context, values.get(1).float32, values.get(2).float32,
                values.get(3).float32, orient, "dash-landing");
    }

    /**
     * 冲锋落地续招本体（212 与 msg15 到位探测共用）：清冲锋态 + msg337 回显坐标 +
     * msg26 解除 skill_pause + 取暂存出招 → 重组 211（10 值短形，目标回落选中态）
     * 重入受理链正式出招（msg20 动作三分/CD 行/伤害段=官服受理簇同款）。
     */
    private static void resumePendingCastAt(CastContext context, float x, float y, float z,
                                            float orient, String trigger) {
        context.player().clearRushInFlight();
        context.connection().writeFrame(FightMessages.casterTransformSync(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, x, y, z, orient));
        PlayerActor.PendingCast pending = context.player()
                .consumePendingCast(context.clock().instant()).orElse(null);
        if (pending == null) {
            LOG.info("dash landing without live pending cast (expired or none)");
            return;
        }
        context.connection().writeFrame(FightMessages.skillPauseClearFrame());
        List<CustomC2S.Value> resume = List.of(
                CustomC2S.Value.ofInt(CUSTOM_USE_SKILL),
                CustomC2S.Value.ofText(pending.skillId()),
                CustomC2S.Value.ofFloat(x), CustomC2S.Value.ofFloat(y), CustomC2S.Value.ofFloat(z),
                CustomC2S.Value.ofFloat(orient),
                CustomC2S.Value.ofFloat(0f), CustomC2S.Value.ofFloat(0f), CustomC2S.Value.ofFloat(0f),
                CustomC2S.Value.ofText(""));
        LOG.info("dash landing resume skill id=" + pending.skillId() + " via " + trigger);
        handleValues(context, resume);
    }

    /**
     * msg15 移动上报驱动的冲锋到位续招（2026-11-05 用户拍板时序：到达目标位置后才
     * 正式触发特效伤害）：客户端疾跑到位后不必然发 212（61653 实证：玩家到过目标
     * 1m 内仍未发）——服务端在每次移动上报时探测，玩家与暂存目标距离进入**到达
     * 半径**（{@link #RUSH_ARRIVAL_RADIUS_METERS}=近身身位，非技能射程——「进射程
     * 即出伤害」即真机反馈的「到达阈值就释放」）才落地续招。212 路径保留
     * （consumePendingCast 幂等，双触发只生效一次）。
     */
    public static void tryRushArrival(CastContext context, float x, float y, float z, float orient) {
        PlayerActor player = context.player();
        if (!player.rushInFlight()) {
            return;
        }
        PlayerActor.PendingCast pending = player.peekPendingCast(context.clock().instant());
        if (pending == null) {
            return;
        }
        SceneLifecycle.CombatNpcInfo target = context.world().combatNpcInfo(pending.targetObj());
        if (target == null) {
            return;
        }
        double distance = Math.sqrt(square(x - target.x()) + square(y - target.y())
                + square(z - target.z()));
        if (distance > RUSH_ARRIVAL_RADIUS_METERS) {
            return;
        }
        LOG.info("rush arrival at target skill id=" + pending.skillId() + " dist="
                + (int) distance + "m <= " + (int) RUSH_ARRIVAL_RADIUS_METERS + "m");
        resumePendingCastAt(context, x, y, z, orient, "msg15-arrival");
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
                return;
            }
            // 扑空重查（2026-11-04 真机：多段技 touchCombat 把活跃时刻推后 ~1s，一次性
            // +10s 任务判 9s<10s 失败后永无重试，LogicState 卡 Fighting「无法结束战斗」）。
            if (context.player().fighting()) {
                scheduleCombatIdleExitRetry(context);
            }
        }, COMBAT_IDLE_EXIT_MS, TimeUnit.MILLISECONDS);
    }

    /** 扑空后的短周期重查（2s 一拍直至退出；不再战斗即静默停）。 */
    private static void scheduleCombatIdleExitRetry(CastContext context) {
        context.scheduler().schedule(() -> {
            if (context.player().exitCombatIfIdle(
                    java.time.Duration.ofMillis(COMBAT_IDLE_EXIT_MS), context.clock().instant())) {
                context.connection().writeFrame(context.player().logicStateFrame());
                LOG.info("combat idle exit (retry) -> LogicState=NORMAL");
            } else if (context.player().fighting()) {
                scheduleCombatIdleExitRetry(context);
            }
        }, COMBAT_IDLE_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 伤害

    /** 单次命中的结算产物（damage=0 表示未命中/未结算，整段不发帧）。 */
    private record Hit(FightState.HitTarget target, int damage) {
    }

    /**
     * 出招时刻的目标解析（单体/以己形状/以目标为中心形状/地面落点形状；无伤害效果返回空表）。
     * GROUND_* 以 {@code castPoint}（211 长格式落点）为中心，矩形投影用施法者朝向。
     */
    private static List<FightState.HitTarget> resolveTargets(CastContext context, CombatSkill skill,
                                                             float x, float y, float z, float orient,
                                                             CastPoint castPoint) {
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
            case GROUND_AREA, GROUND_RECTANGLE -> {
                if (castPoint != null) {
                    targets.addAll(context.fight().shapedTargets(context.world(),
                            castPoint.x(), castPoint.y(), castPoint.z(), orient, skill));
                }
            }
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

    /**
     * 211 长格式落点中心（圈定/冲锋圈定技能）：可解析的目标对象（战斗 NPC）位置 →
     * 目标坐标(11-13)非零 → 方向点(6-8)非零 → 选中 NPC 位置（Go 近似 SelectedArea
     * 的行为兜底）→ null（无中心，伤害空集）。
     */
    private static CastPoint resolveCastPoint(CastContext context, int aimObjId, int aimOwnerId,
                                              CastPoint aim, CastPoint dir) {
        if (aimObjId != 0) {
            SceneLifecycle.CombatNpcInfo info = context.world().combatNpcInfo(aimObjId);
            if (info != null && info.combat() && info.ownerId() == aimOwnerId) {
                return new CastPoint(info.x(), info.y(), info.z());
            }
        }
        if (aim.present()) {
            return aim;
        }
        if (dir.present()) {
            return dir;
        }
        FightState.SelectedTarget selected = context.fight().livingSelectedTarget(context.world());
        if (selected != null) {
            SceneLifecycle.CombatNpcInfo info = context.world().combatNpcInfo(selected.objectId());
            if (info != null) {
                return new CastPoint(info.x(), info.y(), info.z());
            }
        }
        return null;
    }

    /** 211 长格式坐标点（全 0 视为未提供——世界原点不在可玩区域）。 */
    private record CastPoint(float x, float y, float z) {

        boolean present() {
            return x != 0f || y != 0f || z != 0f;
        }
    }

    /** msg20 动作目标（0,0=空对象）。 */
    private record ActionTarget(int objectId, int ownerId) {

        static final ActionTarget NONE = new ActionTarget(0, 0);
    }

    /**
     * msg20 动作帧的目标对象（57378/52795 官服抓包实证）：锁定/冲锋技（SkillLock
     * /CanRush，单体与圈定同规）=211 目标对象（校验战斗 NPC，失效回落选中目标）——
     * 客户端收到目标对象才本地执行冲锋位移（「伤害出了人没过去」根因即此参数恒空）；
     * 自增益招（IsDamage=0，气/架招族）=施法者自己；其余伤害技=空对象。
     */
    private static ActionTarget resolveActionTarget(CastContext context, CombatSkill skill,
                                                    FightState.SelectedTarget selected,
                                                    int aimObjId, int aimOwnerId) {
        if (skill.lockSkill()) {
            if (aimObjId != 0) {
                SceneLifecycle.CombatNpcInfo info = context.world().combatNpcInfo(aimObjId);
                if (info != null && info.combat() && info.ownerId() == aimOwnerId) {
                    return new ActionTarget(aimObjId, aimOwnerId);
                }
            }
            FightState.SelectedTarget fallback = selected != null ? selected
                    : context.fight().livingSelectedTarget(context.world());
            if (fallback != null) {
                return new ActionTarget(fallback.objectId(), fallback.ownerId());
            }
            return ActionTarget.NONE;
        }
        if (!skill.isDamage()) {
            return new ActionTarget(PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID);
        }
        return ActionTarget.NONE;
    }

    /**
     * 官方回怒：每次实招命中未格挡目标 +2（按打击次数不按伤害；多段招每段每目标
     * 各算）。变化才发单属性 SP 帧（饱和 0..100，满值不加帧）。可用提示不在此处
     * 推 CanUse——官方 SP 变化不触发行帧（52795：SP 跨 50 阈值无 CanUse 推送，
     * 图标点亮由客户端按 SP 自算）。
     */
    private static void awardRagePerHit(CastContext context, List<Hit> hits) {
        int landed = 0;
        for (Hit hit : hits) {
            if (hit.damage() > 0) {
                landed++;
            }
        }
        awardRage(context, landed * RAGE_PER_HIT_DEALT);
    }

    private static void awardRage(CastContext context, int delta) {
        if (delta <= 0) {
            return;
        }
        PlayerActor player = context.player();
        int before = player.sp();
        PlayerActor.SPUpdate update = player.addSP(delta);
        if (update.sp() != before) {
            context.connection().writeFrame(update.frame());
        }
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
                        onNpcKilled(context, target);
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
     * 目标控制并刷新战斗活跃时刻；末段后按整招累计伤害喂经脉真气。
     * （buff 已随出招面板下发，段间无 buff 帧——参考服 exe 形态。）
     */
    private static void scheduleMultiHits(CastContext context, CombatSkill skill,
                                          List<FightState.HitTarget> targets,
                                          List<Long> times, String playerName) {
        int damage = rollDamage(context, skill);
        if (targets.isEmpty() || damage <= 0) {
            return; // 无目标/未掷中：零命中零帧
        }
        long[] landedTotal = {0};
        boolean[] firstTick = {true};
        long lastAt = times.get(times.size() - 1);
        for (int tick = 0; tick < times.size(); tick++) {
            long at = times.get(tick);
            final boolean lastTick = at == lastAt;
            final int tickIndex = tick;
            context.scheduler().schedule(() -> {
                Instant now = context.clock().instant();
                List<Hit> hits = new ArrayList<>(targets.size());
                boolean killed = false;
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
                        killed = true;
                        onNpcKilled(context, target);
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
                emitPerTargetSequence(context, skill, hits, playerName);
                emitWujiSequence(context, skill, hits, tickIndex);
                if (killed && skill.wujiCombo()) {
                    // 击杀红名标记（参考服 55866：myjf08 击杀后 [1, buf_fixed_red, 玩家…]）
                    context.connection().writeFrame(FightMessages.skillBuff(1,
                            PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                            PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                            "buf_fixed_red", playerName));
                }
                awardRagePerHit(context, hits); // 每段每目标各 +2（官方多段叠加速更快）
                landedTotal[0] += (long) damage * hits.size();
                if (lastTick) {
                    feedZhenQiAmount(context, (int) Math.min(landedTotal[0], Integer.MAX_VALUE));
                }
            }, at, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * formula.lua（Lua 权威/Java 兜底）：attack=基础+装备+内功掷值（面板同口径），
     * 守方侧当前取缺省。掷骰序：miss/crit 先掷、区间掷值后掷——前两者不依赖区间
     * 跨度，同种子下命中/暴击结论稳定（可测试）。
     */
    private static int rollDamage(CastContext context, CombatSkill skill) {
        double missRoll = context.random().nextDouble();
        double critRoll = context.random().nextDouble();
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
                missRoll, critRoll);
        CombatFormulas.Result result = LuaScriptService.getInstance().calc(input);
        return result.miss() ? 0 : (int) Math.min(result.damage(), Integer.MAX_VALUE);
    }

    private static void emitPerTargetSequence(CastContext context, CombatSkill skill,
                                              List<Hit> hits, String playerName) {
        Connection connection = context.connection();
        for (Hit hit : hits) {
            if (hit.damage() <= 0) {
                continue; // 未命中目标：整段不发（HP 无变化）
            }
            FightState.HitTarget target = hit.target();
            Actor.State state = target.combat().state();
            SceneLifecycle.CombatNpcInfo info = target.info();
            connection.writeFrame(FightMessages.npcCombatFrame(info.id(), info.ownerId(),
                    state.hp(), state.maxHp(), state.logicState(), state.hp() <= 0));
            connection.writeFrame(FightMessages.fightRelationThreat(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    target.combat().threat()));
            connection.writeFrame(FightMessages.skillDamageFloat(info.id(), info.ownerId(),
                    hit.damage(), skill.id(), skill.scriptLabel(), info.modelOrFallback(), playerName));
            connection.writeFrame(FightMessages.hitEffect(info.id(), info.ownerId(),
                    state.maxHp() <= 0 ? 0 : state.hp() * 100 / state.maxHp()));
            connection.writeFrame(FightMessages.hurtAction(info.id(), info.ownerId(),
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
            connection.writeFrame(FightMessages.hitConfirm(PlayerActor.PLAYER_OBJECT_ID,
                    PlayerActor.PLAYER_OWNER_ID, skill.id(), info.id(), info.ownerId()));
        }
    }

    /**
     * 武技族出招附加帧（2026-11-04 对拍 §D2，参考服 55866 myjf03/myjf08 实证）：
     *
     * <ul>
     *   <li>wuji_* 族（LevelupRelevance）：首跳摘除玩家自身 {@code buf_<技能>_01}
     *       （六值 169 v0=0 形态；myjf03 每招一次）。</li>
     *   <li>wuji_* + 怒招（PackCategory=2，如 myjf08）：每跳对每个受害 NPC 追加
     *       目标 debuff 169（v0=1 带到期毫秒，承载=NPC/施法者=玩家）+ 一条
     *       msg24 c1=71 武技连击段（段名 ''/“_2”/“_3”/“_4”，受害与第二目标轮转）。</li>
     * </ul>
     */
    private static void emitWujiSequence(CastContext context, CombatSkill skill,
                                         List<Hit> hits, int tickIndex) {
        if (!skill.wujiRelevance() || hits.isEmpty()) {
            return;
        }
        Connection connection = context.connection();
        String playerName = context.player().snapshotName();
        if (tickIndex == 0) {
            connection.writeFrame(FightMessages.skillBuff(PlayerActor.PLAYER_OBJECT_ID,
                    PlayerActor.PLAYER_OWNER_ID, PlayerActor.PLAYER_OBJECT_ID,
                    PlayerActor.PLAYER_OWNER_ID, "buf_" + skill.id() + "_01", playerName));
        }
        if (!skill.wujiCombo()) {
            return;
        }
        Instant now = context.clock().instant();
        for (Hit hit : hits) {
            if (hit.damage() <= 0) {
                continue;
            }
            SceneLifecycle.CombatNpcInfo info = hit.target().info();
            connection.writeFrame(FightMessages.targetDebuffTimed(
                    "buf_" + skill.id(), info.id(), info.ownerId(),
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, playerName,
                    now.toEpochMilli() + WUJI_DEBUFF_LEASE_MS));
        }
        // 连击段（上限 4 段：''/ _2/ _3/ _4；参考服 myjf08 四跳四段）；受害/第二目标轮转
        if (tickIndex > 3) {
            return;
        }
        String stage = tickIndex == 0 ? "" : skill.levelupRelevance() + "_" + (tickIndex + 1);
        Hit first = hits.get(0);
        Hit second = hits.size() > 1 ? hits.get(1) : first;
        connection.writeFrame(FightMessages.wujiComboFloat(
                first.target().info().id(), first.target().info().ownerId(),
                second.target().info().id(), second.target().info().ownerId(),
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, stage));
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
        // Go activateSkillBuff 槽位序：preferredSlot → 同 ConfigID 现存租约复用 →
        // 空闲扫描(12..24，系统租约区 1..11 不可写)。
        int slot = buff.preferredSlot();
        if (slot <= 0) {
            slot = player.reuseBuffSlot(buff.configId(), now);
        }
        if (slot <= 0) {
            slot = player.freeBuffSlot();
            if (slot <= 0) {
                LOG.info("no free BuffInfo slot for " + buff.configId());
                return null;
            }
        }
        evictReplaceCategoryBuffs(context, buff, slot, now);
        long lifetime = buff.lifetimeMs() > 0 ? buff.lifetimeMs() : CombatSkillCatalog.DEFAULT_BUFF_LEASE_MS;
        Instant expiresAt = now.plusMillis(lifetime);
        player.activateBuff(slot, buff.staticData(), buff.configId(), buff.level(), expiresAt);
        return new StagedBuff(slot, buff.staticData(), buff.configId(), expiresAt);
    }

    /**
     * 官方互斥组逐出（buff_static ReplaceCategory，2026-11-03）：同组新 buff 顶掉其它
     * 槽位的旧 buff——架招族 Category=9/ReplaceCategory=220214（太极 6013/6055 与花神
     * 15101 同组），「架招 buff 只能有一个」。逐出即发空槽帧（客户端 BuffStore 按槽
     * 权威，不做本地替换）；本槽复用（同 configId 或同 preferredSlot）不逐出自己。
     */
    private static void evictReplaceCategoryBuffs(CastContext context, CombatSkill.Buff buff,
                                                  int targetSlot, Instant now) {
        CombatSkillCatalog catalog = CombatSkillCatalog.load();
        long replaceCategory = catalog.buffReplaceCategory(buff.staticData());
        if (replaceCategory <= 0) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> active
                : context.player().activeBuffStaticDatas(now).entrySet()) {
            int slot = active.getKey();
            if (slot == targetSlot
                    || catalog.buffReplaceCategory(active.getValue()) != replaceCategory) {
                continue;
            }
            if (context.player().clearBuffSlot(slot)) {
                context.connection().writeFrame(context.player().buffSlotFrame(slot, ""));
                LOG.info("evict same replace_category=" + replaceCategory + " buff slot=" + slot
                        + " for incoming " + buff.configId());
            }
        }
    }

    /**
     * 动作总时长后：0x12 删 active_skill_rec + State="stand"；buff 到期清槽发空帧。
     * 删行按 (id, epoch) 配对——被新招顶掉的旧招在此空转（§D8），同技能快速复读
     * 也不会误删新招的行。
     */
    private static void scheduleEndOfAction(CastContext context, CombatSkill skill,
                                            List<StagedBuff> staged, long castEpoch) {
        long delay = Math.max(skill.totalActionDurationMs(), 1);
        context.scheduler().schedule(() -> {
            if (context.player().clearCurrentSkill(skill.id(), castEpoch)) {
                context.connection().writeFrame(FightMessages.activeSkillRowRemove(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
                // 技能状态清除帧（参考服 exe 收招恒发 #2014；此前缺失致客户端技能
                // 状态机滞留，配合 5000ms 硬直锁为空中位置重置根因之一）。
                context.connection().writeFrame(context.player().skillStateClearUpdate());
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

    /**
     * 击杀分发：会话层击杀接管钩子（世界 BOSS）认领目标 → 跳过重生由其善后
     * （摘对象 + 面板回执）；未认领走默认 15s 满血重生。
     */
    private static void onNpcKilled(CastContext context, FightState.HitTarget hit) {
        IntPredicate override = context.npcKillOverride();
        if (override != null && override.test(hit.info().id())) {
            return;
        }
        scheduleRespawn(context, hit);
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
