package com.nineyin.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 线缆级收发日志（对齐 Go 版 protocol-probe 的输入输出观测）：
 * 收包每帧一行摘要（DEBUG），全量十六进制转储在 TRACE 级追加；
 * 入站登录帧 0x02 只打摘要不打转储（含账号凭据，与 Go 一致）。
 * 统一走独立 logger "nineyin.wire"（默认 logs/wire.log，见 logback.xml），
 * 级别可用 -DWIRE_LOG_LEVEL / 环境变量 WIRE_LOG_LEVEL 调整（DEBUG 只留摘要，OFF 关闭）。
 */
public final class FxWireLog {

    private static final Logger WIRE = LoggerFactory.getLogger("nineyin.wire");

    /** 登录帧 opcode：摘要处理，不转储。 */
    private static final int OPCODE_LOGIN = 0x02;

    private FxWireLog() {
    }

    public static boolean enabled() {
        return WIRE.isDebugEnabled();
    }

    public static boolean dumpEnabled() {
        return WIRE.isTraceEnabled();
    }

    /** 入站（客户端→服务器）解密后的明文帧。 */
    public static void recv(String remote, byte[] plain) {
        if (plain.length == 0 || !WIRE.isDebugEnabled()) {
            return;
        }
        int opcode = plain[0] & 0xFF;
        StringBuilder msg = new StringBuilder(96 + plain.length * 6)
                .append("recv remote=").append(remote)
                .append(" opcode=0x").append(Integer.toHexString(opcode))
                .append(" decoded_len=").append(plain.length);
        if (opcode != OPCODE_LOGIN && WIRE.isTraceEnabled()) {
            msg.append('\n').append(HexDump.dump(plain));
        }
        WIRE.debug(msg.toString());
    }

    /** 出站（服务器→客户端）编码前的明文帧。 */
    public static void send(String remote, byte[] plain) {
        if (plain.length == 0 || !WIRE.isDebugEnabled()) {
            return;
        }
        int opcode = plain[0] & 0xFF;
        StringBuilder msg = new StringBuilder(96 + plain.length * 6)
                .append("send remote=").append(remote)
                .append(" opcode=0x").append(Integer.toHexString(opcode))
                .append(" len=").append(plain.length);
        if (WIRE.isTraceEnabled()) {
            msg.append('\n').append(HexDump.dump(plain));
        }
        WIRE.debug(msg.toString());
    }
}
