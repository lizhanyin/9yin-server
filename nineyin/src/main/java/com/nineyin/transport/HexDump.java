package com.nineyin.transport;

/**
 * 与 Go encoding/hex Dump 逐字节兼容的十六进制转储：
 * 8 位小写偏移 + 每行 16 字节（第 8/9 字节间双空格）+ 定宽补位 + ASCII 列。
 * 格式以真实 Go 日志（logs/startup-*.out.log）样本为准，便于两侧日志直接 diff。
 */
public final class HexDump {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HexDump() {
    }

    public static String dump(byte[] data) {
        return dump(data, 0, data.length);
    }

    /** 无首尾换行的转储文本；多行之间以 \n 分隔。 */
    public static String dump(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(Math.max(80, len / 16 * 79 + 8));
        for (int base = 0; base < len; base += 16) {
            int lineLen = Math.min(16, len - base);
            if (base > 0) {
                sb.append('\n');
            }
            String offset = Integer.toHexString(base);
            sb.append("0".repeat(8 - offset.length())).append(offset).append("  ");
            for (int i = 0; i < 16; i++) {
                if (i == 8) {
                    sb.append(' ');
                }
                if (i < lineLen) {
                    int b = data[off + base + i] & 0xFF;
                    sb.append(HEX[b >>> 4]).append(HEX[b & 0xF]).append(' ');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ');
            sb.append('|');
            for (int i = 0; i < lineLen; i++) {
                int b = data[off + base + i] & 0xFF;
                sb.append(b >= 0x20 && b <= 0x7E ? (char) b : '.');
            }
            sb.append('|');
        }
        return sb.toString();
    }
}
