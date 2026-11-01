package com.nineyin.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** 明文帧（byte[]）→ wire 字节。对齐 Go connection.go WriteFrame：EncodeFrame(plain, writeKey)。 */
public final class FrameEncoder extends MessageToByteEncoder<byte[]> {

    private final Connection connection;

    public FrameEncoder(Connection connection) {
        this.connection = connection;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) {
        out.writeBytes(FrameCodec.encode(msg, connection.writeKey()));
    }
}
