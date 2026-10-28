package com.nineyin.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 4000 端口 svrlist 纯文本协议。 */
class SvrListHandlerTest {

    @Test
    void svrlistRequestGetsResponseAndClose() {
        EmbeddedChannel channel = new EmbeddedChannel(new SvrListHandler("127.0.0.1", 19061));
        channel.writeInbound(Unpooled.copiedBuffer("svrlist\r\n", StandardCharsets.US_ASCII));

        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] data = new byte[out.readableBytes()];
        out.readBytes(data);
        out.release();
        assertEquals("svrlist #LocalTest $127.0.0.1 19061 0\r\n",
                new String(data, StandardCharsets.US_ASCII));
        channel.runPendingTasks();
        assertFalse(channel.isOpen(), "connection should close after response");
    }

    @Test
    void unknownCommandCloses() {
        EmbeddedChannel channel = new EmbeddedChannel(new SvrListHandler("127.0.0.1", 19061));
        channel.writeInbound(Unpooled.copiedBuffer("hello\r\n", StandardCharsets.US_ASCII));
        assertNull(channel.readOutbound(), "no response for unknown command");
        channel.runPendingTasks();
        assertFalse(channel.isOpen());
    }
}
