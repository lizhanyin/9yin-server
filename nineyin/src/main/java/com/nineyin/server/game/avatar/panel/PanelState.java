package com.nineyin.server.game.avatar.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.protocol.LoginMessages;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;

/**
 * 人物属性面板实时状态（bak PlayerActor 面板域的移植，四期）。
 *
 * <p>三块内容：身份块字段（实力/称号/侠恶/性格/体力/饱食/杀戮/酒量——参考服登录帧
 * exe 表 256-266 段，缺发则面板整块空白）；装备词缀累积层 equip*Add（propBuilt
 * "1,id;…" 引用 proppack 节，穿脱/登录时整层重算）；装备特效免疫位。
 * 面板战斗块 = 基础属性 + 本层装备累积 + 运功内功 varprop（IDA 逆向钉死的叠加序）。
 */
public final class PanelState {

    /** 实力等级种子（与参考服同种子玩家 ng_wd_001 30 级登录帧一致）。 */
    public static final int SEED_POWER_LEVEL = 151;

    // ---------------------------------------------------------- 身份块（int 字段）
    public int powerLevel = SEED_POWER_LEVEL;
    public int justice;            // 善（320）
    public int evil;               // 恶（321）
    public int freeBase;           // 基础狂邪（322）
    public int characterFlag;      // 性格标记（318）
    public int characterValue;     // 性格值（319）
    public int ene;                // 体力（704）
    public int maxEne;
    public int resumeEne;
    public int sat;                // 饱食（707）
    public int maxSat;
    public int pkValue;            // 杀戮值（426，红名来源）
    public int drunkLevel;         // 酒量（722）
    /** PK 模式（437，i32；GM set_pk_mode 驱动客户端 UI/本地渲染。服务端攻击门控未接，
     *  会话态不落库——官方玩法链 C2S 291 custom_set_pkmode 接通后改同源状态机）。 */
    public int pkMode;

    // ------------------------------------------- 域块（DB 驱动，保守语义真机待验）
    /** 佩戴称号（role_titles is_worn=1；0=无，面板显示名走客户端 TitleID 表）。 */
    public int wornTitleId;
    /** 兵录最高等级（role_binglu 12 槽 max(cur_level)；BingLuType 无数据源暂 0）。 */
    public int maxBingLuLevel;
    /** 声望总量（role_repute 汇总 → PrestigeExp）。 */
    public int prestigeExp;
    /** 当前坐骑 configId（空=无坐骑；role_mounts snapshot）。 */
    public String mount = "";

    // ------------------------------------------------ 装备词缀累积层（整层重算幂等）
    int equipMaxHpAdd;
    int equipMaxMpAdd;
    int equipStrAdd;
    int equipDexAdd;
    int equipIngAdd;
    int equipSpiAdd;
    int equipStaAdd;
    int equipPhyDef;
    int equipPhyHit;
    int equipMagicHit;
    int equipDodge;
    int equipPhyVa;
    int equipMagicVa;
    int equipMeleePowerAdd;
    int equipMinMelee;
    int equipMaxMelee;

    private final Set<Integer> equipImmunities = new LinkedHashSet<>();

    /**
     * 从装备栏词缀重算加成累积（propBuilt 串 "1,id;1,id" → proppack 节 → r= 属性行）。
     * 幂等：先清零再累积，穿/脱/登录均可反复调用；基础属性字段不受影响。
     *
     * @param propBuilts 每件已穿装备的词缀串（缺列的库/角色传空元素即可）
     */
    public void recomputeEquipBonuses(EquipAffixCatalog catalog, List<String> propBuilts) {
        clearEquipAccumulators();
        if (catalog == null || propBuilts == null) {
            return;
        }
        for (String built : propBuilts) {
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
                for (EquipAffixCatalog.Mod mod : catalog.modsOf(packId)) {
                    accumulateEquipMod(mod);
                }
            }
        }
    }

    /**
     * 装备特效应用（武器走 EffectPackID → 特效包 → buff 链）：与 recomputeEquipBonuses
     * 同调用点、先词缀后特效。属性类效果（varprop @PropModifyPackRec）复用词缀累积器
     * 继续叠加；Immunity 位收集进 {@link #equipImmunities()}。
     *
     * @param effectPackIds 每件已穿装备的特效包号（0=无；来自 equipment.ini 配置）
     */
    public void applyEquipEffects(EquipEffectCatalog effects, EquipAffixCatalog packs,
                                  List<Integer> effectPackIds) {
        equipImmunities.clear();
        if (effects == null || effectPackIds == null) {
            return;
        }
        for (int packId : effectPackIds) {
            for (EquipEffectCatalog.EquipBuff buff : effects.buffsOfPack(packId)) {
                if (packs != null) {
                    for (int propPack : effects.propPacksOf(buff.buffId(), buff.level())) {
                        for (EquipAffixCatalog.Mod mod : packs.modsOf(propPack)) {
                            accumulateEquipMod(mod);
                        }
                    }
                }
                equipImmunities.addAll(effects.immunitiesOf(buff.buffId(), buff.level()));
            }
        }
    }

    /** 当前装备特效免疫位（只读视图）。 */
    public Set<Integer> equipImmunities() {
        return Collections.unmodifiableSet(equipImmunities);
    }

    /**
     * 词缀属性名 → equip*Add 累积器；未覆盖词缀（概率%/倍率/内功防御系等）忽略。
     * 后缀省略变体（PhyDef/MaxHP/Str/Sta/Dex/Dodge/MaxMP/MeleePower，实测 proppack
     * 与抓包装备 62995=PhyDef+475）与 *Add 同累积器；重复包号逐条叠加。
     */
    private void accumulateEquipMod(EquipAffixCatalog.Mod mod) {
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

    private void clearEquipAccumulators() {
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
    }

    /** 面板战斗块叠加层快照（{@code PlayerProgress.properties} 的装备输入）。 */
    public PlayerProgress.EquipBonus equipBonus() {
        return new PlayerProgress.EquipBonus(equipMaxHpAdd, equipMaxMpAdd,
                equipStrAdd, equipStaAdd, equipDexAdd, equipIngAdd, equipSpiAdd,
                equipMeleePowerAdd, equipPhyVa, equipMagicVa,
                equipPhyHit, equipMagicHit, equipDodge, equipPhyDef,
                equipMinMelee, equipMaxMelee);
    }

    /** 身份块属性（按名写入官方 2424 表；LevelTitle 随 PowerLevel 档位）。 */
    public List<PropertyValues.Indexed> identityProperties() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(14);
        Props.add(properties, "PowerLevel", PropertyValues.Value.ofInt32(powerLevel));
        Props.add(properties, "LevelTitle", PropertyValues.Value.ofString(
                LoginMessages.roleListTitle(powerLevel)));
        Props.add(properties, "CharacterFlag", PropertyValues.Value.ofInt32(characterFlag));
        Props.add(properties, "CharacterValue", PropertyValues.Value.ofInt32(characterValue));
        Props.add(properties, "Justice", PropertyValues.Value.ofInt32(justice));
        Props.add(properties, "Evil", PropertyValues.Value.ofInt32(evil));
        Props.add(properties, "FreeBase", PropertyValues.Value.ofInt32(freeBase));
        Props.add(properties, "Ene", PropertyValues.Value.ofInt32(ene));
        Props.add(properties, "MaxEne", PropertyValues.Value.ofInt32(maxEne));
        Props.add(properties, "ResumeEne", PropertyValues.Value.ofInt32(resumeEne));
        Props.add(properties, "Sat", PropertyValues.Value.ofInt32(sat));
        Props.add(properties, "MaxSat", PropertyValues.Value.ofInt32(maxSat));
        Props.add(properties, "PKValue", PropertyValues.Value.ofInt32(pkValue));
        Props.add(properties, "DrunkLevel", PropertyValues.Value.ofInt32(drunkLevel));
        Props.add(properties, "PKMode", PropertyValues.Value.ofInt32(pkMode));
        return properties;
    }

    /**
     * 域块属性（四期）：佩戴称号/坐骑/兵录/声望。保守语义——BingLuType 无数据源发 0，
     * CurRideMount 骑乘态未接发空对象，均真机待验后按抓包修正。
     */
    public List<PropertyValues.Indexed> domainProperties() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(6);
        Props.add(properties, "TitleID", PropertyValues.Value.ofInt32(wornTitleId));
        Props.add(properties, "Mount", PropertyValues.Value.ofString(mount == null ? "" : mount));
        Props.add(properties, "CurRideMount", PropertyValues.Value.ofObject(0));
        Props.add(properties, "BingLuType", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "MaxBingLuLevel", PropertyValues.Value.ofInt32(maxBingLuLevel));
        Props.add(properties, "PrestigeExp", PropertyValues.Value.ofInt32(prestigeExp));
        return properties;
    }

    /** 生存属性快照（GM set_vitals 落 role_faculty 档案的嵌套 vitals 节）。 */
    public PlayerProgress.Vitals vitalsSnapshot() {
        return new PlayerProgress.Vitals(ene, maxEne, resumeEne, sat, maxSat, pkValue, drunkLevel,
                maxHpOverride, maxMpOverride);
    }

    /** 登录回填生存属性（旧档无 vitals 节 = 从未 GM 设置，保持 0 种子不动）。 */
    public void restoreVitals(PlayerProgress.Vitals value) {
        if (value == null) {
            return;
        }
        ene = Math.max(0, value.ene());
        maxEne = Math.max(0, value.maxEne());
        resumeEne = Math.max(0, value.resumeEne());
        sat = Math.max(0, value.sat());
        maxSat = Math.max(0, value.maxSat());
        pkValue = Math.max(0, value.pkValue());
        drunkLevel = Math.max(0, value.drunkLevel());
        maxHpOverride = Math.max(0, value.maxHpOverride());
        maxMpOverride = Math.max(0, value.maxMpOverride());
    }

    /** 血蓝上限客户端校准值（血条上限由客户端按装备/内功自算，服务端公式复刻前覆盖对齐；0=走公式）。 */
    public int maxHpOverride;

    public int maxHpOverride() {
        return maxHpOverride;
    }

    public int maxMpOverride;

    public int maxMpOverride() {
        return maxMpOverride;
    }
}
