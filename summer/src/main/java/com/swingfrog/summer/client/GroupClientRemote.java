package com.swingfrog.summer.client;

import com.swingfrog.summer.client.exception.SyncRemoteTimeOutException;
import com.swingfrog.summer.protocol.SessionRequest;
import com.swingfrog.summer.protocol.SessionResponse;
import com.swingfrog.summer.server.exception.CodeException;
import com.swingfrog.summer.util.ParamUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GroupClientRemote implements ClientRemote {

    private static final Logger log = LoggerFactory.getLogger(GroupClientRemote.class);
    private final ClientContext defaultClientContext;
    private final ClientCluster clientCluster;

    public GroupClientRemote(ClientContext clientContext, ClientCluster clientCluster) {
        this.defaultClientContext = clientContext;
        this.clientCluster = clientCluster;
    }

    private ClientContext nextClientContext() {
        Client client = clientCluster.getClientWithNext();
        if (client != null) {
            return client.getClientContext();
        }
        return defaultClientContext;
    }

    @Override
    public void asyncRemote(String remote, String method, Object data, RemoteCallback remoteCallback) {
        if (remoteCallback == null) {
            throw new NullPointerException("remoteCallback is null");
        }
        ClientContext clientContext = nextClientContext();
        SessionRequest sessionRequest = SessionRequest.buildRemote(ClientMgr.get().incrementCurrentId(), remote, method, data);
        PushDispatchMgr.get().putAsyncRemote(sessionRequest.getId(), remoteCallback);
        if (clientContext.isChannelActive()) {
            String msg = sessionRequest.toJSONString();
            log.debug("client request serverName[{}] async {}", clientContext.getConfig().getServerName(), msg);
            clientContext.getChannel().writeAndFlush(msg);
        } else {
            clientContext.getRequestQueue().add(sessionRequest);
        }
    }

    @Override
    public void retryAsyncRemote(String remote, String method, Object data, RemoteCallback remoteCallback, long afterTimeRetry, TimeUnit unit) {
        if (remoteCallback == null) {
            throw new NullPointerException("remoteCallback is null");
        }
        ClientContext clientContext = nextClientContext();
        SessionRequest sessionRequest = SessionRequest.buildRemote(ClientMgr.get().incrementCurrentId(), remote, method, data);
        String msg = sessionRequest.toJSONString();
        PushDispatchMgr.get().putAsyncRemote(sessionRequest.getId(), remoteCallback);
        if (clientContext.isChannelActive()) {
            log.debug("client request serverName[{}] retry async {}", clientContext.getConfig().getServerName(), msg);
            clientContext.getChannel().writeAndFlush(msg);
        }
        addAsyncRemoteTimeoutTask(sessionRequest.getId(), msg, afterTimeRetry, unit);
    }

    @Override
    public <T> T syncRemote(String remote, String method, Object data, Type type) {
        SessionRequest sessionRequest = SessionRequest.buildRemote(ClientMgr.get().incrementCurrentId(), remote, method, data);
        String msg = sessionRequest.toJSONString();
        return sendSyncRemote(sessionRequest.getId(), msg, remote, method, type);
    }

    @Override
    public <T> T rsyncRemote(String remote, String method, Object data, Type type) {
        SessionRequest sessionRequest = SessionRequest.buildRemote(ClientMgr.get().incrementCurrentId(), remote, method, data);
        String msg = sessionRequest.toJSONString();
        for (;;) {
            try {
                return sendSyncRemote(sessionRequest.getId(), msg, remote, method, type);
            } catch (SyncRemoteTimeOutException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> CompletableFuture<T> futureRemote(String remote, String method, Object data, Type type) {
        return futureRemote(remote, method, data).thenApply(resp -> {
            if (resp == null) {
                return null;
            }
            return (T) ParamUtil.convert(type, resp.toString());
        });
    }

    @Override
    public CompletableFuture<Object> futureRemote(String remote, String method, Object data) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        asyncRemote(remote, method, data, new RemoteCallback() {
            @Override
            public void success(Object obj) {
                future.complete(obj);
            }
            @Override
            public void failure(long code, String msg) {
                future.completeExceptionally(new CodeException((int) code, msg));
            }
        });
        return future;
    }

    private void addAsyncRemoteTimeoutTask(long id, String msg, long afterTimeRetry, TimeUnit unit) {
        ClientMgr.get().getAsyncRemoteCheckExecutor().schedule(() -> {
            if (!PushDispatchMgr.get().containsAsyncRemote(id)) {
                ClientMgr.get().getFutureMap().remove(id);
                return;
            }
            ClientContext clientContext = nextClientContext();
            if (clientContext.isChannelActive()) {
                log.debug("client request serverName[{}] retry async {}", clientContext.getConfig().getServerName(), msg);
                clientContext.getChannel().writeAndFlush(msg);
            }
            addAsyncRemoteTimeoutTask(id, msg, afterTimeRetry, unit);
        }, afterTimeRetry, unit);
    }

    @SuppressWarnings("unchecked")
    private <T> T sendSyncRemote(long id, String msg, String remote, String method, Type type) {
        CompletableFuture<SessionResponse> future = new CompletableFuture<>();
        PushDispatchMgr.get().putSyncRemote(id, future);
        long deadline = System.currentTimeMillis() + defaultClientContext.getConfig().getSyncRemoteTimeOutMs();
        try {
            while (true) {
                ClientContext clientContext = nextClientContext();
                if (clientContext.isChannelActive()) {
                    log.debug("client request serverName[{}] sync {}", clientContext.getConfig().getServerName(), msg);
                    clientContext.getChannel().writeAndFlush(msg);
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new SyncRemoteTimeOutException(remote, method);
                }
                Thread.sleep(1);
            }
            SessionResponse sessionResponse = future.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (sessionResponse.getCode() != 0) {
                throw new CodeException(sessionResponse.getCode(), sessionResponse.getData().toString());
            }
            Object resp = sessionResponse.getData();
            if (resp != null) {
                return (T) ParamUtil.convert(type, resp.toString());
            }
            return null;
        } catch (TimeoutException e) {
            throw new SyncRemoteTimeOutException(remote, method);
        } catch (ExecutionException e) {
            throw new SyncRemoteTimeOutException(remote, method);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(e.getMessage(), e);
            return null;
        } finally {
            if (PushDispatchMgr.get().removeSyncRemote(id) != null) {
                PushDispatchMgr.get().discardSyncRemote(id);
            }
        }
    }

}
