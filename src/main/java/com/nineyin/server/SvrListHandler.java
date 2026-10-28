package com.nineyin.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 4000 区服列表端口的纯文本协议：客户端发 svrlist\r\n，
 * 服务端回 svrlist #LocalTest $127.0.0.1 19061 0\r\n 后关闭连接。
 * 名字必须 ASCII（中文名会导致部分客户端构建显示异常）。
 */
final class SvrListHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SvrListHandler.class);
    private static final byte[] RESPONSE =
            "svrlist #LocalTest $127.0.0.1 19061 0\r\n".getBytes(StandardCharsets.US_ASCII);

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
                byte[] resp = ("svrlist #LocalTest $" + gameHost + " " + gamePort + " 0\r\n")
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
