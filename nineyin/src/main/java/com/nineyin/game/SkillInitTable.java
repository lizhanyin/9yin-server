package com.nineyin.game;

import com.nineyin.protocol.SkillPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 技能学习表（share/skill/skill_init.ini，GBK；节名 = 技能 ConfigID）。
 * 参考服证据串：`loaded skill_init.ini skills=%d path=%s`、
 * `skill_init.ini unavailable (%s): %v; using built-in starter set (%d)`、
 * `skill_init.ini empty (%s); using built-in starter set`——
 * 学习集默认取该文件（当前数据全量 1.7 万+，私服"全学"形态），
 * 文件缺失/为空时回退内置起始集（View 40 的 23 技）。
 * 每节字段：staticData/itemType/level/maxlevel/total/pauseTime/coolDown/consumeSP。
 */
public final class SkillInitTable {

    private static final Logger log = LoggerFactory.getLogger(SkillInitTable.class);
    private static final Charset GBK = Charset.forName("GBK");

    /** 一条已学习技能（level 为学习等级，coolDown 为毫秒显示值，totalFill 为修炼总量显示值）。 */
    public record LearnedSkill(String configId, int level, int staticData,
                               int itemType, int maxLevel, int coolDownMs, int totalFill) {
    }

    private final Map<String, LearnedSkill> skills = new LinkedHashMap<>();
    private Path source;
    private boolean fallbackToBuiltIn;

    /** 解析 skill_init.ini；不可用/为空回退内置起始集（日志与参考服同构）。 */
    public void load(Path skillInitIni) {
        if (!Files.isRegularFile(skillInitIni)) {
            fallbackToBuiltIn = true;
            seedBuiltIn(skillInitIni);
            log.warn("skill_init.ini unavailable ({}): not a regular file; using built-in starter set ({})",
                    skillInitIni, skills.size());
            return;
        }
        try {
            String data = new String(Files.readAllBytes(skillInitIni), GBK);
            String section = "";
            Map<String, String> values = new LinkedHashMap<>();
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    flush(section, values);
                    section = line.substring(1, line.length() - 1).trim();
                    values.clear();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && !section.isEmpty()) {
                    values.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                            line.substring(eq + 1).trim());
                }
            }
            flush(section, values);
        } catch (IOException | RuntimeException e) {
            skills.clear();
            fallbackToBuiltIn = true;
            seedBuiltIn(skillInitIni);
            log.warn("skill_init.ini unavailable ({}): {}; using built-in starter set ({})",
                    skillInitIni, e.toString(), skills.size());
            return;
        }
        if (skills.isEmpty()) {
            fallbackToBuiltIn = true;
            seedBuiltIn(skillInitIni);
            log.warn("skill_init.ini empty ({}); using built-in starter set", skillInitIni);
            return;
        }
        source = skillInitIni;
        log.info("loaded skill_init.ini skills={} path={}", skills.size(), skillInitIni);
    }

    private void flush(String section, Map<String, String> values) {
        if (section.isEmpty()) {
            return;
        }
        int level = parseInt(values.get("level"), 1);
        if (level < 1) {
            level = 1;
        }
        skills.put(section, new LearnedSkill(section, level,
                parseInt(values.get("staticdata"), 0),
                parseInt(values.get("itemtype"), 0),
                parseInt(values.get("maxlevel"), 1),
                parseInt(values.get("cooldown"), 0),
                parseInt(values.get("total"), 0)));
    }

    private void seedBuiltIn(Path skillInitIni) {
        source = skillInitIni;
        for (SkillPackets.StarterSkill sk : SkillPackets.STARTER_SKILLS) {
            skills.put(sk.configId(), new LearnedSkill(sk.configId(), 1, sk.staticData(),
                    sk.itemType(), sk.maxLevel(), sk.cooldownMs(), 0));
        }
    }

    /** 学习等级（>0 已学习；0 未学习）。 */
    public int level(String configId) {
        LearnedSkill s = configId == null ? null : skills.get(configId);
        return s == null ? 0 : s.level();
    }

    public LearnedSkill lookup(String configId) {
        return configId == null ? null : skills.get(configId);
    }

    /** 全量学习集（保持文件节序——choose 阶段目录整推的行序来源）。 */
    public java.util.Collection<LearnedSkill> all() {
        return java.util.Collections.unmodifiableCollection(skills.values());
    }

    public int size() {
        return skills.size();
    }

    /** 是否回退到了内置起始集（参考服日志口径：!loaded）。 */
    public boolean usingBuiltInFallback() {
        return fallbackToBuiltIn;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
