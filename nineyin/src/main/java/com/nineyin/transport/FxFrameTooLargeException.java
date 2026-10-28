package com.nineyin.transport;

public class FxFrameTooLargeException extends FxFrameException {

    public FxFrameTooLargeException(int size, int limit) {
        super("transport frame too large: size=" + size + " limit=" + limit);
    }
}
