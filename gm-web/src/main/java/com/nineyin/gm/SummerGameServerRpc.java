package com.nineyin.gm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.swingfrog.summer.client.ClientMgr;
import com.swingfrog.summer.client.ClientRemote;
import com.swingfrog.summer.client.exception.SyncRemoteTimeOutException;
import com.swingfrog.summer.config.ClientConfig;
import com.swingfrog.summer.protocol.ProtocolConst;
import com.swingfrog.summer.server.exception.CodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GameServerRpc} 的 summer 实现：按「host:port」幂等动态注册客户端
 * （{@link ClientMgr#register}，断线自动重连），syncRemote 调 GmRemote。
 *
 * <p>断连退避：调用失败后 5s 内该服直接快速失败，避免控制台 2.5s 轮询 ×
 * 秒级 syncRemote 超时把面板拖卡；重试由 summer reconnect 兜底。
 */
public final class SummerGameServerRpc implements GameServerRpc {

    private static final Logger log = LoggerFactory.getLogger(SummerGameServerRpc.class);

    /** 与游戏服 minor.gm 对齐的参数（msgLength 需 ≥ 最大会话列表响应）。 */
    private static final String CLUSTER = "Game";
    private static final String REMOTE = "GmRemote";
    private static final int SYNC_TIMEOUT_MS = 3000;
    private static final long DOWN_BACKOFF_MS = 5000;

    private final Map<String, Long> downUntil = new ConcurrentHashMap<>();

    @Override
    public String call(String host, int port, String method, Map<String, Object> data) {
        String addr = host + ":" + port;
        String name = serverName(host, port);
        long now = System.currentTimeMillis();
        Long down = downUntil.get(addr);
        if (down != null && down > now) {
            throw new GameServerOfflineException("game server offline (backoff): " + addr);
        }
        try {
            ClientRemote remote = remote(host, port, name);
            if (remote == null) {
                throw new GameServerOfflineException("game server client unavailable: " + addr);
            }
            return remote.syncRemote(REMOTE, method, data != null ? data : Map.of(), String.class);
        } catch (SyncRemoteTimeOutException | CodeException e) {
            return markDown(addr, e);
        } catch (RuntimeException e) {
            // 连接未建立（syncRemote 忙等超时抛出的底层异常）等场景统一按离线处理。
            return markDown(addr, e);
        }
    }

    private String markDown(String addr, Exception cause) {
        downUntil.put(addr, System.currentTimeMillis() + DOWN_BACKOFF_MS);
        log.debug("game server rpc failed ({}): {}", addr, cause.toString());
        throw new GameServerOfflineException("game server offline: " + addr, cause);
    }

    private ClientRemote remote(String host, int port, String name) {
        try {
            ClientMgr.get().register(config(host, port, name));
        } catch (Exception e) {
            throw new GameServerOfflineException("register client failed: " + host + ":" + port, e);
        }
        return ClientMgr.get().getClientRemote(CLUSTER, name);
    }

    /** 幂等注册的配置：cluster=Game（对端 minor.gm 所在集群），serverName=host_port 唯一标识。 */
    private static ClientConfig config(String host, int port, String name) {
        ClientConfig config = new ClientConfig();
        config.setCluster(CLUSTER);
        config.setServerName(name);
        config.setAddress(host);
        config.setPort(port);
        config.setProtocol(ProtocolConst.SERVER_PROTOCOL_STRING_LINE);
        config.setCharset("UTF-8");
        config.setPassword("");
        config.setMsgLength(1048576);
        config.setHeartSec(20);
        config.setReconnectMs(100);
        config.setSyncRemoteTimeOutMs(SYNC_TIMEOUT_MS);
        config.setConnectNum(1);
        return config;
    }

    static String serverName(String host, int port) {
        return host.replace(':', '_') + "_" + port;
    }
}
