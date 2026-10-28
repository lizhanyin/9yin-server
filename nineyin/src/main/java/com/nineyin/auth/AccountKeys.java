package com.nineyin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 登录账号键推导，兼容 Go auth.ParseLoginAccountIdentity：
 * SHA-256( "nineyin/login-account/v1\0" + u32(alen) + msg[0x19..0x19+alen] )，
 * 账号 blob 位于 0x02 登录包偏移 0x19（长度字段在 0x15，u32 LE，
 * 要求 16..256 且 16 对齐）。结果形如 acct:v1:&lt;hex&gt;。
 */
public final class AccountKeys {

    public static final String UNKNOWN = "acct:v1:unknown";
    private static final String DOMAIN = "nineyin/login-account/v1";
    private static final byte[] DOMAIN_BYTES;

    static {
        byte[] d = DOMAIN.getBytes(StandardCharsets.US_ASCII);
        DOMAIN_BYTES = new byte[d.length + 1];
        System.arraycopy(d, 0, DOMAIN_BYTES, 0, d.length);
    }

    private AccountKeys() {
    }

    /** 从 0x02 ClientLogin 明文包推导账号键；包不合法返回 acct:v1:unknown。 */
    public static String parseLoginAccountKey(byte[] plain) {
        if (plain == null || plain.length < 25 || plain[0] != 0x02) {
            return UNKNOWN;
        }
        int alen = (plain[0x15] & 0xFF) | ((plain[0x16] & 0xFF) << 8)
                | ((plain[0x17] & 0xFF) << 16) | ((plain[0x18] & 0xFF) << 24);
        if (alen < 16 || alen > 256 || (alen % 16) != 0 || 25 + alen > plain.length) {
            return UNKNOWN;
        }
        byte[] buf = new byte[DOMAIN_BYTES.length + 4 + alen];
        System.arraycopy(DOMAIN_BYTES, 0, buf, 0, DOMAIN_BYTES.length);
        int off = DOMAIN_BYTES.length;
        buf[off] = (byte) (alen & 0xFF);
        buf[off + 1] = (byte) ((alen >> 8) & 0xFF);
        buf[off + 2] = (byte) ((alen >> 16) & 0xFF);
        buf[off + 3] = (byte) ((alen >> 24) & 0xFF);
        System.arraycopy(plain, 25, buf, off + 4, alen);
        return "acct:v1:" + hex(sha256(buf));
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
