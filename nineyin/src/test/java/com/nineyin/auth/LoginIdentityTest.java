package com.nineyin.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.util.Objects;

import com.nineyin.server.game.login.LoginIdentity;
import org.junit.jupiter.api.Test;

/** 对齐 Go internal/auth/login_identity_test.go：抓包向量 + 密码位无关性。 */
class LoginIdentityTest {

    private static final String CAPTURED_IDENTITY =
            "acct:v1:112cd36f1e1d2bac408e0428aabef6e399080c2bc33407de6677e60dec97c2a6";

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = LoginIdentityTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void capturedLoginIdentity() throws Exception {
        LoginIdentity identity = LoginIdentity.parse(golden("login_first_flight_plain.bin"));
        assertEquals(CAPTURED_IDENTITY, identity.string());
        assertEquals("acct:v1:112cd36f1e1d", identity.prefix());
    }

    @Test
    void identityIgnoresPasswordBytes() throws Exception {
        byte[] message = golden("login_first_flight_plain.bin");
        // 密码槽位于 account 槽之后：accountLength@0x15，account 槽紧随，然后是 4 字节密码长度。
        int accountLength = (message[0x15] & 0xFF) | ((message[0x16] & 0xFF) << 8);
        int passwordLengthOffset = 0x19 + accountLength;
        byte[] mutated = message.clone();
        mutated[passwordLengthOffset + 4] ^= 0x5A;
        assertEquals(LoginIdentity.parse(message).string(), LoginIdentity.parse(mutated).string());
    }

    @Test
    void rejectsTrailingData() throws Exception {
        byte[] message = golden("login_first_flight_plain.bin");
        byte[] padded = new byte[message.length + 1];
        System.arraycopy(message, 0, padded, 0, message.length);
        assertThrows(IllegalArgumentException.class, () -> LoginIdentity.parse(padded));
    }

    @Test
    void rejectsBadOpcodeAndShortMessage() {
        assertThrows(IllegalArgumentException.class, () -> LoginIdentity.parse(new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> LoginIdentity.parse(new byte[64]));
    }
}
