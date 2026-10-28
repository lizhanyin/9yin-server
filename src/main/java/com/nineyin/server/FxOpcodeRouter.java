package com.nineyin.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** C2S opcode 路由表。登录流程（0x02-0x0A）等 handler 逐阶段注册进来。 */
public final class FxOpcodeRouter {

    private static final Logger log = LoggerFactory.getLogger(FxOpcodeRouter.class);

    private final Map<Integer, FxC2SHandler> handlers = new ConcurrentHashMap<>();

    public void register(int opcode, FxC2SHandler handler) {
        FxC2SHandler old = handlers.put(opcode, handler);
        if (old != null) {
            log.warn("opcode 0x{} handler overwritten", Integer.toHexString(opcode));
        }
    }

    public void dispatch(FxSession session, byte[] plain) {
        if (plain.length == 0) {
            return;
        }
        int opcode = plain[0] & 0xFF;
        FxC2SHandler handler = handlers.get(opcode);
        if (handler == null) {
            log.debug("unhandled opcode 0x{} from {}", Integer.toHexString(opcode), session.remoteAddress());
            return;
        }
        try {
            handler.handle(session, plain);
        } catch (Exception e) {
            log.error("opcode 0x{} handler error", Integer.toHexString(opcode), e);
        }
    }
}
