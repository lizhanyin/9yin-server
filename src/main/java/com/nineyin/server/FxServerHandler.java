package com.nineyin.server;

import com.nineyin.transport.FxConnection;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 连接级入站处理：绑定会话属性，把明文帧交给 opcode 路由器。 */
final class FxServerHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(FxServerHandler.class);

    private final FxOpcodeRouter router;
    private final FxConnection connection;

    FxServerHandler(FxOpcodeRouter router, FxConnection connection) {
        this.router = router;
        this.connection = connection;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().attr(FxSession.ATTR).set(new FxSession(ctx.channel(), connection));
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] plain) {
        FxSession session = ctx.channel().attr(FxSession.ATTR).get();
        router.dispatch(session, plain);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("connection {} error: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
