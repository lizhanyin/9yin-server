package com.nineyin.auth;

import com.nineyin.protocol.PacketWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountKeysTest {

    private static byte[] loginPacket(int alen, byte[] blob) {
        PacketWriter w = new PacketWriter().u8(0x02);
        while (w.size() < 0x15) {
            w.u8(0);
        }
        w.u32(alen).raw(blob);
        return w.toByteArray();
    }

    @Test
    void derivesStableKey() {
        byte[] blob = new byte[32];
        blob[0] = 0x11;
        byte[] packet = loginPacket(32, blob);
        String key1 = AccountKeys.parseLoginAccountKey(packet);
        String key2 = AccountKeys.parseLoginAccountKey(packet);
        assertTrue(key1.startsWith("acct:v1:"), key1);
        assertEquals(8 + 64, key1.length());
        assertEquals(key1, key2);
    }

    @Test
    void differentBlobDifferentKey() {
        byte[] a = new byte[16];
        byte[] b = new byte[16];
        b[15] = 1;
        assertNotEquals(AccountKeys.parseLoginAccountKey(loginPacket(16, a)),
                AccountKeys.parseLoginAccountKey(loginPacket(16, b)));
    }

    @Test
    void rejectsMalformedPackets() {
        assertEquals(AccountKeys.UNKNOWN, AccountKeys.parseLoginAccountKey(new byte[]{0x02}));
        assertEquals(AccountKeys.UNKNOWN, AccountKeys.parseLoginAccountKey(new byte[40]));
        // opcode 不对
        byte[] wrongOp = loginPacket(32, new byte[32]);
        wrongOp[0] = 0x03;
        assertEquals(AccountKeys.UNKNOWN, AccountKeys.parseLoginAccountKey(wrongOp));
        // 长度非 16 对齐
        assertEquals(AccountKeys.UNKNOWN,
                AccountKeys.parseLoginAccountKey(loginPacket(20, new byte[20])));
        // 长度超出包
        assertEquals(AccountKeys.UNKNOWN,
                AccountKeys.parseLoginAccountKey(loginPacket(64, new byte[32])));
    }

    @Test
    void matchesSha256OfDomainAndBlob() {
        byte[] blob = new byte[16];
        blob[3] = 0x42;
        byte[] expected = AccountKeys.sha256(java.nio.ByteBuffer.allocate(24 + 1 + 4 + 16)
                .put("nineyin/login-account/v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .put((byte) 0)
                .put((byte) 16).put((byte) 0).put((byte) 0).put((byte) 0)  // u32 alen 小端
                .put(blob)
                .array());
        StringBuilder hex = new StringBuilder();
        for (byte b : expected) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("acct:v1:" + hex,
                AccountKeys.parseLoginAccountKey(loginPacket(16, blob)));
    }
}
