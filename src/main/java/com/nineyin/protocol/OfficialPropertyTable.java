package com.nineyin.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 官服 2424 条属性表（data/official_property_table.txt：index\tName\ttype）。
 * 加载后缓存编码好的 0x09 ServerPropertyTable 包，进场景链路直接下发。
 */
public final class OfficialPropertyTable {

    private static volatile byte[] cachedPacket;

    private OfficialPropertyTable() {
    }

    /** 返回完整 0x09 属性表包（懒加载、线程安全）。 */
    public static byte[] packet() {
        byte[] packet = cachedPacket;
        if (packet == null) {
            synchronized (OfficialPropertyTable.class) {
                if (cachedPacket == null) {
                    cachedPacket = encode(load());
                }
                packet = cachedPacket;
            }
        }
        return packet;
    }

    public record Entry(int index, String name, int type) {
    }

    public static List<Entry> load() {
        List<Entry> entries = new ArrayList<>(2500);
        try (InputStream in = OfficialPropertyTable.class.getResourceAsStream(
                "/nineyin/protocol/official_property_table.txt")) {
            if (in == null) {
                throw new IllegalStateException("official property table resource missing");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 3) {
                    continue;
                }
                entries.add(new Entry(Integer.parseInt(parts[0]), parts[1],
                        Integer.parseInt(parts[2])));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load official property table", e);
        }
        return entries;
    }

    /** [0]=0x09 u16 条数，随后每条：name\0 + 类型字节。 */
    static byte[] encode(List<Entry> entries) {
        PacketWriter w = new PacketWriter().u8(0x09).u16(entries.size());
        for (Entry e : entries) {
            w.raw(e.name().getBytes(StandardCharsets.US_ASCII)).u8(0).u8(e.type());
        }
        return w.toByteArray();
    }
}
