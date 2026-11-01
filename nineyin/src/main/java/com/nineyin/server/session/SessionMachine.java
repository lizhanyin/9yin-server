package com.nineyin.server.session;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话相位状态机，逐行对齐 Go internal/session/state.go。
 *
 * <p>相位：Connected(0)→RoleCreation(1)/RoleSelection(2)→EnteringWorld(3)→
 * AwaitingActivity(4)→InWorld(5)，Closed(6)。未知 opcode 校验放行（交给业务分发器）；
 * 0x09 在 InWorld 幂等放行；任何转移错误在 main.go 对应断连。
 */
public final class SessionMachine {

    public static final int CLIENT_LOGIN = 0x02;
    public static final int CLIENT_WORLD_INFO = 0x03;
    public static final int CLIENT_CHOOSE_ROLE = 0x04;
    public static final int CLIENT_CREATE_ROLE = 0x05;
    public static final int CLIENT_READY = 0x09;
    public static final int CLIENT_ACTIVITY = 0x0A;

    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final long id;
    private Phase phase = Phase.CONNECTED;

    public SessionMachine() {
        this.id = NEXT_ID.incrementAndGet();
    }

    public long id() {
        return id;
    }

    public Phase phase() {
        return phase;
    }

    /** Go Validate：只校验握手 opcode 的相位矩阵，未知 opcode 返回 null（放行）。 */
    public SessionException validate(int opcode) {
        if (phase == Phase.CLOSED) {
            return SessionException.transition(phase, opcode);
        }
        boolean allowed;
        switch (opcode) {
            case CLIENT_LOGIN:
                allowed = phase == Phase.CONNECTED;
                break;
            case CLIENT_WORLD_INFO:
                allowed = phase == Phase.ROLE_CREATION || phase == Phase.ROLE_SELECTION;
                break;
            case CLIENT_CREATE_ROLE:
                allowed = phase == Phase.ROLE_CREATION;
                break;
            case CLIENT_CHOOSE_ROLE:
                allowed = phase == Phase.ROLE_SELECTION;
                break;
            case CLIENT_READY:
                // InWorld 中重复 ready 幂等放行，拒绝会掐断健康连接。
                allowed = phase == Phase.ENTERING_WORLD || phase == Phase.IN_WORLD;
                break;
            case CLIENT_ACTIVITY:
                // pre-ready 的 0x0A 合法，但不得提前打开场景物化屏障。
                allowed = phase == Phase.ENTERING_WORLD || phase == Phase.AWAITING_ACTIVITY || phase == Phase.IN_WORLD;
                break;
            default:
                return null;
        }
        return allowed ? null : SessionException.transition(phase, opcode);
    }

    /** Go ValidateMessage：先相位后长度；0x02≥5、0x04≥0x54、0x05≥0x49、0x09 恰 1 字节。 */
    public SessionException validateMessage(byte[] message) {
        if (message.length == 0) {
            return SessionException.message(0, 0, "message must contain an opcode");
        }
        int opcode = message[0] & 0xFF;
        SessionException transition = validate(opcode);
        if (transition != null) {
            return transition;
        }
        int minimum = 1;
        int exact = 0;
        switch (opcode) {
            case CLIENT_LOGIN:
                minimum = 5;
                break;
            case CLIENT_CHOOSE_ROLE:
                minimum = 0x54;
                break;
            case CLIENT_CREATE_ROLE:
                minimum = 0x49;
                break;
            case CLIENT_READY:
                exact = 1;
                break;
            default:
                break;
        }
        if (exact != 0 && message.length != exact) {
            return SessionException.message(opcode, message.length, "want exactly " + exact);
        }
        if (message.length < minimum) {
            return SessionException.message(opcode, message.length, "want at least " + minimum);
        }
        return null;
    }

    public SessionException loginCompleted(boolean hasRole) {
        SessionException error = require(Phase.CONNECTED, CLIENT_LOGIN);
        if (error != null) {
            return error;
        }
        phase = hasRole ? Phase.ROLE_SELECTION : Phase.ROLE_CREATION;
        return null;
    }

    public SessionException roleCreated() {
        if (phase != Phase.ROLE_CREATION && phase != Phase.ROLE_SELECTION) {
            return SessionException.transition(phase, CLIENT_CREATE_ROLE);
        }
        phase = Phase.ROLE_SELECTION;
        return null;
    }

    public SessionException roleChosen() {
        SessionException error = require(Phase.ROLE_SELECTION, CLIENT_CHOOSE_ROLE);
        if (error != null) {
            return error;
        }
        phase = Phase.ENTERING_WORLD;
        return null;
    }

    public SessionException ready() {
        SessionException error = validate(CLIENT_READY);
        if (error != null) {
            return error;
        }
        phase = Phase.AWAITING_ACTIVITY;
        return null;
    }

    public SessionException activity() {
        SessionException error = validate(CLIENT_ACTIVITY);
        if (error != null) {
            return error;
        }
        if (phase == Phase.AWAITING_ACTIVITY) {
            phase = Phase.IN_WORLD;
        }
        return null;
    }

    /** 换图（0x0C）：InWorld→EnteringWorld，下一对 ready/activity 复用物化屏障。 */
    public SessionException reenterScene() {
        SessionException error = require(Phase.IN_WORLD, 0x0C);
        if (error != null) {
            return error;
        }
        phase = Phase.ENTERING_WORLD;
        return null;
    }

    public void close() {
        phase = Phase.CLOSED;
    }

    private SessionException require(Phase expected, int opcode) {
        if (phase != expected) {
            return SessionException.transition(phase, opcode);
        }
        return null;
    }

    public enum Phase {
        CONNECTED(0, "connected"),
        ROLE_CREATION(1, "role_creation"),
        ROLE_SELECTION(2, "role_selection"),
        ENTERING_WORLD(3, "entering_world"),
        AWAITING_ACTIVITY(4, "awaiting_activity"),
        IN_WORLD(5, "in_world"),
        CLOSED(6, "closed");

        public final int value;
        private final String label;

        Phase(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
