package com.nineyin.server.game.starter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

/**
 * 技能学习表（share/skill/skill_init.ini，节名 = 技能 ConfigID，CRLF）。
 * 每节字段：staticData/itemType/level/maxlevel/total/pauseTime/coolDown/consumeSP
 * （本表消费前六显示项中除 pauseTime 外的五项；PauseTime 仍取 StarterSkills 内联帧表）。
 * 注意：官方数据的 coolDown 字段全 0，**不是** View40 行 CoolDownTime 显示源——
 * 显示值取 varprop 按级 CoolDownTime（CombatSkillCatalog.personalCdMs，抓包实证），
 * 本表 coolDown 仅作自定义覆盖兜底。
 * 文件缺失/为空 → 空表，View40 行回退内联值（与 NeiGongCatalog 同 best-effort 方言）。
 */
public final class SkillInitTable {

    /** 一条已学习技能（coolDown 为自定义覆盖毫秒，官方数据全 0；totalFill 为修炼总量显示值）。 */
    public record Row(String configId, int level, int staticData,
                      int itemType, int maxLevel, int coolDownMs, int totalFill) {
    }

    private final Map<String, Row> rows = new HashMap<>();

    /** 目录级缓存（serverRoot 变化自动换表）。 */
    public static SkillInitTable load() {
        return ResourceCache.of("skill-init:" + ServerPaths.projectPath("resources", "modern", "share"), () -> {
            SkillInitTable table = new SkillInitTable();
            table.loadAll(ServerPaths.projectPath("resources", "modern", "share"));
            return table;
        });
    }

    private void loadAll(Path shareRoot) {
        loadIni(shareRoot.resolve(Path.of("skill", "skill_init.ini")));
    }

    /** 单文件解析（测试注入临时 ini 用；ResourceCache 按路径缓存文本）。 */
    static SkillInitTable loadIni(Path ini) {
        SkillInitTable parsed = new SkillInitTable();
        ResourceCache.ini(ini, StandardCharsets.ISO_8859_1, (section, key, value) -> {
            Accumulator row = parsed.table.computeIfAbsent(section, ignored -> new Accumulator());
            switch (key.toLowerCase(Locale.ROOT)) {
                case "level" -> row.level = parseIntOrDefault(value, 1);
                case "staticdata" -> row.staticData = parseIntOrDefault(value, 0);
                case "itemtype" -> row.itemType = parseIntOrDefault(value, 0);
                case "maxlevel" -> row.maxLevel = parseIntOrDefault(value, 1);
                case "cooldown" -> row.coolDownMs = parseIntOrDefault(value, 0);
                case "total" -> row.totalFill = parseIntOrDefault(value, 0);
                default -> {
                }
            }
        });
        return parsed;
    }

    /** 行查询（无该技能/空表 → null，调用方回退内联值）。 */
    public Row row(String configId) {
        Accumulator row = configId == null ? null : table.get(configId);
        if (row == null || row.staticData == 0) {
            return null;
        }
        return new Row(configId, Math.max(1, row.level), row.staticData, row.itemType,
                Math.max(1, row.maxLevel), row.coolDownMs, row.totalFill);
    }

    public int size() {
        return table.size();
    }

    private static int parseIntOrDefault(String text, int fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private final Map<String, Accumulator> table = new HashMap<>();

    private static final class Accumulator {
        int level = 1;
        int staticData;
        int itemType;
        int maxLevel = 1;
        int coolDownMs;
        int totalFill;
    }

    private SkillInitTable() {
    }
}
