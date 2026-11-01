package com.nineyin.server.db.role;

import java.util.List;

/** 角色域模型，对齐 Go internal/role/repository.go。 */
public final class Roles {

    public static final int MAX_ROLE_NAME_RUNES = 64;
    /** Go internal/role/mysql_repository.go:15。 */
    public static final int DEFAULT_MAX_ROLE_SLOTS = 1;

    private Roles() {
    }

    public enum AccountStatus {
        DISABLED(0), ACTIVE(1), LOCKED(2);

        public final int value;

        AccountStatus(int value) {
            this.value = value;
        }

        public static AccountStatus of(int value) {
            for (AccountStatus status : values()) {
                if (status.value == value) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown account status " + value);
        }
    }

    public record Scene(String config, String resource) {
    }

    public record Position(float x, float y, float z, float orient) {
    }

    /** Go role.Role：存档文档中的账号级单角色值。 */
    public record RoleValue(String name, List<String> appearance, Scene scene, Position position) {
    }

    public record Account(long id, String key, AccountStatus status, long version) {
    }

    public record Appearance(int formatVersion, List<String> values, long version) {

        public Appearance(int formatVersion, List<String> values) {
            this(formatVersion, values, 0);
        }
    }

    public record Location(Scene scene, Position position, long version) {
    }

    public record RoleSummary(long id, int slot, String name, boolean deleteRequested, long version) {
    }

    public record RoleSnapshot(long id, int slot, String name, boolean deleteRequested, long version,
                               long accountId, Appearance appearance, Location location) {
    }

    public record NewRole(int slot, String name, Appearance appearance, Location location) {
    }
}
