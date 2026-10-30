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

    public String name = "";
    public String faction = "";

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
    public boolean parrying;              // 218 招架开关
    public int parryEndurance = 100;      // 招架耐力（当前）
    public int maxParryEndurance = 100;   // 招架耐力上限
    public int parryEnduranceRecovery = 5;// 招架耐力每秒被动恢复
    public int blockRating = 50;          // 格挡值（Wiki：基础 50）
    public int internalConversion = 100;  // 化劲（近似基础 100 → 招架减半）
    public int qinggongPoint = 150;
    public int maxQinggongPoint = 150;
    public int maxQinggongPointAdd;
    public int sp;
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

    /** 当前战斗属性档案（喂给 CombatFormulas）；装备词缀加成在此叠加（基础字段不动）。 */
    public CombatStats combatStats() {
        return new CombatStats(attack + equipPhyVa + equipMeleePowerAdd,
                defense + equipPhyDef, pierce,
                critDamageBonus, critDamageReduction, critRateBonus, critRateReduction,
                accuracy + equipDexAdd + equipPhyHit, evasion + equipDodge,
                hitRateBonus, dodgeRateBonus,
                damageReduction, blockRating, internalConversion,
                critValue + equipStrAdd, resilience);
    }

    /** 有效上限（基础 + 装备词缀；夹下限 1 防词缀负值归零）。 */
    public int maxHp() {
        return Math.max(1, baseMaxHp + equipMaxHpAdd);
    }

    public int maxMp() {
        return Math.max(1, baseMaxMp + equipMaxMpAdd);
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

    // ---------- tick（每秒） ----------

    /** 返回是否有状态变化（调用方据此发 vitalUpdate）。 */
    public boolean tick(long nowMs) {
        boolean changed = pruneBuffs(nowMs);
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
                changed = true;
            }
        }
        return changed;
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
        b.f32(PropertyId.GRAVITY, gravity)
                .f32(PropertyId.GRAVITY_ADD, 0f)
                .f32(PropertyId.DROP_HEIGHT_PUB, 100000f)
                .f32(PropertyId.MOVE_SPEED, moveSpeed)
                .f32(PropertyId.WALK_SPEED, walkSpeed)
                .f32(PropertyId.RUN_SPEED, runSpeed)
                .f32(PropertyId.JUMP_SPEED, jumpSpeed)
                .str(PropertyId.CUR_NEIGONG, BootstrapCurNeigong.CUR)
                .i32(PropertyId.FACULTY, BootstrapCurNeigong.FACULTY)
                .i32(PropertyId.FACULTY_STATE, BootstrapCurNeigong.STATE)
                .i32(PropertyId.FACULTY_STYLE, BootstrapCurNeigong.STYLE)
                .str(PropertyId.FACULTY_NAME, BootstrapCurNeigong.NAME)
                .i32(PropertyId.CUR_FILL_VALUE, 0)
                .i32(PropertyId.TOTAL_FILL_VALUE, BootstrapCurNeigong.TOTAL)
                .i32(PropertyId.FILL_SPEED, BootstrapCurNeigong.FILL_SPEED)
                .i32(PropertyId.SP, sp)
                .i32(PropertyId.POWER_VALUE, BootstrapCurNeigong.POWER)
                .i32(PropertyId.MAX_POWER_VALUE, BootstrapCurNeigong.MAX_POWER);
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
