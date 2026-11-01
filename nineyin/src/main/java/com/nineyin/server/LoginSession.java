package com.nineyin.server;

import com.nineyin.game.avatar.PlayerActor;
import com.nineyin.game.avatar.RoleVisual;
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

    LoginSession() {
    }
}
