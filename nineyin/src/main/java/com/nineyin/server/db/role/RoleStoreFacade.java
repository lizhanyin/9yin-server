package com.nineyin.server.db.role;

import java.util.ArrayList;
import java.util.List;

/**
 * 存档门面，逐行对齐 Go cmd/protocol-probe/role_store.go。
 *
 * <p>login：FindByKey 未命中则 ProvisionLegacy 自动开号，要求恰好 1 个 Slot==0 的角色；
 * create：Slot=0 + 默认外观（values[8]=force_yihua）+ 默认出生点（移花宫）；
 * saveLocation/saveFaction：CAS 乐观版本写回并同步 snapshot。
 */
public final class RoleStoreFacade implements AutoCloseable {

    public static final String DEFAULT_ROLE_FACTION = "force_yihua";

    private final RoleRepository repository;

    public RoleStoreFacade(RoleRepository repository) {
        this.repository = repository;
    }

    public RoleRepository repository() {
        return repository;
    }

    /** Go roleStore.login：返回 (account, snapshot|null)。 */
    public LoginResult login(String key) {
        Roles.Account account;
        try {
            account = repository.findByKey(key);
        } catch (RoleRepositoryException notFound) {
            if (!notFound.is(RoleRepositoryException.Code.NOT_FOUND)) {
                throw notFound;
            }
            account = repository.provisionLegacy(key);
        }
        List<Roles.RoleSummary> summaries = repository.listByAccount(account.id());
        if (summaries.isEmpty()) {
            return new LoginResult(account, null);
        }
        if (summaries.size() != 1 || summaries.get(0).slot() != 0) {
            throw new IllegalStateException("role store: unsupported role layout for account " + account.id());
        }
        Roles.RoleSnapshot snapshot = repository.loadOwned(account.id(), summaries.get(0).id());
        return new LoginResult(account, snapshot);
    }

    public Roles.RoleSnapshot create(Roles.Account account, String name, List<String> appearance) {
        List<String> values = defaultRoleAppearance(appearance);
        return repository.create(account.id(), new Roles.NewRole(0, name,
                new Roles.Appearance(1, values), defaultRoleLocation()));
    }

    public static List<String> defaultRoleAppearance(List<String> appearance) {
        List<String> values = appearance == null ? new ArrayList<>() : new ArrayList<>(appearance);
        while (values.size() <= 8) {
            values.add("");
        }
        values.set(8, DEFAULT_ROLE_FACTION);
        return values;
    }

    public static String roleFaction(List<String> appearance) {
        if (appearance != null && appearance.size() > 8 && !appearance.get(8).isEmpty()) {
            return appearance.get(8);
        }
        return DEFAULT_ROLE_FACTION;
    }

    /** CAS 保存位置，返回带新版本的 snapshot（Go 原地更新，Java 返回新引用）。 */
    public Roles.RoleSnapshot saveLocation(Roles.RoleSnapshot snapshot, Roles.Location location) {
        if (snapshot == null) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
        }
        long next = repository.saveLocation(snapshot.id(), snapshot.location().version(), location);
        return new Roles.RoleSnapshot(snapshot.id(), snapshot.slot(), snapshot.name(), snapshot.deleteRequested(),
                snapshot.version(), snapshot.accountId(), snapshot.appearance(),
                new Roles.Location(location.scene(), location.position(), next));
    }

    /** CAS 保存门派（appearance[8]），返回带新版本的 snapshot。 */
    public Roles.RoleSnapshot saveFaction(Roles.RoleSnapshot snapshot, String faction) {
        if (snapshot == null) {
            throw new RoleRepositoryException(RoleRepositoryException.Code.NOT_FOUND);
        }
        List<String> values = new ArrayList<>(snapshot.appearance().values());
        while (values.size() <= 8) {
            values.add("");
        }
        values.set(8, faction);
        int formatVersion = snapshot.appearance().formatVersion();
        if (formatVersion == 0) {
            formatVersion = 1;
        }
        long next = repository.saveAppearance(snapshot.id(), snapshot.appearance().version(),
                new Roles.Appearance(formatVersion, values));
        return new Roles.RoleSnapshot(snapshot.id(), snapshot.slot(), snapshot.name(), snapshot.deleteRequested(),
                snapshot.version(), snapshot.accountId(),
                new Roles.Appearance(formatVersion, values, next), snapshot.location());
    }

    /** Go defaultRoleLocation：relive_info.ini 的 force_yihua HomePointschool09A。 */
    public static Roles.Location defaultRoleLocation() {
        return new Roles.Location(
                new Roles.Scene("ini\\scene\\school09_YiHuaGong", "school09"),
                new Roles.Position(340.160f, 47.979f, 657.284f, 2.588f),
                1);
    }

    @Override
    public void close() {
        repository.close();
    }

    public record LoginResult(Roles.Account account, Roles.RoleSnapshot snapshot) {
    }
}
