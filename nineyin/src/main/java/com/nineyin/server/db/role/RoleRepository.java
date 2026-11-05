package com.nineyin.server.db.role;

import java.util.List;

/** 对齐 Go internal/role 的 AccountRepository + RoleRepository 契约（M2）。 */
public interface RoleRepository extends AutoCloseable {

    Roles.Account findByKey(String key);

    /** 自动开号（无密码认证），对齐 Go ProvisionLegacy。 */
    Roles.Account provisionLegacy(String key);

    List<Roles.RoleSummary> listByAccount(long accountId);

    Roles.RoleSnapshot loadOwned(long accountId, long roleId);

    Roles.RoleSnapshot loadOwnedByName(long accountId, String name);

    /** 全服按名查角色摘要（GM 邮件等跨账号寻址；名字全服唯一）；null = 不存在。 */
    Roles.RoleSummary findRoleByName(String name);

    Roles.RoleSnapshot create(long accountId, Roles.NewRole draft);

    void requestDelete(long accountId, long roleId);

    /** CAS 外观版本写入，返回新版本号。 */
    long saveAppearance(long roleId, long expectedVersion, Roles.Appearance value);

    /** CAS 位置版本写入，返回新版本号。 */
    long saveLocation(long roleId, long expectedVersion, Roles.Location value);

    @Override
    void close();
}
