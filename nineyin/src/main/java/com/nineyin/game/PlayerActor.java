package com.nineyin.game;

import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 权威玩家运行时状态（对照 Go playerActor 的本地简化版）：
 * 血/蓝/瘀血/轻功点/怒气/碎银/重力与速度、BufferInfo1..24 原生 Buff 槽、
 * 快捷键行、激活轻功集合；每秒 tick 恢复；vitalUpdate 输出官服索引的多属性 0x10。
 */
public final class PlayerActor {

    public static final int MAX_SP = 100;
    public static final int LOGIC_STATE_NORMAL = 0;
    public static final int LOGIC_STATE_FIGHTING = 1;
    public static final int LOGIC_STATE_SITCROSS = 102;

    // 原生 BuffStore 槽位（Go player_actor 常量）
    public static final int BUFF_SLOT_RED_ARMOR = 1;
    public static final int BUFF_SLOT_WATER_DRIFT = 2;
    public static final int BUFF_SLOT_WATER_JUMP = 3;
    public static final int BUFF_SLOT_INNER_POWER = 4;
    public static final int BUFF_SLOT_YIHUA = 5;        // 花神四式自 Buff（35s 租约）
    public static final int BUFF_SLOT_YIHUA_05 = 6;     // 花神五式
    public static final int BUFF_SLOT_TAIJI_STANCE = 7; // 太极架势轮换（攻/守两态）
    public static final int BUFF_SLOT_TAIJI_05 = 8;
    public static final int BUFF_SLOT_TAIJI_07 = 9;
    public static final int BUFF_SLOT_YIHUA_PASSIVE = 10; // 系统租约保留段上界
    public static final int RED_ARMOR_STATIC = 8682;
    public static final int WATER_DRIFT_STATIC = 216;
    public static final int WATER_JUMP_STATIC = 5786;
    /** BuffStore 总槽位；系统租约占 1..10，常规技能 Buff 从 11 起分配。 */
    public static final int BUFF_SLOT_LAST = 24;

    private static final int SITCROSS_FULL_SECONDS = 10;
    private static final int QINGGONG_REGEN_PER_SECOND = 10;
    private static final int BRUISE_RECOVERY_PER_SECOND = 10;

    /** 快捷键行（shortcut_rec：索引、类型、ConfigID）。 */
    public record Shortcut(int index, String kind, String id) {
    }

    static final class ActiveBuff {
        final int staticData;
        final long expiresMs;
        final int level;
        final String configId;

        ActiveBuff(int staticData, long expiresMs, int level, String configId) {
            this.staticData = staticData;
            this.expiresMs = expiresMs;
            this.level = level;
            this.configId = configId;
        }
    }

    /** 到期被剔除的 Buff（event-169 remove 配对用：槽位 + 身份，供表现层补发移除帧）。 */
    public record ExpiredBuff(int slot, int staticData, String configId) {
    }

    public String name = "";
    public String faction = "";

    /** 修炼/内功状态（role_faculty DB 恢复；null = 无库回退走 BootstrapCurNeigong 常量）。 */
    public FacultyState faculty;

    public int baseMaxHp = 1000;
    public int baseMaxMp = 500;
    public int hp = 1000;
    public int mp = 500;
    public int hitHp;

    // 战斗属性骨架（默认对齐旧固定伤害 150；后续接装备/内功/经脉表）
    public int attack = 150;              // 外功攻击
    public int defense;                   // 外功防御
    public int pierce;                    // 破防
    public int critValue;                 // 暴击值（外功暴击 ← 臂力 1:1；全 0 仍有 5% 基础暴击）
    public int resilience;                // 坚韧（Resilience，社区名封禁值）：压低来袭基础暴击率
    public float critDamageBonus;         // 暴伤%加成（基础 100% 之上加算）
    public float critDamageReduction;     // 暴伤减免%（受击方）
    public float critRateBonus;           // 暴击率%加成（无上限，可到 100%）
    public float critRateReduction;       // 被暴击率降低%（受击方）
    public int accuracy;                  // 命中值（身法换算 1:1，接装备时生效）
    public int evasion;                   // 闪避值
    public float hitRateBonus;            // 命中率加成（0.05 = +5%）
    public float dodgeRateBonus;          // 闪避率加成（0.05 = +5%，受 24% 上限约束）
    public int damageReduction;           // 外功伤害减免（每 10 点减 1 点伤害）

    // ---------- 装备词缀加成（proppack → equip*Add 累积层，对齐 Go recomputeCombat） ----------
    // 字段名与 Go 累积器同构（equipMaxHPAdd/equipDexAdd/equipPhyDef/equipMeleePowerAdd…）。
    // 应用面只取已有文档化换算的属性：PhyVa/MeleePower→攻击、PhyDef→防御、
    // Dex(身法)1:1→命中 + PhyHit→命中、Dodge→闪避、Str(臂力)1:1→暴击值、
    // MaxHP/MaxMP→有效上限；五维其余与内功/魔法系先累积不应用（无对应字段）。
    public int equipMaxHpAdd, equipMaxMpAdd;
    public int equipStrAdd, equipDexAdd, equipIngAdd, equipSpiAdd, equipStaAdd;
    public int equipPhyDef, equipPhyHit, equipMagicHit, equipDodge;
    public int equipPhyVa, equipMagicVa, equipMeleePowerAdd, equipMinMelee, equipMaxMelee;

    // 招架（Wiki Parry Attributes；耐力/恢复默认值为骨架近似，官服基础值未公开）
    public boolean parrying;              // 右键格挡(54)/主动招架(218)共用开关
    public int parryEndurance = 100;      // 招架耐力（当前）
    public int maxParryEndurance = 100;   // 招架耐力上限
    public int parryEnduranceRecovery = 5;// 招架耐力每秒被动恢复
    public int blockRating = 50;          // 格挡值（Wiki：基础 50）
    public int internalConversion = 100;  // 化劲（近似基础 100 → 招架减半）

    // ---------- 内功特效（scripts/neigong/*.lua 钩子的运行期载体） ----------
    /** 运行内功脚本状态（buffId → Lua state table 快照；叠层/计数/下次触发时刻）。 */
    public final Map<String, Map<String, Object>> neigongState = new LinkedHashMap<>();
    /** 内功护盾池（多来源叠加为单池；受击时先于血量消耗）。 */
    public int neigongAbsorb;
    public long neigongAbsorbExpiresAt;
    /** 内功常驻被动属性（NeigongRuntime 依当前运行内功重算；null = 无）。 */
    public volatile com.nineyin.script.NeigongScriptBridge.PassiveStats neigongPassive;
    public int qinggongPoint = 150;
    public int maxQinggongPoint = 150;
    public int maxQinggongPointAdd;
    /** 怒气（SP=466，大招消耗 AConsumeSP；普通招式走 MP）。初始满值对齐 Go 参考服
     *  player_progress.go attributes sp:100 / maxPlayerSP=100；出招扣减、无自动回复
     *  （参考服 addSP 亦为预留入口，怒气奖励待战斗事件接入）。 */
    public int sp = MAX_SP;
    public long silver = 99999;
    // 金币（CapitalType0=415，充值商城 560 计价货币）。初始额与 silver 同为私服娱乐值，
    // 参考服走 wallet store 持久化；capital 映射与持久化待实机抓包/存档格式钉死后接入。
    public long gold = 99999;

    public float gravity = 9.8f;
    public float moveSpeed = 5.0f;
    public float walkSpeed = 2.5f;
    public float runSpeed = 7.0f;
    public float jumpSpeed = 8.0f;

    public int logicState = LOGIC_STATE_NORMAL;
    public String curSkillId = "";
    public String curSkillEffectId = "";
    public int curSkillLevel;
    public int curSkillTargetOid;
    public int curSkillTargetOwner;

    /** 已学习技能（ConfigID → 等级）：默认内置 23 技，后续接 [skill-save] 角色存档。 */
    public final Map<String, Integer> learnedSkills = new LinkedHashMap<>();
    /** 技能个人 CD（ConfigID → 就绪时刻 ms）。 */
    public final Map<String, Long> skillCooldowns = new HashMap<>();
    /** 公共 CD（teamKey → 就绪时刻 ms；teamKey=CoolDownTeam 或 -CoolDownCategory）。 */
    public final Map<Integer, Long> skillTeamCooldowns = new HashMap<>();
    /** 通用技能锁（起手互斥窗口）。 */
    public volatile long skillLockUntilMs;
    /** 施法序号（beginSkillUse 递增）：多跳波次/延迟结算据它判废，新施法使旧波次失效。 */
    public volatile long castSeq;

    // 控制效果（§3.25）：受控期间技能施放被拒；免疫位（equipImmunities）在
    // 控制落账前消费（StateType=1 眩晕族 ↔ 位 33..38）。
    public volatile long controlledUntilMs;
    public volatile int controlStateType;   // buff_static.StateType 1..10（0=未受控）

    public boolean controlled(long now) {
        return now < controlledUntilMs;
    }

    public PlayerActor() {
        for (com.nineyin.protocol.SkillPackets.StarterSkill sk
                : com.nineyin.protocol.SkillPackets.STARTER_SKILLS) {
            learnedSkills.put(sk.configId(), 1);
        }
    }

    private final Map<Integer, ActiveBuff> buffs = new LinkedHashMap<>();
    private final List<Shortcut> shortcuts = new ArrayList<>();
    private final Set<String> activeQingGong = new HashSet<>();
    /** pruneBuffs 剔除队列（等表现层取走发 event-169 remove；参考服 add/remove 严格配对）。 */
    private final List<ExpiredBuff> expiredBuffQueue =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // ---------- Buff ----------

    /** 返回该槽的 BufferInfo 十六进制串（StaticData,OwnerID,OwnerSerial,Level,EndTimeMs,Count）。 */
    public String activateBuff(int slot, int staticData, long durationMs, String configId, int level) {
        long expires = System.currentTimeMillis() + durationMs;
        buffs.put(slot, new ActiveBuff(staticData, expires, Math.max(1, level), configId == null ? "" : configId));
        return buffInfo(slot);
    }

    public boolean hasActiveBuff(int slot, int staticData, long nowMs) {
        ActiveBuff buff = buffs.get(slot);
        return buff != null && buff.staticData == staticData && buff.expiresMs > nowMs;
    }

    public boolean clearBuff(int slot, int staticData) {
        ActiveBuff buff = buffs.get(slot);
        if (buff == null || buff.staticData != staticData) {
            return false;
        }
        buffs.remove(slot);
        return true;
    }

    public String buffInfo(int slot) {
        ActiveBuff buff = buffs.get(slot);
        long now = System.currentTimeMillis();
        if (buff == null || buff.expiresMs <= now) {
            return "";
        }
        return String.format(Locale.ROOT, "%X,%X,%X,%X,%X,%X",
                buff.staticData, 1, 1, buff.level, buff.expiresMs, 1);
    }

    // ---------- 技能（211） ----------

    /** 已学习等级（>0 已学习；对照 Go learnedSkillLevel）。 */
    public int learnedSkillLevel(String configId) {
        Integer level = configId == null ? null : learnedSkills.get(configId);
        return level == null ? 0 : level;
    }

    public void learnSkill(String configId, int level) {
        if (configId == null || configId.isEmpty() || level < 1) {
            return;
        }
        learnedSkills.put(configId, level);
    }

    public boolean spendMp(int cost) {
        if (cost <= 0) {
            return true;
        }
        if (mp < cost) {
            return false;
        }
        mp -= cost;
        return true;
    }

    /**
     * 技能 Buff 槽分配（Go activateSkillBuff）：首选槽 → 同 ConfigID 复用 →
     * 11..24 首个空闲/过期槽；无槽可用返回 0。
     */
    public int activateSkillBuff(SkillCatalog.BuffDef def, long nowMs) {
        int slot = def.preferredSlot();
        if (slot == 0) {
            for (Map.Entry<Integer, ActiveBuff> e : buffs.entrySet()) {
                if (e.getValue().configId.equals(def.configId())) {
                    slot = e.getKey();
                    break;
                }
            }
        }
        if (slot == 0) {
            for (int candidate = BUFF_SLOT_YIHUA_PASSIVE + 1; candidate <= BUFF_SLOT_LAST; candidate++) {
                ActiveBuff active = buffs.get(candidate);
                if (active == null || active.expiresMs <= nowMs) {
                    slot = candidate;
                    break;
                }
            }
        }
        if (slot <= 0 || slot > BUFF_SLOT_LAST) {
            return 0;
        }
        long lifetime = def.lifetimeMs() <= 0 ? 1 : def.lifetimeMs();
        ActiveBuff applied = new ActiveBuff(def.staticData(), nowMs + lifetime,
                Math.max(1, def.level()), def.configId());
        buffs.put(slot, applied);
        return slot;
    }

    /** 槽上 Buff 的到期时刻（ms；空槽 0）。 */
    public long buffExpiresAt(int slot) {
        ActiveBuff buff = buffs.get(slot);
        return buff == null ? 0 : buff.expiresMs;
    }

    /** 槽上生效中 Buff 的 StaticData（空槽/已过期 0；脚本钩子 ctx 的架势快照用）。 */
    public int buffStaticDataAt(int slot, long nowMs) {
        ActiveBuff buff = buffs.get(slot);
        return buff != null && buff.expiresMs > nowMs ? buff.staticData : 0;
    }

    /** 架势轮换：首选槽空闲→首变体；当前 StaticData 命中某变体→下一变体（循环）。 */
    public SkillCatalog.BuffDef nextStanceVariant(List<SkillCatalog.BuffDef> variants, long nowMs) {
        if (variants == null || variants.isEmpty()) {
            return null;
        }
        ActiveBuff active = buffs.get(variants.get(0).preferredSlot());
        if (active == null || active.expiresMs <= nowMs) {
            return variants.get(0);
        }
        for (int i = 0; i < variants.size(); i++) {
            if (active.staticData == variants.get(i).staticData()) {
                return variants.get((i + 1) % variants.size());
            }
        }
        return variants.get(0);
    }

    /** 仅当 curSkillId 仍为 expected 时清理（旧定时器不得清掉新技能；Go clearCurrentSkill）。 */
    public boolean clearCurrentSkill(String expected) {
        if (!expected.equals(curSkillId)) {
            return false;
        }
        curSkillId = "";
        curSkillEffectId = "";
        curSkillLevel = 0;
        curSkillTargetOid = 0;
        curSkillTargetOwner = 0;
        logicState = LOGIC_STATE_NORMAL;
        return true;
    }

    /** 技能态清零帧（0x10：LogicState/CurSkillID/CurSkillTarget；官服表无 EffectID/Level 项）。 */
    public byte[] currentSkillClearFrame() {
        return S2CPackets.playerBatch()
                .u8(PropertyId.LOGIC_STATE, logicState)
                .str(PropertyId.CUR_SKILL_ID, curSkillId)
                .obj(PropertyId.CUR_SKILL_TARGET, curSkillTargetOid, curSkillTargetOwner)
                .build();
    }

    // ---------- 轻功 ----------

    /** 起跳计费（仅 begin=1 时调用）：不足拒绝。 */
    public boolean useQingGong(int consume) {
        int cost = consume <= 0 ? 0 : consume;
        if (qinggongPoint < cost) {
            return false;
        }
        qinggongPoint -= cost;
        return true;
    }

    /** 217 激活（幂等：已存在返回 false）。 */
    public boolean activateQingGong(String id) {
        return activeQingGong.add(id);
    }

    public boolean qinggongActive(String id) {
        return activeQingGong.contains(id);
    }

    // ---------- 战斗（轻量） ----------

    /** 瘀血占比：受击伤害的 25% 先进瘀血层（骨架近似，官服比例未公开）。 */
    static final double BRUISE_SPLIT_RATIO = 0.25;
    /** 瘀血上限：最大血量的 30%，超出部分直接扣血量。 */
    static final double BRUISE_CAP_RATIO = 0.30;

    /** 当前战斗属性档案（喂给 CombatFormulas）；装备词缀与内功被动在此叠加（基础字段不动）。 */
    public CombatStats combatStats() {
        var ng = neigongPassive;
        int ngAttack = ng == null ? 0 : ng.attackAdd();
        int ngDefense = ng == null ? 0 : ng.defenseAdd();
        int ngPierce = ng == null ? 0 : ng.pierceAdd();
        int ngAccuracy = ng == null ? 0 : ng.accuracyAdd();
        int ngEvasion = ng == null ? 0 : ng.evasionAdd();
        int ngBlock = ng == null ? 0 : ng.blockRatingAdd();
        int ngReduction = ng == null ? 0 : ng.damageReductionAdd();
        float ngCritRate = ng == null ? 0f : ng.critRateBonus();
        float ngCritDamage = ng == null ? 0f : ng.critDamageBonus();
        return new CombatStats(attack + equipPhyVa + equipMeleePowerAdd + ngAttack + tempAttackAdd(),
                defense + equipPhyDef + ngDefense + tempDefenseAdd(), pierce + ngPierce,
                critDamageBonus + ngCritDamage, critDamageReduction,
                critRateBonus + ngCritRate, critRateReduction,
                accuracy + equipDexAdd + equipPhyHit + ngAccuracy, evasion + equipDodge + ngEvasion,
                hitRateBonus, dodgeRateBonus,
                damageReduction + ngReduction, blockRating + ngBlock, internalConversion,
                critValue + equipStrAdd, resilience);
    }

    /** 有效上限（基础 + 装备词缀 + 内功被动 + 限时道具属性；夹下限 1 防词缀负值归零）。 */
    public int maxHp() {
        int ng = neigongPassive == null ? 0 : neigongPassive.maxHpAdd();
        return Math.max(1, baseMaxHp + equipMaxHpAdd + ng + tempMaxHpAdd());
    }

    public int maxMp() {
        int ng = neigongPassive == null ? 0 : neigongPassive.maxMpAdd();
        return Math.max(1, baseMaxMp + equipMaxMpAdd + ng);
    }

    /**
     * 从装备栏词缀重算加成累积（装备 propBuilt 串 "1,id;1,id" → proppack 节 → r= 属性行）。
     * 幂等：先清零再累积，穿/脱/进场景均可反复调用；基础属性字段不受影响。
     */
    public void recomputeEquipBonuses(PropPackCatalog catalog,
                                      List<com.nineyin.db.RoleInventoryStore.Equipped> equipped) {
        equipMaxHpAdd = 0;
        equipMaxMpAdd = 0;
        equipStrAdd = 0;
        equipDexAdd = 0;
        equipIngAdd = 0;
        equipSpiAdd = 0;
        equipStaAdd = 0;
        equipPhyDef = 0;
        equipPhyHit = 0;
        equipMagicHit = 0;
        equipDodge = 0;
        equipPhyVa = 0;
        equipMagicVa = 0;
        equipMeleePowerAdd = 0;
        equipMinMelee = 0;
        equipMaxMelee = 0;
        if (catalog == null || equipped == null) {
            return;
        }
        for (com.nineyin.db.RoleInventoryStore.Equipped item : equipped) {
            String built = item.propBuilt();
            if (built == null || built.isEmpty()) {
                continue;
            }
            for (String pair : built.split(";")) {
                String[] cols = pair.split(",");
                if (cols.length < 2) {
                    continue;
                }
                int packId;
                try {
                    packId = Integer.parseInt(cols[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                for (PropPackCatalog.Mod mod : catalog.modsOf(packId)) {
                    accumulateEquipMod(mod);
                }
            }
        }
    }

    /** 装备特效免疫位（EffectPackID → EquipAddBuff → varprop Immunity；控制效果接入后生效）。 */
    private final java.util.Set<Integer> equipImmunities = new java.util.LinkedHashSet<>();

    /**
     * 装备特效应用（§3.17 链路）：与 recomputeEquipBonuses 同调用点、先词缀后特效。
     * 属性类效果（varprop @PropModifyPackRec）复用词缀累积器继续叠加；Immunity 位
     * 收集进 {@link #equipImmunities()}（EventProcess/@SkillModifyPackRec 脚本系留待后续）。
     *
     * @param effectPackIds 每件已穿装备的特效包号（0=无；来自 equipment.ini 配置）
     */
    public void applyEquipEffects(com.nineyin.rule.EquipEffectCatalog effects,
                                  PropPackCatalog packs, List<Integer> effectPackIds) {
        equipImmunities.clear();
        if (effects == null || effectPackIds == null) {
            return;
        }
        for (int packId : effectPackIds) {
            for (com.nineyin.rule.EquipEffectCatalog.EquipBuff b : effects.buffsOfPack(packId)) {
                if (packs != null) {
                    for (int pp : effects.propPacksOf(b.buffId(), b.level())) {
                        for (PropPackCatalog.Mod mod : packs.modsOf(pp)) {
                            accumulateEquipMod(mod);
                        }
                    }
                }
                equipImmunities.addAll(effects.immunitiesOf(b.buffId(), b.level()));
            }
        }
    }

    /** 当前装备特效免疫位（只读视图）。 */
    public java.util.Set<Integer> equipImmunities() {
        return java.util.Collections.unmodifiableSet(equipImmunities);
    }

    /** 词缀属性名 → equip*Add 累积器；未覆盖词缀（概率%/倍率/内功防御系等）忽略。
     *  后缀省略变体（PhyDef/MaxHP/Str/Sta/Dex/Dodge/MaxMP/MeleePower，实测 proppack
     *  与抓包装备 62995=PhyDef+475）与 *Add 同累积器；重复包号逐条叠加。 */
    private void accumulateEquipMod(PropPackCatalog.Mod mod) {
        switch (mod.prop()) {
            case "MaxHPAdd", "MaxHP" -> equipMaxHpAdd += mod.value();
            case "MaxMPAdd", "MaxMP" -> equipMaxMpAdd += mod.value();
            case "StrAdd", "Str" -> equipStrAdd += mod.value();
            case "DexAdd", "Dex" -> equipDexAdd += mod.value();
            case "IngAdd" -> equipIngAdd += mod.value();
            case "SpiAdd" -> equipSpiAdd += mod.value();
            case "StaAdd", "Sta" -> equipStaAdd += mod.value();
            case "PhyDefAdd", "PhyDef" -> equipPhyDef += mod.value();
            case "PhyHitAdd" -> equipPhyHit += mod.value();
            case "MagicHitAdd" -> equipMagicHit += mod.value();
            case "DodgeAdd", "Dodge" -> equipDodge += mod.value();
            case "PhyVaAdd" -> equipPhyVa += mod.value();
            case "MagicVaAdd" -> equipMagicVa += mod.value();
            case "MeleePowerAdd", "MeleePower" -> equipMeleePowerAdd += mod.value();
            case "MinMeleeDamage" -> equipMinMelee += mod.value();
            case "MaxMeleeDamage" -> equipMaxMelee += mod.value();
            default -> {
                // 概率%/倍率/内功防御四系等：无对应字段，累积层不覆盖
            }
        }
    }

    /** 招架耐力消耗（按来袭攻击力计）：返回是否刚被打破（耐力归零）。 */
    public boolean drainParry(int cost) {
        if (cost <= 0 || parryEndurance <= 0) {
            return false;
        }
        parryEndurance = Math.max(0, parryEndurance - cost);
        return parryEndurance == 0;
    }

    // ---------- 内功特效运行载体 ----------

    /** 授予内功护盾（叠加入单池；到期时间取更晚者）。 */
    public void grantAbsorb(int amount, long lifeMs, long now) {
        if (amount <= 0 || lifeMs <= 0) {
            return;
        }
        long expires = now + lifeMs;
        if (neigongAbsorb > 0 && neigongAbsorbExpiresAt > now) {
            neigongAbsorb += amount;
            neigongAbsorbExpiresAt = Math.max(neigongAbsorbExpiresAt, expires);
        } else {
            neigongAbsorb = amount;
            neigongAbsorbExpiresAt = expires;
        }
    }

    /** 当前可用护盾量（过期即清零）。 */
    public int absorbRemaining(long now) {
        if (neigongAbsorb <= 0 || neigongAbsorbExpiresAt <= now) {
            neigongAbsorb = 0;
            return 0;
        }
        return neigongAbsorb;
    }

    /** 护盾先行消耗：返回穿盾后仍需进血量层的伤害。 */
    public int consumeAbsorb(int damage, long now) {
        if (damage <= 0) {
            return 0;
        }
        int pool = absorbRemaining(now);
        if (pool <= 0) {
            return damage;
        }
        int used = Math.min(pool, damage);
        neigongAbsorb = pool - used;
        return damage - used;
    }

    /** 回血（夹上限；返回实际回复量）。 */
    public int restoreHp(int amount) {
        if (amount <= 0 || hp <= 0) {
            return 0;
        }
        int before = hp;
        hp = Math.min(maxHp(), hp + amount);
        return hp - before;
    }

    /** 回内力（夹上限；返回实际回复量）。 */
    public int restoreMp(int amount) {
        if (amount <= 0) {
            return 0;
        }
        int before = mp;
        mp = Math.min(maxMp(), mp + amount);
        return mp - before;
    }

    /** 内功脚本状态快照（无则空表；返回副本，脚本侧改写不影响存量）。 */
    public Map<String, Object> neigongStateOf(String buffId) {
        Map<String, Object> state = neigongState.get(buffId);
        return state == null ? Map.of() : new LinkedHashMap<>(state);
    }

    /** 整体替换内功脚本状态（null = 清除）。 */
    public void setNeigongState(String buffId, Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            neigongState.remove(buffId);
        } else {
            neigongState.put(buffId, new LinkedHashMap<>(state));
        }
    }

    /** 伤害进瘀血/血量层：25% 进瘀血（战斗中每秒消退 10 点），其余扣血量；返回是否刚死亡。 */
    public boolean applyDamage(int damage) {
        if (damage <= 0 || hp <= 0) {
            return false;
        }
        int bruiseCap = (int) (maxHp() * BRUISE_CAP_RATIO);
        int bruise = Math.min((int) (damage * BRUISE_SPLIT_RATIO), Math.max(0, bruiseCap - hitHp));
        hitHp += bruise;
        hp = Math.max(0, hp - (damage - bruise));
        return hp == 0;
    }

    public void revive() {
        hp = maxHp();
        mp = maxMp();
        hitHp = 0;
        logicState = LOGIC_STATE_NORMAL;
    }

    // ---------- 限时道具属性（temp_stat） ----------

    /** 限时属性来源（source = 物品 configId；同源替换，到期自清）。 */
    public static final class TempStat {
        public final String source;
        public final int attackAdd;
        public final int defenseAdd;
        public final int maxHpAdd;
        public final long untilMs;

        public TempStat(String source, int attackAdd, int defenseAdd, int maxHpAdd, long untilMs) {
            this.source = source;
            this.attackAdd = attackAdd;
            this.defenseAdd = defenseAdd;
            this.maxHpAdd = maxHpAdd;
            this.untilMs = untilMs;
        }
    }

    /** 生效中的限时属性（scripts/item 的 temp_stat 指令落账处）。 */
    public final java.util.concurrent.ConcurrentHashMap<String, TempStat> tempStats =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 应用限时属性（同源替换）；返回是否引发上限变化（调用方补发 vitalUpdate）。 */
    public boolean applyTempStat(String source, int attackAdd, int defenseAdd,
                                 int maxHpAdd, long lifeMs, long nowMs) {
        if (source == null || source.isBlank() || lifeMs <= 0) {
            return false;
        }
        int oldMaxHp = maxHp();
        tempStats.put(source, new TempStat(source, attackAdd, defenseAdd, maxHpAdd,
                nowMs + lifeMs));
        return maxHp() != oldMaxHp;
    }

    /** 清理过期限时属性（tick 每秒调用；返回是否有变化）。 */
    public boolean expireTempStats(long nowMs) {
        boolean changed = false;
        for (TempStat t : tempStats.values()) {
            if (t.untilMs <= nowMs && tempStats.remove(t.source) != null) {
                changed = true;
            }
        }
        return changed;
    }

    public int tempAttackAdd() {
        int sum = 0;
        for (TempStat t : tempStats.values()) {
            sum += t.attackAdd;
        }
        return sum;
    }

    public int tempDefenseAdd() {
        int sum = 0;
        for (TempStat t : tempStats.values()) {
            sum += t.defenseAdd;
        }
        return sum;
    }

    public int tempMaxHpAdd() {
        int sum = 0;
        for (TempStat t : tempStats.values()) {
            sum += t.maxHpAdd;
        }
        return sum;
    }

    // ---------- tick（每秒） ----------

    /** 返回是否有状态变化（调用方据此发 vitalUpdate）。 */
    public boolean tick(long nowMs) {
        boolean changed = pruneBuffs(nowMs);
        changed |= expireTempStats(nowMs);
        if (logicState == LOGIC_STATE_SITCROSS) {
            int hpRate = Math.max(0, (maxHp() + SITCROSS_FULL_SECONDS - 1) / SITCROSS_FULL_SECONDS);
            int mpRate = Math.max(0, (maxMp() + SITCROSS_FULL_SECONDS - 1) / SITCROSS_FULL_SECONDS);
            if (hp < maxHp()) {
                hp = Math.min(maxHp(), hp + hpRate);
                changed = true;
            }
            if (mp < maxMp()) {
                mp = Math.min(maxMp(), mp + mpRate);
                changed = true;
            }
        }
        if (logicState == LOGIC_STATE_FIGHTING && hitHp > 0) {
            hitHp = Math.max(0, hitHp - BRUISE_RECOVERY_PER_SECOND);
            changed = true;
        }
        // 招架耐力被动恢复（每秒，不在 vitalUpdate 中同步）
        if (parryEndurance < maxParryEndurance) {
            parryEndurance = Math.min(maxParryEndurance,
                    parryEndurance + Math.max(0, parryEnduranceRecovery));
        }
        if (qinggongPoint < maxQinggongPoint + maxQinggongPointAdd) {
            qinggongPoint = Math.min(maxQinggongPoint + maxQinggongPointAdd,
                    qinggongPoint + QINGGONG_REGEN_PER_SECOND);
            changed = true;
        }
        return changed;
    }

    private boolean pruneBuffs(long nowMs) {
        boolean changed = false;
        for (Map.Entry<Integer, ActiveBuff> e : new HashMap<>(buffs).entrySet()) {
            if (e.getValue().expiresMs <= nowMs) {
                buffs.remove(e.getKey());
                expiredBuffQueue.add(new ExpiredBuff(e.getKey(),
                        e.getValue().staticData, e.getValue().configId));
                changed = true;
            }
        }
        return changed;
    }

    /** 取走并清空到期剔除队列（调用方负责逐条发 event-169 remove）。 */
    public List<ExpiredBuff> drainExpiredBuffs() {
        if (expiredBuffQueue.isEmpty()) {
            return List.of();
        }
        List<ExpiredBuff> drained = List.copyOf(expiredBuffQueue);
        expiredBuffQueue.clear();
        return drained;
    }

    // ---------- 快捷键 ----------

    /** 同索引替换（返回旧行号，不存在返回 -1）。 */
    public int setShortcut(int index, String kind, String id) {
        for (int row = 0; row < shortcuts.size(); row++) {
            if (shortcuts.get(row).index() == index) {
                Shortcut old = shortcuts.get(row);
                if (old.kind().equals(kind) && old.id().equals(id)) {
                    return row;
                }
                shortcuts.set(row, new Shortcut(index, kind, id));
                return row;
            }
        }
        shortcuts.add(new Shortcut(index, kind, id));
        return -1;
    }

    public Shortcut removeShortcutRow(int row) {
        if (row < 0 || row >= shortcuts.size()) {
            return null;
        }
        return shortcuts.remove(row);
    }

    public List<Shortcut> shortcuts() {
        return shortcuts;
    }

    // ---------- vital 同步 ----------

    /** 完整权威状态（多属性 0x10），官服属性表索引（上限/恢复速度走装备加成后的有效值）。 */
    public byte[] vitalUpdate() {
        S2CPackets.Batch b = S2CPackets.playerBatch();
        int effHp = maxHp();
        int effMp = maxMp();
        int hpRatio = (int) ((long) hp * 100 / effHp);
        int hitRatio = (int) ((long) hitHp * 100 / effHp);
        int mpRatio = (int) ((long) mp * 100 / effMp);
        int hpUp = Math.max(0, (effHp + SITCROSS_FULL_SECONDS - 1) / SITCROSS_FULL_SECONDS);
        int mpUp = Math.max(0, (effMp + SITCROSS_FULL_SECONDS - 1) / SITCROSS_FULL_SECONDS);
        b.u8(PropertyId.LOGIC_STATE, logicState)
                .i32(PropertyId.HP_RATIO, hpRatio)
                .i32(PropertyId.MP_RATIO, mpRatio)
                .i32(PropertyId.MAX_HP, effHp)
                .i32(PropertyId.MAX_MP, effMp)
                .i32(PropertyId.HP, hp)
                .i32(PropertyId.MP, mp)
                .i32(PropertyId.SPEED_RATIO, 100)
                .i32(PropertyId.HIT_HP, hitHp)
                .i32(PropertyId.HIT_HP_RATIO, hitRatio)
                .i32(PropertyId.QINGGONG_POINT, qinggongPoint)
                .i32(PropertyId.MAX_QINGGONG_POINT, maxQinggongPoint)
                .i32(PropertyId.MAX_QINGGONG_POINT_ADD, maxQinggongPointAdd)
                .u8(PropertyId.DEAD, hp <= 0 ? 1 : 0)
                .str(PropertyId.CUR_SKILL_ID, curSkillId)
                .obj(PropertyId.CUR_SKILL_TARGET, curSkillTargetOid, curSkillTargetOwner)
                .i32(PropertyId.MODIFY_SKILL_LOCK_TIME, 5000)
                .i32(PropertyId.HP_HEART_SPEED, 1000)
                .i32(PropertyId.HP_HEART_SPEED_ADD, 0)
                .i32(PropertyId.MP_HEART_SPEED, 1000)
                .i32(PropertyId.MP_HEART_SPEED_ADD, 0)
                .i32(PropertyId.HP_UP_SPEED, hpUp)
                .i32(PropertyId.HP_UP_SPEED_ADD, 0)
                .i32(PropertyId.MP_UP_SPEED, mpUp)
                .i32(PropertyId.MP_UP_SPEED_ADD, 0)
                .wide(PropertyId.NAME, name)
                .i64(PropertyId.CAPITAL_TYPE1, silver)
                .i64(PropertyId.CAPITAL_TYPE0, gold);
        for (int slot = 1; slot <= PropertyId.BUFFER_INFO_COUNT; slot++) {
            b.str(PropertyId.bufferInfoSlot(slot), buffInfo(slot));
        }
        S2CPackets.appendFaction(b, faction);
        FacultyState.Book target = null;
        if (faculty != null) {
            target = faculty.targetBook();
        }
        String curNeigong = faculty != null ? faculty.curNeigong : BootstrapCurNeigong.CUR;
        int facultyPoint = faculty != null ? faculty.faculty : BootstrapCurNeigong.FACULTY;
        int facultyState = faculty != null ? faculty.facultyState : BootstrapCurNeigong.STATE;
        int facultyStyle = faculty != null ? faculty.facultyStyle : BootstrapCurNeigong.STYLE;
        String facultyName = faculty != null ? faculty.facultyName : BootstrapCurNeigong.NAME;
        int curFill = target != null ? target.fill : 0;
        int totalFill = target != null ? target.total : BootstrapCurNeigong.TOTAL;
        int fillSpeed = faculty != null ? faculty.fillSpeed : BootstrapCurNeigong.FILL_SPEED;
        int power = target != null ? target.power : BootstrapCurNeigong.POWER;
        int maxPower = target != null ? target.maxPower : BootstrapCurNeigong.MAX_POWER;
        b.f32(PropertyId.GRAVITY, gravity)
                .f32(PropertyId.GRAVITY_ADD, 0f)
                .f32(PropertyId.DROP_HEIGHT_PUB, 100000f)
                .f32(PropertyId.MOVE_SPEED, moveSpeed)
                .f32(PropertyId.WALK_SPEED, walkSpeed)
                .f32(PropertyId.RUN_SPEED, runSpeed)
                .f32(PropertyId.JUMP_SPEED, jumpSpeed)
                .str(PropertyId.CUR_NEIGONG, curNeigong)
                .i32(PropertyId.FACULTY, facultyPoint)
                .i32(PropertyId.FACULTY_STATE, facultyState)
                .i32(PropertyId.FACULTY_STYLE, facultyStyle)
                .str(PropertyId.FACULTY_NAME, facultyName)
                .i32(PropertyId.CUR_FILL_VALUE, curFill)
                .i32(PropertyId.TOTAL_FILL_VALUE, totalFill)
                .i32(PropertyId.FILL_SPEED, fillSpeed)
                .i32(PropertyId.SP, sp)
                .i32(PropertyId.POWER_VALUE, power)
                .i32(PropertyId.MAX_POWER_VALUE, maxPower);
        return b.build();
    }

    /** 内功面板静态种子（Go newPlayerProgress：运功中、普通模式、太极心法 30 级）。 */
    static final class BootstrapCurNeigong {
        static final String CUR = "ng_wd_001";
        static final int FACULTY = 9_999_999;
        static final int STATE = 2;
        static final int STYLE = 0;
        static final String NAME = "ng_wd_001";
        static final int TOTAL = 273200;
        static final int FILL_SPEED = 200;
        static final int POWER = 4550;
        static final int MAX_POWER = 4550;

        private BootstrapCurNeigong() {
        }
    }
}
