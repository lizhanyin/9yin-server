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
 * @param taolu           skill_static.TaoLu（套路 ConfigID，如 CS_jh_sfgp；同套路共享。
 *                        GCD 只跨套路生效——同套路技能互不触发 7s 公共 CD）
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
 * @param canRush         skill_static.CanRush（冲锋招：射程闸放宽到 rushRangeM）
 * @param rushRangeM      skill_static.RushRange（冲锋射程米；0=无值）
 * @param isDamage        skill_static.IsDamage（自增益招=false：msg20 动作目标=自己）
 * @param actionName      zhaoshi 时间线首段动作名（当前仅用于总时长；S2C 动作走 msg20）
 * @param actionDurationMs 首段动作时长（endFrame/30fps）
 * @param followups       后续动作段（编译保留；抓包显示 F exe 不下发 followup State 帧）
 * @param hitFrameMs      多段判定时刻（zhaoshi trigger_effect 帧/30fps，剔除与技能同名的
 *                        施法特效帧；52795 抓包实证：gxfhj02 的 4 个 _hurt 触发帧=每招 4 段、
 *                        shl08 的 6 个=6 段、无 trigger_effect 的技能单段即时结算）
 * @param effects         效果列表（伤害/自buff/目标控制/红霸体/架势轮换）
 * @param weaponItemTypes 武器限制（skill_new.UseLimit → skill_use_limit 的 ItemType 规则：
 *                        101刀/102单手剑/103匕首/104双刀/105双剑/106双刺/107长棍/108短棍/
 *                        111-117暗器族；空集=无限制——拳/腿套路空手可出）
 */
public record CombatSkill(
        String id, int staticData, String script, int level,
        int cooldownCategory, int cooldownTeam, String taolu, long personalCdMs, long publicCdMs,
        int mpCost, int spCost, int qgCost,
        int baseDamage, int damageWaves,
        TargetMode targetMode, float areaRadius, float areaHeight, float sectorAngle, float areaWidth,
        boolean canRush, float rushRangeM, boolean isDamage,
        String actionName, long actionDurationMs, List<ActionSegment> followups,
        List<Long> hitFrameMs,
        List<Effect> effects,
        java.util.Set<Integer> weaponItemTypes) {

    /**
     * GROUND_AREA/GROUND_RECTANGLE 为玩家点选地面落点形状（attack_hitshape 11/12，
     * bug.txt「可圈定范围的技能/腾空圈定/冲锋圈定」）：免选中目标，圆心=211 长格式
     * 落点（目标对象/目标坐标/方向坐标，见 SkillCastHandler.CastPoint）。Go 参考服
     * 把 11 近似归入 SelectedArea（以选中怪为圆心），此处按需求超越 Go。
     */
    public enum TargetMode {
        SELF, SELECTED, CASTER_CIRCLE, CASTER_SECTOR, CASTER_RECTANGLE, SELECTED_AREA,
        GROUND_AREA, GROUND_RECTANGLE
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

    /**
     * 是否要求选中目标：单体/以目标为中心的**伤害技**才要求。无伤害技（气/架招/
     * 打坐族，IsDamage=0）免选中——52795 抓包 jysg06 短格式（无目标对象）照样受理
     * 全套出招帧；打坐登录后无选中也须可出（bug.txt「打坐」）。
     */
    public boolean requiresTarget() {
        return isDamage && (targetMode == TargetMode.SELECTED
                || targetMode == TargetMode.SELECTED_AREA);
    }

    /** 武器门控：空集=无限制（拳/腿套路）；否则已穿武器 ItemType 须在允许集内（0=空手必拒）。 */
    public boolean weaponAllowed(int equippedWeaponItemType) {
        return weaponItemTypes.isEmpty() || weaponItemTypes.contains(equippedWeaponItemType);
    }

    /** 冲锋射程（米）：CanRush 且 RushRange&gt;0 才有效，缺省 0=非冲锋。 */
    public float rushRange() {
        return canRush && rushRangeM > 0 ? rushRangeM : 0;
    }

    /**
     * 锁定技（SkillLock 脚本，含全部 CanRush 冲锋招）：msg20 动作帧携带目标对象，
     * 客户端据此本地执行冲锋/锁定位移（官服抓包 57378/52795：jysg03/yqq02/shl03/
     * gxfhj02/05 msg20 第三参=211 目标对象；服务端全程不下发玩家位移帧）。
     */
    public boolean lockSkill() {
        return "skilllock".equalsIgnoreCase(script) || canRush;
    }

    /** 伤害倍率（formula.lua 的 ratio；<=0 由公式按 1.0 处理）。 */
    public float damageRatio() {
        if (damageWaves <= 0) {
            return 0;
        }
        return baseDamage / 100f / damageWaves;
    }

    public Duration personalCd() {
        return Duration.ofMillis(personalCdMs);
    }

    public CombatSkill {
        followups = followups == null ? List.of() : List.copyOf(followups);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }
}
