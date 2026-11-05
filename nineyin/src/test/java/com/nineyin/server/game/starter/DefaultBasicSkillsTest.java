package com.nineyin.server.game.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nineyin.server.world.db.RoleInventoryStore;
import org.junit.jupiter.api.Test;

/**
 * 基础武学→三脚猫招式五件套（bug.txt）：starter 回退集携带 + 登录全量下发
 * DB 补集语义（缺则按默认序补队首，幂等不落库）。
 */
class DefaultBasicSkillsTest {

    /** 官方观测序：剑普攻 / 飞刀普攻 / 打坐调息 / 自断经脉 / 骑术精通。 */
    private static final List<String> QUINTET = List.of(
            "zs_normal_02", "hw_normal_fd", "zs_default_01", "zs_suicide", "riding_start");

    @Test
    void starterSetCarriesDefaultBasicQuintetInCanonicalOrder() {
        List<String> ids = StarterSkills.starterSkillIds();
        assertEquals(QUINTET, ids.subList(0, 5), "五件套按 剑普攻/飞刀普攻/打坐调息/自断经脉/骑术精通 序居首");
        assertEquals("CS_wd_tjq08", ids.get(ids.size() - 1), "末行仍为太极拳(古谱)第八式");
        assertEquals(27, ids.size(), "五件套 + 花神 7 + 心佛掌 7 + 太极拳 8");
        assertEquals(27, StarterSkills.starterLearnedLevels().size(), "已学表同步携带");
        for (String id : QUINTET) {
            assertEquals(1, StarterSkills.starterLearnedLevels().get(id));
        }
    }

    @Test
    void withDefaultBasicsReturnsEmptyListAsIs() {
        assertNull(StarterSkills.withDefaultBasics(null), "null（无 DB 行）原样返回走 starter 回退");
        assertTrue(StarterSkills.withDefaultBasics(List.of()).isEmpty(), "空表同上");
    }

    @Test
    void withDefaultBasicsPrependsMissingQuintetInCanonicalOrder() {
        List<RoleInventoryStore.Skill> db = List.of(
                new RoleInventoryStore.Skill("CS_wd_tjq01", "wuxue", 4057, 1000),
                new RoleInventoryStore.Skill("zs_default_01", "wuxue", 22, 1000));
        List<RoleInventoryStore.Skill> merged = StarterSkills.withDefaultBasics(db);
        assertEquals(6, merged.size());
        assertEquals(List.of("zs_normal_02", "hw_normal_fd", "zs_suicide", "riding_start",
                        "CS_wd_tjq01", "zs_default_01"),
                merged.stream().map(RoleInventoryStore.Skill::skillId).toList(),
                "缺席四件按默认序补队首，已有 zs_default_01 原位保留");
        // 内存合成行：skillType=wuxue，staticData/itemType 与 game_skills 目录一致
        RoleInventoryStore.Skill swordAttack = merged.get(0);
        assertEquals("wuxue", swordAttack.skillType());
        assertEquals(15, swordAttack.staticData(), "zs_normal_02 目录 staticData");
        assertEquals(1000, swordAttack.itemType());
        assertEquals(626, merged.get(1).staticData(), "hw_normal_fd 目录 staticData");
        assertEquals(55, merged.get(2).staticData(), "zs_suicide 目录 staticData");
        assertEquals(21515, merged.get(3).staticData(), "riding_start 目录 staticData");
    }

    @Test
    void withDefaultBasicsIsIdempotentWhenQuintetPresent() {
        List<RoleInventoryStore.Skill> db = List.of(
                new RoleInventoryStore.Skill("zs_normal_02", "wuxue", 15, 1000),
                new RoleInventoryStore.Skill("hw_normal_fd", "wuxue", 626, 1000),
                new RoleInventoryStore.Skill("riding_start", "wuxue", 21515, 1000),
                new RoleInventoryStore.Skill("zs_default_01", "wuxue", 22, 1000),
                new RoleInventoryStore.Skill("zs_suicide", "wuxue", 55, 1000));
        assertSame(db, StarterSkills.withDefaultBasics(db), "五件套齐全原列表直返（不复制不落库）");
    }
}
