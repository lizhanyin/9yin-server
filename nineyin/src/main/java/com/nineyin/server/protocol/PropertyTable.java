package com.nineyin.server.protocol;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真机 0x09 协商属性表（场景方言，2370 项），数据源
 * resources/nineyin/protocol/official_property_table.txt。
 *
 * <p>来源：F 盘参考服（真实服务端 exe）s2c 捕获里的 0x09 帧逐行提取——15 份捕获
 * 表体完全一致；{@code index} 列 = 帧内位置（0x09 帧只带 名字+线型，客户端按位置
 * 绑定索引），重组帧与捕获帧逐字节一致（tools/gen_scene_table.py 回归校验）。
 * bak 提取的 2424/2426 项表（a1219d9 引入）不是线上方言——其低位是 Home/Ssf/
 * LuanDou 等系统属性、且与真机表的顺序/线型/项数都不同，真机客户端实测内修修速
 * 不动，已退役（见 git 历史）。
 *
 * <p>表内同名多处出现（108 名）：0..98 是 NPC/场景基块，99..170 是商店/运动扩展块，
 * 171.. 是玩家/视图块——同名按「域」解析：
 * <ul>
 *   <li>场景域（{@link #byName}/{@link #idx}/{@link #type}）：首次出现（NPC 帧、
 *       entryScene、NpcResolver）；</li>
 *   <li>玩家/视图域（{@link #playerIdx}/{@link #playerType}）：末次出现
 *       （0x0D/0x10 玩家行、0x15/0x18 视图行——真机帧逐项核对面）。</li>
 * </ul>
 */
public final class PropertyTable {

    public record Entry(int index, String name, int typeCode) {
    }

    /** 场景扩展块（商店/运动）在表中的起始位置：扩展名按 ≥99 首次出现解析。 */
    public static final int SCENE_EXTENSION_FLOOR = 99;

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Entry> SCENE_BY_NAME = new HashMap<>();
    private static final Map<String, WireType> SCENE_TYPES = new HashMap<>();
    private static final Map<String, Entry> PLAYER_BY_NAME = new HashMap<>();
    private static final Map<String, WireType> PLAYER_TYPES = new HashMap<>();

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
                    // verbatim 透传：帧内容 = 表文件逐行，不做删改。
                    ENTRIES.add(new Entry(Integer.parseInt(parts[0].trim()), parts[1].trim(),
                            Integer.parseInt(parts[2].trim())));
                }
            }
            for (Entry entry : ENTRIES) {
                WireType type = wireTypeOf(entry.typeCode());
                if (type == null || entry.name().equals("None")) {
                    continue; // 线型码不可编码（6 等）不参与编码；保险起见跳过 None 名。
                }
                SCENE_BY_NAME.putIfAbsent(entry.name(), entry);
                SCENE_TYPES.putIfAbsent(entry.name(), type);
                PLAYER_BY_NAME.put(entry.name(), entry);
                PLAYER_TYPES.put(entry.name(), type);
            }
        } catch (Exception e) {
            throw new IllegalStateException("load official_property_table.txt: " + e.getMessage(), e);
        }
    }

    /** 线型码→WireType；未知码（0/6）不参与编码。 */
    public static WireType wireTypeOf(int code) {
        return switch (code) {
            case 1 -> WireType.BYTE;
            case 2 -> WireType.WORD;
            case 3 -> WireType.INT32;
            case 4 -> WireType.INT64; // 真机抓包实证：货币/时间戳行均按小端 int64 编码
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

    /** 索引反查（测试/调试）。 */
    public static Entry byIndex(int index) {
        for (Entry entry : ENTRIES) {
            if (entry.index() == index) {
                return entry;
            }
        }
        return null;
    }

    /** 场景域按名首现；{@code minIndex} 抬升起始位（扩展块用 {@link #SCENE_EXTENSION_FLOOR}）。 */
    public static Entry byName(String name, int minIndex) {
        for (Entry entry : ENTRIES) {
            if (entry.index() >= minIndex && entry.name().equals(name)
                    && wireTypeOf(entry.typeCode()) != null) {
                return entry;
            }
        }
        return null;
    }

    public static Entry byName(String name) {
        return byName(name, 0);
    }

    /** 该名字场景域的可编码线型（缺名/不可编码 → null）。 */
    public static WireType wireType(String name) {
        return SCENE_TYPES.get(name);
    }

    /** 未知名直接抛错：编码期暴露而非线上丢帧。 */
    public static Entry require(String name) {
        Entry entry = SCENE_BY_NAME.get(name);
        if (entry == null) {
            throw new IllegalStateException("property name absent from property table: " + name);
        }
        return entry;
    }

    public static int idx(String name) {
        return require(name).index();
    }

    public static WireType type(String name) {
        WireType type = SCENE_TYPES.get(name);
        if (type == null) {
            throw new IllegalStateException("property name not encodable in property table: " + name);
        }
        return type;
    }

    /** 玩家/视图域（末次出现）；0x0D/0x10 玩家行与 0x15/0x18 视图行用。 */
    public static Entry playerByName(String name) {
        return PLAYER_BY_NAME.get(name);
    }

    public static int playerIdx(String name) {
        Entry entry = PLAYER_BY_NAME.get(name);
        if (entry == null) {
            throw new IllegalStateException("property name absent from property table: " + name);
        }
        return entry.index();
    }

    public static WireType playerWireType(String name) {
        return PLAYER_TYPES.get(name);
    }

    public static WireType playerType(String name) {
        WireType type = PLAYER_TYPES.get(name);
        if (type == null) {
            throw new IllegalStateException("property name not encodable in property table: " + name);
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
