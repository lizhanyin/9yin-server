package com.nineyin.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nineyin.server.session.SessionMachine;
import org.junit.jupiter.api.Test;

/** 对齐 Go internal/session/state_test.go 的校验矩阵与转移序列。 */
class SessionMachineTest {

    @Test
    void handshakeOrderWithoutRole() {
        SessionMachine machine = new SessionMachine();
        assertEquals(SessionMachine.Phase.CONNECTED, machine.phase());

        assertNull(machine.validateMessage(new byte[]{0x02, 0, 0, 0, 0}));
        assertNull(machine.loginCompleted(false));
        assertEquals(SessionMachine.Phase.ROLE_CREATION, machine.phase());

        assertNull(machine.validate((byte) 0x03));
        assertNotNull(machine.validate((byte) 0x04)); // 创角相位不可直接选角

        assertNull(machine.roleCreated());
        assertEquals(SessionMachine.Phase.ROLE_SELECTION, machine.phase());

        assertNull(machine.roleChosen());
        assertEquals(SessionMachine.Phase.ENTERING_WORLD, machine.phase());

        assertNotNull(machine.validateMessage(new byte[]{0x02, 0, 0, 0, 0})); // 登录只属于 Connected
    }

    @Test
    void readyActivityBarrier() {
        SessionMachine machine = new SessionMachine();
        machine.loginCompleted(true);
        machine.roleChosen();
        assertNull(machine.validateMessage(new byte[]{0x09}));
        assertNotNull(machine.validateMessage(new byte[]{0x09, 0x00})); // 恰 1 字节

        assertNull(machine.ready());
        assertEquals(SessionMachine.Phase.AWAITING_ACTIVITY, machine.phase());

        assertNull(machine.activity());
        assertEquals(SessionMachine.Phase.IN_WORLD, machine.phase());

        // InWorld 重复 ready 幂等放行；重复 activity 也放行。
        assertNull(machine.validate((byte) 0x09));
        assertNull(machine.validate((byte) 0x0A));
    }

    @Test
    void messageLengthRules() {
        SessionMachine machine = new SessionMachine();
        machine.loginCompleted(true);
        byte[] chooseRole = new byte[0x54];
        chooseRole[0] = 0x04;
        assertNull(machine.validateMessage(chooseRole));
        byte[] shortChoose = new byte[0x53];
        shortChoose[0] = 0x04;
        assertNotNull(machine.validateMessage(shortChoose));

        machine.roleChosen();
        byte[] createRole = new byte[0x49];
        createRole[0] = 0x05;
        assertNotNull(machine.validateMessage(createRole)); // EnteringWorld 不接受创角
    }

    @Test
    void unknownOpcodePassesThrough() {
        SessionMachine machine = new SessionMachine();
        assertNull(machine.validate((byte) 0x18));
        assertNull(machine.validate((byte) 0x1E));
        machine.loginCompleted(true);
        assertNull(machine.validate((byte) 0x18));
    }

    @Test
    void closedRejectsEverything() {
        SessionMachine machine = new SessionMachine();
        machine.close();
        assertEquals(SessionMachine.Phase.CLOSED, machine.phase());
        assertNotNull(machine.validate((byte) 0x02));
        assertNotNull(machine.validate((byte) 0x0A));
    }

    @Test
    void reenterSceneRequiresInWorld() {
        SessionMachine machine = new SessionMachine();
        assertNotNull(machine.reenterScene());
        machine.loginCompleted(true);
        machine.roleChosen();
        machine.ready();
        machine.activity();
        assertNull(machine.reenterScene());
        assertEquals(SessionMachine.Phase.ENTERING_WORLD, machine.phase());
    }

    @Test
    void idsAreUniqueAndIncreasing() {
        SessionMachine first = new SessionMachine();
        SessionMachine second = new SessionMachine();
        assertEquals(first.id() + 1, second.id());
    }
}
