package com.nineyin.server.gm;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GM 伙伴预设闭名单（bot_* 动作数据源）：面板键 → 伙伴定义。默认伙伴「青禾」
 * 复用战斗假人同款 attacknpc 模板（AttackNpc scriptClass 天然 combat——可选中/
 * 受击/可被打死供 bot_revive 演练），生成期覆盖名字/血量/跑速；反击 AI 按
 * FightState 伙伴标记跳过（永不还手主人）。
 *
 * <p>刻意闭名单（与 {@link GmFactionPresets} 同款约束）：bot_ensure 的 bot 键
 * 必须命中本表，绝不接受任意模板/资源字符串——GM 可换预设，不可注入模板。
 */
public final class GmBotPresets {

    /**
     * 伙伴预设：模板表/模板 id（GmNpcFactory.spawnFromTemplate 口径）+ 生成期
     * 覆盖值（名字/血量/跑速）+ 辅助技能缺省（施法视觉技/单次治疗量/周期秒）。
     */
    public record Bot(String key, String name, String table, String template,
                      int maxHp, float runSpeed,
                      String supportSkill, int supportHeal, int supportIntervalSec) {
    }

    private static final Map<String, Bot> PRESETS = buildPresets();

    private static Map<String, Bot> buildPresets() {
        Bot qinghe = new Bot("qinghe", "青禾",
                // attacknpc「机关木人」：spawn_combat_dummy 同款，唯一全链验证的人形 AttackNpc 模板。
                "worldnpc/attacknpc.txt", "monf022202",
                5_000, 6.0f,
                // 辅助视觉技与 NPC 反击同款（NpcCombatAi.NPC_ATTACK_SKILL 口径）。
                "jn_mon053_02", 500, 5);
        Map<String, Bot> presets = new HashMap<>();
        presets.put(qinghe.key(), qinghe);
        return Map.copyOf(presets);
    }

    /** 面板键查预设；空/未收录返回 null（入口与游戏侧据此拒绝）。 */
    public static Bot preset(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return PRESETS.get(key.trim().toLowerCase(Locale.ROOT));
    }

    /** 默认伙伴（bot_ensure 未指定 bot 键时）。 */
    public static Bot defaultPreset() {
        return PRESETS.get("qinghe");
    }

    /** 全部面板键（测试覆盖度断言用）。 */
    public static Set<String> keys() {
        return PRESETS.keySet();
    }

    private GmBotPresets() {
    }
}
