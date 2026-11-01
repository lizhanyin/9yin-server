package com.nineyin.gm;

import java.util.Map;

/**
 * gm-web → 游戏服 GmRemote 的 RPC 通道（summer StringLine，动态注册客户端）。
 * 面板侧统一以「host:port」标识目标游戏服；方法契约见游戏服 GmRemote（返回 JSON 字符串）。
 */
public interface GameServerRpc {

    /**
     * 调用目标游戏服的 {@code GmRemote.<method>(data...)}。
     *
     * @param host   游戏服 RPC 地址（仅允许 IP/localhost）
     * @param port   游戏服 RPC 端口
     * @param method GmRemote 方法名（status/sessions/command/minimalLogin/toggleMinimalLogin）
     * @param data   按名绑定参数
     * @return 方法返回的 JSON 字符串
     * @throws GameServerOfflineException 游戏服不可达/超时/断连退避中
     */
    String call(String host, int port, String method, Map<String, Object> data);

    /** 游戏服不在线（连接失败/超时/退避中）。 */
    class GameServerOfflineException extends RuntimeException {

        public GameServerOfflineException(String message) {
            super(message);
        }

        public GameServerOfflineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
