package com.nineyin.server.db.role;

/** 对齐 Go internal/role/repository.go 的哨兵错误。 */
public final class RoleRepositoryException extends RuntimeException {

    public enum Code {
        NOT_FOUND("role: not found"),
        NOT_OWNED("role: not owned by account"),
        ACCOUNT_DISABLED("role: account disabled"),
        ROLE_NAME_TAKEN("role: name already taken"),
        ROLE_SLOT_TAKEN("role: account slot already taken"),
        VERSION_CONFLICT("role: version conflict"),
        CORRUPT_SNAPSHOT("role: corrupt snapshot"),
        INVALID_ARGUMENT("role: invalid argument"),
        STORAGE("role: storage unavailable"),
        INVALID_ACCOUNT_KEY("role: empty account key");

        public final String label;

        Code(String label) {
            this.label = label;
        }
    }

    public final Code code;

    public RoleRepositoryException(Code code) {
        super(code.label);
        this.code = code;
    }

    public RoleRepositoryException(Code code, String detail) {
        super(code.label + ": " + detail);
        this.code = code;
    }

    public boolean is(Code expected) {
        return code == expected;
    }
}
