package com.nineyin.login;

import com.nineyin.server.FxSession;

import java.util.List;

/**
 * 登录会话状态（对应 C++ handle_client 内的 session_* 局部变量）。
 * 游戏系统阶段会把快捷键/技能/轻功等状态并入 PlayerEntity，这里只承载登录链路所需。
 */
public final class LoginSession {

    /** 快捷键栏位（对应 PlayerShortcut）。 */
    public record Shortcut(int index, String kind, String id) {
    }

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
    public int logicState;
    public int pingCount;
    public List<Shortcut> shortcuts = new java.util.ArrayList<>();

    public LoginSession(FxSession fx) {
        this.fx = fx;
    }
}
