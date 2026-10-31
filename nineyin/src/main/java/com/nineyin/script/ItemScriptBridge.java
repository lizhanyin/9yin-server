package com.nineyin.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 道具 Lua ↔ Java 数据桥（纯转换，无状态）：道具使用钩子
 * （scripts/item/&lt;configId&gt;.lua 的 on_use）的输入快照构造与输出指令解析，
 * 风格与 {@link NeigongScriptBridge} 一致。
 *
 * <p>解析原则：指令白名单、字段强校验，非法条目丢弃 + WARN（单脚本错误不得
 * 打断使用链路）。随机数由 Java 预掷传入 {@code ctx.rand.r_drop/r_proc}
 * （脚本不掷骰，保证可测试）。
 *
 * <p>指令集（M.on_use 返回）：
 * <pre>
 * return {
 *   heal_hp    = 300,                       -- 回气血
 *   heal_mp    = 100,                       -- 回内力
 *   open_box   = {drop_id="comb_xx", rolls=1}, -- 按 drops_mvp.json 权重开包（Java 解析）
 *   grant_items = {{id="sd_001", amount=2}},-- 确定性直接给物品
 *   temp_stat  = {attack_add=25, defense_add=0, max_hp_add=0, life_ms=86400000}, -- 限时属性（同源替换）
 *   consume    = true,                      -- 是否消耗 1 个（默认 true）
 * }
 * </pre>
 */
public final class ItemScriptBridge {

    private static final Logger log = LoggerFactory.getLogger(ItemScriptBridge.class);

    private ItemScriptBridge() {
    }

    /** 确定性给物品指令。 */
    public record GrantItem(String configId, int amount) {
    }

    /** 限时属性指令（同 configId 源替换，life_ms &gt; 0）。 */
    public record TempStat(int attackAdd, int defenseAdd, int maxHpAdd, long lifeMs) {
    }

    /** 开包指令（dropId → drops_mvp.json 权重包；rolls 1..10）。 */
    public record OpenBox(String dropId, int rolls) {
    }

    /** on_use 输出（nil/非 table = 无效果，仍按默认消耗处理由调用方决定）。 */
    public record Instructions(int healHp, int healMp, OpenBox openBox,
                               List<GrantItem> grantItems, TempStat tempStat, boolean consume) {

        public boolean isEmpty() {
            return healHp <= 0 && healMp <= 0 && openBox == null
                    && (grantItems == null || grantItems.isEmpty()) && tempStat == null;
        }
    }

    // ---------- ctx 构造 ----------

    /** on_use 输入：item_id/amount/now + self{hp,max_hp,mp,max_mp,silver} + rand{r_drop,r_proc}。 */
    public static Map<String, Object> useContext(String configId, int amount, long now,
                                                 int hp, int maxHp, int mp, int maxMp, long silver,
                                                 double rDrop, double rProc) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("item_id", configId == null ? "" : configId);
        ctx.put("amount", Math.max(1, amount));
        ctx.put("now", now);
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("hp", hp);
        self.put("max_hp", maxHp);
        self.put("mp", mp);
        self.put("max_mp", maxMp);
        self.put("silver", silver);
        ctx.put("self", self);
        Map<String, Object> rand = new LinkedHashMap<>();
        rand.put("r_drop", rDrop);
        rand.put("r_proc", rProc);
        ctx.put("rand", rand);
        return ctx;
    }

    // ---------- 输出解析 ----------

    /** 解析 on_use 返回的指令表（null/非 table → null）。 */
    public static Instructions parseInstructions(String configId, Object result) {
        if (!(result instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> table = (Map<String, Object>) result;
        int healHp = positive(table, "heal_hp", configId);
        int healMp = positive(table, "heal_mp", configId);
        OpenBox openBox = parseOpenBox(configId, table.get("open_box"));
        List<GrantItem> grants = parseGrantItems(configId, table.get("grant_items"));
        TempStat tempStat = parseTempStat(configId, table.get("temp_stat"));
        boolean consume = !(table.get("consume") instanceof Boolean b) || b;
        return new Instructions(healHp, healMp, openBox, grants, tempStat, consume);
    }

    private static OpenBox parseOpenBox(String configId, Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) v;
        String dropId = t.get("drop_id") instanceof String s && !s.isBlank() ? s : null;
        if (dropId == null) {
            warn(configId, "open_box needs drop_id");
            return null;
        }
        long rolls = t.get("rolls") instanceof Number n ? n.longValue() : 1;
        if (rolls < 1 || rolls > 10) {
            warn(configId, "open_box rolls " + rolls + " outside 1..10, clamped");
            rolls = Math.max(1, Math.min(10, rolls));
        }
        return new OpenBox(dropId, (int) rolls);
    }

    private static List<GrantItem> parseGrantItems(String configId, Object v) {
        if (!(v instanceof List<?> list)) {
            return null;
        }
        List<GrantItem> out = new ArrayList<>();
        for (Object e : list) {
            if (!(e instanceof Map)) {
                warn(configId, "grant_items entry must be {id=.., amount=..}");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) e;
            String id = t.get("id") instanceof String s && !s.isBlank() ? s : null;
            long amount = t.get("amount") instanceof Number n ? n.longValue() : 1;
            if (id == null || amount < 1 || amount > 999) {
                warn(configId, "invalid grant_items entry ignored");
                continue;
            }
            out.add(new GrantItem(id, (int) amount));
        }
        return out;
    }

    private static TempStat parseTempStat(String configId, Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) v;
        long life = t.get("life_ms") instanceof Number n ? n.longValue() : 0;
        int attack = signed(t, "attack_add");
        int defense = signed(t, "defense_add");
        int maxHp = signed(t, "max_hp_add");
        if (life <= 0 || (attack == 0 && defense == 0 && maxHp == 0)) {
            warn(configId, "temp_stat needs life_ms>0 and at least one *_add");
            return null;
        }
        return new TempStat(attack, defense, maxHp, life);
    }

    // ---------- 读取辅助 ----------

    private static int positive(Map<String, Object> t, String key, String configId) {
        long v = t.get(key) instanceof Number n ? n.longValue() : 0;
        if (v <= 0) {
            return 0;
        }
        if (v > Integer.MAX_VALUE) {
            warn(configId, key + " overflow clamped");
            return Integer.MAX_VALUE;
        }
        return (int) v;
    }

    private static int signed(Map<String, Object> t, String key) {
        long v = t.get(key) instanceof Number n ? n.longValue() : 0;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    private static void warn(String configId, String detail) {
        log.warn("item script {}: {}", configId, detail);
    }
}
