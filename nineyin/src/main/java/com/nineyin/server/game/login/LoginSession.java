package com.nineyin.server.game.login;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.scene.SceneLifecycle;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.session.SessionMachine;

/** 单连接的登录链状态，对齐 Go handle() 的连接级局部状态（machine/account/selected/player）。 */
public final class LoginSession {

    final SessionMachine machine = new SessionMachine();
    Roles.Account account;
    Roles.RoleSnapshot selected;
    PlayerActor player;
    RoleVisual visual;
    String accountKey;
    /** Go handle() 的 qingGongGranted/neigongGranted 闭包标志（每连接一次）。 */
    boolean qingGongGranted;
    boolean neigongGranted;
    /** 经脉 View45 授予标志（每连接一次）。 */
    boolean meridianGranted;
    /** 个性配置：ClientReady 是否已尝试推送存档 / 会话是否已见过一次上传。 */
    boolean customizingPushed;
    boolean customizingSeenUpload;
    /** 键位映射：post-spawn 是否已尝试推送存档（ShortcutKey 属性帧）。 */
    boolean keybindPushed;
    /** 风物志：会话内皮肤状态（选角时装载，GM 动作原地改写）；ClientReady 是否已推送。 */
    com.nineyin.server.game.avatar.skin.SkinStore.State skinState;
    boolean skinsPushed;
    /** 风物志：已推送的收集位图行数/挂载行数（整刷前删行的口径）。 */
    int skinRows;
    int skinMountedRows;
    /** 时装页签：已激活套装（role_fashion_suits；选角时装载，ClientReady 推 FashionSuitRec 行）。 */
    java.util.List<String> fashionSuits = java.util.List.of();
    boolean fashionPushed;
    /** 二期 NPC：场景目录/活跃下标集/生命周期；lastPositionSave 是 2s 节流基准。 */
    SceneLifecycle world;
    List<NpcSpawn> sceneNpcs = List.of();
    Set<Integer> activeNpcs = new java.util.HashSet<>();
    Instant lastPositionSave;
    Roles.Location pendingLocation;
    /** 三期内修 1s ticker 句柄（断连取消）。 */
    java.util.concurrent.ScheduledFuture<?> facultyTicker;
    /** 战斗一期：单连接战斗态（选角时重建）；combatRandom 测试可换种子。 */
    com.nineyin.server.world.fight.FightState fight;
    java.util.Map<String, Integer> learnedSkills = new java.util.HashMap<>();
    /** View40 行号表（id → 行，CanUse 行帧寻址，授予时与 View40 行同序固化）。 */
    java.util.Map<String, Integer> skillRows = new java.util.HashMap<>();
    java.util.Random combatRandom = new java.util.Random();

    public LoginSession() {
    }
}
