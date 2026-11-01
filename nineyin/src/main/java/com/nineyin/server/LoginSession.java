package com.nineyin.server;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.nineyin.game.avatar.PlayerActor;
import com.nineyin.game.avatar.RoleVisual;
import com.nineyin.game.npc.NpcSpawn;
import com.nineyin.game.scene.SceneLifecycle;
import com.nineyin.role.Roles;
import com.nineyin.session.SessionMachine;

/** 单连接的登录链状态，对齐 Go handle() 的连接级局部状态（machine/account/selected/player）。 */
final class LoginSession {

    final SessionMachine machine = new SessionMachine();
    Roles.Account account;
    Roles.RoleSnapshot selected;
    PlayerActor player;
    RoleVisual visual;
    String accountKey;
    /** Go handle() 的 qingGongGranted/neigongGranted 闭包标志（每连接一次）。 */
    boolean qingGongGranted;
    boolean neigongGranted;
    /** 二期 NPC：场景目录/活跃下标集/生命周期；lastPositionSave 是 2s 节流基准。 */
    SceneLifecycle world;
    List<NpcSpawn> sceneNpcs = List.of();
    Set<Integer> activeNpcs = new java.util.HashSet<>();
    Instant lastPositionSave;
    Roles.Location pendingLocation;

    LoginSession() {
    }
}
