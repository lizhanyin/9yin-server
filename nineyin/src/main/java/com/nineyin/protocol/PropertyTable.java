package com.nineyin.protocol;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方 2424 项协商属性表（三期切换），数据源
 * resources/nineyin/protocol/official_property_table.txt
 * （`index\tName\ttype`，type：1=byte 2=u16 3=i32 4=f64 5=f32 7=窄字符串 8=宽字符串 9=object）。
 *
 * <p>0x09 帧 verbatim 透传整表（bak 真机验证过的做法，含 None/未知码行）；
 * 客户端按"服务器声明的 名字→索引+线型"解码。编码侧只引用线型可映射的名字——
 * Go 复刻方言里官方表没有的名字（PosiX/Orient/BufferListStr/CantUseSkill 等）
 * 一律不进属性帧：位置走 0x0D 头 + 0x1F，Buff 走 BufferInfo 槽串。
 */
public final class PropertyTable {

    public record Entry(int index, String name, int typeCode) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Entry> BY_NAME = new HashMap<>();
    private static final Map<String, WireType> NAME_TYPES = new HashMap<>();

    static {
        try (InputStream in = PropertyTable.class.getResourceAsStream(
                "/nineyin/protocol/official_property_table.txt")) {
            if (in == null) {
                throw new IllegalStateException("official_property_table.txt missing on classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 3) {
                        continue;
                    }
                    // verbatim 透传（bak 真机验证）：帧内容 = 官方文件逐行，不做删改。
                    ENTRIES.add(new Entry(Integer.parseInt(parts[0].trim()), parts[1].trim(),
                            Integer.parseInt(parts[2].trim())));
                }
            }
            for (Entry entry : ENTRIES) {
                WireType type = wireTypeOf(entry.typeCode());
                if (type != null && !entry.name().equals("None") && entry.index() != 0) {
                    BY_NAME.putIfAbsent(entry.name(), entry);
                    NAME_TYPES.putIfAbsent(entry.name(), type);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("load official_property_table.txt: " + e.getMessage(), e);
        }
    }

    /** 线型码→WireType；None(0)/未知(6) 不参与编码。 */
    static WireType wireTypeOf(int code) {
        return switch (code) {
            case 1 -> WireType.BYTE;
            case 2 -> WireType.WORD;
            case 3 -> WireType.INT32;
            case 4 -> WireType.FLOAT64;
            case 5 -> WireType.FLOAT32;
            case 7 -> WireType.STRING;
            case 8 -> WireType.WIDE_STRING;
            case 9 -> WireType.OBJECT;
            default -> null;
        };
    }

    /** 全部声明（文件顺序 = 0x09 帧下发顺序）。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry byName(String name) {
        return BY_NAME.get(name);
    }

    /** 该名字的可编码线型（缺名/None/未知码 → null）。 */
    public static WireType wireType(String name) {
        return NAME_TYPES.get(name);
    }

    /** 未知名直接抛错：编码期暴露而非线上丢帧。 */
    public static Entry require(String name) {
        Entry entry = BY_NAME.get(name);
        if (entry == null) {
            throw new IllegalStateException("property name absent from official table: " + name);
        }
        return entry;
    }

    public static int idx(String name) {
        return require(name).index();
    }

    public static WireType type(String name) {
        WireType type = NAME_TYPES.get(name);
        if (type == null) {
            throw new IllegalStateException("property name not encodable in official table: " + name);
        }
        return type;
    }

    /** 0x09 协商表帧：`0x09 | u16 count | (C名 + NUL + u8 typeCode)*`，整表透传。 */
    public static byte[] propertyTableFrame() {
        PacketWriter writer = PacketWriter.ofCapacity(3 + ENTRIES.size() * 12);
        writer.writeU8(0x09).writeU16(ENTRIES.size());
        for (Entry entry : ENTRIES) {
            writer.writeUtf8(entry.name()).writeU8(0).writeU8(entry.typeCode());
        }
        return writer.toBytes();
    }

    private PropertyTable() {
    }
}
