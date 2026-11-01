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
import com.nineyin.game.starter.StarterGrants;
import com.nineyin.game.starter.StarterQingGong;
import com.nineyin.gm.GmHub;
import com.nineyin.protocol.LoginMessages;
import com.nineyin.protocol.SceneNames;
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
    /** 选人界面实力档位种子（一期无实力持久化，固定 151=title027 返璞归真）。 */
    static final int ROLE_LIST_POWER_LEVEL = 151;

    private final RoleStoreFacade roleStore;
    private final FacultyStore facultyStore;
    private final GmHub gmHub;
    private final Clock clock;

    public LoginFlow(RoleStoreFacade roleStore, FacultyStore facultyStore, GmHub gmHub, Clock clock) {
        this.roleStore = roleStore;
        this.facultyStore = facultyStore;
        this.gmHub = gmHub;
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
            case SessionMachine.CLIENT_ACTIVITY -> onClientActivity(connection, session);
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

    /**
     * 0x04 帧构造：外观经装备表解析；f23 实力称号（一期种子 151=title027）、
     * f24 角色实际所在场景中文名（scenes.ini+desc.idres）、f25 剧本标题键
     * （introduction_*01_title），f32 保持 book 原值。
     */
    private byte[] oneRoleWithVisual(LoginSession session) {
        List<String> appearance = session.selected.appearance().values();
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        String book = appearance.isEmpty() || appearance.get(0).isEmpty() ? "book1" : appearance.get(0);
        Roles.Location location = session.selected.location();
        return LoginMessages.oneRoleLoginSuccess(java.time.LocalDateTime.now(clock),
                session.selected.name(), appearance,
                RoleStoreFacade.roleFaction(appearance),
                visual.hair(), visual.cloth(), visual.pants(), visual.shoes(),
                LoginMessages.roleListTitle(ROLE_LIST_POWER_LEVEL),
                SceneNames.sceneDisplayName(location.scene().config(), location.scene().resource()),
                SceneNames.bookIntroKey(book));
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
        attachGmSnapshot(connection, session, location);
        log.info("session={} sent property/record tables, views, entry scene and player spawn; waiting for ClientReady",
                session.machine.id());
        return true;
    }

    /** Go main.go gm.attach：选角完成即挂面板会话快照。 */
    private void attachGmSnapshot(Connection connection, LoginSession session, Roles.Location location) {
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.remote = String.valueOf(connection.channel().remoteAddress());
        snapshot.name = session.selected.name();
        snapshot.sceneConfig = location.scene().config();
        snapshot.sceneResource = location.scene().resource();
        snapshot.x = location.position().x();
        snapshot.y = location.position().y();
        snapshot.z = location.position().z();
        snapshot.orient = location.position().orient();
        snapshot.silver = session.player.silver();
        snapshot.sp = 0;
        snapshot.faction = session.player.faction();
        gmHub.attach(snapshot.sessionId, snapshot);
    }

    /** 连接断开（含校验失败断连）：Go defer persistFaculty + gm.detach。 */
    public void onDisconnect(LoginSession session) {
        if (session.selected != null && session.player != null) {
            try {
                facultyStore.save(session.selected.id(), session.player.facultySnapshot());
            } catch (RuntimeException e) {
                log.warn("persist faculty progress: {}", e.getMessage());
            }
        }
        gmHub.detach(session.machine.id());
    }

    /**
     * Go main.go 显式 stage_main 0x09 分支：starter 链（轻功/打坐/内功 View43/
     * 技能 View40/快捷栏）→ 0x27 开关 → 重复就绪守卫 → world.clientReady（无帧）
     * → Ready 相位 → 终帧 vital 同步。注意 grants 在 machine.ready() 之前。
     */
    private boolean onClientReady(Connection connection, LoginSession session) {
        if (session.player != null && !session.qingGongGranted) {
            StarterGrants.grantStarterQingGong(connection);
            session.qingGongGranted = true;
            log.info("session={} granted {} QingGongRec rows and {} View=46 items after explicit ClientReady",
                    session.machine.id(), StarterQingGong.STARTER_QING_GONG_IDS.size(),
                    StarterQingGong.STARTER_QING_GONG_IDS.size() + StarterQingGong.STARTER_QING_GONG_SKILL_IDS.size());
        }
        if (session.player != null) {
            boolean granted = StarterGrants.grantSitcrossShortcut(connection, session.player);
            if (granted) {
                log.info("session={} granted native sitcross shortcut index={}",
                        session.machine.id(), StarterGrants.SITCROSS_SHORTCUT_INDEX);
            }
        }
        if (session.player != null && !session.neigongGranted) {
            StarterGrants.grantStarterProgress(connection, session.player);
            session.neigongGranted = true;
            log.info("session={} granted View=43 inner-power and View=40 starter skills after explicit ClientReady",
                    session.machine.id());
        }
        if (session.player != null) {
            // 重复 0x09（活动场景内的后期 UI 回调）也会重发开关，Go 同此。
            StarterGrants.enableSkillActionSwitch(connection);
        }
        if (session.machine.phase() == SessionMachine.Phase.IN_WORLD) {
            log.info("session={} ignored duplicate ClientReady in active scene", session.machine.id());
            return true;
        }
        SessionException transition = session.machine.ready();
        if (transition != null) {
            log.info("complete client-ready transition: {}", transition.getMessage());
            return false;
        }
        if (session.player != null) {
            connection.writeFrame(session.player.vitalUpdate());
        }
        return true;
    }

    /**
     * Go 0x0A 分支：相位 ENTERING_WORLD 时按兼容就绪屏障提升（客户端省略 0x09
     * 直发 0x0A：Ready + vital + 0x27 开关，不发 starter 链），再走 Activity 相位。
     */
    private boolean onClientActivity(Connection connection, LoginSession session) {
        if (session.machine.phase() == SessionMachine.Phase.ENTERING_WORLD) {
            SessionException readyTransition = session.machine.ready();
            if (readyTransition != null) {
                log.info("complete compatibility ready transition: {}", readyTransition.getMessage());
                return false;
            }
            if (session.player != null) {
                connection.writeFrame(session.player.vitalUpdate());
                StarterGrants.enableSkillActionSwitch(connection);
            }
            log.info("session={} promoted observed 0x0A to compatibility ClientReady", session.machine.id());
        }
        SessionException transition = session.machine.activity();
        if (transition != null) {
            log.info("complete client-activity transition: {}", transition.getMessage());
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
