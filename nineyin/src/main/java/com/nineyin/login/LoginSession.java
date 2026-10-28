package com.nineyin.login;

import com.nineyin.game.PlayerActor;
import com.nineyin.server.FxSession;

import java.util.List;

/**
 * 登录会话状态（对应 C++ handle_client 内的 session_* 局部变量）。
 * 进场景后权威战斗/资源状态在 PlayerActor 上（actor），
 * 这里只承载登录链路与位置等会话字段。
 */
public final class LoginSession {

    public final FxSession fx;

    public String accountKey = "";
    public String roleName = "";
    public List<String> appearance = List.of();
    public boolean hasRole;

    public String spawnConfig = "";
    public String spawnResource = "";
    public float x;
    public float y;
    public float z;
    public float orient;

    public boolean uiBootstrapped;
    public int timeSeq;
    public final long bootSteadyMs = System.nanoTime() / 1_000_000L;

    public int operateMode;
    public int pingCount;

    /** 权威玩家状态（选角时创建）。 */
    public PlayerActor actor;

    public LoginSession(FxSession fx) {
        this.fx = fx;
    }
}
