package com.nineyin.protocol;

import java.util.ArrayList;
import java.util.List;

import com.nineyin.role.Roles;

/** 选角/进世界阶段的 S2C 帧构造，对齐 Go cmd/protocol-probe 的 messages/main/scene_transition 家族。 */
public final class SceneMessages {

    public static final int PLAYER_OBJECT_ID = 1;
    public static final int PLAYER_OWNER_ID = 1;

    /** Go main.go visiblePropertyTable：0x09 | u16 count | (C 名 + NUL + u8 wireType)*。 */
    public static byte[] visiblePropertyTable(List<NpcSchema.FieldSpec> properties) {
        PacketWriter writer = PacketWriter.ofCapacity(3 + properties.size() * 12);
        writer.writeU8(0x09).writeU16(properties.size());
        for (NpcSchema.FieldSpec property : properties) {
            writer.writeUtf8(property.name()).writeU8(0).writeU8(property.type().value);
        }
        return writer.toBytes();
    }

    /** 记录表 schema，对齐 Go qinggong_records.go recordSchema + qingGongRecordSchemas。 */
    public record RecordSchema(String name, byte[] columnTypes) {
    }

    public static final List<RecordSchema> QING_GONG_RECORD_SCHEMAS = List.of(
            new RecordSchema("QingGongRec", new byte[]{7}),
            new RecordSchema("ActiveQGSkillRec", new byte[]{7}),
            // 七列兼容前缀：条件 23614 只消费 id..status；宽表会让 RecvRecordRow 越界。
            new RecordSchema("Task_Accepted", new byte[]{3, 3, 3, 7, 7, 7, 3}),
            new RecordSchema("Task_Completed", new byte[]{3}),
            // shortcut_game.lua 先查此记录再 custom_set_shortcut(200)。
            new RecordSchema("shortcut_rec", new byte[]{3, 7, 7}),
            // CoolLogicManager 注册原生回调：每行 = StartACool(category, end-start, team, 0)。
            new RecordSchema("cooldown_rec", new byte[]{3, 4, 4, 3}));

    /** Go serverRecordTable：0x0A | u16 count | (C 名 + NUL + u16 列数 + 类型字节)*。 */
    public static byte[] recordTable(List<RecordSchema> schemas) {
        PacketWriter writer = PacketWriter.ofCapacity(128);
        writer.writeU8(0x0A).writeU16(schemas.size());
        for (RecordSchema schema : schemas) {
            writer.writeUtf8(schema.name()).writeU8(0)
                    .writeU16(schema.columnTypes().length).writeBytes(schema.columnTypes());
        }
        return writer.toBytes();
    }

    /** Go serverViewSpec + starterBagViews：View 定义（view_define.lua 的 ID/容量）。 */
    public record ViewSpec(int id, int capacity) {
    }

    public static List<ViewSpec> starterBagViews() {
        return List.of(
                new ViewSpec(121, 132), // VIEWPORT_EQUIP_TOOL
                new ViewSpec(2, 132),   // VIEWPORT_TOOL
                new ViewSpec(123, 132), // VIEWPORT_MATERIAL_TOOL
                new ViewSpec(125, 132), // VIEWPORT_TASK_TOOL
                new ViewSpec(122, 16),  // VIEWPORT_EQUIP_ADDTOOLBOX
                new ViewSpec(3, 16),    // VIEWPORT_ADDTOOLBOX
                new ViewSpec(124, 16),  // VIEWPORT_MATERIAL_ADDTOOLBOX
                new ViewSpec(126, 16)); // VIEWPORT_TASK_ADDTOOLBOX
    }

    /** 视图属性（0x15/0x18 共用）：官方表线型（byte/word/i32/f32/串）。 */
    public static final class ViewProperty {
        final int index;
        final Byte byte1;
        final Integer word;
        final String text;
        final Integer int32;
        final Float real32;

        private ViewProperty(int index, Byte byte1, Integer word, String text, Integer int32, Float real32) {
            this.index = index;
            this.byte1 = byte1;
            this.word = word;
            this.text = text;
            this.int32 = int32;
            this.real32 = real32;
        }

        public static ViewProperty ofByte(int index, int value) {
            return new ViewProperty(index, (byte) value, null, null, null, null);
        }

        public static ViewProperty ofWord(int index, int value) {
            return new ViewProperty(index, null, value, null, null, null);
        }

        public static ViewProperty ofString(int index, String value) {
            return new ViewProperty(index, null, null, value, null, null);
        }

        public static ViewProperty ofInt(int index, int value) {
            return new ViewProperty(index, null, null, null, value, null);
        }

        public static ViewProperty ofFloat(int index, float value) {
            return new ViewProperty(index, null, null, null, null, value);
        }
    }

    /** Go serverCreateView：0x15 | u16 viewID | u16 capacity | u16 propCount | 归档。 */
    public static byte[] createView(ViewSpec spec) {
        return createViewWithProperties(spec, List.of());
    }

    public static byte[] createViewWithProperties(ViewSpec spec, List<ViewProperty> properties) {
        PacketWriter writer = PacketWriter.ofCapacity(16 + properties.size() * 8);
        writer.writeU8(0x15).writeU16(spec.id()).writeU16(spec.capacity()).writeU16(properties.size());
        appendViewProperties(writer, properties);
        return writer.toBytes();
    }

    /** ServerViewRemove（0x19，5 字节）：视图内按 objectIndex 摘除一行。 */
    public static byte[] viewRemove(int viewId, int objectIndex) {
        PacketWriter writer = PacketWriter.ofCapacity(5);
        return writer.writeU8(0x19).writeU16(viewId).writeU16(objectIndex).toBytes();
    }

    /** Go serverViewAdd（0x18）：viewID + objectIndex + 归档。 */
    public static byte[] viewAdd(int viewId, int objectIndex, List<ViewProperty> properties) {
        PacketWriter writer = PacketWriter.ofCapacity(16 + properties.size() * 8);
        writer.writeU8(0x18).writeU16(viewId).writeU16(objectIndex).writeU16(properties.size());
        appendViewProperties(writer, properties);
        return writer.toBytes();
    }

    private static void appendViewProperties(PacketWriter writer, List<ViewProperty> properties) {
        for (ViewProperty property : properties) {
            if (property.byte1 != null) {
                writer.writeU16(property.index).writeU8(property.byte1);
            } else if (property.word != null) {
                writer.writeU16(property.index).writeU16(property.word);
            } else if (property.text != null) {
                byte[] data = property.text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writer.writeU16(property.index).writeU32(data.length + 1)
                        .writeBytes(data).writeU8(0);
            } else if (property.int32 != null) {
                writer.writeU16(property.index).writeU32(property.int32 & 0xFFFFFFFFL);
            } else if (property.real32 != null) {
                writer.writeU16(property.index).writeF32(property.real32);
            } else {
                throw new IllegalArgumentException("view property " + property.index + " has no value");
            }
        }
    }

    /** Go scene_transition.go sendEntryScene 的 0x0B 入场帧（object=1，属性 7/8/99）。 */
    public static byte[] entryScene(Roles.Scene scene) {
        PacketWriter writer = PacketWriter.ofCapacity(96);
        writer.writeU8(0x0B)
                .writeU32(PLAYER_OBJECT_ID).writeU32(PLAYER_OWNER_ID).writeU32(1).writeU16(3);
        Props.append(writer, "ConfigID", PropertyValues.Value.ofString(scene.config()));
        Props.append(writer, "Resource", PropertyValues.Value.ofString(scene.resource()));
        // MaxVisuals 官方语义=最大可见对象数（u16，官方真服发 100；capture-fxgame-op2 0x0B 实证）， 取值范围是 0 到 65535（即 0 到 2¹⁶-1）
        // 原Go服写大了，发1000000超过了65535。
        Props.append(writer, "MaxVisuals", PropertyValues.Value.ofInt32(100));
        return writer.toBytes();
    }

    /** Go encodeExitScene。 */
    public static byte[] exitScene() {
        return new byte[]{0x0C};
    }

    /** Go main.go serverLocation：0x1F + object/owner + Pos 变换（25 字节）。 */
    public static byte[] serverLocation(int objectId, int ownerId, Roles.Position position) {
        PacketWriter writer = PacketWriter.ofCapacity(25);
        writer.writeU8(0x1F).writeU32(objectId).writeU32(ownerId)
                .writeF32(position.x()).writeF32(position.y())
                .writeF32(position.z()).writeF32(position.orient());
        return writer.toBytes();
    }

    /** Go object_property.go sceneObjectProperties：0x10 通用多属性帧（无类型字节）。 */
    public static byte[] objectProperties(int objectId, int ownerId, int isViewObject,
                                          List<PropertyValues.Indexed> properties) {
        PacketWriter writer = PacketWriter.ofCapacity(12 + properties.size() * 8);
        writer.writeU8(0x10).writeU8(isViewObject).writeU32(objectId).writeU32(ownerId)
                .writeU16(properties.size());
        for (PropertyValues.Indexed property : properties) {
            int before = writer.length();
            writer.appendIndexed(property);
            if (writer.length() == before) {
                throw new IllegalStateException("empty encode for property index " + property.int16Index());
            }
        }
        return writer.toBytes();
    }

    /** 碎银单属性帧（GM/starter 用）：官方 CapitalType1 为 f64。 */
    public static byte[] silverUpdate(int objectId, int ownerId, int silver) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "CapitalType1", PropertyValues.Value.ofInt32(silver));
        return objectProperties(objectId, ownerId, 0, properties);
    }

    /** 三期：0x09 协商表 = 官方 2424 项整表。 */
    public static byte[] propertyTableFrame() {
        return PropertyTable.propertyTableFrame();
    }

    public static byte[] recordTableFrame() {
        return recordTable(QING_GONG_RECORD_SCHEMAS);
    }

    private SceneMessages() {
    }
}
