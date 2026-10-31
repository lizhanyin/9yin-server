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
    public static final int NEW_TERMS_LAST_SIGN_TIME = 269;    // 用户须知最后签署时间（i32 epoch 秒，0=未签署）
    public static final int NEW_TERMS_SIGN_COUNT = 270;        // 用户须知签署次数
    public static final int NEW_TERMS_REPAIR_SIGN_COUNT = 271; // 用户须知补签次数
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
    public static final int CAPITAL_TYPE2 = 417;   // i64（官银 guanYin，GM set_guanyin）
    public static final int MODIFY_SKILL_LOCK_TIME = 221;
    public static final int PK_MODE = 436;         // short：0=和平 1=善恶 2=杀戮（msgId=291 切换）
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
    public static final int FACULTY_STYLE = 758;    // 1=普通内修 2=演武（Go facultyStyle*）
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
    /** 以下按本服 0x09 官服表（official_property_table.txt）：470=InParry、473=InManualParry。
     * 注意新参考 exe 自己的表整体偏移 +1（471/474），帧索引必须与本服下发的表一致。 */
    public static final int IN_PARRY = 470;         // 格挡中（右键 54/主动 218 同步）
    public static final int IN_MANUAL_PARRY = 473;  // 手动格挡中

    /** GM 面板动作所需属性（名字按 menu2 参考服换算到本服 0x09 表 index；
     * menu2 exe 的属性表与本服表是两个版本，数字不可直抄，见 docs/gm-menu2-actions.md）。 */
    public static final int TITLE_ID = 359;         // i32（当前佩戴称号）
    public static final int HAS_LEARN_EXT_SKILL = 434; // u8（set_pk_mode 附带）
    public static final int JUSTICE = 320;          // i32 善
    public static final int EVIL = 321;             // i32 恶
    public static final int FREE_BASE = 322;        // i32 基础狂邪
    public static final int CHARACTER_FLAG = 318;   // i32 性格标记
    public static final int CHARACTER_VALUE = 319;  // i32 性格值
    public static final int POWER_LEVEL = 210;      // i32 实力等级
    public static final int STR = 484;              // i32 臂力
    public static final int ING = 485;              // i32 内息
    public static final int DEX = 486;              // i32 身法
    public static final int SPI = 487;              // i32 罡气
    public static final int STA = 488;              // i32 体魄

    /** 人物属性面板战斗块（Go playerProgressFields 同名属性 → 本服官服表索引）。 */
    public static final int MAX_PARRY = 475;        // i32 招架上限（面板值；耐力消耗另走 parryEndurance）
    public static final int MAX_PARRY_ADD = 476;    // i32 招架上限加成（运功内功 varprop）
    public static final int MELEE_POWER = 499;      // i32 外功攻击
    public static final int MAGIC_POWER = 501;      // i32 内功攻击
    public static final int MAX_MELEE_DAMAGE = 508; // i32 近战伤害上限
    public static final int MIN_MELEE_DAMAGE = 509; // i32 近战伤害下限
    public static final int PHY_HIT = 555;          // i32 外功命中
    public static final int MAGIC_HIT = 558;        // i32 内功命中
    public static final int DODGE = 561;            // i32 闪避
    public static final int MAX_HP_ADD = 580;       // i32 上限加成（装备词缀 + 运功内功）
    public static final int MAX_MP_ADD = 581;
    public static final int PHY_DEF = 583;          // i32 外功防御
    public static final int STI_DEF = 585;          // i32 刚防御
    public static final int JUJ_DEF = 586;          // i32 柔防御
    public static final int NEG_DEF = 587;          // i32 阴防御
    public static final int MAS_DEF = 588;          // i32 阳防御
    public static final int MIN_MAGIC_DEF_ADD = 596;// i32 内功防御加成（运功内功 varprop）
    public static final int CUR_LEVEL = 762;        // i32 修炼目标书当前层数
    // ---------- 新参考 exe 51 字段表追加批（IDA 反编译钉死，索引=exe-1） ----------
    public static final int MAX_SHOT_DAMAGE = 510;  // i32 暗器伤害上限（种子 0）
    public static final int MIN_SHOT_DAMAGE = 511;  // i32 暗器伤害下限（种子 0）
    public static final int PHY_DAMAGE_ADD = 512;   // i32 五系伤害加成（经脉叠加源）
    public static final int STI_DAMAGE_ADD = 513;
    public static final int JUJ_DAMAGE_ADD = 514;
    public static final int NEG_DAMAGE_ADD = 515;
    public static final int MAS_DAMAGE_ADD = 516;
    public static final int PHY_DAMAGE_MUL = 517;   // f32 五系伤害倍率
    public static final int STI_DAMAGE_MUL = 518;
    public static final int JUJ_DAMAGE_MUL = 519;
    public static final int NEG_DAMAGE_MUL = 520;
    public static final int MAS_DAMAGE_MUL = 521;
    public static final int PHY_PARRY_PROB = 2424;      // i32 招架概率（exe 表 171；本服表尾追加）
    public static final int PHY_PARRY_PROB_ADD = 2425;  // i32 招架概率加成（exe 表 172）
    public static final int NEIGONG_USE_VALUE = 816;    // i32 使用值三连（exe >0 才发）
    public static final int SKILL_USE_VALUE = 817;
    /** 内功属性（阴柔/阳刚/太极）链：书行 WuXing + 玩家 CurNeiGongWuXing（IDA 钉死）。 */
    public static final int CUR_NEIGONG_WU_XING = 653;  // i32 当前运功内功五行/属性
    public static final int STR_ADD = 489;              // i32 View43 书行五维 Add
    public static final int DEX_ADD = 490;
    public static final int ING_ADD = 491;
    public static final int SPI_ADD = 492;
    public static final int STA_ADD = 493;
    public static final int WU_XING = 2241;             // i32 五行/内功属性枚举
    public static final int ENE = 704;              // i32 体力
    public static final int MAX_ENE = 705;          // i32 体力上限
    public static final int RESUME_ENE = 706;       // i32 体力恢复
    public static final int SAT = 707;              // i32 饱食
    public static final int MAX_SAT = 708;          // i32 饱食上限
    public static final int DRUNK_LEVEL = 722;      // i32 酒量
    public static final int PK_VALUE = 426;         // i32 杀戮

    /** BufferInfo 槽位属性索引（1..24）。 */
    public static int bufferInfoSlot(int slot) {
        if (slot < 1 || slot > BUFFER_INFO_COUNT) {
            throw new IllegalArgumentException("invalid BufferInfo slot " + slot);
        }
        return BUFFER_INFO_FIRST + slot - 1;
    }
}
