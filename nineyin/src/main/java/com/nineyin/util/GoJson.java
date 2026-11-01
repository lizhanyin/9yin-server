package com.nineyin.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Go encoding/json 兼容输出（MarshalIndent 两空格风格），供存档文件与 Go 侧逐字节一致。
 *
 * <p>要点：字符串转义规则（控制字符转 u00xx 形式、&lt;/&gt;/&amp; 转义、其余 UTF-8 原文）；
 * float32 最短 round-trip 十进制（普通区间非指数，与 Go json 的 'f' 通道一致）；
 * 手写 writer 控制键序（Go struct 序）与 map 键升序。嵌套值必须在构建时用正确
 * depth 调用 {@code render(depth)} 预渲染。
 */
public final class GoJson {

    private GoJson() {
    }

    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '<':
                    out.append("\\u003c");
                    break;
                case '>':
                    out.append("\\u003e");
                    break;
                case '&':
                    out.append("\\u0026");
                    break;
                case '\u2028':
                    out.append("\\u2028");
                    break;
                case '\u2029':
                    out.append("\\u2029");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /** Go encoding/json float32：abs∈[1e-6,1e21) 用普通小数最短表示，否则 'e' 风格。 */
    public static String formatFloat(float value) {
        if (value == 0) {
            return "0";
        }
        double abs = Math.abs((double) value);
        if (abs >= 1e-6 && abs < 1e21) {
            String s = Float.toString(value);
            if (!s.contains("E")) {
                return s;
            }
            return new BigDecimal(s).stripTrailingZeros().toPlainString();
        }
        // Go 'e' 通道（'g' shortest）：形如 1e+21 / 1e-07。
        BigDecimal decimal = new BigDecimal(Float.toString(value)).stripTrailingZeros();
        String scientific = decimal.toString();
        int e = scientific.indexOf('E');
        if (e < 0) {
            return scientific;
        }
        String mantissa = scientific.substring(0, e);
        if (mantissa.endsWith(".0")) {
            mantissa = mantissa.substring(0, mantissa.length() - 2);
        }
        int exponent = Integer.parseInt(scientific.substring(e + 1));
        return mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }

    /** Go MarshalIndent 对象：{"k": v,…}，字段序由调用方保证（Go struct 序）。 */
    public static final class Object {
        private final List<String> parts = new ArrayList<>();

        public Object field(String key, String value) {
            return raw(key, quote(value));
        }

        public Object field(String key, long value) {
            return raw(key, Long.toString(value));
        }

        public Object field(String key, boolean value) {
            return raw(key, Boolean.toString(value));
        }

        public Object floatField(String key, float value) {
            return raw(key, formatFloat(value));
        }

        public Object raw(String key, String renderedValue) {
            parts.add(quote(key) + ": " + renderedValue);
            return this;
        }

        public String render(int depth) {
            if (parts.isEmpty()) {
                return "{}";
            }
            String indent = "  ".repeat(depth + 1);
            return "{\n" + indent + String.join(",\n" + indent, parts) + "\n" + "  ".repeat(depth) + "}";
        }
    }

    /** Go MarshalIndent 数组渲染。 */
    public static String renderArray(List<String> renderedElements, int depth) {
        if (renderedElements.isEmpty()) {
            return "[]";
        }
        String indent = "  ".repeat(depth + 1);
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < renderedElements.size(); i++) {
            out.append(indent).append(renderedElements.get(i));
            out.append(i == renderedElements.size() - 1 ? "\n" : ",\n");
        }
        return out.append("  ".repeat(depth)).append(']').toString();
    }
}
