package com.nineyin.server.protocol;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 登录/创角链 S2C 帧，逐字节对齐 Go cmd/protocol-probe/messages.go。
 *
 * <p>0x04 ServerPlayerRoles：0x29 定长头（时间 u32 LE，间隔 4）+ 角色记录；
 * 0x05 ServerWorldInfo：u16 infoType + UCS-2LE 文本 + NUL 终止（无长度前缀）。
 */
public final class LoginMessages {

    /** Go noRoleLoginSuccess：0x29 定长（时间字段间的空档保持为零）。 */
    public static byte[] noRoleLoginSuccess(LocalDateTime now) {
        PacketWriter writer = PacketWriter.ofCapacity(0x29);
        writer.writeU8(0x04);
        appendLoginHeader(writer, now);
        writer.writeU32(0); // 0x21
        writer.writeU32(0); // 0x25 角色数
        return writer.toBytes();
    }

    private static void appendLoginHeader(PacketWriter writer, LocalDateTime now) {
        writer.writeU32(1);                    // 0x01
        writer.writeU32(0);                    // 0x05
        writer.writeU32(now.getYear());        // 0x09
        writer.writeU32(now.getMonthValue());  // 0x0D
        writer.writeU32(now.getDayOfMonth());  // 0x11
        writer.writeU32(now.getHour());        // 0x15
        writer.writeU32(now.getMinute());      // 0x19
        writer.writeU32(now.getSecond());      // 0x1D
    }

    /**
     * 选角列表 0x04 帧。f23/f24/f25 为选人界面的实力称号/所在场景/剧本标题：
     * 传 Go 原版值（"title001"/"scene_name_017"/book 原值）时与 Go 逐字节一致；
     * 生产由 LoginFlow 传 SceneNames/levelTitleFor 的真实显示值。
     */
    public static byte[] oneRoleLoginSuccess(LocalDateTime now, String name,
                                             List<String> appearance, String faction,
                                             String hair, String cloth, String pants, String shoes) {
        return oneRoleLoginSuccess(now, name, appearance, faction, hair, cloth, pants, shoes,
                "title001", "scene_name_017", null);
    }

    public static byte[] oneRoleLoginSuccess(LocalDateTime now, String name,
                                             List<String> appearance, String faction,
                                             String hair, String cloth, String pants, String shoes,
                                             String title, String sceneName, String bookIntro) {
        PacketWriter writer = PacketWriter.ofCapacity(0x29 + 160 + name.length() * 2);
        writer.writeU8(0x04);
        appendLoginHeader(writer, now);
        writer.writeU32(0); // 0x21
        writer.writeU32(1); // 0x25 角色数

        writer.writeU16(6).writeU8(2).writeU32(0);
        writer.writeU8(2).writeU32(0);
        writer.writeU8(7);
        appendUcs2(writer, name);
        writer.writeU8(7);
        appendUcs2(writer, rolePara(appearance, faction, hair, cloth, pants, shoes, title, sceneName, bookIntro));
        writer.writeU8(2).writeU32(0);
        writer.writeU8(5).writeF64(0);
        return writer.toBytes();
    }

    /** Go appendUCS2：u32((units+1)*2) + UTF-16LE + 0x0000。 */
    private static void appendUcs2(PacketWriter writer, String text) {
        writer.writeU32((text.length() + 1) * 2L);
        writer.writeBytes(text.getBytes(StandardCharsets.UTF_16LE));
        writer.writeU16(0);
    }

    /** Go oneRoleLoginSuccess 的 36 字段 rolePara（逗号拼接，未赋值位为空串；7/9/10/11 为解析后外观）。 */
    public static String rolePara(List<String> appearance, String faction,
                                   String hair, String cloth, String pants, String shoes,
                                   String title, String sceneName, String bookIntro) {
        String resolvedTitle = title == null || title.isEmpty() ? "title001" : title;
        String resolvedScene = sceneName == null || sceneName.isEmpty() ? "scene_name_017" : sceneName;
        String resolvedBookIntro = bookIntro == null ? get(appearance, 0, "book1") : bookIntro;
        String[] fields = new String[36];
        Arrays.fill(fields, "");
        fields[0] = "0"; // sex：当前创角抓包为男路径
        fields[1] = faction;
        fields[2] = "0";
        fields[3] = "0";
        fields[4] = "1";
        fields[5] = get(appearance, 2, "");
        fields[6] = "1";
        fields[7] = hair;
        fields[9] = cloth;
        fields[10] = pants;
        fields[11] = shoes;
        fields[21] = "stand";
        fields[22] = "127.0.0.1";
        fields[23] = resolvedTitle;
        fields[24] = resolvedScene;
        fields[25] = resolvedBookIntro;
        fields[31] = "0";
        fields[32] = get(appearance, 0, "book1");
        fields[33] = get(appearance, 4, "obj\\char\\b_hair\\b_hair1");
        fields[35] = "0";
        return String.join(",", fields);
    }

    /**
     * 选人界面实力称号（f23）：等级→title0NN 档位键（title001=不堪一击 … title027=返璞归真，
     * 客户端按 desc_title0NN 本地化）。种子 151 与参考服同种子玩家一致（2026-10-30 抓包 #0）。
     */
    public static String roleListTitle(int powerLevel) {
        if (powerLevel <= 5) {
            return "title001";
        }
        if (powerLevel <= 120) {
            return String.format("title%03d", Math.min(24, (powerLevel - 1) / 5 + 1));
        }
        if (powerLevel <= 130) {
            return "title025";
        }
        if (powerLevel <= 150) {
            return "title026";
        }
        return "title027";
    }

    private static String get(List<String> appearance, int index, String fallback) {
        if (index >= 0 && index < appearance.size() && !appearance.get(index).isEmpty()) {
            return appearance.get(index);
        }
        return fallback;
    }

    /** Go worldInfo：0x05 | u16 infoType | UCS-2LE 文本 + 0x0000（无长度前缀）。 */
    public static byte[] worldInfo(int infoType, String text) {
        byte[] units = text.getBytes(StandardCharsets.UTF_16LE);
        PacketWriter writer = PacketWriter.ofCapacity(3 + units.length + 2);
        writer.writeU8(0x05).writeU16(infoType);
        writer.writeBytes(units);
        writer.writeU16(0);
        return writer.toBytes();
    }

    /** Go createRoleName：偏移 5 起 UTF-16LE，最多 72 字节，遇 0x0000 止。 */
    public static String createRoleName(byte[] message) {
        if (message.length < 7) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int offset = 5; offset + 1 < message.length && offset < 5 + 72; offset += 2) {
            int unit = (message[offset] & 0xFF) | ((message[offset + 1] & 0xFF) << 8);
            if (unit == 0) {
                break;
            }
            out.append((char) unit);
        }
        return out.toString();
    }

    /** Go createRoleStrings：偏移 0x49 起扫描 type-6 串（u32 size 含尾部 NUL）。 */
    public static List<String> createRoleStrings(byte[] message) {
        List<String> values = new ArrayList<>(10);
        int offset = 0x49;
        while (offset + 5 <= message.length) {
            if ((message[offset] & 0xFF) != 6) {
                offset++;
                continue;
            }
            long size = (message[offset + 1] & 0xFFL)
                    | (message[offset + 2] & 0xFFL) << 8
                    | (message[offset + 3] & 0xFFL) << 16
                    | (message[offset + 4] & 0xFFL) << 24;
            if (size < 1 || size > 4096 || offset + 5 + size > message.length
                    || (message[offset + 5 + (int) size - 1] & 0xFF) != 0) {
                offset++;
                continue;
            }
            values.add(new String(message, offset + 5, (int) size - 1, StandardCharsets.UTF_8));
            offset += 5 + (int) size;
        }
        return values;
    }

    private LoginMessages() {
    }
}
