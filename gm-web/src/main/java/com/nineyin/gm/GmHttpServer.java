package com.nineyin.gm;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GM HTTP 面板服务：默认仅绑定 127.0.0.1（对齐 C++，控制台不对外暴露）。
 */
public final class GmHttpServer {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 19080;

    private static final Logger log = LoggerFactory.getLogger(GmHttpServer.class);

    private final String host;
    private final int port;
    private final GmRequestHandler requestHandler;
    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;

    public GmHttpServer(GmHub hub) {
        this(hub, GmCatalog.empty(), DEFAULT_HOST, DEFAULT_PORT);
    }

    public GmHttpServer(GmHub hub, GmCatalog catalog) {
        this(hub, catalog, DEFAULT_HOST, DEFAULT_PORT);
    }

    public GmHttpServer(GmHub hub, String host, int port) {
        this(hub, GmCatalog.empty(), host, port);
    }

    public GmHttpServer(GmHub hub, GmCatalog catalog, String host, int port) {
        this.host = host;
        this.port = port;
        this.requestHandler = new GmRequestHandler(hub, catalog);
    }

    public String url() {
        return "http://" + host + ":" + port + "/";
    }

    public synchronized void start() {
        if (serverChannel != null) {
            return;
        }
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup(1);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(64 * 1024))
                                .addLast(new GmChannelHandler(requestHandler));
                    }
                });
        try {
            serverChannel = bootstrap.bind(host, port).syncUninterruptibly().channel();
            log.info("GM panel: {}", url());
        } catch (Exception e) {
            log.error("GM HTTP bind {}:{} failed (port in use?)", host, port, e);
            shutdownGroups();
        }
    }

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        shutdownGroups();
    }

    private void shutdownGroups() {
        if (boss != null) {
            boss.shutdownGracefully();
            boss = null;
        }
        if (worker != null) {
            worker.shutdownGracefully();
            worker = null;
        }
    }

    static final class GmChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final GmRequestHandler handler;

        GmChannelHandler(GmRequestHandler handler) {
            this.handler = handler;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            // 与 C++ 一致：一请求一连接，响应后即关闭
            ctx.writeAndFlush(handler.handle(msg))
                    .addListener(future -> ctx.close());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("GM http error: {}", cause.getMessage());
            ctx.close();
        }
    }
}
