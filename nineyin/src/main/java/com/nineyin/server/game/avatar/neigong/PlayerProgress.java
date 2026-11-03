package com.nineyin.server.game.avatar.neigong;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.SceneMessages;

/**
 * 玩家内功修炼进度，对齐 Go cmd/protocol-probe/player_progress.go 的登录时刻子集。
 *
 * <p>newPlayerProgress 的内联书表在资源可用时被 refreshAllModernEffects 的
 * StaticData/当前等级 varprop 结果覆盖（best-effort）。
 */
public final class PlayerProgress {

    public static final int FACULTY_STATE_NONE = 0;
    public static final int FACULTY_STATE_CONVERT = 2;
    public static final int FACULTY_STYLE_NORMAL = 1;
    public static final int FACULTY_STYLE_ACT = 2;
    public static final int LOGIC_STATE_FACULTY = 108;

    /** learnedNeiGong：书与当前等级效果（server 状态，不进 UI 之外的任何客户端权威）。 */
    public static final class Book {
        public String configId;
        public int staticData;
        public int itemType;
        public int level;
        public int maxLevel;
        public int fill;
        public int total;
        public int power;
        public int maxPower;
        public String buffId = "";
        public int buffStaticData;
        public int buffLevel;
        public int strAdd;
        public int staAdd;
        public int dexAdd;
        public int ingAdd;
        public int spiAdd;
        public int maxHpAdd;
        public int maxMpAdd;
        public int maxParryAdd;
        public int minMagicDefAdd;
        /** varprop/proppack 全量 stats 快照（MeleePowerAdd/DamageAdd/Mul 等通用键面，四期面板叠加源）。 */
        public Map<String, Integer> effectStats = Map.of();
        // 内功属性三件套（IDA 钉死：BelongType→太极/阴柔/阳刚；wuxuebaseinfo nWuXing；neigong.ini School）
        public String attribute = "";
        public int wuXing;
        public String school = "";
    }

    /**
     * 面板战斗块的装备词缀叠加层（{@code PanelState.recomputeEquipBonuses} 的产物）：
     * 五维/攻防/命中/闪避/上下限均按 基础 + 本层 + 运功内功 varprop 叠加（IDA 钉死序）。
     */
    public record EquipBonus(int maxHpAdd, int maxMpAdd,
                             int strAdd, int staAdd, int dexAdd, int ingAdd, int spiAdd,
                             int meleePowerAdd, int phyVa, int magicVa,
                             int phyHit, int magicHit, int dodge, int phyDef,
                             int minMelee, int maxMelee) {

        public static final EquipBonus NONE = new EquipBonus(0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** facultyBookSnapshot / facultyProgressSnapshot 的 Go JSON 契约。 */
    public record BookSnapshot(String configId, int level, int fill, int total, int power, int maxPower) {
    }

    public record Snapshot(String curNeiGong, int faculty, int facultyState, int facultyStyle,
                           String facultyName, int fillSpeed, int fillSpeedBase, float fillSpeedMultiplier,
                           Instant lastAdvanceUtc, List<BookSnapshot> books,
                           int liveGroove1, int liveGroove2, int liveGroove3, int liveGroove4, int liveGroove5,
                           int holdFaculty, int liveSpeedPower, Instant lastLivepointUtc,
                           int actDayValue, int actCostSuiYin, int actDayValueMax,
                           int actDayValueUsed, int actDayValueLast,
                           int teamFacultyValue, int teamFacultyLast) {

        /** 旧契约构造（快照无修速拆分层）：基础值/倍率取默认 200 × 1。 */
        public Snapshot(String curNeiGong, int faculty, int facultyState, int facultyStyle,
                        String facultyName, int fillSpeed, Instant lastAdvanceUtc,
                        List<BookSnapshot> books) {
            this(curNeiGong, faculty, facultyState, facultyStyle, facultyName, fillSpeed,
                    NORMAL_FACULTY_FILL_PER_MINUTE, 1.0f, lastAdvanceUtc, books);
        }

        /** 过渡契约构造（快照无历练/额度层）：历练与额度取新建默认，时间戳随修炼基准。 */
        public Snapshot(String curNeiGong, int faculty, int facultyState, int facultyStyle,
                        String facultyName, int fillSpeed, int fillSpeedBase, float fillSpeedMultiplier,
                        Instant lastAdvanceUtc, List<BookSnapshot> books) {
            this(curNeiGong, faculty, facultyState, facultyStyle, facultyName, fillSpeed,
                    fillSpeedBase, fillSpeedMultiplier, lastAdvanceUtc, books,
                    0, 0, 0, 0, 0, 0, 0, lastAdvanceUtc,
                    UNLIMITED_ACT_DAY_VALUE, 0, 0, 0, UNLIMITED_ACT_DAY_VALUE, 0, 0);
        }

        /** 过渡契约构造（快照无历练/额度层）：历练与额度取新建默认，时间戳随修炼基准。 */
        public Snapshot(String curNeiGong, int faculty, int facultyState, int facultyStyle,
                        String facultyName, int fillSpeed, int fillSpeedBase, float fillSpeedMultiplier,
                        Instant lastAdvanceUtc, List<BookSnapshot> books,
                        int liveGroove1, int liveGroove2, int liveGroove3, int liveGroove4, int liveGroove5,
                        int holdFaculty, int liveSpeedPower, Instant lastLivepointUtc) {
            this(curNeiGong, faculty, facultyState, facultyStyle, facultyName, fillSpeed,
                    fillSpeedBase, fillSpeedMultiplier, lastAdvanceUtc, books,
                    liveGroove1, liveGroove2, liveGroove3, liveGroove4, liveGroove5,
                    holdFaculty, liveSpeedPower, lastLivepointUtc,
                    UNLIMITED_ACT_DAY_VALUE, 0, 0, 0, UNLIMITED_ACT_DAY_VALUE, 0, 0);
        }
    }

    static final class Attributes {
        int sp, str, sta, dex, ing, spi;
        int meleePower, magicPower, phyHit, magicHit, dodge;
        int maxParry, phyDef, stiDef, negDef, masDef, jujDef;
        int minMeleeDamage, maxMeleeDamage, maxHpAdd, maxMpAdd;
    }

    final List<Book> books = new ArrayList<>();
    public String curNeiGong;
    int faculty;
    public int facultyState;
    public int facultyStyle;
    String facultyName;
    int fillSpeed;
    /** 基础修速（FillSpeed 的拆分层一）：默认 200/min；GM set_fill_speed 可调，NorFacultyAdd@774 下发。 */
    int fillSpeedBase = NORMAL_FACULTY_FILL_PER_MINUTE;
    /** 修速倍率（拆分层二）：默认 1 倍；实际修速 = round(base × multiplier)（官服实证 FillSpeed=516=200×2.58）。 */
    float fillSpeedMultiplier = 1.0f;
    Instant lastAdvance;
    // ---- 历练层（LiveGroove，毫历练单位）----
    /** 历练槽 1..5（LiveGroove_1..5@804..808，毫历练；UI 显示 = 值/1000，每槽上限 999 点）。 */
    final int[] liveGroove = new int[GROOVE_COUNT];
    /** 潜能修为（HoldFaculty@800）：非名俊转化 30% 分流至此，黄金按价赎回。 */
    int holdFaculty;
    /** 历练转化速度加成%（LiveSpeedPower@809，卦象等来源；0=无加成）。 */
    int liveSpeedPower;
    /** 历练层独立结算基准（100 秒步进，与 lastAdvance 分钟节拍并行）。 */
    Instant lastLivepoint;
    // ---- 演武碎银额度层（ActDayValue 块，0x09 表 790-797；单位文，1 两 = 1000 文）----
    /** ActDayValue(790)：今日可用碎银额度（文）。不限制模式发大数占位（客户端 /1000 显示两）。 */
    int actDayValue = UNLIMITED_ACT_DAY_VALUE;
    /** ActCostSuiYin(791)：今日已耗碎银（文）。主/演武面板可用 = ActDayValue - 本值。 */
    int actCostSuiYin;
    /** ActDayValueMax(797)：每日上限（文）；0 = 不限制（演武页两标签显示"不限制"，switch 2812 关）。 */
    int actDayValueMax;
    /** ActDayValueUsed(796)：今日已用（文）。 */
    int actDayValueUsed;
    /** ActDayValueLast(795)：剩余总量（文，跨日累计语义；不限制模式发大数占位）。 */
    int actDayValueLast = UNLIMITED_ACT_DAY_VALUE;
    // ---- 团练疲劳层（TeamFaculty 块，0x09 表 775-780）----
    /** TeamFacultyValue(777)：团练消耗值（0..TEAM_FACULTY_TOTAL）；客户端按 (总量-值)/总量 反算剩余精力%。 */
    int teamFacultyValue;
    /** TeamFacultyLast(778)：团练剩余可抵扣修炼度。 */
    int teamFacultyLast;
    final Attributes attributes = new Attributes();

    /** Go newPlayerProgress。 */
    public static PlayerProgress create(Clock clock) {
        PlayerProgress progress = new PlayerProgress();
        Book ngWd001 = book("ng_wd_001", 5, 1002, 30, 36, 273200, 4550, 4550);
        ngWd001.buffId = "buf_ng_wd_001";
        ngWd001.buffStaticData = 5954;
        ngWd001.buffLevel = 3;
        ngWd001.strAdd = 45;
        ngWd001.staAdd = 31;
        ngWd001.dexAdd = 13;
        ngWd001.ingAdd = 46;
        ngWd001.spiAdd = 13;
        ngWd001.maxHpAdd = 1630;
        ngWd001.maxMpAdd = 490;
        ngWd001.maxParryAdd = 840;
        ngWd001.minMagicDefAdd = 45;
        ngWd001.attribute = "太极";
        ngWd001.wuXing = NeiGongCatalog.WUXING_TAIJI;
        ngWd001.school = "ng_wd";
        progress.books.add(ngWd001);
        Book ngYh001 = book("ng_yh_001", 114, 1002, 30, 71, 579600, 8180, 8180);
        ngYh001.buffId = "buf_ng_yh_001";
        ngYh001.buffStaticData = 13073;
        ngYh001.buffLevel = 3;
        ngYh001.strAdd = 55;
        ngYh001.staAdd = 57;
        ngYh001.dexAdd = 70;
        ngYh001.ingAdd = 97;
        ngYh001.spiAdd = 67;
        ngYh001.maxHpAdd = 4050;
        ngYh001.maxMpAdd = 1130;
        ngYh001.maxParryAdd = 5040;
        ngYh001.minMagicDefAdd = 100;
        ngYh001.attribute = "阴柔";
        ngYh001.school = "ng_yh";
        progress.books.add(ngYh001);
        Book ngTh001 = book("ng_th_001", 113, 1002, 30, 71, 579600, 8180, 8180);
        ngTh001.buffId = "buf_ng_th_001";
        ngTh001.buffStaticData = 13434;
        ngTh001.buffLevel = 3;
        ngTh001.strAdd = 66;
        ngTh001.staAdd = 56;
        ngTh001.dexAdd = 52;
        ngTh001.ingAdd = 103;
        ngTh001.spiAdd = 68;
        ngTh001.maxHpAdd = 4050;
        ngTh001.maxMpAdd = 1135;
        ngTh001.maxParryAdd = 5070;
        ngTh001.minMagicDefAdd = 100;
        ngTh001.school = "ng_th";
        progress.books.add(ngTh001);
        progress.books.add(book("ng_wd_002", 6, 1002, 1, 36, 700, 120, 120));
        progress.books.add(book("ng_jh_001", 1, 1002, 1, 36, 700, 120, 120));
        progress.books.add(book("ng_em_001", 8, 1002, 1, 36, 700, 120, 120));
        progress.curNeiGong = progress.books.get(0).configId;
        // 完整官服模型：修为由历练转化供给。新角色种子可配（-Dnineyin.faculty.seed），
        // 存量角色由快照 restore 覆盖；GM set_faculty 保留直设兜底。
        progress.faculty = Integer.getInteger("nineyin.faculty.seed", INITIAL_FACULTY);
        progress.facultyState = FACULTY_STATE_CONVERT;
        progress.facultyStyle = FACULTY_STYLE_NORMAL;
        progress.facultyName = progress.books.get(0).configId;
        progress.fillSpeed = 200;
        progress.lastAdvance = Instant.now(clock);
        progress.lastLivepoint = progress.lastAdvance;
        progress.attributes.sp = 100;
        progress.attributes.str = 30;
        progress.attributes.sta = 30;
        progress.attributes.dex = 30;
        progress.attributes.ing = 30;
        progress.attributes.spi = 30;
        progress.attributes.meleePower = 80;
        progress.attributes.magicPower = 80;
        progress.attributes.phyHit = 50;
        progress.attributes.magicHit = 50;
        progress.attributes.dodge = 35;
        progress.attributes.maxParry = 100;
        progress.attributes.phyDef = 60;
        progress.attributes.stiDef = 50;
        progress.attributes.negDef = 50;
        progress.attributes.masDef = 50;
        progress.attributes.jujDef = 50;
        progress.attributes.minMeleeDamage = 20;
        progress.attributes.maxMeleeDamage = 35;
        progress.refreshAllModernEffects();
        return progress;
    }

    private static Book book(String configId, int staticData, int itemType, int level,
                             int maxLevel, int total, int power, int maxPower) {
        Book value = new Book();
        value.configId = configId;
        value.staticData = staticData;
        value.itemType = itemType;
        value.level = level;
        value.maxLevel = maxLevel;
        value.total = total;
        value.power = power;
        value.maxPower = maxPower;
        return value;
    }

    public Book book(String id) {
        for (Book item : books) {
            if (item.configId.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** Go equippedResourceAdds。 */
    public int[] equippedResourceAdds() {
        Book equipped = book(curNeiGong);
        return equipped == null ? new int[]{0, 0} : new int[]{equipped.maxHpAdd, equipped.maxMpAdd};
    }

    /**
     * totalResourceAdds（新参考 exe IDA 逆向钉死，bak 真机验证）：
     * 有效 HP = 基础 + MaxHPAdd 各层（装备/运功内功）+ 7×体魄 + 2×臂力；
     * 有效 MP = 基础 + MaxMPAdd 各层 + 4×内息 + 罡气。五维均含装备词缀与书加成。
     *
     * @return {maxHpAdd, maxMpAdd, strEff, staEff, ingEff, spiEff}
     */
    public int[] totalResourceAdds(EquipBonus bonus) {
        Book equipped = book(curNeiGong);
        int maxHpAdd = bonus.maxHpAdd + (equipped == null ? 0 : equipped.maxHpAdd);
        int maxMpAdd = bonus.maxMpAdd + (equipped == null ? 0 : equipped.maxMpAdd);
        int strEff = attributes.str + bonus.strAdd + (equipped == null ? 0 : equipped.strAdd);
        int staEff = attributes.sta + bonus.staAdd + (equipped == null ? 0 : equipped.staAdd);
        int ingEff = attributes.ing + bonus.ingAdd + (equipped == null ? 0 : equipped.ingAdd);
        int spiEff = attributes.spi + bonus.spiAdd + (equipped == null ? 0 : equipped.spiAdd);
        return new int[]{maxHpAdd, maxMpAdd, strEff, staEff, ingEff, spiEff};
    }

    /** Go applyModernEffect：资源可用时以 varprop 结果覆盖；属性三件套 best-effort 始终回填。 */
    void applyModernEffect(Book item) {
        try {
            NeiGongCatalog catalog = NeiGongCatalog.load();
            // 三件套不依赖 varprop 行（缺该级行也按 staticData/configId 查表，属性显示不断链）；
            // 查到非空才覆盖——空目录环境保留内联种子。
            String attribute = catalog.attributeOf(item.staticData, item.configId);
            if (!attribute.isEmpty()) {
                item.attribute = attribute;
            }
            int wuXing = catalog.wuXingOf(item.configId);
            if (wuXing != 0) {
                item.wuXing = wuXing;
            }
            String school = catalog.schoolOf(item.configId);
            if (!school.isEmpty()) {
                item.school = school;
            }
            NeiGongCatalog.Effect effect = catalog.effect(item.staticData, item.level);
            item.buffId = effect.buffId();
            item.buffStaticData = effect.staticData();
            item.buffLevel = effect.buffLevel();
            item.strAdd = effect.stats().getOrDefault("StrAdd", 0);
            item.staAdd = effect.stats().getOrDefault("StaAdd", 0);
            item.dexAdd = effect.stats().getOrDefault("DexAdd", 0);
            item.ingAdd = effect.stats().getOrDefault("IngAdd", 0);
            item.spiAdd = effect.stats().getOrDefault("SpiAdd", 0);
            item.maxHpAdd = effect.stats().getOrDefault("MaxHPAdd", 0);
            item.maxMpAdd = effect.stats().getOrDefault("MaxMPAdd", 0);
            item.maxParryAdd = effect.stats().getOrDefault("MaxParryAdd", 0);
            item.minMagicDefAdd = effect.stats().getOrDefault("MinMagicDefAdd", 0);
            item.effectStats = effect.stats();
        } catch (RuntimeException ignored) {
            // Go refreshAllModernEffects 是 best-effort：资源缺失保留内联值。
        }
    }

    void refreshAllModernEffects() {
        for (Book item : books) {
            applyModernEffect(item);
        }
    }

    /** Go properties()：37 项内功/面板属性（已叠当前装备书加成）。 */
    public List<PropertyValues.Indexed> properties() {
        return properties(EquipBonus.NONE);
    }

    /**
     * 面板战斗块（四期实时化）：基础属性 + 装备词缀累积层 + 运功内功（平铺 Add 与
     * varprop eff 键面，同键不重复计——平铺五维/上限与 eff 键集不相交）。
     */
    public List<PropertyValues.Indexed> properties(EquipBonus bonus) {
        Book target = targetBook();
        int level = 0;
        int maxLevel = 0;
        int fill = 0;
        int total = 0;
        int power = 0;
        int maxPower = 0;
        if (target != null) {
            level = target.level;
            maxLevel = target.maxLevel;
            fill = target.fill;
            total = target.total;
            power = target.power;
            maxPower = target.maxPower;
        }
        Attributes a = new Attributes();
        a.sp = attributes.sp;
        a.str = attributes.str;
        a.sta = attributes.sta;
        a.dex = attributes.dex;
        a.ing = attributes.ing;
        a.spi = attributes.spi;
        a.meleePower = attributes.meleePower;
        a.magicPower = attributes.magicPower;
        a.phyHit = attributes.phyHit;
        a.magicHit = attributes.magicHit;
        a.dodge = attributes.dodge;
        a.maxParry = attributes.maxParry;
        a.phyDef = attributes.phyDef;
        a.stiDef = attributes.stiDef;
        a.negDef = attributes.negDef;
        a.masDef = attributes.masDef;
        a.jujDef = attributes.jujDef;
        a.minMeleeDamage = attributes.minMeleeDamage;
        a.maxMeleeDamage = attributes.maxMeleeDamage;
        int maxParryAdd = 0;
        int minMagicDefAdd = 0;
        Book equipped = book(curNeiGong);
        if (equipped != null) {
            a.str += equipped.strAdd;
            a.sta += equipped.staAdd;
            a.dex += equipped.dexAdd;
            a.ing += equipped.ingAdd;
            a.spi += equipped.spiAdd;
            a.maxHpAdd += equipped.maxHpAdd;
            a.maxMpAdd += equipped.maxMpAdd;
            maxParryAdd = equipped.maxParryAdd;
            minMagicDefAdd = equipped.minMagicDefAdd;
        }
        List<PropertyValues.Indexed> properties = new ArrayList<>(40);
        Props.add(properties, "CurNeiGong", PropertyValues.Value.ofString(curNeiGong));
        // 当前运功内功五行/属性 int（书行 WuXing 同源；客户端阴阳太极伴生显示）
        Props.add(properties, "CurNeiGongWuXing",
                PropertyValues.Value.ofInt32(equipped == null ? 0 : equipped.wuXing));
        // ---- 内修（faculty）面板块：0x10 全量帧携带，括号内为 0x09 官方属性表索引 ----
        // Faculty(757)：修炼点余额，演武等操作消耗（升级折半扣），种子 9_999_999。
        Props.add(properties, "Faculty", PropertyValues.Value.ofInt32(faculty));
        // FacultyState(758)：修炼状态机。0=FACULTY_STATE_NONE 未修炼；
        // 2=FACULTY_STATE_CONVERT 修炼中。F 盘抓包实证登录即 2（常驻修炼）。
        Props.add(properties, "FacultyState", PropertyValues.Value.ofInt32(facultyState));
        // FacultyStyle(759)：修炼方式。1=FACULTY_STYLE_NORMAL 普通内修（按分钟累积修速）；
        // 2=FACULTY_STYLE_ACT 演武（不计每分钟，单卡奖励 20000）。
        Props.add(properties, "FacultyStyle", PropertyValues.Value.ofInt32(facultyStyle));
        // FacultyName(760)：当前修炼内功书 configId（如 ng_jh_201；建号默认 CS_jh_cqgf01）。
        Props.add(properties, "FacultyName", PropertyValues.Value.ofString(facultyName));
        // CurPlaceList(766)：修炼地点索引（type7 字符串）。客户端按值查本地 UI 串
        // ui_faculty_place_N：1=山 2=水 3=洞 4=林 5=雪 6=市。F 盘实证恒发 "6"（市），
        // 此处暂固定 "1"；切换地点的 C2S 消息未抓到，接动态后再入库。
        Props.add(properties, "CurPlaceList", PropertyValues.Value.ofString("1"));
        // CurFillValue(764)：本层已修修炼度（1s ticker 按整分钟结算累加）。
        Props.add(properties, "CurFillValue", PropertyValues.Value.ofInt32(fill));
        // TotalFillValue(765)：本层升级所需总修炼度（wuxuelevelinfo.ini r=等级,修炼度,速度）。
        Props.add(properties, "TotalFillValue", PropertyValues.Value.ofInt32(total));
        // CurLevel(763)：当前书层数（面板"第 N 层"之源）。
        Props.add(properties, "CurLevel", PropertyValues.Value.ofInt32(level));
        // FillSpeed(773)：每分钟修炼速度。普通内修初始 200（NORMAL_FACULTY_FILL_PER_MINUTE），
        // 地点/道具加成后可变（F 盘实证 516）；演武为 0。客户端按值分档显示 ui_train_speed_0..5
        //（0=停止，实证 FillSpeed=0 对应停止档）。
        Props.add(properties, "FillSpeed", PropertyValues.Value.ofInt32(fillSpeed));
        // NorFacultyAdd(774)：基准修速（普通内修基础值，默认 200），与加成后的 FillSpeed 分列下发；
        // F 盘实证 FillSpeed=516 时此值恒 200（演武帧亦保持 200，F 抓包 #2194 diff 钉死）；
        // GM set_fill_speed 调基础值后随之下发。
        Props.add(properties, "NorFacultyAdd", PropertyValues.Value.ofInt32(fillSpeedBase));
        // ---- 历练层（form_faculty.lua 数据绑定源；括号内为 0x09 官方属性表索引）----
        // LiveGroove_1..5(804..808)：历练槽，毫历练单位（客户端显示 = 值/1000，槽上限 999）。
        // 修炼界面 5 条历练进度条与"当前历练 X/999"总条的数据源；服务端 100 秒步进结算。
        for (int i = 0; i < GROOVE_COUNT; i++) {
            Props.add(properties, "LiveGroove_" + (i + 1),
                    PropertyValues.Value.ofInt32(liveGroove[i]));
        }
        // LiveSpeedPower(809)：历练转化速度加成%（卦象等来源；客户端公式 speed×(1+power/100)）。
        Props.add(properties, "LiveSpeedPower", PropertyValues.Value.ofInt32(liveSpeedPower));
        // HoldFaculty(800)：潜能修为存量。非名俊转化 30% 分流至此；"历练转化"窗黄金赎回。
        Props.add(properties, "HoldFaculty", PropertyValues.Value.ofInt32(holdFaculty));
        // ---- 团练疲劳层（form_wuxue_faculty/form_team_faculty_member 数据绑定源）----
        // TeamFacultyState(775)：团练状态（0=未在团练；团练全链未实现，占位 0）。
        Props.add(properties, "TeamFacultyState", PropertyValues.Value.ofInt32(0));
        // TeamFacultyValue(777)：团练消耗值（F 抓包登录帧实证随全量帧下发，如 79190）。
        // 客户端按 faculty_team_action.ini TeamValue=80000 反算：剩余% = (80000-值)×100/80000。
        Props.add(properties, "TeamFacultyValue", PropertyValues.Value.ofInt32(teamFacultyValue));
        // TeamFacultyLast(778)：团练剩余可抵扣修炼度（团练面板 lbl_offset_faculty）。
        Props.add(properties, "TeamFacultyLast", PropertyValues.Value.ofInt32(teamFacultyLast));
        // ---- 演武碎银额度层（form_wuxue_faculty.lbl_suiyin / form_wuxue_act 数据绑定源）----
        // ActDayValue(790)/ActCostSuiYin(791)：主面板"可用碎银 X 两" = (前-后)/1000（毫单位）。
        Props.add(properties, "ActDayValue", PropertyValues.Value.ofInt32(actDayValue));
        Props.add(properties, "ActCostSuiYin", PropertyValues.Value.ofInt32(actCostSuiYin));
        // ActDayValueLast(795)/Used(796)/Max(797)：演武页"今日可用/剩余总量"三值。
        // Max=0（不限制）或客户端 switch 2812 关闭时，演武页两标签显示"不限制"。
        Props.add(properties, "ActDayValueLast", PropertyValues.Value.ofInt32(actDayValueLast));
        Props.add(properties, "ActDayValueUsed", PropertyValues.Value.ofInt32(actDayValueUsed));
        Props.add(properties, "ActDayValueMax", PropertyValues.Value.ofInt32(actDayValueMax));
        // PowerValue/MaxPowerValue：当前/上限功力（书行 power，如 ng_jh_201 满级 680）。
        Props.add(properties, "PowerValue", PropertyValues.Value.ofInt32(power));
        Props.add(properties, "MaxPowerValue", PropertyValues.Value.ofInt32(maxPower));
        Props.add(properties, "SP", PropertyValues.Value.ofInt32(a.sp));
        // MaxSP(580)/MaxSPAdd(583)：怒气上限——不随下发客户端上限显示 0（62433 抓包
        // 全量帧实证 F exe 恒发，其值 80；本服怒气域上限 100）。
        Props.add(properties, "MaxSP", PropertyValues.Value.ofInt32(MAX_PLAYER_SP_VALUE));
        Props.add(properties, "MaxSPAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "Str", PropertyValues.Value.ofInt32(a.str + bonus.strAdd));
        Props.add(properties, "Sta", PropertyValues.Value.ofInt32(a.sta + bonus.staAdd));
        Props.add(properties, "Dex", PropertyValues.Value.ofInt32(a.dex + bonus.dexAdd));
        Props.add(properties, "Ing", PropertyValues.Value.ofInt32(a.ing + bonus.ingAdd));
        Props.add(properties, "Spi", PropertyValues.Value.ofInt32(a.spi + bonus.spiAdd));
        Props.add(properties, "MeleePower", PropertyValues.Value.ofInt32(
                a.meleePower + bonus.meleePowerAdd + eff(equipped, "MeleePowerAdd")));
        Props.add(properties, "MagicPower", PropertyValues.Value.ofInt32(
                a.magicPower + bonus.magicVa + eff(equipped, "MagicPowerAdd")));
        Props.add(properties, "PhyHit", PropertyValues.Value.ofInt32(
                a.phyHit + bonus.phyHit + eff(equipped, "PhyHitAdd")));
        Props.add(properties, "MagicHit", PropertyValues.Value.ofInt32(
                a.magicHit + bonus.magicHit + eff(equipped, "MagicHitAdd")));
        Props.add(properties, "Dodge", PropertyValues.Value.ofInt32(
                a.dodge + bonus.dodge + eff(equipped, "DodgeAdd")));
        Props.add(properties, "MaxParry", PropertyValues.Value.ofInt32(a.maxParry));
        Props.add(properties, "PhyDef", PropertyValues.Value.ofInt32(
                a.phyDef + bonus.phyDef + eff(equipped, "PhyDefAdd")));
        Props.add(properties, "StiDef", PropertyValues.Value.ofInt32(
                a.stiDef + eff(equipped, "StiDefAdd")));
        Props.add(properties, "NegDef", PropertyValues.Value.ofInt32(
                a.negDef + eff(equipped, "NegDefAdd")));
        Props.add(properties, "MasDef", PropertyValues.Value.ofInt32(
                a.masDef + eff(equipped, "MasDefAdd")));
        Props.add(properties, "JujDef", PropertyValues.Value.ofInt32(
                a.jujDef + eff(equipped, "JujDefAdd")));
        Props.add(properties, "MinMeleeDamage", PropertyValues.Value.ofInt32(
                a.minMeleeDamage + bonus.minMelee + eff(equipped, "MinMeleeDamage")));
        Props.add(properties, "MaxMeleeDamage", PropertyValues.Value.ofInt32(
                a.maxMeleeDamage + bonus.maxMelee + eff(equipped, "MaxMeleeDamage")));
        Props.add(properties, "MaxHPAdd", PropertyValues.Value.ofInt32(a.maxHpAdd + bonus.maxHpAdd));
        Props.add(properties, "MaxMPAdd", PropertyValues.Value.ofInt32(a.maxMpAdd + bonus.maxMpAdd));
        Props.add(properties, "MaxParryAdd", PropertyValues.Value.ofInt32(maxParryAdd));
        Props.add(properties, "MinMagicDefAdd", PropertyValues.Value.ofInt32(minMagicDefAdd));
        Props.add(properties, "MaxLevel", PropertyValues.Value.ofInt32(maxLevel));
        return properties;
    }

    /** 运功书 varprop stats 取值（无书/缺键 0；面板/伤害块叠加）。 */
    public static int eff(Book book, String key) {
        Integer value = book == null ? null : book.effectStats.get(key);
        return value == null ? 0 : value;
    }

    /**
     * 参考全量帧 Add 分解族的有效五维（IDA 换算钉死：Phy/近攻系 Add=臂力、
     * 魔功系=内息、命中/闪避/暗器=身法、魔防=灵气；F 抓包 61579 #2194 逐值核对）：
     * 返回 [臂力, 身法, 内息, 灵气]，各叠 基础+装备词缀+运功书。
     */
    /** 参考帧 ParryValue：格挡上限合计（基础 + 运功书加成；61579 实证 100+949=1049）。 */
    public int parityParryValue() {
        Book equipped = book(curNeiGong);
        return attributes.maxParry + (equipped == null ? 0 : equipped.maxParryAdd);
    }

    public int[] parityEffectiveAttributes(EquipBonus bonus) {
        Book equipped = book(curNeiGong);
        return new int[]{
                attributes.str + bonus.strAdd + (equipped == null ? 0 : equipped.strAdd),
                attributes.dex + bonus.dexAdd + (equipped == null ? 0 : equipped.dexAdd),
                attributes.ing + bonus.ingAdd + (equipped == null ? 0 : equipped.ingAdd),
                attributes.spi + bonus.spiAdd + (equipped == null ? 0 : equipped.spiAdd)};
    }

    /** 运功书千分数键（DamageMul 族 ÷1000）。 */
    public static float effMul(Book book, String key) {
        Integer value = book == null ? null : book.effectStats.get(key);
        return value == null ? 0f : value / 1000f;
    }

    public Book targetBook() {
        if (facultyName != null && !facultyName.isEmpty()) {
            Book byFaculty = book(facultyName);
            if (byFaculty != null) {
                return byFaculty;
            }
        }
        return book(curNeiGong);
    }

    /** View 43（内功书容器）ID。 */
    public static final int VIEWPORT_NEI_GONG = 43;

    /** Go viewFrames：View 43 容器 + 每本书一帧。 */
    public List<byte[]> viewFrames() {
        List<byte[]> frames = new ArrayList<>(books.size() + 1);
        frames.add(SceneMessages.createView(new SceneMessages.ViewSpec(VIEWPORT_NEI_GONG, books.size())));
        for (int slot = 1; slot <= books.size(); slot++) {
            frames.add(bookFrame(slot));
        }
        return frames;
    }

    /** Go bookFrame：ConfigID + 静态/档位/等级/修为/内力九属性。 */
    public byte[] bookFrame(int slot) {
        if (slot <= 0 || slot > books.size()) {
            throw new IllegalArgumentException("invalid neigong slot " + slot);
        }
        Book book = books.get(slot - 1);
        // bak facultyBookFrame 16 属性布局（真机验证）：School/Level(byte)/WuXing 是
        // 内修面板属性列（太极/阴柔/阳刚，客户端 ui_wuxue_prop_N 本地化）的显示来源。
        List<SceneMessages.ViewProperty> properties = new ArrayList<>(16);
        Props.addView(properties, "ConfigID", PropertyValues.Value.ofString(book.configId));
        Props.addView(properties, "StaticData", PropertyValues.Value.ofInt32(book.staticData));
        Props.addView(properties, "ItemType", PropertyValues.Value.ofInt32(book.itemType));
        Props.addView(properties, "School", PropertyValues.Value.ofString(book.school));
        Props.addView(properties, "Level", PropertyValues.Value.ofByte(book.level));
        Props.addView(properties, "MaxLevel", PropertyValues.Value.ofInt32(book.maxLevel));
        Props.addView(properties, "StrAdd", PropertyValues.Value.ofInt32(book.strAdd));
        Props.addView(properties, "DexAdd", PropertyValues.Value.ofInt32(book.dexAdd));
        Props.addView(properties, "IngAdd", PropertyValues.Value.ofInt32(book.ingAdd));
        Props.addView(properties, "SpiAdd", PropertyValues.Value.ofInt32(book.spiAdd));
        Props.addView(properties, "StaAdd", PropertyValues.Value.ofInt32(book.staAdd));
        Props.addView(properties, "CurFillValue", PropertyValues.Value.ofInt32(book.fill));
        Props.addView(properties, "TotalFillValue", PropertyValues.Value.ofInt32(book.total));
        Props.addView(properties, "PowerValue", PropertyValues.Value.ofInt32(book.power));
        Props.addView(properties, "MaxPowerValue", PropertyValues.Value.ofInt32(book.maxPower));
        Props.addView(properties, "WuXing", PropertyValues.Value.ofInt32(book.wuXing));
        return SceneMessages.viewAdd(VIEWPORT_NEI_GONG, slot, properties);
    }

    public Snapshot snapshot() {
        List<BookSnapshot> saved = new ArrayList<>(books.size());
        for (Book item : books) {
            saved.add(new BookSnapshot(item.configId, item.level, item.fill, item.total, item.power, item.maxPower));
        }
        return new Snapshot(curNeiGong, faculty, facultyState, facultyStyle, facultyName,
                fillSpeed, fillSpeedBase, fillSpeedMultiplier, lastAdvance, saved,
                liveGroove[0], liveGroove[1], liveGroove[2], liveGroove[3], liveGroove[4],
                holdFaculty, liveSpeedPower, lastLivepoint,
                actDayValue, actCostSuiYin, actDayValueMax, actDayValueUsed, actDayValueLast,
                teamFacultyValue, teamFacultyLast);
    }

    // ------------------------------------------------------------------- 怒气 SP

    /** 当前怒气（Go progress.attributes.sp，默认 100）。 */
    public int sp() {
        return attributes.sp;
    }

    /** Go clampPlayerSP + setSP 状态部分：0..100 饱和，返回应用后的值。 */
    public synchronized int setSP(int value) {
        attributes.sp = Math.max(0, Math.min(MAX_PLAYER_SP_VALUE, value));
        return attributes.sp;
    }

    /** Go addSP 状态部分：战斗/技能怒气与 GM 调整的唯一入口，0..100 饱和。 */
    public synchronized int addSP(int delta) {
        long next = (long) attributes.sp + delta;
        attributes.sp = (int) Math.max(0, Math.min(MAX_PLAYER_SP_VALUE, next));
        return attributes.sp;
    }

    /** Go maxPlayerSP = 100（怒气上限，进度侧常量）。 */
    static final int MAX_PLAYER_SP_VALUE = 100;

    /** Go restore：校验合并 + 分钟推进 + 资源刷新。 */
    public void restore(Snapshot value, Instant now) {
        if (book(value.curNeiGong()) != null) {
            curNeiGong = value.curNeiGong();
        }
        if (value.faculty() >= 0) {
            faculty = value.faculty();
        }
        if (book(value.facultyName()) != null) {
            facultyName = value.facultyName();
        }
        facultyState = value.facultyState();
        facultyStyle = value.facultyStyle() == FACULTY_STYLE_ACT ? FACULTY_STYLE_ACT : FACULTY_STYLE_NORMAL;
        fillSpeed = value.fillSpeed();
        // 修速拆分层：旧档/异常值（base≤0、multiplier≤0）回退默认 200 × 1；fillSpeed 本身
        // 原样恢复不打折（恢复中的普通内修态由下方 advanceFaculty 按原速追结算）。
        fillSpeedBase = value.fillSpeedBase() > 0 ? value.fillSpeedBase() : NORMAL_FACULTY_FILL_PER_MINUTE;
        fillSpeedMultiplier = value.fillSpeedMultiplier() > 0f ? value.fillSpeedMultiplier() : 1.0f;
        lastAdvance = value.lastAdvanceUtc();
        for (BookSnapshot saved : value.books()) {
            Book item = book(saved.configId());
            if (item == null || saved.level() <= 0 || saved.level() > item.maxLevel
                    || saved.total() <= 0 || saved.fill() < 0) {
                continue;
            }
            item.level = saved.level();
            item.fill = saved.fill();
            item.total = saved.total();
            item.power = saved.power();
            item.maxPower = saved.maxPower();
        }
        if (facultyState == FACULTY_STATE_CONVERT && facultyStyle == FACULTY_STYLE_NORMAL) {
            advanceFaculty(now);
        } else {
            lastAdvance = now;
        }
        // 历练层：槽值钳到上限；旧档 lastLivepointUtc 随修炼基准或取 now，随后离线补结算
        //（在线累积离线不计，转化离线照结——离线时长本就由 lastLivepointUtc 起算）。
        for (int i = 0; i < GROOVE_COUNT; i++) {
            liveGroove[i] = Math.max(0, Math.min(GROOVE_CAP, grooveOf(value, i)));
        }
        holdFaculty = Math.max(0, value.holdFaculty());
        liveSpeedPower = Math.max(0, value.liveSpeedPower());
        Instant lastLivepointUtc = value.lastLivepointUtc();
        // 0001-01-01 是 Store 侧 null 哨兵（formatInstant）；过旧/未来值一律重基 now 防离线结算爆走。
        lastLivepoint = lastLivepointUtc != null && lastLivepointUtc.isAfter(EPOCH_GUARD)
                && !lastLivepointUtc.isAfter(now) ? lastLivepointUtc : now;
        livepointTick(now, livepointVip());
        // 演武碎银额度层：负值/旧档缺省回退新建默认；上限语义保持（max>0 才受限）。
        actDayValue = Math.max(0, value.actDayValue());
        actCostSuiYin = Math.max(0, value.actCostSuiYin());
        actDayValueMax = Math.max(0, value.actDayValueMax());
        actDayValueUsed = Math.max(0, value.actDayValueUsed());
        actDayValueLast = Math.max(0, value.actDayValueLast());
        if (actDayValue <= 0) {
            actDayValue = actDayValueMax > 0 ? actDayValueMax : UNLIMITED_ACT_DAY_VALUE;
        }
        if (actDayValueLast <= 0 && actDayValueMax <= 0) {
            actDayValueLast = UNLIMITED_ACT_DAY_VALUE;
        }
        // 团练疲劳层：消耗值钳到官方总量内。
        teamFacultyValue = Math.max(0, Math.min(TEAM_FACULTY_TOTAL, value.teamFacultyValue()));
        teamFacultyLast = Math.max(0, value.teamFacultyLast());
        refreshAllModernEffects();
    }

    private static int grooveOf(Snapshot value, int index) {
        return switch (index) {
            case 0 -> value.liveGroove1();
            case 1 -> value.liveGroove2();
            case 2 -> value.liveGroove3();
            case 3 -> value.liveGroove4();
            default -> value.liveGroove5();
        };
    }

    /** Go advanceFaculty：按整分钟以 FillSpeed 结算修炼，余数留在 lastAdvance。
     * 返回是否发生结算（ticker 变化检测）。 */
    public boolean advanceFaculty(Instant now) {
        if (facultyState != FACULTY_STATE_CONVERT || facultyStyle != FACULTY_STYLE_NORMAL || fillSpeed <= 0) {
            lastAdvance = now;
            return false;
        }
        if (lastAdvance == null || now.isBefore(lastAdvance)) {
            lastAdvance = now;
            return false;
        }
        long minutes = Duration.between(lastAdvance, now).toMinutes();
        if (minutes <= 0) {
            return false;
        }
        lastAdvance = lastAdvance.plus(Duration.ofMinutes(minutes));
        for (Book item : books) {
            if (!item.configId.equals(facultyName)) {
                continue;
            }
            long fill = (long) fillSpeed * minutes;
            long cost = (fill + 1) / 2;
            if (cost > faculty) {
                cost = faculty;
                fill = cost * 2;
            }
            if (fill <= 0) {
                facultyState = FACULTY_STATE_NONE;
                fillSpeed = 0;
                return false;
            }
            faculty -= (int) cost;
            item.fill += (int) fill;
            levelUpWhile(item);
            applyModernEffect(item);
            if (item.level >= item.maxLevel) {
                item.fill = 0;
                facultyState = FACULTY_STATE_NONE;
                fillSpeed = 0;
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 历练层

    /** 名俊档开关（-Dnineyin.livepoint.vip）：true=无损转化；false=普通档 70% + 30% 进潜能。 */
    public static boolean livepointVip() {
        return Boolean.getBoolean("nineyin.livepoint.vip");
    }

    /**
     * 历练层 100 秒步进结算（官方公式，form_faculty.lua:87-111 钉死）：
     * <ol>
     *   <li>累积：总历练未达 999 点时 +1000 毫（=1 点历练）进第一个未满槽，全满丢弃；</li>
     *   <li>转化（每槽独立，槽非空才计）：{@code full = base_i × (100+power)/100}，
     *       {@code actual = min(槽余, full)}，槽 -= actual（1 毫历练 = 1 修为）；
     *       名俊 {@code faculty += actual}；普通档 {@code gain = actual×7/10} 进修为、
     *       差额进潜能（HoldFaculty，黄金按价赎回）。五槽全满名俊 = 380 修为/100秒。</li>
     * </ol>
     * 余数留在 lastLivepoint（与 advanceFaculty 分钟节拍同模式）。返回是否有变化。
     */
    public boolean livepointTick(Instant now, boolean vip) {
        if (lastLivepoint == null || now.isBefore(lastLivepoint)) {
            lastLivepoint = now;
            return false;
        }
        long steps = Duration.between(lastLivepoint, now).getSeconds() / LIVEPOINT_STEP_SECONDS;
        if (steps <= 0) {
            return false;
        }
        lastLivepoint = lastLivepoint.plusSeconds(steps * LIVEPOINT_STEP_SECONDS);
        boolean changed = false;
        for (long step = 0; step < steps; step++) {
            changed |= accrueLivepoint();
            changed |= convertLivepoint(vip);
        }
        return changed;
    }

    /** 在线累积：+1000 毫进第一个未满槽（总上限 999 点 = 5×999000 毫，全满丢弃）。 */
    private boolean accrueLivepoint() {
        for (int i = 0; i < GROOVE_COUNT; i++) {
            if (liveGroove[i] < GROOVE_CAP) {
                liveGroove[i] = Math.min(GROOVE_CAP, liveGroove[i] + LIVEPOINT_ACCRUE_MILLIS);
                return true;
            }
        }
        return false;
    }

    /** 转化：每槽按官方公式扣毫历练产修为；普通档 70% 进修为、30% 进潜能。 */
    private boolean convertLivepoint(boolean vip) {
        boolean changed = false;
        for (int i = 0; i < GROOVE_COUNT; i++) {
            if (liveGroove[i] <= 0) {
                continue;
            }
            long full = (long) GROOVE_BASE_SPEED[i] * (100 + liveSpeedPower) / 100;
            int actual = (int) Math.min(liveGroove[i], full);
            liveGroove[i] -= actual;
            if (vip) {
                faculty += actual;
            } else {
                int gain = actual * 7 / 10;
                faculty += gain;
                holdFaculty += actual - gain;
            }
            changed = true;
        }
        return changed;
    }

    /** GM add_livepoint：发 N 点历练（×1000 毫按序填槽，溢出丢弃）。返回实际入槽毫数。 */
    public int addLivepoint(int points) {
        int remaining = Math.max(0, points) * LIVEPOINT_ACCRUE_MILLIS;
        int added = 0;
        for (int i = 0; i < GROOVE_COUNT && remaining > 0; i++) {
            int space = GROOVE_CAP - liveGroove[i];
            if (space <= 0) {
                continue;
            }
            int put = Math.min(space, remaining);
            liveGroove[i] += put;
            added += put;
            remaining -= put;
        }
        return added;
    }

    /** GM set_hold_faculty：直设潜能修为（入口已限非负，本处再钳防御）。 */
    public void setHoldFaculty(int value) {
        holdFaculty = Math.max(0, value);
    }

    /** GM set_live_speed_power：设转化速度加成 %（卦象效果等价物）。 */
    public void setLiveSpeedPower(int value) {
        liveSpeedPower = Math.max(0, value);
    }

    /** 潜能修为存量（235 BUY 与 GM 面板只读）。 */
    public int holdFaculty() {
        return holdFaculty;
    }

    // ------------------------------------------------------- 演武碎银/团练疲劳（GM 面）

    /**
     * 演武卡扣碎银额度：有限额模式（max>0）超限拒绝。成功则累计今日已耗/已用。
     * 碎银余额（CapitalType1）校验与扣减在 PlayerActor 侧，本方法只管额度账本。
     */
    public boolean tryChargeActSuiYin(int costMillis) {
        if (costMillis < 0) {
            return false;
        }
        if (actDayValueMax > 0 && actCostSuiYin + (long) costMillis > actDayValueMax) {
            return false;
        }
        actCostSuiYin += costMillis;
        actDayValueUsed += costMillis;
        return true;
    }

    /** 今日已耗碎银（毫；GM 面板只读）。 */
    public int actCostSuiYin() {
        return actCostSuiYin;
    }

    /** 每日上限（毫；0 = 不限制。GM 面板只读）。 */
    public int actDayValueMax() {
        return actDayValueMax;
    }

    /**
     * GM set_act_day_limit：设演武每日碎银上限（两，面板输入刻度）。≤0 = 不限制
     * （max=0、额度发大数占位）。重设即重置今日已耗/已用。返回应用后的上限（两）。
     * 账本存文（1 两 = 1000 文，与卡基价/ActCostSuiYin 同刻度）。
     */
    public int setActDayLimit(int liang) {
        actCostSuiYin = 0;
        actDayValueUsed = 0;
        if (liang <= 0) {
            actDayValueMax = 0;
            actDayValue = UNLIMITED_ACT_DAY_VALUE;
            actDayValueLast = UNLIMITED_ACT_DAY_VALUE;
            return 0;
        }
        actDayValueMax = (int) Math.min((long) liang * 1000, UNLIMITED_ACT_DAY_VALUE);
        actDayValue = actDayValueMax;
        return actDayValueMax / 1000;
    }

    /** GM clear_team_fatigue：清团练疲劳（消耗值归零；客户端剩余精力恢复 100%）。 */
    public void clearTeamFacultyFatigue() {
        teamFacultyValue = 0;
    }

    /** GM set_team_faculty_last：团练剩余可抵扣修炼度直设（非负钳制）。 */
    public void setTeamFacultyLast(int value) {
        teamFacultyLast = Math.max(0, value);
    }

    /** 团练消耗值（毫；GM 面板只读，显示换算 = 值/80000 精力消耗）。 */
    public int teamFacultyValue() {
        return teamFacultyValue;
    }

    /** GM set_hold_faculty 复用 setFaculty 语义；潜能购买入口在 FacultyCustomHandler（235/2）。 */

    /** Go facultyReady：选书；未知 id 静默接受（零帧零变更）。返回书槽（1 基），未知 -1。 */
    public int facultyReady(String configId, Instant now) {
        Book target = book(configId);
        if (target == null) {
            return -1;
        }
        facultyName = configId;
        facultyState = FACULTY_STATE_NONE;
        facultyStyle = FACULTY_STYLE_NORMAL;
        fillSpeed = 0;
        lastAdvance = now;
        return books.indexOf(target) + 1;
    }

    /** Go facultyBegin：普通内修按 基础值×倍率（默认 200/min）；演武不计每分钟（fillSpeed=0）。 */
    public void facultyBegin(boolean actStyle, Instant now) {
        facultyState = FACULTY_STATE_CONVERT;
        facultyStyle = actStyle ? FACULTY_STYLE_ACT : FACULTY_STYLE_NORMAL;
        fillSpeed = actStyle ? 0 : computedFillSpeed();
        lastAdvance = now;
    }

    /** Go facultyExit：停修（状态/风格/修速归零）。 */
    public void facultyExit(Instant now) {
        facultyState = FACULTY_STATE_NONE;
        facultyStyle = FACULTY_STYLE_NORMAL;
        fillSpeed = 0;
        lastAdvance = now;
    }

    /** Go activeFacultyCardReward：演武单卡奖励修炼度。 */
    public static final int ACTIVE_FACULTY_CARD_REWARD = 20_000;
    /** Go normalFacultyFillPerMinute。 */
    public static final int NORMAL_FACULTY_FILL_PER_MINUTE = 200;
    /** 新角色修为种子（-Dnineyin.faculty.seed 可配；官服模型下历练是修为主供给）。 */
    public static final int INITIAL_FACULTY = 10_000;

    // ---- 历练层常量（form_faculty.lua:87-111 官方公式钉死）----
    /** 历练槽位数（LiveGroove_1..5@804..808）。 */
    public static final int GROOVE_COUNT = 5;
    /** 每槽毫历练上限：999 点 × 1000。 */
    public static final int GROOVE_CAP = 999_000;
    /** 在线累积步长：每 100 秒 +1 历练（=1000 毫）进第一个未满槽。 */
    public static final long LIVEPOINT_STEP_SECONDS = 100;
    public static final int LIVEPOINT_ACCRUE_MILLIS = 1_000;
    /**
     * 槽基础转化速度（修为/100秒，槽非空才计）：10/20/50/100/200。
     * 五槽全满名俊 = 380 修为/100秒（客户端同公式）；非名俊 ×0.7。
     */
    public static final int[] GROOVE_BASE_SPEED = {10, 20, 50, 100, 200};
    /** 快照时间戳哨兵下界（2000-01-01）：早于此视为无效（Store null 哨兵 = 0001-01-01）。 */
    static final Instant EPOCH_GUARD = Instant.ofEpochSecond(946684800L);

    // ---- 演武碎银/团练疲劳层常量 ----
    /** 不限制模式的额度占位数（毫 = 999999 两）：主面板"可用碎银"显示一个足够大的值。 */
    public static final int UNLIMITED_ACT_DAY_VALUE = 999_999_999;
    /** 团练精力总量（faculty_team_action.ini [setting] TeamValue=80000）：客户端按此反算剩余百分比。 */
    public static final int TEAM_FACULTY_TOTAL = 80_000;

    /** Go awardActiveFaculty：演武卡结算。返回 [槽位(1 基,-1 无), 是否发放, 是否升级, 是否满级]。 */
    public int[] awardActiveFaculty() {
        if (facultyState != FACULTY_STATE_CONVERT || facultyStyle != FACULTY_STYLE_ACT) {
            return new int[]{-1, 0, 0, 0}; // 演武态外的陈旧请求：吞掉不处罚
        }
        Book target = book(facultyName);
        if (target == null) {
            return new int[]{-1, 0, 0, 0};
        }
        int slot = books.indexOf(target) + 1;
        boolean levelUp = false;
        if (target.level < target.maxLevel) {
            target.fill += ACTIVE_FACULTY_CARD_REWARD;
            levelUp = levelUpWhile(target);
            applyModernEffect(target);
        }
        boolean maxLevel = target.level >= target.maxLevel;
        if (maxLevel) {
            target.fill = 0;
        }
        return new int[]{slot, 1, levelUp ? 1 : 0, maxLevel ? 1 : 0};
    }

    /** facultyName 书的 1 基槽位（ticker bookFrame 用），无 → -1。 */
    public int facultyBookSlot() {
        Book target = book(facultyName);
        return target == null ? -1 : books.indexOf(target) + 1;
    }

    /** facultyName 书当前层（演武卡定价的 actfacultyinfo 层号；无书 0）。 */
    public int actBookLevel() {
        Book target = book(facultyName);
        return target == null ? 0 : target.level;
    }

    // ------------------------------------------------------------------ GM 修为/修速

    /** 实际修速 = round(基础值 × 倍率)。GM set_fill_speed 与 facultyBegin 共用。 */
    public int computedFillSpeed() {
        return Math.round(fillSpeedBase * fillSpeedMultiplier);
    }

    /** GM set_faculty：直接设修炼点余额（入口已限非负；本处再钳一次防御）。 */
    public void setFaculty(int value) {
        faculty = Math.max(0, value);
    }

    /** 修为增量入口（235/2 潜能赎回、历练转化共用；非负钳制）。 */
    public void addFaculty(int delta) {
        faculty = Math.max(0, faculty + delta);
    }

    /** GM set_fill_speed：设基础值/倍率并即时重算。返回当前实际修速。 */
    public int setFillSpeed(int base, float multiplier) {
        fillSpeedBase = base;
        fillSpeedMultiplier = multiplier;
        return reapplyFillSpeed();
    }

    /** 当前修炼书 configId（GM 回执/面板展示用；包外只读）。 */
    public String facultyName() {
        return facultyName;
    }

    /** GM set_fill_speed 生效入口：普通内修态即时重算 fillSpeed；停修/演武态只记住
     * 基础值/倍率，下次 facultyBegin 生效（演武不按分钟累积，保持 0）。返回当前实际修速。 */
    public int reapplyFillSpeed() {
        if (facultyState == FACULTY_STATE_CONVERT && facultyStyle == FACULTY_STYLE_NORMAL) {
            fillSpeed = computedFillSpeed();
        }
        return fillSpeed;
    }

    /** GM fill_current_neigong：把当前修炼书（facultyName）本层修炼度填满并结算升级；
     * 恰好升到满级时对齐自然修炼语义（fill=0、停修）。返回约定同 awardActiveFaculty：
     * [槽位(1 基,-1 无书), 是否发放, 是否升级, 是否满级]。 */
    public int[] fillCurrentFaculty() {
        Book target = book(facultyName);
        if (target == null) {
            return new int[]{-1, 0, 0, 0};
        }
        int slot = books.indexOf(target) + 1;
        if (target.level >= target.maxLevel) {
            return new int[]{slot, 0, 0, 1};
        }
        target.fill = target.total;
        boolean levelUp = levelUpWhile(target);
        applyModernEffect(target);
        boolean maxLevel = target.level >= target.maxLevel;
        if (maxLevel) {
            target.fill = 0;
            facultyState = FACULTY_STATE_NONE;
            fillSpeed = 0;
        }
        return new int[]{slot, 1, levelUp ? 1 : 0, maxLevel ? 1 : 0};
    }

    /** 升级循环（advanceFaculty/awardActiveFaculty/fillCurrentFaculty 共用）：
     * 修炼度攒满本层总量即升一层——total +200、maxPower +20、power 顶满。返回是否升层。 */
    private static boolean levelUpWhile(Book item) {
        boolean levelUp = false;
        while (item.fill >= item.total && item.level < item.maxLevel) {
            item.fill -= item.total;
            item.level++;
            item.total += 200;
            item.maxPower += 20;
            item.power = item.maxPower;
            levelUp = true;
        }
        return levelUp;
    }
}
