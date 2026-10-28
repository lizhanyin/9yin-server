package com.nineyin.server;

import com.swingfrog.summer.protocol.custom.ProtocolCustomHandler;
import com.swingfrog.summer.server.ServerContext;
import com.nineyin.transport.FxConnection;
import com.nineyin.transport.FxFrameDecoder;
import com.nineyin.transport.FxFrameEncoder;
import io.netty.channel.socket.SocketChannel;

/**
 * Summer 自定义协议接入点：server.properties 配 protocol=custom 后，
 * Summer 把原生 SocketChannel 交到这里，按端口装配不同管线——
 * 4000 = 区服列表纯文本；19061 = FxNet2 游戏主协议。
 * 使用前先 ProtocolCustomMgr.get().setHandler(this)。
 */
public final class FxProtocolCustomHandler implements ProtocolCustomHandler {

    public static final int AREA_LIST_PORT = 4000;

    private final FxOpcodeRouter router;
    private final FxConnectionListener listener;
    private final String gameHost;
    private final int gamePort;

    public FxProtocolCustomHandler(FxOpcodeRouter router, FxConnectionListener listener,
                                   String gameHost, int gamePort) {
        this.router = router;
        this.listener = listener;
        this.gameHost = gameHost;
        this.gamePort = gamePort;
    }

    @Override
    public void onHandle(SocketChannel socketChannel, ServerContext serverContext) {
        int port = serverContext.getConfig().getPort();
        if (port == AREA_LIST_PORT) {
            socketChannel.pipeline().addLast(new SvrListHandler(gameHost, gamePort));
            return;
        }
        FxConnection connection = new FxConnection();
        socketChannel.pipeline()
                .addLast(new FxFrameDecoder(connection))
                .addLast(new FxFrameEncoder(connection))
                .addLast(new FxServerHandler(router, connection, listener));
    }
}
