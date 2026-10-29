package com.nineyin.server;

import com.nineyin.transport.FxConnection;
import com.nineyin.transport.FxWireLog;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 连接级入站处理：绑定会话属性，把明文帧交给 opcode 路由器。 */
public final class FxServerHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(FxServerHandler.class);

    private final FxOpcodeRouter router;
    private final FxConnection connection;
    private final FxConnectionListener listener;

    public FxServerHandler(FxOpcodeRouter router, FxConnection connection) {
        this(router, connection, null);
    }

    public FxServerHandler(FxOpcodeRouter router, FxConnection connection, FxConnectionListener listener) {
        this.router = router;
        this.connection = connection;
        this.listener = listener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        FxSession session = new FxSession(ctx.channel(), connection);
        ctx.channel().attr(FxSession.ATTR).set(session);
        if (listener != null) {
            listener.onConnected(session);
        }
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] plain) {
        FxSession session = ctx.channel().attr(FxSession.ATTR).get();
        if (FxWireLog.enabled()) {
            FxWireLog.recv(session.remoteAddress(), plain);
        }
        router.dispatch(session, plain);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (listener != null) {
            FxSession session = ctx.channel().attr(FxSession.ATTR).get();
            if (session != null) {
                listener.onDisconnected(session);
            }
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("connection {} error: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
