package com.nineyin.game;

import com.nineyin.script.ItemScriptBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

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

    /** 单条掉落（weight ≤0 视为无效跳过）。 */
    public record Entry(String item, int min, int max, int weight) {
    }

    private final Map<String, List<Entry>> packages = new LinkedHashMap<>();

    /** 解析 drops_mvp.json；文件缺失保持空目录（开包按 unknown drop 拒绝）。 */
    public static DropPacks load(Path json) {
        DropPacks packs = new DropPacks();
        if (!Files.isRegularFile(json)) {
            log.warn("drop packs unavailable ({}): not a regular file", json);
            return packs;
        }
        try {
            Map<?, ?> root = JSON.read(json);
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
        } catch (IOException e) {
            log.warn("drop packs unavailable ({}): {}", json, e.getMessage());
        }
        log.info("drop packs ready: {} packages from {}", packs.packages.size(), json);
        return packs;
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
    public List<ItemScriptBridge.GrantItem> roll(String dropId, int rolls, DoubleSupplier rand) {
        List<Entry> entries = packages.get(dropId);
        List<ItemScriptBridge.GrantItem> out = new ArrayList<>();
        if (entries == null) {
            return out;
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
        merged.forEach((item, amount) -> out.add(new ItemScriptBridge.GrantItem(item, amount)));
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

    /** 极简 JSON 读取（仅支撑 drops_mvp.json 的对象/数组/字符串/数字字面量；无依赖）。 */
    static final class JSON {

        private final String src;
        private int pos;

        private JSON(String src) {
            this.src = src;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> read(Path file) throws IOException {
            String text = Files.readString(file);
            JSON j = new JSON(text);
            j.ws();
            Object v = j.value();
            return v instanceof Map ? (Map<String, Object>) v : null;
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
