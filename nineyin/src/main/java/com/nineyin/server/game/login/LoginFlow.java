package com.nineyin.server.game.login;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import com.nineyin.server.game.avatar.appearance.AppearanceSync;
import com.nineyin.server.game.avatar.skin.SkinMessages;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.game.avatar.equipment.ToolItemCatalog;
import com.nineyin.server.protocol.EquipmentModels;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.world.fight.SkillCastHandler;
import com.nineyin.server.world.npc.SceneNpcRegistry;
import com.nineyin.server.world.scene.NpcViewport;
import com.nineyin.server.world.scene.SceneLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.neigong.FacultyCustomHandler;
import com.nineyin.server.game.avatar.panel.PanelState;
import com.nineyin.server.game.starter.StarterGrants;
import com.nineyin.server.game.starter.StarterSkills;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.server.world.db.PanelDataStore;
import com.nineyin.server.game.starter.StarterQingGong;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.protocol.CustomMessages;
import com.nineyin.server.protocol.FightMessages;
import com.nineyin.server.protocol.LoginMessages;
import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.protocol.RecordSchemas;
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
    /** Go -npc-radius 默认 120（巡逻已换 mappath 官方路线，无数量上限）。 */
    static final float NPC_RADIUS = 120.0f;
    /** Go 位置保存节流（≥2s）。 */
    static final Duration POSITION_SAVE_INTERVAL = Duration.ofSeconds(2);

    private final RoleStoreFacade roleStore;
    private final FacultyStore facultyStore;
    /** 经脉修炼存档（data/meridians.json 主档；DB 经 {@link #attachMeridianDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.jingmai.MeridianStore meridianStore =
            com.nineyin.server.game.avatar.jingmai.MeridianStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "meridians.json"));
    /** 个性配置存档（data/customizing.json 主档；DB 经 {@link #attachCustomizingDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.customizing.CustomizingStore customizingStore =
            com.nineyin.server.game.avatar.customizing.CustomizingStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "customizing.json"));
    /** 风物志皮肤存档（data/skins.json 主档；DB 经 {@link #attachSkinDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.skin.SkinStore skinStore =
            com.nineyin.server.game.avatar.skin.SkinStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "skins.json"));
    private final GmHub gmHub;
    private final Clock clock;
    private final SceneNpcRegistry npcRegistry;
    private final ScheduledExecutorService scheduler;
    /** 三期物品域 DB 源（可空=回退 starter 行为）。 */
    private final RoleInventoryStore inventoryStore;
    /** 四期面板域 DB 源（称号/兵录/声望/坐骑；可空=回退种子值）。 */
    private final PanelDataStore panelStore;
    /** 在线会话注册表（选角入世界 attachGmSnapshot 时登记）：优雅停机逐会话落盘依据。 */
    private final java.util.Set<LoginSession> activeSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /** 经脉存档 DB 双源挂接（role_meridians 快照；失败仅告警，JSON 仍为主档）。 */
    public void attachMeridianDatabase(String dsn) {
        try {
            meridianStore.attachDatabase(dsn);
            log.info("meridian store attached to MySQL role_meridians");
        } catch (RuntimeException e) {
            log.warn("meridian store MySQL unavailable, JSON only: {}", e.getMessage());
        }
    }

    /** 个性配置存档 DB 双源挂接（role_customizing；失败仅告警，JSON 仍为主档）。 */
    public void attachCustomizingDatabase(String dsn) {
        try {
            customizingStore.attachDatabase(dsn);
            log.info("customizing store attached to MySQL role_customizing");
        } catch (RuntimeException e) {
            log.warn("customizing store MySQL unavailable, JSON only: {}", e.getMessage());
        }
    }

    /** 风物志皮肤存档 DB 双源挂接（role_skins；失败仅告警，JSON 仍为主档）。 */
    public void attachSkinDatabase(String dsn) {
        try {
            skinStore.attachDatabase(dsn);
            log.info("skin store attached to MySQL role_skins");
        } catch (RuntimeException e) {
            log.warn("skin store MySQL unavailable, JSON only: {}", e.getMessage());
        }
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
            case 0x07 -> onObjectRequest(connection, session, plain);
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
        // 经脉修炼存档（恢复失败/无档按 14 条初始经脉落档）。
        var savedMeridian = meridianStore.load(session.selected.id());
        session.player.restoreMeridian(savedMeridian);
        if (savedMeridian == null) {
            try {
                meridianStore.save(session.selected.id(), session.player.meridianSnapshot());
            } catch (RuntimeException e) {
                log.warn("seed meridian store: {}", e.getMessage());
            }
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
        // 战斗一期：战斗态随场景世代重建（NPC 每连接物化，血量/威胁从零开始）。
        session.fight = new com.nineyin.server.world.fight.FightState();
        connection.writeFrame(SceneMessages.entryScene(location.scene()));
        // 四期装备词缀/特效层（DB 模式）：addObject 归档前重算，登录帧即带装备加成面板。
        var equips = loadEquips(session);
        // V2 装备外观挂载：已穿衣/裤/鞋装备的模型覆盖创角初始装——重登不再回落
        // 「初始屌丝装」；帽/时装镜像通道在 addObject 后补帧（pushEquipAppearance）。
        session.visual = AppearanceSync.mergeVisual(
                session.visual, session.visual.sex(), equips, EquipmentResolver.loadDefault());
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
        // V2 装备外观挂载（DB 模式）：帽/时装镜像通道 0x10 补帧——addObject 的 Hat 位
        // 是发型别名（F 抓包方言），真帽子与时装覆盖走属性帧；无帽/时装不发（帧序对齐 golden）。
        pushEquipAppearance(connection, session, equips);
        // V3 风物志：装载角色皮肤存档（ClientReady 后推送，图鉴/挂载状态恢复）。
        session.skinState = skinStore.load(session.selected.id());
        // 时装页签：已激活套装装载（role_fashion_suits；ClientReady 推 FashionSuitRec 行）。
        if (inventoryStore != null) {
            try {
                session.fashionSuits = inventoryStore.fashionSuits(session.selected.id());
            } catch (RuntimeException e) {
                log.warn("session={} load fashion suits: {}", session.machine.id(), e.getMessage());
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

    /** 键位主串（role_keybinds.bind_value）；库缺席/无行回 null（客户端保持默认 QWES）。 */
    private String loadKeybind(LoginSession session) {
        if (inventoryStore == null || session.selected == null) {
            return null;
        }
        try {
            return inventoryStore.keybind(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load keybind: {}", session.machine.id(), e.getMessage());
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
                // F 抓包同拍：294 后随轻功点四元组（QingGongPoint/GJ 三项，61579 #2192）。
                connection.writeFrame(session.player.qingGongHeartbeatFrame());
                facultyTickOnce(connection, session);
                meridianTickOnce(connection, session);
                com.nineyin.server.world.fight.QingGongHandler.glideTick(connection, session.player);
            } catch (RuntimeException e) {
                log.warn("faculty tick: {}", e.getMessage());
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 0x1E msg294 服务端时间广播（F 盘抓包 1s 一帧实证，连续帧 +1000/+1 递增）：
     * [服务器运行毫秒 i32, 时钟毫秒 i64, unix 秒 i64, 游戏日 f64]。
     * v1 对齐 F 的"服务器启动后 ms"口径（JVM uptime 截 i32，回绕无害）；
     * v2 = 1601-01-01 UTC 纪元毫秒（Windows FILETIME 基准 ÷10⁴）——52795 抓包实证：
     * 首帧 v2=13434237233402 恰为 v3=1789763633 的 unix ms + 11644473600000，
     * 此前误发 unix 毫秒（口径差 1.16e13ms）；v3 unix 秒；v4 F 恒 46327.464
     * （游戏日，语义未钉），先常数跟齐。
     */
    public static byte[] timeSyncFrame(Instant now) {
        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        return CustomMessages.customIntMessage(294,
                CustomMessages.Value.ofInt32((int) uptimeMs),
                CustomMessages.Value.ofInt64(now.toEpochMilli() + WINDOWS_EPOCH_TO_UNIX_MS),
                CustomMessages.Value.ofInt64(now.getEpochSecond()),
                CustomMessages.Value.ofFloat64(46327.464));
    }

    /** 1601-01-01 UTC → 1970-01-01 UTC 的毫秒差（msg294 v2 口径）。 */
    public static final long WINDOWS_EPOCH_TO_UNIX_MS = 11644473600000L;

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
        if ("reload_scripts".equals(command.action())) {
            com.nineyin.server.script.LuaScriptService.getInstance().reload();
            command.succeed("Lua 脚本已重载（formula/skill overlay）");
            return;
        }
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
            // 经脉：每日真气硬闸（是否开启 + 每日阈值，角色级持久化）。
            case "set_zhenqi_cap" -> applyZhenqiCap(connection, session, command);
            // 修炼：修为总量（Faculty）+ 修速基础值×倍率 + 当前内功本层修炼度填满。
            case "set_faculty" -> applyFacultyValue(connection, session, command);
            case "set_fill_speed" -> applyFillSpeed(connection, session, command);
            case "fill_current_neigong" -> applyFillNeigong(connection, session, command);
            // 历练层：发历练/潜能修为直设/转化速度加成（LiveGroove/HoldFaculty/LiveSpeedPower）。
            case "add_livepoint" -> applyAddLivepoint(connection, session, command);
            case "set_hold_faculty" -> applyHoldFaculty(connection, session, command);
            case "set_live_speed_power" -> applyLiveSpeedPower(connection, session, command);
            // 演武碎银额度（GM 可设上限或不限制）+ 团练疲劳（清除/剩余抵扣直设）。
            case "set_act_day_limit" -> applyActDayLimit(connection, session, command);
            case "clear_team_fatigue" -> applyClearTeamFatigue(connection, session, command);
            case "set_team_faculty_last" -> applyTeamFacultyLast(connection, session, command);
            // V2 装备外观挂载：物品发放（立即穿戴=纸娃娃行+外观帧+词缀重算；否则入背包）。
            case "grant_item", "add_item" -> applyGrantItem(connection, session, command);
            // V3 风物志：皮肤收集/挂载/卸下（GM 网页直接驱动，不用点背包收集）。
            case "equip_card" -> applyMountSkin(connection, session, command);
            case "unequip_card" -> applyUnmountSkins(connection, session, command);
            case "grant_all_cards" -> applyGrantAllSkins(connection, session, command);
            // 配套：模板生成武器/防具（随机词条）+ 一键激活全部时装。
            case "gm_generate_weapon" -> applyGenerateWeapon(connection, session, command);
            case "gm_generate_armor" -> applyGenerateArmor(connection, session, command);
            case "grant_all_fashion" -> applyGrantAllFashion(connection, session, command);
            default -> {
                log.info("session={} unimplemented GM action {} (backlog)", session.machine.id(), command.action());
                command.fail("动作未实现: " + command.action());
            }
        }
    }

    /**
     * 经脉每日真气硬闸（set_zhenqi_cap）：enabled∈{0,1} + value(1..10⁷)；
     * 开启后当日 ZQPLValue 达阈值即拦截入账，ZhenQiDayValue 属性随开关显示阈值；
     * 角色级设置随 meridians.json/role_meridians 持久化，重登沿用。
     */
    private void applyZhenqiCap(Connection connection, LoginSession session, GmCommand command) {
        Integer enabled = command.intParam("enabled");
        Integer value = command.intParam("value");
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            command.fail("是否开启 enabled 必须为 0/1");
            return;
        }
        if (value == null || value < 1 || value > 10_000_000) {
            command.fail("每日阈值 value 必须在 1 至 10000000 之间");
            return;
        }
        var meridian = session.player.meridian();
        meridian.setDailyCap(enabled == 1, value);
        connection.writeFrame(
                com.nineyin.server.game.avatar.jingmai.JingMaiMessages.playerPropsFrame(meridian));
        persistMeridian(session);
        String action = String.format(java.util.Locale.ROOT, "每日真气硬闸已%s，阈值 %d（今日已获 %d）",
                enabled == 1 ? "开启" : "关闭", meridian.capValue(), meridian.plValue());
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_zhenqi_cap enabled={} value={}",
                session.machine.id(), enabled, value);
    }

    /** GM set_faculty：修炼点余额（Faculty@798，内修按分钟折半消耗）。改值 → 0x10 全量 → 快照落库。 */
    private void applyFacultyValue(Connection connection, LoginSession session, GmCommand command) {
        Integer value = command.intParam("value");
        if (value == null || value < 0) {
            command.fail("修为必须非负");
            return;
        }
        var progress = session.player.progress();
        progress.setFaculty(value);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "修为设为 %d", value);
        gmHub.update(session.machine.id(), s -> {
            s.faculty = value;
            s.lastAction = action;
        });
        command.succeed(action);
        log.info("session={} GM set Faculty={}", session.machine.id(), value);
    }

    /**
     * GM set_fill_speed：修速拆分 基础值×倍率。FillSpeed@773 下发实际值、NorFacultyAdd@774
     * 下发基础值（同帧）；普通内修态即时生效，停修/演武态下次开修生效；随 faculty 快照持久化。
     */
    private void applyFillSpeed(Connection connection, LoginSession session, GmCommand command) {
        Integer base = command.intParam("base");
        Float multiplier = command.floatParam("multiplier");
        if (base == null || base < 1) {
            command.fail("基础值 base 必须为正整数");
            return;
        }
        if (multiplier == null || multiplier.isNaN() || multiplier.isInfinite()
                || multiplier < 0.1f || multiplier > 100f) {
            command.fail("倍率 multiplier 必须在 0.1 至 100 之间");
            return;
        }
        var progress = session.player.progress();
        int fillSpeed = progress.setFillSpeed(base, multiplier);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "修炼速度设为 基础 %d × %.2f = %d/min",
                base, multiplier, fillSpeed);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_fill_speed base={} multiplier={} -> {}",
                session.machine.id(), base, multiplier, fillSpeed);
    }

    /**
     * GM add_livepoint：发 N 点历练（×1000 毫按序填槽，槽满溢出丢弃）。改值 →
     * 0x10 全量（LiveGroove_1..5 随帧）→ 快照落库。
     */
    private void applyAddLivepoint(Connection connection, LoginSession session, GmCommand command) {
        Integer points = command.intParam("points");
        if (points == null || points < 1 || points > 999) {
            command.fail("历练点数 points 必须在 1 至 999 之间");
            return;
        }
        var progress = session.player.progress();
        int added = progress.addLivepoint(points);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "发放历练 %d 点（入槽 %d 毫历练）",
                points, added);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM add_livepoint points={} addedMillis={}",
                session.machine.id(), points, added);
    }

    /** GM set_hold_faculty：潜能修为直设（HoldFaculty@800；0x10 全量随帧 + 快照落库）。 */
    private void applyHoldFaculty(Connection connection, LoginSession session, GmCommand command) {
        Integer value = command.intParam("value");
        if (value == null || value < 0) {
            command.fail("潜能修为必须非负");
            return;
        }
        var progress = session.player.progress();
        progress.setHoldFaculty(value);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "潜能修为设为 %d", value);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_hold_faculty value={}", session.machine.id(), value);
    }

    /** GM set_live_speed_power：历练转化速度加成 %（LiveSpeedPower@809，卦象效果等价物）。 */
    private void applyLiveSpeedPower(Connection connection, LoginSession session, GmCommand command) {
        Integer value = command.intParam("value");
        if (value == null || value < 0 || value > 1000) {
            command.fail("转化加成 value 必须在 0 至 1000 之间");
            return;
        }
        var progress = session.player.progress();
        progress.setLiveSpeedPower(value);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "历练转化加成设为 %d%%", value);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_live_speed_power value={}", session.machine.id(), value);
    }

    /**
     * GM set_act_day_limit：演武每日碎银上限（两）。≤0 = 不限制（ActDayValueMax=0，
     * 客户端演武页两标签显示"不限制"）；>0 = 有限额（超限打卡静默拒绝）。
     * 重设即重置今日已耗/已用（ActCostSuiYin/ActDayValueUsed 归零）。
     */
    private void applyActDayLimit(Connection connection, LoginSession session, GmCommand command) {
        Integer liang = command.intParam("value");
        if (liang == null || liang < 0 || liang > 999_999) {
            command.fail("每日上限 value 必须在 0 至 999999 两之间（0 = 不限制）");
            return;
        }
        var progress = session.player.progress();
        int applied = progress.setActDayLimit(liang);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = applied > 0
                ? String.format(java.util.Locale.ROOT, "演武每日碎银上限设为 %d 两", applied)
                : "演武碎银改为不限制";
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_act_day_limit liang={} appliedMillis={}",
                session.machine.id(), liang, progress.actDayValueMax());
    }

    /** GM clear_team_fatigue：清团练疲劳（TeamFacultyValue=0，客户端剩余精力恢复 100%）。 */
    private void applyClearTeamFatigue(Connection connection, LoginSession session, GmCommand command) {
        var progress = session.player.progress();
        int wasValue = progress.teamFacultyValue();
        progress.clearTeamFacultyFatigue();
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "团练疲劳已清零（原消耗值 %d/80000）", wasValue);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM clear_team_fatigue was={}", session.machine.id(), wasValue);
    }

    /** GM set_team_faculty_last：团练剩余可抵扣修炼度直设（TeamFacultyLast@778）。 */
    private void applyTeamFacultyLast(Connection connection, LoginSession session, GmCommand command) {
        Integer value = command.intParam("value");
        if (value == null || value < 0) {
            command.fail("剩余抵扣修炼度必须非负");
            return;
        }
        var progress = session.player.progress();
        progress.setTeamFacultyLast(value);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT, "团练剩余抵扣修炼度设为 %d", value);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_team_faculty_last value={}", session.machine.id(), value);
    }

    /**
     * GM fill_current_neigong：当前修炼书本层修炼度填满并结算升级（帧序对齐演武卡
     * actPay：0x18 书帧先行 → 0x10 全量 → 升级时 191/14）；恰达满级对齐自然修炼停修。
     */
    private void applyFillNeigong(Connection connection, LoginSession session, GmCommand command) {
        var progress = session.player.progress();
        int[] award = progress.fillCurrentFaculty();
        if (award[0] <= 0) {
            command.fail("当前没有修炼中的内功");
            return;
        }
        if (award[1] == 0) {
            String maxed = String.format(java.util.Locale.ROOT, "内功 %s 已达满级，无需填满",
                    progress.facultyName());
            gmHub.update(session.machine.id(), s -> s.lastAction = maxed);
            command.succeed(maxed);
            return;
        }
        connection.writeFrame(progress.bookFrame(award[0]));
        connection.writeFrame(session.player.vitalUpdate());
        if (award[2] == 1) {
            connection.writeFrame(FacultyCustomHandler.facultyMessage(
                    FacultyCustomHandler.SUB_LEVEL_UP, award[3]));
        }
        persistFaculty(session);
        PlayerProgress.Book book = progress.book(progress.facultyName());
        String action = String.format(java.util.Locale.ROOT, "内功 %s 修炼度已填满，升至第 %d 层",
                progress.facultyName(), book == null ? 0 : book.level);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM fill_current_neigong {} -> level={}",
                session.machine.id(), progress.facultyName(), book == null ? -1 : book.level);
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

    // ================= V2 装备外观挂载：GM 物品发放/穿戴 =================

    /**
     * grant_item/add_item：发放物品。wearNow=1 且 EquipType 可穿戴 → 写
     * role_equip_items + 纸娃娃行（时装/身份装镜像新面板 40-43）+ 外观 0x10 +
     * 词缀重算 + vital；否则入背包（role_bag_items + 成对 0x19/0x18 帧）。
     */
    private void applyGrantItem(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null) {
            command.fail("角色尚未选定");
            return;
        }
        String configId = command.strParam("itemConfig").trim();
        int amount = Math.max(1, command.intParam("amount", 1));
        boolean wearNow = wearNowParam(command);
        if (configId.isEmpty()) {
            command.fail("itemConfig 必填");
            return;
        }
        if (inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(configId);
        if (info == null) {
            command.fail("物品不存在: " + configId + "（equipment.ini/tool_item.ini 均未命中）");
            return;
        }
        int slot = AppearanceSync.slotOfEquipType(info.equipType());
        if (wearNow && slot == 0) {
            wearNow = false; // 不可穿戴（道具/饰品）降级入背包
        }
        long roleId = session.selected.id();
        if (wearNow) {
            RoleInventoryStore.Equip equip = new RoleInventoryStore.Equip(
                    info.configId(), info.itemType(), info.equipType(), slot,
                    100, 100, info.artPack(), "");
            try {
                inventoryStore.upsertEquip(roleId, equip);
            } catch (RuntimeException e) {
                command.fail("穿戴落库失败: " + e.getMessage());
                return;
            }
            pushWearFrames(connection, session, equip);
            command.succeed("已穿戴 " + (info.name().isEmpty() ? info.configId() : info.name())
                    + "（" + info.configId() + "，槽位 " + slot + "）外观已同步");
        } else {
            int viewId = info.viewId() > 0 ? info.viewId() : 1;
            RoleInventoryStore.BagItem item = new RoleInventoryStore.BagItem(
                    0, info.configId(), info.itemType(), amount, viewId, info.name(),
                    info.equipType(), 0, info.script(), null, null, null, 0, 0);
            int bagSlot;
            try {
                bagSlot = inventoryStore.insertBagItem(roleId, item);
            } catch (RuntimeException e) {
                command.fail("入包失败: " + e.getMessage());
                return;
            }
            item = new RoleInventoryStore.BagItem(bagSlot, item.configId(), item.itemType(),
                    item.amount(), item.viewId(), item.name(), item.equipType(), item.colorLevel(),
                    item.script(), item.anchorSlot(), item.expiresAt(), item.artPack(),
                    item.hardiness(), item.maxHardiness());
            pushBagItemFrames(connection, session, item);
            command.succeed("已发放 " + (info.name().isEmpty() ? info.configId() : info.name())
                    + " x" + amount + " 至背包第 " + bagSlot + " 格");
        }
        log.info("session={} GM grant_item {} wearNow={}", session.machine.id(), info.configId(), wearNow);
    }

    /** wearNow 参数（面板发 JSON 布尔，RPC 串化后可能是 0/1 整形）。 */
    private static boolean wearNowParam(GmCommand command) {
        Object raw = command.params().get("wearNow");
        if (raw instanceof Boolean value) {
            return value;
        }
        return command.intParam("wearNow", 0) != 0;
    }

    /** 穿戴帧组：纸娃娃行（槽位+新面板镜像）→ 外观 0x10 → 词缀重算 → vital。 */
    private void pushWearFrames(Connection connection, LoginSession session, RoleInventoryStore.Equip equip) {
        EquipmentResolver resolver = EquipmentResolver.loadDefault();
        long roleId = session.selected.id();
        connection.writeFrame(SceneMessages.viewRemove(InventoryMessages.VIEW_DOLL, equip.slot()));
        connection.writeFrame(InventoryMessages.equipmentViewAdd(roleId, equip, resolver));
        int mirror = AppearanceSync.mirrorSlot(equip.slot());
        if (mirror != equip.slot()) {
            RoleInventoryStore.Equip mirrored = new RoleInventoryStore.Equip(equip.configId(),
                    equip.itemType(), equip.equipType(), mirror, equip.hardiness(),
                    equip.maxHardiness(), equip.artPack(), equip.propBuilt());
            connection.writeFrame(SceneMessages.viewRemove(InventoryMessages.VIEW_DOLL, mirror));
            connection.writeFrame(InventoryMessages.equipmentViewAdd(
                    InventoryMessages.VIEW_DOLL, roleId, mirrored, resolver));
        }
        var equips = loadEquips(session);
        session.visual = AppearanceSync.mergeVisual(session.visual, session.visual.sex(), equips, resolver);
        connection.writeFrame(AppearanceSync.appearanceFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                session.visual.sex(), equips, resolver));
        applyEquipBonuses(session, equips);
        connection.writeFrame(session.player.vitalUpdate());
    }

    /** 入包帧组：0x19(view,slot) → 0x18 行 → 0x19(镜像页,slot)（登录整推同序）。 */
    private void pushBagItemFrames(Connection connection, LoginSession session, RoleInventoryStore.BagItem item) {
        int view = InventoryMessages.bagViewOf(item.viewId());
        connection.writeFrame(SceneMessages.viewRemove(view, item.slot()));
        connection.writeFrame(InventoryMessages.bagViewAdd(
                session.selected.id(), item, EquipmentResolver.loadDefault()));
        int mirror = InventoryMessages.bagMirrorOf(view);
        if (mirror != view) {
            connection.writeFrame(SceneMessages.viewRemove(mirror, item.slot()));
        }
    }

    // ================= V3 风物志挂载：GM 皮肤动作（协议动作名沿用官方 card 语义） =================

    /** equip_card：收录（未收集自动补）+ 挂载（UseCardRec 行 + ShowEquipType=2）。 */
    private void applyMountSkin(Connection connection, LoginSession session, GmCommand command) {
        Integer skinId = command.intParam("cardId");
        if (skinId == null || skinId <= 0) {
            command.fail("cardId 必须正整数");
            return;
        }
        var skin = com.nineyin.server.game.avatar.skin.SkinCatalog.loadDefault().byId(skinId);
        if (skin == null) {
            command.fail("风物志皮肤不存在: " + skinId + "（rule/card.ini 未命中）");
            return;
        }
        var state = sessionSkinState(session);
        List<Integer> collected = new ArrayList<>(state == null ? List.of() : state.collected());
        if (!collected.contains(skinId)) {
            collected.add(skinId);
            java.util.Collections.sort(collected);
        }
        List<Integer> mounted = new ArrayList<>(state == null ? List.of() : state.mounted());
        mounted.remove(skinId); // 重复挂载 = 刷新到末位
        mounted.add(skinId);
        saveSkinState(session, new com.nineyin.server.game.avatar.skin.SkinStore.State(collected, mounted));
        for (byte[] frame : skinRefreshFrames(session, collected, mounted)) {
            connection.writeFrame(frame);
        }
        command.succeed("已挂载风物志皮肤 " + skinId + "（" + skinPartSummary(skin) + "）ShowEquipType=2");
        log.info("session={} GM equip_card={} parts={}", session.machine.id(), skinId, skinPartSummary(skin));
    }

    /** unequip_card：清空挂载（收集保留；ShowEquipType 回 0）。 */
    private void applyUnmountSkins(Connection connection, LoginSession session, GmCommand command) {
        var state = sessionSkinState(session);
        List<Integer> collected = state == null ? List.of() : state.collected();
        if (state == null || state.mounted().isEmpty()) {
            command.succeed("当前没有挂载任何风物志皮肤");
            return;
        }
        saveSkinState(session, new com.nineyin.server.game.avatar.skin.SkinStore.State(collected, List.of()));
        for (byte[] frame : skinRefreshFrames(session, collected, List.of())) {
            connection.writeFrame(frame);
        }
        command.succeed("已卸下全部风物志皮肤（共 " + state.mounted().size() + " 张），收集图鉴保留");
    }

    /** grant_all_cards：全量收录（挂载保持不变）。 */
    private void applyGrantAllSkins(Connection connection, LoginSession session, GmCommand command) {
        var catalog = com.nineyin.server.game.avatar.skin.SkinCatalog.loadDefault();
        if (catalog.isEmpty()) {
            command.fail("风物志目录为空（rule/card.ini 缺失或未解析）");
            return;
        }
        var state = sessionSkinState(session);
        List<Integer> collected = new ArrayList<>();
        for (var skin : catalog.all()) {
            collected.add(skin.id());
        }
        List<Integer> mounted = state == null ? List.of() : state.mounted();
        saveSkinState(session, new com.nineyin.server.game.avatar.skin.SkinStore.State(collected, mounted));
        for (byte[] frame : skinRefreshFrames(session, collected, mounted)) {
            connection.writeFrame(frame);
        }
        command.succeed("已收录全部风物志皮肤 " + collected.size() + " 张（图鉴点亮）");
        log.info("session={} GM grant_all_cards={} mounted={}",
                session.machine.id(), collected.size(), mounted.size());
    }

    /** 皮肤部件摘要（回执文案用，如 "Weapon blade_card_001"）。 */
    private static String skinPartSummary(
            com.nineyin.server.game.avatar.skin.SkinCatalog.Skin skin) {
        StringBuilder summary = new StringBuilder();
        for (var partModel : skin.models(0)) {
            if (summary.length() > 0) {
                summary.append(';');
            }
            summary.append(partModel.part()).append(' ').append(partModel.model());
        }
        return summary.length() == 0 ? "无模型" : summary.toString();
    }

    /** 会话皮肤状态（首访问装载存档；无存档返回 null）。 */
    private com.nineyin.server.game.avatar.skin.SkinStore.State sessionSkinState(LoginSession session) {
        if (session.skinState != null || session.selected == null) {
            return session.skinState;
        }
        try {
            session.skinState = skinStore.load(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load skin state: {}", session.machine.id(), e.getMessage());
        }
        return session.skinState;
    }

    private void saveSkinState(LoginSession session, com.nineyin.server.game.avatar.skin.SkinStore.State state) {
        session.skinState = state;
        try {
            skinStore.save(session.selected.id(), state);
        } catch (RuntimeException e) {
            log.warn("session={} save skin state: {}", session.machine.id(), e.getMessage());
        }
    }

    // ---------------- V3 风物志：客户端上行（卡片使用 → 收录 → 衣橱换装） ----------------

    /**
     * C2S 30 穿脱/使用（引擎原生信封，载荷布局无抓包钉死——整型参数全量记录供
     * 真机校准）。可判定语义：载荷中相邻整型对按 (view,slot)/(slot,view) 双向
     * 尝试命中 script=CardItem 的背包卡片时走「使用收录」链——消耗卡片 →
     * 收录风物志（CardRec 位图 + on_card_msg），衣橱内即可换装（custom 180）。
     */
    void handleBagItemUse(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        List<Integer> args = customIntArgs(custom);
        log.info("session={} custom 30 use/wear args={} (布局待真机校准)", session.machine.id(), args);
        if (inventoryStore == null || args.size() < 2) {
            return;
        }
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null) {
            return;
        }
        for (int i = 0; i + 1 < args.size(); i++) {
            RoleInventoryStore.BagItem hit = skinCardAt(bagItems, args.get(i), args.get(i + 1));
            if (hit == null) {
                hit = skinCardAt(bagItems, args.get(i + 1), args.get(i));
            }
            if (hit != null) {
                collectSkin(connection, session, hit, true);
                return;
            }
        }
    }

    /** 背包 (view,slot) → 风物志卡片行（非 CardItem 返回 null）。 */
    private RoleInventoryStore.BagItem skinCardAt(List<RoleInventoryStore.BagItem> bagItems,
                                                  int view, int slot) {
        for (RoleInventoryStore.BagItem item : bagItems) {
            if (item.slot() != slot || InventoryMessages.bagViewOf(item.viewId()) != view) {
                continue;
            }
            ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(item.configId());
            if (info != null && info.isSkinCard()) {
                return item;
            }
        }
        return null;
    }

    /** 使用卡片收录：消耗 1 张（consume=true 且库可用）→ 收录皮肤（不自动挂载，衣橱内自行换装）。 */
    private void collectSkin(Connection connection, LoginSession session,
                             RoleInventoryStore.BagItem item, boolean consume) {
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(item.configId());
        if (info == null || !info.isSkinCard()) {
            return;
        }
        int skinId = info.cardId();
        if (consume && !consumeBagCard(connection, session, item)) {
            return; // 消耗失败（数量不足/DB 异常）不收录
        }
        var state = sessionSkinState(session);
        List<Integer> collected = new ArrayList<>(state == null ? List.of() : state.collected());
        List<Integer> mounted = state == null ? List.of() : state.mounted();
        if (!collected.contains(skinId)) {
            collected.add(skinId);
            java.util.Collections.sort(collected);
        }
        saveSkinState(session, new com.nineyin.server.game.avatar.skin.SkinStore.State(collected, mounted));
        for (byte[] frame : skinRefreshFrames(session, collected, mounted)) {
            connection.writeFrame(frame);
        }
        log.info("session={} 使用卡片 {}（{}）收录风物志皮肤 {}，衣橱内可换装",
                session.machine.id(), item.configId(),
                info.name().isEmpty() ? info.configId() : info.name(), skinId);
    }

    /** 消耗背包卡片 1 张并同步客户端行（剩余>0 重推行，=0 删行+镜像清位）。 */
    private boolean consumeBagCard(Connection connection, LoginSession session,
                                   RoleInventoryStore.BagItem item) {
        int remaining;
        try {
            remaining = inventoryStore.consumeBagItem(session.selected.id(), item.slot(), 1);
        } catch (RuntimeException e) {
            log.warn("session={} consume card {}: {}", session.machine.id(), item.configId(), e.getMessage());
            return false;
        }
        if (remaining < 0) {
            return false;
        }
        int view = InventoryMessages.bagViewOf(item.viewId());
        int mirror = InventoryMessages.bagMirrorOf(view);
        if (remaining > 0) {
            RoleInventoryStore.BagItem updated = new RoleInventoryStore.BagItem(
                    item.slot(), item.configId(), item.itemType(), remaining, item.viewId(),
                    item.name(), item.equipType(), item.colorLevel(), item.script(),
                    item.anchorSlot(), item.expiresAt(), item.artPack(),
                    item.hardiness(), item.maxHardiness());
            connection.writeFrame(SceneMessages.viewRemove(view, item.slot()));
            connection.writeFrame(InventoryMessages.bagViewAdd(
                    session.selected.id(), updated, EquipmentResolver.loadDefault()));
            connection.writeFrame(SceneMessages.viewRemove(mirror, item.slot()));
        } else {
            connection.writeFrame(SceneMessages.viewRemove(view, item.slot()));
            connection.writeFrame(SceneMessages.viewRemove(mirror, item.slot()));
        }
        return true;
    }

    /**
     * C2S 180 custom_card（衣橱卡牌 UI，`custom_card(int,...)`）。
     * 子操作布局无抓包——按皮肤当前状态推进（幂等状态机，真机日志校准）：
     * 未收录 → 收录（背包有对应卡片则消耗 1 张）；已收录未挂载 → 挂载
     * （同部件互斥换装：新皮肤覆盖部件与已挂载重叠时替换，发型等新部件叠加）；
     * 已挂载 → 卸下。
     */
    void handleWardrobeCard(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        List<Integer> args = customIntArgs(custom);
        log.info("session={} custom 180 card args={} (子操作待真机校准)", session.machine.id(), args);
        Integer skinId = firstKnownSkinArg(args);
        if (skinId == null) {
            return;
        }
        var catalog = com.nineyin.server.game.avatar.skin.SkinCatalog.loadDefault();
        var skin = catalog.byId(skinId);
        if (skin == null) {
            return;
        }
        var state = sessionSkinState(session);
        List<Integer> collected = new ArrayList<>(state == null ? List.of() : state.collected());
        List<Integer> mounted = new ArrayList<>(state == null ? List.of() : state.mounted());
        String action;
        if (!collected.contains(skinId)) {
            // 收录：背包有对应卡片则消耗（「使用后进入衣橱」的卡片链入口）。
            consumeCardItemOfSkin(connection, session, skinId);
            collected.add(skinId);
            java.util.Collections.sort(collected);
            action = "收录";
        } else if (mounted.contains(skinId)) {
            mounted.remove(Integer.valueOf(skinId));
            action = "卸下";
        } else {
            // 换装：同部件互斥（武器换武器、衣服换衣服），不同部件叠加（发型卡口径）。
            int sex = session.visual == null ? 0 : session.visual.sex();
            java.util.Set<String> parts = skin.parts(sex);
            mounted.removeIf(id -> {
                var other = catalog.byId(id);
                return other != null
                        && !java.util.Collections.disjoint(other.parts(sex), parts);
            });
            mounted.add(skinId);
            action = "挂载";
        }
        saveSkinState(session, new com.nineyin.server.game.avatar.skin.SkinStore.State(collected, mounted));
        for (byte[] frame : skinRefreshFrames(session, collected, mounted)) {
            connection.writeFrame(frame);
        }
        log.info("session={} 衣橱{}风物志皮肤 {}（{}）mounted={}",
                session.machine.id(), action, skinId, skinPartSummary(skin), mounted);
    }

    /** 皮肤号对应的背包卡片（card_item_*，CardID 字段直连）存在则消耗 1 张。 */
    private void consumeCardItemOfSkin(Connection connection, LoginSession session, int skinId) {
        if (inventoryStore == null) {
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().cardItemBySkinId(skinId);
        if (info == null) {
            return;
        }
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null) {
            return;
        }
        for (RoleInventoryStore.BagItem item : bagItems) {
            if (item.configId().equalsIgnoreCase(info.configId())) {
                consumeBagCard(connection, session, item);
                return;
            }
        }
    }

    /** custom 载荷整型参数（跳过首位消息号；int32/int64/整值 float 皆收）。 */
    private static List<Integer> customIntArgs(CustomC2S.Message custom) {
        List<Integer> args = new ArrayList<>();
        for (int i = 1; i < custom.values.size(); i++) {
            CustomC2S.Value value = custom.values.get(i);
            if (value.tag >= 2 && value.tag <= 5) {
                try {
                    args.add(value.exactInt32());
                } catch (RuntimeException ignored) {
                    // 非整值跳过
                }
            }
        }
        return args;
    }

    /** 首个命中皮肤目录的整型参数（衣橱操作的皮肤号定位）。 */
    private static Integer firstKnownSkinArg(List<Integer> args) {
        var catalog = com.nineyin.server.game.avatar.skin.SkinCatalog.loadDefault();
        for (int arg : args) {
            if (arg > 0 && catalog.byId(arg) != null) {
                return arg;
            }
        }
        return null;
    }

    /** 背包行装载（DB 不可用/异常返回 null）。 */
    private List<RoleInventoryStore.BagItem> safeBagItems(LoginSession session) {
        if (inventoryStore == null || session.selected == null) {
            return null;
        }
        try {
            return inventoryStore.bagItems(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load bag items: {}", session.machine.id(), e.getMessage());
            return null;
        }
    }

    // ================= 配套：模板生成武器/防具 + 一键激活时装 =================

    /** 武器类型 → 模型前缀（101刀/102剑/103匕首/104双刀/105双剑/106双刺/107长棍/108短棍/122重剑）。 */
    private static final java.util.Map<Integer, String> WEAPON_PREFIX = java.util.Map.of(
            101, "blade_", 102, "sword_", 103, "thorn_", 104, "sblade_", 105, "ssword_",
            106, "sthorn_", 107, "lstuff_", 108, "cosh_", 122, "claymore_");

    /** 防具部位名（GM 面板口径）→ EquipType。 */
    private static final java.util.Map<String, String> ARMOR_SLOT = java.util.Map.of(
            "helmet", "Hat", "amulet", "Necklace", "cloth", "Cloth", "pants", "Pants",
            "leg", "Leg", "shoes", "Shoes", "wrist", "Wrist", "ear", "Earring", "ring", "Fingerring");

    /** gm_generate_weapon：按武器类型挑模板 + 生成随机词条（厨师 6 条/铁匠 3 条）并立即穿戴。 */
    private void applyGenerateWeapon(Connection connection, LoginSession session, GmCommand command) {
        Integer weaponType = command.intParam("weaponType");
        String crafter = command.strParam("crafter");
        if (weaponType == null || !WEAPON_PREFIX.containsKey(weaponType)) {
            command.fail("weaponType 必须是 101-108/122（刀/剑/匕首/双刀/双剑/双刺/长棍/短棍/重剑）");
            return;
        }
        if (!crafter.equals("cook") && !crafter.equals("smith")) {
            command.fail("crafter 必须是 cook（厨师）或 smith（铁匠）");
            return;
        }
        ToolItemCatalog.ItemInfo template = pickTemplate(
                session, "Weapon", WEAPON_PREFIX.get(weaponType), session.visual.sex());
        if (template == null) {
            command.fail("没有武器类型 " + weaponType + " 的可穿戴模板");
            return;
        }
        int affixCount = crafter.equals("cook") ? 6 : 3;
        wearGenerated(connection, session, command, template,
                randomPropBuilt(affixCount), "武器");
    }

    /** gm_generate_armor：按防具部位挑模板 + 3 条随机词条（可穿戴部位直接穿，饰品入背包）。 */
    private void applyGenerateArmor(Connection connection, LoginSession session, GmCommand command) {
        String armorSlot = command.strParam("armorSlot");
        String equipType = ARMOR_SLOT.get(armorSlot);
        if (equipType == null) {
            command.fail("armorSlot 必须是 helmet/amulet/cloth/pants/leg/shoes/ear/ring");
            return;
        }
        ToolItemCatalog.ItemInfo template = pickTemplate(session, equipType, null, session.visual.sex());
        if (template == null) {
            command.fail("没有部位 " + armorSlot + " 的可穿戴模板");
            return;
        }
        wearGenerated(connection, session, command, template, randomPropBuilt(3), "防具");
    }

    /** 模板挑选：EquipType 匹配（武器再加模型前缀）+ 性别过滤 + 品质降序取随机一件。 */
    private ToolItemCatalog.ItemInfo pickTemplate(LoginSession session, String equipType,
                                                  String modelPrefix, int sex) {
        EquipmentResolver resolver = EquipmentResolver.loadDefault();
        List<ToolItemCatalog.ItemInfo> candidates = new ArrayList<>();
        for (ToolItemCatalog.ItemInfo info : ToolItemCatalog.loadDefault().all()) {
            if (!info.equipType().equals(equipType)) {
                continue;
            }
            if (info.needSex() != 2 && info.needSex() != sex) {
                continue; // NeedSex 0男/1女/2通用
            }
            if (modelPrefix != null
                    && !resolver.resolve(info.configId(), sex).startsWith(modelPrefix)) {
                continue;
            }
            candidates.add(info);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort((a, b) -> Integer.compare(b.colorLevel(), a.colorLevel()));
        int window = Math.min(8, candidates.size()); // 头部品质窗口内随机
        return candidates.get(sessionRandom(session).nextInt(window));
    }

    /** 随机词条串（"1,pack;1,pack…"，词缀包号从 EquipAffixCatalog 全池随机）。 */
    private String randomPropBuilt(int count) {
        var packIds = com.nineyin.server.game.avatar.panel.EquipAffixCatalog.load().packIds();
        java.util.Random random = sessionRandom(null);
        if (packIds.isEmpty()) {
            return "";
        }
        StringBuilder built = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (built.length() > 0) {
                built.append(';');
            }
            built.append("1,").append(packIds.get(random.nextInt(packIds.size())));
        }
        return built.toString();
    }

    private java.util.Random sessionRandom(LoginSession session) {
        return session != null ? session.combatRandom : new java.util.Random();
    }

    /** 生成模板落地：词条可写则穿戴（affixes JSON），否则入背包（v1 饰品槽位未证）。 */
    private void wearGenerated(Connection connection, LoginSession session, GmCommand command,
                               ToolItemCatalog.ItemInfo template, String propBuilt, String kind) {
        if (session.selected == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        int slot = AppearanceSync.slotOfEquipType(template.equipType());
        if (slot == 0) {
            command.fail(template.configId() + "（" + kind + "模板）槽位未证，暂不支持穿戴");
            return;
        }
        RoleInventoryStore.Equip equip = new RoleInventoryStore.Equip(
                template.configId(), template.itemType(), template.equipType(), slot,
                100, 100, template.artPack(), propBuilt);
        try {
            inventoryStore.upsertEquip(session.selected.id(), equip);
        } catch (RuntimeException e) {
            command.fail("穿戴落库失败: " + e.getMessage());
            return;
        }
        pushWearFrames(connection, session, equip);
        int affixCount = propBuilt.isEmpty() ? 0 : propBuilt.split(";").length;
        command.succeed("已生成并穿戴" + kind + " " + template.configId()
                + "（词条 " + affixCount + " 条）外观与加成已同步");
        log.info("session={} GM generate {} {} affixes={}",
                session.machine.id(), kind, template.configId(), affixCount);
    }

    /** grant_all_fashion：激活全部时装（EquipType=FashionCoat）→ role_fashion_suits + FashionSuitRec 行 + JSON 存档。 */
    private void applyGrantAllFashion(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        List<String> suitIds = new ArrayList<>();
        for (ToolItemCatalog.ItemInfo info : ToolItemCatalog.loadDefault().all()) {
            if (info.equipType().equals("FashionCoat") || info.equipType().equals("NewFashionCoat")) {
                suitIds.add(info.configId());
            }
        }
        if (suitIds.isEmpty()) {
            command.fail("装备配置中没有 FashionCoat 时装（equipment.ini 未解析？）");
            return;
        }
        try {
            inventoryStore.activateFashionSuits(session.selected.id(), suitIds);
        } catch (RuntimeException e) {
            command.fail("时装落库失败: " + e.getMessage());
            return;
        }
        session.fashionSuits = suitIds;
        for (byte[] frame : fashionSuitFrames(suitIds)) {
            connection.writeFrame(frame);
        }
        archiveFashionSuits(session.selected.id(), suitIds);
        command.succeed("已激活全部时装 " + suitIds.size() + " 套（属性面板「时装」页签 + data/新版服装数据.json）");
        log.info("session={} GM grant_all_fashion={}", session.machine.id(), suitIds.size());
    }

    /** FashionSuitRec 行（[7,3,3,4,3,3]：configId + 数值列零填充——列语义无抓包，真机校准点）。 */
    private List<byte[]> fashionSuitFrames(List<String> suitIds) {
        int record = RecordSchemas.indexByName("FashionSuitRec");
        List<byte[]> frames = new ArrayList<>(suitIds.size());
        for (String suitId : suitIds) {
            frames.add(RecordMessages.recordAddCells(
                    SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, record,
                    RecordMessages.Cell.ofString(suitId),
                    RecordMessages.Cell.ofInt(0), RecordMessages.Cell.ofInt(0),
                    RecordMessages.Cell.ofInt64(0),
                    RecordMessages.Cell.ofInt(0), RecordMessages.Cell.ofInt(0)));
        }
        return frames;
    }

    /** 永久存档 data/新版服装数据.json（GM 面板承诺口径；DB 是权威源）。 */
    private void archiveFashionSuits(long roleId, List<String> suitIds) {
        try {
            Path archive = com.nineyin.util.ServerPaths.projectPath("data", "新版服装数据.json");
            com.alibaba.fastjson2.JSONObject document = new com.alibaba.fastjson2.JSONObject();
            document.put("version", 1);
            com.alibaba.fastjson2.JSONObject roles = new com.alibaba.fastjson2.JSONObject();
            roles.put(Long.toString(roleId), suitIds);
            document.put("roles", roles);
            Path directory = archive.getParent();
            if (directory != null) {
                java.nio.file.Files.createDirectories(directory);
            }
            java.nio.file.Files.writeString(archive,
                    document.toJSONString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("archive fashion suits: {}", e.getMessage());
        }
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
        snapshot.faculty = session.player.progress().snapshot().faculty();
        gmHub.attach(snapshot.sessionId, snapshot);
        trackActive(session);
    }

    /** 在线登记（选角入世界时调用）：优雅停机 shutdownFlush 的刷写依据。 */
    void trackActive(LoginSession session) {
        activeSessions.add(session);
    }

    /** 连接断开（含校验失败断连）：Go defer（pendingLocation/persistFaculty/world.close）+ gm.detach。 */
    public void onDisconnect(LoginSession session) {
        activeSessions.remove(session);
        persistSessionData(session);
        if (session.facultyTicker != null) {
            session.facultyTicker.cancel(false);
        }
        if (session.world != null) {
            session.world.close();
        }
        gmHub.detach(session.machine.id());
    }

    /** 断连/停机共用落盘：pendingLocation + faculty + meridian（幂等，失败仅告警不影响其余）。 */
    private void persistSessionData(LoginSession session) {
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
            try {
                meridianStore.save(session.selected.id(), session.player.meridianSnapshot());
            } catch (RuntimeException e) {
                log.warn("persist meridian progress: {}", e.getMessage());
            }
        }
    }

    /**
     * 优雅停机落盘：Summer 停机钩子的 Server.shutdown() 走 shutdownGracefully()，
     * 不会触发在线连接的 channelInactive，须在此显式逐会话刷写。复用 onDisconnect
     * 全套语义（落盘+停 ticker+关世界+摘除面板快照）；幂等，可安全与真实断连竞争。
     * 调用前提：网络线程已停（Summer 钩子先 ServerMgr.shutdown 再 app.stop）。
     *
     * @return 本次刷写的会话数
     */
    public int shutdownFlush() {
        java.util.List<LoginSession> pending = List.copyOf(activeSessions);
        for (LoginSession session : pending) {
            try {
                onDisconnect(session);
            } catch (RuntimeException e) {
                log.warn("shutdown flush session={}: {}", session.machine.id(), e.getMessage());
            }
        }
        return pending.size();
    }

    /** 关闭自持存档的 DB 池（meridian/customizing；优雅停机收尾，在 shutdownFlush 之后调用）。 */
    public void closeStores() {
        meridianStore.close();
        customizingStore.close();
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
            java.util.List<RoleInventoryStore.Skill> dbSkills = loadDbSkills(session);
            StarterGrants.grantStarterProgress(connection, session.player,
                    dbSkills, loadDbShortcuts(session));
            session.learnedSkills = learnedSkillLevels(dbSkills);
            session.skillRows = skillRows(dbSkills);
            session.neigongGranted = true;
            // View40 行可用性初始批（62433 抓包：starter 授予后每行推 CanUse=1；
            // 不发则客户端技能图标恒为不可用——真机反馈"显示不可用但能释放"）
            for (java.util.Map.Entry<String, Integer> row : session.skillRows.entrySet()) {
                connection.writeFrame(FightMessages.skillRowCanUse(
                        SkillCastHandler.SKILL_VIEW_ID, row.getValue(), true));
            }
            log.info("session={} granted View=43 inner-power and View=40 starter skills after explicit ClientReady (learned={})",
                    session.machine.id(), session.learnedSkills.size());
        }
        if (session.player != null && !session.meridianGranted) {
            // 经脉系统授予（F 盘 52795 抓包同位：View45 容器+行在 46/43/40 之后）：
            // View45 已学经脉 + active_jingmai_rec 行 + 玩家经脉属性帧。
            for (byte[] frame : com.nineyin.server.game.avatar.jingmai.JingMaiMessages.viewFrames(
                    session.player.meridian(),
                    com.nineyin.server.game.avatar.jingmai.MeridianCatalog.load())) {
                connection.writeFrame(frame);
            }
            String cultivating = session.player.meridian().curJingMai();
            if (!cultivating.isEmpty()) {
                connection.writeFrame(com.nineyin.server.game.avatar.jingmai.JingMaiMessages.activeRecordRow(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, cultivating));
                session.player.noteMeridianActivated();
            }
            connection.writeFrame(com.nineyin.server.game.avatar.jingmai.JingMaiMessages.playerPropsFrame(
                    session.player.meridian()));
            session.meridianGranted = true;
            log.info("session={} granted View=45 meridians={} totalLevel={}",
                    session.machine.id(), session.player.meridian().learned().size(),
                    session.player.meridian().totalLevel());
        }
        if (session.player != null && !session.customizingPushed) {
            // 个性配置（按键/操作习惯）：在客户端登录期上传默认键位（C2S 107）之前
            // 推送存档（S2C 705 → on_parser_customizing），玩家自定义键位重登即恢复；
            // 无存档不发（客户端保持默认并上传，首个 107 即建立基线）。
            pushSavedCustomizing(connection, session);
        }
        if (session.player != null && !session.keybindPushed) {
            // 键位映射（role_keybinds.bind_value，"1,87;2,83;…"=W/S/A/D）：post-spawn
            // 以玩家属性 ShortcutKey（0x09 表索引 198，type7 string）下发——不发则
            // 客户端恒为默认 QWES 映射，切 WASD 重登即丢（参考端 641B 同款帧）。
            pushSavedKeybind(connection, session);
        }
        if (session.player != null && !session.skinsPushed) {
            // V3 风物志：CardRec 收集位图 + UseCardRec 挂载行 + ShowEquipType 显示
            // 开关（无存档不发，客户端保持未收集基线）。
            pushSavedSkins(connection, session);
        }
        if (session.player != null && !session.fashionPushed) {
            // 时装页签：FashionSuitRec 行（role_fashion_suits 已激活套装；无激活零帧）。
            session.fashionPushed = true;
            for (byte[] frame : fashionSuitFrames(session.fashionSuits)) {
                connection.writeFrame(frame);
            }
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
            session.world.startPatrol();
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

    /** Go 白名单 {200,201,240,211,215,216,217,218,1006}+150 经脉+235 历练；已实现 215/1006/235/54/211/431/150/216。 */
    private void dispatchCustom(Connection connection, LoginSession session,
                                CustomC2S.Message custom, String route) {
        int messageId = custom.messageId();
        switch (messageId) {
            case 215, 1006, 235 -> {
                try {
                    FacultyCustomHandler.handle(connection, session.player, custom);
                } catch (RuntimeException e) {
                    log.info("session={} reject custom {} via {}: {}",
                            session.machine.id(), messageId, route, e.getMessage());
                    return;
                }
                persistFaculty(session);
            }
            case 150 -> {
                try {
                    com.nineyin.server.game.avatar.jingmai.JingMaiCustomHandler.handle(
                            connection, session.player, custom);
                } catch (RuntimeException e) {
                    log.info("session={} reject custom {} via {}: {}",
                            session.machine.id(), messageId, route, e.getMessage());
                    return;
                }
                persistMeridian(session);
            }
            case 211 -> handleUseSkill(connection, session, custom);
            case 431 -> handleSelectTarget(connection, session, custom);
            case 216 -> {
                if (session.player != null) {
                    try {
                        com.nineyin.server.world.fight.QingGongHandler.handle(
                                connection, session.player, custom);
                    } catch (RuntimeException e) {
                        log.info("session={} reject custom 216 via {}: {}",
                                session.machine.id(), route, e.getMessage());
                    }
                }
            }
            case 107 -> handleCustomizingUpload(connection, session, custom);
            case 200 -> handleSetShortcut(connection, session, custom);
            case 201 -> handleRemoveShortcut(connection, session, custom);
            // V3 风物志：30=引擎原生穿脱/使用（卡片使用收录入口），180=custom_card
            // 衣橱卡牌 UI（换装切换入口）。
            case 30 -> handleBagItemUse(connection, session, custom);
            case 180 -> handleWardrobeCard(connection, session, custom);
            case 240, 217, 218 ->
                    log.info("session={} unimplemented custom msgId={} via {} (phase-4 backlog)",
                            session.machine.id(), messageId, route);
            case 54 -> handleManualParry(connection, session, custom);
            default -> log.info("session={} unrecognized custom msgId={} via {}",
                    session.machine.id(), messageId, route);
        }
    }

    /**
     * C2S 200 设置快捷栏（shortcut_game.lua set_shortcut：`[200, index, kind, id]`，
     * kind∈skill/item/neigong/zhenfa）。帧序同 Go handleShortcutCustom：替换先 0x12 删
     * 旧行再 0x11 增新行；幂等不变零帧。DB 模式同步写 role_shortcuts（重登回放）。
     */
    void handleSetShortcut(Connection connection, LoginSession session,
                                   CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        if (custom.values.size() < 4) {
            log.info("session={} set shortcut without index+kind+id", session.machine.id());
            return;
        }
        int index = tolerantInt(custom, 1);
        String kind = custom.values.get(2).textOrNull();
        String id = custom.values.get(3).textOrNull();
        if (index < 0 || index > 4096 || kind == null || kind.isEmpty()
                || id == null || id.isEmpty() || kind.length() > 64 || id.length() > 256) {
            log.info("session={} set shortcut invalid index={} kind={} id={}",
                    session.machine.id(), index, kind, id);
            return;
        }
        PlayerActor.ShortcutUpdate update = session.player.setShortcut(index, kind, id);
        if (update.unchanged()) {
            return;
        }
        if (update.existed()) {
            connection.writeFrame(RecordMessages.recordDelRow(
                    SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                    RecordMessages.RECORD_SHORTCUT, update.oldRow()));
        }
        connection.writeFrame(StarterGrants.shortcutAddFrame(
                new PlayerActor.PlayerShortcut(index, kind, id)));
        persistShortcut(() -> inventoryStore.upsertShortcut(session.selected.id(), index, kind, id));
        log.info("session={} shortcut set index={} kind={} id={} replaced={}",
                session.machine.id(), index, kind, id, update.existed());
    }

    /**
     * C2S 201 摘除快捷栏（`[201, 行号]`，Go removeShortcutRow 同语义按记录行号删）；
     * 行不存在静默。DB 模式同步删 role_shortcuts 对应 index。
     */
    void handleRemoveShortcut(Connection connection, LoginSession session,
                                      CustomC2S.Message custom) {
        if (session.player == null || session.selected == null || custom.values.size() < 2) {
            return;
        }
        int row = tolerantInt(custom, 1);
        PlayerActor.PlayerShortcut removed = session.player.removeShortcutRow(row);
        if (removed == null) {
            return;
        }
        connection.writeFrame(RecordMessages.recordDelRow(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                RecordMessages.RECORD_SHORTCUT, row));
        persistShortcut(() -> inventoryStore.deleteShortcut(session.selected.id(), removed.index()));
        log.info("session={} shortcut removed row={} index={} kind={} id={}",
                session.machine.id(), row, removed.index(), removed.kind(), removed.id());
    }


    /** tag2 int 宽容读取：客户端 nx_int 负值以 u32 回绕上行，(int) 下转还原。 */
    static int tolerantInt(CustomC2S.Message custom, int position) {
        CustomC2S.Value value = custom.values.get(position);
        if (value.tag != 2) {
            throw new IllegalStateException("arg " + position + " is not int32");
        }
        return (int) value.int32;
    }

    /** 快捷栏 DB 持久化（inventoryStore 缺席或失败仅告警——内存态/帧已生效）。 */
    private void persistShortcut(Runnable write) {
        if (inventoryStore == null) {
            return;
        }
        try {
            write.run();
        } catch (RuntimeException e) {
            log.warn("persist shortcut: {}", e.getMessage());
        }
    }

    /** msg211 USE_SKILL → 战斗编排（拒绝路径静默记日志，抓包实证不回帧）。 */
    private void handleUseSkill(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.world == null || session.fight == null) {
            log.info("session={} reject skill before scene ready", session.machine.id());
            return;
        }
        SkillCastHandler.handle(new SkillCastHandler.CastContext(
                connection, session.player, session.world, session.fight,
                session.learnedSkills, session.skillRows, scheduler, clock,
                session.combatRandom), custom);
    }

    /** msg431 选中目标（与 0x07 等价，62433 抓包成对出现；仅 combat NPC 落选）。 */
    private void handleSelectTarget(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.world == null || session.fight == null
                || custom.values.size() < 2 || custom.values.get(1).tag != 8) {
            return;
        }
        long packed = custom.values.get(1).int64;
        int objectId = (int) (packed & 0xFFFFFFFFL);
        int ownerId = (int) (packed >>> 32);
        selectCombatTarget(connection, session, objectId, ownerId);
    }

    /**
     * C2S 0x07 对象请求（33B：seq@17/objId@21/owner@25/FuncID@29）。
     * 战斗一期只接目标选择：combat NPC → 记选中 + 回 0x10 LastObject（idx414，
     * 62433 抓包 19 对 0x07/431 全回）；非 combat（菜单/传送门）暂放行待后续期。
     */
    private boolean onObjectRequest(Connection connection, LoginSession session, byte[] plain) {
        if (plain.length != 33) {
            log.info("session={} tolerated object request len={}", session.machine.id(), plain.length);
            return true;
        }
        int objectId = readU32(plain, 21);
        int ownerId = readU32(plain, 25);
        if (session.player != null && session.world != null && session.fight != null) {
            selectCombatTarget(connection, session, objectId, ownerId);
        }
        return true;
    }

    /** 选中 combat NPC（存活才可用于出招）；只要对象存在就回 LastObject 属性帧。 */
    private void selectCombatTarget(Connection connection, LoginSession session,
                                    int objectId, int ownerId) {
        if (session.world.combatNpcInfo(objectId) == null) {
            return;
        }
        boolean selected = session.fight.selectTarget(session.world, objectId, ownerId);
        if (!selected) {
            log.info("session={} target {}-{} not selectable (dead or non-combat)",
                    session.machine.id(), objectId, ownerId);
        }
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "LastObject", PropertyValues.Value.ofObject(objectId, ownerId));
        connection.writeFrame(SceneMessages.objectProperties(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, 0, properties));
    }

    private static int readU32(byte[] plain, int offset) {
        return (plain[offset] & 0xFF) | (plain[offset + 1] & 0xFF) << 8
                | (plain[offset + 2] & 0xFF) << 16 | (plain[offset + 3] & 0xFF) << 24;
    }

    /** 已学表：DB role_skills 优先，回退 starter 集（等级恒 1，与 View40 下发一致）。 */
    private static java.util.Map<String, Integer> learnedSkillLevels(
            java.util.List<RoleInventoryStore.Skill> dbSkills) {
        java.util.Map<String, Integer> learned = new java.util.LinkedHashMap<>();
        if (dbSkills != null && !dbSkills.isEmpty()) {
            for (RoleInventoryStore.Skill skill : dbSkills) {
                learned.put(skill.skillId(), 1);
            }
            return learned;
        }
        return StarterSkills.starterLearnedLevels();
    }

    /** View40 行号表：与 grantStarterProgress 下发顺序同构（db 行序或 starter 列表序，1 起）。 */
    private static java.util.Map<String, Integer> skillRows(
            java.util.List<RoleInventoryStore.Skill> dbSkills) {
        java.util.Map<String, Integer> rows = new java.util.LinkedHashMap<>();
        if (dbSkills != null && !dbSkills.isEmpty()) {
            for (int i = 0; i < dbSkills.size(); i++) {
                rows.put(dbSkills.get(i).skillId(), i + 1);
            }
            return rows;
        }
        List<String> ids = StarterSkills.starterSkillIds();
        for (int i = 0; i < ids.size(); i++) {
            rows.put(ids.get(i), i + 1);
        }
        return rows;
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
        // 非战斗也可按格挡：参考服 bug.txt「待机状态下使用气招或者架招不会进入战斗」
        // = 架招可用但不切 LogicState，而非拒绝按下。LogicState 只随命中进/出战斗，
        // 与格挡态正交——db884de 曾按 62433 战斗抓包模型加闸门，真机反馈非战斗
        // 格挡失效后撤掉（2026-11-02）。
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
        boolean advanced = tick[1] != 0;
        if (advanced) {
            if (tick[0] > 0) {
                connection.writeFrame(session.player.progress().bookFrame(tick[0]));
            }
            connection.writeFrame(session.player.vitalUpdate());
            persistFaculty(session);
        }
        livepointTickOnce(connection, session);
    }

    /**
     * 历练层 100 秒步进（facultyTickOnce 同拍）：累积 +1 历练/100s 进槽、按槽速转化产修为
     * （普通档 70% + 30% 潜能）。有变化才 [0x10 vital]（LiveGroove/Faculty/HoldFaculty 随帧）
     * + 持久化；静默秒零帧。
     */
    void livepointTickOnce(Connection connection, LoginSession session) {
        if (session.player == null || session.selected == null) {
            return;
        }
        var progress = session.player.progress();
        if (!progress.livepointTick(clock.instant(), progress.livepointVip())) {
            return;
        }
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
    }

    /**
     * 经脉 1s ticker（facultyTicker 同拍）：整分钟真气结算 → View45 行 CurFillValue
     * 单帧；升级再补 Level/TotalFillValue 行帧 + 玩家 JingMaiTotalLevel/ZQ 属性帧；
     * 有变化才持久化，静默秒零帧。
     */
    void meridianTickOnce(Connection connection, LoginSession session) {
        if (session.player == null || session.selected == null || !session.meridianGranted) {
            return;
        }
        var state = session.player.meridian();
        var catalog = com.nineyin.server.game.avatar.jingmai.MeridianCatalog.load();
        var tick = state.tick();
        if (!tick.changed()) {
            return;
        }
        String current = state.curJingMai();
        var meridian = state.meridian(current);
        int row = state.rowOf(current);
        if (meridian == null || row <= 0) {
            return;
        }
        if (tick.leveledUp().isEmpty()) {
            connection.writeFrame(com.nineyin.server.game.avatar.jingmai.JingMaiMessages.progressFrame(
                    row, meridian.fill()));
        } else {
            connection.writeFrame(com.nineyin.server.game.avatar.jingmai.JingMaiMessages.levelUpFrame(
                    row, meridian.level(), meridian.fill(), catalog.totalFillPerLevel(current)));
        }
        connection.writeFrame(com.nineyin.server.game.avatar.jingmai.JingMaiMessages.playerPropsFrame(state));
        persistMeridian(session);
    }

    /** 经脉存档落盘（失败仅告警，不影响会话）。 */
    private void persistMeridian(LoginSession session) {
        if (session.selected == null || session.player == null) {
            return;
        }
        try {
            meridianStore.save(session.selected.id(), session.player.meridianSnapshot());
        } catch (RuntimeException e) {
            log.warn("persist meridian progress: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 个性配置

    /** 个性配置下行通道（52795 抓包钉死）：S2C 705 = on_parser_customizing(configStr)。 */
    static byte[] customizingFrame(String config) {
        return CustomMessages.customIntMessage(705, CustomMessages.Value.ofString(config));
    }

    /**
     * 键位映射下行帧（参考端 60310 抓包 frame 逐字节范本）：0x10 单属性帧，
     * 玩家对象 + 属性 ShortcutKey（索引 198，type7）。线上串 = bind + " "，
     * STRING 编码 u32(len+1)=643 + 字节 + NUL，与抓包完全一致。
     */
    static byte[] keybindFrame(String bindValue) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "ShortcutKey",
                PropertyValues.Value.ofString(bindValue + " "));
        return SceneMessages.objectProperties(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, 0, properties);
    }

    /** post-spawn 推送存档键位映射（无存档零帧；客户端保持默认 QWES）。 */
    void pushSavedKeybind(Connection connection, LoginSession session) {
        session.keybindPushed = true;
        if (session.selected == null) {
            return;
        }
        String bind = loadKeybind(session);
        if (bind == null || bind.isEmpty()) {
            return;
        }
        connection.writeFrame(keybindFrame(bind));
        log.info("session={} pushed saved keybind {} bytes (ShortcutKey idx=198)",
                session.machine.id(), bind.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    /** ClientReady 推送存档的风物志皮肤状态（无存档零帧；收集位图 + 挂载行 + 显示开关）。 */
    void pushSavedSkins(Connection connection, LoginSession session) {
        session.skinsPushed = true;
        if (session.selected == null) {
            return;
        }
        if (session.skinState == null) {
            session.skinState = skinStore.load(session.selected.id());
        }
        com.nineyin.server.game.avatar.skin.SkinStore.State state = session.skinState;
        if (state == null || (state.collected().isEmpty() && state.mounted().isEmpty())) {
            return;
        }
        for (byte[] frame : skinRefreshFrames(session, state.collected(), state.mounted())) {
            connection.writeFrame(frame);
        }
        log.info("session={} pushed saved skins collected={} mounted={}",
                session.machine.id(), state.collected().size(), state.mounted().size());
    }

    /** 风物志皮肤整刷帧组：清旧收集位图行 → 重推位图（显式行号）→ 挂载行 → 显示开关。 */
    private List<byte[]> skinRefreshFrames(LoginSession session,
                                           List<Integer> collected, List<Integer> mounted) {
        int objectId = SceneMessages.PLAYER_OBJECT_ID;
        int ownerId = SceneMessages.PLAYER_OWNER_ID;
        List<byte[]> frames = new ArrayList<>();
        frames.addAll(SkinMessages.collectedClearFrames(objectId, ownerId, session.skinRows));
        frames.addAll(SkinMessages.collectedFrames(objectId, ownerId, collected));
        frames.addAll(SkinMessages.unmountAllFrames(objectId, ownerId, session.skinMountedRows));
        frames.addAll(SkinMessages.mountedFrames(objectId, ownerId, mounted));
        int lastSkin = mounted.isEmpty() ? 0 : mounted.get(mounted.size() - 1);
        frames.add(SkinMessages.showEquipTypeFrame(objectId, ownerId,
                mounted.isEmpty()
                        ? SkinMessages.SHOW_EQUIP_TYPE_NORMAL : SkinMessages.SHOW_EQUIP_TYPE_SKIN,
                lastSkin));
        frames.add(SkinMessages.skinMsgFrame(lastSkin));
        session.skinRows = SkinMessages.collectedRowCount(collected);
        session.skinMountedRows = mounted.size();
        return frames;
    }

    /** 登录期帽/时装外观 0x10 补帧（无帽且无时装零帧，帧序对齐 golden 基线）。 */
    private void pushEquipAppearance(Connection connection, LoginSession session,
                                     List<RoleInventoryStore.Equip> equips) {
        if (equips.isEmpty()) {
            return;
        }
        var models = AppearanceSync.wornModels(session.visual.sex(), equips, EquipmentResolver.loadDefault());
        boolean overlay = models.containsKey(AppearanceSync.SLOT_HAT)
                || models.containsKey(AppearanceSync.SLOT_FASHION_COAT)
                || models.containsKey(AppearanceSync.SLOT_FASHION_HAT)
                || models.containsKey(AppearanceSync.SLOT_FASHION_SHOES);
        if (!overlay) {
            return;
        }
        connection.writeFrame(AppearanceSync.appearanceFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                session.visual.sex(), equips, EquipmentResolver.loadDefault()));
    }

    /** ClientReady 推送存档的个性配置（无存档零帧；客户端保持默认并自行上传基线）。 */
    void pushSavedCustomizing(Connection connection, LoginSession session) {
        session.customizingPushed = true;
        if (session.selected == null) {
            return;
        }
        String saved;
        try {
            saved = customizingStore.load(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("load customizing: {}", e.getMessage());
            return;
        }
        if (saved == null || saved.isEmpty()) {
            return;
        }
        connection.writeFrame(customizingFrame(saved));
        log.info("session={} pushed saved customizing config {} bytes", session.machine.id(),
                saved.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    /**
     * C2S 107 个性配置上传（引擎 customizing_manager:SaveConfigToServer，`[107, configStr]`）。
     * 时序闸：客户端登录期会先上传**默认键位**（此时未必已应用恢复帧）——会话首个
     * 上传若库里有存档则跳过不落库（防默认覆盖玩家配置），其余上传全部落档；
     * 两条路径都回发 705（F 同款回显，客户端幂等重灌/再确认恢复态）。
     */
    void handleCustomizingUpload(Connection connection, LoginSession session,
                                         CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return; // 选角前的上传无法归属角色，忽略
        }
        String config = custom.values.size() > 1 ? custom.values.get(1).textOrNull() : null;
        if (config == null || config.isEmpty()) {
            return;
        }
        long roleId = session.selected.id();
        String stored = null;
        try {
            stored = customizingStore.load(roleId);
        } catch (RuntimeException e) {
            log.warn("load customizing: {}", e.getMessage());
        }
        boolean firstUpload = !session.customizingSeenUpload;
        session.customizingSeenUpload = true;
        if (firstUpload && stored != null && !stored.isEmpty()) {
            // 恢复前/中的默认键位转储：跳过落库，回发存档再确认恢复态。
            connection.writeFrame(customizingFrame(stored));
            log.info("session={} skipped default customizing dump ({} bytes), re-asserted saved {} bytes",
                    session.machine.id(),
                    config.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            return;
        }
        try {
            customizingStore.save(roleId, config);
        } catch (RuntimeException e) {
            log.warn("persist customizing: {}", e.getMessage());
            return;
        }
        connection.writeFrame(customizingFrame(config));
        log.info("session={} saved customizing config {} bytes", session.machine.id(),
                config.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
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
