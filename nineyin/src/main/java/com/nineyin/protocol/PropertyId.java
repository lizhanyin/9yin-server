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
    public static final int SCHOOL = 189;          // string（门派，factionProperties 三选一）
    public static final int NEW_SCHOOL = 190;      // string（新门派）
    public static final int FORCE = 194;           // string（江湖势力）
    public static final int SHORTCUT_KEY = 198;
    public static final int SHORTCUT_KEY_1 = 199;
    public static final int SHORTCUT_KEY_2 = 200;
    public static final int HAVE_MAIN_FORM_STYLES = 282;
    public static final int MOVE_SPEED = 383;      // f32
    public static final int WALK_SPEED = 384;      // f32
    public static final int RUN_SPEED = 385;       // f32
    public static final int DROP_HEIGHT_PUB = 388; // f32
    public static final int SPEED_RATIO = 389;     // i32
    public static final int ACTION_SET = 405;
    public static final int LAST_OBJECT = 413;
    public static final int AUTO_SELECT_OBJECT = 414;
    public static final int CAPITAL_TYPE0 = 415;   // i64（金币）
    public static final int CAPITAL_TYPE1 = 416;   // i64（碎银）
    public static final int MODIFY_SKILL_LOCK_TIME = 221;
    public static final int CUR_SKILL_ID = 459;    // string
    public static final int LOGIC_STATE = 462;     // byte: 0=普通 1=战斗 102=打坐
    public static final int DEAD = 463;            // byte
    public static final int HP = 464;
    public static final int MP = 465;
    public static final int SP = 466;              // 怒气
    public static final int HP_RATIO = 468;
    public static final int MP_RATIO = 469;
    public static final int HIT_HP = 480;          // 瘀血层
    public static final int HIT_HP_RATIO = 481;
    public static final int CUR_SKILL_TARGET = 626; // object
    public static final int GRAVITY = 676;          // f32
    public static final int GRAVITY_ADD = 677;      // f32
    public static final int JUMP_SPEED = 680;       // f32
    public static final int BUFFER_INFO_FIRST = 628; // BufferInfo1..24（string），索引 = FIRST + slot - 1
    public static final int BUFFER_INFO_COUNT = 24;
    public static final int QINGGONG_POINT = 691;
    public static final int MAX_QINGGONG_POINT = 692;
    public static final int MAX_QINGGONG_POINT_ADD = 693;
    public static final int CUR_NEIGONG = 652;      // string（当前内功 ConfigID）
    public static final int HP_UP_SPEED = 614;
    public static final int HP_UP_SPEED_ADD = 618;
    public static final int MP_UP_SPEED = 615;
    public static final int MP_UP_SPEED_ADD = 619;
    public static final int HP_HEART_SPEED = 616;
    public static final int HP_HEART_SPEED_ADD = 620;
    public static final int MP_HEART_SPEED = 617;
    public static final int MP_HEART_SPEED_ADD = 621;
    public static final int FACULTY_STATE = 757;    // 0=空闲 2=运功中
    public static final int FACULTY_STYLE = 758;    // 0=普通 1=演武
    public static final int FACULTY_NAME = 759;     // string
    public static final int CUR_FILL_VALUE = 763;
    public static final int TOTAL_FILL_VALUE = 764;
    public static final int FILL_SPEED = 772;
    public static final int FACULTY = 797;          // 修为
    public static final int MAX_HP = 577;
    public static final int MAX_MP = 578;
    public static final int POWER_VALUE = 2285;     // 内功威力
    public static final int MAX_POWER_VALUE = 2286;
    public static final int CONFIG_ID = 1439;
    public static final int RESOURCE = 1440;
    public static final int LEVEL = 1464;
    public static final int TIME_TICK = 1491;      // 本地 0 点起分钟数（UI 时钟）
    public static final int MAX_VISUALS = 1460;
    public static final int NPC_TYPE = 1557;
    public static final int SELECT_STATE = 1559;
    public static final int WEAPON_MODE = 1597;
    public static final int MAX_LEVEL = 2081;      // 视图对象（技能/内功）
    public static final int STATIC_DATA = 2235;    // 视图对象
    public static final int ITEM_TYPE = 1887;      // 视图对象
    public static final int CAN_USE = 2234;        // 视图对象 byte
    public static final int PAUSE_TIME = 2238;     // 视图对象
    public static final int SHOP_ID = 2217;        // 商店视图 string
    public static final int SHOP_PAGE_NUM = 2218;
    public static final int SHOP_PAGE_COUNT = 2219;
    public static final int SHOP_TYPE = 2220;
    public static final int AMOUNT = 1892;         // 商店视图
    public static final int MAX_AMOUNT = 1893;     // 商店视图
    public static final int COLLIDE = 1839;
    public static final int CANT_BE_SELECT = 265;
    public static final int HPRATIO = 468;

    /** BufferInfo 槽位属性索引（1..24）。 */
    public static int bufferInfoSlot(int slot) {
        if (slot < 1 || slot > BUFFER_INFO_COUNT) {
            throw new IllegalArgumentException("invalid BufferInfo slot " + slot);
        }
        return BUFFER_INFO_FIRST + slot - 1;
    }
}
