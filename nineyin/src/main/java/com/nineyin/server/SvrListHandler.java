package com.nineyin.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 4000 区服列表端口的纯文本协议：客户端发 svrlist 后跟 CRLF，
 * 服务端回 "$svrlist #<SERVER_NAME_ESCAPED> $127.0.0.1 19061 0" + CRLF 后关闭连接。
 * 应答与 Go 部署的 loopback-lister.ps1 字节级一致（logs/lister/response.bin 实测）：
 * 真实 fxlaunch 要求 $ 前缀 + 反斜杠u跟4位十六进制的转义服名（客户端解码显示"本地测试"），
 * 缺一样都解析失败、进不了服务器列表。
 */
final class SvrListHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SvrListHandler.class);
    private static final String SERVER_NAME_ESCAPED = "\\u672C\\u5730\\u6D4B\\u8BD5";

    private final String gameHost;
    private final int gamePort;

    SvrListHandler(String gameHost, int gamePort) {
        this.gameHost = gameHost;
        this.gamePort = gamePort;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
            String req = buf.toString(StandardCharsets.US_ASCII).trim();
            buf.release();
            if (req.contains("svrlist")) {
                byte[] resp = ("$svrlist #" + SERVER_NAME_ESCAPED + " $" + gameHost + " " + gamePort + " 0\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                ctx.writeAndFlush(Unpooled.wrappedBuffer(resp))
                        .addListener(future -> ctx.close());
                log.info("area request served: {}", req);
            } else {
                log.warn("unknown area command: {}", req);
                ctx.close();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("area connection error: {}", cause.getMessage());
        ctx.close();
    }
}
