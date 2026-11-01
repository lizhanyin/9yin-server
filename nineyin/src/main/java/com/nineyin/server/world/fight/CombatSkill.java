package com.nineyin.server.world.fight;

import java.time.Duration;
import java.util.List;

/**
 * 编译完成的战斗技能定义（skill_catalog.go combatSkillDefinition 的 Java 对应物，
 * 编译来源与字段语义见 docs/combat-skill-211.md §5/§6）。
 *
 * @param id              技能 ConfigID（如 CS_wd_tjq01）
 * @param staticData      skill_new.StaticData
 * @param script          SkillNormal / SkillLock
 * @param cooldownCategory 个人 CD 类别（cooldown_rec 第 1 列）
 * @param cooldownTeam    skill_static.CoolDownTeam（GCD 行第 4 列；0 = 无）
 * @param personalCdMs    varprop L CoolDownTime（个人 CD 毫秒）
 * @param publicCdMs      AddCoolDownTime（公共 GCD 毫秒，缺省回落个人 CD）
 * @param mpCost/spCost/qgCost skill_consume 的 AConsumeMP/SP/QGP
 * @param baseDamage      damage_calculate r 字段第 3 段求和（编译伤害）
 * @param damageWaves     正 r 段数（伤害倍率分母：ratio = baseDamage/100/waves）
 * @param targetMode      目标模式（attack_hitshape.HitShape 映射）
 * @param areaRadius      圆半径/扇形半径/矩形长度（HitShapePara2）
 * @param areaHeight      varprop HeightDiff（高度差过滤）
 * @param sectorAngle     扇形角度（HitShapePara1）
 * @param areaWidth       矩形宽度（HitShapePara1）
 * @param actionName      zhaoshi 时间线首段动作名（当前仅用于总时长；S2C 动作走 msg20）
 * @param actionDurationMs 首段动作时长（endFrame/30fps）
 * @param followups       后续动作段（编译保留；抓包显示 F exe 不下发 followup State 帧）
 * @param effects         效果列表（伤害/自buff/目标控制/红霸体/架势轮换）
 */
public record CombatSkill(
        String id, int staticData, String script, int level,
        int cooldownCategory, int cooldownTeam, long personalCdMs, long publicCdMs,
        int mpCost, int spCost, int qgCost,
        int baseDamage, int damageWaves,
        TargetMode targetMode, float areaRadius, float areaHeight, float sectorAngle, float areaWidth,
        String actionName, long actionDurationMs, List<ActionSegment> followups,
        List<Effect> effects) {

    public enum TargetMode {
        SELF, SELECTED, CASTER_CIRCLE, CASTER_SECTOR, CASTER_RECTANGLE, SELECTED_AREA
    }

    /** zhaoshi 时间线段（30fps 帧换算毫秒）。 */
    public record ActionSegment(String action, long atMs, long durationMs) {
    }

    /** buf_&lt;技能ID&gt; 精确 buff 或架势变体。 */
    public record Buff(String configId, long staticData, int level, long lifetimeMs, int preferredSlot) {
    }

    public enum EffectKind {
        DAMAGE, PLAYER_BUFF, TARGET_CONTROL, RED_ARMOR, STANCE_CYCLE
    }

    /** kind=DAMAGE 用 amount；PLAYER_BUFF/TARGET_CONTROL 用 buff；STANCE_CYCLE 用 buffVariants。 */
    public record Effect(EffectKind kind, int amount, Buff buff, List<Buff> buffVariants) {

        static Effect damage(int amount) {
            return new Effect(EffectKind.DAMAGE, amount, null, null);
        }

        static Effect buff(EffectKind kind, Buff buff) {
            return new Effect(kind, 0, buff, null);
        }
    }

    /** Go totalActionDuration：首段与各后续段 at+duration 的最大值。 */
    public long totalActionDurationMs() {
        long duration = actionDurationMs;
        for (ActionSegment segment : followups) {
            if (segment.atMs() + segment.durationMs() > duration) {
                duration = segment.atMs() + segment.durationMs();
            }
        }
        return duration;
    }

    public boolean requiresTarget() {
        return targetMode == TargetMode.SELECTED || targetMode == TargetMode.SELECTED_AREA;
    }

    /** 伤害倍率（formula.lua 的 ratio；<=0 由公式按 1.0 处理）。 */
    public float damageRatio() {
        if (damageWaves <= 0) {
            return 0;
        }
        return baseDamage / 100f / damageWaves;
    }

    /** GCD 门控键（Go teamKey：有队用队，无队用负类别）。 */
    public long cooldownGateKey() {
        return cooldownTeam != 0 ? cooldownTeam : -(long) cooldownCategory;
    }

    public Duration personalCd() {
        return Duration.ofMillis(personalCdMs);
    }

    public CombatSkill {
        followups = followups == null ? List.of() : List.copyOf(followups);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }
}
