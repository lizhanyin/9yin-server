package com.nineyin.server.protocol;

import com.nineyin.server.protocol.PropertyValues.Indexed;
import com.nineyin.server.protocol.PropertyValues.Value;

/**
 * 官方属性表方言的取值入口：一切属性帧按「名字」写入，索引/线型由
 * {@link PropertyTable} 决定，值按表线型自动适配（Go 方言的 int32 Level →
 * 官方 byte Level 等）。类别冲突按桥接规则转换：空串→0，可解析串→数，
 * 数→十进制文本。名字不在官方表（或线型码不可编码）→ 静默跳过——
 * 语义由 0x0D 头、0x1F、BufferInfo 槽等其它通道承担。
 */
public final class Props {

    /** 名字不在官方表 → null（调用方跳过）。 */
    public static Indexed indexedOrNull(String name, Value value) {
        WireType type = PropertyTable.wireType(name);
        if (type == null) {
            return null;
        }
        return Indexed.of(PropertyTable.idx(name), name, coerce(name, type, value));
    }

    /** 场景对象属性（0x10/0x0D 归档行）：缺名跳过，返回是否已写入。 */
    public static boolean append(PacketWriter writer, String name, Value value) {
        Indexed indexed = indexedOrNull(name, value);
        if (indexed == null) {
            return false;
        }
        writer.appendIndexed(indexed);
        return true;
    }

    /** 场景对象属性列表版：缺名跳过。 */
    public static void add(java.util.List<Indexed> properties, String name, Value value) {
        Indexed indexed = indexedOrNull(name, value);
        if (indexed != null) {
            properties.add(indexed);
        }
    }

    /** 视图行属性（0x15/0x18）：缺名跳过。 */
    public static SceneMessages.ViewProperty view(String name, Value value) {
        WireType type = PropertyTable.wireType(name);
        if (type == null) {
            return null;
        }
        int index = PropertyTable.idx(name);
        Value coerced = coerce(name, type, value);
        return switch (type) {
            case BYTE -> SceneMessages.ViewProperty.ofByte(index, coerced.u8());
            case WORD -> SceneMessages.ViewProperty.ofWord(index, coerced.u16());
            case INT32 -> SceneMessages.ViewProperty.ofInt(index, coerced.i32());
            case FLOAT32 -> SceneMessages.ViewProperty.ofFloat(index, coerced.f32());
            case STRING, WIDE_STRING -> SceneMessages.ViewProperty.ofString(index,
                    coerced.text() == null ? "" : coerced.text());
            default -> throw new IllegalStateException(
                    "view property " + name + " has unsupported official type " + type);
        };
    }

    /** 视图行列表版：缺名/不支持线型跳过。 */
    public static void addView(java.util.List<SceneMessages.ViewProperty> properties,
                               String name, Value value) {
        SceneMessages.ViewProperty property = view(name, value);
        if (property != null) {
            properties.add(property);
        }
    }

    // ---- 便捷重载：数字/文本按表线型收窄或转换 ----

    public static Value number(String name, long value) {
        return coerce(name, PropertyTable.type(name), Value.ofInt32((int) value));
    }

    public static Value decimal(String name, double value) {
        WireType type = PropertyTable.type(name);
        return switch (type) {
            case FLOAT32 -> Value.ofFloat32((float) value);
            case FLOAT64 -> Value.ofFloat64(value);
            default -> coerce(name, type, Value.ofInt32((int) value));
        };
    }

    public static Value text(String name, String value) {
        WireType type = PropertyTable.type(name);
        return switch (type) {
            case STRING -> Value.ofString(value);
            case WIDE_STRING -> Value.ofWideString(value);
            default -> throw new IllegalStateException("property " + name + " wants " + type + ", got text");
        };
    }

    /** 线型适配：同类别内转换；类别冲突按桥接规则（空串↔0、可解析串↔数、数→文本）。 */
    public static Value coerce(String name, WireType want, Value value) {
        if (value.type() == want) {
            return value;
        }
        return switch (want) {
            case BYTE -> Value.ofByte(narrowInt(name, want, value));
            case WORD -> Value.ofWord(narrowInt(name, want, value));
            case INT32 -> Value.ofInt32(narrowInt(name, want, value));
            case FLOAT32 -> Value.ofFloat32((float) numeric(name, value));
            case FLOAT64 -> Value.ofFloat64(numeric(name, value));
            case STRING -> Value.ofString(textOf(name, value));
            case WIDE_STRING -> Value.ofWideString(textOf(name, value));
            case OBJECT -> {
                if (value.type() == WireType.OBJECT) {
                    yield value;
                }
                throw new IllegalStateException("property " + name + " wants object");
            }
        };
    }

    private static int narrowInt(String name, WireType want, Value value) {
        long raw = Math.round(numeric(name, value));
        if (want == WireType.BYTE && (raw < 0 || raw > 255)
                || want == WireType.WORD && (raw < 0 || raw > 65535)) {
            throw new IllegalStateException("property " + name + " value " + raw + " overflows " + want);
        }
        return (int) raw;
    }

    /** 数值视角：数值族直取；串空→0，可解析→值。 */
    private static double numeric(String name, Value value) {
        return switch (value.type()) {
            case BYTE -> value.u8();
            case WORD -> value.u16();
            case INT32 -> value.i32();
            case FLOAT32 -> value.f32();
            case FLOAT64 -> Double.longBitsToDouble(value.u64());
            case STRING, WIDE_STRING -> {
                String text = value.text() == null ? "" : value.text().trim();
                if (text.isEmpty()) {
                    yield 0;
                }
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("property " + name
                            + " wants number, got text \"" + value.text() + "\"");
                }
            }
            default -> throw new IllegalStateException("property " + name + " wants number, got " + value.type());
        };
    }

    private static String textOf(String name, Value value) {
        return switch (value.type()) {
            case STRING, WIDE_STRING -> value.text() == null ? "" : value.text();
            case BYTE -> String.valueOf(value.u8());
            case WORD -> String.valueOf(value.u16());
            case INT32 -> String.valueOf(value.i32());
            case FLOAT32 -> String.valueOf(value.f32());
            case FLOAT64 -> String.valueOf(Double.longBitsToDouble(value.u64()));
            default -> throw new IllegalStateException("property " + name + " wants text, got " + value.type());
        };
    }

    private Props() {
    }
}
