package com.nineyin.game;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.nineyin.protocol.BootstrapPackets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 修炼/内功角色状态机（移植参考服 Go player_progress.go）。
 * 数据来源两路：{@link #bootstrap()} 写死种子（无库回退，与历史行为一致）；
 * {@link #fromSnapshotJson(JSONObject, WuxueLevelInfo)} 从 role_faculty 快照恢复。
 * 升级修炼度优先查 {@link WuxueLevelInfo} 曲线，无曲线回退旧公式 total+=200 / maxPower+=20。
 */
public final class FacultyState {

    // ---- 状态常量（对齐 Go facultyState*/facultyStyle*） ----
    public static final int STATE_NONE = 0;
    public static final int STATE_CONVERT = 2;
    public static final int STYLE_NORMAL = 1;
    public static final int STYLE_ACT = 2;

    /** 演武选卡一次的固定修炼度（Go activeFacultyCardReward，本地测试服基准）。 */
    public static final int ACTIVE_CARD_REWARD = 20_000;
    /** 内修每分钟修炼度（Go normalFacultyFillPerMinute）。 */
    public static final int NORMAL_FILL_PER_MINUTE = 200;
    /** 曲线缺失时的默认等级上限（Go starter 默认 36）。 */
    public static final int DEFAULT_MAX_LEVEL = 36;

    /** 单本已学内功（View 43 行 + 修炼进度 + 当前等级特效）。 */
    public static final class Book {
        public final String configId;
        public int staticData;
        public int itemType;
        public int level;
        public int maxLevel;
        public int fill;
        public int total;
        public int power;
        public int maxPower;
        // 当前等级特效（varprop 目录回填；Go learnedNeiGong 同款字段，种子值兜底）
        public String buffId = "";
        public int buffStaticData;
        public int buffLevel;
        public int strAdd, staAdd, dexAdd, ingAdd, spiAdd;
        public int maxHpAdd, maxMpAdd, maxParryAdd, minMagicDefAdd;
        // 内功属性三件套（IDA 钉死：BelongType→太极/阴柔/阳刚；wuxuebaseinfo nWuXing；neigong.ini School）
        public String attribute = "";
        public int wuXing;
        public String school = "";
        /** varprop/proppack 全量 stats 快照（MeleePowerAdd/DamageAdd/Mul 等通用键面）。 */
        public Map<String, Integer> effectStats = Map.of();

        Book(String configId, int staticData, int itemType, int level, int maxLevel,
             int fill, int total, int power, int maxPower) {
            this.configId = configId;
            this.staticData = staticData;
            this.itemType = itemType;
            this.level = level;
            this.maxLevel = maxLevel;
            this.fill = fill;
            this.total = total;
            this.power = power;
            this.maxPower = maxPower;
        }

        /** game_skills 目录后补（快照行不带 static_data/item_type）。 */
        public void applyCatalog(int staticData, int itemType) {
            if (staticData > 0) {
                this.staticData = staticData;
            }
            if (itemType > 0) {
                this.itemType = itemType;
            }
        }

        /**
         * varprop 目录回填当前等级特效（Go applyModernEffect）：运功 Buff
         * （BufferID→buff_new StaticData）与五维/上限 Add。缺目录/缺该级行
         * 保留原值（种子/旧值），best-effort 不抛错。
         */
        public void applyEffect(NeigongEffectCatalog catalog) {
            NeigongEffectCatalog.Effect effect =
                    catalog == null ? null : catalog.effect(staticData, level);
            if (effect == null) {
                // 特效缺行仍可解析静态三件套（属性/五行/门派按 staticData/configId 查表）
                if (catalog != null) {
                    attribute = catalog.attributeOf(staticData, configId);
                    wuXing = catalog.wuXingOf(configId);
                    school = catalog.schoolOf(configId);
                }
                return;
            }
            buffId = effect.buffId();
            buffStaticData = effect.buffStaticData();
            buffLevel = effect.buffLevel();
            strAdd = effect.stat("StrAdd");
            staAdd = effect.stat("StaAdd");
            dexAdd = effect.stat("DexAdd");
            ingAdd = effect.stat("IngAdd");
            spiAdd = effect.stat("SpiAdd");
            maxHpAdd = effect.stat("MaxHPAdd");
            maxMpAdd = effect.stat("MaxMPAdd");
            maxParryAdd = effect.stat("MaxParryAdd");
            minMagicDefAdd = effect.stat("MinMagicDefAdd");
            effectStats = effect.stats();
            attribute = catalog.attributeOf(staticData, configId);
            wuXing = catalog.wuXingOf(configId);
            school = catalog.schoolOf(configId);
        }

        BootstrapPackets.NeiGongBook toViewBook() {
            return new BootstrapPackets.NeiGongBook(configId, staticData, itemType, level,
                    maxLevel, total, power, maxPower,
                    buffId, buffStaticData, buffLevel, fill,
                    school, wuXing, strAdd, dexAdd, ingAdd, spiAdd, staAdd);
        }
    }

    public final List<Book> books = new ArrayList<>();
    public String curNeigong = "";
    public int faculty;
    public int facultyState = STATE_NONE;
    public int facultyStyle = STYLE_NORMAL;
    public String facultyName = "";
    public int fillSpeed;
    public Instant lastAdvance = Instant.EPOCH;
    /** 风水宝地加成（千分比；SceneFlow 按站位 Fsbd 标记 × 修炼书武学属性刷新，
     *  只放大内修获取与 FILL_SPEED 显示，不落库）。 */
    public int fengshuiPermille;
    /** 变更未落库标记（advance/切换/选卡置位，落库后清除）。 */
    public boolean persistDirty;

    /** 面板显示用有效修炼速度（FILL_SPEED=772）：内修中叠加风水加成，其余状态返回原值。 */
    public int displayFillSpeed() {
        if (facultyState != STATE_CONVERT || facultyStyle != STYLE_NORMAL || fillSpeed <= 0) {
            return fillSpeed;
        }
        return (int) ((long) fillSpeed * (1000L + Math.max(0, fengshuiPermille)) / 1000L);
    }

    // ---- 种子（Go newPlayerProgress 六本；太极 30 级为本地测试基线） ----

    public static FacultyState bootstrap() {
        FacultyState state = new FacultyState();
        // 三本 30 级主内功带 Go 内联种子特效（无资源目录时的回退值，等级 30 varprop 行）；
        // refreshEffects 后安装目录存在则以数据覆盖
        Book wd = new Book("ng_wd_001", 5, 1002, 30, 36, 0, 273200, 4550, 4550);
        wd.buffId = "buf_ng_wd_001";
        wd.buffStaticData = 5954;
        wd.buffLevel = 3;
        wd.strAdd = 45;
        wd.staAdd = 31;
        wd.dexAdd = 13;
        wd.ingAdd = 46;
        wd.spiAdd = 13;
        wd.maxHpAdd = 1630;
        wd.maxMpAdd = 490;
        wd.maxParryAdd = 840;
        wd.minMagicDefAdd = 45;
        wd.attribute = "太极";
        wd.wuXing = 5;
        wd.school = "ng_wd";
        Book yh = new Book("ng_yh_001", 114, 1002, 30, 71, 0, 579600, 8180, 8180);
        yh.buffId = "buf_ng_yh_001";
        yh.buffStaticData = 13073;
        yh.buffLevel = 3;
        yh.strAdd = 55;
        yh.staAdd = 57;
        yh.dexAdd = 70;
        yh.ingAdd = 97;
        yh.spiAdd = 67;
        yh.maxHpAdd = 4050;
        yh.maxMpAdd = 1130;
        yh.maxParryAdd = 5040;
        yh.minMagicDefAdd = 100;
        yh.attribute = "阴柔";
        yh.school = "ng_yh";
        Book th = new Book("ng_th_001", 113, 1002, 30, 71, 0, 579600, 8180, 8180);
        th.buffId = "buf_ng_th_001";
        th.buffStaticData = 13434;
        th.buffLevel = 3;
        th.strAdd = 66;
        th.staAdd = 56;
        th.dexAdd = 52;
        th.ingAdd = 103;
        th.spiAdd = 68;
        th.maxHpAdd = 4050;
        th.maxMpAdd = 1135;
        th.maxParryAdd = 5070;
        th.minMagicDefAdd = 100;
        th.school = "ng_th";
        state.books.add(wd);
        state.books.add(yh);
        state.books.add(th);
        state.books.add(new Book("ng_wd_002", 6, 1002, 1, 36, 0, 700, 120, 120));
        state.books.add(new Book("ng_jh_001", 1, 1002, 1, 36, 0, 700, 120, 120));
        state.books.add(new Book("ng_em_001", 8, 1002, 1, 36, 0, 700, 120, 120));
        state.curNeigong = state.books.get(0).configId;
        state.faculty = 9_999_999;
        state.facultyState = STATE_CONVERT;
        state.facultyStyle = STYLE_NORMAL;
        state.facultyName = state.books.get(0).configId;
        state.fillSpeed = NORMAL_FILL_PER_MINUTE;
        state.lastAdvance = Instant.now();
        state.refreshEffects(NeigongEffectCatalog.instance());
        return state;
    }

    // ---- 查询 ----

    public Book book(String id) {
        for (Book b : books) {
            if (b.configId.equals(id)) {
                return b;
            }
        }
        return null;
    }

    /** 当前运功书（Go book(curNeiGong)；未学/空返回 null）。 */
    public Book equipped() {
        return book(curNeigong);
    }

    /** 全书本回填 varprop 特效（Go refreshAllModernEffects：best-effort，失败保留种子值）。 */
    public void refreshEffects(NeigongEffectCatalog catalog) {
        for (Book book : books) {
            book.applyEffect(catalog);
        }
    }

    /** 面板目标书：修炼中的书优先，否则当前运功书（Go targetBook）。 */
    public Book targetBook() {
        if (!facultyName.isEmpty()) {
            Book byName = book(facultyName);
            if (byName != null) {
                return byName;
            }
        }
        return book(curNeigong);
    }

    public int slotOf(String id) {
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).configId.equals(id)) {
                return i + 1;
            }
        }
        return 0;
    }

    public List<BootstrapPackets.NeiGongBook> viewBooks() {
        List<BootstrapPackets.NeiGongBook> out = new ArrayList<>(books.size());
        for (Book b : books) {
            out.add(b.toViewBook());
        }
        return out;
    }

    // ---- 215 运功切换（Go equip） ----

    /** 未学习返回 false；成功置 curNeigong 并标记落库。 */
    public boolean equip(String id) {
        if (book(id) == null) {
            return false;
        }
        curNeigong = id;
        persistDirty = true;
        return true;
    }

    // ---- 1006 修炼状态机（Go facultyReady/Begin/Exit/advance/award） ----

    /** 选定修炼书（sub=1）：未学习返回 0；成功返回 View 43 槽位并复位状态。 */
    public int facultyReady(String id) {
        int slot = slotOf(id);
        if (slot == 0) {
            return 0;
        }
        facultyName = id;
        // form_wuxue_faculty 只渲染 state 0（空闲可开始）与 2（运功中）；
        // 置 READY=1 会让按钮停在 XML 默认态（Go 同款注释）。
        facultyState = STATE_NONE;
        facultyStyle = STYLE_NORMAL;
        fillSpeed = 0;
        lastAdvance = Instant.now();
        persistDirty = true;
        return slot;
    }

    /** 开始内修（11）/演武（21）：演武速度置 0，选卡结算后才发修炼度。 */
    public int facultyBegin(int style, Instant now) {
        if (book(facultyName) == null) {
            return 0;
        }
        facultyStyle = style == STYLE_ACT ? STYLE_ACT : STYLE_NORMAL;
        facultyState = STATE_CONVERT;
        fillSpeed = facultyStyle == STYLE_ACT ? 0 : NORMAL_FILL_PER_MINUTE;
        lastAdvance = now;
        persistDirty = true;
        return slotOf(facultyName);
    }

    /** 退出内修/演武（12/22）。 */
    public void facultyExit() {
        facultyState = STATE_NONE;
        facultyStyle = STYLE_NORMAL;
        fillSpeed = 0;
        lastAdvance = Instant.now();
        persistDirty = true;
    }

    /**
     * 内修整分钟结算（Go advanceFaculty）：按 fillSpeed 累积、修为点数折半消耗，
     * 余数留在 lastAdvance（重连/重启不丢部分时间）。返回发生推进的 View 43 槽位，0=无推进。
     */
    public int advanceFaculty(Instant now, WuxueLevelInfo curves) {
        return advanceFaculty(now, curves, 0);
    }

    /**
     * 带风水宝地加成（千分比，{@link FengshuiSpots}）的内修结算：
     * 加成只放大每分钟修炼度获取，修为消耗折半规则不变。
     */
    public int advanceFaculty(Instant now, WuxueLevelInfo curves, int fengshuiPermille) {
        if (facultyState != STATE_CONVERT || facultyStyle != STYLE_NORMAL || fillSpeed <= 0) {
            lastAdvance = now;
            return 0;
        }
        if (lastAdvance.equals(Instant.EPOCH) || now.isBefore(lastAdvance)) {
            lastAdvance = now;
            return 0;
        }
        long minutes = (now.toEpochMilli() - lastAdvance.toEpochMilli()) / 60_000L;
        if (minutes <= 0) {
            return 0;
        }
        lastAdvance = lastAdvance.plusMillis(minutes * 60_000L);
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            if (!book.configId.equals(facultyName)) {
                continue;
            }
            long fill = (long) fillSpeed * minutes * (1000L + Math.max(0, fengshuiPermille)) / 1000L;
            long cost = (fill + 1) / 2;
            if (cost > faculty) {
                cost = faculty;
                fill = cost * 2;
            }
            if (fill <= 0) {
                facultyState = STATE_NONE;
                fillSpeed = 0;
                return 0;
            }
            faculty -= (int) cost;
            book.fill += (int) fill;
            settleLevelUps(book, curves);
            if (book.level >= book.maxLevel) {
                book.fill = 0;
                facultyState = STATE_NONE;
                fillSpeed = 0;
            }
            persistDirty = true;
            return i + 1;
        }
        return 0;
    }

    /** 演武选卡结算（Go awardActiveFaculty）。返回 [槽位, 已获, 升级, 满级]；过期 SUB_ACT_PAY 返回槽位 0。 */
    public ActiveAward awardActiveFaculty(WuxueLevelInfo curves) {
        if (facultyState != STATE_CONVERT || facultyStyle != STYLE_ACT) {
            // 表单关闭/切模式时客户端可能残留一发延迟 PAY，静默吞掉不断连（Go 同款）。
            return new ActiveAward(0, false, false, false);
        }
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            if (!book.configId.equals(facultyName)) {
                continue;
            }
            if (book.level >= book.maxLevel) {
                return new ActiveAward(i + 1, false, false, true);
            }
            int previousLevel = book.level;
            book.fill += ACTIVE_CARD_REWARD;
            settleLevelUps(book, curves);
            if (book.level >= book.maxLevel) {
                book.fill = 0;
            }
            persistDirty = true;
            return new ActiveAward(i + 1, true, book.level != previousLevel, book.level >= book.maxLevel);
        }
        return new ActiveAward(0, false, false, false);
    }

    public record ActiveAward(int slot, boolean awarded, boolean levelUp, boolean maxLevel) {
    }

    /** 连续升级循环：新等级修炼度优先取曲线 r[新等级]，缺曲线回退 total+=200；威力 +20/级。
     *  升级后按新等级重算 varprop 特效（五维/上限 Add 与运功 Buff 随层数变化）。 */
    private void settleLevelUps(Book book, WuxueLevelInfo curves) {
        boolean leveled = false;
        while (book.fill >= book.total && book.level < book.maxLevel) {
            book.fill -= book.total;
            book.level++;
            long curveTotal = curves == null ? -1L : curves.total(book.configId, book.level);
            book.total = curveTotal > 0 ? (int) curveTotal : book.total + 200;
            book.maxPower += 20;
            book.power = book.maxPower;
            leveled = true;
        }
        if (leveled) {
            book.applyEffect(NeigongEffectCatalog.instance());
        }
    }

    // ---- role_faculty 快照（托管键子集；扩展键由 RoleFacultyStore 合并保留） ----

    /** 解析快照托管键。非法行（等级越界/total≤0/fill&lt;0）跳过，整段损坏返回 null。 */
    public static FacultyState fromSnapshotJson(JSONObject snapshot, WuxueLevelInfo curves) {
        if (snapshot == null) {
            return null;
        }
        JSONArray books = snapshot.getJSONArray("books");
        if (books == null || books.isEmpty()) {
            return null;
        }
        FacultyState state = new FacultyState();
        for (int i = 0; i < books.size(); i++) {
            JSONObject row = books.getJSONObject(i);
            if (row == null) {
                continue;
            }
            String configId = row.getString("config_id");
            Integer level = row.getInteger("level");
            Integer fill = row.getInteger("fill");
            Integer total = row.getInteger("total");
            Integer power = row.getInteger("power");
            Integer maxPower = row.getInteger("max_power");
            if (configId == null || configId.isEmpty() || level == null || level < 1
                    || total == null || total <= 0 || fill == null || fill < 0
                    || power == null || maxPower == null || maxPower <= 0) {
                continue;
            }
            int maxLevel = row.getIntValue("max_level");
            if (maxLevel <= 0) {
                int curveMax = curves == null ? -1 : curves.maxLevel(configId);
                maxLevel = curveMax > 0 ? curveMax : DEFAULT_MAX_LEVEL;
            }
            state.books.add(new Book(configId, 0, 1002, level, maxLevel,
                    fill, total, power, maxPower));
        }
        if (state.books.isEmpty()) {
            return null;
        }
        state.curNeigong = orDefault(snapshot.getString("cur_neigong"), state.books.get(0).configId);
        state.faculty = snapshot.getIntValue("faculty");
        state.facultyState = snapshot.containsKey("faculty_state")
                ? snapshot.getIntValue("faculty_state") : STATE_NONE;
        if (state.facultyState != STATE_NONE && state.facultyState != STATE_CONVERT) {
            state.facultyState = STATE_NONE;
        }
        state.facultyStyle = snapshot.containsKey("faculty_style")
                && snapshot.getIntValue("faculty_style") == STYLE_ACT
                ? STYLE_ACT : STYLE_NORMAL;
        state.facultyName = orDefault(snapshot.getString("faculty_name"), "");
        state.fillSpeed = snapshot.getIntValue("fill_speed");
        String lastAdvance = snapshot.getString("last_advance_utc");
        if (lastAdvance != null && !lastAdvance.isEmpty()) {
            try {
                state.lastAdvance = Instant.parse(lastAdvance);
            } catch (RuntimeException ignored) {
                state.lastAdvance = Instant.now();
            }
        } else {
            state.lastAdvance = Instant.now();
        }
        return state;
    }

    /** 托管键 JSON（RoleFacultyStore 合并进现有快照文档，保留 F 版 exe 的扩展键）。 */
    public JSONObject toManagedJson() {
        JSONObject out = new JSONObject(true);
        JSONArray bookArray = new JSONArray(books.size());
        for (Book b : books) {
            JSONObject row = new JSONObject(true);
            row.put("config_id", b.configId);
            row.put("level", b.level);
            row.put("fill", b.fill);
            row.put("total", b.total);
            row.put("power", b.power);
            row.put("max_power", b.maxPower);
            bookArray.add(row);
        }
        out.put("books", bookArray);
        out.put("cur_neigong", curNeigong);
        out.put("faculty", faculty);
        out.put("faculty_state", facultyState);
        out.put("faculty_style", facultyStyle);
        out.put("faculty_name", facultyName);
        out.put("fill_speed", fillSpeed);
        out.put("last_advance_utc", lastAdvance.toString());
        return out;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
