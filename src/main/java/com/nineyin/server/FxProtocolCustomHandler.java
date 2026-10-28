package com.nineyin.server;

import com.swingfrog.summer.protocol.custom.ProtocolCustomHandler;
import com.swingfrog.summer.server.ServerContext;
import com.nineyin.transport.FxConnection;
import com.nineyin.transport.FxFrameDecoder;
import com.nineyin.transport.FxFrameEncoder;
import io.netty.channel.socket.SocketChannel;

/**
 * Summer 自定义协议接入点：server.properties 配 protocol=custom 后，
 * Summer 把原生 SocketChannel 交到这里，由我们装配 FxNet2 管线。
 * 使用前先 ProtocolCustomMgr.get().setHandler(this)。
 */
public final class FxProtocolCustomHandler implements ProtocolCustomHandler {

    private final FxOpcodeRouter router;
    private final FxConnectionListener listener;

    public FxProtocolCustomHandler(FxOpcodeRouter router) {
        this(router, null);
    }

    public FxProtocolCustomHandler(FxOpcodeRouter router, FxConnectionListener listener) {
        this.router = router;
        this.listener = listener;
    }

    @Override
    public void onHandle(SocketChannel socketChannel, ServerContext serverContext) {
        FxConnection connection = new FxConnection();
        socketChannel.pipeline()
                .addLast(new FxFrameDecoder(connection))
                .addLast(new FxFrameEncoder(connection))
                .addLast(new FxServerHandler(router, connection, listener));
    }
}
