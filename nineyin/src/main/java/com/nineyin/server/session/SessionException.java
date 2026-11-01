package com.nineyin.server.session;

/** 对齐 Go session.TransitionError / MessageError。 */
public final class SessionException extends RuntimeException {

    private SessionException(String message) {
        super(message);
    }

    public static SessionException transition(SessionMachine.Phase phase, int opcode) {
        return new SessionException(String.format("invalid session transition: phase=%s opcode=0x%02X", phase.label(), opcode));
    }

    public static SessionException message(int opcode, int length, String rule) {
        return new SessionException(String.format("invalid session message: opcode=0x%02X length=%d: %s", opcode, length, rule));
    }
}
