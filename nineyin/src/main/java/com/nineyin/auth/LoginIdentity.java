package com.nineyin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 现代客户端登录报文的兼容身份解析，逐行对齐 Go internal/auth/login_identity.go。
 *
 * <p>身份摘要 = SHA-256("nineyin/login-account/v1" || 0x00 || LE32(accountLength) || account 槽)，
 * id = "acct:v1:" + hex(digest)。密码槽只消费不参与身份。
 */
public final class LoginIdentity {

    public static final String PREFIX = "acct:v1:";
    private static final String DOMAIN = "nineyin/login-account/v1";
    private static final int LOGIN_HEADER_SIZE = 25;
    private static final int ACCOUNT_MIN = 16;
    private static final int ACCOUNT_MAX = 256;
    private static final int SECRET_MIN = 16;
    private static final int SECRET_MAX = 512;

    private final String id;

    private LoginIdentity(String id) {
        this.id = id;
    }

    public String string() {
        return id;
    }

    public String prefix() {
        int prefixLength = PREFIX.length() + 12;
        return id.length() <= prefixLength ? id : id.substring(0, prefixLength);
    }

    public static LoginIdentity parse(byte[] message) {
        if (message.length < LOGIN_HEADER_SIZE || (message[0] & 0xFF) != 0x02) {
            throw new IllegalArgumentException("login: invalid message: missing login header");
        }
        int cursor = LOGIN_HEADER_SIZE;
        long accountLength = readU32(message, 0x15);
        validateSlotLength("account", accountLength, ACCOUNT_MIN, ACCOUNT_MAX);
        long accountEnd = advance(cursor, accountLength, message.length);
        if (accountEnd < 0) {
            throw new IllegalArgumentException("login: invalid message: truncated account slot");
        }

        MessageDigest digest = sha256();
        digest.update(DOMAIN.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update((byte) (accountLength & 0xFF));
        digest.update((byte) ((accountLength >>> 8) & 0xFF));
        digest.update((byte) ((accountLength >>> 16) & 0xFF));
        digest.update((byte) ((accountLength >>> 24) & 0xFF));
        digest.update(message, cursor, (int) accountLength);
        byte[] accountDigest = digest.digest();
        StringBuilder hex = new StringBuilder(accountDigest.length * 2);
        for (byte b : accountDigest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        cursor = (int) accountEnd;

        long passwordLength = readLength(message, cursor, "password length");
        validateSlotLength("password", passwordLength, SECRET_MIN, SECRET_MAX);
        cursor = (int) advance(cursor + 4, passwordLength, message.length);
        if (cursor < 0) {
            throw new IllegalArgumentException("login: invalid message: truncated password slot");
        }

        // client-system/type：裸 u32，值不参与身份。
        cursor = readLengthNext(message, cursor, "client type");

        long loginStringLength = readLength(message, cursor, "login-string length");
        validateSlotLength("login string", loginStringLength, SECRET_MIN, SECRET_MAX);
        cursor = (int) advance(cursor + 4, loginStringLength, message.length);
        if (cursor < 0) {
            throw new IllegalArgumentException("login: invalid message: truncated login-string slot");
        }

        // login type 必须是最后 4 字节，拒绝尾部数据。
        cursor = readLengthNext(message, cursor, "login type");
        if (cursor != message.length) {
            throw new IllegalArgumentException("login: invalid message: trailing login data");
        }
        return new LoginIdentity(PREFIX + hex);
    }

    private static void validateSlotLength(String name, long length, int minimum, int maximum) {
        if (length < minimum || length > maximum || length % 16 != 0) {
            throw new IllegalArgumentException("login: invalid message: invalid " + name + " slot length");
        }
    }

    private static long readU32(byte[] message, int offset) {
        return (message[offset] & 0xFFL)
                | (message[offset + 1] & 0xFFL) << 8
                | (message[offset + 2] & 0xFFL) << 16
                | (message[offset + 3] & 0xFFL) << 24;
    }

    private static long readLength(byte[] message, int cursor, String what) {
        if (message.length - cursor < 4) {
            throw new IllegalArgumentException("login: invalid message: missing " + what);
        }
        return readU32(message, cursor);
    }

    private static int readLengthNext(byte[] message, int cursor, String what) {
        readLength(message, cursor, what);
        return cursor + 4;
    }

    /** Go advance：越界返回 -1，否则返回 cursor+length。 */
    private static long advance(int cursor, long length, int limit) {
        if (cursor < 0 || cursor > limit || length > (limit - cursor)) {
            return -1;
        }
        return cursor + length;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
