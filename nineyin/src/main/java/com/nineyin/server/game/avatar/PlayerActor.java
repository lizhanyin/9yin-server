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

    static final class Motion {
        float moveSpeed = 25;
        float runSpeed = 25;
        float gravity = 9.8f;
    }

    record ActiveBuff(long staticData, Instant expiresUtc, String configId, int level) {
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
    /** 右键格挡：54 按值幂等落态（0=按下/1=松开），状态变化才回帧（F 盘抓包 78 发→50 沿）。 */
    private volatile boolean parrying;
    final PlayerProgress progress;
    /** 面板实时状态（身份块字段 + 装备词缀累积层，四期）。 */
    private final PanelState panel = new PanelState();
    private int logicStateOverride = -1;

    /** 右键格挡 BuffInParry：buff 槽 11（BufferInfo11@639）、StaticData 4558、24h（老分支+参考 setPlayerBlock）。 */
    public static final int PARRY_BUFF_SLOT = 11;
    public static final long PARRY_BUFF_STATIC_DATA = 4558L;
    public static final long PARRY_BUFF_DURATION_MS = 24L * 60 * 60 * 1000;

    public static PlayerActor create(String name, int silver, Clock clock) {
        return new PlayerActor(name, silver, clock);
    }

    private PlayerActor(String name, int silver, Clock clock) {
        this.actor = Actor.newPlayer(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, name);
        this.silver = silver;
        this.faction = "force_yihua";
        this.clock = clock;
        this.progress = PlayerProgress.create(clock);
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

    private final List<PlayerShortcut> shortcuts = new ArrayList<>();

    /** Go playerActor.setShortcut：同索引同值幂等；同索引不同值原位替换。 */
    public synchronized ShortcutUpdate setShortcut(int index, String kind, String id) {
        for (int row = 0; row < shortcuts.size(); row++) {
            PlayerShortcut shortcut = shortcuts.get(row);
            if (shortcut.index() != index) {
                continue;
            }
            if (shortcut.kind().equals(kind) && shortcut.id().equals(id)) {
                return new ShortcutUpdate(row, true, true);
            }
            shortcuts.set(row, new PlayerShortcut(index, kind, id));
            return new ShortcutUpdate(row, true, false);
        }
        shortcuts.add(new PlayerShortcut(index, kind, id));
        return new ShortcutUpdate(0, false, false);
    }

    /** Go removeShortcutRow：按行号摘除。 */
    public synchronized PlayerShortcut removeShortcutRow(int row) {
        if (row < 0 || row >= shortcuts.size()) {
            return null;
        }
        return shortcuts.remove(row);
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

    // ------------------------------------------------------------------ faculty

    public PlayerProgress.Snapshot facultySnapshot() {
        return progress.snapshot();
    }

    public void restoreFaculty(PlayerProgress.Snapshot value, Instant now) {
        progress.restore(value, now);
        if (progress.facultyState == PlayerProgress.FACULTY_STATE_CONVERT
                && progress.facultyStyle == PlayerProgress.FACULTY_STYLE_ACT) {
            logicStateOverride = PlayerProgress.LOGIC_STATE_FACULTY;
        } else {
            logicStateOverride = 0;
        }
        syncEquippedNeiGongBuff(now);
        syncEquippedNeiGongResources();
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
        int totalMaxHp = Math.max(1, base.baseMaxHp() + adds[0] + 7 * adds[3] + 2 * adds[2]);
        int totalMaxMp = Math.max(1, base.baseMaxMp() + adds[1] + 4 * adds[4] + adds[5]);
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
        panel.recomputeEquipBonuses(affixes, propBuilts);
        panel.applyEquipEffects(EquipEffectCatalog.load(), affixes, effectPackIds);
        syncEquippedNeiGongResources();
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
                buff.expiresUtc().toEpochMilli(), 1);
    }

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
                    buff.expiresUtc().toEpochMilli()));
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

    /** 格挡属性帧（0x10）：InParry/InManualParry 置位 + BufferInfo11（槽串）。 */
    public byte[] parryStateFrame(boolean engage, String buffInfo) {
        int flag = engage ? 1 : 0;
        List<PropertyValues.Indexed> properties = new ArrayList<>(3);
        Props.add(properties, "InParry", PropertyValues.Value.ofInt32(flag));
        Props.add(properties, "InManualParry", PropertyValues.Value.ofInt32(flag));
        Props.add(properties, "BufferInfo" + PARRY_BUFF_SLOT, PropertyValues.Value.ofString(buffInfo));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    // ------------------------------------------------------------------- combat

    /** Go beginSkillUse 的 (bool, reason)。 */
    public record SkillGate(boolean allowed, String reason) {

        static SkillGate reject(String reason) {
            return new SkillGate(false, reason);
        }
    }

    /** 技能冷却（skillId → 就绪时刻）与公共 CD 门（gateKey → 就绪时刻）。 */
    private final java.util.Map<String, Instant> skillCooldowns = new java.util.HashMap<>();
    private final java.util.Map<Long, Instant> skillTeamCooldowns = new java.util.HashMap<>();
    private Instant skillLockUntil;
    private String currentSkillId = "";
    private String currentSkillEffectId = "";
    private int currentSkillLevel;
    private long currentSkillTarget;
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

    /** 单属性 LogicState 帧（进/退战斗的即时推送，62433 抓包 n=6 小帧的精简形态）。 */
    public byte[] logicStateFrame() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "LogicState",
                PropertyValues.Value.ofByte(effectiveLogicState(actor.snapshot())));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** 当前是否战斗态（格挡闸门等）。 */
    public boolean fighting() {
        return actor.snapshot().logicState() == Actor.LOGIC_STATE_FIGHTING;
    }

    /**
     * Go beginSkillUse：技能锁 → 个人 CD → 公共 CD → 死亡 → MP → SP → 轻功点，
     * 全过才扣资源、写 CD/当前技能、进 Fighting。拒绝返回原因（调用方静默记日志）。
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
        long gateKey = skill.cooldownGateKey();
        Instant teamReady = skillTeamCooldowns.get(gateKey);
        if (teamReady != null && now.isBefore(teamReady)) {
            return SkillGate.reject("public cooldown team=" + gateKey + " remaining="
                    + java.time.Duration.between(now, teamReady).toMillis() + "ms");
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
        skillTeamCooldowns.put(gateKey, now.plusMillis(skill.publicCdMs()));
        currentSkillId = skill.id();
        currentSkillEffectId = skill.id();
        currentSkillLevel = skill.level();
        currentSkillTarget = targetKey;
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

    /** 找空闲 buff 槽（未占用或已过期），无则 -1。 */
    public synchronized int freeBuffSlot() {
        Instant now = clock.instant();
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            ActiveBuff buff = buffs.get(slot);
            if (buff == null || !buff.expiresUtc().isAfter(now)) {
                return slot;
            }
        }
        return -1;
    }

    /** 单 buff 槽属性帧（0x10 BufferInfoN）。 */
    public byte[] buffSlotFrame(int slot, String buffInfo) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, NpcSchema.bufferInfoPropertyName(slot),
                PropertyValues.Value.ofString(buffInfo == null ? "" : buffInfo));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /** 出招收尾 stand 复位帧（0x10 State，idx19 官方表按名解析）。 */
    public byte[] standStateFrame() {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "State", PropertyValues.Value.ofString("stand"));
        return SceneMessages.objectProperties(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * 伤害公式攻方面（formula.lua 的 attack）：装备外功上下限 + 内功运功书
     * PhyDamageAdd；掷值由调用方做（可测试）。
     */
    public int[] meleeDamageRange() {
        PlayerProgress.EquipBonus bonus = panel.equipBonus();
        PlayerProgress.Book equipped = progress.book(progress.curNeiGong);
        int add = PlayerProgress.eff(equipped, "PhyDamageAdd");
        int meleePower = bonus == null ? 0 : bonus.meleePowerAdd();
        int min = (bonus == null ? 0 : bonus.minMelee()) + add + meleePower;
        int max = (bonus == null ? 0 : bonus.maxMelee()) + add + meleePower;
        if (max < min) {
            max = min;
        }
        return new int[]{min, max};
    }

    // ------------------------------------------------------------------- frames

    /** Go addObject：0x3f 首部 + 属性归档，count 回填 @0x3d。 */
    public byte[] addObject(Roles.Position location, RoleVisual visual) {
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
        prop(writer, "ActionSet", PropertyValues.Value.ofString(visual.actionSet()), bump);
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
        prop(writer, "MaxHP", PropertyValues.Value.ofInt32(state.maxHp()), bump);   // 有效上限（base+Add层+五维换算）
        prop(writer, "MaxMP", PropertyValues.Value.ofInt32(state.maxMp()), bump);
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
        prop(writer, "ModifySkillLockTime", PropertyValues.Value.ofInt32(5000), bump);
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
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            prop(writer, NpcSchema.bufferInfoPropertyName(slot), PropertyValues.Value.ofString(bufferInfo(slot, now)), bump);
        }
        for (PropertyValues.Indexed property : progress.properties(panel.equipBonus())) {
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
        Props.add(properties, "MaxHP", PropertyValues.Value.ofInt32(state.maxHp()));  // 有效上限（base+Add层+五维换算）
        Props.add(properties, "MaxMP", PropertyValues.Value.ofInt32(state.maxMp()));
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
        Props.add(properties, "ModifySkillLockTime", PropertyValues.Value.ofInt32(5000));
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
        properties.addAll(damageProperties());
        properties.addAll(panel.identityProperties());
        properties.addAll(panel.domainProperties());
        return properties;
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
