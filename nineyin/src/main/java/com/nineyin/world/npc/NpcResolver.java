package com.nineyin.world.npc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nineyin.protocol.NpcSchema;
import com.nineyin.protocol.PropertyValues;
import com.nineyin.protocol.PropertyValues.Value;
import com.nineyin.protocol.WireType;

/**
 * 模板行 + creator 实例 → 可见 NPC 属性归档，逐行移植 Go clientdata.ResolveNPC
 * （defaults → 模板列 → creator 属性 → 摆放坐标；Go 的 provenance 不上线，略）。
 */
public final class NpcResolver {

    /** Go ResolvedNPC：按稳定属性名索引的值表 + 非消费字段扩展。 */
    public static final class ResolvedNpc {
        public final String instanceKey;
        public final String configId;
        public final String scriptClass;
        public final NpcCreator.Transform transform;
        public final Map<String, Value> properties;
        public final Map<String, String> extensions;

        public ResolvedNpc(String instanceKey, String configId, String scriptClass,
                           NpcCreator.Transform transform) {
            this.instanceKey = instanceKey;
            this.configId = configId;
            this.scriptClass = scriptClass;
            this.transform = transform;
            this.properties = new HashMap<>();
            this.extensions = new HashMap<>();
        }

        /** Go OrderedProperties：按 schema 顺序输出并校验线型一致。 */
        public List<PropertyValues.Indexed> orderedProperties(List<NpcSchema.FieldSpec> schema) {
            List<PropertyValues.Indexed> result = new java.util.ArrayList<>(schema.size());
            for (NpcSchema.FieldSpec field : schema) {
                Value value = properties.get(field.name());
                if (value == null) {
                    continue;
                }
                if (value.type() != field.type()) {
                    throw new IllegalStateException("resolved NPC property " + field.name()
                            + " type=" + value.type() + ", schema wants " + field.type());
                }
                result.add(PropertyValues.Indexed.of(field.index(), field.name(), value));
            }
            return result;
        }
    }

    /** Go npcTemplateAliases：列名审阅绑定（重复列名绝不猜首末）。 */
    private static final Map<String, String[]> NPC_TEMPLATE_ALIASES = Map.of(
            "ConfigID", new String[]{"ID", "0"},
            "Sex", new String[]{"Gender", "0"},
            // 战斗 StopTime 是第二次出现的同名列（城市表只有一次 → 取唯一一次）。
            "StopTime", new String[]{"StopTime", "1"});

    private static final java.util.Set<String> CREATOR_STRUCTURAL_ATTRIBUTES = java.util.Set.of(
            "No", "id", "x", "y", "z", "ax", "ay", "az", "sx", "sy", "sz");

    public static ResolvedNpc resolve(List<NpcSchema.FieldSpec> schema, NpcTemplateTable table,
                                      NpcCreator.Instance instance, Map<String, Value> defaults) {
        if (schema.isEmpty()) {
            throw new IllegalStateException("resolve NPC " + instance.templateId + " with empty schema");
        }
        NpcTemplateTable.Row row = table.lookup(instance.templateId);
        ResolvedNpc resolved = new ResolvedNpc(instance.instanceKey(), instance.templateId,
                row.scriptClass, instance.transform);
        applyLayer(schema, resolved.properties, defaults, "default", schema.toString());

        Map<Integer, Boolean> consumedTemplate = new HashMap<>();
        for (NpcSchema.FieldSpec field : schema) {
            String[] alias = NPC_TEMPLATE_ALIASES.get(field.name());
            boolean explicit = alias != null;
            String columnName = explicit ? alias[0] : field.name();
            List<Integer> indices = table.columnIndices(columnName);
            if (indices.isEmpty()) {
                continue;
            }
            if (!explicit && indices.size() != 1) {
                continue; // 重复列名需审阅绑定，不猜。
            }
            int occurrence = explicit ? Integer.parseInt(alias[1]) : 0;
            if (explicit && "StopTime".equals(columnName) && occurrence == 1 && indices.size() == 1) {
                occurrence = 0;
            }
            if (occurrence < 0 || occurrence >= indices.size()) {
                throw new IllegalStateException("NPC template binding " + columnName
                        + " occurrence " + occurrence + " not present in " + table.source);
            }
            int columnIndex = indices.get(occurrence);
            consumedTemplate.put(columnIndex, true);
            NpcTemplateTable.RawCell cell = row.cellAt(columnIndex);
            if (cell == null || !cell.set()) {
                continue;
            }
            resolved.properties.put(field.name(), parseWireValue(field.type(), cell.raw()));
        }

        java.util.Set<String> consumedCreator = new java.util.HashSet<>();
        for (NpcSchema.FieldSpec field : schema) {
            NpcCreator.RawValue raw = instance.attributes.get(field.name());
            if (raw == null || !raw.present()) {
                continue;
            }
            resolved.properties.put(field.name(), parseWireValue(field.type(), raw.raw()));
            consumedCreator.add(field.name());
        }

        // 摆放坐标是运行时对象状态，不是模板属性。
        Map<String, Value> placement = new LinkedHashMap<>();
        placement.put("PosiX", Value.ofFloat32(instance.transform.x));
        placement.put("PosiY", Value.ofFloat32(instance.transform.y));
        placement.put("PosiZ", Value.ofFloat32(instance.transform.z));
        if (instance.transform.ay != null) {
            placement.put("Orient", Value.ofFloat32(instance.transform.ay));
        }
        applyLayer(schema, resolved.properties, placement, "runtime", instance.source);

        for (int index = 0; index < row.cells.size(); index++) {
            NpcTemplateTable.RawCell cell = row.cellAt(index);
            if (cell == null || !cell.set() || consumedTemplate.containsKey(index)) {
                continue;
            }
            String key = "template." + cell.columnName();
            List<Integer> columnIndices = table.columnIndices(cell.columnName());
            if (columnIndices.size() > 1) {
                key = key + "[" + columnIndices.indexOf(index) + "]";
            }
            resolved.extensions.put(key, cell.raw());
        }
        for (Map.Entry<String, NpcCreator.RawValue> attribute : instance.attributes.entrySet()) {
            if (consumedCreator.contains(attribute.getKey())
                    || CREATOR_STRUCTURAL_ATTRIBUTES.contains(attribute.getKey())) {
                continue;
            }
            resolved.extensions.put("creator." + attribute.getKey(), attribute.getValue().raw());
        }
        return resolved;
    }

    /** Go applyValueLayer：值必须在 schema 内且线型一致（键序确定，无 Wire 差异）。 */
    private static void applyLayer(List<NpcSchema.FieldSpec> schema, Map<String, Value> target,
                                   Map<String, Value> values, String layer, String source) {
        Map<String, NpcSchema.FieldSpec> byName = new HashMap<>();
        for (NpcSchema.FieldSpec field : schema) {
            byName.putIfAbsent(field.name(), field);
        }
        List<String> keys = new java.util.ArrayList<>(values.keySet());
        java.util.Collections.sort(keys);
        for (String name : keys) {
            Value value = values.get(name);
            NpcSchema.FieldSpec field = byName.get(name);
            if (field == null) {
                throw new IllegalStateException("layer " + layer + " sets property " + name
                        + " absent from schema (" + source + ")");
            }
            if (value.type() != field.type()) {
                throw new IllegalStateException("layer " + layer + " property " + name
                        + " type=" + value.type() + ", schema wants " + field.type());
            }
            target.put(name, value);
        }
    }

    /** Go parseWireValue：按线型解析单元格文本（int32 容纳整值浮点写法）。 */
    static Value parseWireValue(WireType type, String raw) {
        return switch (type) {
            case BYTE -> Value.ofByte(parseUint(raw, 8));
            case WORD -> Value.ofWord(parseUint(raw, 16));
            case INT32 -> Value.ofInt32(parseIntegralInt32(raw));
            case FLOAT32 -> {
                float value;
                try {
                    value = Float.parseFloat(raw.trim());
                } catch (NumberFormatException e) {
                    throw new NumberFormatException("not a float32: " + raw);
                }
                if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
                    throw new NumberFormatException("not a finite float32: " + raw);
                }
                yield Value.ofFloat32(value);
            }
            case STRING -> Value.ofString(raw);
            case WIDE_STRING -> Value.ofWideString(raw);
            default -> throw new IllegalStateException("unsupported wire type " + type);
        };
    }

    private static int parseUint(String raw, int bits) {
        long value;
        try {
            value = Long.parseLong(raw.trim(), 10);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("\"" + raw + "\" is not an unsigned " + bits + "-bit integer");
        }
        if (value < 0 || value >= (1L << bits)) {
            throw new NumberFormatException("\"" + raw + "\" is not an unsigned " + bits + "-bit integer");
        }
        return (int) value;
    }

    static int parseIntegralInt32(String raw) {
        String text = raw.trim();
        try {
            return Integer.parseInt(text, 10);
        } catch (NumberFormatException ignored) {
            // Go 先按十进制整型，失败后接受整值浮点写法。
        }
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("\"" + raw + "\" is not an integral int32");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)
                || Math.rint(value) != value
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new NumberFormatException("\"" + raw + "\" is not an integral int32");
        }
        return (int) value;
    }

    /** Go VisibleNPCSpawnDefaultsCompatV1：现代数据/creator 属性仍可覆盖的兼容默认层。 */
    public static Map<String, Value> spawnDefaultsCompatV1() {
        Map<String, Value> defaults = new LinkedHashMap<>();
        defaults.put("Type", Value.ofByte(4));
        defaults.put("Sex", Value.ofByte(0));
        defaults.put("ActionSet", Value.ofString(""));
        defaults.put("State", Value.ofString("stand"));
        defaults.put("LogicState", Value.ofByte(0));
        defaults.put("HPRatio", Value.ofInt32(100));
        defaults.put("MaxHP", Value.ofInt32(1000));
        defaults.put("HP", Value.ofInt32(1000));
        defaults.put("Scale", Value.ofString(""));
        defaults.put("CollideType", Value.ofInt32(0));
        defaults.put("CantShowHeadInfo", Value.ofByte(0));
        defaults.put("NPCCanSelect", Value.ofWord(1));
        defaults.put("RotatePara", Value.ofString(""));
        defaults.put("NeedRotate", Value.ofInt32(0));
        defaults.put("Collide", Value.ofInt32(0));
        defaults.put("CollideRadius", Value.ofFloat32(0.905f));
        defaults.put("CantAttack", Value.ofInt32(0));
        defaults.put("CantBeAttack", Value.ofInt32(0));
        defaults.put("Force", Value.ofInt32(0));
        defaults.put("MotionNoRotate", Value.ofByte(0));
        defaults.put("CanPick", Value.ofByte(0));
        defaults.put("VisType", Value.ofInt32(0));
        defaults.put("VisDataInt", Value.ofInt32(0));
        defaults.put("VisDataStr", Value.ofWideString(""));
        defaults.put("HeadEffect", Value.ofInt32(0));
        defaults.put("NpcNickName", Value.ofWideString(""));
        defaults.put("ExtraInfo", Value.ofString(""));
        defaults.put("RoleHide", Value.ofInt32(0));
        defaults.put("EscortFlag", Value.ofInt32(0));
        defaults.put("SpringEffect", Value.ofString(""));
        defaults.put("Material", Value.ofString(""));
        defaults.put("WeaponName", Value.ofString(""));
        defaults.put("BornAction", Value.ofString(""));
        defaults.put("ActionSoundMode", Value.ofInt32(0));
        defaults.put("Fixed", Value.ofInt32(0));
        defaults.put("FixedBeHitAct", Value.ofString(""));
        defaults.put("DirectSpring", Value.ofInt32(0));
        defaults.put("NpcTalkType", Value.ofInt32(0));
        defaults.put("PreLoad", Value.ofInt32(0));
        defaults.put("OnlyCareDistance", Value.ofInt32(0));
        defaults.put("RefreshAtOnce", Value.ofInt32(0));
        return defaults;
    }

    private NpcResolver() {
    }
}
