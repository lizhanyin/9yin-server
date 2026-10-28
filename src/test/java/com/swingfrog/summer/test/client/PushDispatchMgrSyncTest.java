package com.swingfrog.summer.test.client;

import com.swingfrog.summer.client.PushDispatchMgr;
import com.swingfrog.summer.protocol.SessionResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PushDispatchMgr 同步响应表（CompletableFuture 化）的行为验证。
 * 使用与 ClientMgr 自增 id 不重叠的大 id，避免单例状态串扰。
 */
class PushDispatchMgrSyncTest {

    private static SessionResponse response(long id, Object data) {
        return SessionResponse.buildMsg(id, RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, data);
    }

    @Test
    void responseCompletesRegisteredFuture() throws Exception {
        long id = 900_001L;
        CompletableFuture<SessionResponse> future = new CompletableFuture<>();
        PushDispatchMgr.get().putSyncRemote(id, future);

        PushDispatchMgr.get().processRemote(response(id, "pong"));

        SessionResponse resp = future.get(1, TimeUnit.SECONDS);
        assertEquals(id, resp.getId());
        assertEquals("pong", resp.getData());
    }

    @Test
    void lateResponseAfterTimeoutCleanupIsDiscarded() {
        long id = 900_002L;
        CompletableFuture<SessionResponse> future = new CompletableFuture<>();
        PushDispatchMgr.get().putSyncRemote(id, future);
        // 模拟 sendSyncRemote 超时后的清理：移除 future 并标记丢弃
        if (PushDispatchMgr.get().removeSyncRemote(id) == null) {
            throw new AssertionError("future should still be registered before cleanup");
        }
        PushDispatchMgr.get().discardSyncRemote(id);

        assertDoesNotThrow(() -> PushDispatchMgr.get().processRemote(response(id, "late")));
        assertFalse(future.isDone());
    }

    @Test
    void unknownResponseDoesNotThrow() {
        long id = 900_003L;
        assertDoesNotThrow(() -> PushDispatchMgr.get().processRemote(response(id, "stray")));
    }
}
