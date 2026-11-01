package com.nineyin.game.avatar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nineyin.game.avatar.neigong.PlayerProgress;
import com.nineyin.protocol.NpcSchema;
import com.nineyin.protocol.PacketWriter;
import com.nineyin.protocol.PropertyIndex;
import com.nineyin.protocol.PropertyValues;
import com.nineyin.protocol.SceneMessages;
import com.nineyin.role.Roles;

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

    public Motion motionSnapshot() {
        Motion snapshot = new Motion();
        snapshot.moveSpeed = motion.moveSpeed;
        snapshot.runSpeed = motion.runSpeed;
        snapshot.gravity = motion.gravity;
        return snapshot;
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

    void syncEquippedNeiGongBuff(Instant now) {
        PlayerProgress.Book book = progress.book(progress.curNeiGong);
        if (book == null || book.buffId.isEmpty() || book.buffStaticData == 0 || book.buffLevel <= 0) {
            buffs.remove(PropertyIndex.INNER_POWER_BUFF_SLOT);
            return;
        }
        buffs.put(PropertyIndex.INNER_POWER_BUFF_SLOT, new ActiveBuff(
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
        addByte(writer, 0, 2, bump);                    // Type = TYPE_PLAYER
        addByte(writer, 1, visual.sex(), bump);
        addString(writer, 2, visual.photo(), bump);
        addInt(writer, 3, 0, bump);                     // Camp
        addInt(writer, 4, 0, bump);                     // Race
        addInt(writer, 5, 0, bump);                     // Job
        addInt(writer, 6, 1, bump);                     // Level
        addFloat(writer, 9, location.x(), bump);
        addFloat(writer, 10, location.y(), bump);
        addFloat(writer, 11, location.z(), bump);
        addFloat(writer, 12, location.orient(), bump);
        addString(writer, 13, visual.hair(), bump);
        addString(writer, 14, visual.face(), bump);
        addString(writer, 15, visual.cloth(), bump);
        addString(writer, 16, visual.pants(), bump);
        addString(writer, 17, visual.shoes(), bump);
        addString(writer, 18, visual.actionSet(), bump);
        addString(writer, 19, "stand", bump);
        addInt(writer, 20, 0, bump);
        addByte(writer, 21, effectiveLogicState(state), bump);
        addFloat(writer, 22, 8.0f, bump);
        addFloat(writer, 23, currentMotion.moveSpeed, bump);
        addFloat(writer, 24, 7.5f, bump);
        addFloat(writer, 25, currentMotion.runSpeed, bump);
        addInt(writer, 26, state.speedRatio(), bump);
        addByte(writer, 27, 0, bump);
        addInt(writer, 28, ratio(state.hp(), state.maxHp()), bump);
        addInt(writer, 29, ratio(state.mp(), state.maxMp()), bump);
        addInt(writer, 30, state.baseMaxHp(), bump);
        addInt(writer, 31, state.baseMaxMp(), bump);
        addInt(writer, 32, state.hp(), bump);
        addInt(writer, 33, state.mp(), bump);
        addInt(writer, 112, state.hitHp(), bump);
        addInt(writer, 113, ratio(state.hitHp(), state.maxHp()), bump);
        addInt(writer, 114, state.qingGongPoint(), bump);
        addInt(writer, 115, state.maxQingGongPoint(), bump);
        addInt(writer, 116, state.maxQingGongPointAdd(), bump);
        addInt(writer, PropertyIndex.NEIGONG_PK_STATUS, 0, bump);
        addInt(writer, PropertyIndex.DEAD, state.hp() <= 0 ? 1 : 0, bump);
        addInt(writer, PropertyIndex.CANT_USE_SKILL, 0, bump);
        addString(writer, PropertyIndex.CUR_SKILL_ID, "", bump);
        addString(writer, PropertyIndex.CUR_SKILL_EFFECT_ID, "", bump);
        addInt(writer, PropertyIndex.CUR_SKILL_LEVEL, 0, bump);
        writer.writeU16(PropertyIndex.CUR_SKILL_TARGET);
        writer.writeU64(0);
        bump.run();
        addInt(writer, PropertyIndex.MODIFY_SKILL_LOCK_TIME, 5000, bump);
        addString(writer, NpcSchema.BUFFER_LIST_PROPERTY_INDEX, bufferListString(now), bump);
        for (PropertyValues.Indexed property : baseMotionProperties(currentMotion)) {
            writer.writeU16(property.int16Index()).writeF32(property.value().f32());
            bump.run();
        }
        for (PropertyValues.Indexed property : qinggongMotionProperties()) {
            writer.writeU16(property.int16Index()).writeF32(property.value().f32());
            bump.run();
        }
        addInt(writer, 111, silver, bump); // CapitalType1 碎银
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            addString(writer, bufferInfoPropertyIndex(slot), bufferInfo(slot, now), bump);
        }
        for (PropertyValues.Indexed property : progress.properties()) {
            writer.appendIndexed(property);
            bump.run();
        }
        addString(writer, 35, visual.hair(), bump);
        writer.appendWideStringProperty(36, state.name());
        bump.run();
        writer.writeU16(100);
        writer.writeU64(0);
        bump.run();
        byte[] frame = writer.toBytes();
        // count 回填到 0x3d
        frame[0x3d] = (byte) count[0];
        frame[0x3e] = (byte) (count[0] >>> 8);
        return frame;
    }

    private int effectiveLogicState(Actor.State state) {
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
        properties.add(PropertyValues.Indexed.of(21, "LogicState", PropertyValues.Value.ofByte(effectiveLogicState(state))));
        properties.add(PropertyValues.Indexed.of(28, "HPRatio", PropertyValues.Value.ofInt32(ratio(state.hp(), state.maxHp()))));
        properties.add(PropertyValues.Indexed.of(29, "MPRatio", PropertyValues.Value.ofInt32(ratio(state.mp(), state.maxMp()))));
        properties.add(PropertyValues.Indexed.of(30, "MaxHP", PropertyValues.Value.ofInt32(state.baseMaxHp())));
        properties.add(PropertyValues.Indexed.of(31, "MaxMP", PropertyValues.Value.ofInt32(state.baseMaxMp())));
        properties.add(PropertyValues.Indexed.of(32, "HP", PropertyValues.Value.ofInt32(state.hp())));
        properties.add(PropertyValues.Indexed.of(33, "MP", PropertyValues.Value.ofInt32(state.mp())));
        properties.add(PropertyValues.Indexed.of(26, "SpeedRatio", PropertyValues.Value.ofInt32(state.speedRatio())));
        properties.add(PropertyValues.Indexed.of(112, "HitHP", PropertyValues.Value.ofInt32(state.hitHp())));
        properties.add(PropertyValues.Indexed.of(113, "HitHPRatio", PropertyValues.Value.ofInt32(ratio(state.hitHp(), state.maxHp()))));
        properties.add(PropertyValues.Indexed.of(114, "QingGongPoint", PropertyValues.Value.ofInt32(state.qingGongPoint())));
        properties.add(PropertyValues.Indexed.of(115, "MaxQingGongPoint", PropertyValues.Value.ofInt32(state.maxQingGongPoint())));
        properties.add(PropertyValues.Indexed.of(116, "MaxQingGongPointAdd", PropertyValues.Value.ofInt32(state.maxQingGongPointAdd())));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.NEIGONG_PK_STATUS, "NeigongPKStatus", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.DEAD, "Dead", PropertyValues.Value.ofInt32(state.hp() <= 0 ? 1 : 0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CANT_USE_SKILL, "CantUseSkill", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_SKILL_ID, "CurSkillID", PropertyValues.Value.ofString("")));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_SKILL_EFFECT_ID, "CurSkillEffectID", PropertyValues.Value.ofString("")));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_SKILL_LEVEL, "CurSkillLevel", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.CUR_SKILL_TARGET, "CurSkillTarget", PropertyValues.Value.ofObject(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MODIFY_SKILL_LOCK_TIME, "ModifySkillLockTime", PropertyValues.Value.ofInt32(5000)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.HP_HEART_SPEED, "HPHeartSpeed", PropertyValues.Value.ofInt32(1000)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.HP_HEART_SPEED_ADD, "HPHeartSpeedAdd", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MP_HEART_SPEED, "MPHeartSpeed", PropertyValues.Value.ofInt32(1000)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MP_HEART_SPEED_ADD, "MPHeartSpeedAdd", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.HP_UP_SPEED, "HPUpSpeed", PropertyValues.Value.ofInt32(hpRecovery)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.HP_UP_SPEED_ADD, "HPUpSpeedAdd", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MP_UP_SPEED, "MPUpSpeed", PropertyValues.Value.ofInt32(mpRecovery)));
        properties.add(PropertyValues.Indexed.of(PropertyIndex.MP_UP_SPEED_ADD, "MPUpSpeedAdd", PropertyValues.Value.ofInt32(0)));
        properties.add(PropertyValues.Indexed.of(36, "Name", PropertyValues.Value.ofWideString(state.name())));
        properties.add(PropertyValues.Indexed.of(111, "CapitalType1", PropertyValues.Value.ofInt32(silver)));
        properties.add(PropertyValues.Indexed.of(NpcSchema.BUFFER_LIST_PROPERTY_INDEX, "BufferListStr",
                PropertyValues.Value.ofString(bufferListString(now))));
        properties.addAll(factionProperties(faction));
        for (int slot = 1; slot <= NpcSchema.BUFFER_INFO_SLOT_COUNT; slot++) {
            properties.add(PropertyValues.Indexed.of(bufferInfoPropertyIndex(slot),
                    NpcSchema.bufferInfoPropertyName(slot), PropertyValues.Value.ofString(bufferInfo(slot, now))));
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
        return List.of(
                PropertyValues.Indexed.of(53, "School", PropertyValues.Value.ofString(school)),
                PropertyValues.Indexed.of(PropertyIndex.FORCE, "Force", PropertyValues.Value.ofString(force)),
                PropertyValues.Indexed.of(PropertyIndex.NEW_SCHOOL, "NewSchool", PropertyValues.Value.ofString(newSchool)));
    }

    /** Go baseMotionProperties：Gravity/GravityAdd/DropHeightPub。 */
    static List<PropertyValues.Indexed> baseMotionProperties(Motion motion) {
        return List.of(
                PropertyValues.Indexed.of(168, "Gravity", PropertyValues.Value.ofFloat32(motion.gravity)),
                PropertyValues.Indexed.of(169, "GravityAdd", PropertyValues.Value.ofFloat32(0)),
                PropertyValues.Indexed.of(170, "DropHeightPub", PropertyValues.Value.ofFloat32(100000)));
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
        properties.add(floatProp(117, "RunSpeedAdd", 0));
        properties.add(floatProp(118, "JumpSpeedAdd", 0));
        properties.add(floatProp(119, "DriftSpeed", waterSpeed));
        properties.add(floatProp(120, "DriftSpeedAdd", 0));
        properties.add(floatProp(121, "DriftJumpSpeed", waterJumpSpeed));
        properties.add(floatProp(122, "DriftJumpSpeedAdd", 0));
        properties.add(floatProp(123, "SndJumpSpeed", secondJumpSpeed));
        properties.add(floatProp(124, "SndJumpSpeedAdd", 0));
        properties.add(floatProp(125, "ThdJumpSpeed", thirdJumpSpeed));
        properties.add(floatProp(126, "ThdJumpSpeedAdd", 0));
        properties.add(floatProp(127, "AirRushSpeed", airRushSpeed));
        properties.add(floatProp(128, "AirRushSpeedAdd", 0));
        properties.add(floatProp(129, "AirRushDist", airRushDist));
        properties.add(floatProp(130, "AirRushDistAdd", 0));
        properties.add(floatProp(131, "LandRushSpeed", landRushSpeed));
        properties.add(floatProp(132, "LandRushSpeedAdd", 0));
        properties.add(floatProp(133, "LandRushDist", landRushDist));
        properties.add(floatProp(134, "LandRushDistAdd", 0));
        properties.add(floatProp(135, "ClimbSpeed", climbSpeed));
        properties.add(floatProp(136, "ClimbSpeedAdd", 0));
        properties.add(floatProp(137, "GRushRange", 5.0f));
        properties.add(floatProp(138, "GRushRangeAdd", 1.5f));
        properties.add(floatProp(139, "ARushRange", 8.0f));
        properties.add(floatProp(140, "ARushRangeAdd", 6.5f));
        properties.add(floatProp(141, "WRushRange", 8.0f));
        properties.add(floatProp(142, "WRushRangeAdd", 6.5f));
        return properties;
    }

    private static PropertyValues.Indexed floatProp(int index, String name, float value) {
        return PropertyValues.Indexed.of(index, name, PropertyValues.Value.ofFloat32(value));
    }

    private static int sitcrossRecoveryPerSecond(int maximum) {
        if (maximum <= 0) {
            return 0;
        }
        return (maximum + 10 - 1) / 10;
    }

    private static void addByte(PacketWriter writer, int index, int value, Runnable bump) {
        writer.writeU16(index).writeU8(value);
        bump.run();
    }

    private static void addInt(PacketWriter writer, int index, int value, Runnable bump) {
        writer.writeU16(index).writeU32(value & 0xFFFFFFFFL);
        bump.run();
    }

    private static void addFloat(PacketWriter writer, int index, float value, Runnable bump) {
        writer.writeU16(index).writeF32(value);
        bump.run();
    }

    private static void addString(PacketWriter writer, int index, String value, Runnable bump) {
        writer.appendStringProperty(index, value);
        bump.run();
    }
}
