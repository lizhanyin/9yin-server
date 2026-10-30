package com.nineyin.login;

import com.nineyin.auth.AccountKeys;
import com.nineyin.db.RoleInventoryStore;
import com.nineyin.db.RoleStore;
import com.nineyin.db.StoredRole;
import com.nineyin.game.NinJiuZhi;
import com.nineyin.game.PlayerActor;
import com.nineyin.game.SceneFlow;
import com.nineyin.protocol.Appearance;
import com.nineyin.protocol.BootstrapPackets;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.FxText;
import com.nineyin.protocol.InventoryPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.RecordId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.protocol.SkillPackets;
import com.nineyin.server.FxC2SHandler;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxSession;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 登录状态机：0x02 登录 → 0x04 角色列表；0x03 → 0x05 剧本；
 * 0x05 建角 → 0x04 单角色 + 落库；0x04 选角 → 进场景链
 * （0x09 属性表 → 0x0A 记录表 → 8 个背包视图 → 0x0B → 0x0D → 0x1F → 权威 0x10）；
 * 0x09 Ready → NPC 下发 + UI bootstrap（轻功/任务解锁/内功/技能/快捷键/开关）。
 * NPC 场景下发挂在 onClientReady 钩子上；0x0C 重进的 Ready 走 reentryHandler。
 */
public final class LoginFlow {

    public static final AttributeKey<LoginSession> SESSION = AttributeKey.valueOf("nineyin.login.session");

    public static final int OP_LOGIN = 0x02;
    public static final int OP_WORLD_INFO = 0x03;
    public static final int OP_CHOOSE_ROLE = 0x04;
    public static final int OP_CREATE_ROLE = 0x05;
    public static final int OP_CLIENT_READY = 0x09;

    /** 初始碎银（Go starter-silver 默认）。 */
    public static final long STARTER_SILVER = 99999;

    /**
     * 单角色技能 view 上限。参考 exe 实测整表 17633 条全发客户端可承受，
     * 上限仅防御异常存档；GM 全技能存档（1.7 万+）不再截断。
     */
    public static final int MAX_ROLE_SKILL_FRAMES = 17664;

    private static final Logger log = LoggerFactory.getLogger(LoginFlow.class);

    private final RoleStore roleStore;
    private final NinJiuZhi ninjiuzhi;
    private final List<Consumer<LoginSession>> readyHooks = new ArrayList<>();
    /** 库存查询（MySQL 后端；null = JSON 后端回退 starter 集合）。 */
    private final RoleInventoryStore inventory;
    /** 0x0C 重进场景后的 Ready 处理（SceneSwitcher.finishReentry）。 */
    private Consumer<FxSession> reentryHandler;

    public LoginFlow(RoleStore roleStore) {
        this(roleStore, NinJiuZhi.alwaysOpen());
    }

    public LoginFlow(RoleStore roleStore, NinJiuZhi ninjiuzhi) {
        this.roleStore = roleStore;
        this.ninjiuzhi = ninjiuzhi;
        this.inventory = RoleInventoryStore.from(roleStore);
    }

    /** ClientReady 后回调（场景阶段用来下发 NPC）。 */
    public void addReadyHook(Consumer<LoginSession> hook) {
        readyHooks.add(hook);
    }

    public void setReentryHandler(Consumer<FxSession> handler) {
        this.reentryHandler = handler;
    }

    public void register(FxOpcodeRouter router) {
        router.register(OP_LOGIN, this::onLogin);
        router.register(OP_WORLD_INFO, this::onWorldInfo);
        router.register(OP_CREATE_ROLE, this::onCreateRole);
        router.register(OP_CHOOSE_ROLE, this::onChooseRole);
        router.register(OP_CLIENT_READY, this::onClientReady);
    }

    public LoginSession sessionOf(FxSession fx) {
        LoginSession session = fx.channel().attr(SESSION).get();
        if (session == null) {
            session = new LoginSession(fx);
            fx.channel().attr(SESSION).set(session);
        }
        return session;
    }

    // ---------- 0x02 ClientLogin ----------

    private void onLogin(FxSession fx, byte[] plain) {
        LoginSession s = sessionOf(fx);
        s.accountKey = AccountKeys.parseLoginAccountKey(plain);
        long[] terms = roleStore.termsState(s.accountKey);
        s.termsAgreed = (int) terms[0];
        s.termsAgreedAtSec = terms[1];
        StoredRole stored = roleStore.get(s.accountKey);
        if (stored != null) {
            s.roleId = stored.getRoleId();
            s.roleName = stored.getName();
            s.appearance = stored.getAppearance();
            s.hasRole = true;
            s.spawnConfig = stored.getSceneConfig();
            s.spawnResource = stored.getSceneResource();
            s.x = stored.getX();
            s.y = stored.getY();
            s.z = stored.getZ();
            s.orient = stored.getOrient();
            fx.send(S2CPackets.loginSuccessOneRole(s.roleName, s.appearance,
                    s.spawnConfig, s.spawnResource, epochSec()));
            log.info("login account={} agreed={} role={} (loaded) ip={}",
                    s.accountKey, s.termsAgreed, s.roleName, fx.remoteAddress());
        } else {
            s.hasRole = false;
            fx.send(S2CPackets.loginSuccessNoRole(epochSec()));
            log.info("login account={} agreed={} no role ip={}",
                    s.accountKey, s.termsAgreed, fx.remoteAddress());
        }
    }

    // ---------- 0x03 ClientMsgWorldInfo ----------

    private void onWorldInfo(FxSession fx, byte[] plain) {
        // 剧本列表按九阴志 scenes 门控（键 bookN），阶段未到的剧本不下发
        fx.send(S2CPackets.openBooksWorldInfo(
                ninjiuzhi.filterOpen(NinJiuZhi.Category.SCENES, S2CPackets.ALL_OPEN_BOOKS)));
    }

    // ---------- 0x05 ClientCreateRole ----------

    private void onCreateRole(FxSession fx, byte[] plain) {
        LoginSession s = sessionOf(fx);
        String name = createRoleName(plain);
        if (name.isEmpty()) {
            name = "LocalHero";
        }
        List<String> appearance = normalizeCreateAppearance(createRoleStrings(plain));
        String book = Appearance.get(appearance, 0, "book1");
        SpawnPoints.SpawnPoint sp = SpawnPoints.forBook(book);
        fx.send(S2CPackets.loginSuccessOneRole(name, appearance, sp.config(), sp.resource(), epochSec()));

        s.roleName = name;
        s.appearance = appearance;
        s.hasRole = true;
        s.spawnConfig = sp.config();
        s.spawnResource = sp.resource();
        s.x = sp.x();
        s.y = sp.y();
        s.z = sp.z();
        s.orient = sp.orient();

        if (s.accountKey.isEmpty() || AccountKeys.UNKNOWN.equals(s.accountKey)) {
            log.warn("cannot save role — account key missing (re-login required)");
        } else {
            StoredRole role = new StoredRole();
            role.setName(name);
            role.setAppearance(appearance);
            role.setSceneConfig(sp.config());
            role.setSceneResource(sp.resource());
            role.setX(sp.x());
            role.setY(sp.y());
            role.setZ(sp.z());
            role.setOrient(sp.orient());
            roleStore.put(s.accountKey, role);
            // 建角当次会话即回填 roleId（MySQL 自增 id；保证 560/30 等按 roleId 记账的
            // 消息不必断线重登才可用。JSON 后端无 id，保持 0）
            StoredRole saved = roleStore.get(s.accountKey);
            if (saved != null) {
                s.roleId = saved.getRoleId();
            }
            log.info("role saved account={} name={} book={} scene={}",
                    s.accountKey, name, book, sp.resource());
        }
    }

    // ---------- 0x04 ClientChooseRole → 进场景链 ----------

    private void onChooseRole(FxSession fx, byte[] plain) {
        LoginSession s = sessionOf(fx);
        if (!s.hasRole) {
            log.warn("ChooseRole without created role");
        }
        String cfg = s.spawnConfig;
        String res = s.spawnResource;
        float px = s.x;
        float py = s.y;
        float pz = s.z;
        float po = s.orient;
        if (cfg.isEmpty()) {
            String book = Appearance.get(s.appearance, 0, "book1");
            SpawnPoints.SpawnPoint sp = SpawnPoints.forBook(book);
            cfg = sp.config();
            res = sp.resource();
            px = sp.x();
            py = sp.y();
            pz = sp.z();
            po = sp.orient();
        }
        // 权威玩家状态：名字 + 门派（appearance[8]，对齐 Go roleFaction）+ 初始碎银
        PlayerActor actor = new PlayerActor();
        actor.name = s.roleName.isEmpty() ? "LocalHero" : s.roleName;
        actor.faction = Appearance.faction(s.appearance);
        actor.silver = STARTER_SILVER;
        s.actor = actor;

        fx.send(S2CPackets.propertyTable());
        fx.send(S2CPackets.recordTableEmpty());
        // 17 个视图：8 主页(BaseCap) + 8 扩展页(+扩展包对象) + 装备纸娃娃 View 1
        // （参考 exe 抓包：容器在 0x0B 前创建，物品对象在 0x0B 后下发）
        CustomPackets.sendAll(fx, InventoryPackets.bagViewCreateFrames());
        CustomPackets.sendAll(fx, InventoryPackets.itemBagFrames());
        fx.send(S2CPackets.entryScene(cfg, res));
        fx.send(S2CPackets.playerAddObject(
                s.roleName.isEmpty() ? "LocalHero" : s.roleName, s.appearance, px, py, pz, po));
        fx.send(S2CPackets.serverLocation(
                S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID, px, py, pz, po));
        fx.send(actor.vitalUpdate());
        // 库存物品（导入的原服存档：装备纸娃娃 View 1 + 各背包页，带 0x19 版本帧）
        sendRoleInventory(fx, s);
        log.info("ChooseRole scene={} pos=({},{},{}) faction={} silver={}",
                cfg, px, py, pz, actor.faction, actor.silver);
    }

    // ---------- 0x09 ClientReady → 清界面 + UI bootstrap ----------

    private void onClientReady(FxSession fx, byte[] plain) {
        LoginSession s = sessionOf(fx);
        if (s.spawnResource.isEmpty()) {
            return;
        }
        // 0x0C 重进场景后的 Ready：补 0x1F + 重授记录/视图 + NPC 重发
        SceneFlow.SceneState st = fx.channel().attr(SceneFlow.STATE).get();
        if (st != null && st.awaitingReentryReady) {
            if (reentryHandler != null) {
                reentryHandler.accept(fx);
            }
            return;
        }
        for (Consumer<LoginSession> hook : readyHooks) {
            try {
                hook.accept(s);
            } catch (Exception e) {
                log.error("ready hook error", e);
            }
        }
        fx.send(S2CPackets.clearMenu());
        for (byte[] frame : S2CPackets.clearSelectionFrames()) {
            fx.send(frame);
        }
        if (!s.uiBootstrapped) {
            sendUiBootstrap(fx, s, st);
        }
    }

    private void sendUiBootstrap(FxSession fx, LoginSession s, SceneFlow.SceneState st) {
        fx.send(S2CPackets.objectPropertyInt(223, s.operateMode));        // OperateMode
        // 用户须知签署状态：官服以属性 269-271 驱动客户端协议框（未签署下发 0）
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_LAST_SIGN_TIME,
                (int) Math.min(Integer.MAX_VALUE, s.termsAgreedAtSec)));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_SIGN_COUNT, s.termsAgreed));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.NEW_TERMS_REPAIR_SIGN_COUNT, 0));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.HAVE_MAIN_FORM_STYLES, -1));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.TIME_TICK, clockMinutes()));
        long elapsed = System.nanoTime() / 1_000_000L - s.bootSteadyMs;
        fx.send(S2CPackets.timeSync((int) (elapsed & 0xFFFFFFFFL), s.timeSeq++));
        fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, s.actor != null ? s.actor.logicState : 0));
        // 轻功：QingGongRec 记录行 + View 46（18 分类 + 52 技能实例）
        CustomPackets.sendAll(fx, BootstrapPackets.qinggongGrantFrames());
        // 九阴志当前阶段（与全服广播同帧，登录补发保证晚接入一致）
        fx.send(CustomPackets.ninJiuZhiStage(ninjiuzhi.stage()));
        // 主界面/内功面板前置任务（Task_Accepted + Task_Completed 位图，九阴志 tasks 门控）
        CustomPackets.sendAll(fx, BootstrapPackets.functionUnlockFrames(ninjiuzhi::taskOpen));
        // 内功容器 View 43（九阴志 inner_books 门控）
        CustomPackets.sendAll(fx, BootstrapPackets.neigongViewFrames(
                id -> ninjiuzhi.isOpen(NinJiuZhi.Category.INNER_BOOKS, id)));
        // 技能容器 View 40（库里有修炼进度的角色用真实技能，否则起始集）+ 技能开关 + 立即 0x38
        CustomPackets.sendAll(fx, roleSkillFramesOf(s));
        // 快捷栏：库里有角色存档的用真实栏位（同步进权威行），否则起始集
        sendRoleShortcuts(fx, s);
        fx.send(CustomPackets.skillActionSwitch());
        fx.send(S2CPackets.timeSync(0, 0));
        // 权威状态全量同步（血蓝/轻功点/怒气/碎银/重力速度/内功面板字段）
        if (s.actor != null) {
            fx.send(s.actor.vitalUpdate());
        }
        if (st != null) {
            st.qinggongGranted = true;
            st.progressGranted = true;
        }
        s.uiBootstrapped = true;
        log.info("UI bootstrap mode={} skills={} qinggong={} books={} clockMin={}",
                s.operateMode, s.roleSkillFrames != null
                        ? s.roleSkillFrames.size() - 1 : SkillPackets.STARTER_SKILLS.size(),
                BootstrapPackets.STARTER_QINGGONG_IDS.size(),
                BootstrapPackets.STARTER_BOOKS.size(), clockMinutes());
    }

    /** 库存物品下发（0x0B 进场景之后；帧缓存到会话，0x0C 重进场景重发）。无库存时静默跳过。 */
    private void sendRoleInventory(FxSession fx, LoginSession s) {
        if (inventory == null || s.roleId == 0) {
            return;
        }
        if (s.inventoryFrames != null) {
            CustomPackets.sendAll(fx, s.inventoryFrames);
            return;
        }
        List<RoleInventoryStore.Equipped> equipped = inventory.equipment(s.roleId);
        List<RoleInventoryStore.Bagged> bagged = inventory.bag(s.roleId);
        if (equipped.isEmpty() && bagged.isEmpty()) {
            return;
        }
        s.inventoryFrames = InventoryPackets.inventoryFrames(s.roleId, equipped, bagged,
                com.nineyin.protocol.Appearance.resolveVisual(s.appearance).sex());
        s.liveEquipped = new ArrayList<>(equipped);
        s.liveBagged = new ArrayList<>(bagged);
        CustomPackets.sendAll(fx, s.inventoryFrames);
        log.info("role inventory sent role={} equipped={} bagged={}", s.roleId,
                equipped.size(), bagged.size());
    }

    /**
     * 快捷栏下发：库里有角色栏位（快捷栏位按键.json 导入）则逐条下发并
     * 同步权威行；否则回退起始集（打坐 Ctrl+0 + 心佛掌 + 太极拳古谱）。
     * 0x0C 重进场景由 SceneSwitcher 重放 actor.shortcuts()。
     */
    private void sendRoleShortcuts(FxSession fx, LoginSession s) {
        if (inventory != null && s.roleId != 0) {
            List<RoleInventoryStore.Shortcut> shortcuts = inventory.shortcuts(s.roleId);
            if (!shortcuts.isEmpty()) {
                for (RoleInventoryStore.Shortcut sc : shortcuts) {
                    if (s.actor != null) {
                        s.actor.setShortcut(sc.index(), sc.kind(), sc.id());
                    }
                    fx.send(S2CPackets.shortcutAdd(sc.index(), sc.kind(), sc.id()));
                }
                log.info("role shortcuts from db role={} count={}", s.roleId, shortcuts.size());
                return;
            }
        }
        fx.send(BootstrapPackets.sitcrossShortcutRow());
        if (s.actor != null) {
            s.actor.setShortcut(BootstrapPackets.SITCROSS_SHORTCUT_INDEX, "skill", "zs_default_01");
            for (BootstrapPackets.StarterShortcut sc : BootstrapPackets.starterShortcuts()) {
                s.actor.setShortcut(sc.index(), "skill", sc.id());
                fx.send(S2CPackets.shortcutAdd(sc.index(), "skill", sc.id()));
            }
        }
    }

    /**
     * 角色技能帧：库里有修炼进度则按库生成并缓存（0x0C 重进场景复用），
     * 否则回退 starter 集。GM 全技能存档按 MAX_ROLE_SKILL_FRAMES 截断。
     */
    private List<byte[]> roleSkillFramesOf(LoginSession s) {
        if (s.roleSkillFrames != null) {
            return s.roleSkillFrames;
        }
        if (inventory != null && s.roleId != 0) {
            List<RoleInventoryStore.SkillRow> rows =
                    inventory.skills(s.roleId, MAX_ROLE_SKILL_FRAMES + 1);
            if (!rows.isEmpty()) {
                int total = rows.size();
                if (total > MAX_ROLE_SKILL_FRAMES) {
                    rows = rows.subList(0, MAX_ROLE_SKILL_FRAMES);
                    log.warn("role {} skills truncated {} -> {} (MAX_ROLE_SKILL_FRAMES)",
                            s.roleId, total, MAX_ROLE_SKILL_FRAMES);
                }
                s.roleSkillFrames = InventoryPackets.roleSkillFrames(rows);
                log.info("role skills from db role={} count={}", s.roleId, rows.size());
                return s.roleSkillFrames;
            }
        }
        return SkillPackets.starterSkillFrames();
    }

    // ---------- C2S 解析 ----------

    /** 建角外观归一化（对齐 Go defaultRoleAppearance）：补齐到 9 段并固定门派段 force_yihua。 */
    static List<String> normalizeCreateAppearance(List<String> values) {
        List<String> out = new ArrayList<>(values);
        while (out.size() < 9) {
            out.add("");
        }
        out.set(8, Appearance.DEFAULT_FACTION);
        return out;
    }

    /** 角色名：偏移 5 起 UTF-16LE，最长 72 字节，遇 NUL 截断。 */
    static String createRoleName(byte[] msg) {
        if (msg.length < 7) {
            return "";
        }
        int maxBytes = Math.min(msg.length - 5, 72);
        StringBuilder sb = new StringBuilder();
        for (int i = 5; i + 1 < 5 + maxBytes; i += 2) {
            int u = (msg[i] & 0xFF) | ((msg[i + 1] & 0xFF) << 8);
            if (u == 0) {
                break;
            }
            sb.append((char) u);
        }
        return sb.toString();
    }

    /** 外观串：从 0x49 起扫描 type-6 长度前缀字符串（u32 长度含 NUL，GBK 窄串）。 */
    static List<String> createRoleStrings(byte[] msg) {
        List<String> values = new ArrayList<>();
        int offset = 0x49;
        while (offset + 5 <= msg.length) {
            if (msg[offset] != 6) {
                offset++;
                continue;
            }
            int size = (msg[offset + 1] & 0xFF) | ((msg[offset + 2] & 0xFF) << 8)
                    | ((msg[offset + 3] & 0xFF) << 16) | ((msg[offset + 4] & 0xFF) << 24);
            if (size < 1 || size > 4096 || offset + 5 + size > msg.length
                    || msg[offset + 5 + size - 1] != 0) {
                offset++;
                continue;
            }
            values.add(FxText.narrow(msg, offset + 5, size - 1));
            offset += 5 + size;
        }
        return values;
    }

    static long epochSec() {
        return System.currentTimeMillis() / 1000L;
    }

    static int clockMinutes() {
        LocalDateTime now = LocalDateTime.now();
        return now.getHour() * 60 + now.getMinute();
    }
}
