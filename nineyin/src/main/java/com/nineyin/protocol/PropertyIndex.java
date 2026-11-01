package com.nineyin.protocol;

/**
 * 属性索引常量，数值对齐 Go cmd/protocol-probe：
 * player_progress.go 头部 iota 171 起 37 项（171..207）+ player_actor.go 显式段（208..227）。
 * FxNet2 属性归档只带数字索引，顺序不可变。
 */
public final class PropertyIndex {

    // ---- player_progress.go：propCurNeiGong = 171 + iota（171..207）
    public static final int CUR_NEIGONG = 171;
    public static final int FACULTY = 172;
    public static final int FACULTY_STATE = 173;
    public static final int FACULTY_STYLE = 174;
    public static final int FACULTY_NAME = 175;
    public static final int CUR_FILL_VALUE = 176;
    public static final int TOTAL_FILL_VALUE = 177;
    public static final int CUR_LEVEL = 178;
    public static final int FILL_SPEED = 179;
    public static final int POWER_VALUE = 180;
    public static final int MAX_POWER_VALUE = 181;
    public static final int SP = 182;
    public static final int STR = 183;
    public static final int STA = 184;
    public static final int DEX = 185;
    public static final int ING = 186;
    public static final int SPI = 187;
    public static final int MELEE_POWER = 188;
    public static final int MAGIC_POWER = 189;
    public static final int PHY_HIT = 190;
    public static final int MAGIC_HIT = 191;
    public static final int DODGE = 192;
    public static final int MAX_PARRY = 193;
    public static final int PHY_DEF = 194;
    public static final int STI_DEF = 195;
    public static final int NEG_DEF = 196;
    public static final int MAS_DEF = 197;
    public static final int JUJ_DEF = 198;
    public static final int MIN_MELEE_DAMAGE = 199;
    public static final int MAX_MELEE_DAMAGE = 200;
    public static final int MAX_HP_ADD = 201;
    public static final int MAX_MP_ADD = 202;
    public static final int MAX_LEVEL = 203;
    public static final int STATIC_DATA = 204;
    public static final int ITEM_TYPE = 205;
    public static final int MAX_PARRY_ADD = 206;
    public static final int MIN_MAGIC_DEF_ADD = 207;

    // ---- player_actor.go：紧随 playerProgressFields 之后（208..227）
    public static final int NEIGONG_PK_STATUS = 208;
    public static final int DEAD = 209;
    public static final int CANT_USE_SKILL = 210;
    public static final int CUR_SKILL_ID = 211;
    public static final int PAUSE_TIME = 212;
    public static final int HP_HEART_SPEED = 213;
    public static final int HP_HEART_SPEED_ADD = 214;
    public static final int MP_HEART_SPEED = 215;
    public static final int MP_HEART_SPEED_ADD = 216;
    public static final int HP_UP_SPEED = 217;
    public static final int HP_UP_SPEED_ADD = 218;
    public static final int MP_UP_SPEED = 219;
    public static final int MP_UP_SPEED_ADD = 220;
    public static final int CUR_SKILL_EFFECT_ID = 221;
    public static final int CUR_SKILL_LEVEL = 222;
    public static final int CUR_SKILL_TARGET = 223;
    public static final int MODIFY_SKILL_LOCK_TIME = 224;
    public static final int SKILL_CAN_USE = 225;
    public static final int FORCE = 226;
    public static final int NEW_SCHOOL = 227;

    // ---- player_actor.go buff 槽位
    public static final int RED_SUPER_ARMOR_BUFF_SLOT = 1;
    public static final int WATER_DRIFT_BUFF_SLOT = 2;
    public static final int WATER_JUMP_BUFF_SLOT = 3;
    public static final int INNER_POWER_BUFF_SLOT = 4;
    public static final int YIHUA_SKILL_BUFF_SLOT = 5;
    public static final int YIHUA_SKILL_05_BUFF_SLOT = 6;
    public static final int TAI_JI_STANCE_BUFF_SLOT = 7;
    public static final int TAI_JI_SKILL_05_BUFF_SLOT = 8;
    public static final int TAI_JI_SKILL_07_BUFF_SLOT = 9;
    public static final int YIHUA_PASSIVE_BUFF_SLOT = 10;

    public static final int RED_SUPER_ARMOR_STATIC_DATA = 8682;
    public static final int WATER_DRIFT_STATIC_DATA = 216;
    public static final int WATER_JUMP_STATIC_DATA = 5786;

    private PropertyIndex() {
    }
}
