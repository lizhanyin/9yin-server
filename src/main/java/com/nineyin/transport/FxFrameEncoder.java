package com.nineyin.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** FxNet2 帧编码器：出站明文 byte[] → 线上字节（XOR + 转义 + EE EE 终止符）。 */
public class FxFrameEncoder extends MessageToByteEncoder<byte[]> {

    private final FxConnection connection;

    public FxFrameEncoder(FxConnection connection) {
        super(byte[].class);
        this.connection = connection;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) {
        out.writeBytes(FxFrame.encode(msg, connection.writeKey()));
    }
}
