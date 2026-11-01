package com.nineyin.server.transport;

/** 帧层错误：非法转义 / 超长 / 缺终止符。对齐 Go frame.go 的 ErrInvalidEscape、ErrFrameTooLarge。 */
public class FrameException extends RuntimeException {

    public FrameException(String message) {
        super(message);
    }
}
