package com.nineyin.server.handler;

import com.nineyin.server.game.login.LoginFlow;
import com.swingfrog.summer.protocol.custom.ProtocolCustomHandler;
import com.swingfrog.summer.server.ServerContext;
import com.nineyin.server.transport.Connection;
import com.nineyin.server.transport.FrameDecoder;
import com.nineyin.server.transport.FrameEncoder;

import io.netty.channel.socket.SocketChannel;

/**
 * Summer 自定义协议接入点：server.properties 配 protocol=custom 后，
 * SocketChannel 按端口装配——4000 = 区服列表纯文本；19061 = FxNet2 登录链。
 */
public final class NineyinProtocolHandler implements ProtocolCustomHandler {

    public static final int AREA_LIST_PORT = 4000;

    private final LoginFlow flow;
    private final String gameHost;
    private final int gamePort;

    public NineyinProtocolHandler(LoginFlow flow, String gameHost, int gamePort) {
        this.flow = flow;
        this.gameHost = gameHost;
        this.gamePort = gamePort;
    }

    @Override
    public void onHandle(SocketChannel socketChannel, ServerContext serverContext) {
        int port = serverContext.getConfig().getPort();
        if (port == AREA_LIST_PORT) {
            socketChannel.pipeline().addLast(new ServerListHandler(gameHost, gamePort));
            return;
        }
        Connection connection = new Connection(socketChannel);
        socketChannel.pipeline()
                .addLast(new FrameDecoder(connection))
                .addLast(new FrameEncoder(connection))
                .addLast(new GameServerHandler(flow, connection));
    }
}
