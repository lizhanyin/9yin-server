package com.nineyin.server.protocol;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真机 0x0A 记录表协商方言（447 schema），数据源
 * resources/nineyin/protocol/official_record_table.bin。
 *
 * <p>来源：F 盘参考服（野猪端真实 exe）s2c 捕获 127-0-0-1-52795 的 0x0A 帧逐字节提取
 * （tools/smoke 提取，10,449B）。此前本服自协商 8 张表（cooldown_rec 落在自定下标 5），
 * 真机客户端 CoolLogicManager 等 C++ 原生消费者对 cooldown_rec 的绑定方式（按名/按
 * 官方下标 109）无法从 lua 侧证伪——技能 CD 行帧字节级对齐抓包却不上显，最大的未验证
 * 假设就是协商表分派。改为整表透传官方方言后，行帧索引/列宽与官方完全一致，
 * 与抓包不可区分（与 {@link PropertyTable} 同一策略）。
 *
 * <p>官方关键下标（帧内位置，行帧 u16 recordIndex 寻址）：Task_Accepted=29（28 列）、
 * Task_Completed=31、shortcut_rec=93、cooldown_rec=109（cols [3,4,4,2]）、
 * fight_relation_rec=114、QingGongRec=116、ActiveQGSkillRec=117（[7,1]）、
 * active_jingmai_rec=162、PepareJingMaiRec=204（8 串列）、active_skill_rec=446。
 */
public final class RecordSchemas {

    public record Schema(int index, String name, byte[] columnTypes) {
    }

    private static final List<Schema> SCHEMAS = new ArrayList<>();
    private static final Map<String, Schema> BY_NAME = new HashMap<>();
    private static final byte[] FRAME;

    static {
        byte[] frame;
        try (InputStream in = RecordSchemas.class.getResourceAsStream(
                "/nineyin/protocol/official_record_table.bin")) {
            if (in == null) {
                throw new IllegalStateException("official_record_table.bin missing on classpath");
            }
            frame = in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("load official_record_table.bin: " + e.getMessage(), e);
        }
        FRAME = frame;
        // 布局：0x0A | u16 count | (名字 NUL + u16 列数 + 类型字节)*——与捕获帧同构自证。
        if (frame.length < 3 || frame[0] != 0x0A) {
            throw new IllegalStateException("official record table frame has unexpected header");
        }
        int count = (frame[1] & 0xFF) | (frame[2] & 0xFF) << 8;
        int offset = 3;
        for (int index = 0; index < count; index++) {
            int end = offset;
            while (end < frame.length && frame[end] != 0) {
                end++;
            }
            if (end >= frame.length) {
                throw new IllegalStateException("official record table truncated at schema " + index);
            }
            String name = new String(frame, offset, end - offset, StandardCharsets.US_ASCII);
            int columns = (frame[end + 1] & 0xFF) | (frame[end + 2] & 0xFF) << 8;
            byte[] columnTypes = new byte[columns];
            System.arraycopy(frame, end + 3, columnTypes, 0, columns);
            Schema schema = new Schema(index, name, columnTypes);
            SCHEMAS.add(schema);
            BY_NAME.putIfAbsent(name, schema);
            offset = end + 3 + columns;
        }
        if (SCHEMAS.size() != count) {
            throw new IllegalStateException("official record table schema count mismatch");
        }
    }

    public static int count() {
        return SCHEMAS.size();
    }

    public static List<Schema> schemas() {
        return SCHEMAS;
    }

    public static Schema byIndex(int index) {
        return index >= 0 && index < SCHEMAS.size() ? SCHEMAS.get(index) : null;
    }

    /** 官方下标（按名首现；缺名抛错——编码期暴露）。 */
    public static int indexByName(String name) {
        Schema schema = BY_NAME.get(name);
        if (schema == null) {
            throw new IllegalStateException("record name absent from official table: " + name);
        }
        return schema.index();
    }

    public static byte[] columnTypes(int index) {
        Schema schema = byIndex(index);
        if (schema == null) {
            throw new IllegalArgumentException("record index " + index + " outside official table");
        }
        return schema.columnTypes();
    }

    /** 0x0A 协商帧：官方表逐字节透传（与捕获帧完全一致）。 */
    public static byte[] recordTableFrame() {
        return FRAME.clone();
    }

    private RecordSchemas() {
    }
}
