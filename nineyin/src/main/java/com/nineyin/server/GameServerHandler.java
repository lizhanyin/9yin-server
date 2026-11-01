package com.nineyin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.transport.Connection;
import com.nineyin.transport.FrameException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/** 19061 主协议入口：明文帧 → LoginFlow；任何处理错误按 Go 语义断连。 */
final class GameServerHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(GameServerHandler.class);

    private final LoginFlow flow;
    private final Connection connection;
    private final LoginSession session = new LoginSession();

    GameServerHandler(LoginFlow flow, Connection connection) {
        this.flow = flow;
        this.connection = connection;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] plain) {
        if (!flow.dispatch(connection, session, plain)) {
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof FrameException) {
            log.info("frame error from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        } else {
            log.warn("connection error from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        }
        ctx.close();
    }
}
