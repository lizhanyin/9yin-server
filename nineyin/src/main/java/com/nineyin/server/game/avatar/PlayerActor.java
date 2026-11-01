package com.nineyin.server.game.avatar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nineyin.server.game.avatar.neigong.PlayerProgress;
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
    private final int silver;
    private String faction;
    private final Motion motion = new Motion();
    private final java.util.Map<Integer, ActiveBuff> buffs = new java.util.HashMap<>();
    final PlayerProgress progress;
    private int logicStateOverride = -1;

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
        int[] adds = progress.equippedResourceAdds();
        Actor.State base = actor.snapshot();
        actor.setDerivedMaxima(base.baseMaxHp() + adds[0], base.baseMaxMp() + adds[1]);
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
        prop(writer, "CurSkillID", PropertyValues.Value.ofString(""), bump);
        prop(writer, "CurSkillEffectID", PropertyValues.Value.ofString(""), bump);
        prop(writer, "CurSkillLevel", PropertyValues.Value.ofInt32(0), bump);
        prop(writer, "CurSkillTarget", PropertyValues.Value.ofObject(0), bump);
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
        for (PropertyValues.Indexed property : progress.properties()) {
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
        Props.add(properties, "CurSkillID", PropertyValues.Value.ofString(""));
        Props.add(properties, "CurSkillEffectID", PropertyValues.Value.ofString(""));
        Props.add(properties, "CurSkillLevel", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "CurSkillTarget", PropertyValues.Value.ofObject(0));
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
        properties.addAll(progress.properties());
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
