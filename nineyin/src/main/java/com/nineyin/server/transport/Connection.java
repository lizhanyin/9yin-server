package com.nineyin.server.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

/**
 * 单连接的读写密钥与发送语义，对齐 Go internal/transport/connection.go。
 *
 * <p>读/写方向密钥独立可轮换（{@code SetReadKey}/{@code SetWriteKey}），
 * 登录链路全程序不轮换、两侧均为 {@link XorKey#INITIAL_VALUE}。
 * {@link #writeFrame(byte[])} = EncodeFrame + channel 写出。
 */
public final class Connection {

    private final Channel channel;
    private volatile XorKey readKey = XorKey.initial();
    private volatile XorKey writeKey = XorKey.initial();
    private final Object writeLock = new Object();

    public Connection(Channel channel) {
        this.channel = channel;
    }

    public XorKey readKey() {
        return readKey;
    }

    public XorKey writeKey() {
        return writeKey;
    }

    public void setReadKey(XorKey key) {
        this.readKey = key;
    }

    public void setWriteKey(XorKey key) {
        this.writeKey = key;
    }

    public Channel channel() {
        return channel;
    }

    /**
     * connection.go WriteFrame 的管线化等价：写出明文帧，编码（XOR+转义+终止符）
     * 由 pipeline 中的 {@link FrameEncoder} 统一完成，避免双重编码。
     */
    public ChannelFuture writeFrame(byte[] plain) {
        synchronized (writeLock) {
            return channel.writeAndFlush(plain);
        }
    }

    public void close() {
        channel.close();
    }
}
