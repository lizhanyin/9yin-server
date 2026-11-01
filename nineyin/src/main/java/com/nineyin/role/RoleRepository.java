package com.nineyin.role;

import java.util.List;

/** 对齐 Go internal/role 的 AccountRepository + RoleRepository 契约（M2）。 */
public interface RoleRepository extends AutoCloseable {

    Roles.Account findByKey(String key);

    /** 自动开号（无密码认证），对齐 Go ProvisionLegacy。 */
    Roles.Account provisionLegacy(String key);

    List<Roles.RoleSummary> listByAccount(long accountId);

    Roles.RoleSnapshot loadOwned(long accountId, long roleId);

    Roles.RoleSnapshot loadOwnedByName(long accountId, String name);

    Roles.RoleSnapshot create(long accountId, Roles.NewRole draft);

    void requestDelete(long accountId, long roleId);

    /** CAS 外观版本写入，返回新版本号。 */
    long saveAppearance(long roleId, long expectedVersion, Roles.Appearance value);

    /** CAS 位置版本写入，返回新版本号。 */
    long saveLocation(long roleId, long expectedVersion, Roles.Location value);

    @Override
    void close();
}
