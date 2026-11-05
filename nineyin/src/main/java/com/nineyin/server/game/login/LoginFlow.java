package com.nineyin.server.game.login;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;

import com.nineyin.server.game.avatar.appearance.AppearanceSync;
import com.nineyin.server.game.avatar.origin.OriginCatalog;
import com.nineyin.server.game.avatar.skin.SkinMessages;
import com.nineyin.server.game.avatar.equipment.EquipmentResolver;
import com.nineyin.server.game.avatar.equipment.ToolItemCatalog;
import com.nineyin.server.game.avatar.equipment.RecastCatalog;
import com.nineyin.server.game.item.DropPacks;
import com.nineyin.server.game.item.ItemUseCatalog;
import com.nineyin.server.protocol.EquipmentModels;
import com.nineyin.server.protocol.InventoryMessages;
import com.nineyin.server.world.fight.SkillCastHandler;
import com.nineyin.server.world.fight.FightState;
import com.nineyin.server.world.npc.NpcDialog;
import com.nineyin.server.world.npc.WorldBossRefreshTable;
import com.nineyin.server.world.npc.NpcSpawn;
import com.nineyin.server.world.npc.OptionTalkCatalog;
import com.nineyin.server.world.npc.SceneNpcRegistry;
import com.nineyin.server.world.npc.ShopCatalog;
import com.nineyin.server.world.scene.CloneScenes;
import com.nineyin.server.world.scene.NpcViewport;
import com.nineyin.server.world.scene.SceneLifecycle;
import com.nineyin.server.protocol.NpcMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nineyin.server.game.avatar.neigong.FacultyStore;
import com.nineyin.server.game.avatar.neigong.PlayerProgress;
import com.nineyin.server.game.avatar.Actor;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.game.avatar.RoleVisual;
import com.nineyin.server.game.avatar.neigong.FacultyCustomHandler;
import com.nineyin.server.game.avatar.panel.PanelState;
import com.nineyin.server.game.starter.StarterGrants;
import com.nineyin.server.game.starter.StarterSkills;
import com.nineyin.server.world.db.RoleInventoryStore;
import com.nineyin.server.world.db.PanelDataStore;
import com.nineyin.server.game.starter.StarterQingGong;
import com.nineyin.server.gm.GmBotPresets;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmFactionPresets;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.gm.GmWeatherPresets;
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
import com.nineyin.server.script.LuaScriptService;
import com.nineyin.server.transport.Connection;

/**
 * 登录链消息分支，逐行移植 Go cmd/protocol-probe/main.go handle() 的
 * 0x02/0x03/0x05/0x04 与 0x09/0x0A 状态机处理。返回 false 表示断连（Go return）。
 *
 * <p>一期边界：0x09/0x0A 只做相位转移 + vital 回包；starter 链与 NPC 物化属二期。
 */
public final class LoginFlow {

    private static final Logger log = LoggerFactory.getLogger(LoginFlow.class);

    /** 240(2) 坐姿回执的重推闸：迁移后首条立即重推，之后 2s 内静默保坐（防重入动画风暴）。 */
    private static final long SITCROSS_RESYNC_GATE_MS = 2_000L;

    static final int STARTER_SILVER = 99999;
    /** 选人界面实力档位种子（一期无实力持久化，固定 151=title027 返璞归真）。 */
    static final int ROLE_LIST_POWER_LEVEL = 151;
    /** Go -npc-radius 默认 120（巡逻已换 mappath 官方路线，无数量上限）。 */
    static final float NPC_RADIUS = 120.0f;
    /** 0x1C 菜单项类型字节（语义未证，统一 0——真机待验）。 */
    static final int MENU_ITEM_TYPE = 0;
    /** 对话「告辞」项文本 key（interface.idres：ui_form_talk_leave=告辞，F 服抓包同款）。 */
    static final String TALK_LEAVE_TEXT_KEY = "ui_form_talk_leave";
    /** 商店视图 View61（view_define.lua；容量 100 = F 服抓包 createview cap）。 */
    static final int SHOP_VIEW_ID = 61;
    static final int SHOP_VIEW_CAPACITY = 100;
    /** View61 每页商品行数（F 服抓包 PageNum=10；shopconfig.ini 的 500 是另一口径）。 */
    static final int SHOP_ROWS_PER_PAGE = 10;
    /** 礼包测试商人货架节（合成节，shop.ini 之外；商品=真正可开的礼包，免费领）。 */
    static final String GIFT_SHOP_ID = "gm_giftbox";
    /** View61 协商属性号（§4.2 追加表 101-111）。 */
    static final int PROP_SHOP_ID = 101;
    static final int PROP_PAGE_NUM = 102;
    static final int PROP_PAGE_COUNT = 103;
    static final int PROP_SHOP_TYPE = 104;
    static final int PROP_GOODS_CONFIG_ID = 105;
    static final int PROP_GOODS_AMOUNT = 106;
    static final int PROP_SELL_PRICE0 = 107;
    static final int PROP_SELL_PRICE1 = 108;
    static final int PROP_SELL_PRICE2 = 109;
    static final int PROP_MAX_AMOUNT = 110;
    /** GM 切图 0x0C → 0x0B 的间隔（Go scene_transition.go 实测 12ms 内必丢新玩家，取 750ms）。 */
    static final long REENTRY_FRAME_DELAY_MS = 750;
    /** 测试可注入的延迟覆盖（生产恒 {@link #REENTRY_FRAME_DELAY_MS}）。 */
    long reentryFrameDelayMillis = REENTRY_FRAME_DELAY_MS;
    /** Go 位置保存节流（≥2s）。 */
    static final Duration POSITION_SAVE_INTERVAL = Duration.ofSeconds(2);

    private final RoleStoreFacade roleStore;
    private final FacultyStore facultyStore;
    /** 经脉修炼存档（data/meridians.json 主档；DB 经 {@link #attachMeridianDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.jingmai.MeridianStore meridianStore =
            com.nineyin.server.game.avatar.jingmai.MeridianStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "meridians.json"));
    /** 任务存档（data/tasks.json 主档；DB 经 {@link #attachTaskDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.task.TaskStore taskStore =
            com.nineyin.server.game.avatar.task.TaskStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "tasks.json"));
    /** 邮件存档（data/letters.json 主档；DB 经 {@link #attachLetterDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.mail.LetterStore letterStore =
            com.nineyin.server.game.avatar.mail.LetterStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "letters.json"));
    /** 个性配置存档（data/customizing.json 主档；DB 经 {@link #attachCustomizingDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.customizing.CustomizingStore customizingStore =
            com.nineyin.server.game.avatar.customizing.CustomizingStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "customizing.json"));
    /** 风物志皮肤存档（data/skins.json 主档；DB 经 {@link #attachSkinDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.skin.SkinStore skinStore =
            com.nineyin.server.game.avatar.skin.SkinStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "skins.json"));
    /** V4 外观偏好存档（data/appearance.json 主档；DB 经 {@link #attachAppearanceDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.appearance.AppearanceStore appearanceStore =
            com.nineyin.server.game.avatar.appearance.AppearanceStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "appearance.json"));
    /** 坐骑存档（data/坐骑.json 主档；DB 经 {@link #attachMountDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.mount.MountStore mountStore =
            com.nineyin.server.game.avatar.mount.MountStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "坐骑.json"));
    /** 待机站姿存档（data/待机站姿.json 主档；DB 经 {@link #attachIdleStanceDatabase} 可选挂接）。 */
    private final com.nineyin.server.game.avatar.stance.IdleStanceStore idleStanceStore =
            com.nineyin.server.game.avatar.stance.IdleStanceStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "待机站姿.json"));
    /** 武学等级存档（data/武学等级.json 主档，learn_all_taolu 落档/forget_all_taolu 清档；role_skills 无 level 列不动 DB）。 */
    private final com.nineyin.server.game.avatar.wuxue.SkillLevelStore skillLevelStore =
            com.nineyin.server.game.avatar.wuxue.SkillLevelStore.open(
                    com.nineyin.util.ServerPaths.projectPath("data", "武学等级.json"));
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
    /** 礼包商人合成货架（懒构建；资源目录 reload_scripts 后进程内复用）。 */
    private volatile ShopCatalog.ShopSection giftShopSection;

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

    /** 任务存档 DB 双源挂接（role_tasks 快照；失败仅告警，JSON 仍为主档）。 */
    public void attachTaskDatabase(String dsn) {
        try {
            taskStore.attachDatabase(dsn);
            log.info("task store attached to MySQL role_tasks");
        } catch (RuntimeException e) {
            log.warn("task store MySQL unavailable, JSON only: {}", e.getMessage());
        }
    }

    /** 邮件存档 DB 双源挂接（role_letters 快照；失败仅告警，JSON 仍为主档）。 */
    public void attachLetterDatabase(String dsn) {
        try {
            letterStore.attachDatabase(dsn);
            log.info("letter store attached to MySQL role_letters");
        } catch (RuntimeException e) {
            log.warn("letter store MySQL unavailable, JSON only: {}", e.getMessage());
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

    /** V4 外观偏好存档 DB 双源挂接（role_appearance；失败仅告警，JSON 仍为主档）。 */
    public void attachAppearanceDatabase(String dsn) {
        try {
            appearanceStore.attachDatabase(dsn);
            log.info("appearance store attached to MySQL role_appearance");
        } catch (RuntimeException e) {
            log.warn("appearance store MySQL unavailable, JSON only: {}", e.getMessage());
        }
    }

    /** 坐骑存档 DB 双源挂接（role_mounts；失败仅告警，JSON 仍为主档）。 */
    public void attachMountDatabase(String dsn) {
        try {
            mountStore.attachDatabase(dsn);
            log.info("mount store attached to MySQL role_mounts");
        } catch (RuntimeException e) {
            log.warn("mount store MySQL unavailable, JSON only: {}", e.getMessage());
        }
    }

    /** 待机站姿存档 DB 双源挂接（role_idle_stance；失败仅告警，JSON 仍为主档）。 */
    public void attachIdleStanceDatabase(String dsn) {
        try {
            idleStanceStore.attachDatabase(dsn);
            log.info("idle stance store attached to MySQL role_idle_stance");
        } catch (RuntimeException e) {
            log.warn("idle stance store MySQL unavailable, JSON only: {}", e.getMessage());
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
     * 0x04 帧构造：外观按显示类型取模型（{@link #roleListVisual}）；f7 显示类型
     * = 有效 ShowEquipType（皮肤挂载=2，否则存档偏好；无存档保持原值 "1"）；
     * f23 实力称号（一期种子 151=title027）、f24 角色实际所在场景中文名
     * （scenes.ini+desc.idres）、f25 剧本标题键（introduction_*01_title），
     * f32 保持 book 原值。
     */
    private byte[] oneRoleWithVisual(LoginSession session) {
        List<String> appearance = session.selected.appearance().values();
        loadAppearancePrefs(session);
        RoleVisual visual = roleListVisual(session, appearance, loadEquips(session));
        String book = appearance.isEmpty() || appearance.get(0).isEmpty() ? "book1" : appearance.get(0);
        Roles.Location location = session.selected.location();
        return LoginMessages.oneRoleLoginSuccess(java.time.LocalDateTime.now(clock),
                session.selected.name(), appearance,
                RoleStoreFacade.roleFaction(appearance),
                visual.hair(), visual.cloth(), visual.pants(), visual.shoes(),
                LoginMessages.roleListTitle(ROLE_LIST_POWER_LEVEL),
                SceneNames.sceneDisplayName(location.scene().config(), location.scene().resource()),
                SceneNames.bookIntroKey(book),
                session.showEquipTypePref);
    }

    /** 首访问装载外观偏好/皮肤存档（0x04 与选角共用；onChooseRole 处幂等重载）。 */
    private void loadAppearancePrefs(LoginSession session) {
        if (session.selected == null) {
            return;
        }
        if (session.showEquipTypePref == null) {
            try {
                session.showEquipTypePref = appearanceStore.load(session.selected.id());
            } catch (RuntimeException e) {
                log.warn("session={} load appearance pref: {}", session.machine.id(), e.getMessage());
            }
        }
        sessionSkinState(session);
    }

    /**
     * 选角列表外观（rolePara f9/f10/f11）按显示类型取模型：江湖装 =
     * 已穿衣/裤/鞋（role_equip_items）模型覆盖创角初始装，与进世界
     * {@link AppearanceSync#mergeVisual} 同口径——选角界面与游戏内外观一致；
     * 身份装/时装 = {@link AppearanceSync#finalModels} 三模式裁决；皮肤挂载
     * （有效类型 2）= card.ini 部件模型（客户端本地无 CardID 通道可用）。
     */
    static RoleVisual roleListVisual(LoginSession session, List<String> appearance,
                                     List<RoleInventoryStore.Equip> equips) {
        RoleVisual merged = roleListVisual(appearance, equips);
        int effective = effectiveShowEquipType(session);
        if (effective == AppearanceSync.SHOW_EQUIP_GEAR) {
            return merged;
        }
        Map<String, String> finals;
        if (effective == AppearanceSync.SHOW_EQUIP_FASHION
                && session.skinState != null && !session.skinState.mounted().isEmpty()) {
            finals = skinCardModels(session.skinState.mounted(), merged.sex());
        } else {
            finals = AppearanceSync.finalModels(effective, merged.sex(), equips,
                    EquipmentResolver.loadDefault());
        }
        return new RoleVisual(merged.sex(), merged.photo(), merged.face(), merged.hair(),
                finals.getOrDefault(AppearanceSync.PART_CLOTH, merged.cloth()),
                finals.getOrDefault(AppearanceSync.PART_PANTS, merged.pants()),
                finals.getOrDefault(AppearanceSync.PART_SHOES, merged.shoes()),
                merged.actionSet());
    }

    /** 江湖装基准（装备模型覆盖创角初始装；session 变体的 gear 分支与测试共用）。 */
    static RoleVisual roleListVisual(List<String> appearance, List<RoleInventoryStore.Equip> equips) {
        EquipmentResolver resolver = EquipmentResolver.loadDefault();
        RoleVisual base = RoleVisual.resolve(appearance, resolver);
        return AppearanceSync.mergeVisual(base, base.sex(), equips, resolver);
    }

    /** 挂载皮肤 → card.ini 部件模型（后挂覆盖同部件；Sex 无数据回退另一性别）。 */
    private static Map<String, String> skinCardModels(List<Integer> mounted, int sex) {
        Map<String, String> models = new HashMap<>();
        var catalog = com.nineyin.server.game.avatar.skin.SkinCatalog.loadDefault();
        for (int skinId : mounted) {
            var skin = catalog.byId(skinId);
            if (skin == null) {
                continue;
            }
            for (var partModel : skin.models(sex)) {
                String part = partModel.part();
                if (AppearanceSync.PART_CLOTH.equalsIgnoreCase(part)
                        || AppearanceSync.PART_PANTS.equalsIgnoreCase(part)
                        || AppearanceSync.PART_SHOES.equalsIgnoreCase(part)) {
                    models.put(part.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + part.substring(1).toLowerCase(java.util.Locale.ROOT),
                            partModel.model());
                }
            }
        }
        return models;
    }

    /** 有效显示类型：皮肤挂载=2（皮肤模型优先）＞存档偏好；无存档 0=江湖装。 */
    static int effectiveShowEquipType(LoginSession session) {
        if (session.skinState != null && !session.skinState.mounted().isEmpty()) {
            return AppearanceSync.SHOW_EQUIP_FASHION;
        }
        if (session.showEquipTypePref == null) {
            return AppearanceSync.SHOW_EQUIP_GEAR;
        }
        int pref = session.showEquipTypePref;
        return pref >= 0 && pref <= 2 ? pref : AppearanceSync.SHOW_EQUIP_GEAR;
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
        // 任务存档（已接/已完成；无档种子空档，恢复帧随 starter 链后下发）。
        var savedTasks = taskStore.load(session.selected.id());
        session.player.restoreTask(savedTasks);
        if (savedTasks == null) {
            try {
                taskStore.save(session.selected.id(), session.player.taskSnapshot());
            } catch (RuntimeException e) {
                log.warn("seed task store: {}", e.getMessage());
            }
        }
        // 邮件存档（RecvLetterRec 行镜像；无档=空邮箱不落种子档，首次发信才建）。
        var savedLetters = letterStore.load(session.selected.id());
        session.letters.clear();
        if (savedLetters != null) {
            session.letters.addAll(savedLetters);
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
        session.gmSpawns.clear();
        session.giftMerchantObjectId = null;
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
        connection.writeFrame(session.player.addObject(location.position(), session.visual,
                new PlayerActor.KeybindArchive(loadKeybind(session), loadOperateMode(session)),
                weaponVisual(session, equips)));
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
        // 坐骑：装载拥有存档（ClientReady 推 NewRideRec 行 + NewRideID；骑乘态不跨登录）。
        session.mountState = mountStore.load(session.selected.id());
        // 待机站姿：装载存档（null=默认；ClientReady 后按需重推保持所选站姿）。
        session.idleStance = idleStanceStore.load(session.selected.id());
        // 时装页签：已激活套装装载（role_fashion_suits；ClientReady 推 FashionSuitRec 行）。
        if (inventoryStore != null) {
            try {
                session.fashionSuits = inventoryStore.fashionSuits(session.selected.id());
            } catch (RuntimeException e) {
                log.warn("session={} load fashion suits: {}", session.machine.id(), e.getMessage());
            }
        }
        // V4 外观偏好：ShowEquipType 存档装载（无存档 null=默认江湖装）。
        loadAppearancePrefs(session);
        // V4 身份装：按角色身份自动激活所属门派/势力/新门派段（幂等，世家/帮会/生活/活动不自动）。
        grantIdentitySuitsAtLogin(session);
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
                        session.activeNpcs, location.position(), NPC_RADIUS, cloneWaveGate(session));
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

    /** 操作模式（role_keybinds.operate_mode，0=3D WASD/1=2.5D 点击）；未设置回 null。 */
    private Integer loadOperateMode(LoginSession session) {
        if (inventoryStore == null || session.selected == null) {
            return null;
        }
        try {
            return inventoryStore.operateMode(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load operate mode: {}", session.machine.id(), e.getMessage());
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

    /** 已穿武器 → Weapon/ShotWeapon/ActionSet 三属性（addObject 归档与 0x10 推送共用）。 */
    private InventoryMessages.WeaponVisual weaponVisual(LoginSession session,
                                                        List<RoleInventoryStore.Equip> equips) {
        return InventoryMessages.weaponVisualOf(
                session.visual == null ? 0 : session.visual.sex(), equips,
                EquipmentResolver.loadDefault());
    }

    /** 装备词缀（propBuilt）+ 武器特效（EffectPackID）整层重算并同步有效上限（幂等）。 */
    private void applyEquipBonuses(LoginSession session, List<RoleInventoryStore.Equip> equips) {
        if (session.player == null) {
            return;
        }
        List<String> propBuilts = new java.util.ArrayList<>(equips.size());
        List<Integer> effectPackIds = new java.util.ArrayList<>(equips.size());
        int weaponItemType = 0;
        for (RoleInventoryStore.Equip equip : equips) {
            propBuilts.add(equip.propBuilt() == null ? "" : equip.propBuilt());
            EquipmentModels.ItemStatic stat = EquipmentModels.itemStatic(equip.configId());
            effectPackIds.add(stat == null ? 0 : stat.effectPackId());
            // 武器族 ItemType（equipment.ini 实测 101-119：兵器 101-108 + 暗器 111-117）
            if (weaponItemType == 0 && equip.itemType() >= 101 && equip.itemType() <= 119) {
                weaponItemType = equip.itemType();
            }
        }
        session.player.recomputeEquipBonuses(propBuilts, effectPackIds);
        session.player.noteEquippedWeaponItemType(weaponItemType);
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
            // 声望行镜像（Repute_Record 服务端行序）+ 汇总（与 reputeTotal 同源，
            // 一次装载两用；GM repute_apply 后内存即权威）。
            session.reputes = panelStore.reputeRows(roleId);
            long total = 0;
            for (long value : session.reputes.values()) {
                total += value;
            }
            panel.prestigeExp = (int) Math.min(Integer.MAX_VALUE, total);
        } catch (RuntimeException e) {
            log.warn("session={} load repute: {}", session.machine.id(), e.getMessage());
        }
        try {
            // 身份集合镜像（Origin_Active/Origin_Prized 行序；1=激活 2=已领奖）。
            session.origins = panelStore.originRows(roleId);
        } catch (RuntimeException e) {
            log.warn("session={} load origins: {}", session.machine.id(), e.getMessage());
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
                // 官方分档轻功回复先结算再推帧（101-150 每3s+10 / 51-100 +5 / 0-50 +3）；
                // 缓降/持续轻功租约消耗中暂停。
                session.player.qingGongRecoveryTick(clock.instant());
                // F 抓包同拍：294 后随轻功点四元组（QingGongPoint/GJ 三项，61579 #2192）。
                connection.writeFrame(session.player.qingGongHeartbeatFrame());
                facultyTickOnce(connection, session);
                meridianTickOnce(connection, session);
                com.nineyin.server.world.fight.QingGongHandler.glideTick(connection, session.player);
                com.nineyin.server.world.fight.QingGongHandler.driftTick(connection, session.player);
                // 打坐回复（Go sitcrossTick：10s 回满 HP/MP，HPUpSpeed/MPHeartSpeed 同源展示）。
                // 回帧走 6 值瘦帧（参考 52905 #5455）：坐姿中重发全量 LogicState 面板会
                // 让客户端重入坐姿动画（2026-11-05 打坐动画不落定根因）。
                if (session.player.sitcrossTick()) {
                    connection.writeFrame(session.player.sitcrossVitalFrame());
                    log.info("session={} settled sitcross HP={}/{} MP={}/{}",
                            session.machine.id(),
                            session.player.actorState().hp(), session.player.actorState().maxHp(),
                            session.player.actorState().mp(), session.player.actorState().maxMp());
                }
                // 格挡值回复（松开格挡后每秒 +ParryUpSpeed；按住不回复）。
                if (session.player.parryRecoveryTick()) {
                    connection.writeFrame(session.player.parryValueFrame());
                }
                // 待机站姿保持（每 4s 一拍）：受击/挖矿/技能结束会打断引擎动作，
                // 闲时（LogicState=0 且不在马上/打坐）重推 PlayActionName 回站。
                idleStanceTick(connection, session);
                // 伙伴拍（bot_* 域）：世代失效重生（跨场景跟随）+ 0x21 跟随 + 周期辅助。
                companionTick(connection, session);
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
            // 门派声望（面板「门派声望（自定义）」区块）：闭名单 = repute_level.ini
            // [Repute]；set/add + all/单阵营 → Repute_Record 行整刷 + PrestigeExp 单帧
            // + role_repute upsert；重登/切图按内存镜像整表重放。
            case "repute_apply" -> applyReputeApply(connection, session, command);
            // 身份系统（面板「身份系统（一键激活全部）」区块）：origin.ini 全量 →
            // Origin_Active 记录行 + role_origins 落库；领奖走 C2S 702（玩家手动点）。
            case "origin_activate_all" -> applyOriginActivateAll(connection, session, command);
            // 基础五维覆盖式（0/缺省跳过该维）：0x10 全量随帧 + faculty 快照落库。
            case "set_five_attributes" -> applyFiveAttributes(connection, session, command);
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
            // 风物志头发：仅纯发型卡（hairOnly），挂载前互斥卸下其他 Hat-only 卡。
            case "equip_hair" -> applyEquipHair(connection, session, command);
            case "unequip_card" -> applyUnmountSkins(connection, session, command);
            case "grant_all_cards" -> applyGrantAllSkins(connection, session, command);
            // 配套：模板生成武器/防具/护腕（官方洗练池词条）+ 一键激活全部时装。
            case "gm_generate_weapon" -> applyGenerateWeapon(connection, session, command);
            case "gm_generate_armor" -> applyGenerateArmor(connection, session, command);
            case "gm_generate_bracer" -> applyGenerateBracer(connection, session, command);
            case "grant_all_fashion" -> applyGrantAllFashion(connection, session, command);
            case "set_showequip_type" -> applySetShowEquipType(connection, session, command);
            case "grant_identity_suits" -> applyGrantIdentitySuits(connection, session, command);
            // 门派切换（Go set_faction 同款）：闭名单预设 → saveFaction CAS 落库（appearance[8]）
            // → 内存 faction + School/Force/NewSchool 单帧 → 面板快照。不自动赠身份装/套路。
            case "set_faction" -> applyFaction(connection, session, command);
            // 传送三动作（Go sceneRuntime）：预设切图=常用传送按钮，自定义切图=免费格式目的地，
            // teleport=场景内瞬移（仅 0x1F，不读图）。切图走 0x0C 重入链，行恢复在目标场景 0x09。
            case "switch_scene_preset" -> applySwitchScenePreset(connection, session, command);
            case "switch_scene" -> applySwitchScene(connection, session, command);
            case "teleport" -> applyTeleportWithinScene(connection, session, command);
            // 副本三动作（面板「副本」区块）：进本=切图+波次链起跑（NpcProgress 累积揭示、
            // 副本怪击杀不复活、当前波全灭自动推进）；重进=保留波次回本；销毁=回进本前
            // 位置。会话即实例——每连接独立 world/目录，无需跨会话副本管理器。
            case "enter_clone" -> applyEnterClone(connection, session, command);
            case "reenter_clone" -> applyReenterClone(connection, session, command);
            case "destroy_clone" -> applyDestroyClone(connection, session, command);
            // 武学套路两动作（面板「武学套路」区块）：已学全部升满级（档值落
            // data/武学等级.json 重登保持、行帧刷等级/修为、CanUse 批按新级重建）；
            // 全遗忘回登录基线集（DB 模式=五件套，starter 模式=starter 集）。
            case "learn_all_taolu" -> applyLearnAllTaolu(connection, session, command);
            case "forget_all_taolu" -> applyForgetAllTaolu(connection, session, command);
            // 弹出游戏 GM 面板（GMCC）：客户端 lua close_gmcc_form msg_id=2 → open_gmcc_chat。
            case "popup_gm_panel" -> applyPopupGmPanel(connection, session, command);
            // 场景天气切换（on_weather_sky sub 4）：闭名单=sceneparastore 天气渲染包目录。
            case "set_weather" -> applyWeather(connection, session, command);
            // 洗练六动作（四池 senior_*，词条=proppack 包号）：随机/定向/抛光/招式开光/加词条/清空。
            case "recast_slot_prop", "recast_slot_dire", "recast_slot_polish", "recast_slot_skill" ->
                    applyRecastSlot(connection, session, command);
            case "recast_add_affix" -> applyRecastAddAffix(connection, session, command);
            case "recast_clear_affix" -> applyRecastClearAffix(connection, session, command);
            // 场景生成：训练木桩（AttackNpc 木桩模板克隆，血量自定义）+ 世界宝箱（点击开箱拾取）。
            case "spawn_dummy" -> applySpawnDummy(connection, session, command);
            case "spawn_chest" -> applySpawnChest(connection, session, command);
            // 战斗假人（bak spawn_combat_dummy 移植）：人形机关木人，命中后经 NpcCombatAi 还手；
            // 配套一键清除本会话生成的全部假人。
            case "spawn_combat_dummy" -> applySpawnCombatDummy(connection, session, command);
            case "clear_combat_dummies" -> applyClearCombatDummies(connection, session, command);
            // 世界 BOSS（worldboss_*）：官方刷新表驱动——当前场景门派固定点每点 1 只 +
            // 场景池命中的江湖 BOSS 随机点，bossnpc 模板（BossNpc 进战斗反击链）；
            // 击杀后消失不重生（bossKillOverride），clear/list 按会话摆放记账。
            case "worldboss_spawn" -> applyWorldBossSpawn(connection, session, command);
            case "worldboss_clear" -> applyWorldBossClear(connection, session, command);
            case "worldboss_list" -> applyWorldBossList(connection, session, command);
            case "giftbox_merchant_here" -> applyGiftboxMerchantHere(connection, session, command);
            case "send_test_mail" -> applySendTestMail(connection, session, command);
            // 坐骑管理：按道具 config 骑乘（再点同骑=下马），NewRideRec 记录 + Mount 属性族下发。
            case "mount_ride" -> applyMountRide(connection, session, command);
            // 待机站姿（持久化）：空参=重推当前档；写档 + PlayActionName 属性下发。
            case "idle_stance_set" -> applyIdleStanceSet(connection, session, command);
            // 伙伴系统（bot_*，预设闭名单 GmBotPresets）：幂等创建/校验、拉到身边、
            // 跟随开关、复活回满、辅助配置；跟随/辅助由 1s ticker 的 companionTick 驱动。
            case "bot_ensure" -> applyBotEnsure(connection, session, command);
            case "bot_pull_here" -> applyBotPullHere(connection, session, command);
            case "bot_follow_start" -> applyBotFollow(connection, session, command, true);
            case "bot_follow_stop" -> applyBotFollow(connection, session, command, false);
            case "bot_revive" -> applyBotRevive(connection, session, command);
            case "bot_support_configure" -> applyBotSupportConfigure(connection, session, command);
            // 御风神水（大轻功）：挂 buf_ride_yufeng 10 分钟租约（msg169+BufferInfo 槽帧
            // 双通道，QingGongHandler 缓降同款）/摘除；到期自动摘由 scheduler 补发。
            case "yufeng_add" -> applyYufengAdd(connection, session, command);
            case "yufeng_remove" -> applyYufengRemove(connection, session, command);
            default -> {
                log.info("session={} unimplemented GM action {} (backlog)", session.machine.id(), command.action());
                command.fail("动作未实现: " + command.action());
            }
        }
    }

    /**
     * GM 常用传送（switch_scene_preset）：预设键 → 目的地，表见
     * {@link com.nineyin.server.world.scene.GmScenePresets}（Go 38 + menu2 exe 105 并集）。
     */
    private void applySwitchScenePreset(Connection connection, LoginSession session, GmCommand command) {
        String key = command.strParam("preset");
        com.nineyin.server.world.scene.GmScenePresets.Destination preset =
                com.nineyin.server.world.scene.GmScenePresets.preset(key);
        if (preset == null) {
            command.fail("未知传送预设: " + key);
            return;
        }
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        Roles.Location destination = new Roles.Location(preset.scene(), preset.position(),
                session.selected.location().version());
        applySceneSwitch(connection, session, command, destination, preset.label());
    }

    /** GM 自定义切图（switch_scene）：config,resource,x,y,z,orient 免费格式。 */
    private void applySwitchScene(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        Roles.Location destination;
        try {
            destination = com.nineyin.server.world.scene.GmScenePresets.parseDestination(
                    command.strParam("destination"));
        } catch (IllegalArgumentException e) {
            command.fail("目的地格式错误（应为 config,resource,x,y,z,orient）: " + e.getMessage());
            return;
        }
        destination = new Roles.Location(destination.scene(), destination.position(),
                session.selected.location().version());
        applySceneSwitch(connection, session, command, destination, destination.scene().resource());
    }

    /**
     * GM 进本（enter_clone）：{@link CloneScenes}（cloneinfo.ini）存在性校验 + 预取
     * 副本目录（入口坐标/最大波次）→ 建 {@link LoginSession.CloneRun}（会话即实例，
     * 快照回城点）→ 复用 {@link #applySceneSwitch} 切图（波次门控随 CloneRun 生效，
     * NpcProgress ≤ wave 累积揭示）。难度档 1普通/2困难/3地狱/4炼狱本期仅记录展示；
     * 进入不限次，会话内计数提示。
     */
    private void applyEnterClone(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        if (session.cloneRun != null) {
            command.fail("已有打开的副本「" + session.cloneRun.desc + "」，请先销毁或直接进入");
            return;
        }
        CloneScenes.CloneInfo info = CloneScenes.info(command.strParam("cloneConfig"));
        if (info == null) {
            command.fail("未知副本: " + command.strParam("cloneConfig"));
            return;
        }
        Roles.Scene scene = normalizeClientScene(cloneScene(info.config()));
        List<NpcSpawn> spawns = List.of();
        if (npcRegistry != null) {
            try {
                spawns = npcRegistry.catalogFor(scene).spawns();
            } catch (Exception e) {
                log.warn("session={} load clone NPC catalog {}: {}", session.machine.id(),
                        scene.resource(), e.getMessage());
            }
        }
        LoginSession.CloneRun run = new LoginSession.CloneRun(scene, info.desc(),
                command.intParam("value", 1), CloneScenes.maxWave(spawns),
                session.selected.location(), CloneScenes.entryPosition(scene, spawns));
        session.cloneRun = run;
        applySceneSwitch(connection, session, command,
                new Roles.Location(run.scene, run.entryPosition,
                        session.selected.location().version()),
                cloneLabel(run) + "，今日第 " + run.enterCount + " 次进入");
        if (!repliedOk(command)) {
            session.cloneRun = null; // 相位/调度器拒绝切图：回滚实例
            return;
        }
        log.info("session={} GM enter clone {} difficulty={} wave={}/{} npcs={}",
                session.machine.id(), info.config(), run.difficulty, run.wave,
                run.maxWave, spawns.size());
    }

    /**
     * GM 重进副本（reenter_clone）：普通切图离开不销毁实例（保留 CloneRun），本动作
     * 带着波次进度回到副本入口；已在副本场景则仅回报进度。世界世代重置后怪物满血
     * 重摆，波次门控按保留的 wave 揭示。
     */
    private void applyReenterClone(Connection connection, LoginSession session, GmCommand command) {
        LoginSession.CloneRun run = session.cloneRun;
        if (run == null) {
            command.fail("当前没有已打开的副本，请先创建并进入");
            return;
        }
        if (inCloneScene(session)) {
            command.succeed("已在" + cloneLabel(run));
            return;
        }
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        run.enterCount++;
        applySceneSwitch(connection, session, command,
                new Roles.Location(run.scene, run.entryPosition,
                        session.selected.location().version()),
                cloneLabel(run) + "，今日第 " + run.enterCount + " 次进入");
    }

    /**
     * GM 销毁副本（destroy_clone）：回进本前快照位置（正常落库），清实例与波次
     * 门控。人不在副本场景时同样销毁并送回快照点。
     */
    private void applyDestroyClone(Connection connection, LoginSession session, GmCommand command) {
        LoginSession.CloneRun run = session.cloneRun;
        if (run == null) {
            command.fail("当前没有已打开的副本");
            return;
        }
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        session.cloneRun = null; // 先清：回程切图的视口注册不受波次门控
        Roles.Location back = run.returnLocation;
        applySceneSwitch(connection, session, command,
                new Roles.Location(back.scene(), back.position(),
                        session.selected.location().version()),
                "原场景 " + back.scene().resource() + "（副本「" + run.desc + "」已销毁）");
    }

    /**
     * GM 一键学满武学套路（learn_all_taolu）：已学会的全部技能升到 varprop 满级
     * （{@link com.nineyin.server.world.fight.CombatSkillCatalog#maxLevelOf}），档值
     * 落 data/武学等级.json（重登保持）；逐行 0x10 刷 Level/MaxLevel/CurFill=
     * TotalFill/VarPropNo（按级 varprop 行号随级变）；CanUse 心跳批按新等级重建。
     * 出招链 definition(id, level) 即时按高级编译——伤害/消耗/CD 全生效。
     */
    private void applyLearnAllTaolu(Connection connection, LoginSession session, GmCommand command) {
        if (session.learnedSkills.isEmpty()) {
            command.fail("尚未学会任何武学");
            return;
        }
        long roleId = session.selected != null ? session.selected.id() : 0L;
        com.nineyin.server.world.fight.CombatSkillCatalog catalog =
                com.nineyin.server.world.fight.CombatSkillCatalog.load();
        java.util.Map<String, Integer> levels = new java.util.LinkedHashMap<>();
        int raised = 0;
        for (java.util.Map.Entry<String, Integer> entry : session.learnedSkills.entrySet()) {
            int max = catalog.maxLevelOf(entry.getKey());
            levels.put(entry.getKey(), max);
            if (max > entry.getValue()) {
                raised++;
            }
        }
        session.learnedSkills.putAll(levels);
        try {
            skillLevelStore.apply(roleId, levels);
        } catch (RuntimeException e) {
            log.warn("session={} persist wuxue levels: {}", session.machine.id(), e.getMessage());
        }
        for (java.util.Map.Entry<String, Integer> row : session.skillRows.entrySet()) {
            Integer level = levels.get(row.getKey());
            if (level == null || level <= 1) {
                continue;
            }
            com.nineyin.server.world.fight.CombatSkillCatalog.SkillRowData rowData =
                    catalog.skillRowData(row.getKey(), level);
            int totalFill = StarterSkills.totalFillDisplay(row.getKey());
            connection.writeFrame(FightMessages.skillRowLevelUp(
                    SkillCastHandler.SKILL_VIEW_ID, row.getValue(), level, level,
                    totalFill, totalFill,
                    rowData != null ? rowData.varPropNo() : null));
        }
        startSkillCanUseTicker(connection, session);
        String message = "已把 " + levels.size() + " 门武学升至满级（其中 " + raised + " 门升档）";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM learn all taolu skills={} raised={}",
                session.machine.id(), levels.size(), raised);
    }

    /**
     * GM 删除全部武学（forget_all_taolu）：回登录基线集——DB 模式=五件套基础武学
     * （{@link StarterSkills#defaultBasicSkills}，等价 role_skills 清空后重登），
     * starter 模式=starter 集。View40 整视图重开（0x16→0x15→0x18→CanUse 批），
     * 等级档清空，指向已遗忘技能的快捷栏槽同步摘除；role_skills 删行 best-effort。
     */
    private void applyForgetAllTaolu(Connection connection, LoginSession session, GmCommand command) {
        long roleId = session.selected != null ? session.selected.id() : 0L;
        boolean dbMode = loadDbSkills(session) != null;
        java.util.List<RoleInventoryStore.Skill> reset = dbMode
                ? StarterSkills.defaultBasicSkills() : null;
        if (dbMode && inventoryStore != null) {
            try {
                inventoryStore.deleteSkills(roleId, "wuxue");
            } catch (RuntimeException e) {
                log.warn("session={} delete role_skills wuxue rows: {}",
                        session.machine.id(), e.getMessage());
            }
        }
        try {
            skillLevelStore.clear(roleId);
        } catch (RuntimeException e) {
            log.warn("session={} clear wuxue level store: {}", session.machine.id(), e.getMessage());
        }
        session.learnedSkills = learnedSkillLevels(roleId, reset);
        session.skillRows = skillRows(reset);
        connection.writeFrame(SceneMessages.deleteView(SkillCastHandler.SKILL_VIEW_ID));
        if (reset != null && !reset.isEmpty()) {
            StarterGrants.grantWuxueSkillView(connection, reset, session.learnedSkills);
        } else {
            for (byte[] frame : StarterSkills.starterSkillViewFrames()) {
                connection.writeFrame(frame);
            }
        }
        for (java.util.Map.Entry<String, Integer> row : session.skillRows.entrySet()) {
            connection.writeFrame(FightMessages.skillRowCanUse(
                    SkillCastHandler.SKILL_VIEW_ID, row.getValue(), true));
        }
        startSkillCanUseTicker(connection, session);
        removeForgottenSkillShortcuts(connection, session);
        String message = "已删除全部武学，重置为" + (dbMode ? "基础五件套" : "初始套路集")
                + "（剩余 " + session.learnedSkills.size() + " 项）";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM forget all taolu -> {} skills (dbMode={})",
                session.machine.id(), session.learnedSkills.size(), dbMode);
    }

    /** 摘除指向已遗忘技能的快捷栏槽（msg201 同款：内存删 + 整表重推 + DB 删行）。 */
    private void removeForgottenSkillShortcuts(Connection connection, LoginSession session) {
        List<PlayerActor.PlayerShortcut> snapshot = session.player.shortcutSnapshot();
        int removed = 0;
        for (PlayerActor.PlayerShortcut shortcut : snapshot) {
            if (!"skill".equals(shortcut.kind())
                    || session.learnedSkills.containsKey(shortcut.id())) {
                continue;
            }
            if (session.player.removeShortcutIndex(shortcut.index()) != null) {
                removed++;
                if (inventoryStore != null && session.selected != null) {
                    try {
                        inventoryStore.deleteShortcut(session.selected.id(), shortcut.index());
                    } catch (RuntimeException e) {
                        log.warn("session={} delete forgotten shortcut {}: {}",
                                session.machine.id(), shortcut.index(), e.getMessage());
                    }
                }
            }
        }
        if (removed > 0) {
            rebuildShortcutRecord(connection, session.player, snapshot.size());
        }
    }

    /** 副本 config（cloneinfo.ini 节名）→ Scene：resource 取 config 基名（GmScenePresets 行口径）。 */
    private static Roles.Scene cloneScene(String config) {
        String clean = config.trim();
        int slash = Math.max(clean.lastIndexOf('\\'), clean.lastIndexOf('/'));
        String base = slash >= 0 ? clean.substring(slash + 1) : clean;
        return new Roles.Scene(clean, base);
    }

    /** 副本进度文案：名称 + 当前/最大波次 + 难度档。 */
    private static String cloneLabel(LoginSession.CloneRun run) {
        return "副本「" + run.desc + "」第 " + run.wave + "/" + run.maxWave
                + " 波(难度" + run.difficulty + ")";
    }

    /** 当前世界是否就是打开中的副本场景（config 归一比较；门控/击杀认领共用）。 */
    private static boolean inCloneScene(LoginSession session) {
        LoginSession.CloneRun run = session.cloneRun;
        if (run == null || session.world == null) {
            return false;
        }
        String current = session.world.sceneConfig();
        return current != null && current.trim().equalsIgnoreCase(run.scene.config().trim());
    }

    /** applySceneSwitch 家族同步回执已定（succeed/fail 在返回前完成）：取回是否成功。 */
    private static boolean repliedOk(GmCommand command) {
        try {
            GmCommand.Reply reply = command.reply().getNow(null);
            return reply != null && reply.ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 副本波次门控：仅在「打开中的副本且当前世界就是该副本场景」时按
     * NpcProgress ≤ wave 累积过滤（无 NpcProgress 恒可见），其余场景恒真。
     */
    static java.util.function.Predicate<NpcSpawn> cloneWaveGate(LoginSession session) {
        LoginSession.CloneRun run = session.cloneRun;
        if (run == null || !inCloneScene(session)) {
            return npc -> true;
        }
        return npc -> {
            Integer progress = CloneScenes.waveOf(npc);
            return progress == null || progress <= run.wave;
        };
    }

    /**
     * 副本目录击杀认领（killOverride 副本分支）：在副本场景且对象属目录小号段
     * （对象号=下标+2）→ 死后不复活（副本怪一次性），随后尝试波次推进。尸体保留
     * 在场（与常规死亡一致，只是无 15s 重生）。
     */
    boolean claimCloneKill(LoginSession session, int objectId) {
        if (!inCloneScene(session)) {
            return false;
        }
        int index = objectId - 2;
        if (index < 0 || index >= session.sceneNpcs.size()) {
            return false;
        }
        advanceCloneWave(session, session.cloneRun);
        return true;
    }

    /**
     * 波次推进：当前波（NpcProgress==wave）的战斗型目录 NPC（attacknpc/bossnpc）
     * 全部死亡（未物化视为存活——玩家需找齐击杀）→ wave+1 并按玩家最新位置对账
     * 视口（下一波 0x0D 上线）+ 面板回执；最终波全灭只标记通关一次。
     */
    void advanceCloneWave(LoginSession session, LoginSession.CloneRun run) {
        if (run == null || run.cleared || !cloneWaveCleared(session, run)) {
            return;
        }
        String message;
        if (run.wave >= run.maxWave) {
            run.cleared = true;
            message = "副本「" + run.desc + "」最终波已全灭，可继续探索或销毁副本回原场景";
        } else {
            run.wave++;
            message = cloneLabel(run) + "，新一波怪物现身";
        }
        Roles.Position position = session.pendingLocation != null
                ? session.pendingLocation.position() : run.entryPosition;
        try {
            boolean changed = NpcViewport.synchronizeNpcViewport(session.world, session.sceneNpcs,
                    session.activeNpcs, position, NPC_RADIUS, cloneWaveGate(session));
            if (changed) {
                session.world.reconcileViewport();
            }
        } catch (RuntimeException e) {
            log.warn("session={} materialize clone wave {}: {}", session.machine.id(),
                    run.wave, e.getMessage());
        }
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        log.info("session={} clone wave -> {}/{} ({})", session.machine.id(),
                run.wave, run.maxWave, run.desc);
    }

    /** 当前波战斗 NPC 是否全部死亡：NpcCombat hp≤0；未物化（未交战）视为存活。 */
    private boolean cloneWaveCleared(LoginSession session, LoginSession.CloneRun run) {
        for (int i = 0; i < session.sceneNpcs.size(); i++) {
            NpcSpawn spawn = session.sceneNpcs.get(i);
            Integer progress = CloneScenes.waveOf(spawn);
            if (progress == null || progress != run.wave
                    || !SceneLifecycle.isCombatScriptClass(spawn.resolved.scriptClass)) {
                continue;
            }
            FightState.NpcCombat combat = session.fight == null ? null : session.fight.npc(i + 2);
            if (combat == null || combat.state().hp() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Go sceneRuntime.SwitchScene 的在线切图链：0x0C 退场 → 世界世代重置 →（750ms，
     * 等旧场景 on_exit_scene 的 ClearAll 收尾）→ 0x0B 进场 + 0x0D 玩家归档；NPC
     * 目录/视口按目标场景重建（注册不上线）。0x1F 与 NPC 物化延迟到目标场景 0x09
     * （{@link #completeSceneReentry}），期间旧场景残余 0x0A 全部忽略。
     *
     * <p>Go 在会话 goroutine 里睡 750ms 保帧序；Java 的 GM 消费跑在 faculty ticker
     * 上不能睡，改调度延迟任务（awaitingReentryReady 先立、帧任务置 reentryFramesSent，
     * 早到的 0x09 在屏障前一并忽略——等价于 Go 阻塞读循环的排队语义）。
     */
    private void applySceneSwitch(Connection connection, LoginSession session, GmCommand command,
                                  Roles.Location destination, String label) {
        if (session.world == null || session.selected == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        if (scheduler == null) {
            command.fail("服务调度器未初始化，无法切图");
            return;
        }
        SessionException phaseError = session.machine.reenterScene();
        if (phaseError != null) {
            command.fail("切图失败：" + phaseError.getMessage());
            return;
        }
        destination = new Roles.Location(normalizeClientScene(destination.scene()),
                destination.position(), destination.version());
        if (npcRegistry != null) {
            try {
                session.sceneNpcs = npcRegistry.catalogFor(destination.scene()).spawns();
            } catch (Exception e) {
                log.warn("session={} load target-scene NPC catalog for {}: {}",
                        session.machine.id(), destination.scene().resource(), e.getMessage());
                session.sceneNpcs = List.of();
            }
        } else {
            session.sceneNpcs = List.of();
        }
        connection.writeFrame(SceneMessages.exitScene());
        session.world.begin(destination.scene().config(), destination.scene().resource());
        // 战斗一期：战斗态随场景世代重建（选角进世界同款）。
        session.fight = new com.nineyin.server.world.fight.FightState();
        session.activeNpcs.clear();
        session.gmSpawns.clear();
        session.giftMerchantObjectId = null;
        try {
            NpcViewport.synchronizeNpcViewport(session.world, session.sceneNpcs,
                    session.activeNpcs, destination.position(), NPC_RADIUS, cloneWaveGate(session));
        } catch (RuntimeException e) {
            log.warn("session={} register target-scene NPC viewport: {}",
                    session.machine.id(), e.getMessage());
        }
        session.awaitingReentryReady = true;
        session.reentryFramesSent = false;
        session.reentryLocation = destination;
        final Roles.Location target = destination;
        scheduler.schedule(() -> {
            try {
                connection.writeFrame(SceneMessages.entryScene(target.scene()));
                connection.writeFrame(session.player.addObject(target.position(), session.visual,
                        new PlayerActor.KeybindArchive(loadKeybind(session), loadOperateMode(session)),
                        weaponVisual(session, loadEquips(session))));
                session.reentryFramesSent = true;
            } catch (RuntimeException e) {
                log.warn("session={} write scene re-entry frames: {}",
                        session.machine.id(), e.getMessage());
            }
        }, reentryFrameDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        // 落库：沿用当前乐观版本（Go parseSceneDestination 无版本信息，同款保留 previous.Version）。
        if (roleStore != null) {
            try {
                session.selected = roleStore.saveLocation(session.selected, target);
                session.pendingLocation = null;
            } catch (RuntimeException e) {
                session.pendingLocation = target;
                log.warn("persist GM scene switch: {}", e.getMessage());
            }
        } else {
            session.pendingLocation = target;
        }
        gmHub.update(session.machine.id(), s -> {
            s.sceneConfig = target.scene().config();
            s.sceneResource = target.scene().resource();
            s.x = target.position().x();
            s.y = target.position().y();
            s.z = target.position().z();
            s.orient = target.position().orient();
            s.lastAction = "已传送到 " + label;
        });
        command.succeed("已发送切图到 " + label + "，等待目标场景加载");
        log.info("session={} GM switch scene config={} resource={} pos=({},{},{},{})",
                session.machine.id(), target.scene().config(), target.scene().resource(),
                target.position().x(), target.position().y(), target.position().z(),
                target.position().orient());
    }

    /**
     * GM 场景内瞬移（teleport）：仅 0x1F ServerLocation（Go TeleportWithinScene），
     * 不退场不读图；重入加载中拒绝。NPC 视口按新坐标对账。
     */
    private void applyTeleportWithinScene(Connection connection, LoginSession session, GmCommand command) {
        if (session.world == null || session.selected == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        if (session.awaitingReentryReady) {
            command.fail("目标场景仍在加载，等 Ready 后再瞬移");
            return;
        }
        Roles.Position position;
        try {
            position = com.nineyin.server.world.scene.GmScenePresets.parsePosition(
                    command.strParam("position"));
        } catch (IllegalArgumentException e) {
            command.fail("坐标格式错误（应为 x,y,z,orient）: " + e.getMessage());
            return;
        }
        Roles.Location location = new Roles.Location(session.selected.location().scene(),
                position, session.selected.location().version());
        connection.writeFrame(SceneMessages.serverLocation(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, position));
        try {
            boolean changed = NpcViewport.synchronizeNpcViewport(session.world, session.sceneNpcs,
                    session.activeNpcs, position, NPC_RADIUS, cloneWaveGate(session));
            if (changed) {
                session.world.reconcileViewport();
            }
        } catch (RuntimeException e) {
            log.warn("session={} synchronize teleported NPC viewport: {}",
                    session.machine.id(), e.getMessage());
        }
        savePosition(session, location, clock.instant());
        gmHub.update(session.machine.id(), s -> {
            s.x = position.x();
            s.y = position.y();
            s.z = position.z();
            s.orient = position.orient();
            s.lastAction = String.format(java.util.Locale.ROOT,
                    "已场景内瞬移到 (%.2f, %.2f, %.2f)", position.x(), position.y(), position.z());
        });
        command.succeed(String.format(java.util.Locale.ROOT,
                "已瞬移到 (%.2f, %.2f, %.2f)", position.x(), position.y(), position.z()));
        log.info("session={} GM teleport within scene to ({},{},{},{})", session.machine.id(),
                position.x(), position.y(), position.z(), position.orient());
    }

    /**
     * GM 弹出游戏 GM 面板（popup_gm_panel）：S2C custom 命令名寻址
     * {@code close_gmcc_form}，msg_id=2 → 客户端 form_gmcc_main.open_gmcc_chat()
     * 显示 GMCC 聊天面板（form_gmcc_chat）；msg_id=1 为关闭分支，本动作不用。
     * 取证：客户端 lua64 custom_handler.lua close_gmcc_form / form_gmcc_main.lua。
     */
    private void applyPopupGmPanel(Connection connection, LoginSession session, GmCommand command) {
        connection.writeFrame(CustomMessages.modernCustomCommandMessage(
                "close_gmcc_form", CustomMessages.Value.ofInt32(2)));
        gmHub.update(session.machine.id(), s -> s.lastAction = "已弹出游戏GM面板");
        command.succeed("已发送 GMCC 面板打开帧（close_gmcc_form msg_id=2）");
    }

    /**
     * GM 场景天气切换（set_weather）：S2C 0x27 命令名寻址 {@code on_weather_sky}
     * sub_type=4 → 客户端原生 {@code weather_manager:ChangeWeatherEffect(目录, 过渡秒,
     * 特效串, 4)}（weatherconfig.ini IsLua=0；Lua 模式 weather_set.lua 同参数收敛）。
     * arg1=SceneParaStore 天气渲染包目录（GmWeatherPresets 闭名单 49 键）、arg2=过渡
     * 秒（缺省 10）、arg3=TXID 特殊特效串（空=无）。雨粒子需客户端开天气+高/极高画质；
     * 恢复默认=切图重进（载图重初始化天气），服务端无干净恢复通道。
     * 取证：custom_handler.lua on_weather_sky / weather_set.lua start_weather。
     */
    private void applyWeather(Connection connection, LoginSession session, GmCommand command) {
        String key = command.strParam("weather");
        GmWeatherPresets.Weather weather = GmWeatherPresets.preset(key);
        if (weather == null) {
            command.fail("未知天气预设: " + key + "（仅 sceneparastore 渲染包目录名）");
            return;
        }
        Integer fadeSec = command.intParam("fadeSec");
        int fade = fadeSec != null ? fadeSec : 10;
        connection.writeFrame(CustomMessages.modernCustomCommandMessage("on_weather_sky",
                CustomMessages.Value.ofInt32(4),
                CustomMessages.Value.ofString(weather.key()),
                CustomMessages.Value.ofInt32(fade),
                CustomMessages.Value.ofString("")));
        gmHub.update(session.machine.id(), s -> s.lastAction = "天气已切换为 " + weather.label());
        command.succeed("已下发天气切换帧（" + weather.label() + "，" + fade + " 秒渐变）");
    }

    // ------------------------------------------------------------ 洗练六动作

    /**
     * 洗练四掷（recast_slot_*）：随机=整池重掷（保留重复权重）；定向=同属性族档序列内
     * 随机重掷数值（不跨族/不跨内功系）；抛光=同序列取最高档（末位）；招式开光=仅
     * weapon 池词条随机重掷、其余保留。词条串 propBuilt（"1,id;1,id"，PanelState 同方言）。
     */
    private void applyRecastSlot(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        Integer slot = command.intParam("slot");
        if (slot == null || slot < 1) {
            command.fail("slot 必须为正整数（目标部位）");
            return;
        }
        RoleInventoryStore.Equip item = equipAt(loadEquips(session), slot);
        if (item == null) {
            command.fail("槽位 " + slot + " 没有已穿戴装备");
            return;
        }
        List<Integer> packs = parsePropBuiltPacks(item.propBuilt());
        if (packs.isEmpty()) {
            command.fail("该部位没有词条，请先用「加入词条」或换其他部位");
            return;
        }
        RecastCatalog recast = RecastCatalog.load();
        if (recast.isEmpty()) {
            command.fail("洗练池为空（share/item/senior_*.ini 缺失）");
            return;
        }
        boolean weaponItem = isWeaponEquipType(item.equipType());
        java.util.Random random = sessionRandom(session);
        List<Integer> result = new ArrayList<>(packs.size());
        int changed = 0;
        int kept = 0;
        switch (command.action()) {
            case "recast_slot_prop" -> {
                List<Integer> pool = recast.randPool(item.configId(), weaponItem);
                if (pool.isEmpty()) {
                    command.fail("洗练池为空（share/item/senior_*.ini 缺失）");
                    return;
                }
                for (int i = 0; i < packs.size(); i++) {
                    result.add(pool.get(random.nextInt(pool.size())));
                    changed++;
                }
            }
            case "recast_slot_skill" -> {
                List<Integer> pool = recast.weaponPool(item.configId());
                for (int p : packs) {
                    if (recast.classify(p) == RecastCatalog.PoolKind.WEAPON && !pool.isEmpty()) {
                        result.add(pool.get(random.nextInt(pool.size())));
                        changed++;
                    } else {
                        result.add(p);
                        kept++;
                    }
                }
                if (changed == 0) {
                    command.fail("该装备没有招式词条（senior_weapon 池）可开光");
                    return;
                }
            }
            default -> {
                boolean polish = "recast_slot_polish".equals(command.action());
                for (int p : packs) {
                    int[] row = recast.direRowOf(p);
                    if (row == null || row.length == 0) {
                        result.add(p);
                        kept++;
                        continue;
                    }
                    result.add(polish ? row[row.length - 1] : row[random.nextInt(row.length)]);
                    changed++;
                }
                if (changed == 0) {
                    command.fail("该装备词条均不在 senior 洗练池内，无法定向/抛光");
                    return;
                }
            }
        }
        String label = switch (command.action()) {
            case "recast_slot_prop" -> "随机洗练";
            case "recast_slot_dire" -> "定向洗练";
            case "recast_slot_polish" -> "抛光";
            default -> "招式开光";
        };
        persistRecast(connection, session, command, item, result,
                label + "完成：" + item.configId() + " 槽" + slot + " 重掷 " + changed + " 条"
                        + (kept > 0 ? "（保留 " + kept + " 条）" : ""));
    }

    /** 加入词条（recast_add_affix）：勾选包号追加写入（不替换现有词条），包号须存在于 proppack。 */
    private void applyRecastAddAffix(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        Integer slot = command.intParam("slot");
        if (slot == null || slot < 1) {
            command.fail("slot 必须为正整数（目标部位）");
            return;
        }
        com.alibaba.fastjson2.JSONArray packIds = command.params().getJSONArray("packIDs");
        if (packIds == null || packIds.isEmpty()) {
            command.fail("packIDs 必须是非空词缀包号列表");
            return;
        }
        var affixes = com.nineyin.server.game.avatar.panel.EquipAffixCatalog.load();
        List<Integer> packs = new ArrayList<>(packIds.size());
        for (int i = 0; i < packIds.size(); i++) {
            Integer id = packIds.getInteger(i);
            if (id == null || id < 1) {
                command.fail("packIDs[" + i + "] 必须是正整数包号");
                return;
            }
            if (affixes.modsOf(id).isEmpty()) {
                command.fail("词缀包 " + id + " 不存在于 proppack.ini");
                return;
            }
            packs.add(id);
        }
        RoleInventoryStore.Equip item = equipAt(loadEquips(session), slot);
        if (item == null) {
            command.fail("槽位 " + slot + " 没有已穿戴装备");
            return;
        }
        List<Integer> merged = parsePropBuiltPacks(item.propBuilt());
        merged.addAll(packs);
        String kind = "skill".equals(command.strParam("affixKind")) ? "招式" : "普通";
        persistRecast(connection, session, command, item, merged,
                "已加入 " + packs.size() + " 条" + kind + "词条（现共 " + merged.size() + " 条）");
    }

    /** 清空词条（recast_clear_affix）：普通+招式全部移除（前端已二次确认）。 */
    private void applyRecastClearAffix(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        Integer slot = command.intParam("slot");
        if (slot == null || slot < 1) {
            command.fail("slot 必须为正整数（目标部位）");
            return;
        }
        RoleInventoryStore.Equip item = equipAt(loadEquips(session), slot);
        if (item == null) {
            command.fail("槽位 " + slot + " 没有已穿戴装备");
            return;
        }
        persistRecast(connection, session, command, item, List.of(),
                "已清空 " + item.configId() + " 槽" + slot + " 全部词条");
    }

    /** 洗练落地：词条串重写 → role_equip_items 落库 → 装备视图/战斗加成/vital 整层重刷。 */
    private void persistRecast(Connection connection, LoginSession session, GmCommand command,
                               RoleInventoryStore.Equip item, List<Integer> packs, String message) {
        RoleInventoryStore.Equip updated = new RoleInventoryStore.Equip(
                item.configId(), item.itemType(), item.equipType(), item.slot(),
                item.hardiness(), item.maxHardiness(), item.artPack(), buildPropBuilt(packs));
        try {
            inventoryStore.upsertEquip(session.selected.id(), updated);
        } catch (RuntimeException e) {
            command.fail("洗练落库失败: " + e.getMessage());
            return;
        }
        pushWearFrames(connection, session, updated);
        int count = packs.size();
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message + "（词条 " + count + " 条）");
        log.info("session={} GM recast {} slot={} packs={}",
                session.machine.id(), command.action(), item.slot(), count);
    }

    private static boolean isWeaponEquipType(String equipType) {
        if (equipType == null) {
            return false;
        }
        return switch (equipType.toLowerCase(java.util.Locale.ROOT)) {
            case "weapon", "shotweapon", "innerweapon" -> true;
            default -> false;
        };
    }

    /** propBuilt 串（"1,id;1,id"）→ 包号列表（脏段跳过；PanelState.recomputeEquipBonuses 同方言）。 */
    static List<Integer> parsePropBuiltPacks(String propBuilt) {
        List<Integer> packs = new ArrayList<>();
        if (propBuilt == null || propBuilt.isBlank()) {
            return packs;
        }
        for (String entry : propBuilt.split(";")) {
            int comma = entry.indexOf(',');
            String id = entry.substring(comma + 1).trim();
            try {
                packs.add(Integer.parseInt(id));
            } catch (NumberFormatException ignored) {
                // 脏段跳过
            }
        }
        return packs;
    }

    /** 包号列表 → propBuilt 串（"1,id;1,id"；老 "1," 前缀与 gm_generate/randomPropBuilt 一致）。 */
    static String buildPropBuilt(List<Integer> packs) {
        StringBuilder built = new StringBuilder();
        for (int p : packs) {
            if (built.length() > 0) {
                built.append(';');
            }
            built.append("1,").append(p);
        }
        return built.toString();
    }

    // ------------------------------------------------------------ 场景生成（GM）

    /** 训练木桩模板（worldnpc/attacknpc.txt 官方「木桩」，AttackNpc → 可选中/受击）。 */
    private static final String DUMMY_TABLE = "worldnpc/attacknpc.txt";
    private static final String DUMMY_TEMPLATE = "monv010100";
    /**
     * 战斗假人模板（bak spawn_combat_dummy 同款）：attacknpc 表「机关木人」，
     * 模型 npc\monster026 与 bak monm080701 同名同模型；AttackNpc scriptClass
     * 天然进战斗链（命中后 NpcCombatAi 反击），等价 bak 的 monster=true。
     */
    private static final String COMBAT_DUMMY_TABLE = "worldnpc/attacknpc.txt";
    private static final String COMBAT_DUMMY_TEMPLATE = "monf022202";
    /** 战斗假人默认血量（bak 同款 5 万）。 */
    private static final int COMBAT_DUMMY_DEFAULT_HP = 50_000;
    private static final int COMBAT_DUMMY_MAX_HP = 100_000_000;
    private static final String COMBAT_DUMMY_DEFAULT_NAME = "战斗假人";
    /** 世界宝箱模板（worldnpc/monsternpc_commonnpc.txt「特殊财宝箱」，CommonNpc → 可点击）。 */
    private static final String CHEST_TABLE = "worldnpc/monsternpc_commonnpc.txt";
    private static final String CHEST_TEMPLATE = "stormbox_10";
    /** 礼包测试商人模板（同表「难民-江湖郎中」，人形 CommonNpc → 可点击开 gm_giftbox 商店）。 */
    private static final String MERCHANT_TABLE = "worldnpc/monsternpc_commonnpc.txt";
    private static final String MERCHANT_TEMPLATE = "citywar03_npc_010";
    /** 世界 BOSS 模板表（官方 BOSS 全集 ~2600 行；BossNpc scriptClass 天然进战斗反击链）。 */
    private static final String WORLD_BOSS_TABLE = "worldnpc/bossnpc.txt";
    /** 世界 BOSS 击杀摘除延迟（毫秒）：击杀结算帧先发，随后 0x0E 消失。 */
    private static final long WORLD_BOSS_REMOVE_DELAY_MS = 300;
    /** GM 摆放对象号基（目录 NPC = 下标+2 的小号段；900000+ 永不冲突）。 */
    private static final int GM_OBJECT_ID_BASE = 900_000;
    /** 生成点距玩家距离（身前 2.5 米，朝向按 0x26 motion 的 orient）。 */
    private static final float GM_SPAWN_AHEAD = 2.5f;

    /** 训练木桩（spawn_dummy）：玩家身前克隆官方木桩模板，血量自定义、移速 0、不还手（战斗一期语义）。 */
    private void applySpawnDummy(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        int hp = command.intParam("dummyHp", 0);
        if (hp <= 0) {
            hp = 100_000;
        }
        if (hp > 100_000_000) {
            command.fail("dummyHp 至多 100000000");
            return;
        }
        float[] at = gmSpawnPosition(session);
        NpcSpawn dummy;
        try {
            dummy = com.nineyin.server.world.npc.GmNpcFactory.spawnFromTemplate(
                    DUMMY_TABLE, DUMMY_TEMPLATE, at[0], at[1], at[2], at[3],
                    Map.of("MaxHP", Integer.toString(hp), "HP", Integer.toString(hp),
                            "WalkSpeed", "0", "RunSpeed", "0"));
        } catch (IOException e) {
            command.fail("木桩模板加载失败: " + e.getMessage());
            return;
        }
        int objectId;
        try {
            objectId = registerGmSpawn(session, dummy, false);
        } catch (RuntimeException e) {
            command.fail("木桩注册失败: " + e.getMessage());
            return;
        }
        session.world.reconcileViewport();
        String message = "已生成训练木桩（血量 " + hp + "，对象 " + objectId + "），选中即可攻击";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM spawn dummy {} hp={} at ({},{},{})",
                session.machine.id(), objectId, hp, at[0], at[1], at[2]);
    }

    /** 世界宝箱（spawn_chest）：玩家身前克隆宝箱模板，点击（0x07）开箱拾取后消失。 */
    private void applySpawnChest(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        float[] at = gmSpawnPosition(session);
        NpcSpawn chest;
        try {
            chest = com.nineyin.server.world.npc.GmNpcFactory.spawnFromTemplate(
                    CHEST_TABLE, CHEST_TEMPLATE, at[0], at[1], at[2], at[3],
                    Map.of("Name", "镀银宝箱"));
        } catch (IOException e) {
            command.fail("宝箱模板加载失败: " + e.getMessage());
            return;
        }
        int objectId;
        try {
            objectId = registerGmSpawn(session, chest, true);
        } catch (RuntimeException e) {
            command.fail("宝箱注册失败: " + e.getMessage());
            return;
        }
        session.world.reconcileViewport();
        String message = "已生成镀银宝箱（对象 " + objectId + "），点击开箱拾取";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM spawn chest {} at ({},{},{})",
                session.machine.id(), objectId, at[0], at[1], at[2]);
    }

    /**
     * 战斗假人（spawn_combat_dummy，bak 同款语义）：玩家身前克隆「机关木人」
     * AttackNpc 模板——AttackNpc scriptClass 即 combat，玩家命中后由 NpcCombatAi
     * 周期反击（等价 bak 的 monster=true 世界 BOSS 战斗链）。血量默认 5 万、
     * 名字默认「战斗假人」；dummyDamage/dummySkills 面板参数对齐 bak 仅记日志。
     */
    private void applySpawnCombatDummy(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("生成战斗假人失败：角色尚未进入场景");
            return;
        }
        String name = command.strParam("dummyName").trim();
        if (name.isEmpty()) {
            name = COMBAT_DUMMY_DEFAULT_NAME;
        }
        int hp = command.intParam("dummyHp", 0);
        if (hp <= 0) {
            hp = COMBAT_DUMMY_DEFAULT_HP;
        }
        if (hp > COMBAT_DUMMY_MAX_HP) {
            command.fail("dummyHp 至多 " + COMBAT_DUMMY_MAX_HP);
            return;
        }
        float[] at = gmSpawnPosition(session);
        NpcSpawn dummy;
        try {
            dummy = com.nineyin.server.world.npc.GmNpcFactory.spawnFromTemplate(
                    COMBAT_DUMMY_TABLE, COMBAT_DUMMY_TEMPLATE, at[0], at[1], at[2], at[3],
                    Map.of("Name", name, "MaxHP", Integer.toString(hp), "HP", Integer.toString(hp)));
        } catch (IOException e) {
            command.fail("战斗假人模板加载失败: " + e.getMessage());
            return;
        }
        int objectId;
        try {
            objectId = registerGmSpawn(session, dummy, false, false, true);
        } catch (RuntimeException e) {
            command.fail("战斗假人注册失败: " + e.getMessage());
            return;
        }
        session.world.reconcileViewport();
        String message = "已生成战斗假人（对象=" + objectId + " " + name + " 血量=" + hp + "），选中攻击后会还手";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM combat dummy {} hp={} name={} damage={} skills={}",
                session.machine.id(), objectId, hp, name,
                command.intParam("dummyDamage", 0), command.strParam("dummySkills"));
    }

    /** 清除战斗假人（clear_combat_dummies，bak 同款）：摘除本会话生成的全部假人。 */
    private void applyClearCombatDummies(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("清除战斗假人失败：角色尚未进入场景");
            return;
        }
        List<Integer> dummyIds = new ArrayList<>();
        for (LoginSession.GmSpawn spawn : session.gmSpawns.values()) {
            if (spawn.combatDummy) {
                dummyIds.add(spawn.objectId);
            }
        }
        for (int objectId : dummyIds) {
            removeGmSpawn(session, objectId);
        }
        String message = "已清除 " + dummyIds.size() + " 只战斗假人";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM clear combat dummies: {} removed",
                session.machine.id(), dummyIds.size());
    }

    /**
     * 世界 BOSS（worldboss_spawn）：官方刷新表驱动——当前场景（scenes.ini 场景号）
     * 的门派固定点每点 1 只（bosslist 逐点轮选）+ 场景池命中的江湖 BOSS 在该场景
     * 随机点池取点；bossnpc 模板真实血量/名字，BossNpc scriptClass 命中即反击。
     * 击杀后消失不重生（{@link #bossKillOverride}）；切图随 gmSpawns 清空同其他摆放。
     */
    private void applyWorldBossSpawn(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("生成世界 BOSS 失败：角色尚未进入场景");
            return;
        }
        Roles.Location location = session.pendingLocation != null
                ? session.pendingLocation : session.selected.location();
        Integer sceneId = SceneNames.sceneId(location.scene().config(), location.scene().resource());
        if (sceneId == null) {
            command.fail("生成世界 BOSS 失败：无法识别当前场景号（scenes.ini 缺失或未收录）");
            return;
        }
        WorldBossRefreshTable table = WorldBossRefreshTable.load();
        if (!table.present()) {
            command.fail("生成世界 BOSS 失败：刷新表缺失（share/npc/normalbossrefreshinfo.ini 等）");
            return;
        }
        List<WorldBossRefreshTable.NormalPoint> points = table.normalPointsForScene(sceneId);
        List<WorldBossRefreshTable.JhBoss> jhBosses = table.jhBossesForScene(sceneId);
        if (points.isEmpty() && jhBosses.isEmpty()) {
            command.fail("当前场景 " + SceneNames.sceneDisplayName(
                            location.scene().config(), location.scene().resource())
                    + "（场景号 " + sceneId + "）无世界 BOSS 刷新配置；"
                    + "门派固定点见场景 5-12，江湖 BOSS 场景池见 jhscenebossrefreshinfo.ini");
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int pointIndex = 0;
        for (WorldBossRefreshTable.NormalPoint point : points) {
            String template = point.bossList().get(pointIndex % point.bossList().size());
            pointIndex++;
            spawnWorldBoss(session, template, point.x(), point.y(), point.z(), names, failures);
        }
        for (WorldBossRefreshTable.JhBoss boss : jhBosses) {
            List<float[]> pool = table.jhPointsForScene(sceneId);
            if (pool.isEmpty()) {
                failures.add(boss.bossId() + "（场景 " + sceneId + " 无随机点池）");
                continue;
            }
            float[] at = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            spawnWorldBoss(session, boss.bossId(), at[0], at[1], at[2], names, failures);
        }
        if (names.isEmpty()) {
            command.fail("生成世界 BOSS 失败：" + String.join("；", failures));
            return;
        }
        session.world.reconcileViewport();
        String message = "已强制生成 " + names.size() + " 只世界 BOSS：" + String.join("、", names);
        if (!failures.isEmpty()) {
            message += "（另有 " + failures.size() + " 失败：" + String.join("；", failures) + "）";
        }
        final String summary = message;
        gmHub.update(session.machine.id(), s -> s.lastAction = summary);
        command.succeed(message);
        log.info("session={} GM worldboss spawn scene={} spawned={} failed={}",
                session.machine.id(), sceneId, names.size(), failures.size());
    }

    /** 清除世界 BOSS（worldboss_clear）：摘除本会话生成的全部 BOSS（击杀消失同口径）。 */
    private void applyWorldBossClear(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("清除世界 BOSS 失败：角色尚未进入场景");
            return;
        }
        List<Integer> bossIds = new ArrayList<>();
        for (LoginSession.GmSpawn spawn : session.gmSpawns.values()) {
            if (spawn.worldBoss) {
                bossIds.add(spawn.objectId);
            }
        }
        for (int objectId : bossIds) {
            removeGmSpawn(session, objectId);
        }
        String message = "已清除 " + bossIds.size() + " 只世界 BOSS";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM worldboss clear: {} removed", session.machine.id(), bossIds.size());
    }

    /** 在场世界 BOSS（worldboss_list）：对象号/名字/血量（受击后实时，否则满血）/坐标。 */
    private void applyWorldBossList(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("查看世界 BOSS 失败：角色尚未进入场景");
            return;
        }
        List<LoginSession.GmSpawn> bosses = new ArrayList<>();
        for (LoginSession.GmSpawn spawn : session.gmSpawns.values()) {
            if (spawn.worldBoss) {
                bosses.add(spawn);
            }
        }
        String message;
        if (bosses.isEmpty()) {
            message = "在场世界 BOSS：0（worldboss_spawn 按当前场景强制生成）";
        } else {
            StringBuilder built = new StringBuilder("在场世界 BOSS ").append(bosses.size()).append(" 只：");
            for (LoginSession.GmSpawn spawn : bosses) {
                SceneLifecycle.CombatNpcInfo info = session.world.combatNpcInfo(spawn.objectId);
                FightState.NpcCombat combat =
                        session.fight != null ? session.fight.npc(spawn.objectId) : null;
                int hp = combat != null ? combat.state().hp()
                        : info != null ? info.maxHp() : 0;
                int maxHp = info != null ? info.maxHp() : 0;
                built.append(spawn.objectId).append(' ').append(gmSpawnName(spawn))
                        .append("（血 ").append(hp).append('/').append(maxHp)
                        .append("，位 ").append(String.format(java.util.Locale.ROOT, "%.1f", spawn.npc.x))
                        .append(',').append(String.format(java.util.Locale.ROOT, "%.1f", spawn.npc.z))
                        .append("） ");
            }
            message = built.toString().trim();
        }
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM worldboss list: {} alive", session.machine.id(), bosses.size());
    }

    /** 克隆 bossnpc 模板并按世界 BOSS 口径注册（名字进名单，失败进 failures）。 */
    private void spawnWorldBoss(LoginSession session, String template, float x, float y, float z,
                                List<String> names, List<String> failures) {
        try {
            NpcSpawn boss = com.nineyin.server.world.npc.GmNpcFactory.spawnFromTemplate(
                    WORLD_BOSS_TABLE, template, x, y, z, 0f, Map.of());
            int objectId = registerGmSpawn(session, boss, false, false, false, true);
            names.add(gmSpawnName(session.gmSpawns.get(objectId)) + "×" + objectId);
        } catch (IOException | RuntimeException e) {
            failures.add(template + "：" + e.getMessage());
        }
    }

    /** GM 摆放显示名（模板 Name 属性，缺省回退 configId）。 */
    private static String gmSpawnName(LoginSession.GmSpawn spawn) {
        PropertyValues.Value name = spawn.npc.resolved.properties.get("Name");
        return name != null && name.text() != null && !name.text().isEmpty()
                ? name.text() : spawn.npc.resolved.configId;
    }

    /**
     * 击杀接管（msg211 击杀分支钩子）：世界 BOSS 认领 worldBoss 摆放 → 跳过 15s
     * 重生，延迟 {@link #WORLD_BOSS_REMOVE_DELAY_MS} 摘除（击杀帧先发）+ 面板回执；
     * 伙伴（bot 摆放）阵亡 → 冻结不重生（bot_revive 手动复活）；副本目录 NPC 由
     * {@link #claimCloneKill} 认领 → 死后不复活并尝试波次推进。
     */
    private IntPredicate killOverride(LoginSession session) {
        return objectId -> {
            LoginSession.GmSpawn spawn = session.gmSpawns.get(objectId);
            if (spawn != null && spawn.worldBoss) {
                String name = gmSpawnName(spawn);
                scheduler.schedule(() -> {
                    LoginSession.GmSpawn current = session.gmSpawns.get(objectId);
                    if (current == null || !current.worldBoss) {
                        return; // 已被 worldboss_clear 摘除
                    }
                    removeGmSpawn(session, objectId);
                    String message = "已击杀世界 BOSS " + name + "（对象 " + objectId + "，消失不重生）";
                    gmHub.update(session.machine.id(), s -> s.lastAction = message);
                    log.info("session={} world boss {} ({}) killed and removed",
                            session.machine.id(), objectId, name);
                }, WORLD_BOSS_REMOVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            }
            // 伙伴阵亡：认领击杀 → 冻结不重生（不吃 15s 满血复活），bot_revive 手动复活。
            if (spawn != null && spawn.bot) {
                String name = gmSpawnName(spawn);
                final String message = "伙伴 " + name + " 已阵亡（bot_revive 复活回满血）";
                gmHub.update(session.machine.id(), s -> {
                    s.lastAction = message;
                    s.botHp = 0;
                });
                log.info("session={} bot {} ({}) killed; frozen until revive",
                        session.machine.id(), objectId, name);
                return true;
            }
            return claimCloneKill(session, objectId);
        };
    }

    /** 注册 GM 摆放并返回对象号（0x0D+0x1F 由随后的 reconcileViewport 物化）。 */
    private int registerGmSpawn(LoginSession session, NpcSpawn npc, boolean chest) {
        return registerGmSpawn(session, npc, chest, false);
    }

    private int registerGmSpawn(LoginSession session, NpcSpawn npc, boolean chest, boolean merchant) {
        return registerGmSpawn(session, npc, chest, merchant, false);
    }

    private int registerGmSpawn(LoginSession session, NpcSpawn npc, boolean chest,
                                boolean merchant, boolean combatDummy) {
        return registerGmSpawn(session, npc, chest, merchant, combatDummy, false);
    }

    private int registerGmSpawn(LoginSession session, NpcSpawn npc, boolean chest,
                                boolean merchant, boolean combatDummy, boolean worldBoss) {
        return registerGmSpawn(session, npc, chest, merchant, combatDummy, worldBoss, false);
    }

    private int registerGmSpawn(LoginSession session, NpcSpawn npc, boolean chest,
                                boolean merchant, boolean combatDummy, boolean worldBoss,
                                boolean bot) {
        int objectId = session.nextGmObjectId++;
        session.world.registerNpc(objectId, npc);
        session.gmSpawns.put(objectId,
                new LoginSession.GmSpawn(objectId, npc, chest, merchant, combatDummy, worldBoss, bot));
        return objectId;
    }

    /**
     * 礼包测试商人（giftbox_merchant_here）：人形 CommonNpc 克隆拉到玩家身前；
     * 点击（0x07）直达 gm_giftbox 商店（真正可开的礼包货架，免费领）。重复
     * 拉人先摘旧商人再放新（身边至多一名）；切图随 world.begin 清空同宝箱口径。
     */
    private void applyGiftboxMerchantHere(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("角色尚未进入场景");
            return;
        }
        int goods = shopSection(GIFT_SHOP_ID) != null
                ? shopSection(GIFT_SHOP_ID).goods().size() : 0;
        if (goods == 0) {
            command.fail("礼包货架为空（drops_mvp.json 缺失或无可开礼包）");
            return;
        }
        if (session.giftMerchantObjectId != null) {
            removeGmSpawn(session, session.giftMerchantObjectId);
        }
        float[] at = gmSpawnPosition(session);
        NpcSpawn merchant;
        try {
            merchant = com.nineyin.server.world.npc.GmNpcFactory.spawnFromTemplate(
                    MERCHANT_TABLE, MERCHANT_TEMPLATE, at[0], at[1], at[2], at[3],
                    Map.of("Name", "礼包测试商人"));
        } catch (IOException e) {
            command.fail("商人模板加载失败: " + e.getMessage());
            return;
        }
        int objectId;
        try {
            objectId = registerGmSpawn(session, merchant, false, true);
        } catch (RuntimeException e) {
            command.fail("商人注册失败: " + e.getMessage());
            return;
        }
        session.giftMerchantObjectId = objectId;
        session.world.reconcileViewport();
        String message = "礼包测试商人已拉到身前（对象 " + objectId + "，货架 " + goods + " 件），点击打开";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM gift merchant {} at ({},{},{}) goods={}",
                session.machine.id(), objectId, at[0], at[1], at[2], goods);
    }

    /** 摘除 GM 摆放（unregister + gmSpawns 移除 + reconcile → 0x0E 消失）。 */
    private void removeGmSpawn(LoginSession session, int objectId) {
        session.gmSpawns.remove(objectId);
        if (session.giftMerchantObjectId != null
                && session.giftMerchantObjectId == objectId) {
            session.giftMerchantObjectId = null;
        }
        session.world.unregister(objectId);
        session.world.reconcileViewport();
    }

    /** 生成点：玩家当前位置（motion 实时位优先）+ 朝向前方 GM_SPAWN_AHEAD 米。 */
    private static float[] gmSpawnPosition(LoginSession session) {
        Roles.Location location = session.pendingLocation != null
                ? session.pendingLocation : session.selected.location();
        Roles.Position p = location.position();
        double orient = p.orient();
        return new float[] {
                p.x() + GM_SPAWN_AHEAD * (float) Math.cos(orient),
                p.y(),
                p.z() + GM_SPAWN_AHEAD * (float) Math.sin(orient),
                p.orient(),
        };
    }

    /**
     * 点击 GM 宝箱（0x07 FuncID=0）：回 LastObject + 随机拾取一件入背包 +
     * 摘除对象（unregister + reconcile → 0x0E）。拾取池 = 可穿戴装备 ∪ 开箱类礼包
     * （tool_item ItemType 2102/2103）；词缀暂不附（背包行无 propBuilt 通道）。
     */
    private void openGmChest(Connection connection, LoginSession session, LoginSession.GmSpawn chest) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "LastObject",
                PropertyValues.Value.ofObject(chest.objectId, SceneMessages.PLAYER_OWNER_ID));
        connection.writeFrame(SceneMessages.objectProperties(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, 0, properties));
        ToolItemCatalog.ItemInfo loot = randomChestLoot(session);
        String lootText = "空手而归";
        if (loot != null && grantBagItem(connection, session, loot, 1) != null) {
            lootText = loot.name().isEmpty() ? loot.configId() : loot.name();
        }
        session.gmSpawns.remove(chest.objectId);
        session.world.unregister(chest.objectId);
        session.world.reconcileViewport();
        log.info("session={} GM chest {} opened, loot={}",
                session.machine.id(), chest.objectId,
                loot == null ? "none" : loot.configId());
    }

    /** 宝箱随机拾取：可穿戴 ∪ 开箱类礼包（空目录返回 null）。 */
    private ToolItemCatalog.ItemInfo randomChestLoot(LoginSession session) {
        List<ToolItemCatalog.ItemInfo> pool = new ArrayList<>();
        for (ToolItemCatalog.ItemInfo info : ToolItemCatalog.loadDefault().all()) {
            if (AppearanceSync.slotOfEquipType(info.equipType()) > 0
                    || info.itemType() == 2102 || info.itemType() == 2103) {
                pool.add(info);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(sessionRandom(session).nextInt(pool.size()));
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

    /** menu2 setVitals：体力/饱食/杀戮/酒量族（均可选；本服表无 maxEneAdd/maxSatAdd 忽略）。
     * 改值即 0x10 推面板并随 role_faculty 快照落库（嵌套 vitals 节，重登回填）。 */
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
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT,
                "体力 %d/%d 饱食 %d/%d 杀戮 %d 酒量 %d 已更新并落库",
                panel.ene, panel.maxEne, panel.sat, panel.maxSat, panel.pkValue, panel.drunkLevel);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_vitals {} items", session.machine.id(), properties.size());
    }

    private interface VitalSetter {
        void set(int value);
    }

    /**
     * GM set_five_attributes：基础五维（臂力/体魄/身法/内息/罡气）覆盖式写入，0/缺省跳过该维。
     * Str/Sta/Dex/Ing/Spi 与 HP/MP 换算族（7×体魄+2×臂力、4×内息+罡气）均由基值派生，
     * 改值 → 0x10 全量 → faculty 快照落库。
     */
    private void applyFiveAttributes(Connection connection, LoginSession session, GmCommand command) {
        Integer str = command.intParam("attrStr");
        Integer sta = command.intParam("attrSta");
        Integer dex = command.intParam("attrDex");
        Integer ing = command.intParam("attrIng");
        Integer spi = command.intParam("attrSpi");
        int provided = 0;
        for (Integer value : new Integer[]{str, sta, dex, ing, spi}) {
            if (value != null && value > 0) {
                provided++;
            }
        }
        if (provided == 0) {
            command.fail("五维均未提供（attrStr/attrSta/attrDex/attrIng/attrSpi，0=不修改）");
            return;
        }
        int[] current = session.player.progress().setBaseAttributes(str, sta, dex, ing, spi);
        connection.writeFrame(session.player.vitalUpdate());
        persistFaculty(session);
        String action = String.format(java.util.Locale.ROOT,
                "五维已更新：臂力 %d 体魄 %d 身法 %d 内息 %d 罡气 %d（改 %d 维）",
                current[0], current[1], current[2], current[3], current[4], provided);
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM set_five_attributes -> str={} sta={} dex={} ing={} spi={}",
                session.machine.id(), current[0], current[1], current[2], current[3], current[4]);
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

    /**
     * 门派声望（repute_apply）：阵营闭名单 = repute_level.ini [Repute]（36 项：
     * 八大门派/江湖/朝廷/隐世势力族；reputeName="all" 全量按表序）。set 覆盖 /
     * add 叠加，clamp 到阵营上限（[repute_max_value] 特例否则 [Level] 最大）；
     * 值 ≤0 摘行。客户端两通道即时刷新：Repute_Record(44) 行 0x12 倒序删旧 +
     * 0x11 整刷（名/值/等级）+ PrestigeExp@848 汇总单帧（panel.prestigeExp）。
     * 落库 role_repute 逐阵营 upsert（best-effort，失败 warn 不回滚内存——与
     * set_faculty 同口径）；重登/切图由 reputes 镜像整表重放。
     */
    private void applyReputeApply(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.player == null) {
            command.fail("设置声望失败：角色尚未进入场景");
            return;
        }
        var table = com.nineyin.server.game.avatar.panel.ReputeLevelTable.load();
        if (!table.present()) {
            command.fail("设置声望失败：repute_level.ini 缺失（share/rule/ 数据根未配置）");
            return;
        }
        String name = command.strParam("reputeName").trim();
        String mode = command.strParam("reputeMode").trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.isEmpty()) {
            mode = "set";
        }
        int value = command.intParam("reputeValue", -1);
        if (value < 0) {
            command.fail("reputeValue 必须为 0..9999999");
            return;
        }
        List<String> targets;
        if ("all".equals(name)) {
            targets = table.camps();
        } else if (table.isCamp(name)) {
            targets = List.of(name);
        } else {
            command.fail("未知声望阵营: " + name + "（可用 all 或 " + table.camps().size()
                    + " 项 repute_* 阵营，见 repute_level.ini [Repute]）");
            return;
        }
        // 逐阵营求新值（add 叠加旧值；clamp 上限；≤0 摘行），保持 [Repute] 表序。
        int oldRows = session.reputes.size();
        for (String camp : targets) {
            long current = session.reputes.getOrDefault(camp, 0L);
            long updated = "add".equals(mode) ? current + value : value;
            updated = Math.max(0, Math.min(updated, table.maxValue(camp)));
            if (updated <= 0) {
                session.reputes.remove(camp);
            } else {
                session.reputes.put(camp, updated);
            }
        }
        // 行整刷（0x12 倒序删旧 + 0x11 逐行）+ PrestigeExp 汇总单帧 + 落库。
        refreshReputeRows(connection, session, oldRows);
        PanelState panel = session.player.panel();
        String action;
        if (targets.size() == 1) {
            String camp = targets.get(0);
            long applied = session.reputes.getOrDefault(camp, 0L);
            action = "声望 " + camp + " 已" + ("add".equals(mode) ? "叠加" : "设置") + "至 "
                    + applied + "（等级 " + table.levelOf(camp, applied) + "），总量 "
                    + panel.prestigeExp;
        } else {
            action = "已按 " + mode + " " + value + " 更新全部 " + targets.size()
                    + " 个声望阵营（在场 " + session.reputes.size() + " 行），总量 "
                    + panel.prestigeExp;
        }
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM repute_apply name={} mode={} value={} rows={} total={}",
                session.machine.id(), name, mode, value, session.reputes.size(), panel.prestigeExp);
    }

    /**
     * Repute_Record 行整刷（0x12 倒序删 oldRows 旧行 + 0x11 逐行重推）+
     * PrestigeExp 汇总单帧 + role_repute 落库 best-effort。
     * repute_apply 与身份领奖（702 prize_prop）共用。
     */
    private void refreshReputeRows(Connection connection, LoginSession session, int oldRows) {
        var table = com.nineyin.server.game.avatar.panel.ReputeLevelTable.load();
        for (byte[] frame : com.nineyin.server.game.avatar.panel.ReputeMessages.clearFrames(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, oldRows)) {
            connection.writeFrame(frame);
        }
        for (byte[] frame : com.nineyin.server.game.avatar.panel.ReputeMessages.rowsFrames(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                session.reputes, table)) {
            connection.writeFrame(frame);
        }
        long total = 0;
        for (long rowValue : session.reputes.values()) {
            total += rowValue;
        }
        PanelState panel = session.player.panel();
        panel.prestigeExp = (int) Math.min(Integer.MAX_VALUE, total);
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "PrestigeExp", PropertyValues.Value.ofInt32(panel.prestigeExp));
        pushPanelProperties(connection, properties);
        // 落库 best-effort（失败 warn，内存与帧已生效；重登以 DB 为准会回落）。
        if (panelStore != null) {
            try {
                panelStore.upsertRepute(session.selected.id(), session.reputes);
            } catch (RuntimeException e) {
                log.warn("session={} persist repute: {}", session.machine.id(), e.getMessage());
            }
        }
    }

    // --------------------------------------------------------------- 身份域（origin）

    /**
     * GM 身份系统（origin_activate_all）：origin.ini 全量身份（1197 个）一次性加入
     * 【已激活】集合——Origin_Active（官方 447 表 idx 45，单窄串列）每身份一行，
     * 行键 = 身份 id 数字串；【未领取】状态保持不变（Origin_Prized 行不动），玩家在
     * 身份界面点领取（C2S 702）才实际拿到物品/称号/声望奖励。落库 role_origins
     * INSERT IGNORE（best-effort 失败 warn 不回滚内存——与 repute_apply 同口径）；
     * 重登/切图按内存镜像整表重放（originsGranted 门，letters/reputes 同口径）。
     */
    private void applyOriginActivateAll(Connection connection, LoginSession session,
                                        GmCommand command) {
        if (session.selected == null || session.player == null) {
            command.fail("激活身份失败：角色尚未进入场景");
            return;
        }
        OriginCatalog catalog = OriginCatalog.load();
        if (!catalog.present()) {
            command.fail("激活身份失败：origin.ini 缺失（share/task/origin/ 数据根未配置）");
            return;
        }
        int activeRecord = RecordSchemas.indexByName("Origin_Active");
        List<Integer> addedIds = new ArrayList<>();
        for (int originId : catalog.originIds()) {
            String key = String.valueOf(originId);
            if (session.origins.containsKey(key)) {
                continue; // 已激活/已领奖保持不动（未领取状态不变）
            }
            session.origins.put(key, 1);
            connection.writeFrame(RecordMessages.recordAddString(
                    SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                    activeRecord, key));
            addedIds.add(originId);
        }
        if (panelStore != null && !addedIds.isEmpty()) {
            try {
                panelStore.insertOrigins(session.selected.id(), addedIds);
            } catch (RuntimeException e) {
                log.warn("session={} persist origins: {}", session.machine.id(), e.getMessage());
            }
        }
        String action = addedIds.isEmpty()
                ? "全部 " + catalog.originIds().size() + " 个身份已在激活集合（未领取状态不变）"
                : "已激活 " + addedIds.size() + " 个身份（共 " + catalog.originIds().size()
                        + " 个）；奖励在身份界面点领取（702）发放";
        gmHub.update(session.machine.id(), s -> s.lastAction = action);
        command.succeed(action);
        log.info("session={} GM origin_activate_all added={} total={}",
                session.machine.id(), addedIds.size(), catalog.originIds().size());
    }

    /**
     * 身份系统 C2S（bak SceneFlow 700-706 stub 交棒；官方 exe 反汇编归属：
     * 700=Active / 702=GetPrize / 706=RequireNotify；701 展示 / 705 领取查询
     * 留待——S2C lua 回调无抓包证据）。
     * <ul>
     *   <li>700 [700, 身份 id] —— 激活单身份（测试服跳过 ActiveConditions 条件门）：
     *       入激活集 + Origin_Active 行 + role_origins INSERT IGNORE。</li>
     *   <li>702 [702, 身份 id] —— 领取身份奖励：prize_item 物品入包（全部成功才标记，
     *       满包/失败不标记、下次点击补发）+ prize_title 称号所有权（role_titles
     *       INSERT IGNORE）+ prize_prop repute_* 阵营叠加（Repute_Record 整刷 +
     *       PrestigeExp 单帧，repute_apply 同口径）→ Origin_Prized 行 + status=2。</li>
     *   <li>706 —— 请求刷新：按内存镜像重推 Origin_Active/Origin_Prized 全部行。</li>
     * </ul>
     */
    void handleOriginCustom(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        int messageId = custom.messageId();
        if (messageId == 706) {
            pushOriginRows(connection, session);
            session.originsGranted = true;
            log.debug("session={} origin refresh: {} rows", session.machine.id(), session.origins.size());
            return;
        }
        String originId = originIdArg(custom);
        if (originId == null) {
            log.info("session={} origin msg {} without origin id", session.machine.id(), messageId);
            return;
        }
        Integer id = parseOriginId(originId);
        OriginCatalog catalog = OriginCatalog.load();
        if (id == null || !catalog.contains(id)) {
            log.info("session={} origin {} not in catalog (msg {})",
                    session.machine.id(), originId, messageId);
            return;
        }
        if (messageId == 700) {
            if (!session.origins.containsKey(originId)) {
                session.origins.put(originId, 1);
                connection.writeFrame(RecordMessages.recordAddString(
                        SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                        RecordSchemas.indexByName("Origin_Active"), originId));
                if (panelStore != null) {
                    try {
                        panelStore.insertOrigins(session.selected.id(), List.of(id));
                    } catch (RuntimeException e) {
                        log.warn("session={} persist origin {}: {}",
                                session.machine.id(), originId, e.getMessage());
                    }
                }
                log.info("session={} origin {} activated (custom 700)",
                        session.machine.id(), originId);
            }
            return;
        }
        // 702 领奖：只处理已激活且未领奖的身份（重复点击幂等）。
        if (session.origins.getOrDefault(originId, 0) == 0) {
            log.info("session={} origin {} not active, prize rejected (custom 702)",
                    session.machine.id(), originId);
            return;
        }
        if (session.origins.get(originId) >= 2) {
            log.debug("session={} origin {} already prized", session.machine.id(), originId);
            return;
        }
        claimOriginPrize(connection, session, catalog, originId, id);
    }

    /** 702 领奖核心（见 {@link #handleOriginCustom}；物品全部入包成功才置已领奖，否则保留未领取待补发）。 */
    private void claimOriginPrize(Connection connection, LoginSession session,
                                  OriginCatalog catalog, String originId, int id) {
        int itemOk = 0;
        for (OriginCatalog.ItemPrize prize : catalog.itemPrizes(id)) {
            ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(prize.configId());
            if (info == null) {
                // 物品配置缺失（如身份装 config 不在 equipment/tool_item 表）：跳过计数，不阻断领奖标记。
                log.info("session={} origin {} prize item {} unknown, skipped",
                        session.machine.id(), originId, prize.configId());
                continue;
            }
            if (grantBagItem(connection, session, info, prize.count()) == null) {
                log.info("session={} origin {} prize item {} not delivered, keep unprized",
                        session.machine.id(), originId, prize.configId());
                return; // 未标记已领奖：下次点击 702 补发
            }
            itemOk++;
        }
        List<Integer> titles = catalog.titlePrizes(id);
        if (panelStore != null && !titles.isEmpty()) {
            try {
                for (int titleId : titles) {
                    panelStore.grantTitle(session.selected.id(), titleId);
                }
            } catch (RuntimeException e) {
                log.warn("session={} grant titles for origin {}: {}",
                        session.machine.id(), originId, e.getMessage());
            }
        }
        int oldReputeRows = session.reputes.size();
        boolean reputeChanged = false;
        var reputeTable = com.nineyin.server.game.avatar.panel.ReputeLevelTable.load();
        for (OriginCatalog.PropPrize prize : catalog.propPrizes(id)) {
            if (!prize.prop().startsWith("repute_") || !reputeTable.present()
                    || !reputeTable.isCamp(prize.prop())) {
                continue; // 非声望属性（或阵营未知）留待后续
            }
            long current = session.reputes.getOrDefault(prize.prop(), 0L);
            long updated = Math.max(0,
                    Math.min(current + prize.value(), reputeTable.maxValue(prize.prop())));
            if (updated > 0) {
                session.reputes.put(prize.prop(), updated);
            }
            reputeChanged = true;
        }
        if (reputeChanged) {
            refreshReputeRows(connection, session, oldReputeRows);
        }
        session.origins.put(originId, 2);
        connection.writeFrame(RecordMessages.recordAddString(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                RecordSchemas.indexByName("Origin_Prized"), originId));
        if (panelStore != null) {
            try {
                panelStore.markOriginPrized(session.selected.id(), id);
            } catch (RuntimeException e) {
                log.warn("session={} mark origin {} prized: {}",
                        session.machine.id(), originId, e.getMessage());
            }
        }
        log.info("session={} origin {} prized: items={} titles={} reputeChanged={}",
                session.machine.id(), originId, itemOk, titles.size(), reputeChanged);
    }

    /** 身份记录行重放（status≥1 → Origin_Active；status=2 追加 Origin_Prized；空表零帧）。 */
    private void pushOriginRows(Connection connection, LoginSession session) {
        if (session.origins.isEmpty()) {
            return;
        }
        int activeRecord = RecordSchemas.indexByName("Origin_Active");
        int prizedRecord = RecordSchemas.indexByName("Origin_Prized");
        for (Map.Entry<String, Integer> row : session.origins.entrySet()) {
            connection.writeFrame(RecordMessages.recordAddString(
                    SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                    activeRecord, row.getKey()));
            if (row.getValue() >= 2) {
                connection.writeFrame(RecordMessages.recordAddString(
                        SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                        prizedRecord, row.getKey()));
            }
        }
    }

    /** 身份 id 实参（lua 端 str/variant 两口径：窄串或 int32/int64 皆可收）。 */
    private static String originIdArg(CustomC2S.Message custom) {
        if (custom.values.size() < 2) {
            return null;
        }
        CustomC2S.Value value = custom.values.get(1);
        if (value.text != null && !value.text.trim().isEmpty()) {
            return value.text.trim();
        }
        try {
            return String.valueOf(value.exactInt32());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer parseOriginId(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
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
     * 词缀重算 + vital；目标槽被占时旧装备让位回背包（swapEquipSlot，同
     * wearFromBag 语义，不再覆盖丢失），双槽饰品（耳环/戒指）优先第二空槽；
     * 否则入背包（role_bag_items + 成对 0x19/0x18 帧）。
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
        long roleId = session.selected.id();
        List<RoleInventoryStore.Equip> equips = wearNow ? loadEquips(session) : List.of();
        // 双槽饰品（耳环 11/12、戒指 14/15）优先空槽，全占用替换首槽（wearFromBag 同语义）。
        int slot = AppearanceSync.equipSlotFor(info.equipType(), equips);
        if (wearNow && slot == 0) {
            wearNow = false; // 不可穿戴（道具/材料）降级入背包
        }
        if (wearNow) {
            // 目标槽被占 → 旧装备让位回背包（wearFromBag 同款回收），不再覆盖丢失。
            RoleInventoryStore.Equip old = equipAt(equips, slot);
            int oldSlot = 0;
            if (old != null) {
                List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
                oldSlot = freeBagSlot(bagItems == null ? List.of() : bagItems, 2);
                if (oldSlot <= 0) {
                    command.fail("背包已满，无法回收槽位 " + slot + " 上的旧装备 "
                            + old.configId() + "；请先清理装备背包");
                    return;
                }
                try {
                    if (!inventoryStore.swapEquipSlot(roleId, slot, oldSlot)) {
                        command.fail("旧装备让位落库失败（详见服务端日志）");
                        return;
                    }
                } catch (RuntimeException e) {
                    command.fail("旧装备让位落库失败: " + e.getMessage());
                    return;
                }
            }
            RoleInventoryStore.Equip equip = new RoleInventoryStore.Equip(
                    info.configId(), info.itemType(), info.equipType(), slot,
                    100, 100, info.artPack(), "");
            try {
                inventoryStore.upsertEquip(roleId, equip);
            } catch (RuntimeException e) {
                command.fail("穿戴落库失败: " + e.getMessage());
                return;
            }
            if (old != null) {
                RoleInventoryStore.BagItem landed = new RoleInventoryStore.BagItem(
                        oldSlot, old.configId(), old.itemType(), 1, 2, "",
                        old.equipType(), 0, "", null, null, old.artPack(),
                        old.hardiness(), old.maxHardiness());
                pushBagItemFrames(connection, session, landed);
            }
            pushWearFrames(connection, session, equip);
            command.succeed("已穿戴 " + (info.name().isEmpty() ? info.configId() : info.name())
                    + "（" + info.configId() + "，槽位 " + slot + "）外观已同步"
                    + (old == null ? "" : "；旧装备 " + old.configId() + " 已回收至背包第 " + oldSlot + " 格"));
        } else {
            Integer bagSlot = grantBagItem(connection, session, info, amount);
            if (bagSlot == null) {
                command.fail("入包失败（背包 90 格已满或物品 DB 写入异常，详见服务端日志）");
                return;
            }
            command.succeed("已发放 " + (info.name().isEmpty() ? info.configId() : info.name())
                    + " x" + amount + " 至背包第 " + bagSlot + " 格");
        }
        log.info("session={} GM grant_item {} wearNow={}", session.machine.id(), info.configId(), wearNow);
    }

    /**
     * 物品入背包（0 假槽先插库换真实槽位）+ 成对 0x19/0x18 行帧；
     * 失败返回 null（grant_item / 宝箱拾取共用）。
     */
    private Integer grantBagItem(Connection connection, LoginSession session,
                                 ToolItemCatalog.ItemInfo info, int amount) {
        if (session.selected == null || inventoryStore == null) {
            return null;
        }
        int viewId = info.viewId() > 0 ? info.viewId() : 1;
        RoleInventoryStore.BagItem item = new RoleInventoryStore.BagItem(
                0, info.configId(), info.itemType(), amount, viewId, info.name(),
                info.equipType(), 0, info.script(), null, null, null, 0, 0);
        int bagSlot;
        try {
            bagSlot = inventoryStore.insertBagItem(session.selected.id(), item);
        } catch (RuntimeException e) {
            log.warn("session={} insert bag item {}: {}", session.machine.id(),
                    info.configId(), e.getMessage());
            return null;
        }
        item = new RoleInventoryStore.BagItem(bagSlot, item.configId(), item.itemType(),
                item.amount(), item.viewId(), item.name(), item.equipType(), item.colorLevel(),
                item.script(), item.anchorSlot(), item.expiresAt(), item.artPack(),
                item.hardiness(), item.maxHardiness());
        pushBagItemFrames(connection, session, item);
        return bagSlot;
    }

    // ===================== 邮件域（RecvLetterRec 319） =====================

    /** 系统信类型（客户端 form_mail.lua LETTER_SYSTEM_POST_USER=101；系统页签 101..199，发件人空串显示「系统邮件」）。 */
    private static final int LETTER_TYPE_SYSTEM_POST = 101;

    /** RecvLetterRec 行帧：16 列全宽（0/3/4 宽串、1/5/6/7/10/11 窄串、8/13 int64、15 f64）。 */
    static byte[] letterRowFrame(com.nineyin.server.game.avatar.mail.LetterStore.Letter letter) {
        return RecordMessages.recordAddCells(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                RecordMessages.RECORD_RECV_LETTER,
                RecordMessages.Cell.ofString(letter.sender()),          // 0 发件人（宽）
                RecordMessages.Cell.ofString(letter.senderUid()),       // 1 发件人 uid
                RecordMessages.Cell.ofInt(letter.type()),               // 2 类型
                RecordMessages.Cell.ofString(letter.title()),           // 3 标题（宽）
                RecordMessages.Cell.ofString(letter.content()),         // 4 正文（宽）
                RecordMessages.Cell.ofString(letter.gold()),            // 5 金（数字串）
                RecordMessages.Cell.ofString(letter.silver()),          // 6 银（数字串）
                RecordMessages.Cell.ofString(letter.appendixXml()),     // 7 附件 XML
                RecordMessages.Cell.ofInt64(letter.dateUnixSeconds()),  // 8 时间（unix 秒）
                RecordMessages.Cell.ofInt(letter.readFlag() ? 1 : 0),   // 9 已读
                RecordMessages.Cell.ofString(letter.serial()),          // 10 序列号
                RecordMessages.Cell.ofString("0"),                      // 11 交易金（数字串）
                RecordMessages.Cell.ofInt(0),                           // 12 勾选
                RecordMessages.Cell.ofInt64(0),                         // 13 剩余时间
                RecordMessages.Cell.ofInt(0),                           // 14 交易完成
                RecordMessages.Cell.ofDouble(0.0));                     // 15 保留 f64
    }

    /** 附件 XML（form_mail_read.lua ParseXmlData 口径：root 首子 Object@Config、其首子 Prop@Amount/ColorLevel）。 */
    static String letterAppendixXml(String configId, int amount) {
        return "<Root><Object Config=\"" + configId + "\"><Prop Amount=\"" + amount
                + "\" ColorLevel=\"0\"/></Object></Root>";
    }

    /** GM send_test_mail：任意物品按系统邮件附件发送（主界面邮件按钮查收，359 领取入包）。 */
    private void applySendTestMail(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null) {
            command.fail("角色尚未选定");
            return;
        }
        String configId = command.strParam("itemConfig").trim();
        int amount = Math.max(1, command.intParam("amount", 1));
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
        String serial = "gm" + clock.instant().toEpochMilli() + "-" + (session.letters.size() + 1);
        com.nineyin.server.game.avatar.mail.LetterStore.Letter letter =
                new com.nineyin.server.game.avatar.mail.LetterStore.Letter(
                        serial, LETTER_TYPE_SYSTEM_POST, "", "gm", "GM 测试邮件",
                        "GM 面板「礼包与物品」发送的系统邮件，附件可直接领取。",
                        "0", "0", letterAppendixXml(info.configId(), amount),
                        clock.instant().getEpochSecond(), false);
        session.letters.add(letter);
        persistLetters(session);
        if (session.lettersGranted) {
            connection.writeFrame(letterRowFrame(letter));
        }
        String name = info.name().isEmpty() ? info.configId() : info.name();
        String message = "系统邮件已发送（" + name + " x" + amount + "），主界面邮件按钮查收";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM send_test_mail {} x{} serial={}",
                session.machine.id(), info.configId(), amount, serial);
    }

    private void persistLetters(LoginSession session) {
        if (session.selected == null) {
            return;
        }
        try {
            letterStore.save(session.selected.id(), session.letters);
        } catch (RuntimeException e) {
            log.warn("session={} persist letters: {}", session.machine.id(), e.getMessage());
        }
    }

    /**
     * 邮件行变更（已读/领取）：0x12 删原行 + 0x11 尾插重推（快捷栏实证方言，
     * 不碰未验证的 0x13 单格帧）；服务端列表同步移到尾部保持行序 1:1。
     * 信件跳到列表底部是本方言的可接受代价。
     */
    private void reorderLetterRow(Connection connection, LoginSession session, int index,
                                  com.nineyin.server.game.avatar.mail.LetterStore.Letter updated) {
        connection.writeFrame(RecordMessages.recordDelRow(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                RecordMessages.RECORD_RECV_LETTER, index));
        session.letters.remove(index);
        session.letters.add(updated);
        if (session.lettersGranted) {
            connection.writeFrame(letterRowFrame(updated));
        }
        persistLetters(session);
    }

    /** C2S 353 custom_read_letter(serial)：置已读（READFLAG=1，行重推）。 */
    void handleReadLetter(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || custom.values.size() < 2) {
            return;
        }
        String serial = custom.values.get(1).textOrNull();
        if (serial == null || serial.isEmpty()) {
            return;
        }
        for (int i = 0; i < session.letters.size(); i++) {
            var letter = session.letters.get(i);
            if (serial.equals(letter.serial())) {
                if (!letter.readFlag()) {
                    reorderLetterRow(connection, session, i, letter.withRead(true));
                }
                return;
            }
        }
        log.info("session={} read letter unknown serial={}", session.machine.id(), serial);
    }

    /** C2S 359 custom_get_appendix(serial)：附件物品入包 + 清附件 + 置已读。 */
    void handleGetAppendix(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null || custom.values.size() < 2) {
            return;
        }
        String serial = custom.values.get(1).textOrNull();
        if (serial == null || serial.isEmpty()) {
            return;
        }
        for (int i = 0; i < session.letters.size(); i++) {
            var letter = session.letters.get(i);
            if (!serial.equals(letter.serial())) {
                continue;
            }
            String xml = letter.appendixXml();
            if (xml == null || xml.isEmpty() || !xml.contains("Object")) {
                log.info("session={} get appendix without content serial={}", session.machine.id(), serial);
                return;
            }
            String configId = xmlAttr(xml, "Config");
            String amountText = xmlAttr(xml, "Amount");
            if (configId == null || configId.isEmpty()) {
                return;
            }
            int amount = 1;
            if (amountText != null && !amountText.isEmpty()) {
                try {
                    amount = Math.max(1, Integer.parseInt(amountText.trim()));
                } catch (NumberFormatException ignored) {
                    // 附件串服务端自产；异常回退 1 件
                }
            }
            ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(configId);
            if (info == null || inventoryStore == null
                    || grantBagItem(connection, session, info, amount) == null) {
                log.warn("session={} appendix grant failed serial={} item={}",
                        session.machine.id(), serial, configId);
                return;
            }
            reorderLetterRow(connection, session, i, letter.withAppendix("").withRead(true));
            log.info("session={} appendix taken serial={} item={} x{}",
                    session.machine.id(), serial, configId, amount);
            return;
        }
        log.info("session={} get appendix unknown serial={}", session.machine.id(), serial);
    }

    /** 极简 XML 属性抽取（附件串服务端自产，格式受控；仅取首个命中属性值）。 */
    private static String xmlAttr(String xml, String attr) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(attr + "=\"([^\"]*)\"").matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
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
                effectiveShowEquipType(session), session.visual.sex(), equips, resolver));
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

    /**
     * equip_hair：GM「风物志头发」——仅收纯发型卡（只含 Hat 部件），
     * 挂载前互斥卸下已挂的其他 Hat-only 卡（发型一次一顶），其余部件挂载不受影响。
     */
    private void applyEquipHair(Connection connection, LoginSession session, GmCommand command) {
        Integer skinId = command.intParam("cardId");
        if (skinId == null || skinId <= 0) {
            command.fail("cardId 必须正整数");
            return;
        }
        var catalog = com.nineyin.server.game.avatar.skin.SkinCatalog.loadDefault();
        var skin = catalog.byId(skinId);
        if (skin == null) {
            command.fail("风物志皮肤不存在: " + skinId + "（rule/card.ini 未命中）");
            return;
        }
        if (!skin.hairOnly()) {
            command.fail("卡牌 " + skinId + " 不是纯发型卡（含非 Hat 部件："
                    + skinPartSummary(skin) + "），请用「风物志卡牌」区块装备");
            return;
        }
        var state = sessionSkinState(session);
        List<Integer> collected = new ArrayList<>(state == null ? List.of() : state.collected());
        if (!collected.contains(skinId)) {
            collected.add(skinId);
            java.util.Collections.sort(collected);
        }
        List<Integer> mounted = new ArrayList<>(state == null ? List.of() : state.mounted());
        int replaced = 0;
        // 同部件互斥：摘掉已挂的其他 Hat-only 卡（自身重复挂载=刷新到末位）。
        for (var it = mounted.iterator(); it.hasNext(); ) {
            Integer other = it.next();
            if (other.equals(skinId)) {
                it.remove();
                continue;
            }
            var otherSkin = catalog.byId(other);
            if (otherSkin != null && otherSkin.hairOnly()) {
                it.remove();
                replaced++;
            }
        }
        mounted.add(skinId);
        saveSkinState(session, new com.nineyin.server.game.avatar.skin.SkinStore.State(collected, mounted));
        for (byte[] frame : skinRefreshFrames(session, collected, mounted)) {
            connection.writeFrame(frame);
        }
        command.succeed("已装备发型 " + skinId + "（" + skinPartSummary(skin) + "）"
                + (replaced > 0 ? "，替换了 " + replaced + " 个旧发型" : "")
                + "，其余风物志挂载保持不变");
        log.info("session={} GM equip_hair={} replaced={}", session.machine.id(), skinId, replaced);
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

    /**
     * mount_ride：GM「坐骑管理」——按道具 config 直接激活并骑乘（未拥有自动补
     * NewRideRec 行，ValidTime=0 永久口径）；骑乘中再点同一坐骑 = 下马。面板承诺
     * 「已激活的坐骑再次使用不会消耗道具」，GM 通道零道具消耗。
     */
    private void applyMountRide(Connection connection, LoginSession session, GmCommand command) {
        String mountConfig = command.strParam("mountConfig");
        var catalog = com.nineyin.server.game.avatar.mount.MountCatalog.loadDefault();
        var mount = catalog.byConfig(mountConfig);
        if (mount == null) {
            command.fail("坐骑道具不存在或不可骑: " + mountConfig
                    + "（需 tool_item.ini script=Mount 且 itemartstatic 有模型映射）");
            return;
        }
        var state = sessionMountState(session);
        var current = state == null ? null : state.byPack(state.currentPack());
        if (session.riding && current != null && current.packId() == mount.packId()) {
            // 再点同骑 = 下马（选中保留，骑乘键可再上）。
            applyRideState(connection, session, current, false);
            command.succeed("已从 " + mount.name() + " [" + mount.configId() + "] 上下马（拥有保留）");
            log.info("session={} GM mount_ride dismount pack={}", session.machine.id(), mount.packId());
            return;
        }
        state = (state == null ? com.nineyin.server.game.avatar.mount.MountStore.State.empty() : state)
                .withOwned(mount.packId(), mount.configId())
                .withSelected(mount.packId());
        saveMountState(session, state);
        applyRideState(connection, session, state.byPack(mount.packId()), true);
        command.succeed("已骑乘 " + mount.name() + " [" + mount.configId() + "] 模型 "
                + mount.model() + "（NewRideRec 包 " + mount.packId() + "；再点同骑下马）");
        log.info("session={} GM mount_ride pack={} model={}",
                session.machine.id(), mount.packId(), mount.model());
    }

    /**
     * idle_stance_set：GM「待机站姿（持久化）」——空参 = 重推当前存档站姿
     * （恢复当前站姿按钮）；动作名须命中 b_action_index.ini。存档
     * data/待机站姿.json + role_idle_stance；下发通道 = 0x10 属性 PlayActionName
     * （备用通道 custom on_body_change ntype=1，真机不生效时切换）。
     */
    private void applyIdleStanceSet(Connection connection, LoginSession session, GmCommand command) {
        String raw = command.strParam("stanceAction");
        String action = raw == null ? "" : raw.trim();
        if (action.isEmpty()) {
            // 恢复当前站姿：不改档，按存档重推（受击/挖矿/技能结束把引擎动作打断后的手动恢复口）。
            reapplyIdleStance(connection, session);
            command.succeed(session.idleStance == null
                    ? "当前为默认站姿（无存档），无需恢复"
                    : "已重推当前站姿 [" + session.idleStance + "]");
            return;
        }
        var catalog = com.nineyin.server.game.avatar.stance.StanceCatalog.loadDefault();
        if (!catalog.exists(action)) {
            command.fail("站姿动作名不存在: " + action + "（b_action_index.ini "
                    + catalog.size() + " 节未命中，注意大小写）");
            return;
        }
        session.idleStance = action;
        try {
            idleStanceStore.save(session.selected.id(), action);
        } catch (RuntimeException e) {
            log.warn("session={} save idle stance: {}", session.machine.id(), e.getMessage());
        }
        pushIdleStance(connection, session);
        command.succeed("待机站姿已切换为 [" + action + "] 并持久化（重登/过图/技能回站保持）");
        log.info("session={} GM idle_stance_set={}", session.machine.id(), action);
    }

    /** 站姿单帧 0x10：PlayActionName=动作名（空串=回默认站姿）。 */
    private void pushIdleStance(Connection connection, LoginSession session) {
        List<com.nineyin.server.protocol.PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "PlayActionName", com.nineyin.server.protocol.PropertyValues.Value.ofString(
                session.idleStance == null ? "" : session.idleStance));
        connection.writeFrame(SceneMessages.objectProperties(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, 0, properties));
        session.idleStanceLastPushMs = clock.instant().toEpochMilli();
    }

    /** 回站重推：仅在有存档站姿时发（受击/挖矿/技能结束后引擎动作被打断）。 */
    private void reapplyIdleStance(Connection connection, LoginSession session) {
        if (session.idleStance == null || session.player == null) {
            return;
        }
        pushIdleStance(connection, session);
    }

    /**
     * 站姿保持拍（1s ticker 内 4s 一拍）：仅闲时（LogicState=0，非打坐/战斗/
     * 骑乘）重推。出招/坐姿面板不带 PlayActionName（186 瘦集），骑乘时引擎
     * 挂坐骑动画——这些态不推，回闲后 4s 内自愈回所选站姿。
     */
    private void idleStanceTick(Connection connection, LoginSession session) {
        if (session.idleStance == null || session.player == null) {
            return;
        }
        long now = clock.instant().toEpochMilli();
        if (now - session.idleStanceLastPushMs < 4000) {
            return;
        }
        if (session.player.effectiveLogicState(session.player.actorState()) != 0 || session.riding) {
            return;
        }
        session.idleStanceLastPushMs = now;
        pushIdleStance(connection, session);
    }

    // ------------------------------------------------------------ 伙伴系统（bot_*）

    /** 伙伴跟随停距（米，XZ 平面）：超出追击、以内停步面向主人。 */
    private static final float BOT_FOLLOW_STOP_DISTANCE = 2.5f;
    /** 伙伴辅助施法距离（米）：太远不施法（跟随时天然满足；驻足走远即停）。 */
    private static final float BOT_SUPPORT_RANGE_METERS = 12.0f;

    /**
     * 创建/校验伙伴（bot_ensure，幂等）：预设闭名单键（缺省 qinghe）；同预设
     * 在场则回执状态校验（0x0D 物化后客户端即可见），不在场（含切图世代失效）
     * 在玩家身前重新生成；换预设先摘旧再放新（辅助/跟随配置沿用会话值）。
     */
    private void applyBotEnsure(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("创建伙伴失败：角色尚未进入场景");
            return;
        }
        String key = command.strParam("bot").trim();
        GmBotPresets.Bot preset = key.isEmpty()
                ? GmBotPresets.defaultPreset() : GmBotPresets.preset(key);
        if (preset == null) {
            command.fail("未知伙伴预设: " + key + "（可用 " + GmBotPresets.keys() + "）");
            return;
        }
        if (botPresent(session)) {
            if (session.bot.preset.key().equals(preset.key())) {
                String message = "伙伴在场：" + botStateText(session);
                refreshBotSnapshot(session);
                gmHub.update(session.machine.id(), s -> s.lastAction = message);
                command.succeed(message);
                return;
            }
            removeGmSpawn(session, session.bot.objectId); // 换预设：先摘旧再放新
        }
        try {
            spawnBot(session, preset);
        } catch (IOException e) {
            command.fail("伙伴模板加载失败: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            command.fail("伙伴注册失败: " + e.getMessage());
            return;
        }
        String message = "已生成伙伴（" + botStateText(session)
                + "），默认跟随；辅助默认关（bot_support_configure 开启）";
        refreshBotSnapshot(session);
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
    }

    /**
     * 拉伙伴到身边（bot_pull_here）：不要求先 ensure（不在场即生成，使用当前
     * 场景）；在场则 0x1F 权威坐标瞬移到玩家身前（companionPlace 双写实体）。
     */
    private void applyBotPullHere(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("拉伙伴失败：角色尚未进入场景");
            return;
        }
        if (!ensureBot(session, command, "拉伙伴")) {
            return;
        }
        float[] at = gmSpawnPosition(session);
        session.world.companionPlace(session.bot.objectId,
                new com.nineyin.server.world.scene.WorldScene.Transform(at[0], at[1], at[2], at[3]));
        session.bot.moving = false;
        String message = "伙伴 " + session.bot.preset.name() + " 已拉到身前（对象 "
                + session.bot.objectId + "）";
        refreshBotSnapshot(session);
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
    }

    /** 跟随开关（bot_follow_start/stop）：start 不在场先 ensure；stop 驻足原地。 */
    private void applyBotFollow(Connection connection, LoginSession session,
                                GmCommand command, boolean start) {
        if (session.selected == null || session.world == null) {
            command.fail((start ? "开始跟随" : "停止跟随") + "失败：角色尚未进入场景");
            return;
        }
        if (start) {
            if (!ensureBot(session, command, "开始跟随")) {
                return;
            }
        } else if (!botPresent(session)) {
            command.fail("停止跟随失败：伙伴不在场");
            return;
        }
        session.bot.following = start;
        String message = "伙伴 " + session.bot.preset.name()
                + (start ? " 开始跟随" : " 已停止跟随（原地驻足）");
        refreshBotSnapshot(session);
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
    }

    /**
     * 复活伙伴（bot_revive）：回满血 + 清威胁/重生租约（FightState.reviveCompanion），
     * 0x10 NPC 战斗帧整血下发；阵亡期死亡由 killOverride 接管冻结（不吃 15s 重生）。
     */
    private void applyBotRevive(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.world == null) {
            command.fail("复活伙伴失败：角色尚未进入场景");
            return;
        }
        if (!ensureBot(session, command, "复活伙伴")) {
            return;
        }
        LoginSession.BotState bot = session.bot;
        SceneLifecycle.CombatNpcInfo info = session.world.combatNpcInfo(bot.objectId);
        int maxHp = info != null ? info.maxHp() : bot.preset.maxHp();
        boolean wasHurt = session.fight != null && session.fight.reviveCompanion(bot.objectId);
        connection.writeFrame(FightMessages.npcCombatFrame(
                bot.objectId, info != null ? info.ownerId() : 1,
                maxHp, maxHp, 0, false));
        String message = "伙伴 " + bot.preset.name() + " 已复活回满血（" + maxHp + "/" + maxHp
                + (wasHurt ? "）" : "，此前未受伤）");
        refreshBotSnapshot(session);
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
    }

    /**
     * 辅助配置（bot_support_configure）：enabled/intervalSec/heal 均可选（缺省
     * 保持现值）；未生成伙伴也可先存配置（ensure/自动重生时沿用）。
     */
    private void applyBotSupportConfigure(Connection connection, LoginSession session,
                                          GmCommand command) {
        if (session.bot == null) {
            LoginSession.BotState bot = new LoginSession.BotState();
            bot.preset = GmBotPresets.defaultPreset();
            bot.supportHeal = bot.preset.supportHeal();
            bot.supportIntervalSec = bot.preset.supportIntervalSec();
            session.bot = bot;
        }
        LoginSession.BotState bot = session.bot;
        Integer enabled = command.intParam("enabled");
        if (enabled != null) {
            bot.supportEnabled = enabled == 1;
        }
        Integer intervalSec = command.intParam("intervalSec");
        if (intervalSec != null) {
            bot.supportIntervalSec = intervalSec;
        }
        Integer heal = command.intParam("heal");
        if (heal != null) {
            bot.supportHeal = heal;
        }
        String message = "伙伴辅助已配置：" + (bot.supportEnabled ? "开" : "关")
                + "，每 " + bot.supportIntervalSec + "s 治疗玩家 " + bot.supportHeal + " 点";
        refreshBotSnapshot(session);
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM bot support configure enabled={} interval={}s heal={}",
                session.machine.id(), bot.supportEnabled, bot.supportIntervalSec, bot.supportHeal);
    }

    /**
     * GM 御风神水·挂（yufeng_add）：给玩家挂 buf_ride_yufeng 御风状态 10 分钟
     * （大轻功百鸟凌云一/二/三段冲刺的客户端条件包）。双通道同 QingGongHandler 缓降：
     * msg169 九值(add=0) 驱动 lua on_buffer_change + 0x10 BufferInfoN 槽串（BuffStore
     * 权威）。重复点击同槽续期（官方 ReplaceType=1 同 id 顶替语义）；到期未摘由
     * scheduler 补发摘除双帧（租约槽位/到期一致才动作，续期后旧任务空转）。
     */
    private void applyYufengAdd(Connection connection, LoginSession session, GmCommand command) {
        PlayerActor player = session.player;
        Instant now = player.clock().instant();
        int existing = player.reuseBuffSlot(PlayerActor.YUFENG_BUFF_CONFIG, now);
        int slot = existing > 0 ? existing : player.freeBuffSlot();
        if (slot <= 0) {
            command.fail("buff 槽 12..24 已满，先用「取消御风状态」或等租约到期");
            return;
        }
        boolean renewal = existing > 0;
        Instant expiresAt = now.plusMillis(PlayerActor.YUFENG_BUFF_LIFETIME_MS);
        String bufferInfo = player.activateBuff(slot, PlayerActor.YUFENG_BUFF_STATIC_DATA,
                PlayerActor.YUFENG_BUFF_CONFIG, 1, expiresAt);
        connection.writeFrame(FightMessages.timedBuff(0, PlayerActor.YUFENG_BUFF_CONFIG,
                PlayerActor.YUFENG_BUFF_CATEGORY, expiresAt.toEpochMilli(),
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, player.snapshotName()));
        connection.writeFrame(player.buffSlotFrame(slot, bufferInfo));
        // 到期自动摘（scheduler 缺席的极简测试流跳过——生产与 faculty ticker 同源必有）。
        if (scheduler != null) {
            long untilMs = expiresAt.toEpochMilli() - now.toEpochMilli();
            scheduler.schedule(() -> {
                if (session.player != player) {
                    return; // 重登换了 PlayerActor，旧租约随实例丢弃
                }
                PlayerActor.ActiveBuffLease lease = player.activeBuffLease(
                        PlayerActor.YUFENG_BUFF_CONFIG, player.clock().instant());
                if (lease == null || lease.slot() != slot
                        || !lease.expiresUtc().equals(expiresAt)) {
                    return; // 已被 yufeng_remove 摘除或重复 add 续期（新任务接管）
                }
                player.clearBuffSlot(slot);
                connection.writeFrame(FightMessages.timedBuff(1, PlayerActor.YUFENG_BUFF_CONFIG,
                        PlayerActor.YUFENG_BUFF_CATEGORY, expiresAt.toEpochMilli(),
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                        player.snapshotName()));
                connection.writeFrame(player.buffSlotFrame(slot, ""));
            }, Math.max(untilMs, 1), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        String message = (renewal ? "御风状态已续期" : "御风状态已添加")
                + "（槽 " + slot + "，10 分钟内可施展大轻功）";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM yufeng add slot={} expires={}",
                session.machine.id(), slot, expiresAt);
    }

    /**
     * GM 御风神水·摘（yufeng_remove）：msg169 九值(add=1) + 空槽帧（客户端 BuffStore
     * 按槽权威，releaseGlide 同款）；未持有时也回 msg169 摘除帧做客户端重同步。
     */
    private void applyYufengRemove(Connection connection, LoginSession session, GmCommand command) {
        PlayerActor player = session.player;
        Instant now = player.clock().instant();
        PlayerActor.ActiveBuffLease lease =
                player.activeBuffLease(PlayerActor.YUFENG_BUFF_CONFIG, now);
        long endMs = lease == null ? now.toEpochMilli() : lease.expiresUtc().toEpochMilli();
        int slot = lease == null ? -1 : lease.slot();
        if (slot > 0) {
            player.clearBuffSlot(slot);
        }
        connection.writeFrame(FightMessages.timedBuff(1, PlayerActor.YUFENG_BUFF_CONFIG,
                PlayerActor.YUFENG_BUFF_CATEGORY, endMs,
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, player.snapshotName()));
        if (slot > 0) {
            connection.writeFrame(player.buffSlotFrame(slot, ""));
        }
        String message = slot > 0 ? "御风状态已取消（槽 " + slot + "）" : "当前没有御风状态（已补发重同步帧）";
        gmHub.update(session.machine.id(), s -> s.lastAction = message);
        command.succeed(message);
        log.info("session={} GM yufeng remove slot={}", session.machine.id(), slot);
    }

    /** 伙伴在场（当前场景世代）：bot 态非空且 gmSpawns 仍有该摆放（切图随 begin 清空）。 */
    private boolean botPresent(LoginSession session) {
        if (session.bot == null) {
            return false;
        }
        LoginSession.GmSpawn spawn = session.gmSpawns.get(session.bot.objectId);
        return spawn != null && spawn.bot;
    }

    /** 伙伴不在场则按当前/缺省预设生成（拉人/跟随/复活不要求先 ensure；失败即回执）。 */
    private boolean ensureBot(LoginSession session, GmCommand command, String what) {
        if (botPresent(session)) {
            return true;
        }
        GmBotPresets.Bot preset = session.bot != null
                ? session.bot.preset : GmBotPresets.defaultPreset();
        try {
            spawnBot(session, preset);
        } catch (IOException | RuntimeException e) {
            command.fail(what + "失败：伙伴生成失败 " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * 生成伙伴：预设模板克隆（名字/血量/跑速覆盖）→ GM 摆放（bot 口径）→
     * FightState 伙伴标记（反击 AI 跳过 + 死亡冻结）→ reconcile 物化 0x0D+0x1F。
     */
    private int spawnBot(LoginSession session, GmBotPresets.Bot preset) throws IOException {
        float[] at = gmSpawnPosition(session);
        NpcSpawn npc = com.nineyin.server.world.npc.GmNpcFactory.spawnFromTemplate(
                preset.table(), preset.template(), at[0], at[1], at[2], at[3],
                Map.of("Name", preset.name(),
                        "MaxHP", Integer.toString(preset.maxHp()),
                        "HP", Integer.toString(preset.maxHp()),
                        "RunSpeed", Float.toString(preset.runSpeed())));
        int objectId = registerGmSpawn(session, npc, false, false, false, false, true);
        if (session.bot == null) {
            LoginSession.BotState bot = new LoginSession.BotState();
            bot.preset = preset;
            bot.supportHeal = preset.supportHeal();
            bot.supportIntervalSec = preset.supportIntervalSec();
            session.bot = bot;
        } else {
            session.bot.preset = preset;
        }
        session.bot.objectId = objectId;
        session.bot.moving = false;
        session.bot.lastHealAtMs = 0;
        if (session.fight != null) {
            session.fight.markCompanion(objectId);
        }
        session.world.reconcileViewport();
        log.info("session={} GM bot {} ({}) spawned as object {} at ({},{},{})",
                session.machine.id(), preset.name(), preset.key(), objectId, at[0], at[1], at[2]);
        return objectId;
    }

    /** 伙伴状态文案（回执/面板用）：名字/对象号/血量/阵亡/跟随/辅助。 */
    private String botStateText(LoginSession session) {
        LoginSession.BotState bot = session.bot;
        SceneLifecycle.CombatNpcInfo info = session.world != null
                ? session.world.combatNpcInfo(bot.objectId) : null;
        int maxHp = info != null ? info.maxHp() : bot.preset.maxHp();
        FightState.NpcCombat combat = session.fight != null
                ? session.fight.npc(bot.objectId) : null;
        int hp = combat != null ? combat.state().hp() : maxHp;
        boolean alive = session.fight == null || session.fight.npcAlive(bot.objectId);
        return bot.preset.name() + " 对象 " + bot.objectId + " 血 " + hp + "/" + maxHp
                + (alive ? "" : "（已阵亡）")
                + (bot.following ? " 跟随中" : " 驻足")
                + " 辅助" + (bot.supportEnabled
                        ? "开 " + bot.supportHeal + "/" + bot.supportIntervalSec + "s" : "关");
    }

    /** 伙伴快照摘要写回 GM 面板（sessions().bot 数据源）。 */
    private void refreshBotSnapshot(LoginSession session) {
        if (session.bot == null) {
            return;
        }
        SceneLifecycle.CombatNpcInfo info = session.world != null
                ? session.world.combatNpcInfo(session.bot.objectId) : null;
        int maxHp = info != null ? info.maxHp() : session.bot.preset.maxHp();
        FightState.NpcCombat combat = session.fight != null
                ? session.fight.npc(session.bot.objectId) : null;
        int hp = combat != null ? combat.state().hp() : maxHp;
        boolean alive = session.fight == null || session.fight.npcAlive(session.bot.objectId);
        String name = session.bot.preset.name();
        boolean following = session.bot.following;
        String support = session.bot.supportEnabled
                ? "开 " + session.bot.supportHeal + "/" + session.bot.supportIntervalSec + "s" : "关";
        gmHub.update(session.machine.id(), s -> {
            s.botName = name;
            s.botHp = alive ? hp : 0;
            s.botMaxHp = maxHp;
            s.botFollowing = following;
            s.botSupport = support;
        });
    }

    /**
     * 伙伴拍（1s ticker，与 GM 命令泵同线程串行）：
     *
     * <ul>
     *   <li>世代失效自动重生：切图/重登后 gmSpawns 清空，本拍在玩家身边按预设
     *       重生成（跨场景跟随），本拍只落位不追击。</li>
     *   <li>跟随（0x21 官方口径）：目的地=玩家实时位（pendingLocation），服务端
     *       权威坐标按速度×1s 步进；到点发一次 moving=0 停步 + 面向主人。</li>
     *   <li>辅助（按配置周期）：msg337+msg20 伙伴施法帧（NpcCombatAi 出招同型）
     *       + 玩家 restore 回血 + vitalUpdate 面板帧。</li>
     * </ul>
     */
    void companionTick(Connection connection, LoginSession session) {
        if (session.bot == null || session.world == null || session.player == null) {
            return;
        }
        if (!botPresent(session)) {
            try {
                spawnBot(session, session.bot.preset);
                refreshBotSnapshot(session);
            } catch (IOException | RuntimeException e) {
                log.warn("session={} respawn bot: {}", session.machine.id(), e.getMessage());
            }
            return;
        }
        SceneLifecycle.CombatNpcInfo info = session.world.combatNpcInfo(session.bot.objectId);
        if (info == null) {
            return;
        }
        Roles.Location location = session.pendingLocation != null
                ? session.pendingLocation : session.selected.location();
        Roles.Position target = location.position();
        companionFollowTick(session, info, target);
        companionSupportTick(connection, session, info, target);
    }

    /** 跟随拍：追击走 0x21（目的地=玩家位），到点/关跟随发一次停步（moving=0 + 面向主人）。 */
    private void companionFollowTick(LoginSession session, SceneLifecycle.CombatNpcInfo info,
                                     Roles.Position target) {
        LoginSession.BotState bot = session.bot;
        float dx = target.x() - info.x();
        float dz = target.z() - info.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (bot.following && session.player.actorState().hp() > 0
                && distance > BOT_FOLLOW_STOP_DISTANCE) {
            float speed = bot.preset.runSpeed();
            float travel = (float) Math.min(speed, distance); // 1s 一拍：步长=速度×1s
            float ratio = (float) (travel / distance);
            com.nineyin.server.world.scene.WorldScene.Transform stepped =
                    new com.nineyin.server.world.scene.WorldScene.Transform(
                            info.x() + dx * ratio,
                            info.y() + (target.y() - info.y()) * ratio,
                            info.z() + dz * ratio,
                            (float) Math.atan2(dz, dx));
            com.nineyin.server.world.scene.WorldScene.Transform dest =
                    new com.nineyin.server.world.scene.WorldScene.Transform(
                            target.x(), target.y(), target.z(), stepped.orient());
            session.world.companionStep(bot.objectId, stepped, dest, speed, true);
            bot.moving = true;
            return;
        }
        if (bot.moving) {
            // 停步：0x21 moving=0（客户端收步动画），朝向=面向主人。
            float facing = (float) Math.atan2(target.z() - info.z(), target.x() - info.x());
            com.nineyin.server.world.scene.WorldScene.Transform at =
                    new com.nineyin.server.world.scene.WorldScene.Transform(
                            info.x(), info.y(), info.z(), facing);
            session.world.companionStep(bot.objectId, at, at, 0f, false);
            bot.moving = false;
        }
    }

    /** 辅助拍：伙伴存活 + 玩家掉血 + 距离内 + 到周期 → 施法帧 + 回血 + vitalUpdate。 */
    private void companionSupportTick(Connection connection, LoginSession session,
                                      SceneLifecycle.CombatNpcInfo info, Roles.Position target) {
        LoginSession.BotState bot = session.bot;
        if (!bot.supportEnabled || bot.supportHeal <= 0) {
            return;
        }
        var state = session.player.actorState();
        if (state.hp() <= 0 || state.hp() >= state.maxHp()) {
            return;
        }
        if (session.fight != null && !session.fight.npcAlive(bot.objectId)) {
            return; // 伙伴阵亡不施法（bot_revive 复活）
        }
        float dx = target.x() - info.x();
        float dz = target.z() - info.z();
        if (dx * dx + dz * dz > BOT_SUPPORT_RANGE_METERS * BOT_SUPPORT_RANGE_METERS) {
            return;
        }
        long now = clock.instant().toEpochMilli();
        if (now - bot.lastHealAtMs < bot.supportIntervalSec * 1000L) {
            return;
        }
        bot.lastHealAtMs = now;
        // 施法两帧（NpcCombatAi 出招同款：msg337 伙伴位姿 + msg20 伙伴施法，目标=玩家）。
        connection.writeFrame(FightMessages.casterTransformSync(
                info.id(), info.ownerId(), info.x(), info.y(), info.z(), info.orient()));
        connection.writeFrame(FightMessages.skillAction(
                info.id(), info.ownerId(), bot.preset.supportSkill(),
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID));
        int heal = Math.min(bot.supportHeal, state.maxHp() - state.hp());
        if (heal > 0 && session.player.restore(heal, 0)) {
            connection.writeFrame(session.player.vitalUpdate());
        }
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

    // ---------------- 装备穿/脱（msg30 移动物品）+ msg31 使用物品 + msg886-889 一键穿 ----------------

    /**
     * C2S 30 移动物品（menu2.exe `MOVEITEM (%d,%d)->(%d,%d)` 钉死）：
     * `[30, fromView, fromSlot, toView, toSlot]`。脱下=[1,槽,121,0]（toSlot=0 自动
     * 选格）；穿上=[121,格,1,身体槽]；背包页间移动同构。帧：源/目标 0x19 清格
     * （页与镜像成对）+ 0x18 落位；触及纸娃娃时刷外观/词缀/vital。扩展页目标 =
     * 扩容包 addtoolbox 链路（后续任务）拒绝。
     */
    void handleMoveItem(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        List<Integer> args = customIntArgs(custom);
        if (inventoryStore == null || args.size() < 4) {
            log.info("session={} move 30 malformed args={} (need fromView,fromSlot,toView,toSlot)",
                    session.machine.id(), args);
            return;
        }
        int fromView = args.get(0);
        int fromSlot = args.get(1);
        int toView = args.get(2);
        int toSlot = args.get(3);
        if (fromView == InventoryMessages.VIEW_DOLL) {
            unequipFromDoll(connection, session, fromSlot, toView, toSlot);
        } else if (toView == InventoryMessages.VIEW_DOLL) {
            wearFromBag(connection, session, fromView, fromSlot, toSlot);
        } else if (InventoryMessages.pageOfExtView(toView) != 0
                || InventoryMessages.pageOfExtView(fromView) != 0) {
            log.info("session={} move 30 ext view ({},{})->({},{}) rejected "
                            + "(bagpack addtoolbox not implemented)",
                    session.machine.id(), fromView, fromSlot, toView, toSlot);
        } else {
            moveBagRow(connection, session, fromView, fromSlot, toView, toSlot);
        }
    }

    /**
     * 脱下：装备栏(身体槽位) → 背包页（toView 须是背包页否则归装备页 121，
     * toSlot<=0 自动选该页首空格）。
     */
    private void unequipFromDoll(Connection connection, LoginSession session,
                                 int equipSlot, int toView, int toSlot) {
        long roleId = session.selected.id();
        RoleInventoryStore.Equip item = equipAt(loadEquips(session), equipSlot);
        if (item == null) {
            log.info("session={} move 30 equip[{}] empty", session.machine.id(), equipSlot);
            return;
        }
        int category = InventoryMessages.bagCategoryOf(toView);
        int view = category != 0 ? toView : InventoryMessages.VIEW_EQUIP_BAG;
        if (category == 0) {
            category = 2;
        }
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null) {
            return;
        }
        int slot = toSlot > 0 ? toSlot : freeBagSlot(bagItems, category);
        if (slot <= 0) {
            log.info("session={} move 30 unequip reject: bag page {} full",
                    session.machine.id(), view);
            return;
        }
        if (!inventoryStore.moveEquipToBag(roleId, equipSlot, slot)) {
            log.warn("session={} move 30 equip[{}]->bag[{}] persist failed",
                    session.machine.id(), equipSlot, slot);
            return;
        }
        // 帧组：纸娃娃清格（时装/身份装含镜像 40-43）→ 入包成对帧 → 外观/词缀/vital 刷新。
        connection.writeFrame(SceneMessages.viewRemove(InventoryMessages.VIEW_DOLL, equipSlot));
        int mirror = AppearanceSync.mirrorSlot(equipSlot);
        if (mirror != equipSlot) {
            connection.writeFrame(SceneMessages.viewRemove(InventoryMessages.VIEW_DOLL, mirror));
        }
        RoleInventoryStore.BagItem landed = new RoleInventoryStore.BagItem(
                slot, item.configId(), item.itemType(), 1, category, "",
                item.equipType(), 0, "", null, null, item.artPack(),
                item.hardiness(), item.maxHardiness());
        pushBagItemFrames(connection, session, landed);
        refreshAfterEquipChange(connection, session);
        log.info("session={} move 30 equip[{}]->bag[{}:{}] config={}",
                session.machine.id(), equipSlot, view, slot, item.configId());
    }

    /**
     * 共享穿装核心（msg30 穿上 / msg31 右键穿 / msg886 一键穿，bak wearBagItem
     * 同构）：校验可穿（EquipType → 槽位，toSlot<=0 自动槽=双槽类型首个空槽）→
     * 目标槽被占时旧装备让位到装备页首空格（swapEquipSlot）→ moveBagToEquip →
     * 源格清帧（页+镜像）+ 旧装备入包帧 → pushWearFrames（纸娃娃行+外观+词缀+vital）。
     *
     * @return 穿戴成功（拒绝/落库失败为 false，均已打日志）
     */
    private boolean wearFromBag(Connection connection, LoginSession session,
                                int fromView, int fromSlot, int toSlot) {
        if (inventoryStore == null) {
            return false;
        }
        long roleId = session.selected.id();
        int category = InventoryMessages.bagCategoryOf(fromView);
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null || category == 0) {
            log.info("session={} wear reject: source view {} not a bag page",
                    session.machine.id(), fromView);
            return false;
        }
        RoleInventoryStore.BagItem item = bagItemAt(bagItems, fromView, fromSlot);
        if (item == null) {
            log.info("session={} wear reject: bag[{}:{}] empty", session.machine.id(), fromView, fromSlot);
            return false;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(item.configId());
        String equipType = info != null && !info.equipType().isEmpty()
                ? info.equipType()
                : (item.equipType() == null ? "" : item.equipType());
        List<RoleInventoryStore.Equip> equips = loadEquips(session);
        int slot = toSlot > 0 ? toSlot : AppearanceSync.equipSlotFor(equipType, equips);
        if (equipType.isEmpty() || slot <= 0) {
            log.info("session={} wear now requires one wearable equipment item (config={} type={})",
                    session.machine.id(), item.configId(), equipType);
            return false;
        }
        RoleInventoryStore.Equip old = equipAt(equips, slot);
        int oldSlot = 0;
        if (old != null) {
            oldSlot = freeBagSlot(bagItems, 2);
            if (oldSlot <= 0) {
                log.info("session={} wear reject: bag full, cannot swap out {}",
                        session.machine.id(), old.configId());
                return false;
            }
            if (!inventoryStore.swapEquipSlot(roleId, slot, oldSlot)) {
                log.warn("session={} wear swap equip[{}]->bag[{}] persist failed",
                        session.machine.id(), slot, oldSlot);
                return false;
            }
        }
        if (!inventoryStore.moveBagToEquip(roleId, fromSlot, slot, category)) {
            log.warn("session={} wear bag[{}]->equip[{}] persist failed",
                    session.machine.id(), fromSlot, slot);
            return false;
        }
        connection.writeFrame(SceneMessages.viewRemove(fromView, fromSlot));
        int fromMirror = InventoryMessages.bagMirrorOf(fromView);
        if (fromMirror != fromView) {
            connection.writeFrame(SceneMessages.viewRemove(fromMirror, fromSlot));
        }
        if (old != null) {
            RoleInventoryStore.BagItem landed = new RoleInventoryStore.BagItem(
                    oldSlot, old.configId(), old.itemType(), 1, 2, "",
                    old.equipType(), 0, "", null, null, old.artPack(),
                    old.hardiness(), old.maxHardiness());
            pushBagItemFrames(connection, session, landed);
        }
        RoleInventoryStore.Equip worn = equipAt(loadEquips(session), slot);
        if (worn == null) {
            worn = new RoleInventoryStore.Equip(item.configId(), item.itemType(), equipType, slot,
                    item.hardiness() > 0 ? item.hardiness() : 100,
                    item.maxHardiness() > 0 ? item.maxHardiness() : 100,
                    item.artPack(), "");
        }
        pushWearFrames(connection, session, worn);
        log.info("session={} wear config={} -> slot={}{}", session.machine.id(), item.configId(), slot,
                old != null ? " (swapped out " + old.configId() + ")" : "");
        return true;
    }

    /** 背包页间/页内移动（非背包页拒绝；toSlot<=0 自动选目标页首空格）。 */
    private void moveBagRow(Connection connection, LoginSession session,
                            int fromView, int fromSlot, int toView, int toSlot) {
        long roleId = session.selected.id();
        int fromCategory = InventoryMessages.bagCategoryOf(fromView);
        int toCategory = InventoryMessages.bagCategoryOf(toView);
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null || fromCategory == 0 || toCategory == 0) {
            log.info("session={} move 30 reject: view ({})/({}) not a bag page",
                    session.machine.id(), fromView, toView);
            return;
        }
        RoleInventoryStore.BagItem item = bagItemAt(bagItems, fromView, fromSlot);
        if (item == null) {
            log.info("session={} move 30 bag[{}:{}] empty", session.machine.id(), fromView, fromSlot);
            return;
        }
        int slot = toSlot > 0 ? toSlot : freeBagSlot(bagItems, toCategory);
        if (slot <= 0) {
            log.info("session={} move 30 reject: bag page {} full", session.machine.id(), toView);
            return;
        }
        if (!inventoryStore.moveBagSlot(roleId, fromSlot, fromCategory, slot, toCategory)) {
            log.warn("session={} move 30 bag[{}]->bag[{}] persist failed",
                    session.machine.id(), fromSlot, slot);
            return;
        }
        connection.writeFrame(SceneMessages.viewRemove(fromView, fromSlot));
        int fromMirror = InventoryMessages.bagMirrorOf(fromView);
        if (fromMirror != fromView) {
            connection.writeFrame(SceneMessages.viewRemove(fromMirror, fromSlot));
        }
        RoleInventoryStore.BagItem moved = new RoleInventoryStore.BagItem(
                slot, item.configId(), item.itemType(), item.amount(), toCategory,
                item.name(), item.equipType(), item.colorLevel(), item.script(),
                item.anchorSlot(), item.expiresAt(), item.artPack(),
                item.hardiness(), item.maxHardiness());
        pushBagItemFrames(connection, session, moved);
        log.info("session={} move 30 bag[{}:{}]{}->bag[{}:{}] config={}",
                session.machine.id(), fromView, fromSlot, fromCategory, toView, slot,
                item.configId());
    }

    /**
     * C2S 31 使用物品（menu2.exe `reject malformed native item use values=` +
     * `USEITEM fwz card` 钉死）：恰好 4 值 `[31, view(int), slot(int), count(int≥1)]`，
     * 值数不符/count=0 拒绝。按物品类型分流：装备 → 右键穿（自动槽）；风物志卡片
     * （CardItem）→ 使用收录链；其余（食物/秘籍/坐骑等子链）记日志钩子。
     */
    /**
     * C2S 34 custom_delete_item(view,pos,amount)（道具丢弃/销毁）。客户端链
     * （game_hand.lua 拖出道包 → 确认框 → CustomSend(34,viewid,pos,amount)）；
     * CantDelete 静态表与重要道具（color_level≥4/内功书）仅本地弹窗拦截，
     * 服务端按分类页+格子+数量权威扣减：非背包页/空格/超量拒绝。回包与
     * msg31 消耗同链——剩余&gt;0 remove+add 堆叠刷新，=0 双 remove 清镜像
     * 页，无专用回包。
     */
    void handleDeleteItem(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null || inventoryStore == null) {
            return;
        }
        List<Integer> args = customIntArgs(custom);
        if (args.size() != 3) {
            log.info("session={} delete 34 malformed args={} (need view,pos,amount)",
                    session.machine.id(), custom.values.size());
            return;
        }
        int view = args.get(0);
        int slot = args.get(1);
        int amount = args.get(2);
        int category = InventoryMessages.bagCategoryOf(view);
        if (category == 0) {
            log.info("session={} delete 34 reject: view={} not a bag page",
                    session.machine.id(), view);
            return;
        }
        // 扩展页归一主分类页（bagItemAt 按 bagViewOf 主 view 匹配，扩展镜像页会 miss）
        view = InventoryMessages.bagViewOf(category);
        if (amount < 1) {
            log.info("session={} delete 34 reject: amount={}", session.machine.id(), amount);
            return;
        }
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null) {
            return;
        }
        RoleInventoryStore.BagItem item = bagItemAt(bagItems, view, slot);
        if (item == null) {
            log.info("session={} delete 34 reject: bag[{}:{}] empty",
                    session.machine.id(), view, slot);
            return;
        }
        if (amount > item.amount()) {
            log.info("session={} delete 34 reject: {} amount {} > stack {}",
                    session.machine.id(), item.configId(), amount, item.amount());
            return;
        }
        if (!consumeBagItemCount(connection, session, item, amount)) {
            log.info("session={} delete 34 {}: consume failed (row missing/changed)",
                    session.machine.id(), item.configId());
            return;
        }
        log.info("session={} delete 34 bag[{}:{}] config={} amount={} discarded",
                session.machine.id(), view, slot, item.configId(), amount);
    }

    void handleBodyItemUse(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        if (custom.values.size() != 4) {
            log.info("session={} reject malformed native item use values={} (need exactly 4)",
                    session.machine.id(), custom.values.size());
            return;
        }
        List<Integer> args = customIntArgs(custom);
        if (args.size() < 3) {
            log.info("session={} reject malformed native item use: non-int arg",
                    session.machine.id());
            return;
        }
        int view = args.get(0);
        int slot = args.get(1);
        int count = args.get(2);
        if (count < 1) {
            log.info("session={} reject native item use: count={}", session.machine.id(), count);
            return;
        }
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null) {
            return;
        }
        RoleInventoryStore.BagItem item = bagItemAt(bagItems, view, slot);
        if (item == null) {
            log.info("session={} native item use reject: bag[{}:{}] empty",
                    session.machine.id(), view, slot);
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(item.configId());
        boolean wearable = info != null && !info.equipType().isEmpty();
        if (wearable) {
            wearFromBag(connection, session, view, slot, 0);
            return;
        }
        if (info != null && info.isSkinCard()) {
            collectSkin(connection, session, item, true);
            return;
        }
        useItemEffect(connection, session, item, view);
    }

    /**
     * 道具使用效果核心（msg31 不可穿戴/非卡片分支，对齐 bak SceneFlow.useItem 五步）：
     * scripts/item/&lt;configId&gt;.lua 的 on_use 优先 → INI 回退（{@link ItemUseCatalog}：
     * giftbox/tool_item 箱类按 drops_mvp.json 开包、item_use_skill_static 回血阶梯）
     * → 均无则维持 use-chain not implemented 口径。落账顺序：效果先行（给物品容量
     * 感知、回血/回蓝），随后消耗 1 个（堆叠感知）并回背包帧对；会话级 800ms 节流
     * 防连点；随机数 Java 预掷（会话 combatRandom，测试可换种子）。temp_stat 已解析
     * 未接（面板限时属性二期）——仅剩 temp_stat 效果时不消耗防白吃。
     */
    private void useItemEffect(Connection connection, LoginSession session,
                               RoleInventoryStore.BagItem item, int view) {
        if (inventoryStore == null || session.selected == null || session.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastItemUseMs < 800) {
            return;
        }
        var actorState = session.player.actorState();
        java.util.Random random = session.combatRandom;
        // ① 效果解析：Lua 脚本优先（无脚本/nil 返回 → null → INI 回退）
        LuaScriptService.ItemUse instr = LuaScriptService.getInstance().itemUse(
                item.configId(),
                new LuaScriptService.ItemUseContext(item.amount(), now,
                        actorState.hp(), actorState.maxHp(), actorState.mp(), actorState.maxMp(),
                        session.player.silver(), random.nextDouble(), random.nextDouble()));
        // ② INI 回退：礼包开包（CompensateItem 多 ref 各开一次，func-56 条件门控未接=全发）
        // / 使用技能回血阶梯
        List<DropPacks.GrantItem> grants = null;
        int healHp = instr != null ? instr.healHp() : 0;
        int healMp = instr != null ? instr.healMp() : 0;
        if (instr != null) {
            if (instr.openDropId() != null) {
                grants = DropPacks.loadDefault().roll(
                        instr.openDropId(), instr.rolls(), random::nextDouble);
                if (grants.isEmpty()) {
                    log.info("item use {}: drop pack {} empty/missing", item.configId(),
                            instr.openDropId());
                    return;
                }
            } else if (!instr.grants().isEmpty()) {
                grants = instr.grants();
            }
        } else {
            ItemUseCatalog.UseEntry entry = ItemUseCatalog.loadDefault()
                    .resolve(item.configId(), item.itemType());
            if (entry == null) {
                log.info("session={} use item {} (view={} pos={}) use-chain not implemented",
                        session.machine.id(), item.configId(), view, item.slot());
                return;
            }
            switch (entry.kind()) {
                case BOX -> {
                    DropPacks packs = DropPacks.loadDefault();
                    grants = new ArrayList<>();
                    for (String dropId : entry.dropIds()) {
                        grants.addAll(packs.roll(dropId, 1, random::nextDouble));
                    }
                    if (grants.isEmpty()) {
                        log.info("item use {}: drop packs {} empty/missing", item.configId(),
                                entry.dropIds());
                        return;
                    }
                }
                case USE_SKILL -> {
                    healHp = ItemUseCatalog.fallbackHealHp(entry.skillLevel());
                    healMp = ItemUseCatalog.fallbackHealMp(entry.skillLevel());
                }
            }
        }
        // 仅剩未实施的 temp_stat：不消耗防白吃（其余空效果维持 bak 口径）
        var tempStat = instr != null ? instr.tempStat() : null;
        if (tempStat != null && healHp <= 0 && healMp <= 0 && grants == null) {
            log.info("item use {}: temp_stat 未接（面板限时属性二期），跳过消耗", item.configId());
            return;
        }
        if (healHp <= 0 && healMp <= 0 && grants == null && tempStat == null) {
            log.info("item use {}: instructions empty", item.configId());
            return;
        }
        session.lastItemUseMs = now;
        // ③ 效果落账：给物品（容量感知，失败即断；部分发放后仍走消耗，防重复刷取）
        List<String> grantedIds = new ArrayList<>();
        if (grants != null) {
            for (DropPacks.GrantItem grant : grants) {
                ToolItemCatalog.ItemInfo grantInfo = ToolItemCatalog.loadDefault()
                        .lookup(grant.configId());
                if (grantInfo == null) {
                    log.info("item use {}: grant {} unknown config", item.configId(), grant.configId());
                    break;
                }
                if (grantBagItem(connection, session, grantInfo, grant.amount()) == null) {
                    log.info("item use {}: grant {} failed", item.configId(), grant.configId());
                    break;
                }
                grantedIds.add(grant.configId() + "x" + grant.amount());
            }
        }
        // ④ 回血/回蓝（死亡/满血在 Actor.restore 内闸掉）
        boolean vitalChanged = session.player.restore(healHp, healMp);
        if (vitalChanged) {
            connection.writeFrame(session.player.vitalUpdate());
        }
        // ⑤ 消耗 1 个（脚本 consume=false 跳过）+ 背包帧对
        boolean consume = instr == null || instr.consume();
        if (consume && !consumeBagItemCount(connection, session, item, 1)) {
            log.info("item use {}: consume failed (row missing/changed)", item.configId());
        }
        log.info("item use {} consumed={} healHp={} healMp={} granted={} tempStat={}",
                item.configId(), consume, healHp, healMp, grantedIds, tempStat != null);
    }

    /**
     * C2S 886~889 一键穿（menu2.exe `onestep equip uid=%s` / `not found in bags`）：
     * 按背包 uid（ViewAdd 下发的 MD5(roleId:bag:slot) 前 8 字节 hex）认领物品，
     * 穿装走 {@link #wearFromBag} 自动槽。
     */
    void handleOnestepEquip(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null || inventoryStore == null) {
            return;
        }
        String uid = custom.values.size() > 1 ? custom.values.get(1).textOrNull() : null;
        if (uid == null || uid.isEmpty()) {
            log.info("session={} wear now requires one wearable equipment item (no uid vals={})",
                    session.machine.id(), custom.values.size());
            return;
        }
        List<RoleInventoryStore.BagItem> bagItems = safeBagItems(session);
        if (bagItems == null) {
            return;
        }
        long roleId = session.selected.id();
        for (RoleInventoryStore.BagItem item : bagItems) {
            if (InventoryMessages.uniqueId(roleId, "bag", item.slot()).equals(uid)) {
                log.info("session={} onestep equip uid={} config={}",
                        session.machine.id(), uid, item.configId());
                wearFromBag(connection, session, InventoryMessages.bagViewOf(item.viewId()),
                        item.slot(), 0);
                return;
            }
        }
        log.info("session={} onestep equip uid={} not found in bags", session.machine.id(), uid);
    }

    /** 装备栏按身体槽位查行。 */
    private static RoleInventoryStore.Equip equipAt(List<RoleInventoryStore.Equip> equips, int slot) {
        for (RoleInventoryStore.Equip equip : equips) {
            if (equip.slot() == slot) {
                return equip;
            }
        }
        return null;
    }

    /** 背包 (wire view,slot) → 行（未命中返回 null）。 */
    private static RoleInventoryStore.BagItem bagItemAt(List<RoleInventoryStore.BagItem> bagItems,
                                                        int view, int slot) {
        for (RoleInventoryStore.BagItem item : bagItems) {
            if (item.slot() == slot && InventoryMessages.bagViewOf(item.viewId()) == view) {
                return item;
            }
        }
        return null;
    }

    /** 分类页第一个空格（1..90，同 insertBagItem 口径；无空位返回 0）。 */
    private static int freeBagSlot(List<RoleInventoryStore.BagItem> bagItems, int category) {
        boolean[] used = new boolean[InventoryMessages.BAG_SLOT_MAX + 1];
        for (RoleInventoryStore.BagItem item : bagItems) {
            if (item.viewId() == category && item.slot() > 0
                    && item.slot() <= InventoryMessages.BAG_SLOT_MAX) {
                used[item.slot()] = true;
            }
        }
        for (int slot = 1; slot <= InventoryMessages.BAG_SLOT_MAX; slot++) {
            if (!used[slot]) {
                return slot;
            }
        }
        return 0;
    }

    /** 脱下后的外观合并/词缀重算/vital 刷新（穿上侧 pushWearFrames 已含同序）。 */
    private void refreshAfterEquipChange(Connection connection, LoginSession session) {
        if (session.visual == null) {
            return;
        }
        List<RoleInventoryStore.Equip> equips = loadEquips(session);
        EquipmentResolver resolver = EquipmentResolver.loadDefault();
        session.visual = AppearanceSync.mergeVisual(session.visual, session.visual.sex(), equips, resolver);
        connection.writeFrame(AppearanceSync.appearanceFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                effectiveShowEquipType(session), session.visual.sex(), equips, resolver));
        applyEquipBonuses(session, equips);
        if (session.player != null) {
            connection.writeFrame(session.player.vitalUpdate());
        }
    }

    // ---------------- V3 风物志：卡片收录（msg31 使用链）+ 衣橱换装 ----------------

    /** 使用卡片收录：消耗 1 张（consume=true 且库可用）→ 收录皮肤（不自动挂载，衣橱内自行换装）。 */
    private void collectSkin(Connection connection, LoginSession session,
                             RoleInventoryStore.BagItem item, boolean consume) {
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(item.configId());
        if (info == null || !info.isSkinCard()) {
            return;
        }
        int skinId = info.cardId();
        if (consume && !consumeBagItemCount(connection, session, item, 1)) {
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

    /** 消耗背包道具 count 个并同步客户端行（剩余>0 重推行，=0 删行+镜像清位）。 */
    private boolean consumeBagItemCount(Connection connection, LoginSession session,
                                   RoleInventoryStore.BagItem item, int count) {
        int remaining;
        try {
            remaining = inventoryStore.consumeBagItem(
                    session.selected.id(), item.viewId(), item.slot(), count);
        } catch (RuntimeException e) {
            log.warn("session={} consume {} x{}: {}",
                    session.machine.id(), item.configId(), count, e.getMessage());
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
                consumeBagItemCount(connection, session, item, 1);
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

    /** gm_generate_weapon：按武器类型挑模板 + 官方洗练池词条（厨师 6 属性/铁匠 3 属性+3 招式）并立即穿戴。 */
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
        // 面板规格：词条取 senior 官方洗练池（厨师 6 属性；铁匠 3 属性 + 3 招式，
        // 招式与洗练「招式开光」同语义按武器类型池随机）；池缺失回退全池随机并在回执注明。
        boolean cook = crafter.equals("cook");
        List<Integer> packs = null;
        RecastCatalog recast = RecastCatalog.load();
        if (!recast.isEmpty()) {
            java.util.Random random = sessionRandom(session);
            packs = drawPacks(random, recast.randPool(template.configId(), true), cook ? 6 : 3);
            if (packs != null && !cook) {
                List<Integer> skills = drawPacks(random, recast.weaponPool(template.configId()), 3);
                if (skills != null) {
                    packs.addAll(skills);
                }
            }
        }
        String propBuilt;
        String note;
        if (packs == null || packs.isEmpty()) {
            propBuilt = randomPropBuilt(cook ? 6 : 3);
            note = "官方洗练池缺失，词条回退全池随机";
        } else {
            propBuilt = buildPropBuilt(packs);
            note = "";
        }
        wearGenerated(connection, session, command, template, propBuilt, "武器", note);
    }

    /** gm_generate_armor：按防具部位挑模板 + 固定 5 条红词条（可穿戴部位直接穿，饰品入背包）。 */
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
        // 面板规格：固定 5 条红词条，按 (属性名,数值) 从 proppack 定位包号，未命中的条目跳过并计数。
        List<Integer> packs = new ArrayList<>(ARMOR_RED_AFFIXES.size());
        int missing = 0;
        for (Map.Entry<String, Integer> affix : ARMOR_RED_AFFIXES) {
            Integer pack = fixedAffixPack(affix.getKey(), affix.getValue());
            if (pack != null) {
                packs.add(pack);
            } else {
                missing++;
            }
        }
        wearGenerated(connection, session, command, template, buildPropBuilt(packs), "防具",
                missing == 0 ? "" : "红词条缺 " + missing + " 条（proppack 未命中）");
    }

    /** gm_generate_bracer：护腕模板 + 固定 3 条阳刚臂力（+33/+35/+35）+ 3 条招式词条（按身上武器类型）。 */
    private void applyGenerateBracer(Connection connection, LoginSession session, GmCommand command) {
        ToolItemCatalog.ItemInfo template = pickTemplate(session, "Wrist", null, session.visual.sex());
        if (template == null) {
            command.fail("没有护腕（Wrist）的可穿戴模板");
            return;
        }
        List<Integer> packs = new ArrayList<>(6);
        int missing = 0;
        for (int value : BRACER_STR_AFFIXES) {
            Integer pack = fixedAffixPack("StrAdd", value);
            if (pack != null) {
                packs.add(pack);
            } else {
                missing++;
            }
        }
        String note = missing == 0 ? "" : "臂力词条缺 " + missing + " 条（proppack 未命中）";
        // 招式词条：按身上已穿武器的官方池随机（洗练「招式开光」同语义）；未穿武器则省略。
        String weaponConfig = equippedWeaponConfig(session);
        RecastCatalog recast = RecastCatalog.load();
        List<Integer> skills = weaponConfig == null || recast.isEmpty()
                ? null
                : drawPacks(sessionRandom(session), recast.weaponPool(weaponConfig), 3);
        if (skills != null) {
            packs.addAll(skills);
        } else {
            note = note.isEmpty() ? "未穿武器，招式词条省略" : note + "；未穿武器，招式词条省略";
        }
        wearGenerated(connection, session, command, template, buildPropBuilt(packs), "护腕", note);
    }

    /** 身上已穿武器 ConfigID（招式词条按其武器类型取池）；未穿返回 null。 */
    private String equippedWeaponConfig(LoginSession session) {
        for (RoleInventoryStore.Equip equip : loadEquips(session)) {
            if (isWeaponEquipType(equip.equipType())) {
                return equip.configId();
            }
        }
        return null;
    }

    /** 官方洗练池均匀抽 count 条包号（池内重复即权重）；池空返回 null。 */
    private static List<Integer> drawPacks(java.util.Random random, List<Integer> pool, int count) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        List<Integer> drawn = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            drawn.add(pool.get(random.nextInt(pool.size())));
        }
        return drawn;
    }

    /** 按 (属性名,数值) 在 proppack 定位词缀包：多包命中取最小包号（基础包低号），未命中返回 null。 */
    private static Integer fixedAffixPack(String prop, int value) {
        var affixes = com.nineyin.server.game.avatar.panel.EquipAffixCatalog.load();
        Integer best = null;
        for (Integer packId : affixes.packIds()) {
            for (var mod : affixes.modsOf(packId)) {
                if (mod.prop().equals(prop) && mod.value() == value
                        && (best == null || packId < best)) {
                    best = packId;
                    break;
                }
            }
        }
        return best;
    }

    /** 模板家族基座后缀（面板规格：51 级金色生活技能装备 60501 家族优先）。 */
    private static final String TEMPLATE_FAMILY_BASE = "_60501";

    /** 防具固定红词条（面板规格）：外功防御+46/+47、体魄+29、内息+39、臂力+35。 */
    private static final List<Map.Entry<String, Integer>> ARMOR_RED_AFFIXES = List.of(
            Map.entry("PhyDefAdd", 46), Map.entry("PhyDefAdd", 47),
            Map.entry("StaAdd", 29), Map.entry("MaxMPAdd", 39), Map.entry("StrAdd", 35));

    /** 护腕固定阳刚臂力词条数值（面板规格：+33/+35/+35）。 */
    private static final int[] BRACER_STR_AFFIXES = {33, 35, 35};

    /** 模板挑选：EquipType 匹配（武器再加模型前缀）+ 性别过滤 + 60501 家族优先，回退品质窗口随机。 */
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
        List<ToolItemCatalog.ItemInfo> family = new ArrayList<>();
        for (ToolItemCatalog.ItemInfo info : candidates) {
            if (info.configId().endsWith(TEMPLATE_FAMILY_BASE)) {
                family.add(info);
            }
        }
        if (!family.isEmpty()) {
            return family.get(sessionRandom(session).nextInt(family.size()));
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

    /** 生成模板落地：可穿戴直接穿（词条随装备落库）；槽位未证类型兜底入背包（词条不随行）。 */
    private void wearGenerated(Connection connection, LoginSession session, GmCommand command,
                               ToolItemCatalog.ItemInfo template, String propBuilt, String kind, String note) {
        if (session.selected == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        int slot = AppearanceSync.slotOfEquipType(template.equipType());
        if (slot == 0) {
            grantGeneratedToBag(connection, session, command, template, kind);
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
                + "（词条 " + affixCount + " 条" + (note.isEmpty() ? "" : "，" + note)
                + "）外观与加成已同步");
        log.info("session={} GM generate {} {} affixes={}",
                session.machine.id(), kind, template.configId(), affixCount);
    }

    /** 槽位未证类型兜底：生成后入背包（applyGrantItem 背包分支同款，词条不随行）。 */
    private void grantGeneratedToBag(Connection connection, LoginSession session, GmCommand command,
                                     ToolItemCatalog.ItemInfo template, String kind) {
        int viewId = template.viewId() > 0 ? template.viewId() : 1;
        RoleInventoryStore.BagItem item = new RoleInventoryStore.BagItem(
                0, template.configId(), template.itemType(), 1, viewId, template.name(),
                template.equipType(), 0, template.script(), null, null, null, 0, 0);
        int bagSlot;
        try {
            bagSlot = inventoryStore.insertBagItem(session.selected.id(), item);
        } catch (RuntimeException e) {
            command.fail("入包失败: " + e.getMessage());
            return;
        }
        item = new RoleInventoryStore.BagItem(bagSlot, item.configId(), item.itemType(),
                item.amount(), item.viewId(), item.name(), item.equipType(), item.colorLevel(),
                item.script(), item.anchorSlot(), item.expiresAt(), item.artPack(),
                item.hardiness(), item.maxHardiness());
        pushBagItemFrames(connection, session, item);
        command.succeed("已生成" + kind + " " + template.configId() + " 入背包第 " + bagSlot
                + " 格（饰品穿戴槽位未证，词条不随行）");
        log.info("session={} GM generate {} {} to bag slot {}",
                session.machine.id(), kind, template.configId(), bagSlot);
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

    // ================= V4 三套外观：显示切换（custom 61）/ 时装穿脱买（custom 19） =================

    /** 登录期身份装自动激活：faction 所属门派/势力/新门派段幂等入 role_fashion_suits。 */
    private void grantIdentitySuitsAtLogin(LoginSession session) {
        if (inventoryStore == null || session.player == null || session.selected == null) {
            return;
        }
        try {
            List<String> suits = com.nineyin.server.game.avatar.appearance.FashionSuitCatalog
                    .loadDefault().identitySuitsOf(session.player.faction());
            List<String> missing = new ArrayList<>();
            for (String suit : suits) {
                if (!containsIgnoreCase(session.fashionSuits, suit)) {
                    missing.add(suit);
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            inventoryStore.activateFashionSuits(session.selected.id(), missing);
            List<String> merged = new ArrayList<>(session.fashionSuits);
            merged.addAll(missing);
            java.util.Collections.sort(merged);
            session.fashionSuits = merged;
            log.info("session={} auto activated identity suits={} faction={}",
                    session.machine.id(), missing.size(), session.player.faction());
        } catch (RuntimeException e) {
            log.warn("session={} grant identity suits: {}", session.machine.id(), e.getMessage());
        }
    }

    /** 列表含 configId（大小写不敏感；equipment.ini 段名与目录 r= 值大小写可能不同）。 */
    private static boolean containsIgnoreCase(List<String> configIds, String configId) {
        for (String candidate : configIds) {
            if (candidate.equalsIgnoreCase(configId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * C2S 61 显示类型切换（form_new_rp_arm.lua 三 radio：0=江湖装 1=身份装 2=时装）。
     * 存偏好 + 推 0x10（ShowEquipType + 三模式裁决后的衣裤鞋帽主通道）；皮肤
     * 挂载期间有效类型恒 2（皮肤模型优先），卸载后回落本次偏好。
     */
    void handleShowEquipType(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        if (custom.values.size() < 2) {
            log.info("session={} custom 61 without type", session.machine.id());
            return;
        }
        int type;
        try {
            type = tolerantInt(custom, 1);
        } catch (RuntimeException e) {
            log.info("session={} custom 61 non-int type arg", session.machine.id());
            return;
        }
        if (type < 0 || type > 2) {
            log.info("session={} custom 61 invalid type={}", session.machine.id(), type);
            return;
        }
        saveShowEquipType(session, type);
        pushAppearanceRefresh(connection, session);
        log.info("session={} custom 61 show equip type={} (effective={})",
                session.machine.id(), type, effectiveShowEquipType(session));
    }

    /** 偏好落档（appearanceStore + 会话缓存）。 */
    private void saveShowEquipType(LoginSession session, int type) {
        session.showEquipTypePref = type;
        try {
            appearanceStore.save(session.selected.id(), type);
        } catch (RuntimeException e) {
            log.warn("session={} save appearance pref: {}", session.machine.id(), e.getMessage());
        }
    }

    /** 三模式外观整刷帧（0x10 ShowEquipType + 裁决后主通道 + 时装镜像 + WeaponMode）。 */
    private void pushAppearanceRefresh(Connection connection, LoginSession session) {
        if (session.visual == null) {
            return;
        }
        connection.writeFrame(AppearanceSync.appearanceFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                effectiveShowEquipType(session), session.visual.sex(),
                loadEquips(session), EquipmentResolver.loadDefault()));
    }

    /**
     * C2S 19 时装套装操作（custom_send_fashion_suit，载荷布局 lua 钉死）：
     * sub=1 nx_string(config) 穿上、sub=2 suitprop(EquipType 槽名) 脱下、
     * sub=3 nx_string(config) 购买（本服口径：扣 1 金直接激活）。
     */
    void handleFashionSuit(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        if (custom.values.size() < 3) {
            log.info("session={} custom 19 without sub+payload", session.machine.id());
            return;
        }
        int sub;
        try {
            sub = tolerantInt(custom, 1);
        } catch (RuntimeException e) {
            log.info("session={} custom 19 non-int sub arg", session.machine.id());
            return;
        }
        String textArg = custom.values.get(2).textOrNull();
        switch (sub) {
            case 1 -> wearFashionSuit(connection, session, textArg);
            case 2 -> removeFashionSuit(connection, session, textArg);
            case 3 -> buyFashionSuit(connection, session, textArg);
            default -> log.info("session={} custom 19 unknown sub={} arg={}",
                    session.machine.id(), sub, textArg);
        }
    }

    /** sub=1 穿上：校验已激活（role_fashion_suits）→ 落 role_equip_items 槽 16-19 → 穿戴帧组；穿戴即切到对应显示类型。 */
    private void wearFashionSuit(Connection connection, LoginSession session, String configId) {
        if (inventoryStore == null || configId == null || configId.isEmpty()) {
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(configId);
        int slot = info == null ? 0 : AppearanceSync.slotOfEquipType(info.equipType());
        if (slot < AppearanceSync.SLOT_SUIT || slot > AppearanceSync.SLOT_FASHION_SHOES) {
            log.info("session={} custom 19 wear {} not a fashion/identity slot (equipType={})",
                    session.machine.id(), configId, info == null ? "?" : info.equipType());
            return;
        }
        if (!containsIgnoreCase(session.fashionSuits, info.configId())) {
            log.info("session={} custom 19 wear {} rejected: not activated", session.machine.id(), configId);
            return;
        }
        RoleInventoryStore.Equip equip = new RoleInventoryStore.Equip(
                info.configId(), info.itemType(), info.equipType(), slot,
                100, 100, info.artPack(), "");
        try {
            inventoryStore.upsertEquip(session.selected.id(), equip);
        } catch (RuntimeException e) {
            log.warn("session={} custom 19 wear {}: {}", session.machine.id(), configId, e.getMessage());
            return;
        }
        // 穿戴即切换到对应显示类型（时装 17-19=2 / 身份装 16=1），否则玩家看不到变化。
        int wearType = slot == AppearanceSync.SLOT_SUIT
                ? AppearanceSync.SHOW_EQUIP_IDENTITY : AppearanceSync.SHOW_EQUIP_FASHION;
        if (session.showEquipTypePref == null || session.showEquipTypePref != wearType) {
            saveShowEquipType(session, wearType);
        }
        pushWearFrames(connection, session, equip);
        log.info("session={} custom 19 wear {} slot={} showType={}",
                session.machine.id(), info.configId(), slot, wearType);
    }

    /** sub=2 脱下（按 EquipType 槽名）：清 role_equip_items 槽 + 纸娃娃双槽 + 外观整刷。 */
    private void removeFashionSuit(Connection connection, LoginSession session, String suitProp) {
        if (inventoryStore == null || suitProp == null || suitProp.isEmpty()) {
            return;
        }
        int slot = AppearanceSync.slotOfEquipType(suitProp);
        if (slot < AppearanceSync.SLOT_SUIT || slot > AppearanceSync.SLOT_FASHION_SHOES) {
            log.info("session={} custom 19 remove unknown slot prop={}", session.machine.id(), suitProp);
            return;
        }
        if (AppearanceSync.configAt(loadEquips(session), slot) == null) {
            return; // 槽位本空，幂等静默
        }
        try {
            inventoryStore.deleteEquip(session.selected.id(), slot);
        } catch (RuntimeException e) {
            log.warn("session={} custom 19 remove slot {}: {}",
                    session.machine.id(), slot, e.getMessage());
            return;
        }
        connection.writeFrame(SceneMessages.viewRemove(InventoryMessages.VIEW_DOLL, slot));
        int mirror = AppearanceSync.mirrorSlot(slot);
        if (mirror != slot) {
            connection.writeFrame(SceneMessages.viewRemove(InventoryMessages.VIEW_DOLL, mirror));
        }
        pushAppearanceRefresh(connection, session);
        log.info("session={} custom 19 remove slot={} prop={}", session.machine.id(), slot, suitProp);
    }

    /** sub=3 购买：扣 1 金（CapitalType0）→ 激活 role_fashion_suits + FashionSuitRec 行；已激活幂等跳过。 */
    private void buyFashionSuit(Connection connection, LoginSession session, String configId) {
        if (inventoryStore == null || configId == null || configId.isEmpty()) {
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(configId);
        if (info == null) {
            log.info("session={} custom 19 buy unknown {}", session.machine.id(), configId);
            return;
        }
        if (containsIgnoreCase(session.fashionSuits, info.configId())) {
            return; // 已激活，幂等
        }
        int gold = session.player.gold();
        if (gold < 1) {
            log.info("session={} custom 19 buy {} rejected: gold {} < 1",
                    session.machine.id(), info.configId(), gold);
            return;
        }
        try {
            inventoryStore.activateFashionSuits(session.selected.id(), List.of(info.configId()));
        } catch (RuntimeException e) {
            log.warn("session={} custom 19 buy {}: {}", session.machine.id(), configId, e.getMessage());
            return;
        }
        connection.writeFrame(session.player.setGold(gold - 1));
        List<String> merged = new ArrayList<>(session.fashionSuits);
        merged.add(info.configId());
        java.util.Collections.sort(merged);
        session.fashionSuits = merged;
        if (session.fashionPushed) {
            for (byte[] frame : fashionSuitFrames(List.of(info.configId()))) {
                connection.writeFrame(frame);
            }
        }
        gmHub.update(session.machine.id(), s -> {
            s.gold = gold - 1;
            s.lastAction = "购买时装 " + info.configId() + "（-1 金）";
        });
        log.info("session={} custom 19 buy {} gold {} -> {}",
                session.machine.id(), info.configId(), gold, gold - 1);
    }

    /** GM set_showequip_type：type 0/1/2 直切（存档 + 0x10 整刷）。 */
    private void applySetShowEquipType(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null) {
            command.fail("角色尚未选定");
            return;
        }
        Integer type = command.intParam("type");
        if (type == null || type < 0 || type > 2) {
            command.fail("type 必须为 0（江湖装）/1（身份装）/2（时装）");
            return;
        }
        saveShowEquipType(session, type);
        pushAppearanceRefresh(connection, session);
        String label = showEquipTypeLabel(type);
        command.succeed("显示类型已切换：" + label + "（ShowEquipType=" + type + "）");
        log.info("session={} GM set_showequip_type={}", session.machine.id(), type);
    }

    /** GM grant_identity_suits：当前角色按 faction 激活全部身份装 + FashionSuitRec 行。 */
    private void applyGrantIdentitySuits(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null || session.player == null || inventoryStore == null) {
            command.fail("物品 DB 未接入（MySQL inventory 源缺失）");
            return;
        }
        List<String> suits = com.nineyin.server.game.avatar.appearance.FashionSuitCatalog
                .loadDefault().identitySuitsOf(session.player.faction());
        if (suits.isEmpty()) {
            command.fail("门派 " + session.player.faction() + " 无身份装目录（fashion_suit.ini 未命中）");
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String suit : suits) {
            if (!containsIgnoreCase(session.fashionSuits, suit)) {
                missing.add(suit);
            }
        }
        if (!missing.isEmpty()) {
            try {
                inventoryStore.activateFashionSuits(session.selected.id(), missing);
            } catch (RuntimeException e) {
                command.fail("身份装落库失败: " + e.getMessage());
                return;
            }
            List<String> merged = new ArrayList<>(session.fashionSuits);
            merged.addAll(missing);
            java.util.Collections.sort(merged);
            session.fashionSuits = merged;
        }
        if (session.fashionPushed) {
            for (byte[] frame : fashionSuitFrames(missing.isEmpty() ? suits : missing)) {
                connection.writeFrame(frame);
            }
        }
        command.succeed("已激活身份装 " + suits.size() + " 套（门派 " + session.player.faction()
                + (missing.isEmpty() ? "，此前已全量激活" : "，新增 " + missing.size() + " 套") + "）");
        log.info("session={} GM grant_identity_suits faction={} total={} new={}",
                session.machine.id(), session.player.faction(), suits.size(), missing.size());
    }

    /**
     * GM set_faction（Go main.go 同序）：闭名单预设 → saveFaction CAS 落库
     * （appearance[8]，失败即回滚不动作内存）→ 内存 faction + 专用单帧
     * （School/Force/NewSchool 三属性）→ 面板快照。身份装/套路/任务线不自动赠送
     * （grant_identity_suits 与登录期自动激活另行覆盖）。
     */
    private void applyFaction(Connection connection, LoginSession session, GmCommand command) {
        if (session.selected == null) {
            command.fail("角色尚未选定");
            return;
        }
        GmFactionPresets.Faction preset = GmFactionPresets.preset(command.strParam("faction"));
        if (preset == null) {
            command.fail("未知门派预设: " + command.strParam("faction"));
            return;
        }
        try {
            session.selected = roleStore.saveFaction(session.selected, preset.faction());
        } catch (RuntimeException e) {
            command.fail("门派保存失败: " + e.getMessage());
            return;
        }
        session.player.setFaction(preset.faction());
        connection.writeFrame(session.player.factionUpdate());
        String action = "门派设为 " + preset.label() + "（" + preset.faction() + "）";
        gmHub.update(session.machine.id(), s -> {
            s.faction = preset.faction();
            s.lastAction = action;
        });
        command.succeed(action);
        log.info("session={} GM set_faction={} ({})", session.machine.id(), preset.faction(), preset.label());
    }

    private static String showEquipTypeLabel(int type) {
        return switch (type) {
            case 1 -> "身份装";
            case 2 -> "时装";
            default -> "江湖装";
        };
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
        if (session.skillCanUseTicker != null) {
            session.skillCanUseTicker.cancel(false);
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
        // GM 切图重入：目标场景 0x09 一次性收尾（延迟 0x1F+0x10、行恢复、物化）。
        if (session.awaitingReentryReady) {
            if (!session.reentryFramesSent) {
                log.info("session={} ignored stale 0x09 before re-entry frames", session.machine.id());
                return true;
            }
            return completeSceneReentry(connection, session);
        }
        grantOncePerConnectionRows(connection, session);
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
     * View40 行 CanUse 心跳批（官方 61579 战斗抓包实证：进战斗 LogicState 帧+首批
     * 命中后紧跟全行批，此后每 ~2s 重推；61114 登录批一次、纯闲时无批——客户端可用
     * 性态会衰减，真机表现为"进战斗图标全灰但能放"，由周期批自愈）。值恒 1、
     * 不按资源门控（52795 取证：怒招行 SP=26<消耗 50 仍推 1；资源灰由客户端按
     * SP+行内 AConsumeSP 自算，CD 灰由 cooldown_rec 扫描独立承担——官方 316 批
     * 11730 帧仅特殊行 QGP 不足出 0）。断连取消。
     */
    /** CanUse 心跳批行数上限：参考服 52905 真机实证（207 技能角色恒只刷 17 行=快捷栏前 17 槽技能）。 */
    private static final int CAN_USE_BATCH_MAX_ROWS = 17;

    /**
     * View40 行 CanUse 心跳批——**只刷快捷栏绑定的技能行（≤17，参考服 52905 同款）**：
     * 参考服对 207 技能角色 234 批每 ~2s 恒只推 17 行且含 0 值（武器不匹配/怒气不足窗口）；
     * 本服曾全量 193 行/批，真机把整个技能图标打成全灰（63296 实证客户端对持续视图行
     * 更新爆发敏感；登录期一次性全量批不受影响，参考服 61114 登录批=190+6 全行）。
     * 值=资源可用性：武器门控（skill_use_limit ItemType）∧ SP≥AConsumeSP；
     * CD 灰由客户端 cooldown_rec 扫描独立承担（官方批对 CD 中的行也恒推可用）。
     */
    private void startSkillCanUseTicker(Connection connection, LoginSession session) {
        if (session.skillCanUseTicker != null) {
            session.skillCanUseTicker.cancel(false);
        }
        // 行集快照：快捷栏槽序去重后的前 17 个技能；空表回落 View40 行号前 17
        java.util.TreeMap<Integer, String> slotSkills = new java.util.TreeMap<>();
        if (session.player != null) {
            for (PlayerActor.PlayerShortcut shortcut : session.player.shortcutSnapshot()) {
                if ("skill".equals(shortcut.kind())) {
                    slotSkills.putIfAbsent(shortcut.index(), shortcut.id());
                }
            }
        }
        java.util.Map<String, Integer> batchIds = new java.util.LinkedHashMap<>();
        for (String id : slotSkills.values()) {
            Integer row = session.skillRows.get(id);
            if (row != null && row > 0) {
                batchIds.putIfAbsent(id, row);
                if (batchIds.size() >= CAN_USE_BATCH_MAX_ROWS) {
                    break;
                }
            }
        }
        if (batchIds.isEmpty()) {
            for (java.util.Map.Entry<String, Integer> e : session.skillRows.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    batchIds.putIfAbsent(e.getKey(), e.getValue());
                    if (batchIds.size() >= CAN_USE_BATCH_MAX_ROWS) {
                        break;
                    }
                }
            }
        }
        com.nineyin.server.world.fight.CombatSkillCatalog catalog =
                com.nineyin.server.world.fight.CombatSkillCatalog.load();
        java.util.Map<String, Integer> costs = new java.util.HashMap<>();
        for (String id : batchIds.keySet()) {
            com.nineyin.server.world.fight.CombatSkillCatalog.SkillRowData rowData =
                    catalog.skillRowData(id, session.learnedSkills.getOrDefault(id, 1));
            if (rowData != null && rowData.aConsumeSP() > 0) {
                costs.put(id, rowData.aConsumeSP());
            }
        }
        java.util.Map<String, Integer> rows = java.util.Map.copyOf(batchIds);
        session.skillCanUseTicker = scheduler.scheduleWithFixedDelay(() -> {
            try {
                PlayerActor player = session.player;
                if (player == null) {
                    return;
                }
                if (player.sitting()) {
                    // 打坐期间整批静默（参考服 52905 坐姿窗口零 CanUse 行帧实证）：
                    // 坐姿动画进行中收到 CanUse 行推送会被客户端当状态事件处理，
                    // 中止坐姿动作并回发 240(2)——重推面板再触发=动画永不落定的
                    // 自持风暴（2026-11-05 真机「完全没有打坐动画」根因）。
                    return;
                }
                int sp = player.sp();
                int weapon = player.equippedWeaponItemType();
                for (java.util.Map.Entry<String, Integer> e : rows.entrySet()) {
                    Integer cost = costs.get(e.getKey());
                    boolean usable = cost == null || sp >= cost;
                    if (usable) {
                        com.nineyin.server.world.fight.CombatSkill def = catalog.definition(
                                e.getKey(), session.learnedSkills.getOrDefault(e.getKey(), 1));
                        usable = def == null || def.weaponAllowed(weapon);
                    }
                    connection.writeFrame(FightMessages.skillRowCanUse(
                            SkillCastHandler.SKILL_VIEW_ID, e.getValue(), usable));
                }
            } catch (RuntimeException e) {
                log.warn("skill CanUse ticker session={}: {}", session.machine.id(), e.getMessage());
            }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 每连接一次的行授予链（原 onClientReady 主体抽出，登录与 GM 切图重入共用）：
     * 轻功 View46/打坐快捷栏/内功 View43+技能 View40/经脉 View45/个性配置/
     * 键位/风物志/时装/技能开关。标志复位即可整链重放（0x0C 清玩家对象挂接行）。
     */
    private void grantOncePerConnectionRows(Connection connection, LoginSession session) {
        if (session.player != null && !session.qingGongGranted) {
            StarterGrants.grantStarterQingGong(connection);
            session.qingGongGranted = true;
            log.info("session={} granted {} QingGongRec rows and {} View=46 items after explicit ClientReady",
                    session.machine.id(), StarterQingGong.STARTER_QING_GONG_IDS.size(),
                    StarterQingGong.STARTER_QING_GONG_IDS.size() + StarterQingGong.STARTER_QING_GONG_SKILL_IDS.size());
            // 任务恢复帧：必须排在 starter 功能解锁行（15 行 Task_Accepted +
            // 位图 0..1593）之后——客户端 Task_Accepted 记录行号 = starter 行数 +
            // 玩家已接序（删行按此口径计算，见 taskAcceptedRowNumber）。
            grantTaskRestoreFrames(connection, session);
        }
        if (session.player != null && !session.shortcutRowsSent) {
            // 快捷栏每场景代全量重放：内存为唯一事实源（DB 基线首载入内存 / starter 自建），
            // 行序=下发序。DB 基线装载必须先于打坐幂等门——否则 idx9 重复行/行号漂移。
            if (!session.shortcutsLoaded) {
                java.util.List<RoleInventoryStore.Shortcut> dbShortcuts = loadDbShortcuts(session);
                session.starterShortcutFallback = dbShortcuts == null || dbShortcuts.isEmpty();
                if (!session.starterShortcutFallback) {
                    session.player.resetShortcuts(dbShortcuts.stream()
                            .map(row -> new PlayerActor.PlayerShortcut(
                                    row.shortcutIndex(), row.kind(), row.id()))
                            .toList());
                }
                session.shortcutsLoaded = true;
            }
            StarterGrants.grantShortcutRows(connection, session.player);
            session.shortcutRowsSent = true;
        }
        if (session.player != null && !session.lettersGranted) {
            // 邮件行（RecvLetterRec 319，玩家对象 1-1 挂接）：ExitScene 清挂接行，
            // 与快捷栏同口径按内存列表整表重放；空邮箱零帧。
            session.lettersGranted = true;
            for (com.nineyin.server.game.avatar.mail.LetterStore.Letter letter : session.letters) {
                connection.writeFrame(letterRowFrame(letter));
            }
            if (!session.letters.isEmpty()) {
                log.info("session={} replayed {} RecvLetterRec rows",
                        session.machine.id(), session.letters.size());
            }
        }
        if (session.player != null && !session.reputesGranted) {
            // 声望行（Repute_Record 44，玩家对象挂接，letters 同口径）：ExitScene 清
            // 挂接行，按内存镜像整表重放；空表零帧（golden 流程角色无声望行）。
            session.reputesGranted = true;
            if (!session.reputes.isEmpty()) {
                for (byte[] frame : com.nineyin.server.game.avatar.panel.ReputeMessages.rowsFrames(
                        SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                        session.reputes, com.nineyin.server.game.avatar.panel.ReputeLevelTable.load())) {
                    connection.writeFrame(frame);
                }
                log.info("session={} replayed {} Repute_Record rows",
                        session.machine.id(), session.reputes.size());
            }
        }
        if (session.player != null && !session.originsGranted) {
            // 身份行（Origin_Active 45 / Origin_Prized 48，玩家对象挂接，reputes 同口径）：
            // ExitScene 清挂接行，按内存镜像整表重放；空表零帧。
            session.originsGranted = true;
            if (!session.origins.isEmpty()) {
                pushOriginRows(connection, session);
                log.info("session={} replayed {} origin rows",
                        session.machine.id(), session.origins.size());
            }
        }
        if (session.player != null) {
            boolean granted = StarterGrants.grantSitcrossShortcut(connection, session.player);
            if (granted) {
                log.info("session={} granted native sitcross shortcut index={}",
                        session.machine.id(), StarterGrants.SITCROSS_SHORTCUT_INDEX);
            }
        }
        if (session.player != null && !session.neigongGranted) {
            // 全量下发补集：基础武学→三脚猫招式五件套缺则补齐（View40/211 已学表/CanUse 行同源）。
            java.util.List<RoleInventoryStore.Skill> dbSkills =
                    StarterSkills.withDefaultBasics(loadDbSkills(session));
            long roleId = session.selected != null ? session.selected.id() : 0L;
            session.learnedSkills = learnedSkillLevels(roleId, dbSkills);
            session.skillRows = skillRows(dbSkills);
            StarterGrants.grantStarterProgress(connection, session.player,
                    dbSkills, session.starterShortcutFallback, session.learnedSkills);
            session.neigongGranted = true;
            // View40 行可用性初始批（62433 抓包：starter 授予后每行推 CanUse=1；
            // 不发则客户端技能图标恒为不可用——真机反馈"显示不可用但能释放"）
            for (java.util.Map.Entry<String, Integer> row : session.skillRows.entrySet()) {
                connection.writeFrame(FightMessages.skillRowCanUse(
                        SkillCastHandler.SKILL_VIEW_ID, row.getValue(), true));
            }
            startSkillCanUseTicker(connection, session);
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
            // 两套已存方案（ShortcutKey1=199/ShortcutKey2=200）：保存面板据此显示
            // "方案一/二 已保存"，客户端启用方案 N 时引擎以 scheme 0 上传该集，
            // bind_value 自动跟随 → 198 恒为当前启用集（重登回推即用户所启用的那套）。
            pushSavedKeybindSchemes(connection, session);
            // 操作模式（role_keybinds.operate_mode，C2S 99）：OperateMode（索引 223，
            // type3 int32）下发。键位串与会话模式两域——模式不恢复则方案错配，
            // 表现为快捷键"保存了但异常"（真机 2026-11-04 反馈）。
            pushSavedOperateMode(connection, session);
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
        if (session.player != null && !session.mountsPushed) {
            // 坐骑：NewRideRec 拥有行 + NewRideID 当前选中（无存档零帧；
            // Mount 属性不跨登录保持——重登即步行，骑乘键/骑术技能再上马）。
            pushSavedMounts(connection, session);
        }
        if (session.player != null && session.idleStance != null && session.idleStanceLastPushMs == 0) {
            // 待机站姿：ClientReady 首推（此后 1s ticker 的 4s 保持拍自愈）。
            reapplyIdleStance(connection, session);
        }
        if (session.player != null && !session.switchSyncPushed) {
            // 参考服 pushSwitchSync：登录收尾一次性 0x1E 414("sync", id×203) 批量开
            // 客户端 SwitchManager。2919 = 装备栏"新旧界面切换"门槛（真机 2026-11-05
            // 确认 Java 端缺此帧 → 按钮不出现）。连接级一次，0x0C 后不重放。
            session.switchSyncPushed = true;
            StarterGrants.pushSwitchSync(connection);
            log.info("session={} switch sync pushed switches={}",
                    session.machine.id(), StarterGrants.SWITCH_SYNC_IDS.length);
        }
        if (session.player != null) {
            // 重复 0x09（活动场景内的后期 UI 回调）也会重发开关，Go 同此。
            StarterGrants.enableSkillActionSwitch(connection);
        }
    }

    /**
     * GM 切图重入的目标场景 0x09 收尾（Go main.go awaitingStableReentryReady 分支）：
     * 延迟 0x1F ServerLocation（读图前发会把目的地投影到旧场景上）+ 0x10 全量 →
     * Ready/Activity 相位推进 + NPC 物化（clientActivity 内含巡逻启动）→ 0x0C 清掉的
     * 玩家挂接行整链重放（轻功/快捷栏/内功/经脉/皮肤/时装）→ 撤屏障。
     */
    private boolean completeSceneReentry(Connection connection, LoginSession session) {
        Roles.Location destination = session.reentryLocation;
        if (session.world != null) {
            session.world.clientReady();
        }
        connection.writeFrame(SceneMessages.serverLocation(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                destination.position()));
        connection.writeFrame(session.player.vitalUpdate());
        SessionException ready = session.machine.ready();
        if (ready != null) {
            log.info("complete stable re-entry ready: {}", ready.getMessage());
            return false;
        }
        if (session.world != null) {
            try {
                session.world.clientActivity();
            } catch (RuntimeException e) {
                log.warn("session={} activate stable re-entry objects: {}",
                        session.machine.id(), e.getMessage());
                return false;
            }
        }
        SessionException activity = session.machine.activity();
        if (activity != null) {
            log.info("complete stable re-entry activity: {}", activity.getMessage());
            return false;
        }
        // ServerExitScene 清掉玩家对象及其挂接行（View 46/43/40/45 与风物志/时装行）；
        // 一次性标志复位后整链重放。个性配置（705→lua 配置对象）确是连接级不重发；
        // 键位四属性（198/199/200/223）挂在玩家对象属性视图上，0x0C 后随 addObject
        // 重建而丢——2026-11-04 真机跨场景丢数据实证，必须复位重推。
        session.qingGongGranted = false;
        session.neigongGranted = false;
        session.meridianGranted = false;
        session.skinsPushed = false;
        session.fashionPushed = false;
        session.mountsPushed = false;
        // 站姿属性随玩家对象 0x0C 重建丢失：复位首推门（grantOnce 内重推）。
        session.idleStanceLastPushMs = 0;
        session.keybindPushed = false;
        // 快捷栏记录行同属玩家对象挂接行，ExitScene 后必须按内存快照整表重放。
        session.shortcutRowsSent = false;
        session.lettersGranted = false;
        // 声望行同属玩家挂接行（Repute_Record），随收尾整表重放。
        session.reputesGranted = false;
        // 身份行同口径（Origin_Active/Origin_Prized）。
        session.originsGranted = false;
        grantOncePerConnectionRows(connection, session);
        session.awaitingReentryReady = false;
        session.reentryFramesSent = false;
        session.reentryLocation = null;
        log.info("session={} target scene confirmed by 0x09; restored rows and activated NPCs",
                session.machine.id());
        return true;
    }

    /**
     * Go 0x0A 分支：相位 ENTERING_WORLD 时按兼容就绪屏障提升（客户端省略 0x09
     * 直发 0x0A：Ready + vital + 0x27 开关，不发 starter 链）；随后物化 NPC 视口
     * （armed 一次）+ 启动巡逻，进 Activity 相位，再处理 67 字节位置更新包。
     */
    private boolean onClientActivity(Connection connection, LoginSession session, byte[] plain) {
        // GM 切图重入窗口：0x0C/0x0B 之后、目标地形就绪之前，旧舞台残余的 0x0A
        // 不得打开新场景物化屏障（Go 同款忽略；目标 0x09 在 onClientReady 收尾）。
        if (session.awaitingReentryReady) {
            log.info("session={} ignored early 0x0A while target scene is loading", session.machine.id());
            return true;
        }
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
            // 冲锋落地（IDA handleCastDashLanding）：[212, f32 x/y/z, (f32 朝向)] ——清冲锋
            // 态回显 337，取 30s 内暂存的出招重组 211 续招（超程冲锋“先冲后放”的回执半段）。
            case 212 -> {
                if (session.player != null && session.world != null && session.fight != null) {
                    SkillCastHandler.handleDashLanding(new SkillCastHandler.CastContext(
                            connection, session.player, session.world, session.fight,
                            session.learnedSkills, session.skillRows, scheduler, clock,
                            session.combatRandom, killOverride(session)), custom);
                }
            }
            // msg15 移动上报（9 值：[15, state, moveType, f32 x/y/z, f32 orient, (会话哈希),
            // tick]；高频，其余用途忽略不落日志）：冲锋暂存挂起时探测到位——玩家与目标
            // 距离进入技能射程即服务端主动落地续招（客户端不必然发 212，61653 实证）。
            case 15 -> handleMovementUpload(connection, session, custom);
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
            // 身份系统：700 激活单身份 / 702 领奖（真发奖）/ 706 请求刷新；
            // 701 展示 / 705 领取查询留待（S2C lua 回调无抓包证据）。
            case 700, 702, 706 -> handleOriginCustom(connection, session, custom);
            case 107 -> handleCustomizingUpload(connection, session, custom);
            case 200 -> handleSetShortcut(connection, session, custom);
            case 201 -> handleRemoveShortcut(connection, session, custom);
            // 装备穿/脱：30=MOVEITEM 移动物品（menu2 `MOVEITEM (%d,%d)->(%d,%d)`），
            // 31=USEITEM 使用物品（右键穿/风物志卡片收录，恰好 4 值），
            // 886-889=一键穿 OnestepEquip（uid 认领）。
            case 30 -> handleMoveItem(connection, session, custom);
            case 31 -> handleBodyItemUse(connection, session, custom);
            // 34=custom_delete_item(view,pos,amount) 道具丢弃（game_hand.lua 拖出
            // 确认销毁；CantDelete 客户端拦，服务端按页/格/量权威扣减）。
            case 34 -> handleDeleteItem(connection, session, custom);
            // 70=custom_buy_item(shop_id,page,pos,count) 商店购买（form_shop.lua 实证参数序）。
            case 70 -> handleBuyItem(connection, session, custom);
            case 886, 887, 888, 889 -> handleOnestepEquip(connection, session, custom);
            // V3 风物志：180=custom_card 衣橱卡牌 UI（换装切换入口）。
            case 180 -> handleWardrobeCard(connection, session, custom);
            // V4 三套外观：61=显示类型切换（江湖装/身份装/时装），19=时装套装
            // 穿/脱/买（form_new_rp_arm.lua custom_send_fashion_suit）。
            case 61 -> handleShowEquipType(connection, session, custom);
            case 19 -> handleFashionSuit(connection, session, custom);
            case 240 -> handleSitcross(connection, session, custom);
            // 坐骑：243=custom_ride(int) 引擎骑乘键（1=骑当前选中坐骑 / 0=下马），
            // 266=custom_new_ride(sub,...)（ride_util 子令 1=USE_ITEM / 2=SELECT_RIDE；
            // 参数形状引擎侧不可见，tolerant 解析 + 未识别形状落 INFO 钓真机）。
            case 243 -> handleRideToggle(connection, session, custom);
            case 266 -> handleNewRide(connection, session, custom);
            case 217, 218 ->
                    log.info("session={} unimplemented custom msgId={} via {} (phase-4 backlog)",
                            session.machine.id(), messageId, route);
            case 54 -> handleManualParry(connection, session, custom);
            // 操作模式（form_system_interface_setting.switch_mode → custom_set_operate_mode）：
            // [99, mode]（0=3D WASD / 1=2.5D 点击）。不存则模式重登即丢，键位方案
            // 与模式错配表现为"保存了但快捷键异常"。
            case 99 -> handleSetOperateMode(connection, session, custom);
            // 键位方案串（引擎 ShortcutKey:EndEdit 上行，非 lua 层消息）：[195, scheme, str]，
            // scheme 0=当前启用/1=方案一/2=方案二（bak 抓包 12173/1578 双帧钉死，658B 样本）。
            // 官服收到不回包，但客户端保存面板读服务端属性视图——不回推则界面恒"未保存"。
            case 195 -> handleKeybindUpload(connection, session, custom);
            // 任务消息（task_define.lua 子命令表：0 放弃/"complete_task" 提交，
            // 正常接/交走 0x07 对话菜单 mark 路由）。
            case 327 -> handleTaskMsg(connection, session, custom);
            // 邮件族（form_mail.lua，RecvLetterRec 记录表即邮箱列表）：350 开邮箱/
            // 358 关读信窗/357 勾选均为客户端本地行为（列表=data_binder 绑定记录行，
            // 无需回包）；353 读信置已读；359 领附件入包。
            case 350, 357, 358 -> {
                // 邮箱开合/勾选：零服务端动作
            }
            case 353 -> handleReadLetter(connection, session, custom);
            case 359 -> handleGetAppendix(connection, session, custom);
            default -> log.info("session={} unrecognized custom msgId={} via {}",
                    session.machine.id(), messageId, route);
        }
    }

    /**
     * C2S 200 设置快捷栏（shortcut_game.lua set_shortcut：`[200, index, kind, id]`，
     * kind∈skill/item/neigong/zhenfa）。变更后走 {@link #rebuildShortcutRecord} 清空重推
     * （menu2 rebuildShortcutRecord 语义，非增量 del+add）；幂等不变零帧。DB 模式同步写
     * role_shortcuts（重登回放）。
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
        int preCount = session.player.shortcutSnapshot().size();
        PlayerActor.ShortcutUpdate update = session.player.setShortcut(index, kind, id);
        if (update.unchanged()) {
            return;
        }
        rebuildShortcutRecord(connection, session.player, preCount);
        persistShortcut(() -> inventoryStore.upsertShortcut(session.selected.id(), index, kind, id));
        log.info("session={} shortcut set index={} kind={} id={} replaced={} rebuilt={}",
                session.machine.id(), index, kind, id, update.existed(), preCount);
    }

    /**
     * C2S 201 摘除快捷栏（`[201, 可见快捷栏索引]`）。客户端 shortcut_game.lua
     * remove_shortcut(index) → custom_remove_shortcut 的全部调用点（game_hand.lua
     * 拖出释放/put_other_short 交换/form_jingmai 清栏）上行的是栏位索引而非记录行号
     * ——Go removeShortcutRow 按行号删是对 custom_sender 形参名 row 的误读，真机
     * 拖拽即错删他行。索引未命中静默零帧；DB 模式同步删 role_shortcuts 对应行。
     */
    void handleRemoveShortcut(Connection connection, LoginSession session,
                                      CustomC2S.Message custom) {
        if (session.player == null || session.selected == null || custom.values.size() < 2) {
            return;
        }
        int index = tolerantInt(custom, 1);
        if (index < 0 || index > 4096) {
            log.info("session={} remove shortcut invalid index={}", session.machine.id(), index);
            return;
        }
        int preCount = session.player.shortcutSnapshot().size();
        PlayerActor.RemovedShortcut removed = session.player.removeShortcutIndex(index);
        if (removed == null) {
            return;
        }
        rebuildShortcutRecord(connection, session.player, preCount);
        persistShortcut(() -> inventoryStore.deleteShortcut(session.selected.id(), index));
        log.info("session={} shortcut removed index={} row={} kind={} id={} rebuilt={}",
                session.machine.id(), index, removed.row(),
                removed.shortcut().kind(), removed.shortcut().id(), preCount);
    }


    /** tag2 int 宽容读取：客户端 nx_int 负值以 u32 回绕上行，(int) 下转还原。 */
    static int tolerantInt(CustomC2S.Message custom, int position) {
        CustomC2S.Value value = custom.values.get(position);
        if (value.tag != 2) {
            throw new IllegalStateException("arg " + position + " is not int32");
        }
        return (int) value.int32;
    }

    /** 243 custom_ride(int)：引擎骑乘键（>0=骑当前选中坐骑 / 0=下马）。 */
    private void handleRideToggle(Connection connection, LoginSession session, CustomC2S.Message custom) {
        int flag = tolerantInt(custom, 1);
        var state = sessionMountState(session);
        var current = state == null ? null : state.byPack(state.currentPack());
        if (flag == 0) {
            if (!session.riding) {
                log.info("session={} ride toggle 0 but not riding", session.machine.id());
                return;
            }
            applyRideState(connection, session, current, false);
            log.info("session={} dismounted via ride key", session.machine.id());
            return;
        }
        if (current == null) {
            log.info("session={} ride toggle {} but no selected mount", session.machine.id(), flag);
            return;
        }
        applyRideState(connection, session, current, true);
        log.info("session={} mounted pack {} via ride key", session.machine.id(), current.packId());
    }

    /**
     * 266 custom_new_ride(sub,...)：sub=1 USE_ITEM（使用坐骑道具，参数形状未知——
     * 先按 config 串 / 包 id int tolerant 命中）/ sub=2 SELECT_RIDE（选中并骑乘）。
     * 使用坐骑道具不消耗（GM 口径：已激活的坐骑再次使用不消耗道具）。
     */
    private void handleNewRide(Connection connection, LoginSession session, CustomC2S.Message custom) {
        int sub = tolerantInt(custom, 1);
        var catalog = com.nineyin.server.game.avatar.mount.MountCatalog.loadDefault();
        com.nineyin.server.game.avatar.mount.MountCatalog.Mount mount = null;
        if (custom.values.size() > 2) {
            CustomC2S.Value arg = custom.values.get(2);
            if (arg.tag == 6 || arg.tag == 7) {
                mount = catalog.byConfig(arg.text);
            } else if (arg.tag == 2) {
                mount = catalog.byPack((int) arg.int32);
            }
        }
        if (mount == null) {
            log.info("session={} new_ride sub={} arg not resolved to a mount (values={}/{}), shape probe",
                    session.machine.id(), sub,
                    custom.values.size() > 2 ? custom.values.get(2).tag : -1,
                    custom.values.size() > 2 ? custom.values.get(2).textOrNull() : "");
            return;
        }
        switch (sub) {
            case 1, 2 -> {
                // USE_ITEM / SELECT_RIDE：拥有 + 骑乘（SELECT_RIDE 官方语义为切换当前坐骑）。
                var state = (sessionMountState(session) == null
                        ? com.nineyin.server.game.avatar.mount.MountStore.State.empty()
                        : sessionMountState(session))
                        .withOwned(mount.packId(), mount.configId())
                        .withSelected(mount.packId());
                saveMountState(session, state);
                applyRideState(connection, session, state.byPack(mount.packId()), true);
                log.info("session={} new_ride sub={} mounted {} [{}]",
                        session.machine.id(), sub, mount.name(), mount.configId());
            }
            default -> log.info("session={} new_ride unhandled sub={} mount={}",
                    session.machine.id(), sub, mount.configId());
        }
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

    /**
     * 快捷栏变更全量重建（menu2 9yin-game-native-menu.exe rebuildShortcutRecord 逐指令
     * 移植）：先按变更前行数从 preCount-1 递减到 0 逐行 0x12 清空客户端 shortcut_rec，
     * 再按内存快照全量重推 0x11。
     *
     * <p>为何不做增量 del：真机客户端对记录表中段删行的方言不可靠——按服务端行号发
     * 0x12 会出现拖拽后图标残留（幽灵）/同槽双行（重叠）/拖出无法删除（2026-11-04
     * 19:06-19:10 session=1 实录：服务端状态机自洽但客户端记录漂移，用户在 203/204/209
     * 反复拖拽 15 次）。menu2（唯一与真实客户端长期配套的参考服）在 200/201 的所有
     * 变更路径都调用 rebuildShortcutRecord 整表重建，不依赖删行方言；官服抓包
     * （61114）中 shortcut_rec 也只有 0x11 追加、从未出现中段 0x12。递减序保证每个
     * 0x12 行号在删行收缩后仍指向有效行。
     */
    private void rebuildShortcutRecord(Connection connection, PlayerActor player, int preCount) {
        for (int row = preCount - 1; row >= 0; row--) {
            connection.writeFrame(RecordMessages.recordDelRow(
                    SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                    RecordMessages.RECORD_SHORTCUT, row));
        }
        for (PlayerActor.PlayerShortcut shortcut : player.shortcutSnapshot()) {
            connection.writeFrame(StarterGrants.shortcutAddFrame(shortcut));
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
                session.combatRandom, killOverride(session)), custom);
    }

    /**
     * C2S msg15 移动上报（高频）：非冲锋态静默忽略；冲锋暂存挂起（rushInFlight）时
     * 取 idx 3-6 的 f32 x/y/z/orient 交 {@link SkillCastHandler#tryRushArrival} 做
     * 到位探测——进入技能射程即服务端主动续招（msg20 攻击目标帧簇）。
     */
    private void handleMovementUpload(Connection connection, LoginSession session,
                                      CustomC2S.Message custom) {
        if (session.player == null || !session.player.rushInFlight()) {
            return;
        }
        List<CustomC2S.Value> values = custom.values;
        if (values.size() < 6 || values.get(3).tag != 4 || values.get(4).tag != 4
                || values.get(5).tag != 4) {
            return;
        }
        float orient = values.size() > 6 && values.get(6).tag == 4 ? values.get(6).float32 : 0f;
        SkillCastHandler.tryRushArrival(new SkillCastHandler.CastContext(
                connection, session.player, session.world, session.fight,
                session.learnedSkills, session.skillRows, scheduler, clock,
                session.combatRandom, killOverride(session)), values.get(3).float32, values.get(4).float32,
                values.get(5).float32, orient);
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
     * C2S 0x07 对象请求（33B：seq@17/objId@21/owner@25/FuncID@29，Go client_object_request.go）。
     * 两段式：FuncID==0 是初次点击——combat NPC 记选中回 0x10 LastObject（idx414，
     * 62433 抓包 19 对 0x07/431 全回）；非 combat NPC 命中 optiontalk 则开 0x1C 对话
     * 菜单。FuncID!=0 是菜单项 mark 回发，查 {@link NpcDialog} 分发（翻页/商店/发放/告辞）。
     */
    private boolean onObjectRequest(Connection connection, LoginSession session, byte[] plain) {
        if (plain.length != 33) {
            log.info("session={} tolerated object request len={}", session.machine.id(), plain.length);
            return true;
        }
        int objectId = readU32(plain, 21);
        int ownerId = readU32(plain, 25);
        int funcId = readU32(plain, 29);
        if (session.player == null || session.world == null) {
            return true;
        }
        if (funcId != 0) {
            dispatchMenuMark(connection, session, funcId);
            return true;
        }
        // combatNpcInfo 对一切已注册实体非空，combat 标志才区分战斗对象
        SceneLifecycle.CombatNpcInfo registered = session.world.combatNpcInfo(objectId);
        if (session.fight != null && registered != null && registered.combat()) {
            selectCombatTarget(connection, session, objectId, ownerId);
            return true;
        }
        openNpcDialog(connection, session, objectId, ownerId);
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

    /**
     * 非 combat NPC 点击：回 0x10 LastObject（与 combat 同口径 idx414）+
     * optiontalk 命中（creator id → DialogID）则重置对话态并发首页 0x1C 菜单。
     * 无对话数据时回落任务专线路径（npcconfig TaskCanAccept/TaskCanSubmit 命中
     * 才发菜单）；均未命中/未物化对象（传送门等）放行记日志。
     */
    private void openNpcDialog(Connection connection, LoginSession session,
                               int objectId, int ownerId) {
        // GM 运行时摆放优先于场景目录（对象号 900000+ 不在下标+2 号段）。
        LoginSession.GmSpawn gmSpawn = session.gmSpawns.get(objectId);
        if (gmSpawn != null) {
            if (gmSpawn.chest) {
                openGmChest(connection, session, gmSpawn);
            } else if (gmSpawn.merchant) {
                List<PropertyValues.Indexed> properties = new ArrayList<>(1);
                Props.add(properties, "LastObject",
                        PropertyValues.Value.ofObject(objectId, ownerId));
                connection.writeFrame(SceneMessages.objectProperties(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, 0, properties));
                openShop(connection, session, GIFT_SHOP_ID);
            }
            return; // 其余 GM 摆放（木桩为战斗对象，走不到这里）无对话
        }
        NpcSpawn npc = npcByObjectId(session, objectId);
        if (npc == null) {
            return; // 未物化对象（传送门等）——明细已由 npcByObjectId 记录
        }
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "LastObject", PropertyValues.Value.ofObject(objectId, ownerId));
        connection.writeFrame(SceneMessages.objectProperties(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, 0, properties));
        Integer dialogId = optionTalk().dialogIdFor(npc.resolved.configId);
        if (dialogId == null) {
            sendTaskOnlyMenu(connection, session, objectId, ownerId, npc.resolved.configId);
            return;
        }
        OptionTalkCatalog.Node node = optionTalk().node(dialogId);
        if (node == null) {
            return;
        }
        session.dialog.reset(objectId, ownerId);
        sendDialogMenu(connection, session, node, npc.resolved.configId);
    }

    /** 物化下标 → 场景目录项（NPC 对象号 = 目录下标+2，见 NpcViewport）。 */
    private static NpcSpawn npcByObjectId(LoginSession session, int objectId) {
        int index = objectId - 2;
        if (index < 0 || index >= session.sceneNpcs.size()
                || !session.activeNpcs.contains(index)) {
            log.info("session={} object {} not an active npc (menu/portal pass-through): "
                            + "index={} catalog={} activeSample={}",
                    session.machine.id(), objectId, index, session.sceneNpcs.size(),
                    session.activeNpcs.stream().limit(12).sorted().toList());
            return null;
        }
        return session.sceneNpcs.get(index);
    }

    /**
     * 组一页 0x1C 菜单：剧情链节点（仅 NextPage）每链一项「继续」（文本=下页
     * MenuID，如 qy_gcd_01_005_1=是叫我么？）；选项页逐项 + 可交任务项 +
     * 尾部「告辞」（ui_form_talk_leave，F 服抓包同款）。mark 服务端本地分配，
     * 选择后以 0x07 FuncID=mark 回发。
     */
    private void sendDialogMenu(Connection connection, LoginSession session,
                                OptionTalkCatalog.Node node, String npcConfigId) {
        List<NpcMessages.MenuItem> items = new ArrayList<>();
        for (int nextId : node.nextPages()) {
            OptionTalkCatalog.Node next = optionTalk().node(nextId);
            String text = next == null ? "" : next.menuId();
            items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                    session.dialog.allocate(new NpcDialog.NextPage(nextId)), text));
        }
        for (OptionTalkCatalog.Option option : node.options()) {
            items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                    session.dialog.allocate(new NpcDialog.ChooseOption(option)), option.textKey()));
        }
        appendSubmitTaskItems(items, session, npcConfigId);
        if (items.isEmpty() || !node.options().isEmpty()) {
            items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                    session.dialog.allocate(new NpcDialog.Leave()), TALK_LEAVE_TEXT_KEY));
        }
        if (items.isEmpty()) {
            return; // 空节点不发帧
        }
        connection.writeFrame(NpcMessages.serverMenu(
                session.dialog.objectId(), session.dialog.ownerId(), items));
    }

    /** 0x07 FuncID=mark：查对话路由表分发（消费即删，防重放）。 */
    private void dispatchMenuMark(Connection connection, LoginSession session, int funcId) {
        NpcDialog.MenuAction action = session.dialog.take(funcId);
        if (action == null) {
            log.info("session={} menu mark {} unknown (stale?)", session.machine.id(), funcId);
            connection.writeFrame(NpcMessages.clearMenu());
            return;
        }
        switch (action) {
            case NpcDialog.NextPage next -> {
                OptionTalkCatalog.Node node = optionTalk().node(next.nodeId());
                if (node != null) {
                    sendDialogMenu(connection, session, node, null);
                } else {
                    connection.writeFrame(NpcMessages.clearMenu());
                }
            }
            case NpcDialog.ChooseOption choose ->
                    applyDialogOption(connection, session, choose.option());
            case NpcDialog.AcceptTask accept -> acceptTask(connection, session, accept.taskId());
            case NpcDialog.SubmitTask submit -> submitTask(connection, session, submit.taskId());
            case NpcDialog.Leave unused -> connection.writeFrame(NpcMessages.clearMenu());
        }
    }

    /** 对话选项落地：shop=开 View61；giveitem=入包；task=接任务；trans 等留后续期。 */
    private void applyDialogOption(Connection connection, LoginSession session,
                                   OptionTalkCatalog.Option option) {
        connection.writeFrame(NpcMessages.clearMenu());
        switch (option.type().toLowerCase(java.util.Locale.ROOT)) {
            case "shop" -> openShop(connection, session, option.target());
            case "giveitem" -> grantDialogItem(connection, session, option);
            // optiontalk task 选项 target=任务 ID（optiontalk_dialog_pack.ini
            // `task|title_<id>|<id>|<接取对话>/<完成对话>`）。
            case "task" -> acceptTask(connection, session, parseTaskId(option.target()));
            default -> log.info("session={} dialog operate {} -> {} not implemented (phase backlog)",
                    session.machine.id(), option.type(), option.target());
        }
    }

    // ------------------------------------------------------------------ 任务系统

    /** 任务配置目录（share/task TSV + npcconfig 任务归属列；进程级缓存）。 */
    private static com.nineyin.server.game.avatar.task.TaskCatalog taskCatalog() {
        return com.nineyin.server.game.avatar.task.TaskCatalog.load();
    }

    /** optiontalk/npcconfig 任务号解析；非法值 0（acceptTask 侧再校验）。 */
    private static int parseTaskId(String target) {
        try {
            return Integer.parseInt(target.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 任务专线路径（NPC 无 optiontalk 对话）：npcconfig TaskCanAccept 可接 +
     * TaskCanSubmit 已接可交 + 「告辞」；均无命中不发帧（维持放行行为）。
     * 先探测后 reset（mark 必须在 reset 后分配，reset 清路由表）。
     */
    private void sendTaskOnlyMenu(Connection connection, LoginSession session,
                                  int objectId, int ownerId, String npcConfigId) {
        var npcTasks = taskCatalog().npcTasks(npcConfigId);
        List<Integer> acceptable = new ArrayList<>();
        for (int taskId : npcTasks.accept()) {
            if (taskCatalog().task(taskId) != null && canAcceptTask(session, taskId)) {
                acceptable.add(taskId);
            }
        }
        List<Integer> submittable = new ArrayList<>();
        if (session.player != null) {
            for (int taskId : npcTasks.submit()) {
                if (session.player.task().isAccepted(taskId) && taskCatalog().task(taskId) != null) {
                    submittable.add(taskId);
                }
            }
        }
        if (acceptable.isEmpty() && submittable.isEmpty()) {
            log.info("session={} npc {} has no optiontalk dialog", session.machine.id(), npcConfigId);
            return;
        }
        session.dialog.reset(objectId, ownerId);
        List<NpcMessages.MenuItem> items = new ArrayList<>(acceptable.size() + submittable.size() + 1);
        for (int taskId : acceptable) {
            items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                    session.dialog.allocate(new NpcDialog.AcceptTask(taskId)),
                    taskCatalog().task(taskId).titleId()));
        }
        for (int taskId : submittable) {
            items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                    session.dialog.allocate(new NpcDialog.SubmitTask(taskId)),
                    taskCatalog().task(taskId).titleId()));
        }
        items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                session.dialog.allocate(new NpcDialog.Leave()), TALK_LEAVE_TEXT_KEY));
        connection.writeFrame(NpcMessages.serverMenu(objectId, ownerId, items));
    }

    /** 可交任务菜单项（npcconfig TaskCanSubmit ∩ 玩家已接，文本=任务标题 key）。 */
    private void appendSubmitTaskItems(List<NpcMessages.MenuItem> items, LoginSession session,
                                       String npcConfigId) {
        if (npcConfigId == null || npcConfigId.isEmpty() || session.player == null) {
            return;
        }
        var npcTasks = taskCatalog().npcTasks(npcConfigId);
        for (int taskId : npcTasks.submit()) {
            if (!session.player.task().isAccepted(taskId)) {
                continue;
            }
            var def = taskCatalog().task(taskId);
            if (def == null) {
                continue;
            }
            items.add(new NpcMessages.MenuItem(MENU_ITEM_TYPE,
                    session.dialog.allocate(new NpcDialog.SubmitTask(taskId)), def.titleId()));
        }
    }

    /** 接取校验（不落态）：配置存在、未接未完成、前置完成（任一即可，一期 OR 假设）。 */
    private boolean canAcceptTask(LoginSession session, int taskId) {
        if (session.player == null || taskId <= 0) {
            return false;
        }
        var def = taskCatalog().task(taskId);
        if (def == null || session.player.task().isAccepted(taskId)
                || session.player.task().isCompleted(taskId)) {
            return false;
        }
        for (int pre : def.preTaskLimit()) {
            if (session.player.task().isCompleted(pre)) {
                return true;
            }
        }
        return def.preTaskLimit().isEmpty();
    }

    /**
     * 接任务（对话选项/任务专线菜单项落地）：校验 → Task_Accepted 28 列行 +
     * Task_Record story 步骤行 + 327 sub=9（on_task_add）→ 落档。
     * mark 表内的任务窗（sub=1 官方接取窗）真机验证后按需启用。
     */
    private void acceptTask(Connection connection, LoginSession session, int taskId) {
        connection.writeFrame(NpcMessages.clearMenu());
        if (session.player == null || taskId <= 0) {
            return;
        }
        long now = clock.instant().toEpochMilli();
        var result = session.player.task().accept(taskCatalog(), taskId, now);
        if (!result.ok()) {
            log.info("session={} reject accept task {}: {}",
                    session.machine.id(), taskId, result.reject());
            return;
        }
        var def = taskCatalog().task(taskId);
        connection.writeFrame(com.nineyin.server.game.avatar.task.TaskMessages.taskAcceptedRow(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                taskCatalog(), result.entry()));
        for (var step : taskCatalog().steps(taskId)) {
            if ("story".equalsIgnoreCase(step.type())) {
                connection.writeFrame(com.nineyin.server.game.avatar.task.TaskMessages.taskStoryRow(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                        taskCatalog(), step, def.trackInfoId()));
            }
        }
        connection.writeFrame(com.nineyin.server.game.avatar.task.TaskMessages.taskAdded(taskId));
        persistTask(session);
        log.info("session={} accepted task {} ({})", session.machine.id(), taskId, def.titleId());
    }

    /**
     * 交任务（SubmitTask 菜单项 / 327 "complete_task"）：先算 Task_Accepted 行号
     * 再落态 → 0x12 删行 + Task_Completed 位图行同步（删+加，行值=starter 位与
     * 玩家完成位合并）+ 327 sub=8 → 奖励 → 落档。
     */
    private void submitTask(Connection connection, LoginSession session, int taskId) {
        connection.writeFrame(NpcMessages.clearMenu());
        if (session.player == null || taskId <= 0) {
            return;
        }
        var entry = session.player.task().acceptedTask(taskId);
        if (entry == null) {
            log.info("session={} submit task {} not accepted", session.machine.id(), taskId);
            return;
        }
        int row = taskAcceptedRowNumber(session, taskId);
        var completed = session.player.task().complete(taskId);
        if (completed == null) {
            return;
        }
        connection.writeFrame(RecordMessages.recordDelRow(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                RecordMessages.RECORD_TASK_ACCEPTED, row));
        for (byte[] frame : taskCompletedRowSyncFrames(session, taskId)) {
            connection.writeFrame(frame);
        }
        connection.writeFrame(com.nineyin.server.game.avatar.task.TaskMessages.taskSubmitted(taskId));
        grantTaskRewards(connection, session, taskCatalog().prize(taskId));
        persistTask(session);
        log.info("session={} submitted task {}", session.machine.id(), taskId);
    }

    /** 放弃任务（327 sub=0，任务日志界面）：删 Task_Accepted 行；完成集不动。 */
    private void giveupTask(Connection connection, LoginSession session, int taskId) {
        if (session.player == null || taskId <= 0) {
            return;
        }
        int row = taskAcceptedRowNumber(session, taskId);
        var removed = session.player.task().giveup(taskId);
        if (removed == null) {
            log.info("session={} giveup task {} not accepted", session.machine.id(), taskId);
            return;
        }
        connection.writeFrame(RecordMessages.recordDelRow(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                RecordMessages.RECORD_TASK_ACCEPTED, row));
        persistTask(session);
        log.info("session={} gave up task {}", session.machine.id(), taskId);
    }

    /**
     * 327 CLIENT_CUSTOMMSG_TASK_MSG（task_define.lua 子命令表）：sub 0=giveup /
     * 字符串 "complete_task"=提交；1/2/reset/share 族一期 log-only。
     * sub 值线型可 int 可 string（custom_sender 两种发送方言并存）。
     */
    private void handleTaskMsg(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null || custom.values.size() < 2) {
            return;
        }
        CustomC2S.Value sub = custom.values.get(1);
        Integer taskId = custom.values.size() >= 3 ? tolerantInt(custom, 2) : null;
        if (sub.tag == 2 || sub.tag == 3) {
            switch (sub.exactInt32()) {
                case 0 -> {
                    if (taskId != null) {
                        giveupTask(connection, session, taskId);
                    }
                }
                default -> log.info("session={} task msg sub={} not implemented",
                        session.machine.id(), sub.exactInt32());
            }
            return;
        }
        String command = sub.textOrNull();
        if ("complete_task".equals(command)) {
            if (taskId != null) {
                submitTask(connection, session, taskId);
            }
            return;
        }
        log.info("session={} task msg command {} not implemented",
                session.machine.id(), command);
    }

    /**
     * 任务奖励（prize 表）：历练→PlayerProgress.addFaculty、碎银→CapitalType1
     * 同单位相加、物品 Item1..4→入包（GM grant_item 同通道）。收尾 vital 刷新
     * Faculty/CapitalType1 显示。
     */
    private void grantTaskRewards(Connection connection, LoginSession session,
                                  com.nineyin.server.game.avatar.task.TaskCatalog.PrizeDef prize) {
        if (prize == null || session.player == null) {
            return;
        }
        if (prize.faculty() > 0) {
            session.player.progress().addFaculty(prize.faculty());
        }
        if (prize.normalCapital() > 0) {
            session.player.setSilver((int) Math.min(Integer.MAX_VALUE,
                    (long) session.player.silver() + prize.normalCapital()));
        }
        if (inventoryStore != null && session.selected != null) {
            grantPrizeItem(connection, session, prize.item1(), prize.num1());
            grantPrizeItem(connection, session, prize.item2(), prize.num2());
            grantPrizeItem(connection, session, prize.item3(), prize.num3());
            grantPrizeItem(connection, session, prize.item4(), prize.num4());
        }
        connection.writeFrame(session.player.vitalUpdate());
    }

    /** prize 物品入包（applyGrantItem 背包分支同款：查静态表→insertBagItem→成对帧）。 */
    private void grantPrizeItem(Connection connection, LoginSession session,
                                String configId, int amount) {
        if (configId == null || configId.isEmpty() || amount <= 0) {
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(configId);
        if (info == null) {
            log.info("session={} task prize item {} not in item tables", session.machine.id(), configId);
            return;
        }
        int viewId = info.viewId() > 0 ? info.viewId() : 1;
        RoleInventoryStore.BagItem item = new RoleInventoryStore.BagItem(
                0, info.configId(), info.itemType(), amount, viewId, info.name(),
                info.equipType(), 0, info.script(), null, null, null, 0, 0);
        try {
            int slot = inventoryStore.insertBagItem(session.selected.id(), item);
            item = new RoleInventoryStore.BagItem(slot, item.configId(), item.itemType(),
                    item.amount(), item.viewId(), item.name(), item.equipType(), item.colorLevel(),
                    item.script(), item.anchorSlot(), item.expiresAt(), item.artPack(),
                    item.hardiness(), item.maxHardiness());
            pushBagItemFrames(connection, session, item);
        } catch (RuntimeException e) {
            log.warn("task prize bag insert {}: {}", configId, e.getMessage());
        }
    }

    /** 任务落档（操作点即存，失败仅告警——与 persistMeridian 同模式）。 */
    private void persistTask(LoginSession session) {
        if (session.selected == null || session.player == null) {
            return;
        }
        try {
            taskStore.save(session.selected.id(), session.player.taskSnapshot());
        } catch (RuntimeException e) {
            log.warn("persist task progress: {}", e.getMessage());
        }
    }

    /**
     * Task_Accepted 记录行号：客户端行序 = starter 功能解锁 15 行（登录链前缀）
     * + 玩家已接任务升序序号。删除前调用（删除后序号即失真）。
     */
    private static int taskAcceptedRowNumber(LoginSession session, int taskId) {
        int starterRows = StarterQingGong.acceptedTaskIds().size();
        int offset = 0;
        for (int id : session.player.task().acceptedIds()) {
            if (id == taskId) {
                return starterRows + offset;
            }
            if (id > taskId) {
                break;
            }
            offset++;
        }
        return starterRows + offset;
    }

    /**
     * Task_Completed 位图行同步帧：行值 = starter 基线位 | 玩家全部完成位
     * （只发本任务 mask 会清掉同行其它任务位）。
     */
    private List<byte[]> taskCompletedRowSyncFrames(LoginSession session, int taskId) {
        long[] rowAndMask = com.nineyin.server.game.avatar.task.TaskMessages.completedBit(taskId);
        long row = rowAndMask[0];
        long value = starterCompletedRowValue(row);
        var playerRows = com.nineyin.server.game.avatar.task.TaskMessages
                .completedRowValues(session.player.task().completedIds());
        value |= playerRows.getOrDefault(row, 0L);
        return com.nineyin.server.game.avatar.task.TaskMessages.taskCompletedRowSync(
                PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID, row, value);
    }

    /** starter 功能解锁完成族的位图基线行值。 */
    private static long starterCompletedRowValue(long row) {
        return com.nineyin.server.game.avatar.task.TaskMessages
                .completedRowValues(StarterQingGong.completedTaskIds())
                .getOrDefault(row, 0L);
    }

    /**
     * 登录任务恢复帧（starter 链后下发）：已接任务 Task_Accepted 28 列行
     * （starter 族去重）+ 已完成任务位图差异行（starter 基线 ∪ 玩家完成集，
     * 与基线不同的行删+加）。新玩家零帧——starter golden 帧序不受影响。
     */
    private void grantTaskRestoreFrames(Connection connection, LoginSession session) {
        if (session.player == null) {
            return;
        }
        java.util.Set<Long> starterAccepted = new java.util.HashSet<>(StarterQingGong.acceptedTaskIds());
        int restored = 0;
        for (int taskId : session.player.task().acceptedIds()) {
            if (starterAccepted.contains((long) taskId)) {
                continue; // starter 链已发，避免重复行
            }
            try {
                connection.writeFrame(com.nineyin.server.game.avatar.task.TaskMessages.taskAcceptedRow(
                        PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                        taskCatalog(), session.player.task().acceptedTask(taskId)));
                restored++;
            } catch (RuntimeException e) {
                log.warn("restore accepted task row {}: {}", taskId, e.getMessage());
            }
        }
        var combined = new java.util.HashSet<Integer>(session.player.task().completedIds());
        for (Long id : StarterQingGong.completedTaskIds()) {
            combined.add(id.intValue());
        }
        var combinedRows = com.nineyin.server.game.avatar.task.TaskMessages.completedRowValues(combined);
        var starterRows = com.nineyin.server.game.avatar.task.TaskMessages
                .completedRowValues(StarterQingGong.completedTaskIds());
        int bitmapSyncs = 0;
        for (var entry : combinedRows.entrySet()) {
            if (entry.getValue().equals(starterRows.getOrDefault(entry.getKey(), 0L))) {
                continue;
            }
            for (byte[] frame : com.nineyin.server.game.avatar.task.TaskMessages.taskCompletedRowSync(
                    PlayerActor.PLAYER_OBJECT_ID, PlayerActor.PLAYER_OWNER_ID,
                    entry.getKey(), entry.getValue())) {
                connection.writeFrame(frame);
                bitmapSyncs++;
            }
        }
        if (restored > 0 || bitmapSyncs > 0) {
            log.info("session={} restored {} accepted task rows, {} completed bitmap sync frames",
                    session.machine.id(), restored, bitmapSyncs);
        }
    }

    /**
     * 开商店 View61（form_shop.lua 只认 0x15/0x18 下发，不读 shop.ini——坑 24）。
     * F 服帧序：0x16 清旧 → 0x15 头（101 ShopID/102 PageNum=10/103 PageCount/
     * 104 ShopType=0）→ 逐行 0x18（105 ConfigID/106 Amount/107-109 SellPrice0-2/
     * 110 MaxAmount=物品堆叠上限）。f2/f4-f8 语义未证不下发。
     */
    void openShop(Connection connection, LoginSession session, String shopId) {
        ShopCatalog.ShopSection section = shopSection(shopId);
        if (section == null) {
            log.info("session={} shop {} not in shop.ini", session.machine.id(), shopId);
            return;
        }
        connection.writeFrame(SceneMessages.deleteView(SHOP_VIEW_ID));
        connection.writeFrame(SceneMessages.createViewWithProperties(
                new SceneMessages.ViewSpec(SHOP_VIEW_ID, SHOP_VIEW_CAPACITY),
                List.of(SceneMessages.ViewProperty.ofString(PROP_SHOP_ID, section.shopId()),
                        SceneMessages.ViewProperty.ofInt(PROP_PAGE_NUM, SHOP_ROWS_PER_PAGE),
                        SceneMessages.ViewProperty.ofInt(PROP_PAGE_COUNT,
                                Math.max(1, section.pageCount())),
                        SceneMessages.ViewProperty.ofInt(PROP_SHOP_TYPE, 0))));
        ToolItemCatalog items = ToolItemCatalog.loadDefault();
        int row = 1;
        for (ShopCatalog.GoodsRow goods : section.goods()) {
            ToolItemCatalog.ItemInfo info = items.lookup(goods.itemId());
            int maxAmount = info != null && info.maxAmount() > 0 ? info.maxAmount() : 99;
            connection.writeFrame(SceneMessages.viewAdd(SHOP_VIEW_ID, row++, List.of(
                    SceneMessages.ViewProperty.ofString(PROP_GOODS_CONFIG_ID, goods.itemId()),
                    SceneMessages.ViewProperty.ofInt(PROP_GOODS_AMOUNT, goods.amount()),
                    SceneMessages.ViewProperty.ofInt(PROP_SELL_PRICE0, 0),
                    SceneMessages.ViewProperty.ofInt(PROP_SELL_PRICE1, 0),
                    SceneMessages.ViewProperty.ofInt(PROP_SELL_PRICE2, goods.sellPrice2()),
                    SceneMessages.ViewProperty.ofInt(PROP_MAX_AMOUNT, maxAmount))));
        }
        session.dialog.openShop(section.shopId());
        log.info("session={} opened shop {} ({} goods)", session.machine.id(),
                section.shopId(), section.goods().size());
    }

    /**
     * msg70 custom_buy_item(shop_id,page,pos,count)（form_shop.lua buy_shop_item
     * 实证参数序）：View61 行号 = page*10+pos（pos 客户端已 +1）。碎银（CapitalType1）
     * 扣款 → 入包（复用 GM 发放链路）→ 碎银单帧刷新。
     */
    void handleBuyItem(Connection connection, LoginSession session,
                               CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            return;
        }
        String openShopId = session.dialog.openShopId();
        if (openShopId == null) {
            log.info("session={} buy without open shop", session.machine.id());
            return;
        }
        if (custom.values.size() < 5) {
            log.info("session={} buy malformed args {}", session.machine.id(), custom.values.size());
            return;
        }
        String clientShopId = custom.values.get(1).textOrNull();
        int page;
        int pos;
        int count;
        try {
            page = tolerantInt(custom, 2);
            pos = tolerantInt(custom, 3);
            count = tolerantInt(custom, 4);
        } catch (RuntimeException e) {
            log.info("session={} buy non-int args", session.machine.id());
            return;
        }
        if (clientShopId != null && !clientShopId.isEmpty()
                && !clientShopId.equalsIgnoreCase(openShopId)) {
            log.info("session={} buy shop mismatch client={} open={}",
                    session.machine.id(), clientShopId, openShopId);
            return;
        }
        ShopCatalog.ShopSection section = shopSection(openShopId);
        int rowIndex = page * SHOP_ROWS_PER_PAGE + pos;
        if (section == null || rowIndex < 1 || rowIndex > section.goods().size()) {
            log.info("session={} buy row {} out of shop {}", session.machine.id(), rowIndex, openShopId);
            return;
        }
        if (count <= 0) {
            count = 1;
        }
        ShopCatalog.GoodsRow goods = section.goods().get(rowIndex - 1);
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(goods.itemId());
        if (info == null) {
            log.info("session={} buy unknown item {}", session.machine.id(), goods.itemId());
            return;
        }
        if (inventoryStore == null) {
            log.info("session={} buy rejected: inventory DB missing", session.machine.id());
            return;
        }
        long cost = (long) goods.sellPrice2() * count;
        if (cost > session.player.silver()) {
            log.info("session={} buy insufficient silver: need {} have {}",
                    session.machine.id(), cost, session.player.silver());
            return;
        }
        long roleId = session.selected.id();
        int viewId = info.viewId() > 0 ? info.viewId() : 1;
        RoleInventoryStore.BagItem item = new RoleInventoryStore.BagItem(
                0, info.configId(), info.itemType(), count, viewId, info.name(),
                info.equipType(), 0, info.script(), null, null, null, 0, 0);
        int bagSlot;
        try {
            bagSlot = inventoryStore.insertBagItem(roleId, item);
        } catch (RuntimeException e) {
            log.info("session={} buy insert failed: {}", session.machine.id(), e.getMessage());
            return;
        }
        item = new RoleInventoryStore.BagItem(bagSlot, item.configId(), item.itemType(),
                item.amount(), item.viewId(), item.name(), item.equipType(), item.colorLevel(),
                item.script(), item.anchorSlot(), item.expiresAt(), item.artPack(),
                item.hardiness(), item.maxHardiness());
        pushBagItemFrames(connection, session, item);
        session.player.setSilver((int) (session.player.silver() - cost));
        connection.writeFrame(session.player.silverUpdate());
        log.info("session={} bought {} x{} for {} (bag slot {})", session.machine.id(),
                goods.itemId(), count, cost, bagSlot);
    }

    /** giveitem 选项：目标形如 Box_qyjl_gcd_009*1，复用 GM 发放入包链路。 */
    private void grantDialogItem(Connection connection, LoginSession session,
                                 OptionTalkCatalog.Option option) {
        if (session.selected == null || inventoryStore == null) {
            log.info("session={} giveitem skipped (no role/inventory)", session.machine.id());
            return;
        }
        ToolItemCatalog.ItemInfo info = ToolItemCatalog.loadDefault().lookup(option.itemId());
        if (info == null) {
            log.info("session={} giveitem unknown item {}", session.machine.id(), option.itemId());
            return;
        }
        int viewId = info.viewId() > 0 ? info.viewId() : 1;
        RoleInventoryStore.BagItem item = new RoleInventoryStore.BagItem(
                0, info.configId(), info.itemType(), option.itemCount(), viewId, info.name(),
                info.equipType(), 0, info.script(), null, null, null, 0, 0);
        int bagSlot;
        try {
            bagSlot = inventoryStore.insertBagItem(session.selected.id(), item);
        } catch (RuntimeException e) {
            log.info("session={} giveitem insert failed: {}", session.machine.id(), e.getMessage());
            return;
        }
        item = new RoleInventoryStore.BagItem(bagSlot, item.configId(), item.itemType(),
                item.amount(), item.viewId(), item.name(), item.equipType(), item.colorLevel(),
                item.script(), item.anchorSlot(), item.expiresAt(), item.artPack(),
                item.hardiness(), item.maxHardiness());
        pushBagItemFrames(connection, session, item);
        log.info("session={} dialog giveitem {} x{} (bag slot {})", session.machine.id(),
                option.itemId(), option.itemCount(), bagSlot);
    }

    private OptionTalkCatalog optionTalk() {
        return OptionTalkCatalog.loadDefault();
    }

    private ShopCatalog shopCatalog() {
        return ShopCatalog.loadDefault();
    }

    /** 商店节查找：gm_giftbox=合成货架（真正可开的礼包免费领），其余走 shop.ini 目录。 */
    ShopCatalog.ShopSection shopSection(String shopId) {
        if (GIFT_SHOP_ID.equals(shopId)) {
            return giftBoxShopSection();
        }
        return shopCatalog().section(shopId);
    }

    /** 礼包货架合成节（懒构建缓存）：BOX 语义且至少一个 DropID 在 drops_mvp 可解析的礼包，按 configId 排序。 */
    private ShopCatalog.ShopSection giftBoxShopSection() {
        ShopCatalog.ShopSection cached = giftShopSection;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (giftShopSection == null) {
                ItemUseCatalog uses = ItemUseCatalog.loadDefault();
                DropPacks packs = DropPacks.loadDefault();
                List<ToolItemCatalog.ItemInfo> items = new ArrayList<>(ToolItemCatalog.loadDefault().all());
                items.sort(java.util.Comparator.comparing(ToolItemCatalog.ItemInfo::configId));
                List<ShopCatalog.GoodsRow> goods = new ArrayList<>();
                for (ToolItemCatalog.ItemInfo info : items) {
                    ItemUseCatalog.UseEntry entry = uses.resolve(info.configId(), info.itemType());
                    if (entry == null || entry.kind() != ItemUseCatalog.UseKind.BOX) {
                        continue;
                    }
                    boolean openable = false;
                    for (String dropId : entry.dropIds()) {
                        if (packs.has(dropId)) {
                            openable = true;
                            break;
                        }
                    }
                    if (openable) {
                        goods.add(new ShopCatalog.GoodsRow(goods.size(), info.configId(), 1, 0));
                    }
                }
                int pageCount = Math.max(1, (goods.size() + SHOP_ROWS_PER_PAGE - 1) / SHOP_ROWS_PER_PAGE);
                giftShopSection = new ShopCatalog.ShopSection(GIFT_SHOP_ID, "", pageCount, 0,
                        List.copyOf(goods));
                log.info("gift box shop section ready: {} goods", goods.size());
            }
            return giftShopSection;
        }
    }

    private static int readU32(byte[] plain, int offset) {
        return (plain[offset] & 0xFF) | (plain[offset + 1] & 0xFF) << 8
                | (plain[offset + 2] & 0xFF) << 16 | (plain[offset + 3] & 0xFF) << 24;
    }

    /** 已学表：DB role_skills 优先，回退 starter 集；等级合并 data/武学等级.json 档值（learn_all_taolu 满级重登保持）。 */
    java.util.Map<String, Integer> learnedSkillLevels(
            long roleId, java.util.List<RoleInventoryStore.Skill> dbSkills) {
        java.util.Map<String, Integer> learned = new java.util.LinkedHashMap<>();
        if (dbSkills != null && !dbSkills.isEmpty()) {
            for (RoleInventoryStore.Skill skill : dbSkills) {
                learned.put(skill.skillId(), storedSkillLevel(roleId, skill.skillId()));
            }
            return learned;
        }
        for (java.util.Map.Entry<String, Integer> entry : StarterSkills.starterLearnedLevels().entrySet()) {
            learned.put(entry.getKey(), storedSkillLevel(roleId, entry.getKey()));
        }
        return learned;
    }

    /** 档值等级：data/武学等级.json &gt;1 用档值，否则 1。 */
    private int storedSkillLevel(long roleId, String skillId) {
        int stored = skillLevelStore.level(roleId, skillId);
        return stored > 1 ? stored : 1;
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

    /**
     * C2S 240 打坐开关：`(int 1 开始 / 0 停止 / 2 动作完结回执)`。**2 不是停坐**
     * （2026-11-04 修正，Go handleSitcrossCustom 的「2=打断当停」是误读——三服抓包
     * 12141/52905/63296 同现 [1→2×n] 且 0 从未出现；把 2 当停=坐下即被拉起，真机
     * 「打坐后人立马站起来」三服同症）。重复 1=按住非切换，但重推 102 面板 resync
     * 客户端动画态；死亡拒绝。回普通 0x10 vital（LogicState=102/0），回复由 1s
     * ticker 的 sitcrossTick 结算（10s 回满 HP/MP，HPUpSpeed/MPUpSpeed 面板值同源
     * =客户端显示的每秒 +N 数字）。
     */
    private void handleSitcross(Connection connection, LoginSession session, CustomC2S.Message custom) {
        if (session.player == null) {
            log.info("session={} sitcross before scene player exists", session.machine.id());
            return;
        }
        if (custom.values.size() < 2) {
            log.info("session={} sitcross without start_or_stop", session.machine.id());
            return;
        }
        int startOrStop = custom.values.get(1).exactInt32();
        boolean engage;
        if (startOrStop == 1) {
            engage = true;
        } else if (startOrStop == 0 || startOrStop == 2) {
            engage = false;
        } else {
            log.info("session={} ignore unknown sitcross start_or_stop={}",
                    session.machine.id(), startOrStop);
            return;
        }
        PlayerActor player = session.player;
        if (player.actorState().logicState() == Actor.LOGIC_STATE_DIED) {
            log.info("session={} reject sitcross while dead", session.machine.id());
            return;
        }
        boolean wasSitting = player.sitting();
        if (startOrStop == 2) {
            // 2026-11-04 打坐秒起定案：三服抓包（官方时代 12141 / 参考服 52905 / 本服
            // 63296）同现 [240(1) → 240(2)×n]，真停坐形态 0 从未出现——2 是坐姿动作
            // 完结/本地取消回执，不是起立指令。把 2 当停=坐下即被服务端面板拉起。
            // 2026-11-05 修正：重推全量面板本身会重入客户端坐姿动画（重推→再完结→
            // 再 2 的自持风暴，一轮坐 63 条），改为限频重推：坐/起迁移后首条 2 立即
            // 重推兜底，之后 ≥2s 闸内静默保坐。真打断由出招起立/死亡路径接管。
            if (wasSitting) {
                long now = clock.millis();
                if (now - session.lastSitcrossResyncMs >= SITCROSS_RESYNC_GATE_MS) {
                    session.lastSitcrossResyncMs = now;
                    connection.writeFrame(player.castVitalUpdate());
                    log.info("session={} sitcross keep seated on 240(2) completion ack (resync 102)",
                            session.machine.id());
                } else {
                    log.info("session={} sitcross keep seated on 240(2) (resync gated, silent)",
                            session.machine.id());
                }
            }
            return;
        }
        if (wasSitting == engage) {
            // 重复 240(1)：按住非切换，但改零帧为重推面板——客户端坐姿动画被本地取消
            // 后用户重点，重推 102 让客户端回落坐姿（desync 陷阱：零帧会让重点无效）。
            if (engage) {
                connection.writeFrame(player.castVitalUpdate());
            }
            log.info("session={} sitcross state already {} (resync panel)",
                    session.machine.id(), engage ? 102 : 0);
            return;
        }
        session.lastSitcrossResyncMs = 0L;
        player.applySitcross(engage);
        // 2026-11-05 根因修复：坐/起迁移面板必须走 188 名瘦集（castVitalUpdate＝参考服
        // 52905 #5451 坐姿面板逐名相等）。此前全量 vitalUpdate 夹带 Mount/CurRideMount
        // 等 36 个登录域属性——重推 Mount 族会让客户端清动作/移动态（同 11-03 空中
        // 位置重置根因），坐姿动画每帧被打断＝真机「完全没有打坐动画」+240(2) 风暴。
        connection.writeFrame(player.castVitalUpdate());
        log.info("session={} sitcross state {} -> {} via normal 0x10", session.machine.id(),
                wasSitting ? 102 : 0, engage ? 102 : 0);
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
            // 188 名瘦集面板（打坐中整分钟结算也走此帧——全量 vitalUpdate 夹带 Mount
            // 族会打断坐姿动画，2026-11-05 根因）。
            connection.writeFrame(session.player.castVitalUpdate());
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
        connection.writeFrame(session.player.livepointVitalUpdate());
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

    /**
     * post-spawn 推送两套已存方案（ShortcutKey1=199 / ShortcutKey2=200，无档零帧）：
     * 保存面板 check_saved 读这两个属性判定"方案一/二 已保存"，不下发则恒显示未保存。
     * 当前启用集 = bind_value（198）由 {@link #pushSavedKeybind} 同闸下发——客户端
     * 启用方案 N 时引擎以 scheme 0 上传该集，bind_value 自动跟随，重登即回推启用集。
     */
    void pushSavedKeybindSchemes(Connection connection, LoginSession session) {
        if (session.selected == null || inventoryStore == null) {
            return;
        }
        for (int slot = 1; slot <= 2; slot++) {
            String scheme;
            try {
                scheme = inventoryStore.keybindScheme(session.selected.id(), slot);
            } catch (RuntimeException e) {
                log.warn("session={} load keybind scheme {}: {}",
                        session.machine.id(), slot, e.getMessage());
                continue;
            }
            if (scheme == null || scheme.isEmpty()) {
                continue;
            }
            connection.writeFrame(keybindSchemeFrame(slot, scheme));
            log.info("session={} pushed saved keybind scheme {} ({} bytes, idx={})",
                    session.machine.id(), slot,
                    scheme.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    slot == 1 ? 199 : 200);
        }
    }

    /**
     * C2S 195 键位方案上传（引擎 ShortcutKey:EndEdit(commit, scheme) 触发）：
     * `[195, scheme, str]`，scheme 0=当前启用/1=方案一/2=方案二，串形
     * "项,键码;…"（官服 641-658B 样本）。存 role_keybinds 对应列并回推
     * 0x10 属性帧（198/199/200）——客户端属性视图以服务端下发为准，不回推
     * 则保存面板恒"未保存"。非法 scheme/串静默拒（零帧）。
     */
    void handleKeybindUpload(Connection connection, LoginSession session,
                             CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            log.info("session={} keybind upload before scene player exists", session.machine.id());
            return;
        }
        if (custom.values.size() < 3) {
            log.info("session={} keybind upload without scheme+string", session.machine.id());
            return;
        }
        int scheme = tolerantInt(custom, 1);
        String bind = custom.values.get(2).textOrNull();
        if (scheme < 0 || scheme > 2 || bind == null || bind.isEmpty()
                || bind.length() > 4096 || !bind.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            log.info("session={} ignore invalid keybind upload scheme={} len={} printable={}",
                    session.machine.id(), scheme, bind == null ? -1 : bind.length(),
                    bind != null && bind.chars().allMatch(c -> c >= 0x20 && c <= 0x7E));
            return;
        }
        connection.writeFrame(keybindSchemeFrame(scheme, bind));
        if (inventoryStore != null) {
            try {
                inventoryStore.saveKeybindScheme(session.selected.id(), scheme, bind);
            } catch (RuntimeException e) {
                log.warn("session={} persist keybind scheme {}: {}",
                        session.machine.id(), scheme, e.getMessage());
            }
        }
        log.info("session={} keybind scheme {} uploaded ({} bytes)",
                session.machine.id(), scheme,
                bind.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    /** 键位方案单属性帧：slot 0/1/2 → ShortcutKey/1/2（索引 198/199/200，type7）。 */
    static byte[] keybindSchemeFrame(int slot, String value) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, slot == 0 ? "ShortcutKey" : "ShortcutKey" + slot,
                PropertyValues.Value.ofString(value + " "));
        return SceneMessages.objectProperties(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * C2S 99 设置操作模式（CLIENT_CUSTOMMSG_SET_OPERATEMODE，
     * form_system_interface_setting.switch_mode → custom_set_operate_mode）：
     * `[99, mode]`，mode 0=3D（WASD）/1=2.5D（点击）。存 role_keybinds.operate_mode
     * 并立即回推 0x10 OperateMode（idx 223 INT32）同步客户端属性缓存；
     * 登录 post-spawn 由 {@link #pushSavedOperateMode} 回放。非法值静默拒（零帧）。
     */
    void handleSetOperateMode(Connection connection, LoginSession session,
                              CustomC2S.Message custom) {
        if (session.player == null || session.selected == null) {
            log.info("session={} operate mode before scene player exists", session.machine.id());
            return;
        }
        if (custom.values.size() < 2) {
            log.info("session={} operate mode without mode arg", session.machine.id());
            return;
        }
        int mode = tolerantInt(custom, 1);
        if (mode != 0 && mode != 1) {
            log.info("session={} ignore unknown operate mode {}", session.machine.id(), mode);
            return;
        }
        connection.writeFrame(operateModeFrame(mode));
        if (inventoryStore != null) {
            try {
                inventoryStore.saveOperateMode(session.selected.id(), mode);
            } catch (RuntimeException e) {
                log.warn("session={} persist operate mode: {}", session.machine.id(), e.getMessage());
            }
        }
        log.info("session={} operate mode set {} ({})", session.machine.id(), mode,
                mode == 0 ? "3D WASD" : "2.5D click");
    }

    /** OperateMode 单属性帧（0x10，官服 0x09 表索引 223，type3 int32）。 */
    static byte[] operateModeFrame(int mode) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "OperateMode", PropertyValues.Value.ofInt32(mode));
        return SceneMessages.objectProperties(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID, 0, properties);
    }

    /**
     * post-spawn 推送存档操作模式（键位域同表恢复）：有存档 → 0x10 OperateMode；
     * 未设置零帧（客户端保持本地默认 2.5D）。先于键位串无顺序依赖——引擎按属性
     * 缓存整体应用，mode 决定 WASD/点击操作方式，bind 决定键位映射。
     */
    void pushSavedOperateMode(Connection connection, LoginSession session) {
        Integer mode = loadOperateMode(session);
        if (mode == null) {
            return;
        }
        connection.writeFrame(operateModeFrame(mode));
        log.info("session={} pushed saved operate mode {} ({})", session.machine.id(), mode,
                mode == 0 ? "3D WASD" : "2.5D click");
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

    /** ClientReady 推送存档的坐骑状态（无存档零帧；NewRideRec 行 + NewRideID 选中项）。 */
    void pushSavedMounts(Connection connection, LoginSession session) {
        session.mountsPushed = true;
        if (session.selected == null) {
            return;
        }
        if (session.mountState == null) {
            session.mountState = mountStore.load(session.selected.id());
        }
        var state = session.mountState;
        if (state == null || state.mounts().isEmpty()) {
            return;
        }
        for (byte[] frame : mountRefreshFrames(session, state.mounts())) {
            connection.writeFrame(frame);
        }
        // 选中包单独 0x10（NewRideID；不在马上 Mount 恒空串——重登步行口径）。
        connection.writeFrame(com.nineyin.server.game.avatar.mount.MountMessages.rideStateFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                "", state.currentPack(), mountRiderTypeOf(state.currentPack())));
        log.info("session={} pushed saved mounts owned={} current={}",
                session.machine.id(), state.mounts().size(), state.currentPack());
    }

    /** 坐骑整刷帧组：清旧 NewRideRec 行 → 重推行（Selected 位随行）。 */
    private List<byte[]> mountRefreshFrames(LoginSession session,
                                            List<com.nineyin.server.game.avatar.mount.MountStore.Entry> mounts) {
        int objectId = SceneMessages.PLAYER_OBJECT_ID;
        int ownerId = SceneMessages.PLAYER_OWNER_ID;
        List<byte[]> frames = new ArrayList<>();
        frames.addAll(com.nineyin.server.game.avatar.mount.MountMessages.ownedClearFrames(
                objectId, ownerId, session.mountRows));
        frames.addAll(com.nineyin.server.game.avatar.mount.MountMessages.ownedFrames(
                objectId, ownerId, mounts));
        session.mountRows = mounts.size();
        return frames;
    }

    /** 坐骑包 → CurRiderType（MountType；未知包 0）。 */
    private int mountRiderTypeOf(int packId) {
        if (packId <= 0) {
            return 0;
        }
        var mount = com.nineyin.server.game.avatar.mount.MountCatalog.loadDefault().byPack(packId);
        return mount == null ? 0 : mount.mountType();
    }

    /** 会话坐骑状态（首访问装载存档；无存档返回 null）。 */
    private com.nineyin.server.game.avatar.mount.MountStore.State sessionMountState(LoginSession session) {
        if (session.mountState != null || session.selected == null) {
            return session.mountState;
        }
        try {
            session.mountState = mountStore.load(session.selected.id());
        } catch (RuntimeException e) {
            log.warn("session={} load mount state: {}", session.machine.id(), e.getMessage());
        }
        return session.mountState;
    }

    private void saveMountState(LoginSession session,
                                com.nineyin.server.game.avatar.mount.MountStore.State state) {
        session.mountState = state;
        try {
            mountStore.save(session.selected.id(), state);
        } catch (RuntimeException e) {
            log.warn("session={} save mount state: {}", session.machine.id(), e.getMessage());
        }
    }

    /**
     * 上马/下马核心（GM mount_ride 与 C2S 骑乘链共用）：owned 增量行刷新 +
     * 骑乘态 0x10（Mount 模型串/NewRideID/CurRiderType）+ 面板域 Mount 字段同步
     * （防全量 0x10 用空串清骑乘态）。
     */
    private void applyRideState(Connection connection, LoginSession session,
                                com.nineyin.server.game.avatar.mount.MountStore.Entry entry,
                                boolean riding) {
        var state = sessionMountState(session);
        List<com.nineyin.server.game.avatar.mount.MountStore.Entry> mounts =
                state == null ? List.of() : state.mounts();
        for (byte[] frame : mountRefreshFrames(session, mounts)) {
            connection.writeFrame(frame);
        }
        int packId = entry == null ? 0 : entry.packId();
        String model = "";
        if (riding && entry != null) {
            String resolved = com.nineyin.server.game.avatar.mount.MountCatalog.loadDefault()
                    .modelOf(entry.packId());
            model = resolved == null ? "" : resolved;
        }
        connection.writeFrame(com.nineyin.server.game.avatar.mount.MountMessages.rideStateFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                model, riding ? packId : 0, riding ? mountRiderTypeOf(packId) : 0));
        session.riding = riding;
        if (session.player != null) {
            session.player.panel().mount = model;
        }
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
        // 卸载皮肤后回落存档显示偏好（此前恒回 0=江湖装），挂载期间恒 2=皮肤优先。
        int showType = mounted.isEmpty()
                ? (session.showEquipTypePref == null
                        ? SkinMessages.SHOW_EQUIP_TYPE_NORMAL : session.showEquipTypePref)
                : SkinMessages.SHOW_EQUIP_TYPE_SKIN;
        frames.add(SkinMessages.showEquipTypeFrame(objectId, ownerId, showType, lastSkin));
        frames.add(SkinMessages.skinMsgFrame(lastSkin));
        session.skinRows = SkinMessages.collectedRowCount(collected);
        session.skinMountedRows = mounted.size();
        return frames;
    }

    /**
     * 登录期帽/时装/身份装外观 0x10 补帧：有覆盖槽（帽/16-19）或非默认显示类型
     * 才发（无存档偏好且纯装备位零帧，帧序对齐 golden 基线）。
     */
    private void pushEquipAppearance(Connection connection, LoginSession session,
                                     List<RoleInventoryStore.Equip> equips) {
        if (equips.isEmpty()) {
            return;
        }
        int effective = effectiveShowEquipType(session);
        var models = AppearanceSync.wornModels(session.visual.sex(), equips, EquipmentResolver.loadDefault());
        boolean overlay = models.containsKey(AppearanceSync.SLOT_HAT)
                || models.containsKey(AppearanceSync.SLOT_SUIT)
                || models.containsKey(AppearanceSync.SLOT_FASHION_COAT)
                || models.containsKey(AppearanceSync.SLOT_FASHION_HAT)
                || models.containsKey(AppearanceSync.SLOT_FASHION_SHOES);
        if (!overlay && effective == AppearanceSync.SHOW_EQUIP_GEAR) {
            return;
        }
        connection.writeFrame(AppearanceSync.appearanceFrame(
                SceneMessages.PLAYER_OBJECT_ID, SceneMessages.PLAYER_OWNER_ID,
                effective, session.visual.sex(), equips, EquipmentResolver.loadDefault()));
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
                    session.activeNpcs, location.position(), NPC_RADIUS, cloneWaveGate(session));
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
