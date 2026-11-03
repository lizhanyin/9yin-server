package com.nineyin.server.handler;

import com.nineyin.server.game.login.LoginFlow;
import com.nineyin.server.game.login.LoginSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/** 19061 主协议入口：明文帧 → LoginFlow；任何处理错误按 Go 语义断连。 */
public final class GameServerHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(GameServerHandler.class);

    private final LoginFlow flow;
    private final Connection connection;
    private final LoginSession session = new LoginSession();

    public GameServerHandler(LoginFlow flow, Connection connection) {
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
    public void channelInactive(ChannelHandlerContext ctx) {
        com.nineyin.server.transport.CaptureSink.close(
                String.valueOf(ctx.channel().remoteAddress()));
        flow.onDisconnect(session);
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
