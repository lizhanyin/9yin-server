package com.nineyin.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/** FxNet2 帧解码器：入站线上字节 → 出站明文 byte[]（opcode + 载荷），一段数据可含多帧。 */
public class FxFrameDecoder extends ByteToMessageDecoder {

    private final FxConnection connection;
    private final FxFrameAccumulator accumulator;

    public FxFrameDecoder(FxConnection connection) {
        this(connection, FxFrame.DEFAULT_MAX_FRAME_SIZE);
    }

    public FxFrameDecoder(FxConnection connection, int maxFrameSize) {
        this.connection = connection;
        this.accumulator = new FxFrameAccumulator(maxFrameSize);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }
        byte[] chunk = new byte[in.readableBytes()];
        in.readBytes(chunk);
        int pos = 0;
        while (pos < chunk.length && accumulator.hasCompleteFrame() == false) {
            int consumed = accumulator.feed(chunk, pos, chunk.length - pos);
            if (consumed == 0) {
                break;
            }
            pos += consumed;
            if (accumulator.hasCompleteFrame()) {
                out.add(accumulator.takeFrame(connection.readKey()));
            }
        }
    }
}
