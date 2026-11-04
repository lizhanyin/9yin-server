package com.nineyin.server.game.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.protocol.PropertyValues;

/**
 * 四期实时面板：totalResourceAdds 公式（IDA 钉死：HP=基础+Add层+7×体魄+2×臂力；
 * MP=基础+Add层+4×内息+罡气）与五系伤害块（varprop 透传 / 千分÷1000）。
 */
class PlayerPanelRealtimeTest {

    @Test
    void totalResourceAddsFollowsExeFormula() {
        PlayerProgress progress = PlayerProgress.create(Clock.systemUTC());
        // create() 的内联种子（无资源目录回退）：ng_wd_001 运功，
        // strEff=30+45=75 / staEff=30+31=61 / ingEff=30+46=76 / spiEff=30+13=43
        PlayerProgress.EquipBonus bonus = new PlayerProgress.EquipBonus(
                300, 120, 10, 20, 5, 6, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        int[] adds = progress.totalResourceAdds(bonus);
        assertEquals(300 + 1630, adds[0], "maxHpAdd = 装备层 + 书层");
        assertEquals(120 + 490, adds[1], "maxMpAdd");
        assertEquals(30 + 10 + 45, adds[2], "strEff = 基础+装备+书");
        assertEquals(30 + 20 + 31, adds[3], "staEff");
        assertEquals(30 + 6 + 46, adds[4], "ingEff");
        assertEquals(30 + 7 + 13, adds[5], "spiEff");
        // 公式本体（Actor 基础 1000/500）
        int maxHp = 1000 + adds[0] + 7 * adds[3] + 2 * adds[2];
        int maxMp = 500 + adds[1] + 4 * adds[4] + adds[5];
        assertEquals(1000 + 1930 + 7 * 81 + 2 * 85, maxHp);
        assertEquals(500 + 610 + 4 * 82 + 50, maxMp);
    }

    @Test
    void actorDerivedMaximaUseEffectiveFormula() {
        PlayerActor player = PlayerActor.create("面板玩家", 99999, Clock.systemUTC());
        // 内联种子：1000+1630+7×61+2×75 / 500+490+4×76+43
        assertEquals(1000 + 1630 + 7 * 61 + 2 * 75, player.actorState().maxHp());
        assertEquals(500 + 490 + 4 * 76 + 43, player.actorState().maxMp());
        // 装备层重算 → 上限随之变化（体力词缀 +39×3 → 7×117 的体魄项抬升）
        player.recomputeEquipBonuses(List.of(), List.of());
        assertEquals(1000 + 1630 + 7 * 61 + 2 * 75, player.actorState().maxHp(), "空装备=种子值");
    }

    @Test
    void statePropertiesCarryDamageAndIdentityBlocks() {
        PlayerActor player = PlayerActor.create("面板玩家", 99999, Clock.systemUTC());
        // 注入 varprop stats：DamageAdd 族 + DamageMul 千分数
        PlayerProgress.Book equipped = player.progress.book(player.progress.curNeiGong);
        Map<String, Integer> stats = new HashMap<>(equipped.effectStats);
        stats.put("PhyDamageAdd", 240);
        stats.put("MasDamageAdd", 18);
        stats.put("PhyDamageMul", 1250);
        equipped.effectStats = stats;

        List<PropertyValues.Indexed> damage = player.damageProperties();
        Map<String, PropertyValues.Value> byName = new HashMap<>();
        for (PropertyValues.Indexed property : damage) {
            byName.put(property.name(), property.value());
        }
        assertEquals(12, damage.size());
        assertEquals(0, byName.get("MaxShotDamage").i32(), "暗器上限种子 0");
        assertEquals(240, byName.get("PhyDamageAdd").i32(), "varprop 透传");
        assertEquals(18, byName.get("MasDamageAdd").i32());
        assertEquals(1.25f, byName.get("PhyDamageMul").f32(), 0.0001f, "千分÷1000");
        assertEquals(0f, byName.get("StiDamageMul").f32(), 0.0001f, "缺键=0");

        List<PropertyValues.Indexed> state = player.stateProperties();
        List<String> names = state.stream().map(PropertyValues.Indexed::name).toList();
        assertTrue(names.contains("PowerLevel"), "stateProperties 含身份块");
        assertTrue(names.contains("PhyDamageAdd"), "stateProperties 含伤害块");
        assertTrue(names.contains("Justice"));
        assertTrue(names.contains("LevelTitle"));
        // 有效上限下发（非 base）
        int maxHp = valueOf(state, "MaxHP").i32();
        assertTrue(maxHp > 1000, "MaxHP=有效上限（含 Add 层与五维换算），实际=" + maxHp);
    }

    @Test
    void combatBlockOverlaysEquipBonusOnBase() {
        PlayerActor player = PlayerActor.create("面板玩家", 99999, Clock.systemUTC());
        // 通过面板状态字段间接驱动：直接构造 bonus 检查叠加（基础 str=30 + 书 strAdd=45）
        PlayerProgress.EquipBonus bonus = new PlayerProgress.EquipBonus(
                0, 0, 100, 0, 0, 0, 0, 50, 0, 30, 8, 6, 12, 200, 25, 40);
        List<PropertyValues.Indexed> block = player.progress.properties(bonus);
        assertEquals(30 + 100 + 45, valueOf(block, "Str").i32(), "基础+装备+书");
        assertEquals(80 + 50, valueOf(block, "MeleePower").i32(), "MeleePower=基础+装备层（无书 eff 键）");
        assertEquals(50 + 8, valueOf(block, "PhyHit").i32());
        assertEquals(35 + 12, valueOf(block, "Dodge").i32());
        assertEquals(60 + 200, valueOf(block, "PhyDef").i32());
        assertEquals(20 + 25, valueOf(block, "MinMeleeDamage").i32());
        assertEquals(35 + 40, valueOf(block, "MaxMeleeDamage").i32());
    }

    @Test
    void meleeDamageRangeKeepsUnarmedBase() {
        PlayerActor player = PlayerActor.create("空手侠", 99998, Clock.systemUTC());
        // 拳/腿套路无武器限制：空手仍有基础外功 20-35，掷值不落 0（否则公式保底恒 1 点，
        // 真机表现为「打怪无伤害」——2026-11-04 修复回归钉）
        int[] bare = player.meleeDamageRange();
        assertEquals(20, bare[0], "空手 min=基础下限");
        assertEquals(35, bare[1], "空手 max=基础上限");

        // 有武器：与面板 Min/MaxMeleeDamage 同口径（基础+装备），MeleePower 不折入
        PlayerProgress.EquipBonus bonus = new PlayerProgress.EquipBonus(
                0, 0, 0, 0, 0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 25, 40);
        int[] armed = player.progress.meleeDamageRange(bonus);
        assertEquals(20 + 25, armed[0], "min=基础+装备（面板同口径）");
        assertEquals(35 + 40, armed[1], "max=基础+装备（面板同口径）");
    }

    private static PropertyValues.Value valueOf(List<PropertyValues.Indexed> properties, String name) {
        for (PropertyValues.Indexed property : properties) {
            if (property.name().equals(name)) {
                return property.value();
            }
        }
        throw new IllegalArgumentException("property absent: " + name);
    }
}
