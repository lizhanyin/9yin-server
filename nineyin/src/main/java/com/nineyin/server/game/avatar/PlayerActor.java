package com.nineyin.server.game.avatar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.game.avatar.panel.EquipAffixCatalog;
import com.nineyin.server.game.avatar.panel.EquipEffectCatalog;
import com.nineyin.server.game.avatar.panel.PanelState;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.protocol.NpcSchema;
import com.nineyin.server.protocol.PacketWriter;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.db.role.Roles;

/**
 * 玩家 actor 的登录时刻子集，对齐 Go cmd/protocol-probe/player_actor.go：
 * newPlayerActor 初始态、addObject（0x0D 全归档）、vitalUpdate（0x10）、
 * 内功 Buff 同步与 restoreFaculty。时间全部走注入 Clock（Go time.Now()）。
 */
public final class PlayerActor {

    public static final int PLAYER_OBJECT_ID = 1;
    public static final int PLAYER_OWNER_ID = 1;
    public static final int MAX_PLAYER_SP = 100;
    static final Duration INNER_POWER_BUFF_LEASE = Duration.ofHours(24);
    /** Go innerPowerBuffSlot：内功 BuffStore 槽（BufferInfo4）。 */
    static final int INNER_POWER_BUFF_SLOT = 4;

    /** qinggong_9 轻身缓降（buf_qg_light）：抓包/配置实证常量（buff_static[218]）。 */
    public static final String GLIDE_BUFF_CONFIG = "buf_qg_light";
    public static final long GLIDE_BUFF_STATIC_DATA = 218L;
    public static final int GLIDE_BUFF_CATEGORY = 2048;
    public static final long GLIDE_BUFF_LIFETIME_MS = 600_000L;
    /** buff_varprop[23001] L1 CCQGP=-10：缓降每秒消耗轻功值 10。 */
    public static final int GLIDE_POINT_COST_PER_SECOND = 10;

    /**
     * 御风神水状态（buf_ride_yufeng）：大轻功（百鸟凌云系列）一/二/三段冲刺的
     * 客户端条件包要求 BuffStore 持有御风。buff_new[buf_ride_yufeng] StaticData=10293、
     * buff_static[10293] Category=0（与缓降 218→2048 同口径表实证）；官方语义每 10s
     * 耗 1% 内力上限且进战斗即摘（buff_static EndFightClear=0，摘除在客户端 lua
     * buf\ride\buf_ride_yufeng.lua）——GM 版不模拟消耗，固定 10 分钟租约可续。
     */
    public static final String YUFENG_BUFF_CONFIG = "buf_ride_yufeng";
    public static final long YUFENG_BUFF_STATIC_DATA = 10_293L;
    public static final int YUFENG_BUFF_CATEGORY = 0;
    public static final long YUFENG_BUFF_LIFETIME_MS = 600_000L;

    static class Motion {
        // 默认移动/奔跑速度 5（2026-11-02 由 25 下调；25 过快，GM 面板可改）
        float moveSpeed = 5;
        float runSpeed = 5;
        float gravity = 9.8f;
    }

    record ActiveBuff(long staticData, Instant expiresUtc, String configId, int level) {
    }

    /** 内功护盾（shield{amount,life_ms} 指令产物；FIFO 消耗，到期/耗尽即弃）。 */
    record NeiGongShield(int amount, long expiresAtMs) {
    }

    private final Actor actor;
    private final Clock clock;
    /** 碎银（CapitalType1）：volatile——addObject/vital 归档线程与 GM 设置并发读写。 */
    private volatile int silver;
    /** 官银（CapitalType2）/ 黄金（CapitalType0）：GM 会话级数值；menu2 的 vip.json 持久化待接。 */
    private volatile int guanYin;
    private volatile int gold;
    private String faction;
    private final Motion motion = new Motion();
    private final java.util.Map<Integer, ActiveBuff> buffs = new java.util.HashMap<>();
    /** 内功特效运行期 state（bookId → 脚本返回扁平表，Java 持有回喂；内存态随会话不落档）。 */
    private final java.util.Map<String, java.util.Map<String, Object>> neigongState =
            new java.util.HashMap<>();
    /** 内功特效各书最近有效触发时刻（bookId → epoch ms，算 ctx.since_last_ms）。 */
    private final java.util.Map<String, Long> neigongLastFireAt = new java.util.HashMap<>();
    /** 内功护盾池（受击路径先扣盾再落血）。 */
    private final java.util.List<NeiGongShield> neigongShields = new java.util.ArrayList<>();
    /** 右键格挡：54 按值幂等落态（0=按下/1=松开），状态变化才回帧（F 盘抓包 78 发→50 沿）。 */
    private volatile boolean parrying;
    final PlayerProgress progress;
    /** 面板实时状态（身份块字段 + 装备词缀累积层，四期）。 */
    private final PanelState panel = new PanelState();
    /** 已穿武器 ItemType（101-119 武器/暗器族；0=空手）。skill_use_limit 武器门控数据源，
     *  登录装载与穿脱（applyEquipBonuses 全量重算点）同步。 */
    private volatile int equippedWeaponItemType;
    /** 经脉修炼状态（修炼经脉/周天/进度/总等级权威源；登录恢复）。 */
    private final com.nineyin.server.game.avatar.jingmai.PlayerMeridian meridian;
    /** 任务状态（已接/已完成；登录恢复，TaskStore 双源持久化）。 */
    private final com.nineyin.server.game.avatar.task.PlayerTask task =
            new com.nineyin.server.game.avatar.task.PlayerTask();
    /** active_jingmai_rec 行是否已下发（jmActCount=1，激活位=当前修炼经脉）。 */
    private boolean meridianActivated;
    /** qinggong_9 轻身缓降租约（216 flag=1 挂 / flag=0 摘 / 点耗尽强摘）。 */
    private boolean gliding;
    private int glideBuffSlot;
    private Instant glideEndsAt;
    /** 持续型轻功点租约（踏波行/上天梯/壁虎游墙等 216 flag=1..0）：无 buff 链，只做秒耗。 */
    private boolean drifting;
    private String driftQingGongId;
    private int driftDrainPerSecond;
    /** 即时型轻功最近扣费时刻（引擎重报闸）。 */
    private final java.util.Map<String, Instant> instantQingGongLastCharge = new java.util.HashMap<>();
    /**
     * 即时型轻功同 id 重扣最小间隔：对齐官方冲刺链节奏（雁行功 13 点/冲，冲距 6.5m
     * @ 25m/s + 收势 ≈ 0.5s/次 = 26 点/s 上限）；引擎逐帧重发（10-30/s）全拦。
     */
    private static final Duration INSTANT_QINGGONG_REFIRE_GUARD = Duration.ofMillis(500);
    /** 轻功分档回复累积器（QING_GONG_RECOVERY_INTERVAL_MS 一档）。 */
    private long qingGongRecoveryElapsedMs;
    /** 当前格挡值（ParryValue idx475 动态下发；上限=ParryValue 满值 parityParryValue()）。 */
    private int parryValue;
    private int logicStateOverride = -1;

    /** 右键格挡 BuffInParry：buff 槽 11（BufferInfo11@639）、StaticData 4558、24h（老分支+参考 setPlayerBlock）。 */
    public static final int PARRY_BUFF_SLOT = 11;
    public static final long PARRY_BUFF_STATIC_DATA = 4558L;
    public static final long PARRY_BUFF_DURATION_MS = 24L * 60 * 60 * 1000;

    /**
     * 格挡值回复速度（ParryUpSpeed 面板值，F 抓包 61579 登录面板 idx479 实证=4）：
     * 按住格挡不回复，松开后每秒 +4 至上限（玩家资料：回复速度受内功/宝物/经脉影响，
     * 此处为无加成基线）。
     */
    public static final int PARRY_UP_SPEED_BASE = 4;
    /**
     * 轻功值官方分档回复节拍（玩家资料实测）：每 3 秒一档——101..150 → +10、
     * 51..100 → +5、0..50 → +3（0→150 全程 96s）。消耗中（缓降/踏波行租约）暂停。
     */
    public static final long QING_GONG_RECOVERY_INTERVAL_MS = 3000L;

    public static PlayerActor create(String name, int silver, Clock clock) {
        return new PlayerActor(name, silver, clock);
    }

    private PlayerActor(String name, int silver, Clock clock) {
        this.actor = Actor.newPlayer(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, name);
        this.silver = silver;
        this.faction = "force_yihua";
        this.clock = clock;
        this.progress = PlayerProgress.create(clock);
        this.meridian = com.nineyin.server.game.avatar.jingmai.PlayerMeridian.create(
                com.nineyin.server.game.avatar.jingmai.MeridianCatalog.load(), clock);
        this.parryValue = progress.parityParryValue();
        syncEquippedNeiGongBuff(clock.instant());
        syncEquippedNeiGongResources();
    }

    public Clock clock() {
        return clock;
    }

    public PlayerProgress progress() {
        return progress;
    }

    public int silver() {
        return silver;
    }

    public int guanYin() {
        return guanYin;
    }

    public int gold() {
        return gold;
    }

    /** 当前怒气（progress.attributes.sp）。 */
    public int sp() {
        return progress.sp();
    }

    // ------------------------------------------------------------------ 货币/怒气

    /** Go setSilver：碎银（CapitalType1，会话级；Go 亦不持久化）。 */
    public void setSilver(int value) {
        silver = value;
    }

    /** Go silverUpdate：单帧 0x10 归档（index 111 Go 方言 / 官方表按名解析）。 */
    public byte[] silverUpdate() {
        return capitalUpdate("CapitalType1", silver);
    }

    /**
     * menu2 exe 取证（9yin-game-native-menu.exe main.handle.func12）：
     * set_silver_card(→set_guanyin) case = setSilverCard + silverCardUpdate
     * + setAction「官银设为 %d」+ log「GM set CapitalType2=%d」。
     */
    public byte[] setGuanYin(int value) {
        guanYin = value;
        return capitalUpdate("CapitalType2", value);
    }

    /** 同上：set_gold case = setGold + goldUpdate +「黄金设为 %d」+ CapitalType0。 */
    public byte[] setGold(int value) {
        gold = value;
        return capitalUpdate("CapitalType0", value);
    }

    /** Go setSP：改权威怒气并立即回单帧 0x10（主角色 UI 消费）。 */
    public SPUpdate setSP(int value) {
        int sp = progress.setSP(value);
        return new SPUpdate(sp, capitalUpdate("SP", sp));
    }

    /** Go addSP：GM 调整与战斗怒气的同一饱和入口。 */
    public SPUpdate addSP(int delta) {
        int sp = progress.addSP(delta);
        return new SPUpdate(sp, capitalUpdate("SP", sp));
    }

    /** Go (int32, []byte) 返回对的 Java 形态。 */
    public record SPUpdate(int sp, byte[] frame) {
    }

    private static byte[] capitalUpdate(String name, int value) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, name, PropertyValues.Value.ofInt32(value));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    public String faction() {
        return faction;
    }

    public void setFaction(String faction) {
        this.faction = faction;
    }

    // ----------------------------------------------------------------- 快捷栏

    /** shortcut_rec 一行：索引、种类（skill/item…）、配置 ID。 */
    public record PlayerShortcut(int index, String kind, String id) {
    }

    /** Go setShortcut 结果：旧行号 / 是否已存在该索引 / 是否完全相同无需变更。 */
    public record ShortcutUpdate(int oldRow, boolean existed, boolean unchanged) {
    }

    /** 201 摘除结果：被删行号 + 行内容（帧 0x12 按行号删，须与列表口径一致）。 */
    public record RemovedShortcut(int row, PlayerShortcut shortcut) {
    }

    private final List<PlayerShortcut> shortcuts = new ArrayList<>();

    /**
     * Go playerActor.setShortcut：同索引同值幂等；同索引不同值**删旧行 + 追加表尾**。
     * 追加而非原位替换是协议要求：下发帧序为 0x12 删旧行 + 0x11 增新行，客户端记录表
     * 删行后重编号、0x11 追加在表尾——服务端列表必须镜像同一套操作，行号口径才不漂移。
     */
    public synchronized ShortcutUpdate setShortcut(int index, String kind, String id) {
        for (int row = 0; row < shortcuts.size(); row++) {
            PlayerShortcut shortcut = shortcuts.get(row);
            if (shortcut.index() != index) {
                continue;
            }
            if (shortcut.kind().equals(kind) && shortcut.id().equals(id)) {
                return new ShortcutUpdate(row, true, true);
            }
            shortcuts.remove(row);
            shortcuts.add(new PlayerShortcut(index, kind, id));
            return new ShortcutUpdate(row, true, false);
        }
        shortcuts.add(new PlayerShortcut(index, kind, id));
        return new ShortcutUpdate(0, false, false);
    }

    /**
     * C2S 201 按可见快捷栏索引摘除。客户端 shortcut_game.lua 的 remove_shortcut(index)
     * → custom_remove_shortcut 上行的一律是栏位索引（game_hand.lua 拖出、put_other_short
     * 交换、form_jingmai 清栏都传 index；Go 注释"按记录行号"是误读形参名）。
     * 未命中返回 null（静默零帧）。
     */
    public synchronized RemovedShortcut removeShortcutIndex(int index) {
        for (int row = 0; row < shortcuts.size(); row++) {
            if (shortcuts.get(row).index() == index) {
                return new RemovedShortcut(row, shortcuts.remove(row));
            }
        }
        return null;
    }

    /** DB 基线整表替换（登录装载；行序=下发序，与客户端记录表逐行对齐）。 */
    public synchronized void resetShortcuts(List<PlayerShortcut> rows) {
        shortcuts.clear();
        shortcuts.addAll(rows);
    }

    public synchronized List<PlayerShortcut> shortcutSnapshot() {
        return new ArrayList<>(shortcuts);
    }

    /** Go playerActor 判定：指定索引是否已有快捷方式（sitcross 幂等门）。 */
    public synchronized boolean hasShortcutIndex(int index) {
        for (PlayerShortcut shortcut : shortcuts) {
            if (shortcut.index() == index) {
                return true;
            }
        }
        return false;
    }

    public synchronized Motion motionSnapshot() {
        Motion snapshot = new Motion();
        snapshot.moveSpeed = motion.moveSpeed;
        snapshot.runSpeed = motion.runSpeed;
        snapshot.gravity = motion.gravity;
        return snapshot;
    }

    /**
     * Go setMoveSpeed：MoveSpeed 与 RunSpeed 同值联动变更（GM 控件对走/跑一个
     * 明确含义），返回单帧普通 0x10 属性归档（index 23/25）。
     */
    public synchronized byte[] setMoveSpeed(float value) {
        motion.moveSpeed = value;
        motion.runSpeed = value;
        List<PropertyValues.Indexed> properties = new ArrayList<>(2);
        Props.add(properties, "MoveSpeed", PropertyValues.Value.ofFloat32(motion.moveSpeed));
        Props.add(properties, "RunSpeed", PropertyValues.Value.ofFloat32(motion.runSpeed));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** Go setGravity：Gravity 变更（index 168），下一次起跳生效；返回单帧 0x10 归档。 */
    public synchronized byte[] setGravity(float value) {
        motion.gravity = value;
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "Gravity", PropertyValues.Value.ofFloat32(motion.gravity));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    public Actor.State actorState() {
        return actor.snapshot();
    }

    /** 包内直访（战斗/测试对权威 HP·MP·轻功态的窄入口）。 */
    Actor actor() {
        return actor;
    }

    // ------------------------------------------------------------------ faculty

    public PlayerProgress.Snapshot facultySnapshot() {
        return progress.snapshot().withVitals(panel.vitalsSnapshot());
    }

    // ------------------------------------------------------------------ jingmai/qinggong

    /** 经脉修炼状态（登录 restore 后为持久化形态）。 */
    public com.nineyin.server.game.avatar.jingmai.PlayerMeridian meridian() {
        return meridian;
    }

    /** 登录恢复经脉存档。 */
    public void restoreMeridian(com.nineyin.server.game.avatar.jingmai.PlayerMeridian.Snapshot snapshot) {
        meridian.restore(snapshot, clock.instant());
    }

    public com.nineyin.server.game.avatar.jingmai.PlayerMeridian.Snapshot meridianSnapshot() {
        return meridian.snapshot();
    }

    // ------------------------------------------------------------------ task

    /** 任务状态（登录 restore 后为持久化形态）。 */
    public com.nineyin.server.game.avatar.task.PlayerTask task() {
        return task;
    }

    /** 登录恢复任务存档。 */
    public void restoreTask(com.nineyin.server.game.avatar.task.PlayerTask.Snapshot snapshot) {
        task.restore(snapshot);
    }

    public com.nineyin.server.game.avatar.task.PlayerTask.Snapshot taskSnapshot() {
        return task.snapshot();
    }

    public boolean meridianActivated() {
        return meridianActivated;
    }

    public void noteMeridianActivated() {
        meridianActivated = true;
    }

    /** 缓降租约当前是否持有（ticker 消耗/到期摘除用）。 */
    public synchronized boolean gliding() {
        return gliding;
    }

    /** 挂缓降 buff（216 qinggong_9 flag=1）：返回槽位与到期时刻，无空闲槽返回 null。 */
    public synchronized Instant engageGlide(int slot, Instant now) {
        if (gliding) {
            return glideEndsAt; // 幂等
        }
        Instant expiresAt = now.plusMillis(GLIDE_BUFF_LIFETIME_MS);
        activateBuff(slot, GLIDE_BUFF_STATIC_DATA, GLIDE_BUFF_CONFIG, 1, expiresAt);
        gliding = true;
        glideBuffSlot = slot;
        glideEndsAt = expiresAt;
        return expiresAt;
    }

    /** 摘缓降 buff（216 flag=0 / 点耗尽 / 到期）：返回曾占用的槽位，未持有返回 -1。 */
    public synchronized int releaseGlide() {
        if (!gliding) {
            return -1;
        }
        gliding = false;
        int slot = glideBuffSlot;
        glideBuffSlot = 0;
        glideEndsAt = null;
        if (slot > 0) {
            clearBuffSlot(slot);
        }
        return slot;
    }

    /** 缓降每秒扣点（不触发 <50 自动回满）；付不起返回 false（调用方强摘）。 */
    public synchronized boolean drainGlidePoint() {
        return actor.drainQingGong(GLIDE_POINT_COST_PER_SECOND) >= 0;
    }

    /** 缓降到期时刻（未持有返回 null）。 */
    public synchronized Instant glideEndsAt() {
        return gliding ? glideEndsAt : null;
    }

    /** 轻功值单属性帧（缓降消耗/恢复推送）。 */
    public byte[] qingGongPointFrame() {
        Actor.State state = actor.snapshot();
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "QingGongPoint", PropertyValues.Value.ofInt32(state.qingGongPoint()));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * 持续型轻功租约（踏波行 qinggong_2 / 上天梯 qinggong_11 / 壁虎游墙 qinggong_10）：
     * 216 begin 已扣 Consume，此后每秒按 qgdefine→BufferID→buff_varprop CCQGP 扣点
     * （玩家资料：踏波行/上天梯 开局 10 点、其后每秒 7 点）；end/耗尽摘除。无 buff 链。
     *
     * @return false=同 id 续约（租约已在，不重复扣开局费——客户端按住冲刺时引擎会
     *         每帧重发 begin，逐条扣费会秒清轻功条，2026-11-03 真机反馈）
     */
    public synchronized boolean engageDrift(String qingGongId, int drainPerSecond) {
        if (drifting && qingGongId != null && qingGongId.equals(driftQingGongId)) {
            return false; // 续约：不重复扣费不重挂
        }
        drifting = true;
        driftQingGongId = qingGongId;
        driftDrainPerSecond = Math.max(drainPerSecond, 0);
        return true;
    }

    /** 摘持续轻功租约（216 end / 点耗尽）；返回是否确在持有。 */
    public synchronized boolean releaseDrift() {
        boolean was = drifting;
        drifting = false;
        driftQingGongId = null;
        driftDrainPerSecond = 0;
        return was;
    }

    /** 当前租约的轻功 id（无租约 null）。 */
    public synchronized String driftQingGongId() {
        return drifting ? driftQingGongId : null;
    }

    public synchronized boolean drifting() {
        return drifting;
    }

    /**
     * 即时型轻功（UseType=0，如雁行功 qinggong_7/三段跳 qinggong_5）重复上报闸：
     * 同 id 在窗口内的重发视为引擎重报不扣费（人手极限 ~4 次/秒，引擎逐帧重发 10-30/s）。
     */
    public synchronized boolean instantQingGongRechargeable(String qingGongId, Instant now) {
        Instant last = instantQingGongLastCharge.get(qingGongId);
        if (last != null && Duration.between(last, now).compareTo(INSTANT_QINGGONG_REFIRE_GUARD) < 0) {
            return false;
        }
        instantQingGongLastCharge.put(qingGongId, now);
        return true;
    }

    /** 持续轻功每秒扣点；付不起返回 false（调用方强摘租约）。 */
    public synchronized boolean drainDriftPoint() {
        return actor.drainQingGong(driftDrainPerSecond) >= 0;
    }

    /** 轻功点一次性扣费（216 begin 的 qgdefine Consume / 技能 AConsumeQGP）；付不起 -1。 */
    public synchronized int spendQingGong(int cost) {
        return actor.useQingGong(cost);
    }

    /**
     * 1s ticker 的官方分档回复（每 3s 一档，档位按当时余量）：缓降/持续轻功消耗中
     * 暂停。返回是否恢复（调用方无需单独发帧——心跳帧每秒都推 QingGongPoint）。
     */
    public synchronized boolean qingGongRecoveryTick(Instant now) {
        if (gliding || drifting) {
            qingGongRecoveryElapsedMs = 0; // 消耗期不累积（档位按余量瞬时判定）
            return false;
        }
        qingGongRecoveryElapsedMs += 1000;
        if (qingGongRecoveryElapsedMs < QING_GONG_RECOVERY_INTERVAL_MS) {
            return false;
        }
        qingGongRecoveryElapsedMs -= QING_GONG_RECOVERY_INTERVAL_MS;
        return actor.recoverQingGongTiered();
    }

    // ------------------------------------------------------------------ sitcross

    /** 当前是否打坐态（LogicState=102）。 */
    public boolean sitting() {
        return actor.snapshot().logicState() == Actor.LOGIC_STATE_SITCROSS;
    }

    /**
     * msg240 打坐开关落态（Go handleSitcrossCustom 同语义）：1=进入 102，
     * 0/2=回 NORMAL（2 是客户端本地打断形态，必须当正常停处理）。幂等/死亡由调用方
     * 闸门。返回是否发生迁移（调用方据此发 0x10 vital）。
     */
    public synchronized boolean applySitcross(boolean engage) {
        int next = engage ? Actor.LOGIC_STATE_SITCROSS : Actor.LOGIC_STATE_NORMAL;
        if (actor.snapshot().logicState() == next) {
            return false;
        }
        actor.setLogicState(next);
        if (engage) {
            logicStateOverride = -1; // 打坐退出演武等覆盖态（覆盖态下本就进不来）
        }
        return true;
    }

    /**
     * 1s ticker 打坐回复（Go sitcrossTick）：本地规则 10 秒回满 MaxHP/MaxMP
     * （每秒向上取整）。死亡/非坐姿在 {@link Actor#restoreWhileSit} 内闸掉。
     * 面板 HPUpSpeed/MPUpSpeed 与每秒回复量同源，客户端按其显示 +N 数字。
     */
    public boolean sitcrossTick() {
        Actor.State state = actor.snapshot();
        return actor.restoreWhileSit(
                sitcrossRecoveryPerSecond(state.maxHp()),
                sitcrossRecoveryPerSecond(state.maxMp()));
    }

    /**
     * 药品即时回复（msg31 使用物 heal_hp/heal_mp；{@link Actor#restore} 不限坐姿），
     * 调用方据返回值补发 vitalUpdate。
     */
    public boolean restore(int hpAmount, int mpAmount) {
        return actor.restore(hpAmount, mpAmount);
    }

    /**
     * NPC 反击伤害落地（NpcCombatAi；参考服口径实伤 1）。内功护盾先行 FIFO 吸收
     * （到期即弃），返回<b>实际落血值</b>：&gt;0=落血（调用方发伤害帧）、
     * 0=全吸收（静默，对齐格挡形态）、-1=已死不结算（原 boolean false 语义）。
     * 血条推送由调用方走 0x2D 的 HPRatio（参考服 NPC 命中不发玩家 HP 面板）。
     */
    public synchronized int applyNpcDamage(int amount) {
        if (amount <= 0) {
            return 0;
        }
        if (actorState().hp() <= 0) {
            return -1;
        }
        pruneNeiGongShields();
        int remaining = amount;
        java.util.ListIterator<NeiGongShield> it = neigongShields.listIterator();
        while (remaining > 0 && it.hasNext()) {
            NeiGongShield shield = it.next();
            int absorbed = Math.min(shield.amount(), remaining);
            remaining -= absorbed;
            if (absorbed >= shield.amount()) {
                it.remove();
            } else {
                it.set(new NeiGongShield(shield.amount() - absorbed, shield.expiresAtMs()));
            }
        }
        if (remaining > 0) {
            actor.applyDamage(remaining);
        }
        return remaining; // 剩余未吸收部分=实际落血（全吸收=0）
    }

    // ------------------------------------------------------------------ neigong effects

    /** 内功特效 state 快照（bookId；无态返回空表，调用方免判空）。 */
    public synchronized java.util.Map<String, Object> neigongStateOf(String bookId) {
        java.util.Map<String, Object> state = neigongState.get(bookId);
        return state == null ? java.util.Map.of() : new java.util.HashMap<>(state);
    }

    /** 内功特效 state 回写（脚本返回表；null/空 = 清态，窗口过期自然复位）。 */
    public synchronized void storeNeigongState(String bookId, java.util.Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            neigongState.remove(bookId);
        } else {
            neigongState.put(bookId, new java.util.HashMap<>(state));
        }
    }

    /** 内功特效各书最近有效触发时刻（epoch ms；从未触发返回 -1 → ctx.since_last_ms=-1）。 */
    public synchronized long neigongLastFireAt(String bookId) {
        return neigongLastFireAt.getOrDefault(bookId, -1L);
    }

    /** 记一次有效触发（仅非空指令返回时调；纯 nil 返回不推进节拍，周期门才成立）。 */
    public synchronized void markNeigongFired(String bookId) {
        neigongLastFireAt.put(bookId, clock.instant().toEpochMilli());
    }

    /** 内功护盾注入（shield{amount,life_ms}；非正值静默忽略）。 */
    public synchronized void addNeiGongShield(int amount, long lifeMs) {
        if (amount <= 0 || lifeMs <= 0) {
            return;
        }
        neigongShields.add(new NeiGongShield(amount, clock.instant().toEpochMilli() + lifeMs));
    }

    /** 当前内功护盾总量（过期顺带清理；测试/调试口）。 */
    public synchronized int neigongShieldTotal() {
        pruneNeiGongShields();
        int total = 0;
        for (NeiGongShield shield : neigongShields) {
            total += shield.amount();
        }
        return total;
    }

    private void pruneNeiGongShields() {
        long now = clock.instant().toEpochMilli();
        neigongShields.removeIf(shield -> shield.expiresAtMs() <= now || shield.amount() <= 0);
    }

    // ------------------------------------------------------------------ parry value

    /** 当前格挡值（动态）。 */
    public synchronized int parryValue() {
        return parryValue;
    }

    /** 格挡值上限（基础 maxParry + 运功书 MaxParryAdd，满值即面板合计）。 */
    public int parryValueMax() {
        return progress.parityParryValue();
    }

    /** 装备/内功上限重算后的格挡值同步：满则跟满，否则夹住。 */
    private synchronized void resyncParryValue(int previousMax) {
        int max = parryValueMax();
        parryValue = parryValue >= previousMax ? max : Math.min(parryValue, max);
    }

    /**
     * 1s ticker 格挡值回复（玩家资料）：按住格挡绝不回复；松开后每秒
     * +{@link #PARRY_UP_SPEED_BASE} 至上限。返回是否变化（调用方发单属性帧）。
     */
    public synchronized boolean parryRecoveryTick() {
        if (parrying || parryValue >= parryValueMax()) {
            return false;
        }
        parryValue = Math.min(parryValueMax(), parryValue + PARRY_UP_SPEED_BASE);
        return true;
    }

    /**
     * 被格挡命中扣减格挡值（NPC 攻击玩家路径接线用；归零=破防）。返回扣后余量。
     */
    public synchronized int consumeParryValue(int amount) {
        if (amount <= 0) {
            return parryValue;
        }
        parryValue = Math.max(0, parryValue - amount);
        return parryValue;
    }

    /** 格挡值单属性帧（0x10 ParryValue idx475）。 */
    public byte[] parryValueFrame() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "ParryValue", PropertyValues.Value.ofInt32(parryValue()));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    public void restoreFaculty(PlayerProgress.Snapshot value, Instant now) {
        int previousParryMax = parryValueMax();
        progress.restore(value, now);
        // 生存属性层（GM set_vitals 档案）：旧档 null 不动 PanelState 的 0 种子。
        panel.restoreVitals(value.vitals());
        if (progress.facultyState == PlayerProgress.FACULTY_STATE_CONVERT
                && progress.facultyStyle == PlayerProgress.FACULTY_STYLE_ACT) {
            logicStateOverride = PlayerProgress.LOGIC_STATE_FACULTY;
        } else {
            logicStateOverride = 0;
        }
        syncEquippedNeiGongBuff(now);
        syncEquippedNeiGongResources();
        resyncParryValue(previousParryMax);
    }

    /** Go equipNeiGong（215）：换装内功（未学会抛错=断连），重挂 Buff 槽4 与资源上限。 */
    public void equipNeiGong(String configId) {
        if (progress.book(configId) == null) {
            throw new IllegalStateException("not learned neigong " + configId);
        }
        progress.curNeiGong = configId;
        syncEquippedNeiGongBuff(clock.instant());
        syncEquippedNeiGongResources();
    }

    /** Go facultyTick：整分钟结算；变化时重挂 Buff/资源。返回 [槽位, 是否变化]。 */
    public int[] facultyTick(java.time.Instant now) {
        boolean advanced = progress.advanceFaculty(now);
        if (advanced) {
            syncEquippedNeiGongBuff(now);
            syncEquippedNeiGongResources();
        }
        return new int[]{progress.facultyBookSlot(), advanced ? 1 : 0};
    }

    /** Go SetLogicState：演武 108 / 常规 0（背景内修绝不进 LS_FACULTY）。 */
    public void setLogicStateOverride(int state) {
        logicStateOverride = state;
    }

    void syncEquippedNeiGongBuff(Instant now) {
        PlayerProgress.Book book = progress.book(progress.curNeiGong);
        if (book == null || book.buffId.isEmpty() || book.buffStaticData == 0 || book.buffLevel <= 0) {
            buffs.remove(INNER_POWER_BUFF_SLOT);
            return;
        }
        buffs.put(INNER_POWER_BUFF_SLOT, new ActiveBuff(
                book.buffStaticData, now.plus(INNER_POWER_BUFF_LEASE), book.buffId, book.buffLevel));
    }

    void syncEquippedNeiGongResources() {
        // totalResourceAdds（IDA 钉死）：有效上限 = 基础 + MaxHP/MPAdd 各层 + 五维换算项
        int[] adds = progress.totalResourceAdds(panel.equipBonus());
        Actor.State base = actor.snapshot();
        // 客户端校准覆盖（vitals.max_hp_override）：血条上限由客户端按装备/内功自算，
        // 服务端公式缺层时用覆盖对齐（2026-11-05 真机 3496/1442 vs 客户端 5366/2007）。
        int hpOverride = panel.maxHpOverride();
        int mpOverride = panel.maxMpOverride();
        int totalMaxHp = hpOverride > 0 ? hpOverride
                : Math.max(1, base.baseMaxHp() + adds[0] + 7 * adds[3] + 2 * adds[2]);
        int totalMaxMp = mpOverride > 0 ? mpOverride
                : Math.max(1, base.baseMaxMp() + adds[1] + 4 * adds[4] + adds[5]);
        actor.setDerivedMaxima(totalMaxHp, totalMaxMp);
    }

    // ------------------------------------------------------------------ panel

    /** 面板实时状态（GM 动作写身份字段；装备词缀经 recomputeEquipBonuses 整层重算）。 */
    public PanelState panel() {
        return panel;
    }

    /**
     * 装备词缀/特效整层重算（登录装备载入后、穿脱时调用；幂等）：
     * 先词缀（propBuilt → proppack）后特效（EffectPackID → buff 链），
     * 随后同步有效上限。调用方负责补发 vitalUpdate。
     *
     * @param propBuilts    每件已穿装备的词缀串（"1,id;1,id"，无词缀传空）
     * @param effectPackIds 每件已穿装备的特效包号（0=无；equipment.ini EffectPackID）
     */
    public void recomputeEquipBonuses(List<String> propBuilts, List<Integer> effectPackIds) {
        EquipAffixCatalog affixes = EquipAffixCatalog.load();
        int previousParryMax = parryValueMax();
        panel.recomputeEquipBonuses(affixes, propBuilts);
        panel.applyEquipEffects(EquipEffectCatalog.load(), affixes, effectPackIds);
        syncEquippedNeiGongResources();
        resyncParryValue(previousParryMax);
    }

    /** 五系伤害块（exe 追加批）：暗器上下限种子 0；DamageAdd=运功书 varprop 透传，DamageMul=千分÷1000。 */
    List<PropertyValues.Indexed> damageProperties() {
        PlayerProgress.Book equipped = progress.book(progress.curNeiGong);
        List<PropertyValues.Indexed> properties = new ArrayList<>(12);
        Props.add(properties, "MaxShotDamage", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "MinShotDamage", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "PhyDamageAdd", PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "PhyDamageAdd")));
        Props.add(properties, "StiDamageAdd", PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "StiDamageAdd")));
        Props.add(properties, "JujDamageAdd", PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "JujDamageAdd")));
        Props.add(properties, "NegDamageAdd", PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "NegDamageAdd")));
        Props.add(properties, "MasDamageAdd", PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "MasDamageAdd")));
        Props.add(properties, "PhyDamageMul", PropertyValues.Value.ofFloat32(PlayerProgress.effMul(equipped, "PhyDamageMul")));
        Props.add(properties, "StiDamageMul", PropertyValues.Value.ofFloat32(PlayerProgress.effMul(equipped, "StiDamageMul")));
        Props.add(properties, "JujDamageMul", PropertyValues.Value.ofFloat32(PlayerProgress.effMul(equipped, "JujDamageMul")));
        Props.add(properties, "NegDamageMul", PropertyValues.Value.ofFloat32(PlayerProgress.effMul(equipped, "NegDamageMul")));
        Props.add(properties, "MasDamageMul", PropertyValues.Value.ofFloat32(PlayerProgress.effMul(equipped, "MasDamageMul")));
        return properties;
    }

    // -------------------------------------------------------------------- buffs

    static int bufferInfoPropertyIndex(int slot) {
        return NpcSchema.bufferInfoPropertyIndex(slot);
    }

    public String bufferInfo(int slot, Instant now) {
        ActiveBuff buff = buffs.get(slot);
        if (buff == null || !buff.expiresUtc().isAfter(now)) {
            return "";
        }
        int level = Math.max(buff.level(), 1);
        return String.format(Locale.ROOT, "%X,%X,%X,%X,%X,%X",
                buff.staticData(), PLAYER_OBJECT_ID, PLAYER_OWNER_ID, level,
                buff.expiresUtc().toEpochMilli() + EPOCH_1601_OFFSET_MS, 1);
    }

    /**
     * FILETIME 纪元偏移（1601-01-01→1970-01-01 的毫秒差）：BufferInfo/BufferListStr 的
     * 到期字段客户端按 1601 纪元解析。参考服 exe 实证（127-0-0-1-50768 三招）：
     * 第 5 字段恒 = expiryUnixMs + 11644473600000（严格恒等）；此前 Java 直写 Unix 毫秒，
     * 客户端解出"公元 1657 年已过期"的 buff。
     */
    private static final long EPOCH_1601_OFFSET_MS = 11_644_736_000_000L;

    /** Go bufferListStringLocked：ConfigID,UniqueID,Level,OwnerIdent-OwnerSerial,EndTime,EffectType,OverlayRows|。 */
    public String bufferListString(Instant now) {
        StringBuilder list = new StringBuilder();
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            ActiveBuff buff = buffs.get(slot);
            if (buff == null || !buff.expiresUtc().isAfter(now)) {
                continue;
            }
            int level = Math.max(buff.level(), 1);
            list.append(String.format(Locale.ROOT, "%s,local-%d,%d,%d-%d,%d,1,0|",
                    buff.configId(), slot, level, PLAYER_OBJECT_ID, PLAYER_OWNER_ID,
                    buff.expiresUtc().toEpochMilli() + EPOCH_1601_OFFSET_MS));
        }
        return list.toString();
    }

    // ------------------------------------------------------------------- manual parry

    public boolean parrying() {
        return parrying;
    }

    /** 角色名（msg169 双宽串等帧用）。 */
    public String snapshotName() {
        return actor.snapshot().name();
    }

    /** 按下格挡：挂 BuffInParry 到槽 11，返回 BufferInfo 串；幂等由调用方（状态变化才调用）。 */
    public String engageParryBuff(Instant now) {
        ActiveBuff existing = buffs.get(PARRY_BUFF_SLOT);
        ActiveBuff buff = new ActiveBuff(PARRY_BUFF_STATIC_DATA,
                now.plusMillis(PARRY_BUFF_DURATION_MS), "BuffInParry", 1);
        if (existing != null && existing.expiresUtc().isAfter(now)) {
            buff = existing; // 已在格挡：保留原到期时间
        }
        buffs.put(PARRY_BUFF_SLOT, buff);
        parrying = true;
        return bufferInfo(PARRY_BUFF_SLOT, now);
    }

    /** 松开格挡：清槽 11，返回空 BufferInfo 串。 */
    public String releaseParryBuff() {
        buffs.remove(PARRY_BUFF_SLOT);
        parrying = false;
        return "";
    }

    /** 格挡属性帧（0x10）：InParry/InManualParry 置位 + BufferInfo11（槽串）+ 当前格挡值。 */
    public byte[] parryStateFrame(boolean engage, String buffInfo) {
        int flag = engage ? 1 : 0;
        List<PropertyValues.Indexed> properties = new ArrayList<>(4);
        Props.add(properties, "InParry", PropertyValues.Value.ofInt32(flag));
        Props.add(properties, "InManualParry", PropertyValues.Value.ofInt32(flag));
        Props.add(properties, "BufferInfo" + PARRY_BUFF_SLOT, PropertyValues.Value.ofString(buffInfo));
        Props.add(properties, "ParryValue", PropertyValues.Value.ofInt32(parryValue()));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    // ------------------------------------------------------------------- combat

    /** Go beginSkillUse 的 (bool, reason)。 */
    public record SkillGate(boolean allowed, String reason) {

        static SkillGate reject(String reason) {
            return new SkillGate(false, reason);
        }
    }

    /** 技能冷却（skillId → 就绪时刻）与公共 GCD（跨套路才生效）。 */
    private final java.util.Map<String, Instant> skillCooldowns = new java.util.HashMap<>();
    /** 公共 GCD 到期时刻与其发起套路：同套路只走个人 CD，切套路才付 7s（bug.txt 官方规则）。 */
    private Instant publicCdUntil;
    private String publicCdTaolu = "";
    private Instant skillLockUntil;
    private String currentSkillId = "";
    private String currentSkillEffectId = "";
    private int currentSkillLevel;
    private long currentSkillTarget;
    /** 出招代次：每次 beginSkillUse 成功自增——收尾定时器按 (id, epoch) 防误删后续招
     * （GCD 不硬闸下 1-3s 连招会把上一招的收尾顶掉；同技能快速复读时 id 相同须靠代次区分）。 */
    private long currentSkillEpoch;
    /**
     * 冲锋暂存出招（IDA stagePreCast/handleCastDashLanding 取证）：超 RushRange 的
     * SkillLock+CanRush 技能先暂存 30s 窗口并让客户端起跑，落地 212 后重组 211 续招。
     */
    private String pendingSkillId = "";
    private int pendingTargetObj;
    private int pendingTargetOwner;
    private java.time.Instant pendingCastExpiresAt;
    private volatile boolean rushInFlight;

    /** 暂存出招（冲锋起跑时；IDA stagePendingCast）。 */
    public synchronized void stagePendingCast(String skillId, int targetObj, int targetOwner,
                                              java.time.Instant expiresAt) {
        pendingSkillId = skillId;
        pendingTargetObj = targetObj;
        pendingTargetOwner = targetOwner;
        pendingCastExpiresAt = expiresAt;
    }

    /** 暂存出招快照（落地续招重组 211 用）。 */
    public record PendingCast(String skillId, int targetObj, int targetOwner) {
    }

    /** 取出暂存出招（过期/为空返回 empty 并清空，IDA consumePendingCast）。 */
    public synchronized java.util.Optional<PendingCast> consumePendingCast(java.time.Instant now) {
        if (pendingSkillId.isEmpty() || pendingCastExpiresAt == null
                || now.isAfter(pendingCastExpiresAt)) {
            pendingSkillId = "";
            pendingTargetObj = 0;
            pendingTargetOwner = 0;
            pendingCastExpiresAt = null;
            return java.util.Optional.empty();
        }
        PendingCast pending = new PendingCast(pendingSkillId, pendingTargetObj, pendingTargetOwner);
        pendingSkillId = "";
        pendingTargetObj = 0;
        pendingTargetOwner = 0;
        pendingCastExpiresAt = null;
        return java.util.Optional.of(pending);
    }

    public void markRushInFlight() {
        rushInFlight = true;
    }

    public void clearRushInFlight() {
        rushInFlight = false;
    }

    public boolean rushInFlight() {
        return rushInFlight;
    }

    /** 暂存出招只读查看（msg15 到位探测用；过期/为空返回 null，不消费不清空）。 */
    public synchronized PendingCast peekPendingCast(java.time.Instant now) {
        if (pendingSkillId.isEmpty() || pendingCastExpiresAt == null
                || now.isAfter(pendingCastExpiresAt)) {
            return null;
        }
        return new PendingCast(pendingSkillId, pendingTargetObj, pendingTargetOwner);
    }
    /** 架势轮换相位（stance 槽当前 staticData → 下一变体）。 */
    private final java.util.Map<Integer, Long> stanceSlotStatic = new java.util.HashMap<>();
    /** 最近一次命中敌人的时刻（战斗退出计时基准；62433 抓包：命中才进 Fighting）。 */
    private volatile Instant lastCombatAt;

    /**
     * 命中敌人进入战斗（62433 抓包实证：出招不切 Fighting，首招 vital 仍 0，
     * 命中后才有 LogicState=1 帧）。返回是否发生 0→1 迁移（调用方据此发单帧）。
     */
    public synchronized boolean enterCombat(Instant now) {
        lastCombatAt = now;
        if (actor.snapshot().logicState() == Actor.LOGIC_STATE_FIGHTING) {
            return false;
        }
        actor.setLogicState(Actor.LOGIC_STATE_FIGHTING);
        logicStateOverride = -1;
        return true;
    }

    /** 已在战斗中的活跃刷新（多段技后续段命中：防段间被闲时退出判定打断）。 */
    public synchronized void touchCombat(Instant now) {
        lastCombatAt = now;
    }

    /**
     * 战斗闲时退出：距最近命中超过窗口且仍在 Fighting → 切回 NORMAL。
     * 返回是否发生 1→0 迁移（调用方据此发单帧）。
     */
    public synchronized boolean exitCombatIfIdle(java.time.Duration idleWindow, Instant now) {
        if (actor.snapshot().logicState() != Actor.LOGIC_STATE_FIGHTING) {
            return false;
        }
        if (lastCombatAt == null || Duration.between(lastCombatAt, now).compareTo(idleWindow) < 0) {
            return false;
        }
        actor.setLogicState(Actor.LOGIC_STATE_NORMAL);
        return true;
    }

    /** 单属性 CurSkillID 帧（冲锋暂存时随 msg25 下发，IDA castSkillIDFrame）。 */
    public byte[] curSkillIdFrame(String skillId) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "CurSkillID", PropertyValues.Value.ofString(skillId));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** 单属性 LogicState 帧（进/退战斗的即时推送，62433 抓包 n=6 小帧的精简形态）。 */
    public byte[] logicStateFrame() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "LogicState",
                PropertyValues.Value.ofByte(effectiveLogicState(actor.snapshot())));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** 当前是否战斗态（LogicState=Fighting）。 */
    public boolean fighting() {
        return actor.snapshot().logicState() == Actor.LOGIC_STATE_FIGHTING;
    }

    /**
     * Go beginSkillUse：技能锁 → 个人 CD → 公共 GCD（跨套路）→ 死亡 → MP → SP → 轻功点，
     * 全过才扣资源、写 CD/当前技能、进 Fighting。拒绝返回原因（调用方静默记日志）。
     * 公共 GCD 只在换套路时拦截（bug.txt「同套路不在触发公共7秒冷却，不同套路会看到
     * 7秒CD」）——同套路连招各走各的个人 CD，cd&lt;7s 的技能不再被堆到 7s。
     */
    public synchronized SkillGate beginSkillUse(com.nineyin.server.world.fight.CombatSkill skill,
                                                long targetKey, Instant now) {
        if (skillLockUntil != null && now.isBefore(skillLockUntil)) {
            return SkillGate.reject("skill locked remaining="
                    + java.time.Duration.between(now, skillLockUntil).toMillis() + "ms");
        }
        Instant ready = skillCooldowns.get(skill.id());
        if (ready != null && now.isBefore(ready)) {
            return SkillGate.reject("personal cooldown remaining="
                    + java.time.Duration.between(now, ready).toMillis() + "ms");
        }
        if (publicCdUntil != null && now.isBefore(publicCdUntil)
                && !skill.taolu().equals(publicCdTaolu)) {
            return SkillGate.reject("public cooldown (taolu switch from=" + publicCdTaolu
                    + ") remaining=" + java.time.Duration.between(now, publicCdUntil).toMillis() + "ms");
        }
        Actor.State state = actor.snapshot();
        if (state.logicState() == Actor.LOGIC_STATE_DIED || state.hp() <= 0) {
            return SkillGate.reject("player is dead");
        }
        if (state.mp() < skill.mpCost()) {
            return SkillGate.reject("MP " + state.mp() + " < " + skill.mpCost());
        }
        if (progress.sp() < skill.spCost()) {
            return SkillGate.reject("SP " + progress.sp() + " < " + skill.spCost());
        }
        if (skill.qgCost() > 0 && actor.useQingGong(skill.qgCost()) < 0) {
            return SkillGate.reject("QingGongPoint cannot pay " + skill.qgCost());
        }
        if (!actor.spendMp(skill.mpCost())) {
            return SkillGate.reject("MP spend rejected");
        }
        if (skill.spCost() > 0) {
            progress.addSP(-skill.spCost());
        }
        skillCooldowns.put(skill.id(), now.plusMillis(skill.personalCdMs()));
        // 每次出招都刷新 GCD 窗口与发起套路（62433 抓包：每招恒发个人+GCD 双行；
        // 客户端按 team 列过滤，同套路技能不显示 7s——服务端同样只拦跨套路）。
        publicCdUntil = now.plusMillis(skill.publicCdMs());
        publicCdTaolu = skill.taolu();
        currentSkillId = skill.id();
        currentSkillEffectId = skill.id();
        currentSkillLevel = skill.level();
        currentSkillTarget = targetKey;
        currentSkillEpoch++;
        // 62433 抓包实证：出招不切 Fighting——命中敌人才进战斗（enterCombat）。
        return new SkillGate(true, "");
    }

    /** Go clearCurrentSkill：仅当当前技能仍是它才清（并发出招防误清）；不动 LogicState。 */
    public synchronized boolean clearCurrentSkill(String skillId) {
        if (!currentSkillId.equals(skillId)) {
            return false;
        }
        currentSkillId = "";
        currentSkillEffectId = "";
        currentSkillLevel = 0;
        currentSkillTarget = 0;
        return true;
    }

    /**
     * 收尾定时器版：额外校验出招代次——上一招被新招顶掉（GCD 不硬闸下 1-3s 连招）
     * 或同技能快速复读时，旧定时器不得删掉新招的 active_skill 行
     * （2026-11-04 对拍：Java 29 加 18 删、参考服 8/8 恒配对）。
     */
    public synchronized boolean clearCurrentSkill(String skillId, long epoch) {
        if (currentSkillEpoch != epoch || !currentSkillId.equals(skillId)) {
            return false;
        }
        return clearCurrentSkill(skillId);
    }

    /** 当前出招代次（受理后读取，供收尾定时器配对）。 */
    public synchronized long currentSkillEpoch() {
        return currentSkillEpoch;
    }

    public synchronized String currentSkillId() {
        return currentSkillId;
    }

    /** 被击技能锁（Go 500ms；本 phase 无 NPC 反击，接口先备）。 */
    public synchronized void lockSkillsUntil(Instant until) {
        if (skillLockUntil == null || until.isAfter(skillLockUntil)) {
            skillLockUntil = until;
        }
    }

    /**
     * 泛化 buff 槽激活（Go activateSkillBuff）：写槽返回 BufferInfo 串；
     * 到期清理由调用方调度（scheduler 到期发空槽帧）。
     */
    public synchronized String activateBuff(int slot, long staticData, String configId,
                                            int level, Instant expiresAt) {
        buffs.put(slot, new ActiveBuff(staticData, expiresAt, configId, Math.max(level, 1)));
        return bufferInfo(slot, clock.instant());
    }

    /** 架势轮换取下一变体：槽位当前 staticData 的下一个（双态即互切，首拍取第一个）。 */
    public synchronized com.nineyin.server.world.fight.CombatSkill.Buff nextStanceVariant(
            List<com.nineyin.server.world.fight.CombatSkill.Buff> variants) {
        Long current = stanceSlotStatic.get(variants.get(0).preferredSlot());
        for (int i = 0; i < variants.size(); i++) {
            if (current != null && variants.get(i).staticData() == current) {
                return variants.get((i + 1) % variants.size());
            }
        }
        return variants.get(0);
    }

    /** 记录架势槽相位（activateBuff 成功后由调用方回写）。 */
    public synchronized void noteStanceSlot(int slot, long staticData) {
        stanceSlotStatic.put(slot, staticData);
    }

    /** 清 buff 槽（到期调度用）；返回是否确有清除。 */
    public synchronized boolean clearBuffSlot(int slot) {
        return buffs.remove(slot) != null;
    }

    /**
     * 找空闲 buff 槽（未占用或已过期），无则 -1。常规技能/缓降 buff 只从
     * {@code PARRY_BUFF_SLOT+1}(12) 起分配：客户端 1..11 是系统租约区（Go 源码
     * activateSkillBuff 注释 "System leases keep slots 1..10；routine Buffs ...
     * allocate from 11..24"，格挡租约占 11），参考服 exe 实测 CS_jh_jysg01 的
     * buff 落槽 12（127-0-0-1-50768）。此前从 1 扫会把出招 buff 写进系统槽，
     * 空中出招时打断客户端本地状态（人物被拉回地面）。
     */
    public synchronized int freeBuffSlot() {
        Instant now = clock.instant();
        for (int slot = PARRY_BUFF_SLOT + 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            ActiveBuff buff = buffs.get(slot);
            if (buff == null || !buff.expiresUtc().isAfter(now)) {
                return slot;
            }
        }
        return -1;
    }

    /** Go activateSkillBuff：同 ConfigID 的现存租约复用其槽位；无活跃租约返回 -1。 */
    public synchronized int reuseBuffSlot(String configId, Instant now) {
        for (java.util.Map.Entry<Integer, ActiveBuff> entry : buffs.entrySet()) {
            ActiveBuff buff = entry.getValue();
            if (configId != null && configId.equals(buff.configId())
                    && buff.expiresUtc().isAfter(now)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /** 同 ConfigID 活跃租约的槽位与到期（独立 buff 摘除回帧/msg169 到期值用）；无则 null。 */
    public record ActiveBuffLease(int slot, Instant expiresUtc) {
    }

    public synchronized ActiveBuffLease activeBuffLease(String configId, Instant now) {
        for (java.util.Map.Entry<Integer, ActiveBuff> entry : buffs.entrySet()) {
            ActiveBuff buff = entry.getValue();
            if (configId != null && configId.equals(buff.configId())
                    && buff.expiresUtc().isAfter(now)) {
                return new ActiveBuffLease(entry.getKey(), buff.expiresUtc());
            }
        }
        return null;
    }

    /** 活跃 buff 的 槽位→StaticData 快照（架招 ReplaceCategory 互斥逐出用）。 */
    public synchronized java.util.Map<Integer, Long> activeBuffStaticDatas(Instant now) {
        java.util.Map<Integer, Long> snapshot = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, ActiveBuff> entry : buffs.entrySet()) {
            if (entry.getValue().expiresUtc().isAfter(now)) {
                snapshot.put(entry.getKey(), entry.getValue().staticData());
            }
        }
        return snapshot;
    }

    /** 单 buff 槽属性帧（0x10 BufferInfoN）。 */
    public byte[] buffSlotFrame(int slot, String buffInfo) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, NpcSchema.bufferInfoPropertyName(slot),
                PropertyValues.Value.ofString(buffInfo == null ? "" : buffInfo));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * 出招收尾 stand 复位帧（0x10 State，idx19 官方表按名解析）。
     * 2026-11-02 起出招收尾停发（空中出招被强制贴地）；保留 wire 格式备用。
     */
    public byte[] standStateFrame() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "State", PropertyValues.Value.ofString("stand"));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * 伤害公式攻方面（formula.lua 的 attack）：基础外功（空手 20-35）+ 装备外功 +
     * 内功 Min/MaxMeleeDamage，与面板 Min/MaxMeleeDamage 同口径（拳/腿套路无武器
     * 不落 0 吃保底 1 点）；掷值由调用方做（可测试）。
     */
    public int[] meleeDamageRange() {
        return progress.meleeDamageRange(panel.equipBonus());
    }

    /** 已穿武器 ItemType（0=空手；skill_use_limit 武器门控用）。 */
    public int equippedWeaponItemType() {
        return equippedWeaponItemType;
    }

    /** 武器类型同步（applyEquipBonuses 全量重算点调用：装备里首个武器族 ItemType，0=无）。 */
    public void noteEquippedWeaponItemType(int itemType) {
        this.equippedWeaponItemType = itemType;
    }

    // ------------------------------------------------------------------- frames

    /** 键位域归档（登录/切图 0x0D 属性档内嵌，引擎建对象时即读到）。 */
    public record KeybindArchive(String bindValue, Integer operateMode) {
        public static final KeybindArchive EMPTY = new KeybindArchive(null, null);
    }

    /** Go addObject：0x3f 首部 + 属性归档，count 回填 @0x3d。 */
    public byte[] addObject(Roles.Position location, RoleVisual visual) {
        return addObject(location, visual, KeybindArchive.EMPTY);
    }

    public byte[] addObject(Roles.Position location, RoleVisual visual, KeybindArchive keybind) {
        return addObject(location, visual, keybind, InventoryMessages.WeaponVisual.EMPTY);
    }

    /**
     * 带键位域的 addObject：0x0D 属性档内嵌 ShortcutKey（198，无尾空格——官服
     * 抓包归档形态 641 串+NUL=642）与 OperateMode（223，int32）。引擎在玩家对象
     * 创建时读模式，post-ready 的 0x10 补推太晚不生效（2026-11-04 真机"模式没推"
     * 根因）；官服两处都发（归档 frame#16 + 单属性帧 frame#26），Java 同款双发。
     *
     * <p>武器域（2026-11-04 武器不显示根因修复）：Weapon/ShotWeapon（396/398，裸
     * 模型名→客户端挂 obj\weapon\<名>）随归档下发，ActionSet（406）有械时覆盖
     * 创角默认（空手保持 visual.actionSet()="0h"）。引擎按玩家属性 Weapon 挂武器
     * （lua64 role_composite PlayerVisPropChange 实证），此前值误发在
     * WeaponMode@1599（NPC 域语义）故模型不显示。
     */
    public byte[] addObject(Roles.Position location, RoleVisual visual, KeybindArchive keybind,
                            InventoryMessages.WeaponVisual weapon) {
        Actor.State state = actor.snapshot();
        Motion currentMotion = motionSnapshot();
        Instant now = clock.instant();
        PacketWriter writer = PacketWriter.ofCapacity(0x120);
        writer.writeU8(0x0D)
                .writeU32(state.objectId()).writeU32(state.ownerId())
                .writeF32(location.x()).writeF32(location.y())
                .writeF32(location.z()).writeF32(location.orient());
        // Go make([]byte, 0x3f, 0x120)：首部固定 0x3f 字节（0x19..0x3c 保留零），属性从 0x3f 追加。
        while (writer.length() < 0x3f) {
            writer.writeU8(0);
        }
        int[] count = {0};
        Runnable bump = () -> count[0]++;
        prop(writer, "Type", PropertyValues.Value.ofByte(2), bump);                    // Type = TYPE_PLAYER
        prop(writer, "Sex", PropertyValues.Value.ofByte(visual.sex()), bump);
        prop(writer, "Photo", PropertyValues.Value.ofString(visual.photo()), bump);
        prop(writer, "Camp", PropertyValues.Value.ofInt32(0), bump);                     // Camp
        prop(writer, "Race", PropertyValues.Value.ofInt32(0), bump);                     // Race
        prop(writer, "Job", PropertyValues.Value.ofInt32(0), bump);                     // Job
        prop(writer, "Level", PropertyValues.Value.ofInt32(1), bump);                     // Level
        prop(writer, "PosiX", PropertyValues.Value.ofFloat32(location.x()), bump);
        prop(writer, "PosiY", PropertyValues.Value.ofFloat32(location.y()), bump);
        prop(writer, "PosiZ", PropertyValues.Value.ofFloat32(location.z()), bump);
        prop(writer, "Orient", PropertyValues.Value.ofFloat32(location.orient()), bump);
        prop(writer, "Hair", PropertyValues.Value.ofString(visual.hair()), bump);
        prop(writer, "Face", PropertyValues.Value.ofString(visual.face()), bump);
        prop(writer, "Cloth", PropertyValues.Value.ofString(visual.cloth()), bump);
        prop(writer, "Pants", PropertyValues.Value.ofString(visual.pants()), bump);
        prop(writer, "Shoes", PropertyValues.Value.ofString(visual.shoes()), bump);
        // 武器域（官方表序 395 Shoes→396 Weapon→398 ShotWeapon→406 ActionSet）：空手省行。
        if (!weapon.weapon().isEmpty()) {
            prop(writer, "Weapon", PropertyValues.Value.ofString(weapon.weapon()), bump);
        }
        if (!weapon.shotWeapon().isEmpty()) {
            prop(writer, "ShotWeapon", PropertyValues.Value.ofString(weapon.shotWeapon()), bump);
        }
        prop(writer, "ActionSet", PropertyValues.Value.ofString(
                weapon.armed() && !weapon.actionSet().isEmpty()
                        ? weapon.actionSet() : visual.actionSet()), bump);
        prop(writer, "State", PropertyValues.Value.ofString("stand"), bump);
        prop(writer, "FloatingState", PropertyValues.Value.ofInt32(0), bump);
        prop(writer, "LogicState", PropertyValues.Value.ofByte(effectiveLogicState(state)), bump);
        prop(writer, "JumpSpeed", PropertyValues.Value.ofFloat32(8.0f), bump);
        prop(writer, "MoveSpeed", PropertyValues.Value.ofFloat32(currentMotion.moveSpeed), bump);
        prop(writer, "WalkSpeed", PropertyValues.Value.ofFloat32(7.5f), bump);
        prop(writer, "RunSpeed", PropertyValues.Value.ofFloat32(currentMotion.runSpeed), bump);
        prop(writer, "SpeedRatio", PropertyValues.Value.ofInt32(state.speedRatio()), bump);
        prop(writer, "CantJump", PropertyValues.Value.ofByte(0), bump);
        prop(writer, "HPRatio", PropertyValues.Value.ofInt32(ratio(state.hp(), state.maxHp())), bump);
        prop(writer, "MPRatio", PropertyValues.Value.ofInt32(ratio(state.mp(), state.maxMp())), bump);
        // 2026-11-05 IDA 钉死客户端血蓝条上限公式（fxgamelogic.unpacked.dll
        // sub_121018F0/sub_12101BD0：pbar_hp.Maximum = MaxHPAdd + MaxHP×(MaxHPMul+1)；
        // MP 同构 sub_120F4C10：pbar_mp.Maximum = MaxMPAdd + MaxMP×(MaxMPMul+1)；
        // sub_127C4950 百分比耗血/蓝同构 MaxHP/MaxMP+Add）：客户端不算五维、不叠
        // 任何层，Mul 缺省 0 即 MaxHP+MaxHPAdd。全折差（五维+词缀+书）全由 Add
        // 承载（PlayerProgress 推值），故 MaxHP/MaxMP 只推 base；推有效值会被
        // 客户端当 base 原样用再叠 Add（真机 3496 被叠成 5366）。
        prop(writer, "MaxHP", PropertyValues.Value.ofInt32(state.baseMaxHp()), bump);
        prop(writer, "MaxMP", PropertyValues.Value.ofInt32(state.baseMaxMp()), bump);
        prop(writer, "HP", PropertyValues.Value.ofInt32(state.hp()), bump);
        prop(writer, "MP", PropertyValues.Value.ofInt32(state.mp()), bump);
        prop(writer, "HitHP", PropertyValues.Value.ofInt32(state.hitHp()), bump);
        prop(writer, "HitHPRatio", PropertyValues.Value.ofInt32(ratio(state.hitHp(), state.maxHp())), bump);
        prop(writer, "QingGongPoint", PropertyValues.Value.ofInt32(state.qingGongPoint()), bump);
        prop(writer, "MaxQingGongPoint", PropertyValues.Value.ofInt32(state.maxQingGongPoint()), bump);
        prop(writer, "MaxQingGongPointAdd", PropertyValues.Value.ofInt32(state.maxQingGongPointAdd()), bump);
        prop(writer, "NeigongPKStatus", PropertyValues.Value.ofInt32(0), bump);
        prop(writer, "Dead", PropertyValues.Value.ofInt32(state.hp() <= 0 ? 1 : 0), bump);
        prop(writer, "CantUseSkill", PropertyValues.Value.ofInt32(0), bump);
        prop(writer, "CurSkillID", PropertyValues.Value.ofString(currentSkillId), bump);
        prop(writer, "CurSkillEffectID", PropertyValues.Value.ofString(currentSkillEffectId), bump);
        prop(writer, "CurSkillLevel", PropertyValues.Value.ofInt32(currentSkillLevel), bump);
        prop(writer, "CurSkillTarget", PropertyValues.Value.ofObject(currentSkillTarget), bump);
        prop(writer, "ModifySkillLockTime", PropertyValues.Value.ofInt32(200), bump);
        prop(writer, "BufferListStr", PropertyValues.Value.ofString(bufferListString(now)), bump);
        for (PropertyValues.Indexed property : baseMotionProperties(currentMotion)) {
            writer.appendIndexed(property);
            bump.run();
        }
        for (PropertyValues.Indexed property : qinggongMotionProperties()) {
            writer.appendIndexed(property);
            bump.run();
        }
        prop(writer, "CapitalType1", PropertyValues.Value.ofInt32(silver), bump); // CapitalType1 碎银
        if (keybind.bindValue() != null && !keybind.bindValue().isEmpty()) {
            prop(writer, "ShortcutKey", PropertyValues.Value.ofString(keybind.bindValue()), bump);
        }
        if (keybind.operateMode() != null) {
            prop(writer, "OperateMode", PropertyValues.Value.ofInt32(keybind.operateMode()), bump);
        }
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            prop(writer, NpcSchema.bufferInfoPropertyName(slot), PropertyValues.Value.ofString(bufferInfo(slot, now)), bump);
        }
        for (PropertyValues.Indexed property : progress.properties(panel.equipBonus())) {
            writer.appendIndexed(property);
            bump.run();
        }
        for (PropertyValues.Indexed property : com.nineyin.server.game.avatar.jingmai.JingMaiMessages.playerProps(meridian)) {
            writer.appendIndexed(property);
            bump.run();
        }
        for (PropertyValues.Indexed property : damageProperties()) {
            writer.appendIndexed(property);
            bump.run();
        }
        for (PropertyValues.Indexed property : panel.identityProperties()) {
            writer.appendIndexed(property);
            bump.run();
        }
        for (PropertyValues.Indexed property : panel.domainProperties()) {
            writer.appendIndexed(property);
            bump.run();
        }
        prop(writer, "Hat", PropertyValues.Value.ofString(visual.hair()), bump);
        prop(writer, "Name", PropertyValues.Value.ofWideString(state.name()), bump);
        prop(writer, "LastObject", PropertyValues.Value.ofObject(0), bump);
        byte[] frame = writer.toBytes();
        // count 回填到 0x3d
        frame[0x3d] = (byte) count[0];
        frame[0x3e] = (byte) (count[0] >>> 8);
        return frame;
    }

    public int effectiveLogicState(Actor.State state) {
        return logicStateOverride >= 0 ? logicStateOverride : state.logicState();
    }

    private static int ratio(long current, long maximum) {
        return (int) (current * 100 / maximum);
    }

    /** Go vitalUpdate = sceneObjectProperties(1,1,0,stateProperties)。 */
    public byte[] vitalUpdate() {
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, stateProperties());
    }

    /**
     * 打坐回复瘦帧（参考服 52905 #5455/#5459、12141 #9892/#9895 实证）：坐姿期间每秒
     * 变化时只发 {HP,MP,HPRatio,MPRatio,HitHP,HitHPRatio} 六值，绝不含 LogicState——
     * 坐姿中重推带 LogicState 的面板会让客户端重入坐姿动画（动画永不落定 +
     * 240(2) 风暴，2026-11-05 真机「完全没有打坐动画」根因）。
     */
    public byte[] sitcrossVitalFrame() {
        Actor.State state = actor.snapshot();
        List<PropertyValues.Indexed> properties = new ArrayList<>(6);
        Props.add(properties, "HP", PropertyValues.Value.ofInt32(state.hp()));
        Props.add(properties, "MP", PropertyValues.Value.ofInt32(state.mp()));
        Props.add(properties, "HPRatio", PropertyValues.Value.ofInt32(ratio(state.hp(), state.maxHp())));
        Props.add(properties, "MPRatio", PropertyValues.Value.ofInt32(ratio(state.mp(), state.maxMp())));
        Props.add(properties, "HitHP", PropertyValues.Value.ofInt32(state.hitHp()));
        Props.add(properties, "HitHPRatio", PropertyValues.Value.ofInt32(ratio(state.hitHp(), state.maxHp())));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** 历练步进帧附加集（castVitalUpdate 之外随帧的历练域属性）。 */
    private static final java.util.Set<String> LIVEPOINT_PANEL_EXTRA = java.util.Set.of(
            "LiveGroove_1", "LiveGroove_2", "LiveGroove_3", "LiveGroove_4", "LiveGroove_5",
            "HoldFaculty", "ResumeEne");

    /**
     * 参考服出招面板白名单（抓包 127-0-0-1-50768 #2007 的 186 名瘦集）：出招时的
     * 全量 0x10 只重发这批。登录面板里的移动模式/外观/身份属性（Mount、CurRiderType、
     * NewRideID、Mount*Speed、WalkSpeed、CantMove、State、ActionSet 等 78 项）官方
     * 出招不重发——腾空出招瞬间重推 Mount=''/CurRideMount=空 会让客户端清骑行/移动
     * 态，把空中人物强制放回地面（2026-11-03 空中位置重置三轮根因）。
     */
    private static final java.util.Set<String> CAST_PANEL_NAMES = java.util.Set.of(
            "LogicState", "HPRatio", "MPRatio", "MaxHP", "MaxMP", "HP", "MP", "SpeedRatio",
            "MoveSpeed", "RunSpeed", "HitHP", "HitHPRatio", "QingGongPoint", "MaxQingGongPoint",
            "MaxQingGongPointAdd", "NeigongPKStatus", "Dead", "InParry", "CurSkillID",
            "CurSkillTarget", "ModifySkillLockTime", "HPHeartSpeed", "HPHeartSpeedAdd",
            "MPHeartSpeed", "MPHeartSpeedAdd", "HPUpSpeed", "HPUpSpeedAdd", "MPUpSpeed",
            "MPUpSpeedAdd", "Name", "CapitalType1", "CapitalType0", "CapitalType2",
            "CapitalType4", "School", "Force", "NewSchool", "SelectSchool",
            "BufferInfo1", "BufferInfo2", "BufferInfo3", "BufferInfo4", "BufferInfo5",
            "BufferInfo6", "BufferInfo7", "BufferInfo8", "BufferInfo9", "BufferInfo10",
            "BufferInfo11", "BufferInfo12", "BufferInfo13", "BufferInfo14", "BufferInfo15",
            "BufferInfo16", "BufferInfo17", "BufferInfo18", "BufferInfo19", "BufferInfo20",
            "BufferInfo21", "BufferInfo22", "BufferInfo23", "BufferInfo24", "Gravity",
            "GravityAdd", "DropHeightPub", "JumpSpeed", "RunSpeedAdd", "JumpSpeedAdd",
            "DriftSpeed", "DriftSpeedAdd", "DriftJumpSpeed", "DriftJumpSpeedAdd",
            "SndJumpSpeed", "SndJumpSpeedAdd", "ThdJumpSpeed", "ThdJumpSpeedAdd",
            "AirRushSpeed", "AirRushSpeedAdd", "AirRushDist", "AirRushDistAdd",
            "LandRushSpeed", "LandRushSpeedAdd", "LandRushDist", "LandRushDistAdd",
            "ClimbSpeed", "ClimbSpeedAdd", "GRushRange", "GRushRangeAdd", "ARushRange",
            "ARushRangeAdd", "WRushRange", "WRushRangeAdd", "CurJingMai", "LastJingMai",
            "jmActCount", "ZhenQiDayValue", "ZQPLValue", "ZQActlValue", "ZQUnUsedValue",
            "CurNeiGong", "Faculty", "FacultyState", "FacultyStyle", "FacultyName",
            "CurFillValue", "TotalFillValue", "CurLevel", "FillSpeed", "PowerValue",
            "MaxPowerValue", "SP", "Str", "Sta", "Dex", "Ing", "Spi", "MeleePower",
            "MagicPower", "PhyHit", "MagicHit", "Dodge", "MaxParry", "PhyDef", "StiDef",
            "NegDef", "MasDef", "JujDef", "MinMeleeDamage", "MaxMeleeDamage",
            "MaxShotDamage", "MinShotDamage", "PhyDamageAdd", "StiDamageAdd", "JujDamageAdd",
            "NegDamageAdd", "MasDamageAdd", "PhyDamageMul", "StiDamageMul", "JujDamageMul",
            "NegDamageMul", "MasDamageMul", "MaxHPAdd", "MaxMPAdd", "MaxParryAdd",
            "MinMagicDefAdd", "MaxLevel", "ParryValue", "MeleePowerAdd", "MagicPowerAdd",
            "ShotPower", "ShotPowerAdd", "PhyVa", "PhyVaAdd", "MagicVa", "MagicVaAdd",
            "PhyHitAdd", "MagicHitAdd", "DodgeAdd", "PhyParryDamRes", "PhyParryDamResAdd",
            "MagicParryDamRes", "MagicParryDamResAdd", "PhyParryProb", "PhyParryProbAdd",
            "PhyFinalDamageAdd", "StiFinalDamageAdd", "JujFinalDamageAdd",
            "NegFinalDamageAdd", "MasFinalDamageAdd", "PhyFinalDamageReduce",
            "StiFinalDamageReduce", "JujFinalDamageReduce", "NegFinalDamageReduce",
            "MasFinalDamageReduce", "PowerLevel", "LevelTitle", "SkillUseValue",
            "JingMaiTotalLevel", "FamousNeiGongName", "NeigongUseValue", "CharacterFlag",
            "CharacterValue", "Justice", "Evil", "FreeBase", "TitleID");

    /** 出招面板（参考服形态）：stateProperties 过滤到官方出招瘦集。 */
    public byte[] castVitalUpdate() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(CAST_PANEL_NAMES.size());
        for (PropertyValues.Indexed property : stateProperties()) {
            if (CAST_PANEL_NAMES.contains(property.name())) {
                properties.add(property);
            }
        }
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * 历练步进面板：188 名瘦集 + LiveGroove_1..5/HoldFaculty 七项（该帧本来就要
     * 更新的历练域属性）。Mount/CurRideMount 等登录域属性绝不能随打坐中的
     * 周期帧重推（清客户端动作态，2026-11-05 打坐动画根因）。
     */
    public byte[] livepointVitalUpdate() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(CAST_PANEL_NAMES.size() + 8);
        for (PropertyValues.Indexed property : stateProperties()) {
            if (CAST_PANEL_NAMES.contains(property.name()) || LIVEPOINT_PANEL_EXTRA.contains(property.name())) {
                properties.add(property);
            }
        }
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * 收招技能状态清除帧（参考服 exe 127-0-0-1-50768 #2014 六项形态）：
     * LogicState(回显当前，62433 战斗态跨招持续实证非硬编 0) + CurSkillID/Target 清空
     * + CurSkillCanNoLimit/CantBeSkillLocked/CantBeSkillMustLocked 归零。
     * 旧 Go 源码五项版无后三项；缺此帧客户端技能状态机滞留（CurSkill* 残留到下一招）。
     */
    public byte[] skillStateClearUpdate() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(6);
        Actor.State state = actor.snapshot();
        Props.add(properties, "LogicState", PropertyValues.Value.ofByte(effectiveLogicState(state)));
        Props.add(properties, "CurSkillID", PropertyValues.Value.ofString(""));
        Props.add(properties, "CurSkillTarget", PropertyValues.Value.ofObject(0));
        Props.add(properties, "CurSkillCanNoLimit", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "CantBeSkillLocked", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "CantBeSkillMustLocked", PropertyValues.Value.ofInt32(0));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** Go stateProperties：LogicState/资源/恢复速率/名字/碎银/Buff/运动/内功全表。 */
    public List<PropertyValues.Indexed> stateProperties() {
        Actor.State state = actor.snapshot();
        Instant now = clock.instant();
        int hpRecovery = sitcrossRecoveryPerSecond(state.maxHp());
        int mpRecovery = sitcrossRecoveryPerSecond(state.maxMp());
        List<PropertyValues.Indexed> properties = new ArrayList<>(60);
        Props.add(properties, "LogicState", PropertyValues.Value.ofByte(effectiveLogicState(state)));
        Props.add(properties, "HPRatio", PropertyValues.Value.ofInt32(ratio(state.hp(), state.maxHp())));
        Props.add(properties, "MPRatio", PropertyValues.Value.ofInt32(ratio(state.mp(), state.maxMp())));
        // base 方言（同登录面板，IDA 参考服实证）：客户端自行叠 Add+五维，勿推有效值。
        Props.add(properties, "MaxHP", PropertyValues.Value.ofInt32(state.baseMaxHp()));
        Props.add(properties, "MaxMP", PropertyValues.Value.ofInt32(state.baseMaxMp()));
        Props.add(properties, "HP", PropertyValues.Value.ofInt32(state.hp()));
        Props.add(properties, "MP", PropertyValues.Value.ofInt32(state.mp()));
        Props.add(properties, "SpeedRatio", PropertyValues.Value.ofInt32(state.speedRatio()));
        Props.add(properties, "HitHP", PropertyValues.Value.ofInt32(state.hitHp()));
        Props.add(properties, "HitHPRatio", PropertyValues.Value.ofInt32(ratio(state.hitHp(), state.maxHp())));
        Props.add(properties, "QingGongPoint", PropertyValues.Value.ofInt32(state.qingGongPoint()));
        Props.add(properties, "MaxQingGongPoint", PropertyValues.Value.ofInt32(state.maxQingGongPoint()));
        Props.add(properties, "MaxQingGongPointAdd", PropertyValues.Value.ofInt32(state.maxQingGongPointAdd()));
        Props.add(properties, "NeigongPKStatus", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "Dead", PropertyValues.Value.ofInt32(state.hp() <= 0 ? 1 : 0));
        Props.add(properties, "CantUseSkill", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "CurSkillID", PropertyValues.Value.ofString(currentSkillId));
        Props.add(properties, "CurSkillEffectID", PropertyValues.Value.ofString(currentSkillEffectId));
        Props.add(properties, "CurSkillLevel", PropertyValues.Value.ofInt32(currentSkillLevel));
        Props.add(properties, "CurSkillTarget", PropertyValues.Value.ofObject(currentSkillTarget));
        // ModifySkillLockTime：FxGameLogic 自目标技能动作门的毫秒容差（Go 源码注释实证）。
        // 旧 Go 源码 5000，参考服 exe 实发 200（127-0-0-1-50768 n=188 面板）——
        // 5000 会让客户端出招后维持 5s 技能硬直锁，轻功空中出招被锁判定打断、
        // 人物被拉回地面（2026-11-03 空中位置重置根因）。
        Props.add(properties, "ModifySkillLockTime", PropertyValues.Value.ofInt32(200));
        Props.add(properties, "HPHeartSpeed", PropertyValues.Value.ofInt32(1000));
        Props.add(properties, "HPHeartSpeedAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "MPHeartSpeed", PropertyValues.Value.ofInt32(1000));
        Props.add(properties, "MPHeartSpeedAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "HPUpSpeed", PropertyValues.Value.ofInt32(hpRecovery));
        Props.add(properties, "HPUpSpeedAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "MPUpSpeed", PropertyValues.Value.ofInt32(mpRecovery));
        Props.add(properties, "MPUpSpeedAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "Name", PropertyValues.Value.ofWideString(state.name()));
        Props.add(properties, "CapitalType1", PropertyValues.Value.ofInt32(silver));
        Props.add(properties, "BufferListStr", PropertyValues.Value.ofString(bufferListString(now)));
        properties.addAll(factionProperties(faction));
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            Props.add(properties, NpcSchema.bufferInfoPropertyName(slot),
                    PropertyValues.Value.ofString(bufferInfo(slot, now)));
        }
        properties.addAll(baseMotionProperties(motionSnapshot()));
        properties.addAll(qinggongMotionProperties());
        properties.addAll(progress.properties(panel.equipBonus()));
        properties.addAll(com.nineyin.server.game.avatar.jingmai.JingMaiMessages.playerProps(meridian));
        properties.addAll(damageProperties());
        properties.addAll(panel.identityProperties());
        properties.addAll(panel.domainProperties());
        properties.addAll(panelParityProperties(state, now));
        return properties;
    }

    /**
     * 参考全量帧补齐块（F 抓包 61579 演武帧 188 属性对照，2026-11-03）：
     * 我方 vital 原缺的运动三速/格挡族/面板 Add 分解族/货币四件套/功力对偶。
     * 值口径：Add 分解族按 IDA 换算（Add=对应有效五维）；PhyVa/MagicVa 基础值取
     * 真机样例 23/16（模板常量，待 IDA 进一步钉死）；SelectSchool 无数据源不发。
     */
    private List<PropertyValues.Indexed> panelParityProperties(Actor.State state, Instant now) {
        PlayerProgress.Book equipped = progress.book(progress.curNeiGong);
        PlayerProgress.EquipBonus bonus = panel.equipBonus();
        int[] effAttr = progress.parityEffectiveAttributes(bonus); // [臂,身,内息,灵]
        int strEff = effAttr[0];
        int dexEff = effAttr[1];
        int ingEff = effAttr[2];
        int spiEff = effAttr[3];
        List<PropertyValues.Indexed> properties = new ArrayList<>(32);
        // 运动三速（参考全量帧随帧；addObject 亦有）。
        Motion motion = motionSnapshot();
        Props.add(properties, "MoveSpeed", PropertyValues.Value.ofFloat32(motion.moveSpeed));
        Props.add(properties, "RunSpeed", PropertyValues.Value.ofFloat32(motion.runSpeed));
        Props.add(properties, "JumpSpeed", PropertyValues.Value.ofFloat32(8.0f));
        // 格挡族：InParry=BuffInParry 槽活跃位；ParryValue=当前格挡值（动态，满值=上限合计）；
        // ParryRatio/ParryUpSpeed/ParryUpSpeedAdd 为官方登录面板 idx478-480（61579 实证 100/4/0）。
        ActiveBuff parry = buffs.get(PARRY_BUFF_SLOT);
        boolean inParry = parry != null && parry.expiresUtc().isAfter(now);
        Props.add(properties, "InParry", PropertyValues.Value.ofInt32(inParry ? 1 : 0));
        Props.add(properties, "ParryValue", PropertyValues.Value.ofInt32(parryValue()));
        Props.add(properties, "ParryRatio", PropertyValues.Value.ofInt32(100));
        Props.add(properties, "ParryUpSpeed", PropertyValues.Value.ofInt32(PARRY_UP_SPEED_BASE));
        Props.add(properties, "ParryUpSpeedAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "PhyParryProb", PropertyValues.Value.ofInt32(50));
        Props.add(properties, "PhyParryProbAdd", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "PhyParryDamRes", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "PhyParryDamResAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "MagicParryDamRes", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "MagicParryDamResAdd", PropertyValues.Value.ofFloat32(0));
        // 货币四件套（碎银 C1 在主块；此处补 银票 C4 未建档恒 0）。
        Props.add(properties, "CapitalType0", PropertyValues.Value.ofInt32(gold()));
        Props.add(properties, "CapitalType2", PropertyValues.Value.ofInt32(guanYin()));
        Props.add(properties, "CapitalType4", PropertyValues.Value.ofInt32(0));
        // 面板 Add 分解族（IDA：Phy 系=臂力、魔功=内息、命中/闪避/暗器=身法、魔防=灵气）。
        Props.add(properties, "MeleePowerAdd", PropertyValues.Value.ofInt32(strEff));
        Props.add(properties, "MagicPowerAdd", PropertyValues.Value.ofInt32(ingEff));
        Props.add(properties, "PhyHitAdd", PropertyValues.Value.ofInt32(dexEff));
        Props.add(properties, "MagicHitAdd", PropertyValues.Value.ofInt32(spiEff));
        Props.add(properties, "DodgeAdd", PropertyValues.Value.ofInt32(dexEff));
        Props.add(properties, "ShotPower", PropertyValues.Value.ofInt32(80));
        Props.add(properties, "ShotPowerAdd", PropertyValues.Value.ofInt32(dexEff));
        Props.add(properties, "PhyVa", PropertyValues.Value.ofInt32(23));
        Props.add(properties, "PhyVaAdd", PropertyValues.Value.ofInt32(strEff));
        Props.add(properties, "MagicVa", PropertyValues.Value.ofInt32(16));
        Props.add(properties, "MagicVaAdd", PropertyValues.Value.ofInt32(spiEff));
        // 终伤加成/减免十件套（运功书 varprop 键缺省 0）。
        Props.add(properties, "PhyFinalDamageAdd",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "PhyFinalDamageAdd")));
        Props.add(properties, "StiFinalDamageAdd",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "StiFinalDamageAdd")));
        Props.add(properties, "JujFinalDamageAdd",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "JujFinalDamageAdd")));
        Props.add(properties, "NegFinalDamageAdd",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "NegFinalDamageAdd")));
        Props.add(properties, "MasFinalDamageAdd",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "MasFinalDamageAdd")));
        Props.add(properties, "PhyFinalDamageReduce",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "PhyFinalDamageReduce")));
        Props.add(properties, "StiFinalDamageReduce",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "StiFinalDamageReduce")));
        Props.add(properties, "JujFinalDamageReduce",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "JujFinalDamageReduce")));
        Props.add(properties, "NegFinalDamageReduce",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "NegFinalDamageReduce")));
        Props.add(properties, "MasFinalDamageReduce",
                PropertyValues.Value.ofInt32(PlayerProgress.eff(equipped, "MasFinalDamageReduce")));
        // 功力对偶与著名内功（参考：NeigongUseValue=运功书 power；Famous=书名,层 宽串）。
        Props.add(properties, "NeigongUseValue",
                PropertyValues.Value.ofInt32(equipped == null ? 0 : equipped.power));
        Props.add(properties, "SkillUseValue", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "FamousNeiGongName", PropertyValues.Value.ofWideString(
                progress.curNeiGong + "," + (equipped == null ? 0 : equipped.level)));
        return properties;
    }

    /**
     * 每秒轻功点心跳帧（F 抓包 61579 与 294/0x38 同拍逐秒：QingGongPoint + GJ 三项，
     * GJ=高阶轻功点未实现恒 0）。参考 #2192 四元组：150,0,0,0。
     */
    public byte[] qingGongHeartbeatFrame() {
        Actor.State state = actor.snapshot();
        List<PropertyValues.Indexed> properties = new ArrayList<>(4);
        Props.add(properties, "QingGongPoint", PropertyValues.Value.ofInt32(state.qingGongPoint()));
        Props.add(properties, "GJQingGongPoint", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "MaxGJQingGongPoint", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "MaxGJQingGongPointAdd", PropertyValues.Value.ofInt32(0));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** Go factionProperties：原门派 School / 势力 Force / 后期门派 NewSchool 三选一。 */
    public static List<PropertyValues.Indexed> factionProperties(String faction) {
        String school = faction;
        String force = "";
        String newSchool = "";
        if (faction.startsWith("force_")) {
            school = "";
            force = faction;
        } else if (faction.startsWith("newschool_")) {
            school = "";
            newSchool = faction;
        }
        List<PropertyValues.Indexed> properties = new ArrayList<>(3);
        Props.add(properties, "School", PropertyValues.Value.ofString(school));
        Props.add(properties, "Force", PropertyValues.Value.ofString(force));
        Props.add(properties, "NewSchool", PropertyValues.Value.ofString(newSchool));
        return properties;
    }

    /** Go setFaction 返回帧：faction 变更专用单帧（仅 School/Force/NewSchool 三属性）。 */
    public byte[] factionUpdate() {
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, factionProperties(faction));
    }

    /** Go baseMotionProperties：Gravity/GravityAdd/DropHeightPub。 */
    static List<PropertyValues.Indexed> baseMotionProperties(Motion motion) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(3);
        Props.add(properties, "Gravity", PropertyValues.Value.ofFloat32(motion.gravity));
        Props.add(properties, "GravityAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "DropHeightPub", PropertyValues.Value.ofFloat32(100000));
        return properties;
    }

    /** Go qinggongMotionProperties：117..142 的速度/距离族（值取自 QGDefine Para）。 */
    static List<PropertyValues.Indexed> qinggongMotionProperties() {
        float waterSpeed = 7.5f;      // qinggong_2 Para1
        float waterJumpSpeed = 6.0f;  // qinggong_3 Para1
        float secondJumpSpeed = 6.0f; // qinggong_4 Para1
        float thirdJumpSpeed = 6.0f;  // qinggong_5 Para1
        float airRushSpeed = 15;      // qinggong_6 Para1
        float airRushDist = 12;       // qinggong_6 Para2
        float landRushSpeed = 25;     // qinggong_7 Para1
        float landRushDist = 6.5f;    // qinggong_7 Para2
        float climbSpeed = 7.5f;      // qinggong_11 Para1
        List<PropertyValues.Indexed> properties = new ArrayList<>(26);
        Props.add(properties, "RunSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "JumpSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "DriftSpeed", PropertyValues.Value.ofFloat32(waterSpeed));
        Props.add(properties, "DriftSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "DriftJumpSpeed", PropertyValues.Value.ofFloat32(waterJumpSpeed));
        Props.add(properties, "DriftJumpSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "SndJumpSpeed", PropertyValues.Value.ofFloat32(secondJumpSpeed));
        Props.add(properties, "SndJumpSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "ThdJumpSpeed", PropertyValues.Value.ofFloat32(thirdJumpSpeed));
        Props.add(properties, "ThdJumpSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "AirRushSpeed", PropertyValues.Value.ofFloat32(airRushSpeed));
        Props.add(properties, "AirRushSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "AirRushDist", PropertyValues.Value.ofFloat32(airRushDist));
        Props.add(properties, "AirRushDistAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "LandRushSpeed", PropertyValues.Value.ofFloat32(landRushSpeed));
        Props.add(properties, "LandRushSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "LandRushDist", PropertyValues.Value.ofFloat32(landRushDist));
        Props.add(properties, "LandRushDistAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "ClimbSpeed", PropertyValues.Value.ofFloat32(climbSpeed));
        Props.add(properties, "ClimbSpeedAdd", PropertyValues.Value.ofFloat32(0));
        Props.add(properties, "GRushRange", PropertyValues.Value.ofFloat32(5.0f));
        Props.add(properties, "GRushRangeAdd", PropertyValues.Value.ofFloat32(1.5f));
        Props.add(properties, "ARushRange", PropertyValues.Value.ofFloat32(8.0f));
        Props.add(properties, "ARushRangeAdd", PropertyValues.Value.ofFloat32(6.5f));
        Props.add(properties, "WRushRange", PropertyValues.Value.ofFloat32(8.0f));
        Props.add(properties, "WRushRangeAdd", PropertyValues.Value.ofFloat32(6.5f));
        return properties;
    }

    private static int sitcrossRecoveryPerSecond(int maximum) {
        if (maximum <= 0) {
            return 0;
        }
        return (maximum + 10 - 1) / 10;
    }

    /** 官方方言属性写入：索引/线型按名查表，缺名跳过（不计数）。 */
    private static boolean prop(PacketWriter writer, String name, PropertyValues.Value value, Runnable bump) {
        if (Props.append(writer, name, value)) {
            bump.run();
            return true;
        }
        return false;
    }
}
