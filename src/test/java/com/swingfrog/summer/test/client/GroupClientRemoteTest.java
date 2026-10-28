package com.swingfrog.summer.test.client;

import com.swingfrog.summer.client.ClientCluster;
import com.swingfrog.summer.client.ClientContext;
import com.swingfrog.summer.client.GroupClientRemote;
import com.swingfrog.summer.client.PushDispatchMgr;
import com.swingfrog.summer.protocol.SessionResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GroupClientRemote（集群轮询，空集群时回退默认连接）同步 RPC 行为验证。
 */
class GroupClientRemoteTest {

    @Test
    void syncRemoteSuccessViaDefaultContextWhenClusterEmpty() throws Exception {
        ClientContext ctx = RemoteTestSupport.newClientContext(RemoteTestSupport.newConfig(2000));
        EmbeddedChannel channel = RemoteTestSupport.attachChannel(ctx);
        GroupClientRemote remote = new GroupClientRemote(ctx, new ClientCluster());

        CompletableFuture<String> result = RemoteTestSupport.callAsync(
                () -> remote.syncRemote(RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, null, String.class));
        long id = RemoteTestSupport.awaitOutboundId(channel);

        PushDispatchMgr.get().processRemote(
                SessionResponse.buildMsg(id, RemoteTestSupport.REMOTE, RemoteTestSupport.METHOD, "ok"));

        assertEquals("ok", result.get(2, TimeUnit.SECONDS));
    }
}
