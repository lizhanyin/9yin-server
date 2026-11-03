package com.nineyin.server.transport;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * wire 字节流 → 明文帧（byte[]）。对齐 Go main.go 的 link.ReadFrame() 循环：
 * FrameReader 状态机切帧后用读密钥 XOR，非法转义/超长抛 {@link FrameException}
 * （Netty exceptionCaught → 断连，等价 Go handle() 的 return 断连）。
 */
public final class FrameDecoder extends ByteToMessageDecoder {

    private final Connection connection;
    private final FrameAccumulator accumulator = new FrameAccumulator(FrameCodec.DEFAULT_MAX_FRAME_SIZE);

    public FrameDecoder(Connection connection) {
        this.connection = connection;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        byte[] chunk = new byte[in.readableBytes()];
        in.readBytes(chunk);
        CaptureSink.append(ctx.channel().remoteAddress().toString(), false, chunk, chunk.length);
        for (byte[] frame : accumulator.feed(chunk, 0, chunk.length)) {
            XorKey key = connection.readKey();
            key.apply(frame, 0, frame.length);
            out.add(frame);
        }
    }
}
