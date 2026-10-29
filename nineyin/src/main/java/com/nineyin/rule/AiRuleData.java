package com.nineyin.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 事件规则数据（rule/ai_editor_package.ini、rule/ai_editor_event.ini、
 * rule/condition.ini、rule/condition_formula.ini，GBK ini 节格式）。
 *
 * 事件四元组：EventType（1=出生 3=死亡 4=周期 5=消失 6=战斗中）× OpRate（概率%）
 * × SelfCondition（条件/公式 ID，0=无条件）× OpEvent（2=加buff 19=设属性
 * 25=召唤NPC/宝箱 27/29=喊话 70/71=召唤组/台词组 23/24=技能切换/使用）。
 *
 * 条件语义（数据反证，见 docs/clone003-boss-mechanics.md）：
 * Type=19 Func Para1=8 → 副本难度档（1普通/2困难/3地狱/4修罗）；
 * Type=19 Func Para1=22 → 通关评价档（1/2/3）；Type=1 数值区间（HPRatio/
 * MPRatio/Level 用 min/max）；Type=2 BufferContainer Para1=buff ID。
 */
public final class AiRuleData {

    public static final Charset GBK = Charset.forName("GBK");

    private static final Logger log = LoggerFactory.getLogger(AiRuleData.class);
    private static final Pattern KV = Pattern.compile("^(\\w+)=([^\\r\\n]*)");

    /** AI 包：EventGroup 事件 ID 列表（Text 为策划备注名）。 */
    public record AiPackage(int id, String text, List<Integer> eventIds) {
    }

    /** 单条事件（未命中字段保持 0/空）。 */
    public record AiEvent(int id, int eventType, int targetType, int opRate,
                          int selfCondition, int opType, int opEvent,
                          String para1, String para2, String para3) {
    }

    /** 数值/函数条件；formula 为 null 表示普通条件。 */
    public record AiCondition(int id, int type, String name, String para1, String para2,
                              Integer min, Integer max, boolean not) {
    }

    /** 公式：token 交替为条件 ID 与 & / |（无优先级，从左到右）。 */
    public record AiFormula(int id, List<String> tokens) {
    }

    private final Map<Integer, AiPackage> packages = new HashMap<>();
    private final Map<Integer, AiEvent> events = new HashMap<>();
    private final Map<Integer, AiCondition> conditions = new HashMap<>();
    private final Map<Integer, AiFormula> formulas = new HashMap<>();

    public void load(Path shareRoot) {
        loadPackages(shareRoot.resolve("rule").resolve("ai_editor_package.ini"));
        loadEvents(shareRoot.resolve("rule").resolve("ai_editor_event.ini"));
        loadConditions(shareRoot.resolve("rule").resolve("condition.ini"));
        loadFormulas(shareRoot.resolve("rule").resolve("condition_formula.ini"));
        log.info("ai rules loaded: packages={} events={} conditions={} formulas={}",
                packages.size(), events.size(), conditions.size(), formulas.size());
    }

    public AiPackage pkg(int id) {
        return packages.get(id);
    }

    public AiEvent event(int id) {
        return events.get(id);
    }

    public AiCondition condition(int id) {
        return conditions.get(id);
    }

    public AiFormula formula(int id) {
        return formulas.get(id);
    }

    public int packageCount() {
        return packages.size();
    }

    public int eventCount() {
        return events.size();
    }

    public int conditionCount() {
        return conditions.size();
    }

    public int formulaCount() {
        return formulas.size();
    }

    /** 按备注名精确/包含匹配包（"暮色之村韩似锦（新）"等）。 */
    public AiPackage packageByText(String text, boolean exact) {
        for (AiPackage p : packages.values()) {
            if (exact ? p.text().equals(text) : p.text().contains(text)) {
                return p;
            }
        }
        return null;
    }

    private void loadPackages(Path file) {
        for (Map<String, String> sec : sections(file)) {
            int id = intOr(sec.get(""), 0);
            if (id == 0) {
                continue;
            }
            List<Integer> ids = new ArrayList<>();
            for (String part : sec.getOrDefault("EventGroup", "").split(",")) {
                int v = intOr(part.trim(), 0);
                if (v > 0) {
                    ids.add(v);
                }
            }
            packages.put(id, new AiPackage(id, sec.getOrDefault("Text", ""), ids));
        }
    }

    private void loadEvents(Path file) {
        for (Map<String, String> sec : sections(file)) {
            int id = intOr(sec.get(""), 0);
            if (id == 0) {
                continue;
            }
            events.put(id, new AiEvent(id,
                    intOr(sec.get("EventType"), 0),
                    intOr(sec.get("TargetType"), 0),
                    intOr(sec.get("OpRate"), 100),
                    intOr(sec.get("SelfCondition"), 0),
                    intOr(sec.get("OpType"), 0),
                    intOr(sec.get("OpEvent"), 0),
                    sec.getOrDefault("Para1", ""),
                    sec.getOrDefault("Para2", ""),
                    sec.getOrDefault("Para3", "")));
        }
    }

    private void loadConditions(Path file) {
        for (Map<String, String> sec : sections(file)) {
            int id = intOr(sec.get(""), 0);
            if (id == 0) {
                continue;
            }
            conditions.put(id, new AiCondition(id,
                    intOr(sec.get("Type"), 0),
                    sec.getOrDefault("Name", ""),
                    sec.getOrDefault("Para1", ""),
                    sec.getOrDefault("Para2", ""),
                    intOrNull(sec.get("min")),
                    intOrNull(sec.get("max")),
                    intOr(sec.get("Not"), 0) != 0));
        }
    }

    private void loadFormulas(Path file) {
        for (Map<String, String> sec : sections(file)) {
            int id = intOr(sec.get(""), 0);
            if (id == 0) {
                continue;
            }
            List<String> tokens = new ArrayList<>();
            Matcher m = Pattern.compile("(\\d+)|([&|])").matcher(sec.getOrDefault("Formula", ""));
            while (m.find()) {
                tokens.add(m.group());
            }
            formulas.put(id, new AiFormula(id, tokens));
        }
    }

    /** GBK ini 节解析：每节一个 LinkedHashMap，节头 ID 放 "" 键（BuffCatalog 复用）。 */
    static List<Map<String, String>> sections(Path file) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            log.warn("ai rule file missing: {}", file);
            return out;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), GBK);
        } catch (IOException e) {
            log.warn("ai rule read failed: {}", file, e);
            return out;
        }
        Map<String, String> cur = new LinkedHashMap<>();
        cur.put("", "");
        for (String line : text.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '[') {
                int end = line.indexOf(']');
                if (end > 1) {
                    if (!cur.get("").isEmpty()) {
                        out.add(cur);
                    }
                    cur = new LinkedHashMap<>();
                    cur.put("", line.substring(1, end).strip());
                }
                continue;
            }
            Matcher m = KV.matcher(line);
            if (m.find()) {
                // strip 而非 trim：全角空格 U+3000 等空白 trim 不删（GBK 表真实存在）
                cur.put(m.group(1), m.group(2).strip());
            }
        }
        if (!cur.get("").isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static int intOr(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
