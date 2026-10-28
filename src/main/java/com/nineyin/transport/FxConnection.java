package com.nineyin.transport;

/**
 * 单连接的双向密钥状态。读写方向密钥独立：客户端会在登录流程的不同时机
 * 通过 on_set_verify / on_set_encode 轮换单方向密钥。
 */
public final class FxConnection {

    private volatile FxKey readKey = FxKey.initial();
    private volatile FxKey writeKey = FxKey.initial();

    public FxKey readKey() {
        return readKey;
    }

    public FxKey writeKey() {
        return writeKey;
    }

    public void setReadKey(FxKey key) {
        this.readKey = key;
    }

    public void setWriteKey(FxKey key) {
        this.writeKey = key;
    }
}
