package com.nineyin.server;

import java.time.Clock;
import java.util.List;

import com.nineyin.game.avatar.equipment.EquipmentResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.auth.LoginIdentity;
import com.nineyin.game.avatar.neigong.FacultyStore;
import com.nineyin.game.avatar.PlayerActor;
import com.nineyin.game.avatar.RoleVisual;
import com.nineyin.protocol.LoginMessages;
import com.nineyin.protocol.SceneMessages;
import com.nineyin.role.RoleStoreFacade;
import com.nineyin.role.Roles;
import com.nineyin.session.SessionException;
import com.nineyin.session.SessionMachine;
import com.nineyin.transport.Connection;

/**
 * 登录链消息分支，逐行移植 Go cmd/protocol-probe/main.go handle() 的
 * 0x02/0x03/0x05/0x04 与 0x09/0x0A 状态机处理。返回 false 表示断连（Go return）。
 *
 * <p>一期边界：0x09/0x0A 只做相位转移 + vital 回包；starter 链与 NPC 物化属二期。
 */
public final class LoginFlow {

    private static final Logger log = LoggerFactory.getLogger(LoginFlow.class);

    static final int STARTER_SILVER = 99999;

    private final RoleStoreFacade roleStore;
    private final FacultyStore facultyStore;
    private final Clock clock;

    public LoginFlow(RoleStoreFacade roleStore, FacultyStore facultyStore, Clock clock) {
        this.roleStore = roleStore;
        this.facultyStore = facultyStore;
        this.clock = clock;
    }

    /** 处理一帧明文；返回 false 断连。 */
    boolean dispatch(Connection connection, LoginSession session, byte[] plain) {
        SessionException invalid = session.machine.validateMessage(plain);
        if (invalid != null) {
            log.info("session={} reject client message: {}", session.machine.id(), invalid.getMessage());
            return false;
        }
        int opcode = plain[0] & 0xFF;
        return switch (opcode) {
            case SessionMachine.CLIENT_LOGIN -> onLogin(connection, session, plain);
            case SessionMachine.CLIENT_WORLD_INFO -> onWorldInfo(connection);
            case SessionMachine.CLIENT_CREATE_ROLE -> onCreateRole(connection, session, plain);
            case SessionMachine.CLIENT_CHOOSE_ROLE -> onChooseRole(connection, session);
            case SessionMachine.CLIENT_READY -> onClientReady(connection, session);
            case SessionMachine.CLIENT_ACTIVITY -> onClientActivity(session);
            default -> true; // 未分类 opcode 放行业务层（一期无业务层）
        };
    }

    private boolean onLogin(Connection connection, LoginSession session, byte[] plain) {
        LoginIdentity identity;
        try {
            identity = LoginIdentity.parse(plain);
        } catch (IllegalArgumentException e) {
            log.info("session={} reject login structure: {}", session.machine.id(), e.getMessage());
            return false;
        }
        session.accountKey = identity.string();
        log.info("session={} opcode=0x02 decoded_len={} account={}",
                session.machine.id(), plain.length, identity.prefix());

        RoleStoreFacade.LoginResult result;
        try {
            result = roleStore.login(session.accountKey);
        } catch (RuntimeException e) {
            log.warn("load account role: {}", e.getMessage());
            return false;
        }
        session.account = result.account();
        session.selected = result.snapshot();
        boolean exists = session.selected != null;
        byte[] payload = exists ? oneRoleWithVisual(session)
                : LoginMessages.noRoleLoginSuccess(java.time.LocalDateTime.now(clock));
        connection.writeFrame(payload);
        SessionException transition = session.machine.loginCompleted(exists);
        if (transition != null) {
            log.info("complete login transition: {}", transition.getMessage());
            return false;
        }
        log.info("session={} sent ServerPlayerRoles(opcode=0x04, role_exists={})", session.machine.id(), exists);
        return true;
    }

    /** Go oneRoleLoginSuccess 内部的 resolveRoleVisual：外观路径经装备表解析。 */
    private byte[] oneRoleWithVisual(LoginSession session) {
        RoleVisual visual = RoleVisual.resolve(
                session.selected.appearance().values(), EquipmentResolver.loadDefault());
        return LoginMessages.oneRoleLoginSuccess(java.time.LocalDateTime.now(clock),
                session.selected.name(), session.selected.appearance().values(),
                RoleStoreFacade.roleFaction(session.selected.appearance().values()),
                visual.hair(), visual.cloth(), visual.pants(), visual.shoes());
    }

    private boolean onWorldInfo(Connection connection) {
        connection.writeFrame(LoginMessages.worldInfo(0, "0,book1,0,book2,0,book4,0,book5,0,book8,0,0"));
        return true;
    }

    private boolean onCreateRole(Connection connection, LoginSession session, byte[] plain) {
        String name = LoginMessages.createRoleName(plain);
        if (name.isEmpty()) {
            name = "本地角色";
        }
        List<String> appearance = LoginMessages.createRoleStrings(plain);
        try {
            session.selected = roleStore.create(session.account, name, appearance);
        } catch (RuntimeException e) {
            log.warn("persist created role: {}", e.getMessage());
            return false;
        }
        connection.writeFrame(oneRoleWithVisual(session));
        SessionException transition = session.machine.roleCreated();
        if (transition != null) {
            log.info("complete create-role transition: {}", transition.getMessage());
            return false;
        }
        log.info("session={} sent ServerPlayerRoles with created role {}", session.machine.id(), name);
        return true;
    }

    /** 选角进世界：0x09 表 → 0x0A 表 → 8×0x15 → 0x0B → 0x0D/0x1F/0x10 → RoleChosen。 */
    private boolean onChooseRole(Connection connection, LoginSession session) {
        if (session.selected == null) {
            log.info("session={} choose role without an account-owned role", session.machine.id());
            return false;
        }
        Roles.Location location = session.selected.location();
        location = new Roles.Location(normalizeClientScene(location.scene()), location.position(), location.version());

        session.visual = RoleVisual.resolve(
                session.selected.appearance().values(), EquipmentResolver.loadDefault());
        session.player = PlayerActor.create(session.selected.name(), STARTER_SILVER, clock);
        session.player.setFaction(RoleStoreFacade.roleFaction(session.selected.appearance().values()));

        var saved = facultyStore.load(session.selected.id());
        if (saved != null) {
            session.player.restoreFaculty(saved, clock.instant());
            log.info("session={} restored persistent faculty role={} name={}",
                    session.machine.id(), session.selected.id(), saved.facultyName());
        } else {
            facultyStore.save(session.selected.id(), session.player.facultySnapshot());
            log.info("session={} initialized persistent normal faculty role={}",
                    session.machine.id(), session.selected.id());
        }

        connection.writeFrame(SceneMessages.propertyTableFrame());
        connection.writeFrame(SceneMessages.recordTableFrame());
        for (SceneMessages.ViewSpec view : SceneMessages.starterBagViews()) {
            connection.writeFrame(SceneMessages.createView(view));
        }
        // Go world.begin：场景世代重置，不发帧；一期无 world 内核，等价空操作。
        connection.writeFrame(SceneMessages.entryScene(location.scene()));
        connection.writeFrame(session.player.addObject(location.position(), session.visual));
        connection.writeFrame(SceneMessages.serverLocation(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, location.position()));
        connection.writeFrame(session.player.vitalUpdate());
        // NPC viewport 仅注册不上线（activity 屏障后物化）——一期无 NPC 目录，跳过。

        SessionException transition = session.machine.roleChosen();
        if (transition != null) {
            log.info("complete choose-role transition: {}", transition.getMessage());
            return false;
        }
        log.info("session={} sent property/record tables, views, entry scene and player spawn; waiting for ClientReady",
                session.machine.id());
        return true;
    }

    /** 一期：相位转移 + 权威 vital；Go 的 starter 链（轻功/View43/40/0x27）属二期。 */
    private boolean onClientReady(Connection connection, LoginSession session) {
        SessionException transition = session.machine.ready();
        if (transition != null) {
            log.info("ready transition: {}", transition.getMessage());
            return false;
        }
        if (session.player != null) {
            connection.writeFrame(session.player.vitalUpdate());
        }
        return true;
    }

    /** 一期：相位转移；NPC 物化（reconcileViewport）属二期。 */
    private boolean onClientActivity(LoginSession session) {
        SessionException transition = session.machine.activity();
        if (transition != null) {
            log.info("activity transition: {}", transition.getMessage());
            return false;
        }
        return true;
    }

    /** Go scene_catalog_registry.go normalizeClientScene 的三条地图特例。 */
    static Roles.Scene normalizeClientScene(Roles.Scene scene) {
        if (scene.config().equalsIgnoreCase("ini\\scene\\scene09_SaiBeiCaoYuan")) {
            scene = new Roles.Scene(scene.config(), "scene09");
        }
        if (scene.config().equalsIgnoreCase("ini\\scene\\scene07_MeiHuaLing")) {
            scene = new Roles.Scene(scene.config(), "scene07");
        }
        if (scene.resource().equals("clone004_wl_1") || scene.config().equals("ini\\scene\\clone004_wl_1")) {
            scene = new Roles.Scene("ini\\scene\\clone004_wl", "clone004_wl");
        }
        return scene;
    }
}
