package com.swingfrog.summer.test.client;

import com.swingfrog.summer.client.ClientContext;
import com.swingfrog.summer.client.DefaultClientRemote;
import com.swingfrog.summer.client.PushDispatchMgr;
import com.swingfrog.summer.client.exception.SyncRemoteTimeOutException;
import com.swingfrog.summer.protocol.SessionResponse;
import com.swingfrog.summer.server.exception.CodeException;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DefaultClientRemote 同步/异步 RPC 改造后的行为验证（EmbeddedChannel，无真实网络）。
 * 等待方统一运行在虚拟线程上，验证阻塞等待可被响应线程唤醒。
 */
class DefaultClientRemoteTest {

    private static SessionResponse okResponse(long id, Object data) {
        return SessionResponse.buildMsg(id, RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, data);
    }

    private static SessionResponse errorResponse(long id, int code, String msg) {
        return SessionResponse.buildError(id, RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, code, msg);
    }

    private static com.alibaba.fastjson.JSONObject data() {
        com.alibaba.fastjson.JSONObject data = new com.alibaba.fastjson.JSONObject();
        data.put("k", "v");
        return data;
    }

    @Test
    void syncRemoteSuccessWithTypeConversion() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(2000));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<Integer> result = RemoteTestSupport.callAsync(
                () -> remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, Integer.class));
        long id = RemoteTestSupport.awaitOutboundId(channel);

        PushDispatchMgr.get().processRemote(okResponse(id, "777"));

        assertEquals(777, result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void syncRemoteErrorCodeThrowsCodeException() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(2000));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<Integer> result = RemoteTestSupport.callAsync(
                () -> remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, Integer.class));
        long id = RemoteTestSupport.awaitOutboundId(channel);

        PushDispatchMgr.get().processRemote(errorResponse(id, 1001, "boom"));

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        CodeException cause = (CodeException) e.getCause();
        assertEquals(1001, cause.getCode());
    }

    @Test
    void syncRemoteTimeoutThenLateResponseDiscardedAndNextCallWorks() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(150));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<Integer> result = RemoteTestSupport.callAsync(
                () -> remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, Integer.class));
        long id = RemoteTestSupport.awaitOutboundId(channel);

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof SyncRemoteTimeOutException);
        // 迟到响应应被丢弃，不影响后续调用
        PushDispatchMgr.get().processRemote(okResponse(id, "1"));

        CompletableFuture<Integer> next = RemoteTestSupport.callAsync(
                () -> remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, Integer.class));
        long nextId = RemoteTestSupport.awaitOutboundId(channel);
        PushDispatchMgr.get().processRemote(okResponse(nextId, "42"));
        assertEquals(42, next.get(2, TimeUnit.SECONDS));
    }

    @Test
    void syncRemoteTimesOutWhenChannelInactiveWithoutQueueing() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(150));
        DefaultClientRemote remote = new DefaultClientRemote(ctx); // 未注入 channel

        CompletableFuture<Integer> result = RemoteTestSupport.callAsync(
                () -> remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, Integer.class));

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof SyncRemoteTimeOutException);
        // 同步路径不排队（排队只在 asyncRemote 的断线缓存里）
        assertEquals(0, ctx.getRequestQueue().size());
    }

    @Test
    void rsyncRemoteRetriesUntilSuccess() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(150));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<String> result = RemoteTestSupport.callAsync(
                () -> remote.rsyncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, String.class));
        RemoteTestSupport.awaitOutboundId(channel); // 第一次请求不响应，等其超时
        long retryId = RemoteTestSupport.awaitOutboundId(channel); // 重试的仍是同一请求

        PushDispatchMgr.get().processRemote(okResponse(retryId, "ok"));

        assertEquals("ok", result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void syncRemoteInterruptedReturnsNullAndCleansUp() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(5000));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread caller = Thread.ofVirtual().unstarted(() -> {
            try {
                result.complete(remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, Object.class));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        caller.start();
        long id = RemoteTestSupport.awaitOutboundId(channel);

        caller.interrupt();

        assertNull(result.get(2, TimeUnit.SECONDS));
        assertTrue(caller.isInterrupted()); // 中断标志被恢复而非吞掉
        assertNull(PushDispatchMgr.get().removeSyncRemote(id)); // 挂起请求已清理
    }

    @Test
    void futureRemoteCompletesWithRawAndConvertedData() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(2000));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<Object> raw = remote.futureRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, data());
        long rawId = RemoteTestSupport.awaitOutboundId(channel);
        PushDispatchMgr.get().processRemote(okResponse(rawId, "pong"));
        assertEquals("pong", raw.get(2, TimeUnit.SECONDS));

        CompletableFuture<Integer> typed = remote.futureRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, data(), Integer.class);
        long typedId = RemoteTestSupport.awaitOutboundId(channel);
        PushDispatchMgr.get().processRemote(okResponse(typedId, "42"));
        assertEquals(42, typed.get(2, TimeUnit.SECONDS));
    }

    @Test
    void futureRemoteFailsWithCodeException() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(2000));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        DefaultClientRemote remote = new DefaultClientRemote(ctx);

        CompletableFuture<Object> result = remote.futureRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, data());
        long id = RemoteTestSupport.awaitOutboundId(channel);
        PushDispatchMgr.get().processRemote(errorResponse(id, 500, "denied"));

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        CodeException cause = (CodeException) e.getCause();
        assertEquals(500, cause.getCode());
    }
}
