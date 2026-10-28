package com.nineyin.transport;

public class FxInvalidEscapeException extends FxFrameException {

    public FxInvalidEscapeException(int next) {
        super(String.format("invalid EE escape: EE %02X", next));
    }
}
