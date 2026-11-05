package com.nineyin.server.protocol;

/**
 * 0x18 ping → 0x38 heartbeatReply（连接级心跳，全程相位有效）。
 *
 * <p>客户端连上后每 ~1s 发 12B {@code 0x18}：{@code tick u32@1 | 0@5 | seq u16@6 | 尾段 u32@8 | u8@11}，
 * 按 0x38 回包回显的 seq 配对测 RTT；无回包/seq 失配则测不出 → 客户端时序补偿劣化
 * （2026-10-31 实证：HUD 延时恒 3000ms=封顶值）。参考服 exe（55866 抓包 115 ping↔115 回包
 * 严格成对）为 12B 载荷原样回显；官服为 30B（多服务器时钟 u64×2）。
 *
 * <p>30B 布局逐字节移植自 menu2 exe {@code main.heartbeatReply}（旧仓 SceneFlow.onPing，
 * 2026-10-31 已真机验证 HUD 正常）：tick 回显、seq@6 回显、@7=0（客户端只匹配 seq 低位，
 * 容忍该位不回显）、尾段回显、{@code [13..21]/[22..30]} = 服务器毫秒时钟（官服 pcap 实证
 * ms 基准；旧仓用 Unix 秒亦实测可用）。仅按 ping 回，无登录种子/周期推送（官服同款）。
 */
public final class HeartbeatMessages {

    private HeartbeatMessages() {
    }

    /** 0x38 回包（30B）。ping 不足 12B 时仍回最小帧（tick/seq 回显，时钟段置零）。 */
    public static byte[] heartbeatReply(byte[] ping, long serverUnixMs) {
        PacketWriter writer = PacketWriter.ofCapacity(30);
        writer.writeU8(0x38);
        if (ping.length >= 7) {
            writer.writeU32(readU32(ping, 1)); // 客户端 tick 回显
            writer.writeU8(ping[5] & 0xFF);
            writer.writeU8(ping[6] & 0xFF);    // seq 低位（HUD 配对键）
            writer.writeU8(0);
            if (ping.length >= 12) {
                writer.writeU32(readU32(ping, 8)); // 尾段回显
                writer.writeU8(ping[11] & 0xFF);
                writer.writeU64(serverUnixMs);
                writer.writeU8(1);
                writer.writeU64(serverUnixMs);
            } else {
                for (int i = 0; i < 18; i++) {
                    writer.writeU8(0);
                }
            }
        } else {
            for (int i = 0; i < 29; i++) {
                writer.writeU8(0);
            }
        }
        return writer.toBytes();
    }

    private static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | (data[offset + 1] & 0xFFL) << 8
                | (data[offset + 2] & 0xFFL) << 16
                | (data[offset + 3] & 0xFFL) << 24;
    }
}
