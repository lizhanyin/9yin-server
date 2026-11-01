package com.nineyin.gm;

import com.alibaba.fastjson.JSONObject;

/**
 * GM 命令（控制台 POST 进队列，游戏侧消费）。
 * 参数整体透传：面板动作已达数十种（guanYin/gold/pkMode/stage/cardId/…），
 * 由消费端按 action 自行取值。
 */
public record GmCommand(long sessionId, String action, JSONObject params) {

    public static final String ACTION_KICK = "kick";
    public static final String ACTION_MINIMAL_LOGIN = "minimal_login";

    public GmCommand {
        params = params != null ? params : new JSONObject();
    }

    public int intParam(String key, int def) {
        Integer v = params.getInteger(key);
        return v != null ? v : def;
    }

    public String strParam(String key) {
        String v = params.getString(key);
        return v != null ? v : "";
    }
}
