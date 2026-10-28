package com.swingfrog.summer.test.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.alibaba.fastjson.JSON;
import com.swingfrog.summer.client.ClientContext;
import com.swingfrog.summer.config.ClientConfig;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 无网络环境的 RPC 客户端测试支撑：用 EmbeddedChannel 伪装已连接的 channel，
 * 出站请求可读取、响应通过 PushDispatchMgr.processRemote 注入。
 */
final class RemoteTestSupport {

    static final String REMOTE = "TestRemote";
    static final String METHOD = "echo";

    private RemoteTestSupport() {
    }

    static ClientConfig newConfig(int syncRemoteTimeOutMs) {
        ClientConfig config = new ClientConfig();
        config.setCluster("test-cluster");
        config.setServerName("test-server");
        config.setSyncRemoteTimeOutMs(syncRemoteTimeOutMs);
        return config;
    }

    static ClientContext newClientContext(ClientConfig config) {
        return new ClientContext(0, config, null);
    }

    static EmbeddedChannel attachChannel(ClientContext clientContext) {
        ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ChannelHandlerContext ctx = channel.pipeline().context(handler);
        clientContext.setChannel(ctx);
        return channel;
    }

    static long awaitOutboundId(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            Object msg = channel.readOutbound();
            if (msg != null) {
                return JSON.parseObject((String) msg).getLongValue("id");
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no outbound request within 2s");
    }

    static <T> CompletableFuture<T> callAsync(Supplier<T> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(call.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }
}
