package com.nineyin.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 小端帧构造器。属性编码对齐 Go cmd/protocol-probe 的 append*Property 家族：
 * 窄字符串 = u32(字节数+1) + UTF-8 + NUL；宽字符串 = u32((unit 数+1)*2) + UTF-16LE + 0x0000；
 * object = u64(objectID | ownerID&lt;&lt;32)。
 */
public final class PacketWriter {

    private byte[] buffer = new byte[64];
    private int length;

    public static PacketWriter ofCapacity(int capacity) {
        PacketWriter writer = new PacketWriter();
        writer.buffer = new byte[capacity];
        return writer;
    }

    public int length() {
        return length;
    }

    private void ensure(int extra) {
        if (length + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + extra));
        }
    }

    public PacketWriter writeU8(int value) {
        ensure(1);
        buffer[length++] = (byte) value;
        return this;
    }

    public PacketWriter writeU16(int value) {
        ensure(2);
        buffer[length++] = (byte) value;
        buffer[length++] = (byte) (value >>> 8);
        return this;
    }

    public PacketWriter writeU32(long value) {
        ensure(4);
        buffer[length++] = (byte) value;
        buffer[length++] = (byte) (value >>> 8);
        buffer[length++] = (byte) (value >>> 16);
        buffer[length++] = (byte) (value >>> 24);
        return this;
    }

    public PacketWriter writeU64(long value) {
        writeU32(value & 0xFFFFFFFFL);
        writeU32(value >>> 32);
        return this;
    }

    public PacketWriter writeF32(float value) {
        return writeU32(Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
    }

    public PacketWriter writeF64(double value) {
        return writeU64(Double.doubleToRawLongBits(value));
    }

    public PacketWriter writeBytes(byte[] data) {
        ensure(data.length);
        System.arraycopy(data, 0, buffer, length, data.length);
        length += data.length;
        return this;
    }

    public PacketWriter writeUtf8(String value) {
        return writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 回填 u16（例如 0x0D 的属性数写在首部 0x3d）。 */
    public void patchU16(int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
    }

    public void patchU32(int offset, long value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    // ------------------------------------------------------------ 属性 append

    /** Go main.go appendStringProperty。 */
    public PacketWriter appendStringProperty(int index, String value) {
        writeU16(index);
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        writeU32(data.length + 1);
        writeBytes(data);
        return writeU8(0);
    }

    /** Go main.go appendWideStringProperty。 */
    public PacketWriter appendWideStringProperty(int index, String value) {
        writeU16(index);
        writeU32((value.length() + 1) * 2L);
        writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
        return writeU16(0);
    }

    /** Go main.go appendObjectProperty：低 32 位 objectID，高 32 位 ownerID。 */
    public PacketWriter appendObjectProperty(int index, long objectId, long ownerId) {
        writeU16(index);
        return writeU64((objectId & 0xFFFFFFFFL) | (ownerId << 32));
    }

    /** Go npc_catalog.go appendNPCProperty：按协商线型编码（无类型字节）。 */
    public PacketWriter appendIndexed(PropertyValues.Indexed property) {
        PropertyValues.Value value = property.value();
        writeU16(property.int16Index());
        switch (value.type()) {
            case BYTE:
                return writeU8(value.u8());
            case WORD:
                return writeU16(value.u16());
            case INT32:
                return writeU32(value.i32() & 0xFFFFFFFFL);
            case FLOAT32:
                return writeF32(value.f32());
            case FLOAT64:
                // f64 位存于 u64 字段（官方表 type=4，如 CapitalType1 碎银）
                return writeU64(value.u64());
            case STRING:
                byte[] data = (value.text() == null ? "" : value.text()).getBytes(StandardCharsets.UTF_8);
                writeU32(data.length + 1);
                writeBytes(data);
                return writeU8(0);
            case WIDE_STRING: {
                String text = value.text() == null ? "" : value.text();
                writeU32((text.length() + 1) * 2L);
                writeBytes(text.getBytes(StandardCharsets.UTF_16LE));
                return writeU16(0);
            }
            case OBJECT:
                return writeU64(value.u64());
            default:
                throw new IllegalStateException("unsupported NPC wire type " + value.type());
        }
    }

    public byte[] toBytes() {
        return Arrays.copyOf(buffer, length);
    }
}
