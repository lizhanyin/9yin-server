package com.nineyin.server;

/** C2S 包处理器：plain[0] 为 opcode，其后为该消息的具体布局。 */
@FunctionalInterface
public interface FxC2SHandler {

    void handle(FxSession session, byte[] plain);
}
