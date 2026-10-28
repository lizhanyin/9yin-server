package com.nineyin.server;

/** 连接生命周期回调（会话注册表/落库钩子用）。 */
public interface FxConnectionListener {

    void onConnected(FxSession session);

    void onDisconnected(FxSession session);
}
