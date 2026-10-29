package com.nineyin.transport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxServerHandler;
import com.nineyin.server.FxSession;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线缆日志格式与挂钩验证：
 * HexDump 以 Go 真实日志（logs/startup-*.out.log）的基准行做逐字节断言，
 * 保证两侧转储可直接 diff；FxWireLog 验证摘要/转储分级与登录帧 0x02 不转储。
 */
class FxWireLogTest {

    /** Go 日志实测样本：startup-20260726-185352.out.log 的一行 0x18 心跳帧转储。 */
    private static final byte[] GO_SAMPLE = {
            0x18, 0x70, 0x29, 0x6a, 0x01, 0x00, (byte) 0xb5, 0x20, 0x00, 0x00, 0x00, 0x00,
    };

    private Logger wire;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        wire = (Logger) LoggerFactory.getLogger("nineyin.wire");
        originalLevel = wire.getLevel();
        appender = new ListAppender<>();
        appender.start();
        wire.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        wire.setLevel(originalLevel);
        wire.detachAppender(appender);
        appender.stop();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getMessage).collect(Collectors.toList());
    }

    @Test
    void hexDumpMatchesGoByteForByte() {
        assertEquals("00000000  18 70 29 6a 01 00 b5 20  00 00 00 00              |.p)j... ....|",
                HexDump.dump(GO_SAMPLE));
    }

    @Test
    void hexDumpEmpty() {
        assertEquals("", HexDump.dump(new byte[0]));
    }

    @Test
    void hexDumpMultiLine() {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) 0xab;
        }
        String dump = HexDump.dump(data);
        String[] lines = dump.split("\n", -1);
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("00000000  ab ab ab ab ab ab ab ab  ab ab ab ab ab ab ab ab  |"));
        assertTrue(lines[0].endsWith("|................|"));
        assertTrue(lines[1].startsWith("00000010  ab ab ab ab "));
        assertTrue(lines[1].endsWith("|....|"));
    }

    @Test
    void recvSummaryPlusDumpAtTrace() {
        wire.setLevel(Level.TRACE);
        FxWireLog.recv("127.0.0.1:9331", GO_SAMPLE);
        assertEquals(List.of("recv remote=127.0.0.1:9331 opcode=0x18 decoded_len=12\n"
                + "00000000  18 70 29 6a 01 00 b5 20  00 00 00 00              |.p)j... ....|"),
                messages());
    }

    @Test
    void recvLoginFrameNeverDumped() {
        wire.setLevel(Level.TRACE);
        byte[] login = {0x02, 0x01, 0x02, 0x03};
        FxWireLog.recv("127.0.0.1:9331", login);
        assertEquals(List.of("recv remote=127.0.0.1:9331 opcode=0x2 decoded_len=4"), messages());
    }

    @Test
    void debugLevelKeepsSummaryOnly() {
        wire.setLevel(Level.DEBUG);
        FxWireLog.send("127.0.0.1:9331", GO_SAMPLE);
        assertEquals(List.of("send remote=127.0.0.1:9331 opcode=0x18 len=12"), messages());
    }

    @Test
    void offLevelLogsNothing() {
        wire.setLevel(Level.OFF);
        FxWireLog.recv("127.0.0.1:9331", GO_SAMPLE);
        FxWireLog.send("127.0.0.1:9331", GO_SAMPLE);
        assertTrue(messages().isEmpty());
        assertFalse(FxWireLog.enabled());
    }

    @Test
    void inboundHookedIntoServerHandler() {
        wire.setLevel(Level.DEBUG);
        EmbeddedChannel channel = new EmbeddedChannel(
                new FxServerHandler(new FxOpcodeRouter(), new FxConnection()));
        channel.writeInbound(GO_SAMPLE.clone());
        assertEquals(1, messages().size());
        assertTrue(messages().get(0).startsWith("recv remote=embedded"));
        channel.finishAndReleaseAll();
    }

    @Test
    void outboundHookedIntoSessionSend() {
        wire.setLevel(Level.DEBUG);
        EmbeddedChannel channel = new EmbeddedChannel();
        FxSession.attach(channel).send(GO_SAMPLE.clone());
        assertEquals(List.of("send remote=embedded opcode=0x18 len=12"), messages());
        channel.finishAndReleaseAll();
    }
}
