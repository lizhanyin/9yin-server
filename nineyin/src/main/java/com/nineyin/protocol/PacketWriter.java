package com.nineyin.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 小端二进制包写入器。字符串编码与 C++ 工程一致：
 * 普通字符串 = u32 长度(含 NUL) + 字节 + 0；宽字符串 = u32 字节数(含 NUL) + UTF-16LE + 0。
 */
public final class PacketWriter {

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);

    public PacketWriter u8(int v) {
        buf.write(v & 0xFF);
        return this;
    }

    public PacketWriter u16(int v) {
        buf.write(v & 0xFF);
        buf.write((v >> 8) & 0xFF);
        return this;
    }

    public PacketWriter u32(long v) {
        buf.write((int) (v & 0xFF));
        buf.write((int) ((v >> 8) & 0xFF));
        buf.write((int) ((v >> 16) & 0xFF));
        buf.write((int) ((v >> 24) & 0xFF));
        return this;
    }

    public PacketWriter i32(int v) {
        return u32(v & 0xFFFFFFFFL);
    }

    public PacketWriter i64(long v) {
        u32(v & 0xFFFFFFFFL);
        u32((v >>> 32) & 0xFFFFFFFFL);
        return this;
    }

    public PacketWriter f32(float v) {
        return u32(Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    public PacketWriter f64(double v) {
        return i64(Double.doubleToRawLongBits(v));
    }

    public PacketWriter raw(byte[] bytes) {
        buf.write(bytes, 0, bytes.length);
        return this;
    }

    /** 普通字符串（窄串，客户端按 GBK 解释）：u32 长度(含 NUL) + GBK 字节 + 0。ASCII 与 UTF-8 字节一致。 */
    public PacketWriter string(String s) {
        byte[] bytes = FxText.narrow(s);
        u32(bytes.length + 1);
        raw(bytes);
        u8(0);
        return this;
    }

    /** 宽字符串（角色名等）：u32 字节数(含 NUL) + UTF-16LE + 0x0000。 */
    public PacketWriter wideString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
        u32(bytes.length + 2);
        raw(bytes);
        u8(0);
        u8(0);
        return this;
    }

    public int size() {
        return buf.size();
    }

    public byte[] toByteArray() {
        return buf.toByteArray();
    }
}
