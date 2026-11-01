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
                           String facultyName, int fillSpeed, Instant lastAdvanceUtc,
                           List<BookSnapshot> books) {
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
    Instant lastAdvance;
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
        progress.faculty = 9_999_999;
        progress.facultyState = FACULTY_STATE_CONVERT;
        progress.facultyStyle = FACULTY_STYLE_NORMAL;
        progress.facultyName = progress.books.get(0).configId;
        progress.fillSpeed = 200;
        progress.lastAdvance = Instant.now(clock);
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
        // NorFacultyAdd(774)：基准修速（普通内修每分钟 200），与加成后的 FillSpeed 分列下发；
        // F 盘实证 FillSpeed=516 时此值恒 200。
        Props.add(properties, "NorFacultyAdd", PropertyValues.Value.ofInt32(
                facultyStyle == FACULTY_STYLE_ACT ? 0 : NORMAL_FACULTY_FILL_PER_MINUTE));
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
                fillSpeed, lastAdvance, saved);
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
        refreshAllModernEffects();
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
            while (item.fill >= item.total && item.level < item.maxLevel) {
                item.fill -= item.total;
                item.level++;
                item.total += 200;
                item.maxPower += 20;
                item.power = item.maxPower;
            }
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

    /** Go facultyBegin：普通内修 200/min；演武不计每分钟（fillSpeed=0）。 */
    public void facultyBegin(boolean actStyle, Instant now) {
        facultyState = FACULTY_STATE_CONVERT;
        facultyStyle = actStyle ? FACULTY_STYLE_ACT : FACULTY_STYLE_NORMAL;
        fillSpeed = actStyle ? 0 : NORMAL_FACULTY_FILL_PER_MINUTE;
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
            while (target.fill >= target.total && target.level < target.maxLevel) {
                target.fill -= target.total;
                target.level++;
                target.total += 200;
                target.maxPower += 20;
                target.power = target.maxPower;
                levelUp = true;
            }
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
}
