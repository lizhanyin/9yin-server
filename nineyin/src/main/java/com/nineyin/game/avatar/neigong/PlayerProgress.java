package com.nineyin.game.avatar.neigong;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.nineyin.protocol.PropertyIndex;
import com.nineyin.protocol.PropertyValues;
import com.nineyin.protocol.SceneMessages;

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

    /** Go applyModernEffect：资源可用时以 varprop 结果覆盖。 */
    void applyModernEffect(Book item) {
        try {
            NeiGongCatalog.Effect effect = NeiGongCatalog.load().effect(item.staticData, item.level);
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
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_NEIGONG, "CurNeiGong", PropertyValues.Value.ofString(curNeiGong)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.FACULTY, "Faculty", PropertyValues.Value.ofInt32(faculty)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.FACULTY_STATE, "FacultyState", PropertyValues.Value.ofInt32(facultyState)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.FACULTY_STYLE, "FacultyStyle", PropertyValues.Value.ofInt32(facultyStyle)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.FACULTY_NAME, "FacultyName", PropertyValues.Value.ofString(facultyName)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_FILL_VALUE, "CurFillValue", PropertyValues.Value.ofInt32(fill)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.TOTAL_FILL_VALUE, "TotalFillValue", PropertyValues.Value.ofInt32(total)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_LEVEL, "CurLevel", PropertyValues.Value.ofInt32(level)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.FILL_SPEED, "FillSpeed", PropertyValues.Value.ofInt32(fillSpeed)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.POWER_VALUE, "PowerValue", PropertyValues.Value.ofInt32(power)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_POWER_VALUE, "MaxPowerValue", PropertyValues.Value.ofInt32(maxPower)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.SP, "SP", PropertyValues.Value.ofInt32(a.sp)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.STR, "Str", PropertyValues.Value.ofInt32(a.str)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.STA, "Sta", PropertyValues.Value.ofInt32(a.sta)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.DEX, "Dex", PropertyValues.Value.ofInt32(a.dex)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.ING, "Ing", PropertyValues.Value.ofInt32(a.ing)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.SPI, "Spi", PropertyValues.Value.ofInt32(a.spi)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MELEE_POWER, "MeleePower", PropertyValues.Value.ofInt32(a.meleePower)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAGIC_POWER, "MagicPower", PropertyValues.Value.ofInt32(a.magicPower)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.PHY_HIT, "PhyHit", PropertyValues.Value.ofInt32(a.phyHit)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAGIC_HIT, "MagicHit", PropertyValues.Value.ofInt32(a.magicHit)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.DODGE, "Dodge", PropertyValues.Value.ofInt32(a.dodge)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_PARRY, "MaxParry", PropertyValues.Value.ofInt32(a.maxParry)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.PHY_DEF, "PhyDef", PropertyValues.Value.ofInt32(a.phyDef)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.STI_DEF, "StiDef", PropertyValues.Value.ofInt32(a.stiDef)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.NEG_DEF, "NegDef", PropertyValues.Value.ofInt32(a.negDef)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAS_DEF, "MasDef", PropertyValues.Value.ofInt32(a.masDef)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.JUJ_DEF, "JujDef", PropertyValues.Value.ofInt32(a.jujDef)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MIN_MELEE_DAMAGE, "MinMeleeDamage", PropertyValues.Value.ofInt32(a.minMeleeDamage)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_MELEE_DAMAGE, "MaxMeleeDamage", PropertyValues.Value.ofInt32(a.maxMeleeDamage)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_HP_ADD, "MaxHPAdd", PropertyValues.Value.ofInt32(a.maxHpAdd)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_MP_ADD, "MaxMPAdd", PropertyValues.Value.ofInt32(a.maxMpAdd)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_PARRY_ADD, "MaxParryAdd", PropertyValues.Value.ofInt32(maxParryAdd)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MIN_MAGIC_DEF_ADD, "MinMagicDefAdd", PropertyValues.Value.ofInt32(minMagicDefAdd)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MAX_LEVEL, "MaxLevel", PropertyValues.Value.ofInt32(maxLevel)));
        return properties;
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
        return SceneMessages.viewAdd(VIEWPORT_NEI_GONG, slot, List.of(
                SceneMessages.ViewProperty.ofString(7, book.configId),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.STATIC_DATA, book.staticData),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.ITEM_TYPE, book.itemType),
                SceneMessages.ViewProperty.ofInt(6, book.level),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.MAX_LEVEL, book.maxLevel),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.CUR_FILL_VALUE, book.fill),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.TOTAL_FILL_VALUE, book.total),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.POWER_VALUE, book.power),
                SceneMessages.ViewProperty.ofInt(PropertyIndex.MAX_POWER_VALUE, book.maxPower)));
    }

    public Snapshot snapshot() {
        List<BookSnapshot> saved = new ArrayList<>(books.size());
        for (Book item : books) {
            saved.add(new BookSnapshot(item.configId, item.level, item.fill, item.total, item.power, item.maxPower));
        }
        return new Snapshot(curNeiGong, faculty, facultyState, facultyStyle, facultyName,
                fillSpeed, lastAdvance, saved);
    }

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

    /** Go advanceFaculty：按整分钟以 FillSpeed 结算修炼，余数留在 lastAdvance。 */
    void advanceFaculty(Instant now) {
        if (facultyState != FACULTY_STATE_CONVERT || facultyStyle != FACULTY_STYLE_NORMAL || fillSpeed <= 0) {
            lastAdvance = now;
            return;
        }
        if (lastAdvance == null || now.isBefore(lastAdvance)) {
            lastAdvance = now;
            return;
        }
        long minutes = Duration.between(lastAdvance, now).toMinutes();
        if (minutes <= 0) {
            return;
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
                return;
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
            return;
        }
    }
}
