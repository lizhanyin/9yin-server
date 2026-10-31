package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.Appearance;
import com.nineyin.protocol.BagCatalog;
import com.nineyin.protocol.BootstrapPackets;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.InventoryPackets;
import com.nineyin.protocol.MailPackets;
import com.nineyin.protocol.NpcPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.protocol.SkillPackets;
import com.nineyin.protocol.TeamPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxC2SHandler;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxSession;

import java.util.function.Supplier;
import com.nineyin.server.GameScheduler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 场景同步与自定义消息总线：
 * - 67 字节移动包：更新位置、取消打坐、2 秒节流落库、>1m 触发 NPC 增量补发（上限 50）
 * - 自定义 msg（0x0A 信封 + 0x1E/0x27 直连两种载体，对齐 Go）：
 *   240 打坐、200/201 快捷键、211 技能（skill_init 学习校验 + 编译目录计费/CD/动作/区域结算）、
 *   216/217 轻功、218 招架、223/5223 操作模式、64/70/71 商店（打开流程见 SelectFlow）、
 *   924 消息延迟回显、560 充值商城（货品表+校验链+金币扣减+入包）、
 *   943 兵录 / 784 乱斗 / 865/868 踢馆 / 180 卡片 / 209 任务 / 270 暮色 / 382 掉落 /
 *   249 御风 / 144 副本结算 / 517 GM 探针（C2S 校验层+日志对齐，见 docs §3.5）、
 *   215 内功 / 19 时装 / 301 VIP / 688 珍宝阁 / 692 面料 / origin 700~706 / 713 礼盒 /
 *   770 体型 / 864 生活职业 / 145 副本奖励 / 380 拾取（编号钉死批，见 docs §3.6）、
 *   2 GM 探针 / 54 手动招架 / 84+112+144+145 副本族 / 886~889 一键穿 / 938 重铸 /
 *   15/31/70/107/187/243/266/295/442~445（区间树解码批，完整 msgId 表见 docs §3.9）
 * - 传送门：移动进入触发半径（door_data.ini MoveTarget/SpringRange）
 * - 巡逻广播（任意 0x0A 驱动，500ms 节流）
 * - 每秒 tick（NineyinServer 驱动）：actor 恢复 + vital 同步 + NPC 重生
 */
public final class SceneFlow {

    public static final AttributeKey<SceneState> STATE =
            AttributeKey.valueOf("nineyin.scene.state");

    public static final int OP_ACTIVITY = 0x0A;
    public static final int OP_PING = 0x18;
    public static final int OP_CUSTOM_DIRECT_LEGACY = 0x1E;
    public static final int OP_CUSTOM_DIRECT_MODERN = 0x27;

    public static final float VIEW_RADIUS = 450f;
    private static final int INITIAL_NPC_CAP = 200;
    /** 副本场景全量摆位（clone003 实测约 330）一次性下发上限。 */
    private static final int CLONE_NPC_CAP = 400;
    private static final int STREAM_NPC_CAP = 50;
    private static final float PATROL_BROADCAST_RADIUS = 250f;
    private static final int PATROL_BROADCAST_CAP = 32;
    private static final long PORTAL_TRIGGER_COOLDOWN_MS = 2000;
    private static final long NPC_RESPAWN_MS = 15000;

    private static final Logger log = LoggerFactory.getLogger(SceneFlow.class);

    private final SceneManager sceneManager;
    private final RoleStore roleStore;
    private final QinggongCatalog qinggongCatalog;
    private final SceneSwitcher switcher;
    private final AiEventEngine ai;   // 可空：未接线时无 AI 事件（测试旧路径）
    private final NinJiuZhi ninjiuzhi;  // 全服阶段门控（缺省 alwaysOpen）
    /** 库存查询（MySQL 后端；JSON 后端为 null，msgId=30 移动回退日志）。 */
    private final com.nineyin.db.RoleInventoryStore inventory;
    /** 修炼/内功快照存取（role_faculty；JSON 后端为 null，215/1006 走拒绝日志路径）。 */
    private final com.nineyin.db.RoleFacultyStore facultyStore;
    /** 修炼等级曲线（wuxuelevelinfo.ini；升级 total 查表，null = 旧公式回退）。 */
    private WuxueLevelInfo levelCurves;
    /** 脏快照周期冲刷间隔（纯 fill 累积不必每 tick 落库）。 */
    private static final long FACULTY_FLUSH_MS = 60_000L;
    /** 技能编译目录与学习表（211；空目录时全部走"未编译"拒绝路径）。 */
    private final SkillCatalog skillCatalog;
    private final SkillInitTable skillInit;
    /** 充值商城货品表（560；缺失时购买链按 no goods 拒绝）。 */
    private final ChargeShopCatalog chargeShop;
    /** 词缀属性包目录（装备 PropModify 加成；缺失时加成层禁用，对齐参考服）。 */
    private final PropPackCatalog propPacks;
    /** 装备特效目录（equip_special_effect_pack + buff 三表，§3.17 链路）。 */
    private final com.nineyin.rule.EquipEffectCatalog equipEffects;
    /** 道具使用目录（giftbox/item_use_skill INI 回退层；scripts/item/*.lua 优先）。 */
    private final ItemUseCatalog itemUse;
    /** 掉落奖励包（drops_mvp.json；开包权重抽取，随机数 Java 预掷）。 */
    private final DropPacks dropPacks;
    /** 开包随机源（生产 ThreadLocalRandom；测试可确定化的注入点在 roll 参数）。 */
    private final java.util.function.DoubleSupplier dropRand =
            () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble();
    /** 商店表（msgId 70 真机 5 值布局按 shopId 解析；null = 仅会话快照）。 */
    private com.nineyin.shop.ShopRepository shopRepository;
    /** 技能脚本宿主（on_cast/on_hit 运行期钩子 + 伤害公式分发；null = 全走 Java 默认管线）。 */
    private com.nineyin.script.ScriptHost scriptHost;
    /** 内功特效运行时（scripts/neigong/*.lua 钩子触发与指令执行；null = 内功特效关闭）。 */
    private NeigongRuntime neigong;
    private com.nineyin.script.CombatFormulaHost formulaHost;
    /** 全服会话来源（NineyinServer 装配；玩家互播遍历同场景会话；null = 单会话模式互播关闭）。 */
    private Supplier<Iterable<FxSession>> sessionsSource;
    /** 玩家互播 objectId 分配器：500001 起避开 NPC 静态段（10001+）与动态段（900001+）。 */
    private static final java.util.concurrent.atomic.AtomicInteger PLAYER_OID_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(500001);

    /** 每会话场景同步状态。 */
    public static final class SceneState {

        public SceneWorld world;
        public boolean npcsSent;
        public final Set<Integer> sentIds = new HashSet<>();
        public final Map<Integer, Long> legSent = new HashMap<>();       // oid -> leg.startedMs
        public final Map<Integer, PendingArrive> pendingArrive = new HashMap<>();
        public float lastNpcX;
        public float lastNpcZ;
        public long lastPatrolMs;
        public long lastPosSaveMs;
        // 选中目标（SelectFlow 维护）
        public int selectedOid;
        public int selectedOwner;
        public float selectedX;
        public float selectedZ;
        // NPC 菜单（帧对话）
        public int lastMenuOid;
        public int lastMenuOwner;
        public long lastMenuAtMs;
        // 0x0C 重进场景
        public boolean awaitingReentryReady;
        public boolean qinggongGranted;
        public boolean progressGranted;
        // 轻量战斗（历史节流字段已由技能锁/CD 取代）
        public long lastPortalMs;
        // 互播位置转发节流（0x0A 位置包 → serverLocation(oid)）
        public long lastPeerPosMs;
        // 已互播的玩家 oid（sendSceneNpcs 重入时防重复 add 帧；与 sentIds 同步清理）
        public final Set<Integer> announcedPeerOids = new HashSet<>();
        // 每秒 tick
        public long lastVitalMs;
        // 道具使用节流（useItem 800ms 防连点）
        public long lastItemUseMs;
        // 修炼快照上次落库（即时/周期冲刷共用）
        public long lastFacultyFlushMs;
    }

    public record PendingArrive(long atMs, float x, float y, float z, float orient) {
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog) {
        this(sceneManager, roleStore, qinggongCatalog, null, null);
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog,
                     AiEventEngine ai) {
        this(sceneManager, roleStore, qinggongCatalog, ai, null);
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog,
                     AiEventEngine ai, NinJiuZhi ninjiuzhi) {
        this(sceneManager, roleStore, qinggongCatalog, ai, ninjiuzhi, new SkillCatalog(), new SkillInitTable());
    }

    public SceneFlow(SceneManager sceneManager, RoleStore roleStore, QinggongCatalog qinggongCatalog,
                     AiEventEngine ai, NinJiuZhi ninjiuzhi,
                     SkillCatalog skillCatalog, SkillInitTable skillInit) {
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
        this.qinggongCatalog = qinggongCatalog;
        this.ninjiuzhi = ninjiuzhi != null ? ninjiuzhi : NinJiuZhi.alwaysOpen();
        this.switcher = new SceneSwitcher(sceneManager, roleStore, this, this.ninjiuzhi);
        this.ai = ai;
        if (ai != null) {
            // NPC 技能命中玩家后的控制落账（§3.25；免疫位在 applyControlToPlayer 内消费）
            ai.setControlSink(this::applySkillControlToPlayer);
            if (neigong != null) {
                ai.setNeigongRuntime(neigong);
            }
        }
        this.inventory = com.nineyin.db.RoleInventoryStore.from(roleStore);
        this.facultyStore = com.nineyin.db.RoleFacultyStore.from(roleStore);
        this.skillCatalog = skillCatalog != null ? skillCatalog : new SkillCatalog();
        this.skillInit = skillInit != null ? skillInit : new SkillInitTable();
        // 充值商城货品表（ini/charge_shop_goods.ini，share 根的上级即 resources/modern）
        java.nio.file.Path iniRoot = sceneManager.shareRoot().getParent();
        this.chargeShop = iniRoot != null
                ? ChargeShopCatalog.load(iniRoot.resolve("ini").resolve("charge_shop_goods.ini"))
                : new ChargeShopCatalog();
        this.propPacks = PropPackCatalog.load(
                sceneManager.shareRoot().resolve("modifypack").resolve("proppack.ini"));
        this.itemUse = ItemUseCatalog.load(sceneManager.shareRoot());
        this.dropPacks = DropPacks.load(
                sceneManager.shareRoot().resolve("drop").resolve("drops_mvp.json"));
        this.equipEffects = new com.nineyin.rule.EquipEffectCatalog();
        this.equipEffects.load(sceneManager.shareRoot());
        this.controlCatalog = new com.nineyin.rule.ControlCatalog();
        this.controlCatalog.load(sceneManager.shareRoot().resolve("skill"));
    }

    /** 每件已穿装备的特效包号（equipment.ini 配置解析；0=无特效；供 applyEquipEffects）。 */
    private List<Integer> equipEffectPackIds(LoginSession session) {
        if (session.liveEquipped == null) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>(session.liveEquipped.size());
        for (com.nineyin.db.RoleInventoryStore.Equipped e : session.liveEquipped) {
            com.nineyin.protocol.EquipmentModels.ItemStatic st =
                    com.nineyin.protocol.EquipmentModels.itemStatic(e.configId());
            ids.add(st != null ? st.effectPackId() : 0);
        }
        return ids;
    }

    /** 九阴志全局门控（GM / 广播接线用）。 */
    public NinJiuZhi ninjiuzhi() {
        return ninjiuzhi;
    }

    /** 注入商店表（NineyinServer 装配；启用 msgId 70 按消息内 shopId 解析）。 */
    /** 注入脚本宿主（NineyinServer wire）：on_cast/on_hit 钩子 + 伤害公式分发 + 内功特效。 */
    public void setScriptHost(com.nineyin.script.ScriptHost host) {
        this.scriptHost = host;
        this.formulaHost = new com.nineyin.script.CombatFormulaHost(host);
        this.neigong = new NeigongRuntime(host);
        // 内功高层特效档位随九阴志阶段开放（stage 1..9；缺省 alwaysOpen = 阶段 1）
        this.neigong.setStoryStage(() -> ninjiuzhi.stage());
        if (ai != null) {
            ai.setNeigongRuntime(neigong);
        }
    }

    /** GM reload_scripts：重建脚本 state + 清技能定义缓存（进行中波次继续旧 Def）。 */
    public String reloadScripts() {
        if (scriptHost == null) {
            return "脚本系统未启用";
        }
        scriptHost.reload();
        skillCatalog.invalidateDefinitionCache();
        return scriptHost.available()
                ? "脚本已重载（" + scriptHost.loadedCount() + " 个）"
                : "脚本重载失败（native 初始化错误），已回退 Java 默认管线";
    }

    public void setShopRepository(com.nineyin.shop.ShopRepository shopRepository) {
        this.shopRepository = shopRepository;
    }

    /** 注入修炼等级曲线（NineyinServer 装配；升级 total 查 wuxuelevelinfo 曲线）。 */
    public void setWuxueLevelInfo(WuxueLevelInfo levelCurves) {
        this.levelCurves = levelCurves;
    }

    /** 邮箱存储（NineyinServer 装配 data/mail.json；测试默认内存实例）。 */
    private MailStore mailStore = MailStore.inMemory();

    public void setMailStore(MailStore mailStore) {
        this.mailStore = mailStore;
    }

    /** 组队存储（NineyinServer 装配 data/teams.json；测试默认内存实例）。 */
    private TeamManager teamManager = TeamManager.inMemory();

    public void setTeamManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    /** 控制效果目录（buff_static.StateType；构造器装载，失败容缺为空目录）。 */
    private com.nineyin.rule.ControlCatalog controlCatalog = new com.nineyin.rule.ControlCatalog();

    public void setControlCatalog(com.nineyin.rule.ControlCatalog controlCatalog) {
        this.controlCatalog = controlCatalog;
    }

    // ---------- 玩家互播（同场景会话间外观/位置帧转发） ----------

    /** 全服会话来源注入（NineyinServer 装配；缺省 null = 单会话模式互播关闭）。 */
    public void setSessionsSource(Supplier<Iterable<FxSession>> source) {
        this.sessionsSource = source;
    }

    /** 判断会话是否已就绪可收互播帧（同 world、NPC 已下发、非重进加载期）。 */
    private boolean peerReady(LoginSession peer) {
        if (peer == null || peer.fx == null || peer.playerObjectId == 0 || peer.actor == null) {
            return false;
        }
        SceneState st = peer.fx.channel().attr(STATE).get();
        return st != null && st.world != null && st.npcsSent && !st.awaitingReentryReady;
    }

    /**
     * 会话进入场景完成（sendSceneNpcs 尾部调用）：双向互播——
     * ① 其余同场景会话收到本玩家的 playerAddObject(oid)+serverLocation(oid)；
     * ② 本玩家收到已在场景其他玩家的同款两帧（自机恒 oid=1，他人用其分配 oid）。
     */
    private void announcePeerPlayers(FxSession fx, LoginSession session) {
        if (sessionsSource == null || session.playerObjectId == 0) {
            return;
        }
        SceneState st = stateOf(fx);
        byte[] selfAdd = null;
        byte[] selfLoc = null;
        for (FxSession other : sessionsSource.get()) {
            if (other == fx) {
                continue;
            }
            LoginSession peer = other.channel().attr(LoginFlow.SESSION).get();
            if (!peerReady(peer) || peer.playerObjectId == session.playerObjectId) {
                continue;
            }
            SceneState peerSt = stateOf(other);
            if (peerSt.world != st.world) {
                continue;
            }
            // 幂等：GM 重发 NPC（sendSceneNpcs 复用）不得重复互播 add 帧
            boolean iKnowPeer = st.announcedPeerOids.contains(peer.playerObjectId);
            boolean peerKnowsMe = peerSt.announcedPeerOids.contains(session.playerObjectId);
            if (iKnowPeer && peerKnowsMe) {
                continue;
            }
            if (selfAdd == null) {
                selfAdd = S2CPackets.playerAddObject(session.playerObjectId, S2CPackets.PLAYER_OWNER_ID,
                        session.roleName, session.appearance, session.x, session.y, session.z, session.orient);
                selfLoc = S2CPackets.serverLocation(session.playerObjectId, S2CPackets.PLAYER_OWNER_ID,
                        session.x, session.y, session.z, session.orient);
            }
            if (!peerKnowsMe) {
                other.send(selfAdd);
                other.send(selfLoc);
                peerSt.announcedPeerOids.add(session.playerObjectId);
            }
            if (!iKnowPeer) {
                fx.send(S2CPackets.playerAddObject(peer.playerObjectId, S2CPackets.PLAYER_OWNER_ID,
                        peer.roleName, peer.appearance, peer.x, peer.y, peer.z, peer.orient));
                fx.send(S2CPackets.serverLocation(peer.playerObjectId, S2CPackets.PLAYER_OWNER_ID,
                        peer.x, peer.y, peer.z, peer.orient));
                st.announcedPeerOids.add(peer.playerObjectId);
            }
        }
        if (selfAdd != null) {
            log.info("peer visibility: player {} (oid={}) mutual with scene peers",
                    session.roleName, session.playerObjectId);
        }
    }

    /** 本玩家离开场景（切图/断线）：其余同场景会话收 0x0E removeObject(oid)。 */
    public void announcePlayerLeft(LoginSession session) {
        if (sessionsSource == null || session == null || session.playerObjectId == 0 || session.fx == null) {
            return;
        }
        SceneState st = session.fx.channel().attr(STATE).get();
        if (st == null || st.world == null) {
            return;
        }
        byte[] remove = S2CPackets.removeObject(session.playerObjectId, S2CPackets.PLAYER_OWNER_ID);
        for (FxSession other : sessionsSource.get()) {
            if (other == session.fx) {
                continue;
            }
            LoginSession peer = other.channel().attr(LoginFlow.SESSION).get();
            if (peer == null || peer.playerObjectId == session.playerObjectId) {
                continue;
            }
            SceneState peerSt = other.channel().attr(STATE).get();
            if (peerSt == null || peerSt.world != st.world || peerSt.awaitingReentryReady) {
                continue;
            }
            other.send(remove);
        }
    }

    /** 0x0A 位置包互播转发：150ms 节流，同场景其余会话收 serverLocation(oid)。 */
    private void relayPeerPosition(FxSession fx, LoginSession session, SceneState st,
                                   float x, float y, float z, float orient, long now) {
        if (sessionsSource == null || session.playerObjectId == 0) {
            return;
        }
        if (now - st.lastPeerPosMs < 150) {
            return;
        }
        st.lastPeerPosMs = now;
        byte[] loc = S2CPackets.serverLocation(session.playerObjectId, S2CPackets.PLAYER_OWNER_ID,
                x, y, z, orient);
        for (FxSession other : sessionsSource.get()) {
            if (other == fx) {
                continue;
            }
            LoginSession peer = other.channel().attr(LoginFlow.SESSION).get();
            if (!peerReady(peer)) {
                continue;
            }
            SceneState peerSt = other.channel().attr(STATE).get();
            if (peerSt.world != st.world) {
                continue;
            }
            other.send(loc);
        }
    }

    public void register(FxOpcodeRouter router) {
        router.register(OP_ACTIVITY, this::onActivity);
        router.register(OP_PING, this::onPing);
        router.register(OP_CUSTOM_DIRECT_LEGACY, this::onDirectCustom);
        router.register(OP_CUSTOM_DIRECT_MODERN, this::onDirectCustom);
    }

    public SceneSwitcher switcher() {
        return switcher;
    }

    public SceneState stateOf(FxSession fx) {
        SceneState st = fx.channel().attr(STATE).get();
        if (st == null) {
            st = new SceneState();
            fx.channel().attr(STATE).set(st);
        }
        return st;
    }

    /** LoginFlow 的 ClientReady 钩子：首次下发场景 NPC。 */
    public void sendSceneNpcs(LoginSession session) {
        FxSession fx = session.fx;
        SceneState st = stateOf(fx);
        // 切图重入：旧场景同伴先收 0x0E 移除帧（断线路径走 NineyinServer.onDisconnected）
        if (st.world != null && st.npcsSent) {
            announcePlayerLeft(session);
        }
        st.sentIds.clear();
        st.announcedPeerOids.clear();
        st.world = sceneManager.world(session.spawnResource);
        st.world.tick(System.currentTimeMillis());
        // 装备词缀加成（库存在 onChooseRole 已下发，此处 liveEquipped 就绪；进场景属性批走有效值）
        if (session.actor != null) {
            session.actor.recomputeEquipBonuses(propPacks, session.liveEquipped);
            session.actor.applyEquipEffects(equipEffects, propPacks, equipEffectPackIds(session));
        }
        List<NpcInstance> visible = visibleNpcs(st.world, session.x, session.z);
        int cap = st.world.cloneScene ? CLONE_NPC_CAP : INITIAL_NPC_CAP;
        int sent = 0;
        for (NpcInstance n : visible) {
            float dx = n.x - session.x;
            float dz = n.z - session.z;
            if (dx * dx + dz * dz > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
            fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
            st.sentIds.add(n.objectId);
            if (++sent >= cap) {
                break;
            }
        }
        st.lastNpcX = session.x;
        st.lastNpcZ = session.z;
        st.npcsSent = true;
        // 玩家互播：首进场景分配 oid（会话内稳定），向同场景同伴双向同步外观/位置
        if (session.playerObjectId == 0) {
            session.playerObjectId = PLAYER_OID_SEQ.getAndIncrement();
        }
        announcePeerPlayers(fx, session);
        // 邮箱行（记录 17 RecvLetterRec）：进场景推送该角色全部邮件（对齐 publishSystemMailRecords）
        if (session.roleId != 0) {
            List<MailStore.Mail> mails = mailStore.list(session.roleId);
            for (int i = 0; i < mails.size(); i++) {
                fx.send(mailRowFrame(i, mails.get(i)));
            }
        }
        // 组队视图（View 190）：进场景在队则补发（对齐 resyncTeam 登录补发）
        if (session.roleId != 0) {
            TeamManager.Team team = teamManager.teamOf(session.roleId);
            if (team != null) {
                fx.send(TeamPackets.teamViewCreate(team.members.size()));
                for (int i = 0; i < team.members.size(); i++) {
                    TeamManager.Member m = team.members.get(i);
                    fx.send(TeamPackets.teamMemberRow(i + 1, m.name(),
                            m.roleId() == team.captainRoleId));
                }
            }
        }
        log.info("scene {} NPCs sent={} of {} loaded (portals={})", session.spawnResource, sent,
                st.world.npcs.size(), st.world.portals.size());
    }

    /** 模板命中 + 非剧情 + 非活动怪，按距离排序（相近时功能 NPC 优先）；副本场景按 NpcProgress 波次门控。 */
    List<NpcInstance> visibleNpcs(SceneWorld world, float px, float pz) {
        List<NpcInstance> hits;
        synchronized (world) {
            hits = new ArrayList<>();
            for (NpcInstance n : world.npcs) {
                var tmpl = sceneManager.templates().lookup(n.configId);
                if (tmpl == com.nineyin.scene.NpcTemplate.MISSING) {
                    continue;
                }
                if ("eventnpc".equalsIgnoreCase(tmpl.scriptClass())) {
                    continue;
                }
                if (world.cloneScene && n.npcProgress > 0 && world.dungeonProgress < n.npcProgress) {
                    continue;
                }
                if (!ninjiuzhi.npcOpen(n.configId)) {
                    continue;
                }
                if (n.dead) {
                    continue;
                }
                hits.add(n);
            }
        }
        hits.sort(Comparator.comparingDouble((NpcInstance n) -> {
            float dx = n.x - px;
            float dz = n.z - pz;
            return (double) (dx * dx + dz * dz);
        }).thenComparingInt(SceneFlow::residentScore));
        return hits;
    }

    /** 重生间隔：数据 RefreshTime 优先，未配置走 15s 默认。 */
    static long respawnDelay(NpcInstance n) {
        return n.refreshMs >= 0 ? n.refreshMs : NPC_RESPAWN_MS;
    }

    private static int residentScore(NpcInstance n) {
        String id = n.configId.toLowerCase(Locale.ROOT);
        return id.contains("leader") || id.contains("world") ? -100 : 0;
    }

    // ---------- 0x18 ping → 回显客户端 tick 的 0x38（HUD 延迟显示依赖此回包） ----------

    private void onPing(FxSession fx, byte[] plain) {
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        if (session == null) {
            return;
        }
        int tick = plain.length >= 5 ? leU32(plain, 1) : 0;
        fx.send(S2CPackets.timeSync(tick, session.timeSeq++));
        if (++session.pingCount % 10 == 0) {
            fx.send(S2CPackets.objectPropertyInt(PropertyId.TIME_TICK,
                    LocalDateTime.now().getHour() * 60 + LocalDateTime.now().getMinute()));
        }
    }

    // ---------- 0x0A ----------

    private void onActivity(FxSession fx, byte[] plain) {
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        if (session == null) {
            return;
        }
        SceneState st = stateOf(fx);

        if (st.awaitingReentryReady) {
            // 目标场景加载期的旧场景活动包，忽略
            return;
        }

        // 67 字节移动位置包：偏移 0x26 起 4 个类型码 4 的 f32
        if (plain.length == 67 && plain.length > 0x26 + 20
                && plain[0x26] == 4 && plain[0x26 + 5] == 4
                && plain[0x26 + 10] == 4 && plain[0x26 + 15] == 4) {
            float mx = readF32(plain, 0x27);
            float my = readF32(plain, 0x2C);
            float mz = readF32(plain, 0x31);
            float mo = readF32(plain, 0x36);
            session.x = mx;
            session.y = my;
            session.z = mz;
            session.orient = mo;
            // 移动取消打坐
            if (session.actor != null && session.actor.logicState == PlayerActor.LOGIC_STATE_SITCROSS) {
                session.actor.logicState = PlayerActor.LOGIC_STATE_NORMAL;
                fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, 0));
                fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, "stand"));
                log.info("sitcross cancelled by move");
            }
            long now = System.currentTimeMillis();
            if (session.accountKey.length() > 8 && now - st.lastPosSaveMs > 2000) {
                st.lastPosSaveMs = now;
                roleStore.updateLocation(session.accountKey, mx, my, mz, mo);
            }
            // 玩家互播：位置转发（150ms 节流，同场景其余会话收 serverLocation(oid)）
            relayPeerPosition(fx, session, st, mx, my, mz, mo, now);
            // 选中目标超出 28m 自动清除
            SelectFlow.autoClearIfNeeded(fx, session, st);
            // 传送门 proximity 触发
            if (checkPortalProximity(fx, session, st, now)) {
                return;
            }
            // >1m 触发增量补发
            if (st.npcsSent && st.world != null) {
                float moved = (session.x - st.lastNpcX) * (session.x - st.lastNpcX)
                        + (session.z - st.lastNpcZ) * (session.z - st.lastNpcZ);
                if (moved > 1.0f) {
                    st.lastNpcX = session.x;
                    st.lastNpcZ = session.z;
                    streamNewNpcs(fx, st, session);
                }
            }
        } else {
            CustomMsg msg = CustomMsg.parse(plain);
            if (msg.ok()) {
                dispatchCustom(fx, session, msg);
            }
        }

        // 任意 0x0A 驱动巡逻广播（500ms 节流）
        broadcastPatrol(fx, st, session);
    }

    // ---------- 0x1E / 0x27 直连自定义消息 ----------

    private void onDirectCustom(FxSession fx, byte[] plain) {
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        if (session == null) {
            return;
        }
        CustomMsg msg = CustomMsg.parseDirect(plain);
        if (!msg.ok() || msg.val(0) == null || msg.val(0).type != CustomMsg.TYPE_I32) {
            log.debug("reject malformed C2S custom opcode=0x{} from {}",
                    Integer.toHexString(plain[0] & 0xFF), fx.remoteAddress());
            return;
        }
        dispatchCustom(fx, session, msg);
    }

    /** 传送门 proximity：进入 MoveTarget/SpringRange 半径即触发（零售客户端行为）。 */
    private boolean checkPortalProximity(FxSession fx, LoginSession session, SceneState st, long now) {
        if (st.world == null || st.world.portals.isEmpty()) {
            return false;
        }
        if (now - st.lastPortalMs < PORTAL_TRIGGER_COOLDOWN_MS) {
            return false;
        }
        for (SceneWorld.Portal portal : st.world.portals) {
            com.nineyin.scene.DoorRoutes.Route route = portal.route();
            float dx = route.trigger().x() - session.x;
            float dz = route.trigger().z() - session.z;
            float radius = route.triggerRadius();
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            st.lastPortalMs = now;
            if (route.sameScene()) {
                switcher.teleportWithinScene(fx, session, st,
                        route.position().x(), route.position().y(),
                        route.position().z(), route.position().orient());
                log.info("portal proximity teleport source={} npc={}", route.source(), portal.objectId());
            } else {
                switcher.switchScene(fx, session, st, route.config(), route.resource(),
                        route.position().x(), route.position().y(),
                        route.position().z(), route.position().orient());
                log.info("portal proximity switch source={} npc={} -> {}/{}",
                        route.source(), portal.objectId(), route.config(), route.resource());
            }
            return true;
        }
        return false;
    }

    private void streamNewNpcs(FxSession fx, SceneState st, LoginSession session) {
        int added = 0;
        for (NpcInstance n : visibleNpcs(st.world, session.x, session.z)) {
            if (st.sentIds.contains(n.objectId)) {
                continue;
            }
            float dx = n.x - session.x;
            float dz = n.z - session.z;
            if (dx * dx + dz * dz > VIEW_RADIUS * VIEW_RADIUS) {
                continue;
            }
            fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
            fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
            st.sentIds.add(n.objectId);
            if (++added >= STREAM_NPC_CAP) {
                break;
            }
        }
    }

    void dispatchCustom(FxSession fx, LoginSession session, CustomMsg msg) {
        int mid = msg.msgId();
        switch (mid) {
            case 240 -> handleSit(fx, session, msg);
            case 30 -> handleMoveItem(fx, session, msg);
            case 200 -> handleShortcutSet(fx, session, msg);
            case 201 -> handleShortcutRemove(fx, session, msg);
            case 211 -> handleSkill(fx, session, msg);
            case 216 -> handleQingGong(fx, session, msg);
            case 217 -> handleActiveQingGong(fx, session, msg);
            case 218 -> handleActiveParry(fx, session, msg);
            // 操作模式切换：223/5223 为实机抓包编号；反汇编证实 Go switch 的 case 值是 99
            //（223/5223 在 exe .text 无任何比较点），99 一并接入
            case 223, 5223, 99 -> {
                CustomMsg.Val v = msg.val(1);
                if (v != null && v.type == CustomMsg.TYPE_I32) {
                    session.operateMode = v.asInt();
                    fx.send(S2CPackets.objectPropertyInt(223, session.operateMode));
                }
            }
            // 显示装备类型（参考服：showequip_type set to %d / invalid value %d；
            // 外观 gear visual 帧的线格式未钉死，先校验+存储）
            case 61 -> handleShowEquipType(fx, session, msg);
            // PK 三开关（参考服参数校验串钉死布局）：
            // 291 TVarList(291, mode)；292 无参 toggle（响应走 normal 0x10，属性待钉死）；
            // 293 TVarList(293, type, onoff)
            case 291 -> handleChangePkMode(fx, session, msg);
            case 292 -> handleChangeWarning(fx, session, msg);
            case 293 -> handlePkProtectToggle(fx, session, msg);
            // 用户须知"同意"确认（实测：客户端进世界后 ~30s 首发并每 ~1 分钟重复，直至服务器确认；
            // 反汇编：819 不在 custom switch（Go 在登录/场景流内处理），Java 保留实测编号）
            case 819 -> handleTermsSign(fx, session);
            // ---- 2026-10-30 custom 轮：参数布局/行为已从 exe 字符串钉死的消息 ----
            // 消息延迟探测：[924, i64 tick]，回显 tick 供客户端测往返延迟
            case 924 -> handleMessageDelay(fx, session, msg);
            // 充值商城：[560, cmd, ...]，cmd=3 购买 [560,3,charge_id,nums]，2/4/5 忽略
            case 560 -> handleChargeShop(fx, session, msg);
            // 御风蓄力完成确认（参考服在蓄力窗口内等待 249，无移动则超时取消）
            case 249 -> handleYufengChargeConfirm(fx, session);
            // 副本结算确认（参考服推 col result 后 awaiting C2S 144；反汇编归属 Clone 族）
            case 144 -> handleColResultConfirm(fx, session);
            // 卡片（sub_command 路由，USE/USESKILL/SAVE 的子编号未钉死）
            case 180 -> handleCard(fx, session, msg);
            // 任务面板（无活跃任务时参考服直接忽略）
            case 209 -> handleTaskPanel(fx, session, msg);
            // 暮色道具技能（无活跃暮色场景拒绝）
            case 270 -> handleTwilightItemSkill(fx, session, msg);
            // 关闭掉落箱（Java 无 DropBox 实体，先对齐日志）
            case 382 -> handleCloseDrop(fx, session);
            // 兵录修改：[943, sub(int), name(str), num(int)]
            case 943 -> handleBingluModify(fx, session, msg);
            // 乱斗：[784, sub(int), ...]，sub=10 OPEN_LIMITE index/origin_id
            case 784 -> handleLuanDou(fx, session, msg);
            // 踢馆：sub 6=确认切换 / 7=离场（线序见 artifacts/audit/tiguan 文档）
            case 865 -> handleTiguan(fx, session, msg);
            // 踢馆轮盘：[868, action(str)]（反汇编：868 不在 custom switch——Go 在 865
            // handler 内子路由轮盘；Java 按审计线序保留独立 case）
            case 868 -> handleTiguanRoulette(fx, session, msg);
            // ---- 2026-10-30 custom 第七轮：区间树解码拉全 switch 表 ----
            // GM 探针（反汇编：2 → handleGMCustom；517 为旧误传，非 Go case）
            case 2, 517 -> handleGmProbe(fx, session, msg);
            // 高级轻功入场信号（反汇编：15 → signalAdvancedQingGongEntry）
            case 15 -> handleAdvancedQingGongEntry(fx, session, msg);
            // 身体道具使用（反汇编：31 → handleBodyItemUse）
            case 31 -> handleBodyItemUse(fx, session, msg);
            // 右键格挡（F 参考服 2026-10-30 抓包：int64 0=按下/1=松开，松开连发 2-3 个 1 须幂等；
            // 218 为主动招架，另路）
            case 54 -> handleManualParry(fx, session, msg);
            // 商店购买（反汇编：70 → handleShopBuyCustom；64 打开未在 custom switch 出现，
            // 开店走 SelectFlow 的 NPC 菜单链）
            case 64 -> log.info("shop msg=64 open argc={} (open via NPC menu; response pending capture)",
                    msg.size());
            case 70 -> handleShopBuy(fx, session, msg);
            // 副本族（反汇编：84/112/144/145 → handleCloneCustom：进入/列表/结算确认/奖励拾取；
            // 144 的精确日志见下方独立 case）
            case 84, 112, 145 -> handleClone(fx, session, msg);
            // 捏脸保存（反汇编：107 → handleSaveCustomizing）
            case 107 -> handleSaveCustomizing(fx, session, msg);
            // 属性商城（反汇编：187 → handleAttributeMallCustom）
            case 187 -> handleAttributeMall(fx, session, msg);
            // 称号佩戴（反汇编：295 → handleSetTitleCustom，`reject wear title %d: not owned`）
            case 295 -> handleSetTitle(fx, session, msg);
            // 骑乘上下骑/新坐骑（反汇编：243=Ride / 266=NewRide）
            case 243, 266 -> handleRide(fx, session, msg);
            // 组队（§3.24：op=首值，外层将 308-319 与 326/388/442-445 一并路由至此）
            case 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319,
                 326, 388, 442, 443, 444, 445 -> handleTeam(fx, session, msg);
            // 一键穿（反汇编：886~889 → handleOnestepEquipCustom，`wear now requires one
            // wearable equipment item`）
            case 886, 887, 888, 889 -> handleOnestepEquip(fx, session, msg);
            // 武器重铸总入口（反汇编：938 → handleWeaponRecastCustom；Preview/Lock/Unlock/Sure 为其子命令）
            case 938 -> handleWeaponRecast(fx, session, msg);
            // ---- 2026-10-30 custom 第六轮：反汇编钉死批（je-相等提取 + 全 .text 常量扫描） ----
            // 聊天（反汇编：1 → handleChatCustom）
            case 1 -> handleChat(fx, session, msg);
            // 道具动作（反汇编：34/71 → handleItemActionCustom；71 曾被旧分析误标"商店关闭"）
            case 34, 71 -> handleItemAction(fx, session, msg);
            // 经脉（反汇编：150/161 → handleJingMaiCustom）
            case 150, 161 -> handleJingMai(fx, session, msg);
            // 快捷键方案保存（反汇编：195 → handleSaveShortcutScheme）
            case 195 -> handleSaveShortcutScheme(fx, session, msg);
            // 修炼进度（反汇编：215 → handlePlayerProgressCustom，内功子命令同属此 handler）
            case 215 -> handlePlayerProgress(fx, session, msg);
            // 修炼面板子命令（Go 同 handler 内分支：1006 → faculty 1/11/12/21/22/23/24/31/32）
            case 1006 -> handleFacultyInfo(fx, session, msg);
            // 邮件（反汇编：350/353/355/356/357/359/361/365 → handleMailCustom，§3.23 八编号全链）
            case 350, 353, 355, 356, 357, 359, 361, 365 -> handleMail(fx, session, msg);
            // 骑乘技能（反汇编：961 → handleRideSkillUse）
            case 961 -> handleRideSkillUse(fx, session, msg);
            // ---- 2026-10-30 custom 第四轮：§3.5 候补清单编号钉死批 ----
            // 时装套装（第八轮链追踪：19 = 使用物品广义链 FashionSuit→Yufeng→GiftBox→
            // EquipItemUse（右键穿）→WuJiBook，bool 链式认领，见 handleFashionSuit）
            case 19 -> handleFashionSuit(fx, session, msg);
            // VIP（头部校验：无命令/命令非整数；价格与购买的 cmd 编号待钉死）
            case 301 -> handleVip(fx, session, msg);
            // 珍宝阁（全 int 参数 + sub_type 路由）
            case 688 -> handleTersure(fx, session, msg);
            // 面料混合（`attire blend 692 missing args args=[%s]`）
            case 692 -> handleAttireBlend(fx, session, msg);
            // 出身/起源家族（[70X, origin_id(int)]）
            case 700, 701, 702, 705, 706 -> handleOrigin(fx, session, msg);
            // 礼盒领取（`gift box catalog unavailable: %v`）
            case 713 -> handleGiftBoxClaim(fx, session, msg);
            // C 面板体型（BodyScale）
            case 770 -> handleBodyScale(fx, session, msg);
            // 生活职业（`unknown life job %q`）
            case 864 -> handleLifeJob(fx, session, msg);
            // 副本奖励 View80 拾取（`reject 145 no clone awards view`）
            // 掉落拾取（`reject 380 pickup without vpos` / `bad vpos %s`）
            case 380 -> handlePickup(fx, session, msg);
            default -> {
                // 监控：对齐参考服 default 分支 `unhandled custom msg_id=%d args=[%s]`；
                // 参考服逐条打，这里首现 INFO、重复 DEBUG 防刷屏（语义一致）
                if (session.seenCustomMsgIds.add(mid)) {
                    log.info("unhandled custom msg_id={} args=[{}] (first this session account={})",
                            mid, summarizeVals(msg), session.accountKey);
                } else {
                    log.debug("unhandled custom msg_id={} args=[{}]", mid, summarizeVals(msg));
                }
            }
        }
    }

    /**
     * 819 用户须知签署：落库 agreed_terms/agreed_terms_at（幂等，重复点击不刷新时间），
     * 回推属性 269 签署时间 / 270 签署标志，客户端据此停止周期性重弹协议框。
     */
    private void handleTermsSign(FxSession fx, LoginSession session) {
        if (session.accountKey.isEmpty()) {
            return;
        }
        boolean first = roleStore.markTermsAgreed(session.accountKey);
        long[] t = roleStore.termsState(session.accountKey);
        session.termsAgreed = (int) t[0];
        session.termsAgreedAtSec = t[1];
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_LAST_SIGN_TIME, (int) t[1]));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_SIGN_COUNT, (int) t[0]));
        log.info("terms sign accepted account={} first={} signCount={} at={} ip={}",
                session.accountKey, first, t[0], t[1], fx.remoteAddress());
    }

    /**
     * 291 PK 模式切换（参考服：change pk mode expects TVarList(291, mode) / invalid mode）。
     * 权威值 0x10 推送 PKMode(436, short)；官服属性表 type 1 → i16 编码。
     */
    private void handleChangePkMode(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 291, TVarList.intArg("mode"));
        if (!r.ok()) {
            log.info("change pk mode expects {}, got {}", r.expected(), r.got());
            return;
        }
        int mode = msg.val(1).intOrZero();
        if (mode < 0 || mode > 2) {
            log.info("change pk mode invalid mode {}", mode);
            return;
        }
        int old = session.pkMode;
        session.pkMode = mode;
        fx.send(S2CPackets.objectPropertyShort(PropertyId.PK_MODE, mode));
        log.info("pk mode {} -> {} (property 436 pushed)", old, mode);
    }

    /**
     * 292 警告开关（参考服：warning expects TVarList(292) with no extra values；
     * change warning %d -> %d via normal 0x10）。0x10 目标属性 ID 未从 exe 钉死，
     * 先翻转+存储，属性推送待抓包补上（见 docs/go-exe-protocol-analysis.md §3.2）。
     */
    private void handleChangeWarning(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 292);
        if (!r.ok()) {
            log.info("change warning expects {} with no extra values, got {}", r.expected(), r.got());
            return;
        }
        boolean next = !session.warningOn;
        int old = session.warningOn ? 1 : 0;
        session.warningOn = next;
        log.info("change warning {} -> {} (0x10 property pending capture)", old, next ? 1 : 0);
    }

    /**
     * 293 PK 保护开关（参考服：toggle expects TVarList(293, type, onoff)；
     * client pk protect toggle type=%d on=%d）。
     */
    private void handlePkProtectToggle(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 293, TVarList.intArg("type"), TVarList.intArg("onoff"));
        if (!r.ok()) {
            log.info("pk protect toggle expects {}, got {}", r.expected(), r.got());
            return;
        }
        session.pkProtectType = msg.val(1).intOrZero();
        session.pkProtectOn = msg.val(2).intOrZero() != 0;
        log.info("pk protect toggle type={} on={}", session.pkProtectType, session.pkProtectOn);
    }

    /** 61 显示装备类型（参考服：showequip_type set to %d / invalid value %d）。 */
    private void handleShowEquipType(FxSession fx, LoginSession session, CustomMsg msg) {
        TVarList.Result r = TVarList.check(msg, 61, TVarList.intArg("type"));
        if (!r.ok()) {
            log.info("showequip_type expects {}, got {}", r.expected(), r.got());
            return;
        }
        int type = msg.val(1).intOrZero();
        if (type < 0) {
            log.info("showequip_type invalid value {}", type);
            return;
        }
        session.showEquipType = type;
        log.info("showequip_type set to {}", type);
    }

    // ---- 2026-10-30 custom 轮：参数布局/行为已从 exe 钉死的消息 ----

    /**
     * 924 消息延迟探测（参考服：`message delay 924: expected [int32:924, int64:tick]`）。
     * 响应回显 tick（`message delay 924 response`）；回显载体按 custom 0x1E 同 msgId
     * 假设实现——精确响应格式待实机抓包钉死。
     */
    private void handleMessageDelay(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val tick = msg.val(1);
        if (msg.size() != 2 || tick == null || tick.type != CustomMsg.TYPE_I64) {
            log.info("message delay 924: expected [int32:924, int64:tick], got {}", summarizeVals(msg));
            return;
        }
        fx.send(CustomPackets.intMessage(924, tick.asLong()));
        log.info("message delay 924 response");
    }

    /**
     * 560 充值商城（cmd=3 购买 [560,3,charge_id,nums]；2 礼物/4 收藏/5 取消收藏忽略）。
     * 计价货币按金币（CapitalType0=415）建模，capital 映射待实机抓包；
     * 发放路由：game_items 可解析的货品入背包，时装/坐骑/礼包走专用 store（未实装，不扣币）。
     */
    private void handleChargeShop(FxSession fx, LoginSession session, CustomMsg msg) {
        // "未选角"按会话是否已选/建角色判定（roleId 是存储产物：JSON 后端恒 0，
        // MySQL 建角当次会话也尚未回读，不能作为选角依据）
        if (!session.hasRole) {
            log.info("charge shop 560 arrived before role selection");
            return;
        }
        CustomMsg.Val cmdVal = msg.val(1);
        if (cmdVal == null || !cmdVal.asExactInt()) {
            log.info("charge shop 560 command is not an integer: {}",
                    cmdVal != null ? cmdVal : "<missing>");
            return;
        }
        int cmd = cmdVal.intOrZero();
        switch (cmd) {
            case 2 -> {
                log.info("ignored charge shop present (cmd 2)");
                return;
            }
            case 4 -> {
                log.info("ignored charge shop favorite (cmd 4)");
                return;
            }
            case 5 -> {
                log.info("ignored charge shop favorite remove (cmd 5)");
                return;
            }
            case 3 -> {
                // buy
            }
            default -> {
                log.info("ignored charge shop command {}", cmd);
                return;
            }
        }
        if (msg.size() != 4) {
            log.info("charge shop buy expects [560,3,charge_id,nums], got {} values", msg.size());
            return;
        }
        CustomMsg.Val idVal = msg.val(2);
        CustomMsg.Val numsVal = msg.val(3);
        if (!idVal.asExactInt()) {
            log.info("charge shop buy charge_id is not an integer: {}", idVal);
            return;
        }
        if (!numsVal.asExactInt()) {
            log.info("charge shop buy nums is not an integer: {}", numsVal);
            return;
        }
        int chargeId = idVal.intOrZero();
        int nums = numsVal.intOrZero();
        if (nums < 1 || nums > ChargeShopCatalog.MAX_NUMS) {
            log.info("charge shop buy nums={} outside 1..{}", nums, ChargeShopCatalog.MAX_NUMS);
            return;
        }
        ChargeShopCatalog.Goods goods = chargeShop.byChargeId(chargeId);
        if (goods == null) {
            log.info("charge shop has no goods charge_id={}", chargeId);
            return;
        }
        if (goods.priceType() != 0) {
            log.info("charge shop goods {} price_type={} not supported", chargeId, goods.priceType());
            return;
        }
        PlayerActor actor = session.actor;
        if (actor == null || inventory == null || session.liveBagged == null) {
            log.info("charge shop buy has no bag");
            return;
        }
        long cost = goods.price() * nums;
        if (actor.gold < cost) {
            log.info("charge shop goods {} needs gold={}, have {}", chargeId, cost, actor.gold);
            return;
        }
        var add = inventory.addBagItem(session.roleId, goods.configId(), nums);
        if (add.fail() != null) {
            if ("full".equals(add.fail())) {
                log.info("charge shop goods {} ({}): bag full", chargeId, goods.configId());
            } else {
                log.info("charge shop goods {} ({}): grant route pending "
                        + "(fashion/mount/box store not implemented)", chargeId, goods.configId());
            }
            return;
        }
        // 背包帧：清格 + 落位（页与镜像成对，节奏对齐 handleMoveItem 的入包路径）
        int view = InventoryPackets.bagViewOf(add.view());
        var item = new com.nineyin.db.RoleInventoryStore.Bagged(
                add.slot(), goods.configId(), add.itemType(), nums, add.view(), "");
        List<byte[]> out = new ArrayList<>();
        out.add(InventoryPackets.viewRemove(view, add.slot()));
        out.add(InventoryPackets.bagViewAdd(
                item, InventoryPackets.uniqueId(session.roleId, "bag", add.slot()), view));
        int mirror = InventoryPackets.mirrorOf(view);
        if (mirror != 0) {
            out.add(InventoryPackets.viewRemove(mirror, add.slot()));
        }
        actor.gold -= cost;
        out.add(S2CPackets.playerBatch()
                .i64(PropertyId.CAPITAL_TYPE0, actor.gold).build());
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.liveBagged.add(item);
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                session.roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
        log.info("charge shop bought {} x{} (charge_id={}) for {} gold, left={}",
                goods.configId(), nums, chargeId, cost, actor.gold);
    }

    // ---- 2026-10-30 custom 第七轮：区间树解码拉全 switch 表 ----

    /**
     * 70 商店购买（参考服证据链）：`shop %s buy page=%d pos=%d must be positive` →
     * `buy amount=%d outside 1..%d` → `has no listing at page=%d pos=%d` →
     * `item %s stock=%d < requested=%d` → `unsupported capital type %d` →
     * `item %s needs silver=%d, have %d` → 成功 `shop %s bought %s x%d for %d (capital %d)`。
     * 挂单来自会话打开的商店快照（SelectFlow.openShop）；列语义与 game_shop_items
     * 互证：0=config 1=amount(库存) 2=capital(2=碎银) 3=price 5=page 6=position。
     * 购得物品经 game_items 入包（解析失败不扣款）；碎银走 CapitalType1。
     */
    private void handleShopBuy(FxSession fx, LoginSession session, CustomMsg msg) {
        // 布局两形态（§3.19 实机钉死）：真机 5 值 [70, shopId(str), page(0 基), pos(1 基), amount]
        // （Go 拒绝串 "shop buy needs 5 values (msg, shopid, page, pos, amount)"）；
        // 旧 4 值 [70, page(1 基), pos, amount] 保留容错。
        int page;
        int pos;
        int nums;
        String shopArg = null;
        boolean fiveValue = msg.size() >= 5
                && (msg.val(1).type == CustomMsg.TYPE_STRING || msg.val(1).type == CustomMsg.TYPE_WIDE_STRING)
                && msg.val(2).asExactInt() && msg.val(3).asExactInt() && msg.val(4).asExactInt();
        if (fiveValue) {
            shopArg = msg.val(1).asString();
            page = msg.val(2).intOrZero() + 1;
            pos = msg.val(3).intOrZero();
            nums = msg.val(4).intOrZero();
        } else {
            if (msg.size() < 4 || !msg.val(1).asExactInt() || !msg.val(2).asExactInt()
                    || !msg.val(3).asExactInt()) {
                log.info("shop buy malformed (want [70, shopid, page, pos, amount]) vals={}",
                        summarizeVals(msg));
                return;
            }
            page = msg.val(1).intOrZero();
            pos = msg.val(2).intOrZero();
            nums = msg.val(3).intOrZero();
        }
        var shop = session.openShop;
        if (shopArg != null && !shopArg.isEmpty()) {
            var resolved = shopRepository != null ? shopRepository.get(shopArg) : null;
            if (resolved != null) {
                shop = resolved;
            } else if (shop == null || !shopArg.equals(shop.id())) {
                log.info("shop {} buy reject: unknown shop id (open={})",
                        shopArg, shop != null ? shop.id() : "<none>");
                return;
            }
        }
        String shopId = shop != null ? shop.id() : "<none>";
        if (page <= 0 || pos <= 0) {
            log.info("shop {} buy page={} pos={} must be positive", shopId, page, pos);
            return;
        }
        if (nums < 1 || nums > ChargeShopCatalog.MAX_NUMS) {
            log.info("shop {} buy amount={} outside 1..{}", shopId, nums, ChargeShopCatalog.MAX_NUMS);
            return;
        }
        if (shop == null) {
            log.info("shop {} has no listing at page={} pos={} (no open shop)", shopId, page, pos);
            return;
        }
        // 挂单查找：page 列 5、position 列 6（0 基；客户端 pos 1 基）
        com.nineyin.shop.ShopRepository.ShopItem listing = null;
        for (var item : shop.items()) {
            if (colInt(item, 5, 1) == page && colInt(item, 6, 0) == pos - 1
                    && !colStr(item, 0).isEmpty()) {
                listing = item;
                break;
            }
        }
        if (listing == null) {
            log.info("shop {} has no listing at page={} pos={}", shopId, page, pos);
            return;
        }
        String config = colStr(listing, 0);
        int stock = colInt(listing, 1, 1);
        int capital = colInt(listing, 2, 2);
        int price = colInt(listing, 3, 0);
        PlayerActor actor = session.actor;
        if (actor == null) {
            log.info("shop {} item {} reject: no actor", shopId, config);
            return;
        }
        if (stock < nums) {
            log.info("shop {} item {} stock={} < requested={}", shopId, config, stock, nums);
            return;
        }
        // capital 2=碎银（DB 互证）；3=兑换(price=0) 同走碎银计价；其余不支持
        if (capital != 2 && capital != 3) {
            log.info("shop {} item {} unsupported capital type {}", shopId, config, capital);
            return;
        }
        long cost = (long) price * nums;
        if (actor.silver < cost) {
            log.info("shop {} item {} needs silver={}, have {}", shopId, config, cost, actor.silver);
            return;
        }
        // 入包（game_items 可解析才扣款；grant 路由与充值商城一致）
        if (session.roleId == 0 || inventory == null) {
            log.info("shop {} item {} reject: no bag store", shopId, config);
            return;
        }
        var add = inventory.addBagItem(session.roleId, config, nums);
        if (add.fail() != null) {
            if ("full".equals(add.fail())) {
                log.info("shop {} item {}: bag full", shopId, config);
            } else {
                log.info("shop {} item {}: grant route missing (not in game_items)", shopId, config);
            }
            return;
        }
        actor.silver -= cost;
        int view = InventoryPackets.bagViewOf(add.view());
        var granted = new com.nineyin.db.RoleInventoryStore.Bagged(
                add.slot(), config, 0, nums, add.view(), "");
        List<byte[]> out = new ArrayList<>();
        out.add(InventoryPackets.viewRemove(view, add.slot()));
        out.add(InventoryPackets.bagViewAdd(
                granted, InventoryPackets.uniqueId(session.roleId, "bag", add.slot()), view));
        int mirror = InventoryPackets.mirrorOf(view);
        if (mirror != 0) {
            out.add(InventoryPackets.viewRemove(mirror, add.slot()));
        }
        out.add(S2CPackets.playerBatch()
                .i64(PropertyId.CAPITAL_TYPE1, actor.silver).build());
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.liveBagged.add(granted);
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                session.roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
        log.info("shop {} bought {} x{} for {} (capital {})", shopId, config, nums, cost, capital);
    }

    /** shop.ini 挂单列取值（int）。 */
    private static int colInt(com.nineyin.shop.ShopRepository.ShopItem item, int col, int def) {
        var cols = item.columns();
        if (col >= cols.size() || cols.get(col).isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(cols.get(col).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** shop.ini 挂单列取值（str）。 */
    private static String colStr(com.nineyin.shop.ShopRepository.ShopItem item, int col) {
        var cols = item.columns();
        return col < cols.size() ? cols.get(col).trim() : "";
    }

    /**
     * 副本族（反汇编：84/112/144/145 → handleCloneCustom）。Java 无副本实例运行时：
     * 84/112 按 `clone %d unhandled` 分支；145 保留精确拒绝文本
     * （`reject 145 no clone awards view`）。
     */
    private void handleClone(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.msgId() == 145) {
            log.info("reject 145 no clone awards view");
            return;
        }
        log.info("clone msg={} unhandled (clone runtime not implemented; vals={})",
                msg.msgId(), summarizeVals(msg));
    }

    /** 15 高级轻功入场信号（反汇编：signalAdvancedQingGongEntry；轻功状态机未接）。 */
    private void handleAdvancedQingGongEntry(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("advanced qinggong entry signal vals={} (gj state machine not implemented)",
                summarizeVals(msg));
    }

    /**
     * 31 使用物品链（第十轮反汇编钉死：Yufeng/GiftBox/EquipItemUse/WuJiBook 四个
     * handler 都自检 msgId==31，链式认领；参数 `[31, view(int), slot(int), ...]`
     * 至少 4 值——EquipItemUse 右键穿用 view+slot 定位背包物品）。
     * Java：BodyItemUse（身体道具表未接）与御风/礼盒/无极段落未实装先拒绝，
     * EquipItemUse 段走共享穿装核心；首次穿戴记绑定（会话级，持久化待接）。
     */
    private void handleBodyItemUse(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.size() < 4) {
            log.info("item use 31 malformed vals={}", summarizeVals(msg));
            return;
        }
        if (session.roleId == 0 || session.liveBagged == null || session.liveEquipped == null) {
            log.info("right-click equip reject: no live inventory");
            return;
        }
        int view = intArg(msg, 1);
        int slot = intArg(msg, 2);
        // view+slot 反查背包物品
        com.nineyin.db.RoleInventoryStore.Bagged item = null;
        for (var b : session.liveBagged) {
            if (b.slot() == slot && InventoryPackets.bagViewOf(b.viewId()) == view) {
                item = b;
                break;
            }
        }
        if (item == null) {
            log.info("right-click equip reject: bag[{}:{}] empty", view, slot);
            return;
        }
        if (equipTypeOf(item.configId()).isEmpty()) {
            // 不可穿戴 → 道具使用效果链（scripts/item 优先，INI 回退）
            useItem(fx, session, item, view);
            return;
        }
        String uid = InventoryPackets.uniqueId(session.roleId, "bag", slot);
        boolean firstWear = session.boundUids.add(uid);
        if (firstWear) {
            log.info("right-click equip {} bound (first wear)", item.configId());
        }
        log.info("right-click equip uid={} config={}", uid, item.configId());
        wearBagItem(fx, session, item, view);
    }

    /** 右键格挡 BuffInParry：buff 槽 11（本服表 →BufferInfo11=638）、StaticData 4558、持续 24h（参考 setPlayerBlock）。 */
    private static final int PARRY_BUFF_SLOT = 11;
    private static final int PARRY_BUFF_STATIC_DATA = 4558;
    private static final long PARRY_BUFF_DURATION_MS = 24L * 60 * 60 * 1000;

    /**
     * 54 右键格挡（F 参考服抓包 + exe handleBlockStateCustom 逆向，2026-10-31）：
     * int64 参数 0=按下、1=松开，按值幂等落态；状态未变（客户端 500ms 重发等不到
     * 确认、松开连发 2-3 个 1）直接吞掉不回帧，状态变化才回两帧——这正是客户端
     * 格挡动画的触发条件：
     * ① 0x1E msg169 BuffInParry buff 事件（end=!parrying、双 ObjRef(玩家)、int64 nowMs）；
     * ② 0x10 属性帧 InParry(470)/InManualParry(473) + BufferInfo11(638)（索引按本服 0x09 表）。
     * 施法锁期间按下被忽略（参考 `block engage ignored during cast`）。
     */
    private void handleManualParry(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            log.info("manual parry before scene player exists");
            return;
        }
        CustomMsg.Val state = msg.val(1);
        if (state == null || !state.asExactInt()) {
            log.info("manual parry arg invalid ({}), keep parrying={}", state, session.actor.parrying);
            return;
        }
        PlayerActor actor = session.actor;
        boolean engage = state.intOrZero() == 0;
        long now = System.currentTimeMillis();
        if (engage && now < actor.skillLockUntilMs) {
            log.info("block engage ignored during cast lock remaining={}ms", actor.skillLockUntilMs - now);
            return;
        }
        if (actor.parrying == engage) {
            return;   // 重复包：对齐参考 setPlayerBlock 去重，不回帧
        }
        actor.parrying = engage;
        String buffInfo;
        if (engage) {
            buffInfo = actor.activateBuff(PARRY_BUFF_SLOT, PARRY_BUFF_STATIC_DATA,
                    PARRY_BUFF_DURATION_MS, "BuffInParry", 1);
        } else {
            actor.clearBuff(PARRY_BUFF_SLOT, PARRY_BUFF_STATIC_DATA);
            buffInfo = "";
        }
        fx.send(CustomPackets.intMessage(169,
                engage ? 0 : 1,                     // end 标志 = !parrying
                "BuffInParry",
                new CustomPackets.ObjRef(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID),
                new CustomPackets.ObjRef(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID),
                actor.name, actor.name,
                now,
                0));
        int flag = engage ? 1 : 0;
        fx.send(S2CPackets.objectProperties(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID,
                S2CPackets.PropVal.of(PropertyId.IN_PARRY, flag),
                S2CPackets.PropVal.of(PropertyId.IN_MANUAL_PARRY, flag),
                S2CPackets.PropVal.text(PropertyId.BUFFER_INFO_FIRST + PARRY_BUFF_SLOT - 1, buffInfo)));
        log.info("publish manual parry state={} (arg={} endurance={})",
                actor.parrying, state.intOrZero(), actor.parryEndurance);
    }

    /** 107 捏脸保存（参考服：`customizing upload too large: %d`；customizing 存储未接）。 */
    private void handleSaveCustomizing(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("handle customizing upload vals={} (customizing store not implemented)",
                summarizeVals(msg));
    }

    /** 187 属性商城（反汇编：handleAttributeMallCustom）。 */
    private void handleAttributeMall(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("attribute mall vals={} (mall catalog not implemented)", summarizeVals(msg));
    }

    /** 295 称号佩戴（参考服：`reject wear title %d: not owned`；称号库未接）。 */
    private void handleSetTitle(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val title = msg.val(1);
        int id = title != null && title.asExactInt() ? title.intOrZero() : -1;
        log.info("reject wear title {}: not owned (title store not implemented)", id);
    }

    /** 243/266 骑乘（参考服：`ride activate`/`new_ride`；坐骑库未接）。 */
    private void handleRide(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("ride msg={} vals={} (mount store not implemented)",
                msg.msgId(), summarizeVals(msg));
    }

    /**
     * 886~889 一键穿（参考服：`onestep equip uid=%s` / `not found in bags` /
     * `wear now requires one wearable equipment item`）。按背包 uid（ViewAdd 下发的
     * MD5 串）认领物品，穿装走共享核心 {@link #wearBagItem}。
     */
    private void handleOnestepEquip(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val uidVal = msg.val(1);
        String uid = uidVal != null && uidVal.str != null ? uidVal.str : "";
        if (session.roleId == 0 || session.liveBagged == null || session.liveEquipped == null) {
            log.info("wear now requires one wearable equipment item (no live inventory)");
            return;
        }
        if (uid.isEmpty()) {
            log.info("wear now requires one wearable equipment item (no uid vals={})",
                    summarizeVals(msg));
            return;
        }
        // uid 反查背包（uid 即 ViewAdd 下发的 MD5(roleId:bag:slot)）
        com.nineyin.db.RoleInventoryStore.Bagged item = null;
        int fromView = 0;
        for (var b : session.liveBagged) {
            int view = InventoryPackets.bagViewOf(b.viewId());
            if (InventoryPackets.uniqueId(session.roleId, "bag", b.slot()).equals(uid)) {
                item = b;
                fromView = view;
                break;
            }
        }
        if (item == null) {
            log.info("onestep equip uid={} not found in bags", uid);
            return;
        }
        // 穿装细节（槽位/换位/加成）由 wearBagItem 记录；这里只记认领
        log.info("onestep equip uid={} config={}", uid, item.configId());
        wearBagItem(fx, session, item, fromView);
    }

    /**
     * 共享穿装核心（一键穿 886 / 右键穿 31 共用）：定位槽位（官方存档实测映射
     * {@link #EQUIP_SLOTS}）→ 背包清格+镜像 → 旧装备让位 → 0x18 纸娃娃 ViewAdd →
     * WeaponMode 模型帧 → §3.7 词缀加成重算 → inventoryFrames 缓存重建 → 持久化。
     *
     * @return 被换下的旧装备 configId；目标格无旧装备返回 ""；物品不可穿返回 null
     *         （已打 `wear now requires...` / `right-click equip ... reject` 日志）
     */
    private String wearBagItem(FxSession fx, LoginSession session,
                               com.nineyin.db.RoleInventoryStore.Bagged item, int fromView) {
        String equipType = equipTypeOf(item.configId());
        int slot = equipSlotFor(equipType, session.liveEquipped);
        if (equipType.isEmpty() || slot <= 0) {
            log.info("wear now requires one wearable equipment item (config={} type={})",
                    item.configId(), equipType);
            return null;
        }
        long roleId = session.roleId;
        int fromSlot = item.slot();
        int category = bagWireToCategory(fromView);
        session.liveBagged.remove(item);
        List<byte[]> out = new ArrayList<>();
        out.add(InventoryPackets.viewRemove(fromView, fromSlot));
        int fromMirror = InventoryPackets.mirrorOf(fromView);
        if (fromMirror != 0) {
            out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
        }
        com.nineyin.db.RoleInventoryStore.Equipped old = removeEquipped(session, slot);
        String oldConfig = null;
        if (old != null) {
            oldConfig = old.configId();
            int oldSlot = freeBagSlot(session.liveBagged, InventoryPackets.VIEW_EQUIP_BAG);
            out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_BAG, oldSlot));
            out.add(InventoryPackets.bagViewAdd(
                    new com.nineyin.db.RoleInventoryStore.Bagged(
                            oldSlot, old.configId(), old.itemType(), 1, 2, ""),
                    InventoryPackets.uniqueId(roleId, "bag", oldSlot),
                    InventoryPackets.VIEW_EQUIP_BAG));
            int m = InventoryPackets.mirrorOf(InventoryPackets.VIEW_EQUIP_BAG);
            if (m != 0) {
                out.add(InventoryPackets.viewRemove(m, oldSlot));
            }
            session.liveBagged.add(new com.nineyin.db.RoleInventoryStore.Bagged(
                    oldSlot, old.configId(), old.itemType(), 1, 2, ""));
            if (inventory != null && !inventory.swapEquipSlot(roleId, slot, oldSlot)) {
                log.warn("wear swap equip[{}]->bag[{}] persist failed", slot, oldSlot);
            }
        }
        com.nineyin.db.RoleInventoryStore.Equipped worn =
                new com.nineyin.db.RoleInventoryStore.Equipped(
                        slot, item.configId(), item.itemType(), equipType, 100, 100, "");
        out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_DOLL, slot));
        out.add(InventoryPackets.equipmentViewAdd(
                worn, InventoryPackets.uniqueId(roleId, "equip", slot)));
        session.liveEquipped.add(worn);
        if (inventory != null && !inventory.moveBagToEquip(roleId, fromSlot, slot, category)) {
            log.warn("wear bag[{}]->equip[{}] persist failed", fromSlot, slot);
        }
        out.add(InventoryPackets.weaponModeFrame(session.liveEquipped,
                Appearance.resolveVisual(session.appearance).sex()));
        if (session.actor != null) {
            int hpBefore = session.actor.maxHp();
            int mpBefore = session.actor.maxMp();
            session.actor.recomputeEquipBonuses(propPacks, session.liveEquipped);
            session.actor.applyEquipEffects(equipEffects, propPacks, equipEffectPackIds(session));
            if (session.actor.maxHp() != hpBefore || session.actor.maxMp() != mpBefore) {
                out.add(S2CPackets.playerBatch()
                        .i32(PropertyId.MAX_HP, session.actor.maxHp())
                        .i32(PropertyId.MAX_MP, session.actor.maxMp()).build());
                out.add(session.actor.vitalUpdate());
            }
        }
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
        log.info("wear config={} -> slot={}{}", item.configId(), slot,
                oldConfig != null ? " (swapped out " + oldConfig + ")" : "");
        return oldConfig != null ? oldConfig : "";
    }

    /**
     * EquipType → 身体槽位（官方 equip.json 存档 33 件实测；双槽类型给两个候选，
     * 运行时选第一个空槽，全占用时替换第一槽）。未收录类型返回 0（按不可穿拒绝）。
     */
    private static final Map<String, int[]> EQUIP_SLOTS = Map.of(
            "hat", new int[]{1},
            "cloth", new int[]{3},
            "pants", new int[]{4},
            "wrist", new int[]{6},
            "leg", new int[]{7},
            "shoes", new int[]{8},
            "earring", new int[]{11, 12},
            "necklace", new int[]{13},
            "fingerring", new int[]{14, 15},
            "weapon", new int[]{22});

    /** 类型对应的穿戴槽：双槽类型优先空槽；类型未收录返回 0。 */
    static int equipSlotFor(String equipType, List<com.nineyin.db.RoleInventoryStore.Equipped> equipped) {
        if (equipType == null) {
            return 0;
        }
        int[] slots = EQUIP_SLOTS.get(equipType.toLowerCase(Locale.ROOT));
        if (slots == null) {
            return 0;
        }
        for (int s : slots) {
            boolean taken = false;
            for (var e : equipped) {
                if (e.slot() == s) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return s;
            }
        }
        return slots[0];
    }

    /**
     * 938 武器重铸总入口（反汇编：handleWeaponRecastCustom；Preview/Lock/Unlock/Sure 是其
     * 子命令——`recast preview reject: need 5 args, got %d`、`recast lock reject %s has no
     * rand pool`；重铸资源 `weapon recast resources not loaded` 未接）。
     */
    private void handleWeaponRecast(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("weapon recast vals={} (recast resources not loaded)", summarizeVals(msg));
    }



    /**
     * 1 聊天（参考服：`reject chat before player spawn` / `chat text is empty` /
     * `ignore empty chat (channel=%d)`；S2C 为 echo 帧——
     * `chat echo object=%d channel=%d chatType=%d text=%q`，帧格式待钉死）。
     * 布局按 echo 串反推：[1, channel(int), chatType(int), text(str), ...]。
     */
    private void handleChat(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            log.info("reject chat before player spawn");
            return;
        }
        CustomMsg.Val channel = msg.val(1);
        if (channel == null || !channel.asExactInt()) {
            log.info("ignore empty chat (channel=none) vals={}", summarizeVals(msg));
            return;
        }
        int ch = channel.intOrZero();
        CustomMsg.Val text = msg.val(3);
        if (text == null || text.str == null || text.str.isEmpty()) {
            log.info("ignore empty chat (channel={})", ch);
            return;
        }
        // echo 帧格式（custom msgId/值序）待实机抓包钉死后接入
        log.info("chat echo pending capture object=1 channel={} chatType={} text=\"{}\"",
                ch, intArg(msg, 2), text.str.length() > 48 ? text.str.substring(0, 48) + "…" : text.str);
    }

    /** 34/71 道具动作（参考服：`item action %d has invalid view/slot`；动作语义子表未接）。 */
    private void handleItemAction(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("item action msg={} vals={} (action table not implemented)",
                msg.msgId(), summarizeVals(msg));
    }

    /** 150/161 经脉（参考服：`jingmai custom requires an int subcommand` / `jingmai toggle active=%v name=%q`；经脉表未接）。 */
    private void handleJingMai(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        if (sub == null || !sub.asExactInt()) {
            log.info("jingmai custom requires an int subcommand (vals={})", summarizeVals(msg));
            return;
        }
        log.info("jingmai msg={} sub={} (jingmai store not implemented)",
                msg.msgId(), sub.intOrZero());
    }

    /** 195 快捷键方案保存（参考服：`save shortcut scheme sub=%d len=%d` / `unknown sub=%d`；方案存储未接）。 */
    private void handleSaveShortcutScheme(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        if (sub == null || !sub.asExactInt()) {
            log.info("save shortcut scheme malformed vals={}", summarizeVals(msg));
            return;
        }
        log.info("save shortcut scheme unknown sub={} len={} (scheme store not implemented)",
                sub.intOrZero(), msg.size() - 2);
    }

    /**
     * 组队（§3.24 反汇编 + 实机钉死）：**op = 首值**（外层 custom switch 将
     * 308-319/326/388/442-445 一并路由至此，teamOpIDs 18 编号校验）。
     * 参数形状（decodeNativeTeamRequest）：{310,311,312,444} 恰 1 值；
     * {313,316,445} = 2 值 [op, 名字(宽串)]；其余 op 走 int 参数路径。
     * 行为：310 建队 → 0x15 view190 + 成员行 + 0x10 全量 resync（实机帧序）；
     * 312/313 离队 → 仅 0x10（实机无视图删除帧）；其余 op 校验层 + Go 同构日志
     * （`team: operation %d requires %d values`）。
     */
    private void handleTeam(FxSession fx, LoginSession session, CustomMsg msg) {
        int op = msg.msgId();   // op 即首值（反汇编 `mov 0x4(%r9),%ecx` 后查 teamOpIDs）
        if (session.actor == null) {
            log.info("reject team op {} before player spawn", op);
            return;
        }
        switch (op) {
            case 310 -> {   // CreateTeam（恰 1 值）
                if (msg.size() != 1) {
                    log.info("team: operation {} requires {} values", op, 1);
                    return;
                }
                String name = session.roleName != null ? session.roleName : "";
                TeamManager.Team team = teamManager.create(session.roleId, name);
                fx.send(TeamPackets.teamViewCreate(team.members.size()));
                for (int i = 0; i < team.members.size(); i++) {
                    TeamManager.Member m = team.members.get(i);
                    fx.send(TeamPackets.teamMemberRow(i + 1, m.name(),
                            m.roleId() == team.captainRoleId));
                }
                fx.send(session.actor.vitalUpdate());
                log.info("team {} created via captain {} members={}",
                        team.id, session.roleId, team.members.size());
            }
            case 312, 313 -> {   // Leave（313 为带名字变体：2 值宽串）
                if (op == 313 ? msg.size() != 2 : msg.size() != 1) {
                    log.info("team: operation {} requires {} values", op, op == 313 ? 2 : 1);
                    return;
                }
                if (op == 313) {
                    CustomMsg.Val v = msg.val(1);
                    if (v == null || v.type != CustomMsg.TYPE_WIDE_STRING) {
                        log.info("team: operation {} requires {} values", op, 2);
                        return;
                    }
                }
                if (teamManager.leave(session.roleId)) {
                    fx.send(session.actor.vitalUpdate());
                    log.info("team member {} left team", session.roleId);
                }
            }
            // 名字 op（宽串）：{316,445}
            case 316, 445 -> {
                if (msg.size() != 2 || msg.val(1) == null
                        || msg.val(1).type != CustomMsg.TYPE_WIDE_STRING) {
                    log.info("team: operation {} requires {} values", op, 2);
                    return;
                }
                log.info("team op {} name={} pending (companion team ops unpinned)",
                        op, msg.val(1).str);
            }
            // 443/444 = Invite（§3.24 反汇编：TeamOf 前置 + 尾值扫描 type6/7 + TrimSpace
            // → team.Manager.Invite 挂起 offer；offer 送达与接受 op 待真实客户端钉死）
            case 443, 444 -> {
                String invitee = null;
                for (int i = msg.size() - 1; i >= 1; i--) {
                    CustomMsg.Val v = msg.val(i);
                    if (v != null && (v.type == CustomMsg.TYPE_STRING
                            || v.type == CustomMsg.TYPE_WIDE_STRING)) {
                        invitee = v.str == null ? "" : v.str.trim();
                        break;
                    }
                }
                if (invitee == null || invitee.isEmpty()) {
                    log.info("team invite requires a name value vals={}", summarizeVals(msg));
                    return;
                }
                log.info("team invite name={} offer pending (delivery/accept unpinned)", invitee);
            }
            // 其余 op（311/314/315/317/318/319/326/388/442）：校验层 + 待钉死
            default -> log.info("team op {} vals={} (per-op behavior pending pinning)",
                    op, summarizeVals(msg));
        }
    }

    /**
     * 邮件八编号（§3.23 反汇编 + 实机钉死）：
     * 350/365 打开/刷新（mailStore.Load，行帧不重推）；353 [serial] 已读 → 0x13 col9=1；
     * 355/359 [serial] 领附件 → 物品入包（0x19/0x18 页+镜像对）+ 0x13(col7="" + col9=1)；
     * 356/361 [serial] 删除 → 0x12 删行 + 余下行按新行号重推；
     * 357 [int,…] 选择（参数非整型对齐 `mail selection has invalid fields`）。
     * serial 未命中一律静默（实机行为）。
     */
    private void handleMail(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null || session.roleId == 0) {
            return;
        }
        switch (msg.msgId()) {
            case 350, 365 -> {
                mailStore.reload();
                log.info("mail {} reload rows={}", msg.msgId(), mailStore.count(session.roleId));
            }
            case 353 -> {
                String serial = lastMailSerial(msg);
                int row = serial != null ? mailStore.setRead(session.roleId, serial) : -1;
                if (row >= 0) {
                    fx.send(MailPackets.readFlag(row));
                    log.info("mail read serial={} row={}", serial, row);
                }
            }
            case 355, 359 -> claimMailAttachment(fx, session, msg);
            case 356, 361 -> {
                String serial = lastMailSerial(msg);
                int row = serial != null ? mailStore.delete(session.roleId, serial) : -1;
                if (row >= 0) {
                    fx.send(MailPackets.deleteRow(row));
                    List<MailStore.Mail> rest = mailStore.list(session.roleId);
                    for (int i = row; i < rest.size(); i++) {
                        fx.send(mailRowFrame(i, rest.get(i)));
                    }
                    log.info("mail delete serial={} row={} left={}", serial, row, rest.size());
                }
            }
            case 357 -> {
                CustomMsg.Val v = msg.size() >= 2 ? msg.val(1) : null;
                if (v == null || (v.type != CustomMsg.TYPE_I32 && v.type != CustomMsg.TYPE_I64)) {
                    log.info("mail selection has invalid fields vals={}", summarizeVals(msg));
                } else {
                    log.info("mail selection field={}", v.asInt());
                }
            }
            default -> log.info("mail msg={} vals={}", msg.msgId(), summarizeVals(msg));
        }
    }

    /** 尾参 serial（Go 取最后一个值且要求 string 型非空；否则视为未命中静默）。 */
    private String lastMailSerial(CustomMsg msg) {
        if (msg.size() < 2) {
            return null;
        }
        CustomMsg.Val v = msg.val(msg.size() - 1);
        if (v == null || (v.type != CustomMsg.TYPE_STRING && v.type != CustomMsg.TYPE_WIDE_STRING)
                || v.str == null || v.str.isEmpty()) {
            return null;
        }
        return v.str;
    }

    private byte[] mailRowFrame(int rowNo, MailStore.Mail mail) {
        return MailPackets.mailRow(rowNo, mail.senderId, mail.titleId, mail.contentId,
                mail.attachment == null ? "" : MailPackets.attachmentXml(
                        mail.attachment.configId(), mail.attachment.amount()),
                mail.createdAt, mail.read, mail.serial);
    }

    /** 355/359 领附件：物品经 addBagItem 入包（与 560 充值商城同构，但不扣款、无 0x1E-05 提示）。 */
    private void claimMailAttachment(FxSession fx, LoginSession session, CustomMsg msg) {
        String serial = lastMailSerial(msg);
        if (serial == null) {
            return;
        }
        int row = mailStore.indexOf(session.roleId, serial);
        MailStore.Mail claimed = row >= 0 ? mailStore.takeAttachment(session.roleId, serial) : null;
        if (claimed == null || claimed.attachment == null) {
            return;
        }
        if (inventory == null || session.liveBagged == null) {
            mailStore.restoreAttachment(session.roleId, serial, claimed.attachment);
            log.info("mail claim serial={} has no bag", serial);
            return;
        }
        var add = inventory.addBagItem(session.roleId, claimed.attachment.configId(),
                claimed.attachment.amount());
        if (add.fail() != null) {
            mailStore.restoreAttachment(session.roleId, serial, claimed.attachment);
            log.info("mail claim serial={} {} bag reject: {}", serial,
                    claimed.attachment.configId(), add.fail());
            return;
        }
        int view = InventoryPackets.bagViewOf(add.view());
        var item = new com.nineyin.db.RoleInventoryStore.Bagged(
                add.slot(), claimed.attachment.configId(), 0,
                claimed.attachment.amount(), add.view(), "");
        List<byte[]> out = new ArrayList<>();
        out.add(InventoryPackets.viewRemove(view, add.slot()));
        out.add(InventoryPackets.bagViewAdd(
                item, InventoryPackets.uniqueId(session.roleId, "bag", add.slot()), view));
        int mirror = InventoryPackets.mirrorOf(view);
        if (mirror != 0) {
            out.add(InventoryPackets.viewRemove(mirror, add.slot()));
        }
        out.add(MailPackets.attachmentClaimed(row));
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.liveBagged.add(item);
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                session.roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
        log.info("mail claim serial={} row={} grant {} x{} bag[{}:{}]",
                serial, row, claimed.attachment.configId(), claimed.attachment.amount(),
                add.view(), add.slot());
    }

    /** GM send_test_mail（GM 面板动作）：写库并立即推新邮件行（对齐实机 gm-mail 推送）。 */
    /**
     * GM 发物品入包（give_item 动作；对齐 F 参考服 GM give item 的测试用途）。
     * 走 addBagItem（容量感知找格 + game_items 落页），帧节奏对齐商店入包路径。
     *
     * @return null 成功；否则给 GM 面板的失败原因
     */
    /**
     * 道具使用效果核心（msg31 不可穿戴分支）：
     * scripts/item/&lt;configId&gt;.lua 的 on_use 优先 → INI 回退
     * （giftbox.ini 开包 / item_use_skill_static 回血阶梯）→ 均无则保持原日志行为。
     * 落账：效果先行（给物品容量感知、回血/回蓝、限时属性），成功后消耗 1 个
     * （堆叠感知）并回背包帧对；会话级 800ms 使用节流防连点。
     */
    private void useItem(FxSession fx, LoginSession session,
                         com.nineyin.db.RoleInventoryStore.Bagged item, int view) {
        PlayerActor actor = session.actor;
        if (actor == null || inventory == null || session.roleId == 0) {
            return;
        }
        SceneState st = stateOf(fx);
        long now = System.currentTimeMillis();
        if (now - st.lastItemUseMs < 800) {
            return;
        }
        // ① 效果解析：Lua 脚本优先
        com.nineyin.script.ItemScriptBridge.Instructions instr = null;
        if (scriptHost != null) {
            party.iroiro.luajava.value.LuaValue module = scriptHost.itemModule(item.configId());
            if (module != null) {
                party.iroiro.luajava.value.LuaValue fn = module.get("on_use");
                if (fn != null && fn.type() == party.iroiro.luajava.Lua.LuaType.FUNCTION) {
                    Object out = scriptHost.callMap(fn, com.nineyin.script.ItemScriptBridge.useContext(
                            item.configId(), item.amount(), now,
                            actor.hp, actor.maxHp(), actor.mp, actor.maxMp(), actor.silver,
                            dropRand.getAsDouble(), dropRand.getAsDouble()));
                    instr = com.nineyin.script.ItemScriptBridge.parseInstructions(item.configId(), out);
                }
            }
        }
        // ② INI 回退：礼包开包 / 使用技能回血
        List<com.nineyin.script.ItemScriptBridge.GrantItem> grants = null;
        int healHp = instr != null ? instr.healHp() : 0;
        int healMp = instr != null ? instr.healMp() : 0;
        var tempStat = instr != null ? instr.tempStat() : null;
        if (instr != null) {
            if (instr.openBox() != null) {
                grants = dropPacks.roll(instr.openBox().dropId(), instr.openBox().rolls(), dropRand);
            } else if (instr.grantItems() != null) {
                grants = instr.grantItems();
            }
        } else {
            int itemType = inventory.itemTypeOf(item.configId());
            var entry = itemUse.resolve(item.configId(), itemType);
            if (entry == null) {
                log.info("item use {}: no script and no INI entry (item_type={})",
                        item.configId(), itemType);
                return;
            }
            switch (entry.kind()) {
                case BOX -> {
                    grants = dropPacks.roll(entry.dropId(), 1, dropRand);
                    if (grants.isEmpty()) {
                        log.info("item use {}: drop pack {} empty/missing", item.configId(),
                                entry.dropId());
                        return;
                    }
                }
                case USE_SKILL -> {
                    healHp = ItemUseCatalog.fallbackHealHp(entry.skillLevel());
                    healMp = ItemUseCatalog.fallbackHealMp(entry.skillLevel());
                }
            }
        }
        if (healHp <= 0 && healMp <= 0 && grants == null && tempStat == null) {
            log.info("item use {}: instructions empty", item.configId());
            return;
        }
        st.lastItemUseMs = now;
        // ③ 效果落账：给物品（容量感知，满则中断）
        List<String> grantedIds = new ArrayList<>();
        if (grants != null) {
            for (var g : grants) {
                String fail = giveItem(fx, session, g.configId(), g.amount());
                if (fail != null) {
                    log.info("item use {}: grant {} failed: {}", item.configId(), g.configId(), fail);
                    break;
                }
                grantedIds.add(g.configId() + "x" + g.amount());
            }
        }
        // ④ 回血/回蓝 + 限时属性
        boolean vitalChanged = false;
        if (healHp > 0 && actor.hp < actor.maxHp()) {
            actor.hp = Math.min(actor.maxHp(), actor.hp + healHp);
            vitalChanged = true;
        }
        if (healMp > 0 && actor.mp < actor.maxMp()) {
            actor.mp = Math.min(actor.maxMp(), actor.mp + healMp);
            vitalChanged = true;
        }
        if (tempStat != null) {
            vitalChanged |= actor.applyTempStat(item.configId(), tempStat.attackAdd(),
                    tempStat.defenseAdd(), tempStat.maxHpAdd(), tempStat.lifeMs(), now);
        }
        if (vitalChanged) {
            fx.send(actor.vitalUpdate());
        }
        // ⑤ 消耗 1 个（脚本 consume=false 则跳过；如二次使用卸下类）+ 背包帧对
        boolean consume = instr == null || instr.consume();
        if (consume) {
            int remaining = inventory.consumeBagItem(session.roleId, item.slot(), item.viewId(), 1);
            if (remaining >= 0) {
                fx.send(InventoryPackets.viewRemove(view, item.slot()));
                if (remaining > 0) {
                    var left = new com.nineyin.db.RoleInventoryStore.Bagged(
                            item.slot(), item.configId(), item.itemType(), remaining,
                            item.viewId(), "");
                    fx.send(InventoryPackets.bagViewAdd(left,
                            InventoryPackets.uniqueId(session.roleId, "bag", item.slot()), view));
                    session.liveBagged.remove(item);
                    session.liveBagged.add(left);
                } else {
                    session.liveBagged.remove(item);
                }
                int mirror = InventoryPackets.mirrorOf(view);
                if (mirror != 0) {
                    fx.send(InventoryPackets.viewRemove(mirror, item.slot()));
                }
            } else {
                log.info("item use {}: consume failed (row missing/changed)", item.configId());
            }
        }
        log.info("item use {} consumed={} healHp={} healMp={} granted={} tempStat={}",
                item.configId(), consume, healHp, healMp, grantedIds,
                tempStat != null ? "atk+" + tempStat.attackAdd() + "/" + (tempStat.lifeMs() / 1000)
                        + "s" : "none");
    }

    public String giveItem(FxSession fx, LoginSession session, String configId, int amount) {
        if (inventory == null || session.actor == null || session.liveBagged == null) {
            return "库存系统未接线";
        }
        if (configId == null || configId.isBlank()) {
            return "itemConfig 不能为空";
        }
        if (amount < 1 || amount > 999) {
            return "amount 需在 1..999";
        }
        var add = inventory.addBagItem(session.roleId, configId, amount);
        if (add.fail() != null) {
            return "missing".equals(add.fail())
                    ? "game_items 无此配置: " + configId
                    : "背包已满（" + add.fail() + "）";
        }
        int view = InventoryPackets.bagViewOf(add.view());
        var item = new com.nineyin.db.RoleInventoryStore.Bagged(
                add.slot(), configId, add.itemType(), amount, add.view(), "");
        List<byte[]> out = new ArrayList<>();
        out.add(InventoryPackets.viewRemove(view, add.slot()));
        out.add(InventoryPackets.bagViewAdd(
                item, InventoryPackets.uniqueId(session.roleId, "bag", add.slot()), view));
        int mirror = InventoryPackets.mirrorOf(view);
        if (mirror != 0) {
            out.add(InventoryPackets.viewRemove(mirror, add.slot()));
        }
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.liveBagged.add(item);
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                session.roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
        log.info("GM give item {} x{} role={} -> view={} slot={}",
                configId, amount, session.roleId, view, add.slot());
        return null;
    }

    public void sendTestMail(FxSession fx, LoginSession session, String configId, int amount) {
        MailStore.Mail mail = mailStore.sendTestMail(session.roleId, configId, amount);
        int row = mailStore.count(session.roleId) - 1;
        fx.send(mailRowFrame(row, mail));
        log.info("GM send_test_mail config={} amount={} rows={}", configId, amount, row + 1);
    }

    /** 961 骑乘技能（参考服：`ride skill (not dismount) id=%s mounted=%v`；坐骑状态未接）。 */
    private void handleRideSkillUse(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val id = msg.val(1);
        String skillId = id != null && id.str != null ? id.str : "";
        log.info("ride skill (not dismount) id={} mounted=false (mount system not implemented)",
                skillId.isEmpty() ? "<missing>" : skillId);
    }

    /** 249 御风蓄力完成确认（参考服在蓄力窗口内等 249；Java 无蓄力窗口，收下即记日志）。 */
    private void handleYufengChargeConfirm(FxSession fx, LoginSession session) {
        log.info("yufeng charge confirm 249 ignored (no charge window active)");
    }

    /** 144 副本结算确认（参考服推 col result 后等待 144；Java 无待确认结算）。 */
    private void handleColResultConfirm(FxSession fx, LoginSession session) {
        log.info("clone col result confirm 144 ignored (no pending col result)");
    }

    /**
     * 180 卡片（参考服按 sub_command 路由 USE/USESKILL/SAVE；子编号未从字符串钉死，
     * 未钉死前一律走参考服的未知子命令分支，日志同构便于实机对照）。
     */
    private void handleCard(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        if (sub == null || !sub.asExactInt()) {
            log.info("card C2S: malformed sub values={}", summarizeVals(msg));
            return;
        }
        log.info("card C2S: unknown sub_command={} args=[{}] (sub routing pending capture)",
                sub.intOrZero(), summarizeVals(msg));
    }

    /** 209 任务面板（参考服：无活跃任务直接忽略；Java 无活跃任务状态）。 */
    private void handleTaskPanel(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("task 209 ignored: no active task (vals={})", summarizeVals(msg));
    }

    /** 270 暮色道具技能（参考服：`twilight item skill %s has no active scene`）。 */
    private void handleTwilightItemSkill(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val id = msg.val(1);
        String skillId = id != null && id.str != null ? id.str : "";
        if (skillId.isEmpty()) {
            log.info("twilight item skill malformed values={}", summarizeVals(msg));
            return;
        }
        log.info("twilight item skill {} has no active scene", skillId);
    }

    /** 382 关闭掉落箱（参考服：`closed DropBox via 382`；Java 无 DropBox 实体）。 */
    private void handleCloseDrop(FxSession fx, LoginSession session) {
        log.info("close drop 382 ignored (no open DropBox)");
    }

    /**
     * 943 兵录修改（参考服：`binglu modify: missing sub message` / `sub message is not an
     * integer` / `binglu modify unhandled sub=%d name=%q num=%d`；binglu 数据表未接入，
     * 全部子命令走 unhandled 分支）。
     */
    private void handleBingluModify(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        if (sub == null) {
            log.info("binglu modify: missing sub message");
            return;
        }
        if (!sub.asExactInt()) {
            log.info("binglu modify: sub message is not an integer");
            return;
        }
        CustomMsg.Val name = msg.val(2);
        CustomMsg.Val num = msg.val(3);
        log.info("binglu modify unhandled sub={} name={} num={}",
                sub.intOrZero(),
                name != null && name.str != null ? "\"" + name.str + "\"" : "\"\"",
                num != null ? num.intOrZero() : 0);
    }

    /**
     * 784 乱斗（参考服：`luandou 784: missing sub_msg args=%d` / `bad sub_msg type %s` /
     * `sub=10 OPEN_LIMITE index=%d origin_id=%d` / `sub=%d ignored (not PlayerIsInCross)`；
     * 回复帧 `luandou 784: encode reply` 的格式未钉死）。
     */
    private void handleLuanDou(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.size() < 2) {
            log.info("luandou 784: missing sub_msg args={}", msg.size());
            return;
        }
        CustomMsg.Val sub = msg.val(1);
        if (!sub.asExactInt()) {
            log.info("luandou 784: bad sub_msg type {}", sub);
            return;
        }
        int subId = sub.intOrZero();
        if (subId == 10) {
            int index = intArg(msg, 2);
            int originId = intArg(msg, 3);
            log.info("luandou 784 sub=10 OPEN_LIMITE index={} origin_id={} (reply pending capture)",
                    index, originId);
            return;
        }
        log.info("luandou 784 sub={} ignored (not PlayerIsInCross)", subId);
    }

    /**
     * 865 踢馆（sub 6=确认切换 / 7=离场，其余 `tiguan 865 bad sub_msg %s`）。
     * 完整线序见 artifacts/audit/tiguan-group-scene-lifecycle-20260808.md：
     * 6 → ID24/87 淡入淡出 → CurGuanID → 0x0C → 750ms → 0x0B（同地图保持坐标）→
     * PlayerAddObject → SubSceneNo → 0x11 target=2 Time_Limit_Form_Rec → 等 ClientReady；
     * 7 → 删计时行 → ID24/87 → CurGuanID=0 → 0x0C → 0x0B（公共场景 TransOut）。
     * 场景分组生命周期未实装，先对齐子命令校验与日志。
     */
    private void handleTiguan(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        if (sub == null || !sub.asExactInt() || (sub.intOrZero() != 6 && sub.intOrZero() != 7)) {
            log.info("tiguan 865 bad sub_msg {}", sub != null ? sub : "<missing>");
            return;
        }
        log.info("tiguan 865 sub={} (grouped scene lifecycle pending; see audit/tiguan)",
                sub.intOrZero());
    }

    /** 868 踢馆轮盘（参考服：`tiguan roulette 868 missing action` / `bad action %s`；
     *  合法 action 词表未钉死，Java 无踢馆会话，先记日志）。 */
    private void handleTiguanRoulette(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val action = msg.val(1);
        if (action == null || action.str == null || action.str.isEmpty()) {
            log.info("tiguan roulette 868 missing action");
            return;
        }
        log.info("tiguan roulette 868 action={} (no tiguan session; whitelist pending capture)",
                action.str);
    }

    /** 517 GM 探针默认 msgId（参考服：`GM custom probe msgID=%d subType=%d`）。 */
    private void handleGmProbe(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        log.info("GM custom probe msgID={} subType={}", msg.msgId(),
                sub != null ? sub.intOrZero() : 0);
    }

    // ---- 2026-10-30 custom 第四轮：编号钉死批（校验层 + 日志对齐） ----

    /**
     * 215 运功切换（反汇编：handlePlayerProgressCustom 的 USE_NEIGONG 分支）。
     * 无 DB 修炼状态（actor.faculty==null）维持历史拒绝日志；有则按已学表校验：
     * 未学习仅记日志（对齐 Go `not learned neigong %q`，不断连），成功切当前内功并同步面板。
     */
    private void handlePlayerProgress(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            log.info("player progress request before scene player exists");
            return;
        }
        CustomMsg.Val id = msg.val(1);
        boolean malformed = id == null || id.str == null || id.str.isEmpty();
        if (session.actor.faculty == null) {
            log.info("215 requires a learned neigong ConfigID string ({} vals={})",
                    malformed ? "malformed" : "id=" + id.str + " unlearned; neigong table pending",
                    summarizeVals(msg));
            return;
        }
        if (malformed) {
            log.info("215 requires a learned neigong ConfigID string (malformed vals={})",
                    summarizeVals(msg));
            return;
        }
        FacultyState faculty = session.actor.faculty;
        if (!faculty.equip(id.str)) {
            log.info("not learned neigong \"{}\" (role={} vals={})", id.str, session.roleId,
                    summarizeVals(msg));
            return;
        }
        fx.send(session.actor.vitalUpdate());
        persistFacultyNow(fx, session);
        log.info("equip neigong \"{}\" role={}", id.str, session.roleId);
    }

    /**
     * 1006 修炼面板子命令（Go handlePlayerProgressCustom 同名分支移植）：
     * 1=选定修炼书 11=内修开始 12=内修退出 21=演武开始 22=演武退出
     * 23=选卡结算（卡号 0..2 + 支付位 1/2/4） 24=面板刷新 31/32=经脉上报。
     * faculty==null（无 DB 行）按未实装记日志，不影响连接。
     */
    private void handleFacultyInfo(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            log.info("player progress request before scene player exists");
            return;
        }
        CustomMsg.Val sub = msg.val(1);
        if (sub == null || !sub.asExactInt()) {
            log.info("1006 requires an int subcommand vals={}", summarizeVals(msg));
            return;
        }
        FacultyState faculty = session.actor.faculty;
        if (faculty == null) {
            log.info("faculty 1006 sub={} ignored (no db faculty snapshot; vals={})",
                    sub.intOrZero(), summarizeVals(msg));
            return;
        }
        switch (sub.intOrZero()) {
            case 1 -> {   // SUB_CLIENT_CONVERT_READY
                CustomMsg.Val id = msg.val(2);
                if (id == null || id.str == null || id.str.isEmpty()) {
                    log.info("1006 sub=1 requires a learned neigong ConfigID string vals={}",
                            summarizeVals(msg));
                    return;
                }
                int slot = faculty.facultyReady(id.str);
                if (slot == 0) {
                    // View43 换面板期可能带来非内功 ConfigID：非断连错误（Go 同款）
                    log.info("not learned neigong \"{}\" (1006 sub=1 role={})", id.str, session.roleId);
                    return;
                }
                // FacultyName 到客户端前先刷 View43 对象：set_control_state 立即解析等级（Go 同序）
                fx.send(facultyBookFrame(slot, faculty.book(id.str)));
                fx.send(session.actor.vitalUpdate());
                persistFacultyNow(fx, session);
            }
            case 11 -> {  // SUB_CLIENT_NORMAL_BEGIN
                if (faculty.facultyBegin(FacultyState.STYLE_NORMAL, java.time.Instant.now()) == 0) {
                    log.info("no selected learned neigong (1006 sub=11 role={})", session.roleId);
                    return;
                }
                session.actor.logicState = 0;   // 内修是后台转换，不进 LS_FACULTY（Go 同款）
                fx.send(session.actor.vitalUpdate());
                persistFacultyNow(fx, session);
            }
            case 21 -> {  // SUB_CLIENT_ACT_BEGIN
                if (faculty.facultyBegin(FacultyState.STYLE_ACT, java.time.Instant.now()) == 0) {
                    log.info("no selected learned neigong (1006 sub=21 role={})", session.roleId);
                    return;
                }
                session.actor.logicState = 108; // LS_FACULTY：演武独占的可见动作态
                fx.send(session.actor.vitalUpdate());
                persistFacultyNow(fx, session);
            }
            case 12, 22 -> {  // NORMAL_EXIT / ACT_EXIT
                faculty.facultyExit();
                session.actor.logicState = 0;
                fx.send(session.actor.vitalUpdate());
                persistFacultyNow(fx, session);
            }
            case 23 -> {  // SUB_CLIENT_ACT_PAY：卡号 + 支付方式（枚举值，double 容错取整）
                if (msg.size() < 4) {
                    return;
                }
                CustomMsg.Val card = msg.val(2);
                CustomMsg.Val payment = msg.val(3);
                if (!card.asExactInt() || !payment.asExactInt()) {
                    return;
                }
                int cardIdx = card.intOrZero();
                int pay = payment.intOrZero();
                if (cardIdx < 0 || cardIdx > 2 || (pay != 1 && pay != 2 && pay != 4)) {
                    return;
                }
                FacultyState.ActiveAward award = faculty.awardActiveFaculty(levelCurves);
                if (award.slot() == 0) {
                    return;   // 过期残留 PAY（面板已关/已切模式），静默吞
                }
                // 面板从 View43 对象读等级/修炼度：先发对象再发面板态（Go 同序）
                if (award.awarded()) {
                    fx.send(facultyBookFrame(award.slot(),
                            faculty.books.get(award.slot() - 1)));
                }
                fx.send(session.actor.vitalUpdate());
                if (award.levelUp()) {
                    // faculty.lua 的 sub=14 → on_msg_level_up() 读 CurLevel 刷新面板/通知
                    fx.send(CustomPackets.facultyMessage(14, award.maxLevel() ? 1 : 0));
                }
                if (award.levelUp() || award.maxLevel()) {
                    // 等级已全量同步到客户端后才关闭演武页（exit_flag 抑制残留 1006/22）
                    fx.send(CustomPackets.facultyMessage(23, 0));   // SUB_SERVER_ACT_EXIT
                    persistFacultyNow(fx, session);
                    return;
                }
                fx.send(CustomPackets.facultyMessage(24, 0));      // SUB_SERVER_ACT_PLAY
                persistFacultyNow(fx, session);
            }
            case 24 -> {  // 面板重开刷新：先脉冲清 FacultyName 再恢复真值触发 set_control_state
                if (!faculty.facultyName.isEmpty()) {
                    fx.send(S2CPackets.objectPropertyString(PropertyId.FACULTY_NAME, ""));
                }
                fx.send(session.actor.vitalUpdate());
            }
            case 31, 32 ->   // 经脉流上报/打开：深业务不实装，接受保持 1006 总线规整（Go 同款）
                    fx.send(session.actor.vitalUpdate());
            default -> log.info("unsupported 1006 subcommand {}", sub.intOrZero());
        }
    }

    /** View 43 单本行刷新（0x18，9 属性布局与登录种子一致；槽位从 1 起）。 */
    private static byte[] facultyBookFrame(int slot, FacultyState.Book book) {
        return SkillPackets.viewAddHeader(SkillPackets.VIEW_NEIGONG, slot, 9)
                .u16(SkillPackets.V_CONFIG_ID).string(book.configId)
                .u16(PropertyId.STATIC_DATA).i32(book.staticData)
                .u16(PropertyId.ITEM_TYPE).i32(book.itemType)
                .u16(PropertyId.LEVEL).u8(book.level)
                .u16(PropertyId.MAX_LEVEL).i32(book.maxLevel)
                .u16(PropertyId.CUR_FILL_VALUE).i32(book.fill)
                .u16(PropertyId.TOTAL_FILL_VALUE).i32(book.total)
                .u16(PropertyId.POWER_VALUE).i32(book.power)
                .u16(PropertyId.MAX_POWER_VALUE).i32(book.maxPower)
                .toByteArray();
    }

    /** 立即落库（切换/状态变化路径；失败保留脏标记由周期冲刷重试）。 */
    private void persistFacultyNow(FxSession fx, LoginSession session) {
        if (facultyStore == null || session.actor == null || session.actor.faculty == null) {
            return;
        }
        if (facultyStore.save(session.roleId, session.actor.faculty)) {
            SceneState st = fx.channel().attr(STATE).get();
            if (st != null) {
                st.lastFacultyFlushMs = System.currentTimeMillis();
            }
        }
    }

    /** 周期冲刷（tick 内调用）：脏快照超过间隔才写，避免纯 fill 累积每秒打库。 */
    private void flushFacultyDirty(FxSession fx, LoginSession session, long now) {
        if (facultyStore == null || session.actor == null || session.actor.faculty == null
                || !session.actor.faculty.persistDirty) {
            return;
        }
        SceneState st = fx.channel().attr(STATE).get();
        if (st == null || now - st.lastFacultyFlushMs < FACULTY_FLUSH_MS) {
            return;
        }
        if (facultyStore.save(session.roleId, session.actor.faculty)) {
            st.lastFacultyFlushMs = now;
            log.info("settled inner cultivation flushed role={} slotBook={}", session.roleId,
                    session.actor.faculty.facultyName);
        }
    }

    /**
     * 19 使用物品广义链（第八轮反汇编链追踪钉死）：FashionSuit → YufengItemUse →
     * GiftBoxItemUse → EquipItemUseCustom（右键穿）→ WuJiBookUse 链式认领——
     * 各 handler 返回 bool，未认领落到下一个（无独立 msgId，`case 19` 一个块全包）。
     * Java 无各物品 store：头部校验对齐后按链序记日志。
     */
    private void handleFashionSuit(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val sub = msg.val(1);
        if (sub == null) {
            log.info("fashion_suit has no sub-message");
            return;
        }
        if (sub.asExactInt()) {
            log.info("fashion_suit unhandled sub={} args=[{}] (wear/buy routing pending capture)",
                    sub.intOrZero(), summarizeVals(msg));
            return;
        }
        log.info("fashion_suit invalid sub-message {}", sub);
        // 链后续段（御风道具/礼盒/右键穿/无极书）均未实装；右键穿的拒绝文本对齐：
        // `%s: right-click equip %s reject: %v`（无装备上下文时按 reject 分支记录）
        log.info("use-item chain (19) remainder unclaimed: yufeng/giftbox/right-click-equip/wuji "
                + "(stores not implemented; vals={})", summarizeVals(msg));
    }

    /**
     * 301 VIP（参考服头部校验：`VIP message 301 has no command` /
     * `command is not an exact integer: %s`）。价格查询与购买链的 cmd 编号、
     * 价格表（`month count %d is outside 1..36`、`gold balance %d is below VIP cost %d`、
     * `completed golden VIP purchase type=%d months=%d cost=%d gold=%d ...`）待钉死。
     */
    private void handleVip(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val cmd = msg.val(1);
        if (cmd == null) {
            log.info("VIP message 301 has no command");
            return;
        }
        if (!cmd.asExactInt()) {
            log.info("VIP message 301 command is not an exact integer: {}", cmd);
            return;
        }
        log.info("unhandled VIP command {} (price table pending capture)", cmd.intOrZero());
    }

    /**
     * 688 珍宝阁（参考服：`tersure 688 has non-int args: %v` /
     * `unhandled tersure 688 sub_type=%d`；重铸/修复/洗练各 sub 的结果帧未钉死）。
     */
    private void handleTersure(FxSession fx, LoginSession session, CustomMsg msg) {
        for (int i = 1; i < msg.size(); i++) {
            if (!msg.val(i).asExactInt()) {
                log.info("tersure 688 has non-int args: {}", summarizeVals(msg));
                return;
            }
        }
        int subType = msg.size() >= 2 ? msg.val(1).intOrZero() : 0;
        log.info("unhandled tersure 688 sub_type={}", subType);
    }

    /** 692 面料混合（参考服：`attire blend 692 missing args args=[%s]`；attire store 未实装）。 */
    private void handleAttireBlend(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.size() < 2) {
            log.info("attire blend 692 missing args args=[{}]", summarizeVals(msg));
            return;
        }
        log.info("attire blend 692 args=[{}] (attire store not implemented)", summarizeVals(msg));
    }

    /**
     * origin 家族（反汇编钉死归属：700=Active / 701=Get / 702=GetPrize / 705=SetShow /
     * 706=RequireNotify；校验串 `origin 700: missing origin id args=%d` / `bad origin id
     * type %s`）。出身目录 `origin: loaded %d origins from %s` 的数据文件未接入，带 id 亦先记 pending。
     */
    private void handleOrigin(FxSession fx, LoginSession session, CustomMsg msg) {
        int mid = msg.msgId();
        CustomMsg.Val id = msg.val(1);
        if (id == null) {
            log.info("origin {}: missing origin id args={}", mid, msg.size());
            return;
        }
        if (!id.asExactInt()) {
            log.info("origin {}: bad origin id type {}", mid, id);
            return;
        }
        log.info("origin {} id={} (origin catalog not implemented)", mid, id.intOrZero());
    }

    /** 713 礼盒领取（参考服：`gift box catalog unavailable: %v` / `gift box %s claimed prizes=%d`）。 */
    private void handleGiftBoxClaim(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("gift box claim 713 ignored (gift box catalog not implemented; vals={})",
                summarizeVals(msg));
    }

    /** 770 C 面板体型（参考服：`handle C-panel BodyScale 770`；customizing 上传/恢复未实装）。 */
    private void handleBodyScale(FxSession fx, LoginSession session, CustomMsg msg) {
        log.info("handle C-panel BodyScale 770 vals={} (customizing store not implemented)",
                summarizeVals(msg));
    }

    /**
     * 864 生活职业（参考服：`unknown life job %q` / `life job %s already learned;
     * republish job_rec`；职业表与 job_rec 记录表未接入，任何职业名均按未知拒绝）。
     */
    private void handleLifeJob(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val job = msg.val(1);
        if (job == null || job.str == null || job.str.isEmpty()) {
            log.info("life job 864 malformed vals={}", summarizeVals(msg));
            return;
        }
        log.info("unknown life job \"{}\" (life job catalog not implemented)", job.str);
    }

    /**
     * 380 掉落拾取（参考服：`reject 380 pickup without vpos` / `reject 380 bad vpos %s`；
     * vpos 假定为首参整数（成功日志 `pickup 380 vpos=%d ...`），Java 无掉落实体，校验后记 pending）。
     */
    private void handlePickup(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val vpos = msg.val(1);
        if (vpos == null) {
            log.info("reject 380 pickup without vpos");
            return;
        }
        if (!vpos.asExactInt()) {
            log.info("reject 380 bad vpos {}", vpos);
            return;
        }
        log.info("pickup 380 vpos={} ignored (no drop entities)", vpos.intOrZero());
    }

    /** 参数摘要（单值 48 字符、总长 160 截断）：统一走 TVarList.describe，与校验错误文本同格式。 */
    private static String summarizeVals(CustomMsg msg) {
        return TVarList.describe(msg);
    }

    /** 打坐：cmd 1=坐下(102) 0/2=起身(0)；权威状态在 actor，并按 tick 恢复血蓝。 */
    private void handleSit(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        TVarList.Result r = TVarList.check(msg, 240, TVarList.intArg("start_or_stop"));
        if (!r.ok()) {
            log.info("sitcross expects {}, got {}", r.expected(), r.got());
            return;
        }
        int next = switch (msg.val(1).intOrZero()) {
            case 1 -> PlayerActor.LOGIC_STATE_SITCROSS;
            case 0, 2 -> PlayerActor.LOGIC_STATE_NORMAL;
            default -> session.actor.logicState;
        };
        if (next == session.actor.logicState) {
            return;
        }
        session.actor.logicState = next;
        fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, next));
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, next == PlayerActor.LOGIC_STATE_SITCROSS ? "sitdown" : "stand"));
        log.info("sitcross LogicState -> {} (cmd={})", next, msg.val(1).intOrZero());
    }

    /** 200 设快捷键：idx, kind(str 或 int→skill), id；同 index 替换（先 0x12 删旧行）。 */
    /**
     * msgId=30 移动物品（参考服日志钉死语义）：args=[fromView, fromSlot, toView, toSlot]。
     * 卸下=[1,槽位,121,0]（0=自动选格）；穿上=[121,格位,1,身体槽位]；背包页间移动同构。
     * 帧：源/目标按 0x19 ViewRemove 清格（页与镜像成对），0x18 ViewAdd 落位；
     * 库内同步移动并重建 inventoryFrames 缓存（0x0C 重进场景用）。
     */
    private void handleMoveItem(FxSession fx, LoginSession session, CustomMsg msg) {
        if (msg.size() < 5 || session.roleId == 0 || session.liveBagged == null
                || session.liveEquipped == null || inventory == null) {
            log.info("move 30 ignored: no live inventory role={} argc={}",
                    session.roleId, msg.size());
            return;
        }
        int fromView = intArg(msg, 1);
        int fromSlot = intArg(msg, 2);
        int toView = intArg(msg, 3);
        int toSlot = intArg(msg, 4);
        long roleId = session.roleId;
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        if (fromView == InventoryPackets.VIEW_EQUIP_DOLL) {
            // 卸下：装备栏 → 背包装备页
            com.nineyin.db.RoleInventoryStore.Equipped item = removeEquipped(session, fromSlot);
            if (item == null) {
                log.info("move 30 equip[{}] empty", fromSlot);
                return;
            }
            int view = bagWireToCategory(toView) != 0 ? toView : InventoryPackets.VIEW_EQUIP_BAG;
            int slot = toSlot > 0 ? toSlot : freeBagSlot(session.liveBagged, view);
            out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_DOLL, fromSlot));
            out.add(InventoryPackets.viewRemove(view, slot));
            out.add(InventoryPackets.bagViewAdd(
                    new com.nineyin.db.RoleInventoryStore.Bagged(
                            slot, item.configId(), item.itemType(), 1,
                            bagWireToCategory(view), ""),
                    InventoryPackets.uniqueId(roleId, "bag", slot), view));
            int mirror = InventoryPackets.mirrorOf(view);
            if (mirror != 0) {
                out.add(InventoryPackets.viewRemove(mirror, slot));
            }
            session.liveBagged.add(new com.nineyin.db.RoleInventoryStore.Bagged(
                    slot, item.configId(), item.itemType(), 1, bagWireToCategory(view), ""));
            if (!inventory.moveEquipToBag(roleId, fromSlot, slot)) {
                log.warn("move 30 equip[{}]->bag[{}] persist failed", fromSlot, slot);
            }
            log.info("move 30 equip[{}]->bag[{}] config={}", fromSlot, slot, item.configId());
        } else if (toView == InventoryPackets.VIEW_EQUIP_DOLL) {
            // 穿上：背包页 → 装备栏（目标槽被占时旧装备让位到空闲背包格）
            int category = bagWireToCategory(fromView);
            com.nineyin.db.RoleInventoryStore.Bagged item = removeBagged(session, fromSlot, category);
            if (item == null) {
                log.info("move 30 bag[{}:{}] empty", fromView, fromSlot);
                return;
            }
            com.nineyin.db.RoleInventoryStore.Equipped old = removeEquipped(session, toSlot);
            int fromMirror = InventoryPackets.mirrorOf(fromView);
            out.add(InventoryPackets.viewRemove(fromView, fromSlot));
            if (fromMirror != 0) {
                out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
            }
            if (old != null) {
                int oldSlot = freeBagSlot(session.liveBagged, InventoryPackets.VIEW_EQUIP_BAG);
                out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_BAG, oldSlot));
                out.add(InventoryPackets.bagViewAdd(
                        new com.nineyin.db.RoleInventoryStore.Bagged(
                                oldSlot, old.configId(), old.itemType(), 1, 2, ""),
                        InventoryPackets.uniqueId(roleId, "bag", oldSlot),
                        InventoryPackets.VIEW_EQUIP_BAG));
                int m = InventoryPackets.mirrorOf(InventoryPackets.VIEW_EQUIP_BAG);
                if (m != 0) {
                    out.add(InventoryPackets.viewRemove(m, oldSlot));
                }
                session.liveBagged.add(new com.nineyin.db.RoleInventoryStore.Bagged(
                        oldSlot, old.configId(), old.itemType(), 1, 2, ""));
                if (!inventory.swapEquipSlot(roleId, toSlot, oldSlot)) {
                    log.warn("move 30 swap equip[{}]->bag[{}] persist failed", toSlot, oldSlot);
                }
            }
            out.add(InventoryPackets.viewRemove(InventoryPackets.VIEW_EQUIP_DOLL, toSlot));
            com.nineyin.db.RoleInventoryStore.Equipped worn = new com.nineyin.db.RoleInventoryStore.Equipped(
                    toSlot, item.configId(), item.itemType(),
                    equipTypeOf(item.configId()), 100, 100, "");
            out.add(InventoryPackets.equipmentViewAdd(
                    worn, InventoryPackets.uniqueId(roleId, "equip", toSlot)));
            session.liveEquipped.add(worn);
            if (!inventory.moveBagToEquip(roleId, fromSlot, toSlot, category)) {
                log.warn("move 30 bag[{}]->equip[{}] persist failed", fromSlot, toSlot);
            }
            log.info("move 30 bag[{}:{}]->equip[{}] config={}{}",
                    fromView, fromSlot, toSlot, item.configId(),
                    old != null ? " (swapped out " + old.configId() + ")" : "");
        } else if (InventoryPackets.pageOfExtView(toView) != 0) {
            // 目标是扩展页（view 3/122/124/126）：扩容包装备/续期，或普通物品入扩展格
            handleMoveToExtension(out, session, fromView, fromSlot, toView, toSlot, roleId);
            if (out.isEmpty()) {
                return; // 拒绝路径：无帧、不动缓存
            }
        } else if (InventoryPackets.pageOfExtView(fromView) != 0) {
            // 拖出扩展页 = 取下锚点：不支持（对齐 F 参考消耗品语义，到期自动失效）
            log.info("move 30 from ext view={} pos={} rejected (bagpack cannot be unequipped)",
                    fromView, fromSlot);
            return;
        } else {
            // 背包页间/页内移动
            int category = bagWireToCategory(fromView);
            com.nineyin.db.RoleInventoryStore.Bagged item = removeBagged(session, fromSlot, category);
            if (item == null) {
                log.info("move 30 bag[{}:{}] empty", fromView, fromSlot);
                return;
            }
            int view = toView != 0 ? toView : InventoryPackets.VIEW_TOOL_PAGE;
            int slot = toSlot > 0 ? toSlot : freeBagSlot(session.liveBagged, view);
            int fromMirror = InventoryPackets.mirrorOf(fromView);
            out.add(InventoryPackets.viewRemove(fromView, fromSlot));
            if (fromMirror != 0) {
                out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
            }
            out.add(InventoryPackets.viewRemove(view, slot));
            com.nineyin.db.RoleInventoryStore.Bagged moved =
                    new com.nineyin.db.RoleInventoryStore.Bagged(
                            slot, item.configId(), item.itemType(), item.amount(),
                            bagWireToCategory(view), "");
            out.add(InventoryPackets.bagViewAdd(
                    moved, InventoryPackets.uniqueId(roleId, "bag", slot), view));
            int mirror = InventoryPackets.mirrorOf(view);
            if (mirror != 0) {
                out.add(InventoryPackets.viewRemove(mirror, slot));
            }
            session.liveBagged.add(moved);
            if (!inventory.moveBagSlot(roleId, fromSlot, category, slot, bagWireToCategory(view))) {
                log.warn("move 30 bag[{}]->bag[{}] persist failed", fromSlot, slot);
            }
            log.info("move 30 bag[{}:{}]{}->bag[{}:{}] config={}",
                    fromView, fromSlot, category, view, slot, item.configId());
        }
        // 穿/脱触及装备纸娃娃时重推武器模型（对齐参考服 pushGearVisual：WeaponMode=1597 随 0x10 增量）
        if (fromView == InventoryPackets.VIEW_EQUIP_DOLL || toView == InventoryPackets.VIEW_EQUIP_DOLL) {
            out.add(InventoryPackets.weaponModeFrame(session.liveEquipped,
                    Appearance.resolveVisual(session.appearance).sex()));
            // 装备词缀加成重算（对齐 recomputeCombat 的 equip*Add 层）；有效上限变化时
            // 推 MaxHP/MaxMP 增量 + vital 同步
            if (session.actor != null) {
                int maxHpBefore = session.actor.maxHp();
                int maxMpBefore = session.actor.maxMp();
                session.actor.recomputeEquipBonuses(propPacks, session.liveEquipped);
                session.actor.applyEquipEffects(equipEffects, propPacks, equipEffectPackIds(session));
                if (session.actor.maxHp() != maxHpBefore || session.actor.maxMp() != maxMpBefore) {
                    out.add(S2CPackets.playerBatch()
                            .i32(PropertyId.MAX_HP, session.actor.maxHp())
                            .i32(PropertyId.MAX_MP, session.actor.maxMp()).build());
                    out.add(session.actor.vitalUpdate());
                    log.info("equip bonuses reapplied maxHp={} maxMp={} attack+={} defense+={} accuracy+={} evasion+={}",
                            session.actor.maxHp(), session.actor.maxMp(),
                            session.actor.equipPhyVa + session.actor.equipMeleePowerAdd,
                            session.actor.equipPhyDef,
                            session.actor.equipDexAdd + session.actor.equipPhyHit,
                            session.actor.equipDodge);
                }
            }
        }
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.inventoryFrames = InventoryPackets.inventoryFrames(
                roleId, session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
    }

    /**
     * msg30 目标为扩展页（F 参考服 2026-10-31 抓包语义）：
     * - script=Bag 物品 → BAGPACK 装备（消耗物品、下一空闲锚点、expires=now+目录时长）；
     *   同页同 config 已装备 → 续期（expires += 时长）；回应 = 锚点帧 + 主页整页重推
     * - 普通物品 → addtoolbox：无锚点/格位超容量拒绝（对齐 "MOVEITEM addtoolbox anchor not found"）
     */
    private void handleMoveToExtension(List<byte[]> out, LoginSession session,
                                       int fromView, int fromSlot, int toView, int toSlot,
                                       long roleId) {
        int page = InventoryPackets.pageOfExtView(toView);
        int fromCategory = bagWireToCategory(fromView);
        com.nineyin.db.RoleInventoryStore.Bagged peek = null;
        for (com.nineyin.db.RoleInventoryStore.Bagged b : session.liveBagged) {
            if (b.slot() == fromSlot && (fromCategory == 0 || b.viewId() == fromCategory)) {
                peek = b;
                break;
            }
        }
        if (peek == null) {
            log.info("move 30 bag[{}:{}] empty", fromView, fromSlot);
            return;
        }
        List<com.nineyin.db.RoleInventoryStore.Bagpack> packs = new ArrayList<>(
                session.liveBagpacks != null ? session.liveBagpacks : List.of());
        BagCatalog.BagItem bag = BagCatalog.bagOf(peek.configId());
        if (bag != null && bag.page() == page) {
            equipOrRenewBagpack(out, session, packs, page, peek, bag, fromView, fromSlot, roleId);
        } else {
            moveToToolboxSlot(out, session, packs, page, peek, fromView, fromSlot,
                    fromCategory, toSlot, roleId);
        }
    }

    /** 扩容包装备/续期（先 DB 后消耗物品；无空闲锚点拒绝不动状态）。 */
    private void equipOrRenewBagpack(List<byte[]> out, LoginSession session,
                                     List<com.nineyin.db.RoleInventoryStore.Bagpack> packs, int page,
                                     com.nineyin.db.RoleInventoryStore.Bagged item,
                                     BagCatalog.BagItem bag, int fromView, int fromSlot, long roleId) {
        com.nineyin.db.RoleInventoryStore.Bagpack same = null;
        boolean anchor1 = false;
        boolean anchor2 = false;
        for (com.nineyin.db.RoleInventoryStore.Bagpack bp : packs) {
            if (bp.page() != page) {
                continue;
            }
            if (same == null && bp.configId().equalsIgnoreCase(item.configId())) {
                same = bp;
            }
            if (bp.anchor() == 1) {
                anchor1 = true;
            } else if (bp.anchor() == 2) {
                anchor2 = true;
            }
        }
        boolean renew = same != null;
        int anchor;
        if (renew) {
            anchor = same.anchor();
        } else if (!anchor1) {
            anchor = 1;
        } else if (!anchor2) {
            anchor = 2;
        } else {
            log.info("BAGPACK no free anchor config={} page={} (both anchors busy, rejected)",
                    item.configId(), page);
            return;
        }
        java.time.Instant expires;
        if (renew) {
            expires = inventory.renewBagpack(roleId, page, anchor, bag.durationHours());
            if (expires == null) {
                log.warn("BAGPACK renew persist failed config={} page={} anchor={}",
                        item.configId(), page, anchor);
                return;
            }
            packs.remove(same);
        } else {
            expires = java.time.Instant.now()
                    .plus(java.time.Duration.ofHours(bag.durationHours()));
            if (!inventory.equipBagpack(roleId, page, anchor, item.configId(),
                    InventoryPackets.boostOf(item.configId()), expires)) {
                log.warn("BAGPACK equip persist failed config={} page={} anchor={}",
                        item.configId(), page, anchor);
                return;
            }
        }
        // 锚点已落库，再消耗物品（consume 失败仅告警：锚点生效、物品留存可人工清理）
        if (!inventory.consumeBagItem(roleId, fromSlot, item.viewId())) {
            log.warn("BAGPACK consume failed role={} slot={} view={} (anchor kept)",
                    roleId, fromSlot, item.viewId());
        }
        removeBagged(session, fromSlot, item.viewId());
        packs.add(new com.nineyin.db.RoleInventoryStore.Bagpack(
                page, anchor, item.configId(), InventoryPackets.boostOf(item.configId()), expires));
        session.liveBagpacks = packs;
        // 帧：源格清空（含镜像）→ 锚点对象重落 → 主页整页重推（对齐 F 抓包回应节奏）
        out.add(InventoryPackets.viewRemove(fromView, fromSlot));
        int fromMirror = InventoryPackets.mirrorOf(fromView);
        if (fromMirror != 0) {
            out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
        }
        out.addAll(InventoryPackets.anchorFramesFor(packs, page));
        int mainView = InventoryPackets.bagViewOf(page);
        for (com.nineyin.db.RoleInventoryStore.Bagged b : session.liveBagged) {
            if (b.viewId() == page) {
                out.add(InventoryPackets.bagViewAdd(
                        b, InventoryPackets.uniqueId(roleId, "bag", b.slot()), mainView));
            }
        }
        log.info("BAGPACK {} {} anchor={} boost={} expires={} (page={})",
                renew ? "renew" : "equip", item.configId(), anchor,
                InventoryPackets.boostOf(item.configId()), expires, page);
    }

    /** 普通物品入扩展格（addtoolbox）：容量校验（19..18+Σboost）后走主页对象移动。 */
    private void moveToToolboxSlot(List<byte[]> out, LoginSession session,
                                   List<com.nineyin.db.RoleInventoryStore.Bagpack> packs, int page,
                                   com.nineyin.db.RoleInventoryStore.Bagged peek,
                                   int fromView, int fromSlot, int fromCategory, int toSlot,
                                   long roleId) {
        boolean hasAnchor = false;
        for (com.nineyin.db.RoleInventoryStore.Bagpack bp : packs) {
            if (bp.page() == page) {
                hasAnchor = true;
                break;
            }
        }
        if (!hasAnchor) {
            // 对齐 F 参考服拒绝文案（MOVEITEM addtoolbox anchor not found view=124 pos=1）
            int extView = InventoryPackets.PAGE_EXT_VIEW.get(page);
            log.info("MOVEITEM addtoolbox anchor not found view={} pos={}", extView, toSlot);
            return;
        }
        int capacity = InventoryPackets.pageCapacity(packs, page);
        int target = toSlot;
        if (target <= 0) {
            for (int s = 19; s <= capacity; s++) {
                if (slotFree(session.liveBagged, s, page)) {
                    target = s;
                    break;
                }
            }
        }
        if (target < 19 || target > capacity) {
            int extView = InventoryPackets.PAGE_EXT_VIEW.get(page);
            log.info("MOVEITEM addtoolbox pos={} beyond capacity {} view={} (rejected)",
                    target, capacity, extView);
            return;
        }
        if (!slotFree(session.liveBagged, target, page)) {
            log.info("MOVEITEM addtoolbox pos={} occupied (page={})", target, page);
            return;
        }
        com.nineyin.db.RoleInventoryStore.Bagged item =
                removeBagged(session, fromSlot, fromCategory);
        if (item == null) {
            return;
        }
        // 物品对象始终落主页视图（slot 19-90 表示扩展格）；viewId 记页族类别（修 category=0 旧损）
        int mainView = InventoryPackets.bagViewOf(page);
        out.add(InventoryPackets.viewRemove(fromView, fromSlot));
        int fromMirror = InventoryPackets.mirrorOf(fromView);
        if (fromMirror != 0) {
            out.add(InventoryPackets.viewRemove(fromMirror, fromSlot));
        }
        out.add(InventoryPackets.viewRemove(mainView, target));
        com.nineyin.db.RoleInventoryStore.Bagged moved =
                new com.nineyin.db.RoleInventoryStore.Bagged(
                        target, item.configId(), item.itemType(), item.amount(), page, "");
        out.add(InventoryPackets.bagViewAdd(
                moved, InventoryPackets.uniqueId(roleId, "bag", target), mainView));
        int mirror = InventoryPackets.mirrorOf(mainView);
        if (mirror != 0) {
            out.add(InventoryPackets.viewRemove(mirror, target));
        }
        session.liveBagged.add(moved);
        if (!inventory.moveBagSlot(roleId, fromSlot, fromCategory, target, page)) {
            log.warn("move 30 addtoolbox persist failed role={} {}->{}", roleId, fromSlot, target);
        }
        log.info("move 30 bag[{}:{}]{}->toolbox[{}:{}] config={} (page={} cap={})",
                fromView, fromSlot, fromCategory, mainView, target, item.configId(),
                page, capacity);
    }

    /** 页内格位是否空闲（同页族类别才算占用）。 */
    private static boolean slotFree(List<com.nineyin.db.RoleInventoryStore.Bagged> bagged,
                                    int slot, int page) {
        for (com.nineyin.db.RoleInventoryStore.Bagged b : bagged) {
            if (b.slot() == slot && b.viewId() == page) {
                return false;
            }
        }
        return true;
    }

    private static int intArg(CustomMsg msg, int idx) {
        CustomMsg.Val v = msg.val(idx);
        return v != null && v.asExactInt() ? v.intOrZero() : 0;
    }

    /** 背包 wire 视图 → game_items 分类（1道具 2装备 3材料 4任务；未知 0）。 */
    private static int bagWireToCategory(int view) {
        return switch (view) {
            case InventoryPackets.VIEW_TOOL_PAGE -> 1;
            case InventoryPackets.VIEW_EQUIP_BAG -> 2;
            case InventoryPackets.VIEW_MATERIAL_PAGE -> 3;
            case InventoryPackets.VIEW_TASK_PAGE -> 4;
            default -> 0;
        };
    }

    private static com.nineyin.db.RoleInventoryStore.Equipped removeEquipped(
            LoginSession session, int slot) {
        for (int i = 0; i < session.liveEquipped.size(); i++) {
            if (session.liveEquipped.get(i).slot() == slot) {
                return session.liveEquipped.remove(i);
            }
        }
        return null;
    }

    private static com.nineyin.db.RoleInventoryStore.Bagged removeBagged(
            LoginSession session, int slot, int category) {
        for (int i = 0; i < session.liveBagged.size(); i++) {
            var b = session.liveBagged.get(i);
            if (b.slot() == slot && (category == 0 || b.viewId() == category)) {
                return session.liveBagged.remove(i);
            }
        }
        return null;
    }

    /** 目标背包页第一个空格（1 起：1-18 基础格 + 19-90 扩展格，无空位返回 91 护栏）。 */
    private static int freeBagSlot(List<com.nineyin.db.RoleInventoryStore.Bagged> bagged, int view) {
        int category = bagWireToCategory(view);
        boolean[] used = new boolean[96];
        for (var b : bagged) {
            if (b.viewId() == category && b.slot() > 0 && b.slot() < used.length) {
                used[b.slot()] = true;
            }
        }
        for (int i = 1; i <= 90; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return 91;
    }

    /** 穿上时装备类型（equipment.ini；缺失空串，帧里同样容忍）。 */
    private static String equipTypeOf(String configId) {
        var st = com.nineyin.protocol.EquipmentModels.itemStatic(configId);
        return st != null ? st.equipType() : "";
    }

    private void handleShortcutSet(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null || msg.size() < 4) {
            return;
        }
        CustomMsg.Val idxVal = msg.val(1);
        if (idxVal == null || !idxVal.asExactInt()) {
            return;
        }
        int idx = idxVal.intOrZero();
        String kind = msg.val(2).str != null ? msg.val(2).str : "skill";
        if (kind.isEmpty() && msg.val(2).type == CustomMsg.TYPE_I32) {
            kind = "skill";
        }
        String id = msg.val(3).str != null ? msg.val(3).str : "";
        int oldRow = session.actor.setShortcut(idx, kind, id);
        if (oldRow >= 0) {
            fx.send(S2CPackets.recordDelRow(com.nineyin.protocol.RecordId.SHORTCUT_REC, oldRow));
        }
        fx.send(S2CPackets.shortcutAdd(idx, kind, id));
        log.info("set shortcut idx={} kind={} id={}", idx, kind, id);
    }

    /** 201 删快捷键行。 */
    private void handleShortcutRemove(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        CustomMsg.Val v = msg.val(1);
        if (v == null || !v.asExactInt()) {
            return;
        }
        int row = v.intOrZero();
        PlayerActor.Shortcut removed = session.actor.removeShortcutRow(row);
        if (removed != null) {
            fx.send(S2CPackets.recordDelRow(com.nineyin.protocol.RecordId.SHORTCUT_REC, row));
            log.info("remove shortcut row={} id={}", row, removed.id());
        }
    }

    /**
     * 211 技能释放（对照参考 exe handleSkillCustom 的校验链与日志）：
     * 参数 → 出生 → 已学习 → 可编译 → 目标 → 锁/CD/死亡/MP/SP/轻功点计费
     * → Buff 暂驻 + cooldown_rec 行 + vital + State 起手动作与后续段 → 伤害/控制结算 →
     * 击杀碎银掉落 + total_action 后技能态清零回 stand。
     */
    private void handleSkill(FxSession fx, LoginSession session, CustomMsg msg) {
        CustomMsg.Val idVal = msg.val(1);
        if (idVal == null || idVal.type != CustomMsg.TYPE_STRING
                || idVal.str == null || idVal.str.isEmpty()) {
            log.info("reject malformed or unlearned USE_SKILL argc={} vals={}",
                    msg.size(), summarizeVals(msg));
            return;
        }
        String skillId = idVal.str;
        PlayerActor actor = session.actor;
        if (actor == null) {
            log.info("reject skill before player spawn id={}", skillId);
            return;
        }
        // 学习校验：全局 skill_init.ini 表优先（私服全学形态），否则角色内置集
        int level = skillInit.size() > 0 ? skillInit.level(skillId) : 0;
        if (level <= 0) {
            level = actor.learnedSkillLevel(skillId);
        }
        if (level <= 0) {
            log.info("reject unlearned installed skill id={}", skillId);
            return;
        }
        SkillCatalog.Definition def = skillCatalog.definition(skillId, level);
        if (def == null) {
            log.info("reject learned skill without compiled level response id={} level={}", skillId, level);
            return;
        }
        SceneState st = stateOf(fx);
        int targetOid = S2CPackets.PLAYER_OBJECT_ID;
        int targetOwner = S2CPackets.PLAYER_OWNER_ID;
        NpcInstance selected = null;
        if (def.requiresTarget()) {
            selected = selectedLivingTarget(st);
            if (selected == null) {
                log.info("reject skill id={} without living selected target", skillId);
                return;
            }
            // 施法距离门（attack_targetshape.ini TargetShapePkg）：20% 容差内放行
            // （参考服实测 dist 8.5>8.0 即被硬拒，无容差导致贴脸连按全部被吞）。
            if (def.targetRangeMeters() > 0) {
                float dx = selected.x - session.x;
                float dz = selected.z - session.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double max = def.targetRangeMeters() * RANGE_GATE_TOLERANCE;
                if (dist > max) {
                    log.info("reject skill id={}: target out of range ({}m > {}m)",
                            skillId,
                            String.format(Locale.ROOT, "%.1f", dist),
                            String.format(Locale.ROOT, "%.1f", max));
                    return;
                }
            }
            targetOid = selected.objectId;
            targetOwner = selected.ownerId;
        }
        long now = System.currentTimeMillis();
        String reject = beginSkillUse(actor, def, targetOid, targetOwner, now);
        if (reject != null) {
            log.info("reject skill id={}: {}", skillId, reject);
            return;
        }
        stageSkillBuffs(fx, actor, def, now, appendCastEffects(actor, def, selected, now));
        // cooldown_rec 行（表 5：category, startMs, endMs, team）
        fx.send(S2CPackets.recordAddRow(com.nineyin.protocol.RecordId.COOLDOWN_REC, 0,
                def.cooldownCategory(), now, now + def.personalCdMs(), def.cooldownTeam()));
        fx.send(actor.vitalUpdate());
        if (!def.actionName().isEmpty()) {
            fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, def.actionName()));
            scheduleFollowupActions(fx, def);
        }
        int damageDealt = 0;
        int targetCount = 0;
        int lootTotal = 0;
        SettleSummary lastSettle = new SettleSummary(0, 0, 0, List.of());
        boolean damageSettled = false;
        // 多跳：HitCount 波次铺满动作时长，单波倍率 = 总倍率/波数（总伤害守恒）；
        // 第 0 波立即结算，后续波按 cadence 排期，每波重解析目标（位置/存活实时）。
        int waves = Math.max(1, Math.min(def.hitCount(), MULTI_HIT_MAX_WAVES));
        double waveRatio = (def.baseDamage() > 0 ? def.baseDamage() / 100.0 : 1.0) / waves;
        long cadenceMs = Math.max(1, def.totalActionDurationMs() / waves);
        long seq = actor.castSeq;
        final NpcInstance waveTarget = selected;
        for (SkillCatalog.Effect effect : def.effects()) {
            if (effect.kind() != SkillCatalog.EffectKind.DAMAGE || effect.amount() <= 0) {
                continue;
            }
            if (def.targetMode() == SkillCatalog.TargetMode.SELF) {
                continue;   // 自身技无目标结算（对齐参考实现的 damage switch 无 SELF 分支）
            }
            lastSettle = def.targetMode() == SkillCatalog.TargetMode.SELECTED
                    ? settleSelected(fx, session, st, def, selected, now, waveRatio, 0, waves)
                    : settleArea(fx, session, st, def, selected, msg, now, waveRatio, 0, waves);
            damageSettled = true;
            damageDealt += lastSettle.damage();
            targetCount += lastSettle.targets();
            lootTotal += lastSettle.loot();
            for (int wave = 1; wave < waves; wave++) {
                final int waveIdx = wave;
                GameScheduler.schedule(waveIdx * cadenceMs, () -> {
                    if (actor.castSeq != seq || actor.hp <= 0) {
                        return;   // 新施法/死亡使旧波次作废
                    }
                    SettleSummary s = def.targetMode() == SkillCatalog.TargetMode.SELECTED
                            ? settleSelected(fx, session, st, def, waveTarget,
                                    System.currentTimeMillis(), waveRatio, waveIdx, waves)
                            : settleArea(fx, session, st, def, waveTarget, msg,
                                    System.currentTimeMillis(), waveRatio, waveIdx, waves);
                    if (s.loot() > 0) {
                        creditLoot(fx, actor, s.loot());
                    }
                });
            }
        }
        for (SkillCatalog.Effect effect : def.effects()) {
            if (effect.kind() == SkillCatalog.EffectKind.TARGET_CONTROL) {
                // 纯控制技（无伤害结算）目标独立解析：单体取选中目标、区域走几何过滤，
                // 否则控制永远落空（参考服日志全部 targets=0 的根因之一）。
                SettleSummary controlSettle = lastSettle;
                if (!damageSettled) {
                    controlSettle = new SettleSummary(0, 0, 0,
                            controlTargets(session, st, def, selected, msg));
                }
                applyTargetControl(session, def, effect.buff(), controlSettle, now);
            }
        }
        if (lootTotal > 0) {
            creditLoot(fx, actor, lootTotal);
            log.info("skill defeated NPCs targets={} silver_drop={}", targetCount, lootTotal);
        }
        log.info("accepted skill id={} mp={} sp={} qg={} damage={} target={} targets={} personal_cd={}ms public_cd={}ms",
                skillId, actor.mp, actor.sp, actor.qinggongPoint, damageDealt, targetOid,
                targetCount, def.personalCdMs(), def.publicCdMs());
        scheduleCurrentSkillClear(fx, actor, def);
    }

    /** 计费与互斥（对照 Go beginSkillUse；失败返回原因文本，成功返回 null 并落账）。 */
    private String beginSkillUse(PlayerActor actor, SkillCatalog.Definition def,
                                 int targetOid, int targetOwner, long now) {
        if (now < actor.skillLockUntilMs) {
            return "skill locked remaining=" + (actor.skillLockUntilMs - now) + "ms";
        }
        if (actor.controlled(now)) {
            return "player is controlled (stateType=" + actor.controlStateType
                    + " remaining=" + (actor.controlledUntilMs - now) + "ms)";
        }
        Long ready = actor.skillCooldowns.get(def.id());
        if (ready != null && ready > now) {
            return "personal cooldown remaining=" + (ready - now) + "ms";
        }
        int teamKey = def.cooldownTeam() != 0 ? def.cooldownTeam() : -def.cooldownCategory();
        Long teamReady = actor.skillTeamCooldowns.get(teamKey);
        if (teamReady != null && teamReady > now) {
            return "public cooldown team=" + teamKey + " remaining=" + (teamReady - now) + "ms";
        }
        if (actor.hp <= 0) {
            return "player is dead";
        }
        if (actor.mp < def.mpCost()) {
            return "MP " + actor.mp + " < " + def.mpCost();
        }
        if (actor.sp < def.spCost()) {
            return "SP " + actor.sp + " < " + def.spCost();
        }
        if (def.qgCost() > 0 && !actor.useQingGong(def.qgCost())) {
            return "QingGongPoint cannot pay " + def.qgCost();
        }
        if (!actor.spendMp(def.mpCost())) {
            return "MP spend rejected";
        }
        actor.sp -= def.spCost();
        actor.castSeq++;
        actor.skillCooldowns.put(def.id(), now + def.personalCdMs());
        actor.skillTeamCooldowns.put(teamKey, now + def.publicCdMs());
        actor.skillLockUntilMs = now + SKILL_LOCK_MS;
        actor.curSkillId = def.id();
        actor.curSkillEffectId = def.id();
        actor.curSkillLevel = def.level();
        actor.curSkillTargetOid = targetOid;
        actor.curSkillTargetOwner = targetOwner;
        actor.logicState = PlayerActor.LOGIC_STATE_FIGHTING;
        return null;
    }

    /**
     * 目标控制结算（§3.25）：技能 TARGET_CONTROL 效果按 buff_static.StateType 落账。
     * NPC 目标：受控至 lifetime（AI 出招跳过）；玩家目标：先消费装备免疫位
     * （StateType=1 眩晕族 ↔ 位 33..38，eqp_ImmStun 六级实测位段），命中才受控
     * （受控期间技能施放被拒）。非控制 buff 只记日志。
     * 目标来源 = 伤害结算的 landed 列表（未命中的候选不再被控）。
     */
    private void applyTargetControl(LoginSession session, SkillCatalog.Definition def,
                                    SkillCatalog.BuffDef buff, SettleSummary settle, long now) {
        int stateType = controlCatalog.stateTypeOf(buff.staticData());
        if (stateType == 0) {
            log.info("settled compiled target control skill={} buff={} targets={} lifetime={}ms (no StateType)",
                    def.id(), buff.configId(), settle.targets(), buff.lifetimeMs());
            return;
        }
        int applied = 0;
        for (NpcInstance npc : settle.npcs()) {
            if (npc.dead || npc.hp <= 0) {
                continue;
            }
            npc.controlledUntilMs = now + Math.max(500, buff.lifetimeMs());
            npc.controlStateType = stateType;
            applied++;
        }
        log.info("settled compiled target control skill={} buff={} stateType={} targets={} applied={} lifetime={}ms",
                def.id(), buff.configId(), stateType, settle.targets(), applied, buff.lifetimeMs());
    }

    /**
     * 纯控制技（无伤害效果）的目标解析：单体取存活选中目标，区域技与伤害路径
     * 同一套几何过滤（圆/扇/矩形 + 高度差），不再因无 DAMAGE 结算而落空。
     */
    private List<NpcInstance> controlTargets(LoginSession session, SceneState st,
                                             SkillCatalog.Definition def, NpcInstance selected,
                                             CustomMsg msg) {
        if (def.targetMode() == SkillCatalog.TargetMode.SELF) {
            return List.of();
        }
        if (def.targetMode() == SkillCatalog.TargetMode.SELECTED) {
            if (selected == null || selected.dead || selected.hp <= 0) {
                return List.of();
            }
            return List.of(selected);
        }
        CastCenter center = castCenter(st, selected, msg, session);
        if (center == null) {
            return List.of();
        }
        return areaTargets(st, center, def);
    }

    /**
     * 玩家侧控制落账（供 NPC AI / PvP 控制源调用）：先消费装备免疫位
     * （§3.18 收集的 equipImmunities 在此闭环），被免疫返回 false 不落账。
     */
    public boolean applyControlToPlayer(PlayerActor actor, int stateType, long durationMs, long now) {
        if (controlCatalog.blocks(stateType, actor.equipImmunities())) {
            log.info("control blocked by immunity stateType={} bits={}",
                    stateType, actor.equipImmunities());
            return false;
        }
        actor.controlledUntilMs = now + durationMs;
        actor.controlStateType = stateType;
        log.info("player controlled stateType={} duration={}ms", stateType, durationMs);
        return true;
    }

    /**
     * NPC 技能命中玩家后的控制落账（AiEventEngine.ControlSink 实现）：
     * 按技能目录解析 TARGET_CONTROL 效果，StateType 非 0 的逐个落账
     * （任一实际受控即返回 true；被免疫位抵消的单独记日志）。
     * 包可见：SceneFlowTest 直接验证目录解析半链。
     */
    boolean applySkillControlToPlayer(PlayerActor actor, String skillId, long now) {
        SkillCatalog.Definition def = skillCatalog.definition(skillId, 1);
        if (def == null) {
            return false;
        }
        boolean applied = false;
        for (SkillCatalog.Effect effect : def.effects()) {
            if (effect.kind() != SkillCatalog.EffectKind.TARGET_CONTROL) {
                continue;
            }
            int stateType = controlCatalog.stateTypeOf(effect.buff().staticData());
            if (stateType == 0) {
                continue;
            }
            applied |= applyControlToPlayer(actor, stateType, effect.buff().lifetimeMs(), now);
        }
        return applied;
    }

    /**
     * on_cast 运行期钩子：scripts/skill/&lt;id&gt;.lua 的 M.on_cast(ctx) 返回
     * {add_effects={...}} 时追加自身向效果（PLAYER_BUFF/STANCE_CYCLE/RED_ARMOR；
     * 伤害/控制仍须走编译期管线保证帧配对）。无脚本/出错返回 null（默认管线）。
     */
    private List<SkillCatalog.Effect> appendCastEffects(PlayerActor actor, SkillCatalog.Definition def,
                                                        NpcInstance selected, long now) {
        if (scriptHost == null) {
            return null;
        }
        party.iroiro.luajava.value.LuaValue module = scriptHost.skillModule(def.id());
        if (module == null) {
            return null;
        }
        party.iroiro.luajava.value.LuaValue fn = module.get("on_cast");
        if (fn == null || fn.type() != party.iroiro.luajava.Lua.LuaType.FUNCTION) {
            return null;
        }
        java.util.Map<String, Object> ctx = com.nineyin.script.SkillScriptBridge.castContext(
                def.id(), def.level(),
                actor.buffStaticDataAt(PlayerActor.BUFF_SLOT_TAIJI_STANCE, now),
                actor.hp, actor.maxHp(), actor.mp,
                selected != null ? selected.objectId : null,
                selected != null ? selected.configId : null,
                selected != null ? selected.hp : 0,
                selected != null ? selected.maxHp : 0);
        Object ret = scriptHost.callMap(fn, ctx);
        return ret != null
                ? com.nineyin.script.SkillScriptBridge.parseCastAddEffects(def.id(), ret) : null;
    }

    /**
     * on_hit 运行期钩子：M.on_hit(ctx) 返回 {ratio=...} 时在掷点前修正该波倍率
     * （架势加成/连击衰减等条件数值；门与帧不受影响）。出错/无返回保持原倍率。
     */
    private double modifyHitRatio(PlayerActor actor, NpcInstance npc, SkillCatalog.Definition def,
                                  double ratio, int wave, int waves, long now) {
        if (scriptHost == null) {
            return ratio;
        }
        party.iroiro.luajava.value.LuaValue module = scriptHost.skillModule(def.id());
        if (module == null) {
            return ratio;
        }
        party.iroiro.luajava.value.LuaValue fn = module.get("on_hit");
        if (fn == null || fn.type() != party.iroiro.luajava.Lua.LuaType.FUNCTION) {
            return ratio;
        }
        java.util.Map<String, Object> ctx = com.nineyin.script.SkillScriptBridge.hitContext(
                def.id(), def.level(), wave, waves, ratio,
                actor.buffStaticDataAt(PlayerActor.BUFF_SLOT_TAIJI_STANCE, now),
                actor.hp, actor.maxHp(), actor.mp,
                npc.objectId, npc.configId, npc.hp, npc.maxHp);
        Object ret = scriptHost.callMap(fn, ctx);
        Double modified = ret != null
                ? com.nineyin.script.SkillScriptBridge.parseHitRatio(ret) : null;
        return modified != null ? modified : ratio;
    }

    /** 技能 Buff 暂驻（自身 Buff / 架势轮换 / 红霸体 + 脚本 on_cast 追加效果），日志与参考服同构。 */
    private void stageSkillBuffs(FxSession fx, PlayerActor actor, SkillCatalog.Definition def, long now,
                                 List<SkillCatalog.Effect> castExtras) {
        List<SkillCatalog.Effect> all = def.effects();
        if (castExtras != null && !castExtras.isEmpty()) {
            all = new ArrayList<>(def.effects());
            all.addAll(castExtras);
        }
        for (SkillCatalog.Effect effect : all) {
            SkillCatalog.BuffDef buff = null;
            switch (effect.kind()) {
                case PLAYER_BUFF -> buff = effect.buff();
                case STANCE_CYCLE -> buff = actor.nextStanceVariant(effect.buffVariants(), now);
                case RED_ARMOR -> buff = new SkillCatalog.BuffDef("buf_fixed_red",
                        PlayerActor.RED_ARMOR_STATIC, 1,
                        Math.max(1, def.totalActionDurationMs()), PlayerActor.BUFF_SLOT_RED_ARMOR);
                default -> {
                    continue;
                }
            }
            if (buff == null) {
                continue;
            }
            int slot = actor.activateSkillBuff(buff, now);
            if (slot == 0) {
                log.info("no native BuffInfo slot available skill={} buff={}", def.id(), buff.configId());
                continue;
            }
            if (SEND_SKILL_BUFF_EVENTS) {
                // event-169 add（与到期 remove 严格配对；帧型同 msg54 BuffInParry 路径）
                fx.send(CustomPackets.intMessage(169,
                        0,
                        buff.configId(),
                        new CustomPackets.ObjRef(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID),
                        new CustomPackets.ObjRef(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID),
                        actor.name, actor.name,
                        now,
                        0));
            }
            log.info("staged compiled skill Buff skill={} buff={} property={} value={} expiry={}",
                    def.id(), buff.configId(), PropertyId.bufferInfoSlot(slot),
                    actor.buffInfo(slot), actor.buffExpiresAt(slot));
        }
    }

    /** zhaoshi 后续段：按 at 偏移补发 State 动作。 */
    private void scheduleFollowupActions(FxSession fx, SkillCatalog.Definition def) {
        for (SkillCatalog.Segment segment : def.followupActions()) {
            GameScheduler.schedule(Math.max(0, segment.atMs()), () -> {
                fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, segment.action()));
                log.info("skill follow-up State sent id={} action={} at={}ms",
                        def.id(), segment.action(), segment.atMs());
            });
        }
    }

    /** total_action 后清技能态并回 stand（旧定时器不覆盖新技能）。 */
    private void scheduleCurrentSkillClear(FxSession fx, PlayerActor actor, SkillCatalog.Definition def) {
        long delay = Math.max(1, def.totalActionDurationMs());
        GameScheduler.schedule(delay, () -> {
            if (!actor.clearCurrentSkill(def.id())) {
                return;
            }
            fx.send(actor.currentSkillClearFrame());
            fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, "stand"));
            log.info("skill state cleared id={} total_action={}ms", def.id(), delay);
        });
    }

    /** 击杀碎银入账 + 0x10 CapitalType1（第 0 波同步入账，后续波次各自入账）。 */
    private void creditLoot(FxSession fx, PlayerActor actor, int lootTotal) {
        actor.silver += lootTotal;
        fx.send(S2CPackets.playerBatch()
                .i64(PropertyId.CAPITAL_TYPE1, actor.silver).build());
    }

    /** 选中且存活的可攻击目标。 */
    private NpcInstance selectedLivingTarget(SceneState st) {
        if (st.world == null || st.selectedOid == 0) {
            return null;
        }
        NpcInstance npc;
        synchronized (st.world) {
            npc = st.world.npcById(st.selectedOid);
        }
        return npc != null && !npc.dead && npc.hp > 0 ? npc : null;
    }

    /** 效果结算汇总：实际伤害 / 命中目标数 / 击杀掉落（碎银）/ 被击中的 NPC（控制结算用）。 */
    record SettleSummary(int damage, int targets, int loot, List<NpcInstance> npcs) {
    }

    /** 单体伤害效果结算（SELECTED，多跳波次按 wave/waves 计；目标死亡后波次空结算）。
     *  包可见：SceneFlowTest 直接验证波次守恒。 */
    SettleSummary settleSelected(FxSession fx, LoginSession session, SceneState st,
                                         SkillCatalog.Definition def, NpcInstance selected,
                                         long now, double ratio, int wave, int waves) {
        if (selected == null || selected.dead || selected.hp <= 0) {
            return new SettleSummary(0, 0, 0, List.of());
        }
        HitResult hit = settleSkillHit(fx, session, st, selected, def, now, ratio, wave, waves);
        if (wave > 0) {
            log.info("skill {} wave {}/{} -> npc {} damage={}", def.id(), wave + 1, waves,
                    selected.configId, hit.damage());
        }
        return new SettleSummary(hit.damage(), hit.landed() ? 1 : 0, hit.loot(),
                hit.landed() ? List.of(selected) : List.of());
    }

    /** 区域伤害效果结算（圆/扇/矩形/目标区域；每波重扫目标，日志与参考服同构）。 */
    private SettleSummary settleArea(FxSession fx, LoginSession session, SceneState st,
                                     SkillCatalog.Definition def, NpcInstance selected,
                                     CustomMsg msg, long now, double ratio, int wave, int waves) {
        CastCenter center = castCenter(st, selected, msg, session);
        if (center == null) {
            log.info("compiled area skill id={} has no center; no targets settled", def.id());
            return new SettleSummary(0, 0, 0, List.of());
        }
        List<NpcInstance> hits = areaTargets(st, center, def);
        int damage = 0;
        int targets = 0;
        int loot = 0;
        List<NpcInstance> landed = new ArrayList<>(hits.size());
        for (NpcInstance npc : hits) {
            HitResult hit = settleSkillHit(fx, session, st, npc, def, now, ratio, wave, waves);
            damage += hit.damage();
            loot += hit.loot();
            if (hit.landed()) {
                targets++;
                landed.add(npc);
            }
        }
        log.info("settled compiled area skill id={} wave={}/{} mode={} center=({},{},{}) radius={} angle={} width={} hits={} split={} cadence={}ms damage_total={}",
                def.id(), wave + 1, waves, def.targetMode().ordinal(),
                String.format(Locale.ROOT, "%.2f", center.x()),
                String.format(Locale.ROOT, "%.2f", center.y()),
                String.format(Locale.ROOT, "%.2f", center.z()),
                String.format(Locale.ROOT, "%.1f", def.areaRadius()),
                String.format(Locale.ROOT, "%.1f", def.sectorAngle()),
                String.format(Locale.ROOT, "%.1f", def.areaWidth()),
                hits.size(), waves,
                Math.max(1, def.totalActionDurationMs() / waves), damage);
        return new SettleSummary(damage, targets, loot, landed);
    }

    /** 区域技能中心：SELECTED_AREA 取目标位置，其余取施法者报点（msg 2/3/4=xyz，5=朝向）。 */
    private record CastCenter(float x, float y, float z, float orient) {
    }

    private CastCenter castCenter(SceneState st, NpcInstance selected, CustomMsg msg, LoginSession session) {
        if (selected != null) {
            return new CastCenter(selected.x, selected.y, selected.z, selected.orient);
        }
        float x = session.x;
        float y = session.y;
        float z = session.z;
        float orient = session.orient;
        CustomMsg.Val vx = msg.val(2);
        CustomMsg.Val vy = msg.val(3);
        CustomMsg.Val vz = msg.val(4);
        if (vx != null && vy != null && vz != null
                && (vx.type == CustomMsg.TYPE_F32 || vx.type == CustomMsg.TYPE_F64)) {
            x = vx.asFloat();
            y = vy.asFloat();
            z = vz.asFloat();
            CustomMsg.Val vo = msg.val(5);
            if (vo != null && (vo.type == CustomMsg.TYPE_F32 || vo.type == CustomMsg.TYPE_F64)) {
                orient = vo.asFloat();
            }
        }
        return new CastCenter(x, y, z, orient);
    }

    /** 形状过滤（圆/扇/矩形 + 高度差），与参考 exe applyShapedSkillDamage 同式。 */
    private List<NpcInstance> areaTargets(SceneState st, CastCenter center, SkillCatalog.Definition def) {
        if (st.world == null || def.areaRadius() <= 0) {
            return List.of();
        }
        List<NpcInstance> candidates;
        synchronized (st.world) {
            candidates = st.world.snapshotNear(center.x(), center.z(), def.areaRadius(),
                    AREA_TARGET_CAP, n -> !n.dead && n.hp > 0);
        }
        List<NpcInstance> hits = new ArrayList<>(candidates.size());
        float radiusSq = def.areaRadius() * def.areaRadius();
        for (NpcInstance n : candidates) {
            float dx = n.x - center.x();
            float dz = n.z - center.z();
            float dy = Math.abs(n.y - center.y());
            if (def.areaHeight() > 0 && dy > def.areaHeight()) {
                continue;
            }
            if (def.areaWidth() > 0) {
                double facingX = Math.cos(center.orient());
                double facingZ = Math.sin(center.orient());
                double forward = dx * facingX + dz * facingZ;
                double side = -dx * facingZ + dz * facingX;
                if (forward < 0 || forward > def.areaRadius()
                        || Math.abs(side) > def.areaWidth() / 2.0) {
                    continue;
                }
            } else if (dx * dx + dz * dz > radiusSq) {
                continue;
            } else if (def.sectorAngle() > 0) {
                double targetAngle = Math.atan2(dz, dx);
                double delta = Math.atan2(
                        Math.sin(targetAngle - center.orient()),
                        Math.cos(targetAngle - center.orient()));
                if (Math.abs(delta) > def.sectorAngle() * Math.PI / 360.0) {
                    continue;
                }
            }
            hits.add(n);
        }
        hits.sort(Comparator.comparingInt(n -> n.objectId));
        return hits;
    }

    /** 单目标结算结果：实际伤害 / 是否命中 / 击杀掉落（碎银 = MaxHP/100 夹 [1,99]）。 */
    private record HitResult(int damage, boolean landed, int loot) {
    }

    /**
     * 单目标伤害结算：公式掷未命中/暴击（Lua formula.lua 优先、Java 回退；
     * ratio = 编译伤害/100/波数，on_hit 钩子可在掷点前修正倍率；多跳总伤害与单发守恒），
     * 命中掉血 + 0x10 血条批，死亡 0x0E 移除 + 重生排期 + AI/九阴志事件。
     */
    private HitResult settleSkillHit(FxSession fx, LoginSession session, SceneState st,
                                     NpcInstance npc, SkillCatalog.Definition def, long now,
                                     double ratio, int wave, int waves) {
        ratio = modifyHitRatio(session.actor, npc, def, ratio, wave, waves, now);
        CombatFormulas.DamageResult hit = formulaHost != null
                ? formulaHost.calc(session.actor.combatStats(),
                        CombatStats.defender(npc.defense, npc.evasion),
                        ratio, ThreadLocalRandom.current(), false)
                : CombatFormulas.calc(session.actor.combatStats(),
                        CombatStats.defender(npc.defense, npc.evasion),
                        ratio, ThreadLocalRandom.current(), false);
        if (hit.miss()) {
            log.info("skill {} -> npc {} MISS", def.id(), npc.configId);
            if (ai != null) {
                ai.onNpcDamaged(st.world, npc, fx, now);
            } else {
                npc.lastCombatMs = now;
            }
            return new HitResult(0, false, 0);
        }
        int damage = hit.damage();
        // 内功 on_attack 钩子：附加伤害并入本次扣血（同一血量批/死亡路径），回血/护盾就地结算
        if (neigong != null) {
            damage += neigong.onAttackLanded(fx, session.actor, npc, damage,
                    hit.crit(), wave, waves, now);
        }
        npc.hp = Math.max(0, npc.hp - damage);
        log.info("skill {} -> npc {} damage={} crit={} reductionPct={}",
                def.id(), npc.configId, damage, hit.crit(),
                String.format(Locale.ROOT, "%.1f", hit.reductionPct() * 100));
        if (ai != null) {
            ai.onNpcDamaged(st.world, npc, fx, now);
        } else {
            npc.lastCombatMs = now;
        }
        S2CPackets.Batch npcBatch = S2CPackets.batch(npc.objectId, npc.ownerId);
        npcBatch.i32(PropertyId.HP, npc.hp)
                .i32(PropertyId.HP_RATIO, npc.maxHp > 0 ? (int) ((long) npc.hp * 100 / npc.maxHp) : 0);
        int loot = 0;
        if (npc.hp == 0) {
            npc.dead = true;
            npc.respawnAtMs = WorldBossManager.TAG.equals(npc.tag)
                    ? Long.MAX_VALUE : now + respawnDelay(npc);
            npcBatch.u8(PropertyId.DEAD, 1);
            loot = Math.max(1, Math.min(99, npc.maxHp / 100));
        }
        fx.send(npcBatch.build());
        if (npc.dead) {
            fx.send(S2CPackets.removeObject(npc.objectId, npc.ownerId));
            st.sentIds.remove(npc.objectId);
            if (ai != null) {
                ai.onNpcDeath(st.world, npc, 0);
            }
            ninjiuzhi.onKill();
            log.info("npc {} ({}) defeated, respawn in {}ms", npc.objectId, npc.configId, NPC_RESPAWN_MS);
        }
        return new HitResult(damage, true, loot);
    }

    /** 211 起手互斥窗口（参考实现 500ms）。 */
    private static final long SKILL_LOCK_MS = 500;
    /** 区域技能单次结算目标上限（参考无上限，此处防摆位异常爆帧）。 */
    private static final int AREA_TARGET_CAP = 64;
    /** 施法距离门容差：射程 × 1.2 内放行（参考服无容差硬拒的修正）。 */
    private static final double RANGE_GATE_TOLERANCE = 1.2;
    /** 技能 Buff 是否发 event-169 add/remove（客户端表现异常时可关，仅保留 BufferInfo 属性）。 */
    private static final boolean SEND_SKILL_BUFF_EVENTS = true;
    /** 多跳波次上限（varprop HitCount 实测 1..50；每波重扫目标，上限防爆帧）。 */
    private static final int MULTI_HIT_MAX_WAVES = 20;

    // ---------- 轻功 ----------

    /** 216 USE_QINGGONG：(216, id, begin/end)。仅 begin=1 计费；buf_qg_drift/dst 映射原生 Buff 槽。 */
    private void handleQingGong(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        if (msg.size() != 3 || msg.val(1).type != CustomMsg.TYPE_STRING
                || msg.val(1).str == null || msg.val(1).str.isEmpty()
                || !msg.val(2).asExactInt()
                || (msg.val(2).intOrZero() != 0 && msg.val(2).intOrZero() != 1)) {
            log.info("reject malformed USE_QINGGONG argc={}", msg.size());
            return;
        }
        QinggongCatalog.Definition def = qinggongCatalog.lookup(msg.val(1).str);
        if (def == null) {
            log.info("reject unknown qinggong id={}", msg.val(1).str);
            return;
        }
        long now = System.currentTimeMillis();
        QinggongCatalog.BuffSpec spec = QinggongCatalog.buffSpec(def);
        if (msg.val(2).intOrZero() == 0) {
            if (spec != null && spec.clearOnEnd()
                    && session.actor.clearBuff(spec.slot(), spec.staticData())) {
                fx.send(session.actor.vitalUpdate());
            }
            log.info("qinggong end id={}", def.id());
            return;
        }
        if (spec != null && spec.clearOnEnd()
                && session.actor.hasActiveBuff(spec.slot(), spec.staticData(), now)) {
            log.info("ignore duplicate qinggong start id={}", def.id());
            return;
        }
        if (!session.actor.useQingGong(def.consume())) {
            log.info("reject qinggong id={} cost={} point={}", def.id(), def.consume(),
                    session.actor.qinggongPoint);
            return;
        }
        if (spec != null) {
            session.actor.activateBuff(spec.slot(), spec.staticData(), spec.durationMs(),
                    def.bufferId(), 1);
        }
        fx.send(session.actor.vitalUpdate());
        log.info("accepted qinggong id={} type={} cost={} point={}/{}", def.id(), def.type(),
                def.consume(), session.actor.qinggongPoint, session.actor.maxQinggongPoint);
    }

    /** 217 ACTIVE_QINGGONG：(217, id) → ActiveQGSkillRec 行（高阶轻功装备前置）。 */
    private void handleActiveQingGong(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null) {
            return;
        }
        if (msg.size() != 2 || msg.val(1).type != CustomMsg.TYPE_STRING
                || msg.val(1).str == null || msg.val(1).str.isEmpty()) {
            log.info("reject malformed ACTIVE_QINGGONG argc={}", msg.size());
            return;
        }
        String id = msg.val(1).str;
        if (session.actor.activateQingGong(id)) {
            fx.send(BootstrapPackets.activeQinggongRow(id));
            log.info("activated qinggong id={} via ActiveQGSkillRec", id);
        } else {
            log.info("active qinggong already present id={}", id);
        }
    }

    /** 218 招架：subtype≠0 开启招架（耗耐力减伤，见 CombatFormulas.parryMultiplier），=0 收招。 */
    private void handleActiveParry(FxSession fx, LoginSession session, CustomMsg msg) {
        if (session.actor == null || msg.size() < 3) {
            return;
        }
        CustomMsg.Val subtype = msg.val(1);
        if (!subtype.asExactInt()) {
            return;
        }
        session.actor.parrying = subtype.intOrZero() != 0;
        log.info("active parry {} (subtype={} argument={} endurance={})",
                session.actor.parrying ? "on" : "off", subtype.intOrZero(), msg.val(2),
                session.actor.parryEndurance);
    }

    /** 0x1E msg=20 原生技能动作帧（AI 出招广播用；玩家 211 走 State 动作路由）。 */
    static byte[] nativeSkillAction(int casterOid, int casterOwner, String skillId,
                                    int targetOid, int targetOwner) {
        return CustomPackets.intMessage(20,
                new CustomPackets.ObjRef(casterOid, casterOwner),
                skillId,
                new CustomPackets.ObjRef(targetOid, targetOwner),
                0,
                0f);
    }

    // ---------- 每秒 tick（NineyinServer actor 调度器驱动） ----------

    /** actor 恢复 + NPC 重生 + 修炼推进 + 节流 vital 同步。 */
    /**
     * 扩容包到期扫描（NineyinServer 60s 调度驱动）：过期锚点 → DB 删行 + 锚点对象
     * 0x19 清除 + 受影响主页整页重推 + 重建 0x0C 缓存。扩展格物品原地保留
     * （容量回缩后客户端按容量裁剪显示，重新扩容恢复可见——2026-10-31 决策）。
     */
    public void bagpackTick(FxSession fx, LoginSession session) {
        if (session.roleId == 0 || inventory == null || session.actor == null) {
            return;
        }
        // 每 tick 以 DB 为准刷新（bagpacks() 惰性清理过期行）：快照只作变更检测基线，
        // 这样长会话跨期、外部改库（GM/脚本）都能被看见
        List<com.nineyin.db.RoleInventoryStore.Bagpack> fresh =
                inventory.bagpacks(session.roleId);
        if (fresh.equals(session.liveBagpacks == null ? List.of() : session.liveBagpacks)) {
            return;
        }
        List<com.nineyin.db.RoleInventoryStore.Bagpack> old =
                session.liveBagpacks != null ? session.liveBagpacks : List.of();
        for (com.nineyin.db.RoleInventoryStore.Bagpack bp : old) {
            if (!fresh.contains(bp)) {
                log.info("BAGPACK expired config={} page={} anchor={} expires={}",
                        bp.configId(), bp.page(), bp.anchor(), bp.expires());
            }
        }
        List<com.nineyin.db.RoleInventoryStore.Bagpack> remaining = fresh;
        List<byte[]> out = new ArrayList<>();
        java.util.Set<Integer> pages = new java.util.HashSet<>();
        for (com.nineyin.db.RoleInventoryStore.Bagpack bp : old) {
            if (!fresh.contains(bp)) {
                pages.add(bp.page());
            }
        }
        for (com.nineyin.db.RoleInventoryStore.Bagpack bp : fresh) {
            if (!old.contains(bp)) {
                pages.add(bp.page());
            }
        }
        for (int page : pages) {
            out.addAll(InventoryPackets.anchorFramesFor(remaining, page));
            int mainView = InventoryPackets.bagViewOf(page);
            for (com.nineyin.db.RoleInventoryStore.Bagged b : session.liveBagged) {
                if (b.viewId() == page) {
                    out.add(InventoryPackets.bagViewAdd(b,
                            InventoryPackets.uniqueId(session.roleId, "bag", b.slot()),
                            mainView));
                }
            }
        }
        for (byte[] frame : out) {
            fx.send(frame);
        }
        session.inventoryFrames = InventoryPackets.inventoryFrames(session.roleId,
                session.liveEquipped, session.liveBagged,
                Appearance.resolveVisual(session.appearance).sex());
    }

    public void tick(FxSession fx, LoginSession session) {
        SceneState st = fx.channel().attr(STATE).get();
        if (st == null || session.actor == null || !session.uiBootstrapped
                || st.awaitingReentryReady) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = session.actor.tick(now);
        // 内功特效：被动表重算 + 周期触发钩子（heal 变化并入 vitalUpdate 节流）
        if (neigong != null && neigong.onTick(fx, session.actor, now)) {
            changed = true;
        }
        // Buff 到期剔除：补发 event-169 remove + 槽位清空帧（与 stageSkillBuffs 的 add 配对，
        // 修复参考服「skip add 仍发 remove」造成的客户端 buff 栏漂移）
        if (SEND_SKILL_BUFF_EVENTS) {
            for (PlayerActor.ExpiredBuff expired : session.actor.drainExpiredBuffs()) {
                fx.send(CustomPackets.intMessage(169,
                        1,
                        expired.configId(),
                        new CustomPackets.ObjRef(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID),
                        new CustomPackets.ObjRef(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID),
                        session.actor.name, session.actor.name,
                        now,
                        0));
                fx.send(S2CPackets.objectProperties(S2CPackets.PLAYER_OBJECT_ID,
                        S2CPackets.PLAYER_OWNER_ID,
                        S2CPackets.PropVal.text(PropertyId.bufferInfoSlot(expired.slot()), "")));
            }
        }
        respawnDeadNpcs(fx, st, session, now);
        if (st.world != null && ai != null) {
            ai.pump(st.world, now);
        }
        // 修炼整分钟结算（1s 驱动、内部按分钟推进；对齐 Go facultyTicker）
        FacultyState faculty = session.actor.faculty;
        if (faculty != null) {
            int slot = faculty.advanceFaculty(java.time.Instant.now(), levelCurves);
            if (slot > 0) {
                fx.send(facultyBookFrame(slot, faculty.books.get(slot - 1)));
                fx.send(session.actor.vitalUpdate());
                if (facultyStore != null && facultyStore.save(session.roleId, faculty)) {
                    st.lastFacultyFlushMs = now;
                }
                log.info("settled inner cultivation role={} slot={}", session.roleId, slot);
            } else {
                flushFacultyDirty(fx, session, now);
            }
        }
        if (changed && now - st.lastVitalMs >= 1000) {
            st.lastVitalMs = now;
            fx.send(session.actor.vitalUpdate());
        }
    }

    private void respawnDeadNpcs(FxSession fx, SceneState st, LoginSession session, long now) {
        if (st.world == null) {
            return;
        }
        synchronized (st.world) {
            for (NpcInstance n : st.world.npcs) {
                if (!n.dead || now < n.respawnAtMs) {
                    continue;
                }
                n.dead = false;
                n.hp = n.maxHp;
                n.x = n.homeX;
                n.y = n.homeY;
                n.z = n.homeZ;
                float dx = n.x - session.x;
                float dz = n.z - session.z;
                if (dx * dx + dz * dz <= VIEW_RADIUS * VIEW_RADIUS) {
                    fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
                    fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
                    st.sentIds.add(n.objectId);
                }
            }
        }
    }

    // ---------- 巡逻广播 ----------

    private void broadcastPatrol(FxSession fx, SceneState st, LoginSession session) {
        long now = System.currentTimeMillis();
        // 到达确认 0x1F（更新的腿已开始则跳过，避免回拽）
        var it = st.pendingArrive.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now < e.getValue().atMs()) {
                continue;
            }
            SceneWorld.PendingLeg leg = st.world != null ? st.world.legOf(e.getKey()) : null;
            boolean skip = leg != null && leg.arriveAtMs > e.getValue().atMs();
            if (!skip) {
                PendingArrive a = e.getValue();
                fx.send(S2CPackets.serverLocation(e.getKey(), 1, a.x(), a.y(), a.z(), a.orient()));
            }
            it.remove();
        }
        if (!st.npcsSent || st.world == null || now - st.lastPatrolMs < 500) {
            return;
        }
        st.lastPatrolMs = now;
        st.world.tick(now);
        int moved = 0;
        synchronized (st.world) {
            for (NpcInstance n : st.world.npcs) {
                if (!st.sentIds.contains(n.objectId) || n.dead) {
                    continue;
                }
                float dx = n.x - session.x;
                float dz = n.z - session.z;
                if (dx * dx + dz * dz > PATROL_BROADCAST_RADIUS * PATROL_BROADCAST_RADIUS) {
                    continue;
                }
                if (n.route.size() < 2 && !st.world.legs.containsKey(n.objectId)) {
                    continue;   // 无巡逻路线：仅当有 AI 追击/回位腿时广播移动
                }
                SceneWorld.PendingLeg leg = st.world.legs.get(n.objectId);
                if (leg == null) {
                    continue;
                }
                Long sentAt = st.legSent.get(n.objectId);
                if (sentAt != null && sentAt == leg.startedMs) {
                    continue;
                }
                fx.send(S2CPackets.serverMoving(n.objectId, n.ownerId,
                        leg.x, leg.y, leg.z, leg.orient));
                st.legSent.put(n.objectId, leg.startedMs);
                long remain = Math.max(300, leg.arriveAtMs - now);
                st.pendingArrive.put(n.objectId,
                        new PendingArrive(now + remain, leg.x, leg.y, leg.z, leg.orient));
                if (++moved >= PATROL_BROADCAST_CAP) {
                    break;
                }
            }
        }
    }

    private static float readF32(byte[] b, int off) {
        return Float.intBitsToFloat(leU32(b, off));
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
