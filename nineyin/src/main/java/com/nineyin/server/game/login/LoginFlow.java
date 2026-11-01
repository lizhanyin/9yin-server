package com.nineyin.server.game.login;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.protocol.EquipmentModels;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.world.npc.SceneNpcRegistry;
import com.nineyin.server.world.scene.NpcViewport;
import com.nineyin.server.world.scene.SceneLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.neigong.FacultyCustomHandler;
import com.nineyin.server.game.avatar.panel.PanelState;
import com.nineyin.server.game.starter.StarterGrants;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.server.world.db.PanelDataStore;
import com.nineyin.server.game.starter.StarterQingGong;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.LoginMessages;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.SceneNames;
import com.nineyin.server.protocol.SceneMessages;
import com.nineyin.server.db.role.RoleStoreFacade;
import com.nineyin.server.db.role.Roles;
import com.nineyin.server.session.SessionException;
import com.nineyin.server.session.SessionMachine;
import com.nineyin.server.transport.Connection;

/**
 * 登录链消息分支，逐行移植 Go cmd/protocol-probe/main.go handle() 的
 * 0x02/0x03/0x05/0x04 与 0x09/0x0A 状态机处理。返回 false 表示断连（Go return）。
 *
 * <p>一期边界：0x09/0x0A 只做相位转移 + vital 回包；starter 链与 NPC 物化属二期。
 */
public final class LoginFlow {

    private static final Logger log = LoggerFactory.getLogger(LoginFlow.class);

    static final int STARTER_SILVER = 99999;
    /** 选人界面实力档位种子（一期无实力持久化，固定 151=title027 返璞归真）。 */
    static final int ROLE_LIST_POWER_LEVEL = 151;
    /** Go -npc-radius 默认 120 / -npc-patrol-count 默认 3。 */
    static final float NPC_RADIUS = 120.0f;
    static final int NPC_PATROL_COUNT = 3;
    /** Go 位置保存节流（≥2s）。 */
    static final Duration POSITION_SAVE_INTERVAL = Duration.ofSeconds(2);

    private final RoleStoreFacade roleStore;
    private final FacultyStore facultyStore;
    private final GmHub gmHub;
    private final Clock clock;
    private final SceneNpcRegistry npcRegistry;
    private final ScheduledExecutorService scheduler;
    /** 三期物品域 DB 源（可空=回退 starter 行为）。 */
    private final RoleInventoryStore inventoryStore;
    /** 四期面板域 DB 源（称号/兵录/声望/坐骑；可空=回退种子值）。 */
    private final PanelDataStore panelStore;

    public LoginFlow(RoleStoreFacade roleStore, FacultyStore facultyStore, GmHub gmHub, Clock clock,
                     SceneNpcRegistry npcRegistry, ScheduledExecutorService scheduler) {
        this(roleStore, facultyStore, gmHub, clock, npcRegistry, scheduler, null, null);
    }

    public LoginFlow(RoleStoreFacade roleStore, FacultyStore facultyStore, GmHub gmHub, Clock clock,
                     SceneNpcRegistry npcRegistry, ScheduledExecutorService scheduler,
                     RoleInventoryStore inventoryStore) {
        this(roleStore, facultyStore, gmHub, clock, npcRegistry, scheduler, inventoryStore, null);
    }

    public LoginFlow(RoleStoreFacade roleStore, FacultyStore facultyStore, GmHub gmHub, Clock clock,
                     SceneNpcRegistry npcRegistry, ScheduledExecutorService scheduler,
                     RoleInventoryStore inventoryStore, PanelDataStore panelStore) {
        this.roleStore = roleStore;
        this.facultyStore = facultyStore;
        this.gmHub = gmHub;
        this.clock = clock;
        this.npcRegistry = npcRegistry;
        this.scheduler = scheduler;
        this.inventoryStore = inventoryStore;
        this.panelStore = panelStore;
    }

    /** 处理一帧明文；返回 false 断连。 */
    public boolean dispatch(Connection connection, LoginSession session, byte[] plain) {
        SessionException invalid = session.machine.validateMessage(plain);
        if (invalid != null) {
            log.info("session={} reject client message: {}", session.machine.id(), invalid.getMessage());
            return false;
        }
        int opcode = plain[0] & 0xFF;
        return switch (opcode) {
            case SessionMachine.CLIENT_LOGIN -> onLogin(connection, session, plain);
            case SessionMachine.CLIENT_WORLD_INFO -> onWorldInfo(connection);
            case SessionMachine.CLIENT_CREATE_ROLE -> onCreateRole(connection, session, plain);
            case SessionMachine.CLIENT_CHOOSE_ROLE -> onChooseRole(connection, session);
            case SessionMachine.CLIENT_READY -> onClientReady(connection, session);
            case SessionMachine.CLIENT_ACTIVITY -> onClientActivity(connection, session, plain);
            case 0x1E, 0x27 -> onCustomDirect(connection, session, plain);
            default -> true; // 未分类 opcode 放行业务层（一期无业务层）
        };
    }

    private boolean onLogin(Connection connection, LoginSession session, byte[] plain) {
        LoginIdentity identity;
        try {
            identity = LoginIdentity.parse(plain);
        } catch (IllegalArgumentException e) {
            log.info("session={} reject login structure: {}", session.machine.id(), e.getMessage());
            return false;
        }
        session.accountKey = identity.string();
        log.info("session={} opcode=0x02 decoded_len={} account={}",
                session.machine.id(), plain.length, identity.prefix());

        RoleStoreFacade.LoginResult result;
        try {
            result = roleStore.login(session.accountKey);
        } catch (RuntimeException e) {
            log.warn("load account role: {}", e.getMessage());
            return false;
        }
        session.account = result.account();
        session.selected = result.snapshot();
        boolean exists = session.selected != null;
        byte[] payload = exists ? oneRoleWithVisual(session)
                : LoginMessages.noRoleLoginSuccess(java.time.LocalDateTime.now(clock));
        connection.writeFrame(payload);
        SessionException transition = session.machine.loginCompleted(exists);
        if (transition != null) {
            log.info("complete login transition: {}", transition.getMessage());
            return false;
        }
        log.info("session={} sent ServerPlayerRoles(opcode=0x04, role_exists={})", session.machine.id(), exists);
        return true;
    }

    /**
     * 0x04 帧构造：外观经装备表解析；f23 实力称号（一期种子 151=title027）、
     * f24 角色实际所在场景中文名（scenes.ini+desc.idres）、f25 剧本标题键
     * （introduction_*01_title），f32 保持 book 原值。
     */
    private byte[] oneRoleWithVisual(LoginSession session) {
        List<String> appearance = session.selected.appearance().values();
        RoleVisual visual = RoleVisual.resolve(appearance, EquipmentResolver.loadDefault());
        String book = appearance.isEmpty() || appearance.get(0).isEmpty() ? "book1" : appearance.get(0);
        Roles.Location location = session.selected.location();
        return LoginMessages.oneRoleLoginSuccess(java.time.LocalDateTime.now(clock),
                session.selected.name(), appearance,
                RoleStoreFacade.roleFaction(appearance),
                visual.hair(), visual.cloth(), visual.pants(), visual.shoes(),
                LoginMessages.roleListTitle(ROLE_LIST_POWER_LEVEL),
                SceneNames.sceneDisplayName(location.scene().config(), location.scene().resource()),
                SceneNames.bookIntroKey(book));
    }

    private boolean onWorldInfo(Connection connection) {
        connection.writeFrame(LoginMessages.worldInfo(0, "0,book1,0,book2,0,book4,0,book5,0,book8,0,0"));
        return true;
    }

    private boolean onCreateRole(Connection connection, LoginSession session, byte[] plain) {
        String name = LoginMessages.createRoleName(plain);
        if (name.isEmpty()) {
            name = "本地角色";
        }
        List<String> appearance = LoginMessages.createRoleStrings(plain);
        try {
            session.selected = roleStore.create(session.account, name, appearance);
        } catch (RuntimeException e) {
            log.warn("persist created role: {}", e.getMessage());
            return false;
        }
        connection.writeFrame(oneRoleWithVisual(session));
        SessionException transition = session.machine.roleCreated();
        if (transition != null) {
            log.info("complete create-role transition: {}", transition.getMessage());
            return false;
        }
        log.info("session={} sent ServerPlayerRoles with created role {}", session.machine.id(), name);
        return true;
    }

    /** 选角进世界：0x09 表 → 0x0A 表 → 8×0x15 → 0x0B → 0x0D/0x1F/0x10 → RoleChosen。 */
    private boolean onChooseRole(Connection connection, LoginSession session) {
        if (session.selected == null) {
            log.info("session={} choose role without an account-owned role", session.machine.id());
            return false;
        }
        Roles.Location location = session.selected.location();
        location = new Roles.Location(normalizeClientScene(location.scene()), location.position(), location.version());

        session.visual = RoleVisual.resolve(
                session.selected.appearance().values(), EquipmentResolver.loadDefault());
        session.player = PlayerActor.create(session.selected.name(), STARTER_SILVER, clock);
        session.player.setFaction(RoleStoreFacade.roleFaction(session.selected.appearance().values()));

        var saved = facultyStore.load(session.selected.id());
        if (saved != null) {
            session.player.restoreFaculty(saved, clock.instant());
            log.info("session={} restored persistent faculty role={} name={}",
                    session.machine.id(), session.selected.id(), saved.facultyName());
        } else {
            facultyStore.save(session.selected.id(), session.player.facultySnapshot());
            log.info("session={} initialized persistent normal faculty role={}",
                    session.machine.id(), session.selected.id());
        }

        connection.writeFrame(SceneMessages.propertyTableFrame());
        connection.writeFrame(SceneMessages.recordTableFrame());
        // 三期官方方言：17 视图容器（8 主页 BaseCap=18 + 8 扩展页 + View1 纸娃娃）。
        java.util.List<RoleInventoryStore.Bagpack> bagpacks = loadBagpacks(session);
        for (byte[] frame : InventoryMessages.bagViewCreateFrames()) {
            connection.writeFrame(frame);
        }
        for (byte[] frame : InventoryMessages.itemBagFrames(
                session.selected.id(), bagpacks, clock.instant())) {
            connection.writeFrame(frame);
        }
        // Go world.begin：场景世代重置（清实体/停定时器），不发帧。
        session.world = new SceneLifecycle(connection, scheduler);
        session.world.begin(location.scene().config(), location.scene().resource());
        connection.writeFrame(SceneMessages.entryScene(location.scene()));
        // 四期装备词缀/特效层（DB 模式）：addObject 归档前重算，登录帧即带装备加成面板。
        var equips = loadEquips(session);
        applyEquipBonuses(session, equips);
        loadPanelData(session);
        connection.writeFrame(session.player.addObject(location.position(), session.visual));
        connection.writeFrame(SceneMessages.serverLocation(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, location.position()));
        connection.writeFrame(session.player.vitalUpdate());
        // 三期物品下发（DB 模式）：纸娃娃 + 背包成对行 + WeaponMode；无 DB 行回退空。
        if (!equips.isEmpty()) {
            try {
                int sex = session.visual.sex();
                var bagItems = inventoryStore.bagItems(session.selected.id());
                for (byte[] frame : InventoryMessages.inventoryFrames(
                        session.selected.id(), sex, equips, bagItems,
                        EquipmentResolver.loadDefault())) {
                    connection.writeFrame(frame);
                }
                log.info("session={} pushed inventory equips={} bagItems={} role={}",
                        session.machine.id(), equips.size(), bagItems.size(), session.selected.id());
            } catch (RuntimeException e) {
                log.warn("session={} push inventory: {}", session.machine.id(), e.getMessage());
            }
        }
        // NPC viewport 仅注册不上线（ClientReady/首个 0x0A 屏障后物化）。
        if (npcRegistry != null) {
            try {
                session.sceneNpcs = npcRegistry.catalogFor(location.scene()).spawns();
            } catch (Exception e) {
                log.warn("session={} load NPC catalog for {}: {}", session.machine.id(),
                        location.scene().resource(), e.getMessage());
                session.sceneNpcs = List.of();
            }
        }
        if (!session.sceneNpcs.isEmpty()) {
            try {
                NpcViewport.synchronizeNpcViewport(session.world, session.sceneNpcs,
                        session.activeNpcs, location.position(), NPC_RADIUS);
            } catch (RuntimeException e) {
                log.warn("session={} register scene NPC viewport: {}", session.machine.id(), e.getMessage());
            }
        }

        SessionException transition = session.machine.roleChosen();
        if (transition != null) {
            log.info("complete choose-role transition: {}", transition.getMessage());
            return false;
        }
        attachGmSnapshot(connection, session, location);
        startFacultyTicker(connection, session);
        log.info("session={} sent property/record tables, views, entry scene and player spawn; waiting for ClientReady",
                session.machine.id());
        return true;
    }

    private java.util.List<RoleInventoryStore.Skill> loadDbSkills(LoginSession session) {
        if (inventoryStore == null || session.selected == null
                || !inventoryStore.hasRoleData(session.selected.id())) {
            return null;
        }
        try {
            return inventoryStore.skills(session.selected.id(), "wuxue");
        } catch (RuntimeException e) {
            log.warn("session={} load db skills: {}", session.machine.id(), e.getMessage());
            return null;
        }
    }

    private java.util.List<RoleInventoryStore.Shortcut> loadDbShortcuts(LoginSession session) {
        if (inventoryStore == null || session.selected == null
                || !inventoryStore.hasRoleData(session.selected.id())) {
            return null;
        }
        try {
            return inventoryStore.shortcuts(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load db shortcuts: {}", session.machine.id(), e.getMessage());
            return null;
        }
    }

    private List<RoleInventoryStore.Equip> loadEquips(LoginSession session) {
        if (inventoryStore == null || session.selected == null
                || !inventoryStore.hasRoleData(session.selected.id())) {
            return List.of();
        }
        try {
            return inventoryStore.equips(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load equips: {}", session.machine.id(), e.getMessage());
            return List.of();
        }
    }

    /** 装备词缀（propBuilt）+ 武器特效（EffectPackID）整层重算并同步有效上限（幂等）。 */
    private void applyEquipBonuses(LoginSession session, List<RoleInventoryStore.Equip> equips) {
        if (session.player == null) {
            return;
        }
        List<String> propBuilts = new java.util.ArrayList<>(equips.size());
        List<Integer> effectPackIds = new java.util.ArrayList<>(equips.size());
        for (RoleInventoryStore.Equip equip : equips) {
            propBuilts.add(equip.propBuilt() == null ? "" : equip.propBuilt());
            EquipmentModels.ItemStatic stat = EquipmentModels.itemStatic(equip.configId());
            effectPackIds.add(stat == null ? 0 : stat.effectPackId());
        }
        session.player.recomputeEquipBonuses(propBuilts, effectPackIds);
    }

    /** 面板域块装载（称号/兵录/声望/坐骑；store 缺失或表空保持种子值）。 */
    private void loadPanelData(LoginSession session) {
        if (panelStore == null || session.player == null || session.selected == null) {
            return;
        }
        long roleId = session.selected.id();
        PanelState panel = session.player.panel();
        try {
            panel.wornTitleId = panelStore.wornTitle(roleId);
        } catch (RuntimeException e) {
            log.warn("session={} load worn title: {}", session.machine.id(), e.getMessage());
        }
        try {
            int max = 0;
            for (PanelDataStore.Binglu slot : panelStore.binglu(roleId)) {
                max = Math.max(max, slot.curLevel());
            }
            panel.maxBingLuLevel = max;
        } catch (RuntimeException e) {
            log.warn("session={} load binglu: {}", session.machine.id(), e.getMessage());
        }
        try {
            panel.prestigeExp = (int) Math.min(Integer.MAX_VALUE, panelStore.reputeTotal(roleId));
        } catch (RuntimeException e) {
            log.warn("session={} load repute: {}", session.machine.id(), e.getMessage());
        }
        try {
            panel.mount = panelStore.mount(roleId);
        } catch (RuntimeException e) {
            log.warn("session={} load mount: {}", session.machine.id(), e.getMessage());
        }
    }

    private java.util.List<RoleInventoryStore.Bagpack> loadBagpacks(LoginSession session) {
        if (inventoryStore == null) {
            return java.util.List.of();
        }
        try {
            return inventoryStore.bagpacks(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load bagpacks: {}", session.machine.id(), e.getMessage());
            return java.util.List.of();
        }
    }

    private void startFacultyTicker(Connection connection, LoginSession session) {
        if (scheduler == null) {
            return;
        }
        session.facultyTicker = scheduler.scheduleWithFixedDelay(() -> {
            try {
                drainGmCommands(connection, session);
                connection.writeFrame(timeSyncFrame(clock.instant()));
                facultyTickOnce(connection, session);
            } catch (RuntimeException e) {
                log.warn("faculty tick: {}", e.getMessage());
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 0x1E msg294 服务端时间广播（F 盘抓包 1s 一帧实证，连续帧 +1000/+1 递增）：
     * [服务器运行毫秒 i32, 时钟毫秒 i64, unix 秒 i64, 游戏日 f64]。
     * v1 对齐 F 的"服务器启动后 ms"口径（JVM uptime 截 i32，回绕无害）；v2 用挂钟毫秒
     * （F 为系统 uptime ms，语义未完全钉死，挂钟对客户端校时更有用）；v3 unix 秒；
     * v4 F 恒 46327.464（游戏日，语义未钉），先常数跟齐。
     */
    public static byte[] timeSyncFrame(Instant now) {
        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        return CustomMessages.customIntMessage(294,
                CustomMessages.Value.ofInt32((int) uptimeMs),
                CustomMessages.Value.ofInt64(now.toEpochMilli()),
                CustomMessages.Value.ofInt64(now.getEpochSecond()),
                CustomMessages.Value.ofFloat64(46327.464));
    }

    // -------------------------------------------------------------------- GM 队列

    /** Go 主循环 select 的 command 分支：ticker 顺带清空本会话 GM 队列（≤1s 延迟）。 */
    void drainGmCommands(Connection connection, LoginSession session) {
        GmCommand command;
        while ((command = gmHub.tryPop(session.machine.id())) != null) {
            try {
                applyGmCommand(connection, session, command);
            } catch (RuntimeException e) {
                command.fail("处理异常: " + e.getMessage());
                log.warn("session={} GM command {}: {}", session.machine.id(), command.action(), e.getMessage());
            }
        }
    }

    /** Go processGMCommand：按 action 分派；未进场景/未实现动作明确失败回执。 */
    private void applyGmCommand(Connection connection, LoginSession session, GmCommand command) {
        if (session.player == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        switch (command.action()) {
            case "set_move_speed" -> applyMotionValue(connection, session, command, true);
            case "set_gravity" -> applyMotionValue(connection, session, command, false);
            case "set_silver" -> applySilver(connection, session, command);
            case "set_guanyin" -> applyCapital(connection, session, command, "guanYin", "官银");
            case "set_gold" -> applyCapital(connection, session, command, "gold", "黄金");
            case "set_sp", "add_sp", "fill_sp" -> applySP(connection, session, command);
            // 四期面板实时动作（bak GmActions 同款：写面板字段 → 单帧 0x10 推送）。
            case "set_power_level" -> applyPowerLevel(connection, session, command);
            case "set_alignment" -> applyAlignment(connection, session, command);
            case "set_vitals" -> applyVitals(connection, session, command);
            case "wear_title" -> applyWearTitle(connection, session, command, true);
            case "cancel_title" -> applyWearTitle(connection, session, command, false);
            default -> {
                log.info("session={} unimplemented GM action {} (backlog)", session.machine.id(), command.action());
                command.fail("动作未实现: " + command.action());
            }
        }
    }

    /** Go main.go set_move_speed/set_gravity 分支：改 motion → 单帧 0x10 → lastAction 回显。 */
    private void applyMotionValue(Connection connection, LoginSession session,
                                  GmCommand command, boolean moveSpeed) {
        Float value = command.floatParam("value");
        if (value == null || value.isNaN() || value.isInfinite() || value < 0.1f || value > 100f) {
            command.fail("数值须在 0.1 至 100 之间");
            return;
        }
        String action;
        if (moveSpeed) {
            connection.writeFrame(session.player.setMoveSpeed(value));
            action = String.format(java.util.Locale.ROOT, "移动速度设为 %.2f", value);
        } else {
            connection.writeFrame(session.player.setGravity(value));
            action = String.format(java.util.Locale.ROOT, "下落重力设为 %.2f", value);
        }
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM {}: {}", session.machine.id(), command.action(), action);
    }

    /** Go set_silver 分支：setSilver + silverUpdate 单帧 + 快照/lastAction「碎银设为 %d」。 */
    private void applySilver(Connection connection, LoginSession session, GmCommand command) {
        Integer value = command.params().getInteger("silver");
        if (value == null || value < 0) {
            command.fail("碎银必须非负");
            return;
        }
        session.player.setSilver(value);
        connection.writeFrame(session.player.silverUpdate());
        String action = String.format(java.util.Locale.ROOT, "碎银设为 %d", value);
        gmHub.update(session.machine.id(), s -> {
            s.silver = value;
            s.lastAction = action;
        });
        command.succeed(action);
        log.info("session={} GM set CapitalType1={}", session.machine.id(), value);
    }

    /** menu2 exe 取证：官银=CapitalType2（官银设为 %d）、黄金=CapitalType0（黄金设为 %d）。 */
    private void applyCapital(Connection connection, LoginSession session, GmCommand command,
                              String paramKey, String label) {
        Integer value = command.params().getInteger(paramKey);
        if (value == null || value < 0) {
            command.fail(label + "必须非负");
            return;
        }
        byte[] frame = "guanYin".equals(paramKey)
                ? session.player.setGuanYin(value)
                : session.player.setGold(value);
        connection.writeFrame(frame);
        String action = String.format(java.util.Locale.ROOT, "%s设为 %d", label, value);
        java.util.function.Consumer<GmHub.PlayerSnapshot> field = "guanYin".equals(paramKey)
                ? s -> s.guanYin = value
                : s -> s.gold = value;
        gmHub.update(session.machine.id(), s -> {
            field.accept(s);
            s.lastAction = action;
        });
        command.succeed(action);
        log.info("session={} GM set CapitalType{}={} ({})", session.machine.id(),
                "guanYin".equals(paramKey) ? 2 : 0, value, label);
    }

    /** Go set_sp/add_sp/fill_sp 分支：饱和 0..100 + SP 单帧 + 快照/lastAction「怒气设为 %d/%d」。 */
    private void applySP(Connection connection, LoginSession session, GmCommand command) {
        PlayerActor.SPUpdate update;
        switch (command.action()) {
            case "set_sp" -> {
                Integer value = command.params().getInteger("sp");
                if (value == null) {
                    command.fail("missing sp");
                    return;
                }
                update = session.player.setSP(value);
            }
            case "add_sp" -> {
                Integer value = command.params().getInteger("sp");
                if (value == null) {
                    command.fail("missing sp");
                    return;
                }
                update = session.player.addSP(value);
            }
            default -> update = session.player.setSP(PlayerActor.MAX_PLAYER_SP); // fill_sp
        }
        connection.writeFrame(update.frame());
        int sp = update.sp();
        String action = String.format(java.util.Locale.ROOT, "怒气设为 %d/%d", sp, PlayerActor.MAX_PLAYER_SP);
        gmHub.update(session.machine.id(), s -> {
            s.sp = sp;
            s.lastAction = action;
        });
        command.succeed(action);
        log.info("session={} GM {} SP={}", session.machine.id(), command.action(), sp);
    }

    // -------------------------------------------------------- 四期面板 GM 动作

    /** menu2 setVitalsPowerLevel：实力等级 → PowerLevel + LevelTitle 档位单帧（面板参数名 value）。 */
    private void applyPowerLevel(Connection connection, LoginSession session, GmCommand command) {
        Integer stage = command.intParam("value");
        if (stage == null) {
            stage = command.intParam("stage");
        }
        if (stage == null || stage < 0) {
            command.fail("实力等级 value 必须非负整数");
            return;
        }
        PanelState panel = session.player.panel();
        panel.powerLevel = stage;
        List<PropertyValues.Indexed> properties = new java.util.ArrayList<>(2);
        Props.add(properties, "PowerLevel", PropertyValues.Value.ofInt32(stage));
        Props.add(properties, "LevelTitle", PropertyValues.Value.ofString(
                LoginMessages.roleListTitle(stage)));
        pushPanelProperties(connection, properties);
        String action = String.format(java.util.Locale.ROOT, "实力等级设为 %d，称号 %s",
                stage, LoginMessages.roleListTitle(stage));
        command.succeed(action);
        log.info("session={} GM set_power_level={}", session.machine.id(), stage);
    }

    /** menu2 setAlignment：侠/恶/基础狂邪（均可选，全缺=失败）。 */
    private void applyAlignment(Connection connection, LoginSession session, GmCommand command) {
        Integer justice = command.intParam("justice");
        Integer evil = command.intParam("evil");
        Integer freeBase = command.intParam("freeBase");
        if (justice == null && evil == null && freeBase == null) {
            command.fail("未提供 justice/evil/freeBase");
            return;
        }
        PanelState panel = session.player.panel();
        List<PropertyValues.Indexed> properties = new java.util.ArrayList<>(3);
        if (justice != null && justice >= 0) {
            panel.justice = justice;
            Props.add(properties, "Justice", PropertyValues.Value.ofInt32(justice));
        }
        if (evil != null && evil >= 0) {
            panel.evil = evil;
            Props.add(properties, "Evil", PropertyValues.Value.ofInt32(evil));
        }
        if (freeBase != null && freeBase >= 0) {
            panel.freeBase = freeBase;
            Props.add(properties, "FreeBase", PropertyValues.Value.ofInt32(freeBase));
        }
        if (properties.isEmpty()) {
            command.fail("justice/evil/freeBase 必须非负");
            return;
        }
        pushPanelProperties(connection, properties);
        command.succeed(String.format(java.util.Locale.ROOT, "侠=%d 恶=%d 狂邪=%d 已更新",
                panel.justice, panel.evil, panel.freeBase));
        log.info("session={} GM set_alignment j={} e={} f={}", session.machine.id(),
                panel.justice, panel.evil, panel.freeBase);
    }

    /** menu2 setVitals：体力/饱食/杀戮/酒量族（均可选；本服表无 maxEneAdd/maxSatAdd 忽略）。 */
    private void applyVitals(Connection connection, LoginSession session, GmCommand command) {
        PanelState panel = session.player.panel();
        List<PropertyValues.Indexed> properties = new java.util.ArrayList<>(7);
        applyVital(properties, command, "ene", v -> panel.ene = v, "Ene");
        applyVital(properties, command, "maxEne", v -> panel.maxEne = v, "MaxEne");
        applyVital(properties, command, "resumeEne", v -> panel.resumeEne = v, "ResumeEne");
        applyVital(properties, command, "sat", v -> panel.sat = v, "Sat");
        applyVital(properties, command, "maxSat", v -> panel.maxSat = v, "MaxSat");
        applyVital(properties, command, "drunkLevel", v -> panel.drunkLevel = v, "DrunkLevel");
        applyVital(properties, command, "pkValue", v -> panel.pkValue = v, "PKValue");
        if (properties.isEmpty()) {
            command.fail("未提供 ene/sat/pkValue/drunkLevel 等参数");
            return;
        }
        pushPanelProperties(connection, properties);
        command.succeed("生存属性已更新 " + properties.size() + " 项");
        log.info("session={} GM set_vitals {} items", session.machine.id(), properties.size());
    }

    private interface VitalSetter {
        void set(int value);
    }

    private static void applyVital(List<PropertyValues.Indexed> properties, GmCommand command,
                                   String param, VitalSetter setter, String propName) {
        Integer value = command.intParam(param);
        if (value == null || value < 0) {
            return;
        }
        setter.set(value);
        Props.add(properties, propName, PropertyValues.Value.ofInt32(value));
    }

    /** 称号佩戴/取消：DB（store 可用时校验拥有并事务写）+ TitleID 单帧。 */
    private void applyWearTitle(Connection connection, LoginSession session,
                                GmCommand command, boolean wear) {
        PanelState panel = session.player.panel();
        if (!wear) {
            if (panelStore != null && session.selected != null) {
                try {
                    panelStore.cancelTitle(session.selected.id());
                } catch (RuntimeException e) {
                    log.warn("session={} cancel title db: {}", session.machine.id(), e.getMessage());
                }
            }
            panel.wornTitleId = 0;
            pushTitleId(connection, 0);
            command.succeed("已取消佩戴称号");
            return;
        }
        Integer titleId = command.intParam("titleId");
        if (titleId == null || titleId <= 0) {
            command.fail("titleId 必须正整数");
            return;
        }
        if (panelStore != null && session.selected != null) {
            try {
                if (!panelStore.wearTitle(session.selected.id(), titleId)) {
                    command.fail("未拥有称号 " + titleId);
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("session={} wear title db: {}", session.machine.id(), e.getMessage());
            }
        }
        panel.wornTitleId = titleId;
        pushTitleId(connection, titleId);
        command.succeed("称号 " + titleId + " 佩戴成功");
        log.info("session={} GM wear_title={}", session.machine.id(), titleId);
    }

    private void pushTitleId(Connection connection, int titleId) {
        List<PropertyValues.Indexed> properties = new java.util.ArrayList<>(1);
        Props.add(properties, "TitleID", PropertyValues.Value.ofInt32(titleId));
        pushPanelProperties(connection, properties);
    }

    /** 面板增量属性单帧（0x10 全量对象头 + 少量键）。 */
    private void pushPanelProperties(Connection connection, List<PropertyValues.Indexed> properties) {
        connection.writeFrame(SceneMessages.objectProperties(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, 0, properties));
    }

    /** Go main.go gm.attach：选角完成即挂面板会话快照。 */
    private void attachGmSnapshot(Connection connection, LoginSession session, Roles.Location location) {
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.remote = String.valueOf(connection.channel().remoteAddress());
        snapshot.name = session.selected.name();
        snapshot.sceneConfig = location.scene().config();
        snapshot.sceneResource = location.scene().resource();
        snapshot.x = location.position().x();
        snapshot.y = location.position().y();
        snapshot.z = location.position().z();
        snapshot.orient = location.position().orient();
        snapshot.silver = session.player.silver();
        snapshot.sp = session.player.sp();
        snapshot.guanYin = session.player.guanYin();
        snapshot.gold = session.player.gold();
        snapshot.faction = session.player.faction();
        gmHub.attach(snapshot.sessionId, snapshot);
    }

    /** 连接断开（含校验失败断连）：Go defer（pendingLocation/persistFaculty/world.close）+ gm.detach。 */
    public void onDisconnect(LoginSession session) {
        if (session.pendingLocation != null && session.selected != null) {
            try {
                session.selected = roleStore.saveLocation(session.selected, session.pendingLocation);
            } catch (RuntimeException e) {
                log.warn("persist pending movement: {}", e.getMessage());
            }
        }
        if (session.selected != null && session.player != null) {
            try {
                facultyStore.save(session.selected.id(), session.player.facultySnapshot());
            } catch (RuntimeException e) {
                log.warn("persist faculty progress: {}", e.getMessage());
            }
        }
        if (session.facultyTicker != null) {
            session.facultyTicker.cancel(false);
        }
        if (session.world != null) {
            session.world.close();
        }
        gmHub.detach(session.machine.id());
    }

    /**
     * Go main.go 显式 stage_main 0x09 分支：starter 链（轻功/打坐/内功 View43/
     * 技能 View40/快捷栏）→ 0x27 开关 → 重复就绪守卫 → world.clientReady（无帧）
     * → Ready 相位 → 终帧 vital 同步。注意 grants 在 machine.ready() 之前。
     */
    private boolean onClientReady(Connection connection, LoginSession session) {
        if (session.player != null && !session.qingGongGranted) {
            StarterGrants.grantStarterQingGong(connection);
            session.qingGongGranted = true;
            log.info("session={} granted {} QingGongRec rows and {} View=46 items after explicit ClientReady",
                    session.machine.id(), StarterQingGong.STARTER_QING_GONG_IDS.size(),
                    StarterQingGong.STARTER_QING_GONG_IDS.size() + StarterQingGong.STARTER_QING_GONG_SKILL_IDS.size());
        }
        if (session.player != null) {
            boolean granted = StarterGrants.grantSitcrossShortcut(connection, session.player);
            if (granted) {
                log.info("session={} granted native sitcross shortcut index={}",
                        session.machine.id(), StarterGrants.SITCROSS_SHORTCUT_INDEX);
            }
        }
        if (session.player != null && !session.neigongGranted) {
            StarterGrants.grantStarterProgress(connection, session.player,
                    loadDbSkills(session), loadDbShortcuts(session));
            session.neigongGranted = true;
            log.info("session={} granted View=43 inner-power and View=40 starter skills after explicit ClientReady",
                    session.machine.id());
        }
        if (session.player != null) {
            // 重复 0x09（活动场景内的后期 UI 回调）也会重发开关，Go 同此。
            StarterGrants.enableSkillActionSwitch(connection);
        }
        if (session.machine.phase() == SessionMachine.Phase.IN_WORLD) {
            log.info("session={} ignored duplicate ClientReady in active scene", session.machine.id());
            return true;
        }
        // Go world.clientReady()：armed 置位（RegisterCallBack 之前的窗口不发对象）。
        if (session.world != null) {
            session.world.clientReady();
        }
        SessionException transition = session.machine.ready();
        if (transition != null) {
            log.info("complete client-ready transition: {}", transition.getMessage());
            return false;
        }
        if (session.player != null) {
            connection.writeFrame(session.player.vitalUpdate());
        }
        return true;
    }

    /**
     * Go 0x0A 分支：相位 ENTERING_WORLD 时按兼容就绪屏障提升（客户端省略 0x09
     * 直发 0x0A：Ready + vital + 0x27 开关，不发 starter 链）；随后物化 NPC 视口
     * （armed 一次）+ 启动巡逻，进 Activity 相位，再处理 67 字节位置更新包。
     */
    private boolean onClientActivity(Connection connection, LoginSession session, byte[] plain) {
        if (session.machine.phase() == SessionMachine.Phase.ENTERING_WORLD) {
            if (session.world != null) {
                session.world.clientReady();
            }
            SessionException readyTransition = session.machine.ready();
            if (readyTransition != null) {
                log.info("complete compatibility ready transition: {}", readyTransition.getMessage());
                return false;
            }
            if (session.player != null) {
                connection.writeFrame(session.player.vitalUpdate());
                StarterGrants.enableSkillActionSwitch(connection);
            }
            log.info("session={} promoted observed 0x0A to compatibility ClientReady", session.machine.id());
        }
        if (session.world != null) {
            try {
                session.world.clientActivity();
            } catch (RuntimeException e) {
                log.warn("session={} activate ready scene objects: {}", session.machine.id(), e.getMessage());
                return false;
            }
            session.world.startPatrol(NPC_PATROL_COUNT);
        }
        SessionException transition = session.machine.activity();
        if (transition != null) {
            log.info("complete client-activity transition: {}", transition.getMessage());
            return false;
        }
        handleMotionPosition(session, plain);
        handleActivityCustom(connection, session, plain);
        return true;
    }

    /** Go 0x0A 信封 CustomSend：21 字节头后 TVarList；白名单外/解析失败静默放行。 */
    private void handleActivityCustom(Connection connection, LoginSession session, byte[] plain) {
        CustomC2S.Message custom = CustomC2S.parseActivityEnvelope(plain);
        if (custom == null) {
            return;
        }
        dispatchCustom(connection, session, custom, "activity-envelope");
    }

    /** Go 0x1E/0x27 直达自定义消息路由。 */
    private boolean onCustomDirect(Connection connection, LoginSession session, byte[] plain) {
        CustomC2S.Message custom = CustomC2S.parseDirect(plain);
        if (custom == null) {
            log.info("session={} tolerated unparsable direct custom frame len={}",
                    session.machine.id(), plain.length);
            return true;
        }
        dispatchCustom(connection, session, custom, "direct");
        return true;
    }

    /** Go 白名单 {200,201,240,211,215,216,217,218,1006}；本期实现 215/1006，其余受理记日志。 */
    private void dispatchCustom(Connection connection, LoginSession session,
                                CustomC2S.Message custom, String route) {
        int messageId = custom.messageId();
        switch (messageId) {
            case 215, 1006 -> {
                try {
                    FacultyCustomHandler.handle(connection, session.player, custom);
                } catch (RuntimeException e) {
                    log.info("session={} reject custom {} via {}: {}",
                            session.machine.id(), messageId, route, e.getMessage());
                    return;
                }
                persistFaculty(session);
            }
            case 200, 201, 240, 211, 216, 217, 218 ->
                    log.info("session={} unimplemented custom msgId={} via {} (phase-4 backlog)",
                            session.machine.id(), messageId, route);
            case 54 -> handleManualParry(connection, session, custom);
            default -> log.info("session={} unrecognized custom msgId={} via {}",
                    session.machine.id(), messageId, route);
        }
    }

    /**
     * 54 右键格挡（F 盘抓包 + 老分支 handleManualParry，2026-10-31）：int64 参数
     * 0=按下、1=松开，按值幂等落态；状态未变（客户端 500ms 重发、松开连发 2-3 个 1）
     * 直接吞掉不回帧——状态变化才回两帧，这是客户端格挡动画的触发条件：
     * ① 0x1E msg169 BuffInParry 九值帧；② 0x10 InParry/InManualParry + BufferInfo11。
     * 游离 release（服务端本就非格挡态）必须回一帧确认：跨服/重连客户端带着旧会话
     * "已格挡"认知时只发 1 等确认、永不再发按下(0)——老分支 2026-10-31 晚实抓
     * 141 个 release/0 个 engage 的死锁根因。F 盘实证 78 发按值去重=50 沿=50 回帧。
     */
    private void handleManualParry(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null) {
            log.info("manual parry before scene player exists");
            return;
        }
        if (custom.values.size() < 2) {
            log.info("manual parry arg missing, keep parrying={}", session.player.parrying());
            return;
        }
        CustomC2S.Value state = custom.values.get(1);
        if (state == null || state.exactInt32() != 0 && state.exactInt32() != 1) {
            log.info("manual parry arg invalid, keep parrying={}", session.player.parrying());
            return;
        }
        PlayerActor actor = session.player;
        boolean engage = state.exactInt32() == 0;
        long now = clock.instant().toEpochMilli();
        if (actor.parrying() == engage) {
            if (!engage) {
                sendParryStateFrames(connection, actor, false, "", clock.instant());
                log.info("ack stray manual parry release (client state resync)");
            }
            return;
        }
        Instant nowInstant = clock.instant();
        String buffInfo = engage ? actor.engageParryBuff(nowInstant) : actor.releaseParryBuff();
        sendParryStateFrames(connection, actor, engage, buffInfo, nowInstant);
        log.info("publish manual parry state={} (arg={})", actor.parrying(), state.exactInt32());
    }

    /**
     * 格挡状态两帧：① 0x1E msg169 BuffInParry（end 标志 + 窄串 + 双 ObjRef + 双宽串
     * 角色名 + int64 毫秒 + 0，F 盘抓包 84B 九值帧逐字段对齐）② 0x10 属性帧
     * InParry/InManualParry + BufferInfo11。
     */
    private void sendParryStateFrames(Connection connection, PlayerActor actor,
                                      boolean engage, String buffInfo, Instant now) {
        String name = actor.snapshotName();
        connection.writeFrame(CustomMessages.customIntMessage(169,
                CustomMessages.Value.ofInt32(engage ? 0 : 1),
                CustomMessages.Value.ofString("BuffInParry"),
                CustomMessages.Value.ofObject(PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID),
                CustomMessages.Value.ofObject(PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID),
                CustomMessages.Value.ofWideString(name),
                CustomMessages.Value.ofWideString(name),
                CustomMessages.Value.ofInt64(now.toEpochMilli()),
                CustomMessages.Value.ofInt32(0)));
        connection.writeFrame(actor.parryStateFrame(engage, buffInfo));
    }

    /** Go 1s ticker：整分钟结算变化时 [0x18 书帧, 0x10 vital] + 持久化；静默秒零帧。 */
    void facultyTickOnce(Connection connection, LoginSession session) {
        if (session.player == null || session.selected == null) {
            return;
        }
        int[] tick = session.player.facultyTick(clock.instant());
        if (tick[1] == 0) {
            return;
        }
        if (tick[0] > 0) {
            connection.writeFrame(session.player.progress().bookFrame(tick[0]));
        }
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
    }

    /** Go persistFaculty 闭包。 */
    private void persistFaculty(LoginSession session) {
        if (session.selected == null || session.player == null) {
            return;
        }
        try {
            facultyStore.save(session.selected.id(), session.player.facultySnapshot());
        } catch (RuntimeException e) {
            log.warn("persist faculty progress: {}", e.getMessage());
        }
    }

    /** Go 0x0A motion 段：视口跟随 + 2s 节流位置保存（越界/长包不落盘）。 */
    private void handleMotionPosition(LoginSession session, byte[] plain) {
        if (session.selected == null || session.world == null) {
            return;
        }
        float[] position = parseMotionPosition(plain);
        if (position == null) {
            return;
        }
        Roles.Location location = new Roles.Location(session.selected.location().scene(),
                new Roles.Position(position[0], position[1], position[2], position[3]),
                session.selected.location().version());
        boolean changed = false;
        try {
            changed = NpcViewport.synchronizeNpcViewport(session.world, session.sceneNpcs,
                    session.activeNpcs, location.position(), NPC_RADIUS);
            if (changed) {
                session.world.reconcileViewport();
            }
        } catch (RuntimeException e) {
            log.warn("session={} synchronize moving NPC viewport: {}", session.machine.id(), e.getMessage());
        }
        session.pendingLocation = location;
        Instant now = clock.instant();
        if (session.lastPositionSave == null
                || Duration.between(session.lastPositionSave, now).compareTo(POSITION_SAVE_INTERVAL) >= 0) {
            savePosition(session, location, now);
        }
    }

    private void savePosition(LoginSession session, Roles.Location location, Instant now) {
        try {
            session.selected = roleStore.saveLocation(session.selected, location);
            session.lastPositionSave = now;
            session.pendingLocation = null;
        } catch (RuntimeException e) {
            log.warn("persist movement: {}", e.getMessage());
        }
    }

    /** Go motion.go parseMotionPosition：67 字节包 0x26 起四段 (tag=4,float)。 */
    static float[] parseMotionPosition(byte[] plain) {
        if (plain.length != 67) {
            return null;
        }
        int offset = 0x26;
        if (plain[offset] != 4 || plain[offset + 5] != 4
                || plain[offset + 10] != 4 || plain[offset + 15] != 4) {
            return null;
        }
        float[] values = new float[4];
        for (int i = 0; i < 4; i++) {
            int bits = (plain[offset + 1 + i * 5] & 0xFF)
                    | (plain[offset + 2 + i * 5] & 0xFF) << 8
                    | (plain[offset + 3 + i * 5] & 0xFF) << 16
                    | (plain[offset + 4 + i * 5] & 0xFF) << 24;
            values[i] = Float.intBitsToFloat(bits);
        }
        for (float value : values) {
            if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
                return null;
            }
        }
        if (Math.abs(values[0]) > 10000 || Math.abs(values[1]) > 3000
                || Math.abs(values[2]) > 10000 || Math.abs(values[3]) > 20) {
            return null;
        }
        return values;
    }

    /** Go scene_catalog_registry.go normalizeClientScene 的三条地图特例。 */
    static Roles.Scene normalizeClientScene(Roles.Scene scene) {
        if (scene.config().equalsIgnoreCase("ini\\scene\\scene09_SaiBeiCaoYuan")) {
            scene = new Roles.Scene(scene.config(), "scene09");
        }
        if (scene.config().equalsIgnoreCase("ini\\scene\\scene07_MeiHuaLing")) {
            scene = new Roles.Scene(scene.config(), "scene07");
        }
        if (scene.resource().equals("clone004_wl_1") || scene.config().equals("ini\\scene\\clone004_wl_1")) {
            scene = new Roles.Scene("ini\\scene\\clone004_wl", "clone004_wl");
        }
        return scene;
    }
}
