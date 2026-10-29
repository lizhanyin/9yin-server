package com.nineyin.server;

import com.nineyin.transport.FxConnection;
import com.nineyin.transport.FxWireLog;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;

/** FxNet2 会话：封装 Netty 通道与双向密钥状态。 */
public final class FxSession {

    public static final AttributeKey<FxSession> ATTR = AttributeKey.valueOf("nineyin.fx.session");

    private final Channel channel;
    private final FxConnection connection;

    FxSession(Channel channel, FxConnection connection) {
        this.channel = channel;
        this.connection = connection;
    }

    /** 裸通道会话（测试用：无编解码管线，send 直写出站队列）。 */
    public static FxSession attach(Channel channel) {
        return new FxSession(channel, new FxConnection());
    }

    public Channel channel() {
        return channel;
    }

    public FxConnection connection() {
        return connection;
    }

    /** 发送一个明文 S2C 包（opcode + 载荷），内部完成 XOR/转义/终止符编码。 */
    public void send(byte[] plain) {
        if (FxWireLog.enabled()) {
            FxWireLog.send(remoteAddress(), plain);
        }
        channel.writeAndFlush(plain);
    }

    public String remoteAddress() {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress() + ":" + addr.getPort();
        }
        return channel.remoteAddress() == null ? "unknown" : String.valueOf(channel.remoteAddress());
    }

    public void close() {
        channel.close();
    }
}
