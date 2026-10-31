package com.nineyin.game;

import com.nineyin.scene.SceneManager;
import com.nineyin.script.ItemScriptBridge;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 道具使用链单测：ItemUseCatalog（真实 share 数据 spot check）、DropPacks
 * 权重抽取确定性、ItemScriptBridge 指令白名单解析、PlayerActor 限时属性层。
 */
class ItemUseCatalogTest {

    // ---------- ItemUseCatalog：INI 解析 ----------

    @Test
    void parseSectionsHandlesHeadersCommentsAndCase() {
        String ini = "; 注释\n[-1]\nDropID=0\n\n[box_a]\nscript=BoxItem\nDropID= comb_x \n"
                + "// 行注释\n[box_b]\nDROPID=comb_y\n";
        Map<String, Map<String, String>> sections = ItemUseCatalog.parseSections(ini);
        assertEquals("comb_x", sections.get("box_a").get("dropid"));
        assertEquals("comb_y", sections.get("box_b").get("dropid"));
    }

    @Test
    void fallbackHealLadder() {
        assertEquals(100, ItemUseCatalog.fallbackHealHp(1));
        assertEquals(3000, ItemUseCatalog.fallbackHealHp(9));
        assertEquals(100, ItemUseCatalog.fallbackHealHp(0));      // 越界夹紧
        assertEquals(3000, ItemUseCatalog.fallbackHealHp(99));
        assertEquals(750, ItemUseCatalog.fallbackHealMp(7), "回蓝 = 回血一半（L7 1500/2）");
    }

    // ---------- ItemUseCatalog / DropPacks：真实数据 spot check ----------

    @Test
    void realShareDataSpotChecks() {
        Path shareRoot = SceneManager.locateShareRoot();
        assumeTrue(Files.isRegularFile(shareRoot.resolve("item").resolve("item_use_skill_static.ini")),
                "真实 share 数据不可见，跳过：" + shareRoot);

        ItemUseCatalog catalog = ItemUseCatalog.load(shareRoot);
        assertTrue(catalog.boxCount() > 0, "giftbox.ini 应至少解析出一个礼包");
        assertTrue(catalog.useSkillTypeCount() > 100, "item_use_skill_static 应有上百个 itemType");

        // 礼包：惊鸿礼盒 → MVP 掉落包
        ItemUseCatalog.UseEntry box = catalog.resolve("box_chinajoy_2026_giftbox", -1);
        assertNotNull(box, "box_chinajoy_2026_giftbox 应解析为 BOX");
        assertEquals(ItemUseCatalog.UseKind.BOX, box.kind());
        assertEquals("comb_ai_drop_box_chinajoy_2026_giftbox", box.dropId());

        // 药品：item_type 2101 → yaopinhp1 L1（share/item/item_use_skill_static.ini [2101]）
        ItemUseCatalog.UseEntry skill = catalog.resolve("<unknown-config>", 2101);
        assertNotNull(skill);
        assertEquals(ItemUseCatalog.UseKind.USE_SKILL, skill.kind());
        assertEquals("yaopinhp1", skill.skillId());
        assertEquals(1, skill.skillLevel());

        // 未知 config + 未知 itemType → null（保持原日志拒绝行为）
        assertNull(catalog.resolve("<unknown-config>", -1));

        // 掉落包：真实 drops_mvp.json，固定随机源确定性抽取
        DropPacks packs = DropPacks.load(shareRoot.resolve("drop").resolve("drops_mvp.json"));
        assertTrue(packs.size() > 1000, "drops_mvp.json 应有上千个包");
        assertTrue(packs.has("comb_ai_drop_box_chinajoy_2026_giftbox"));
        List<ItemScriptBridge.GrantItem> first = packs.roll(
                "comb_ai_drop_box_chinajoy_2026_giftbox", 1, () -> 0.0);
        assertEquals(1, first.size(), "r=0 应命中权重区首条 yaopin_00100");
        assertEquals("yaopin_00100", first.get(0).configId());
        assertEquals(1, first.get(0).amount(), "min=max=1 时数量恒 1");
        assertTrue(packs.roll("<no-such-drop>", 3, () -> 0.5).isEmpty());
    }

    // ---------- DropPacks：权重与数量确定性 ----------

    private DropPacks fixturePacks() {
        // 手写最小 JSON 驱动（不依赖真实数据）
        Path json = null;
        try {
            json = java.nio.file.Files.createTempFile("drops", ".json");
            java.nio.file.Files.writeString(json, """
                    {"packages": {"p1": [
                      {"item": "a", "min": 1, "max": 3, "weight": 70},
                      {"item": "b", "min": 2, "max": 2, "weight": 30}
                    ]}}
                    """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return DropPacks.load(json);
    }

    @Test
    void rollRespectsWeightsAndAmountRange() {
        DropPacks packs = fixturePacks();
        // r=0.0 → 首条 a；数量 r=0.99 → max=3
        List<ItemScriptBridge.GrantItem> out = packs.roll("p1", 1,
                java.util.List.of(0.0, 0.99).iterator()::next);
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).configId());
        assertEquals(3, out.get(0).amount());

        // r=0.999 → 末条 b（权重边界）；数量 r=0.5 → 2
        out = packs.roll("p1", 1, java.util.List.of(0.999, 0.5).iterator()::next);
        assertEquals("b", out.get(0).configId());
        assertEquals(2, out.get(0).amount());

        // 多次抽取合并同类
        out = packs.roll("p1", 2, java.util.List.of(0.0, 0.0, 0.0, 0.0).iterator()::next);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).amount(), "两次 a@min1 应合并为 2");
    }

    // ---------- ItemScriptBridge：指令白名单 ----------

    @Test
    void bridgeParsesFullInstructionSet() {
        ItemScriptBridge.Instructions instr = ItemScriptBridge.parseInstructions("demo", Map.of(
                "heal_hp", 300, "heal_mp", 120,
                "grant_items", List.of(Map.of("id", "sd_001", "amount", 2)),
                "temp_stat", Map.of("attack_add", 25, "life_ms", 86400000L),
                "consume", true));
        assertNotNull(instr);
        assertEquals(300, instr.healHp());
        assertEquals(120, instr.healMp());
        assertEquals(1, instr.grantItems().size());
        assertEquals("sd_001", instr.grantItems().get(0).configId());
        assertEquals(25, instr.tempStat().attackAdd());
        assertTrue(instr.consume());
    }

    @Test
    void bridgeDefaultsAndDropsInvalidEntries() {
        // 无 consume 字段 → 默认消耗
        ItemScriptBridge.Instructions heal = ItemScriptBridge.parseInstructions("demo",
                Map.of("heal_hp", 50));
        assertTrue(heal.consume());
        // 负回血归零、脏 grant 条目丢弃、temp_stat 缺 life_ms 拒绝
        ItemScriptBridge.Instructions bad = ItemScriptBridge.parseInstructions("demo", Map.of(
                "heal_hp", -5,
                "grant_items", List.of(Map.of("amount", 3), "junk", Map.of("id", "ok", "amount", 1)),
                "temp_stat", Map.of("attack_add", 10)));
        assertEquals(0, bad.healHp());
        assertEquals(1, bad.grantItems().size(), "脏条目丢弃，合法条目保留");
        assertNull(bad.tempStat());
        // open_box rolls 越界夹紧
        ItemScriptBridge.Instructions box = ItemScriptBridge.parseInstructions("demo",
                Map.of("open_box", Map.of("drop_id", "comb_x", "rolls", 99)));
        assertEquals(10, box.openBox().rolls());
        // 非 table 返回 → null
        assertNull(ItemScriptBridge.parseInstructions("demo", "string"));
        assertNull(ItemScriptBridge.parseInstructions("demo", null));
    }

    // ---------- PlayerActor：限时属性层 ----------

    @Test
    void tempStatAppliesExpiresAndReplaces() {
        PlayerActor actor = new PlayerActor();
        int baseAttack = actor.combatStats().attack();
        actor.applyTempStat("useitemhs_dy_06", 25, 0, 0, 60_000, 1_000L);
        assertEquals(baseAttack + 25, actor.combatStats().attack());

        // 同源替换：+40 覆盖 +25
        actor.applyTempStat("useitemhs_dy_06", 40, 0, 0, 60_000, 1_000L);
        assertEquals(baseAttack + 40, actor.combatStats().attack());

        // 另一来源叠加
        actor.applyTempStat("other_item", 10, 5, 0, 60_000, 1_000L);
        assertEquals(baseAttack + 50, actor.combatStats().attack());

        // 到期自清
        assertTrue(actor.expireTempStats(61_000L));
        assertEquals(baseAttack, actor.combatStats().attack());
        assertFalse(actor.expireTempStats(62_000L));
    }

    // ---------- playerAddObject：互播变体帧结构 ----------

    @Test
    void playerAddObjectVariantDiffersOnlyInObjectId() {
        List<String> appearance = List.of();
        byte[] local = com.nineyin.protocol.S2CPackets.playerAddObject(
                "本地侠", appearance, 1, 2, 3, 0.5f);
        byte[] remote = com.nineyin.protocol.S2CPackets.playerAddObject(
                500001, com.nineyin.protocol.S2CPackets.PLAYER_OWNER_ID,
                "远端侠", appearance, 4, 5, 6, 0.25f);
        assertEquals(local.length, remote.length, "帧长度一致（同一属性集）");
        // 头部结构：[0]=0x0D, oid u32@1, owner u32@5
        assertEquals(0x0D, remote[0] & 0xFF);
        assertEquals(500001, readU32(remote, 1));
        assertEquals(com.nineyin.protocol.S2CPackets.PLAYER_OWNER_ID, readU32(remote, 5));
    }

    private static int readU32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }
}
