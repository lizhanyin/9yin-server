package com.nineyin.protocol;

/**
 * 协议属性 ID 常量（官服 2424 条属性表中的绝对索引，C++ 工程已钉死的子集）。
 * 完整表见 C++ 工程 data/official_property_table.txt 与 spec/properties.json。
 */
public final class PropertyId {

    private PropertyId() {
    }

    /** 属性 wire 类型（0x09 属性表与 0x10 增量包的取值编码）。 */
    public static final byte TYPE_BYTE = 0;
    public static final byte TYPE_SHORT = 1;
    public static final byte TYPE_INT = 2;
    public static final byte TYPE_LONG = 3;
    public static final byte TYPE_FLOAT = 4;
    public static final byte TYPE_DOUBLE = 5;
    public static final byte TYPE_STRING = 6;
    public static final byte TYPE_OBJECT = 7;

    public static final int CAMP = 174;
    public static final int NAME = 178;            // 宽字符（UTF-16LE）
    public static final int COLLIDE_RADIUS = 179;
    public static final int TYPE = 181;
    public static final int SEX = 182;
    public static final int STATE = 183;           // string: stand / sitdown / walk / attack
    public static final int PHOTO = 184;
    public static final int FACE = 185;
    public static final int HAIR = 188;
    public static final int SHORTCUT_KEY = 198;
    public static final int SHORTCUT_KEY_1 = 199;
    public static final int SHORTCUT_KEY_2 = 200;
    public static final int HAVE_MAIN_FORM_STYLES = 282;
    public static final int ACTION_SET = 405;
    public static final int LAST_OBJECT = 413;
    public static final int AUTO_SELECT_OBJECT = 414;
    public static final int CUR_SKILL_ID = 459;
    public static final int LOGIC_STATE = 462;     // byte: 0=普通 102=打坐
    public static final int HP = 464;
    public static final int MP_RATIO = 469;
    public static final int CUR_SKILL_TARGET = 626;
    public static final int MOVE_SPEED = 383;
    public static final int CLOTH = 392;
    public static final int PANTS = 393;
    public static final int SHOES = 394;
    public static final int MAX_HP = 577;
    public static final int MAX_MP = 578;
    public static final int CONFIG_ID = 1439;
    public static final int RESOURCE = 1440;
    public static final int LEVEL = 1464;
    public static final int TIME_TICK = 1491;      // 本地 0 点起分钟数（UI 时钟）
    public static final int MAX_VISUALS = 1460;
    public static final int NPC_TYPE = 1557;
    public static final int SELECT_STATE = 1559;
    public static final int WEAPON_MODE = 1597;
}
