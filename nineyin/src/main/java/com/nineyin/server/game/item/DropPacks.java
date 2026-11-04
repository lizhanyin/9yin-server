package com.nineyin.server.game.item;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 掉落奖励包（share/drop/drops_mvp.json，MVP 自制权重包；
 * 原版 drop 包体表不在资源内，tool_item 的 DropID 引用以此承接）。
 *
 * <p>结构：{@code packages.{DropId} = [{item, min, max, weight}, ...]}；
 * 每次 roll 按权重抽 1 条，数量在 min..max 均匀取。随机数由调用方注入
 * {@link DoubleSupplier}（Java 预掷，测试可确定化）。
 */
public final class DropPacks {

    private static final Logger log = LoggerFactory.getLogger(DropPacks.class);

    /** 单条确定性给物品（roll 合并产物 / 脚本 grant_items 同形）。 */
    public record GrantItem(String configId, int amount) {
    }

    /** 单条掉落（weight ≤0 视为无效跳过）。 */
    public record Entry(String item, int min, int max, int weight) {
    }

    private final Map<String, List<Entry>> packages = new LinkedHashMap<>();

    /**
     * 缺包兜底阶梯（2026-11-04 真机"使用无效果"根因修复）：tool_item.ini 箱类 DropID
     * 引用共 ~16.9k 包不在 drops_mvp.json（官方包体从未随资源发布），缺包 roll 返回空
     * 会让开包静默无动作（不消耗零反馈）。兜底形制对齐 qy_chengdu 系 authored 补齐
     * （yaopin_00100/00300/00500/00700 权重阶梯，箱包偏高档），保证任何箱可开且可追。
     */
    private static final List<Entry> FALLBACK_LADDER = List.of(
            new Entry("yaopin_00100", 1, 2, 50),
            new Entry("yaopin_00300", 1, 2, 30),
            new Entry("yaopin_00500", 1, 1, 15),
            new Entry("yaopin_00700", 1, 1, 5));

    /** 解析 drops_mvp.json；文件缺失保持空目录（开包按 unknown drop 拒绝）。 */
    public static DropPacks load(Path json) {
        DropPacks packs = new DropPacks();
        if (!Files.isRegularFile(json)) {
            log.warn("drop packs unavailable ({}): not a regular file", json);
            return packs;
        }
        Map<?, ?> root = JSON.parse(ResourceCache.text(json, StandardCharsets.UTF_8));
        Object pkg = root == null ? null : root.get("packages");
        if (pkg instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (!(e.getKey() instanceof String id) || !(e.getValue() instanceof List<?> entries)) {
                    continue;
                }
                List<Entry> list = new ArrayList<>();
                for (Object o : entries) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    Object item = m.get("item");
                    Object min = m.get("min");
                    Object max = m.get("max");
                    Object weight = m.get("weight");
                    if (!(item instanceof String s) || s.isBlank()) {
                        continue;
                    }
                    int lo = min instanceof Number n ? n.intValue() : 1;
                    int hi = max instanceof Number n ? n.intValue() : lo;
                    int w = weight instanceof Number n ? n.intValue() : 0;
                    if (w <= 0 || lo < 1 || hi < lo || hi > 999) {
                        continue;
                    }
                    list.add(new Entry(s, lo, hi, w));
                }
                if (!list.isEmpty()) {
                    packs.packages.put(id, List.copyOf(list));
                }
            }
        }
        log.info("drop packs ready: {} packages from {}", packs.packages.size(), json);
        return packs;
    }

    /** 常驻缓存（键加前缀避免与其他消费方互污）。 */
    public static DropPacks loadDefault() {
        Path json = ServerPaths.projectPath("resources", "modern", "share", "drop", "drops_mvp.json");
        return ResourceCache.of("drop-packs:" + json, () -> load(json));
    }

    public int size() {
        return packages.size();
    }

    public boolean has(String dropId) {
        return packages.containsKey(dropId);
    }

    /**
     * 权重抽取：rolls 次独立抽取，同物品数量合并。
     *
     * @param rand 每次调用返回 [0,1)：第一次决定抽中条目，第二次决定数量
     */
    public List<GrantItem> roll(String dropId, int rolls, DoubleSupplier rand) {
        List<Entry> entries = packages.get(dropId);
        List<GrantItem> out = new ArrayList<>();
        if (entries == null) {
            if (dropId == null || dropId.isBlank()) {
                return out;
            }
            // 缺包兜底：按 FALLBACK_LADDER 开（日志可追），不再静默空返。
            log.info("drop pack {} missing, rolling fallback ladder", dropId);
            entries = FALLBACK_LADDER;
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(1, Math.min(10, rolls)); i++) {
            Entry picked = pick(entries, rand.getAsDouble());
            if (picked == null) {
                continue;
            }
            double r = rand.getAsDouble();
            int amount = picked.min() + (int) Math.floor(r * (picked.max() - picked.min() + 1));
            amount = Math.max(picked.min(), Math.min(picked.max(), amount));
            merged.merge(picked.item(), amount, Integer::sum);
        }
        merged.forEach((item, amount) -> out.add(new GrantItem(item, amount)));
        return out;
    }

    private static Entry pick(List<Entry> entries, double r) {
        int total = 0;
        for (Entry e : entries) {
            total += e.weight();
        }
        if (total <= 0) {
            return null;
        }
        int cursor = (int) Math.floor(r * total);
        for (Entry e : entries) {
            cursor -= e.weight();
            if (cursor < 0) {
                return e;
            }
        }
        return entries.get(entries.size() - 1);
    }

    /** 极简 JSON 解析（仅支撑 drops_mvp.json 的对象/数组/字符串/数字字面量；无依赖）。 */
    static final class JSON {

        private final String src;
        private int pos;

        private JSON(String src) {
            this.src = src;
        }

        static Map<String, Object> parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            JSON j = new JSON(text);
            j.ws();
            Object v = j.value();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = v instanceof Map ? (Map<String, Object>) v : null;
            return map;
        }

        private Object value() {
            if (pos >= src.length()) {
                return null;
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{' -> {
                    return object();
                }
                case '[' -> {
                    return array();
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    expect("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    expect("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    expect("null");
                    return null;
                }
                default -> {
                    return number();
                }
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;   // {
            ws();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (pos < src.length()) {
                ws();
                String key = string();
                ws();
                if (pos < src.length() && src.charAt(pos) == ':') {
                    pos++;
                }
                ws();
                out.put(key, value());
                ws();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                break;
            }
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
            }
            return out;
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            pos++;   // [
            ws();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (pos < src.length()) {
                ws();
                out.add(value());
                ws();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                break;
            }
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
            }
            return out;
        }

        private String string() {
            if (pos >= src.length() || src.charAt(pos) != '"') {
                return "";
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && pos < src.length()) {
                    char e = src.charAt(pos++);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'u' -> {
                            if (pos + 4 <= src.length()) {
                                yield (char) Integer.parseInt(src.substring(pos, pos + 4), 16);
                            }
                            pos = src.length();
                            yield '\0';
                        }
                        default -> e;
                    });
                    if (e == 'u') {
                        pos += 4;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number number() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String s = src.substring(start, pos);
            try {
                if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
                    return Double.parseDouble(s);
                }
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private void expect(String token) {
            if (src.startsWith(token, pos)) {
                pos += token.length();
            }
        }

        private void ws() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
