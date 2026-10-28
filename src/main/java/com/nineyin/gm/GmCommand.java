package com.nineyin.gm;

/** GM 命令（控制台 POST 进队列，游戏侧消费）。 */
public record GmCommand(long sessionId, String action,
                        int silver, int sp,
                        String preset, String destination, String faction,
                        String itemId, int amount) {

    public static final String ACTION_SET_SILVER = "set_silver";
    public static final String ACTION_SET_SP = "set_sp";
    public static final String ACTION_ADD_SP = "add_sp";
    public static final String ACTION_TELEPORT_PRESET = "teleport_preset";
    public static final String ACTION_TELEPORT = "teleport";
    public static final String ACTION_SET_FACTION = "set_faction";
    public static final String ACTION_GIVE_ITEM = "give_item";
    public static final String ACTION_KICK = "kick";
}
