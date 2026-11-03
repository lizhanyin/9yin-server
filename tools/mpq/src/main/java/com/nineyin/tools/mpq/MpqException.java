package com.nineyin.tools.mpq;

/** MPQ 归档解析 / 读写失败的统一非受检异常。 */
public class MpqException extends RuntimeException {

    public MpqException(String message) {
        super(message);
    }

    public MpqException(String message, Throwable cause) {
        super(message, cause);
    }
}
