package com.nineyin.server;

import com.nineyin.transport.HexDump;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** C2S opcode 路由表。登录流程（0x02-0x0A）等 handler 逐阶段注册进来。 */
public final class FxOpcodeRouter {

    private static final Logger log = LoggerFactory.getLogger(FxOpcodeRouter.class);

    private final Map<Integer, FxC2SHandler> handlers = new ConcurrentHashMap<>();

    /** 全局已见未知 opcode：首现 INFO 带 hex dump（对齐参考服 unknown C2S opcode=... dump=%x 探针行为），重复 DEBUG 防刷屏。 */
    private final Set<Integer> seenUnknownOpcodes = ConcurrentHashMap.newKeySet();

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
            String hex = Integer.toHexString(opcode);
            if (seenUnknownOpcodes.add(opcode)) {
                log.info("unknown C2S opcode=0x{} len={} dump={} from {}",
                        hex, plain.length, HexDump.dump(plain), session.remoteAddress());
            } else {
                log.debug("unhandled opcode 0x{} from {}", hex, session.remoteAddress());
            }
            return;
        }
        try {
            handler.handle(session, plain);
        } catch (Exception e) {
            log.error("opcode 0x{} handler error", Integer.toHexString(opcode), e);
        }
    }
}
