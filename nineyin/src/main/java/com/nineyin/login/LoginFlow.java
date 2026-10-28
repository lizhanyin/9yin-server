package com.nineyin.login;

import com.nineyin.auth.AccountKeys;
import com.nineyin.db.RoleStore;
import com.nineyin.db.StoredRole;
import com.nineyin.game.PlayerActor;
import com.nineyin.game.SceneFlow;
import com.nineyin.protocol.Appearance;
import com.nineyin.protocol.BootstrapPackets;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.FxText;
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

    private static final Logger log = LoggerFactory.getLogger(LoginFlow.class);

    private final RoleStore roleStore;
    private final List<Consumer<LoginSession>> readyHooks = new ArrayList<>();
    /** 0x0C 重进场景后的 Ready 处理（SceneSwitcher.finishReentry）。 */
    private Consumer<FxSession> reentryHandler;

    public LoginFlow(RoleStore roleStore) {
        this.roleStore = roleStore;
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
        StoredRole stored = roleStore.get(s.accountKey);
        if (stored != null) {
            s.roleName = stored.getName();
            s.appearance = stored.getAppearance();
            s.hasRole = true;
            s.spawnConfig = stored.getSceneConfig();
            s.spawnResource = stored.getSceneResource();
            s.x = stored.getX();
            s.y = stored.getY();
            s.z = stored.getZ();
            s.orient = stored.getOrient();
            fx.send(S2CPackets.loginSuccessOneRole(s.roleName, s.appearance, epochSec()));
            log.info("login account={} role={} (loaded)", s.accountKey, s.roleName);
        } else {
            s.hasRole = false;
            fx.send(S2CPackets.loginSuccessNoRole(epochSec()));
            log.info("login account={} no role", s.accountKey);
        }
    }

    // ---------- 0x03 ClientMsgWorldInfo ----------

    private void onWorldInfo(FxSession fx, byte[] plain) {
        fx.send(S2CPackets.openBooksWorldInfo());
    }

    // ---------- 0x05 ClientCreateRole ----------

    private void onCreateRole(FxSession fx, byte[] plain) {
        LoginSession s = sessionOf(fx);
        String name = createRoleName(plain);
        if (name.isEmpty()) {
            name = "LocalHero";
        }
        List<String> appearance = createRoleStrings(plain);
        fx.send(S2CPackets.loginSuccessOneRole(name, appearance, epochSec()));

        s.roleName = name;
        s.appearance = appearance;
        s.hasRole = true;
        String book = Appearance.get(appearance, 0, "book1");
        SpawnPoints.SpawnPoint sp = SpawnPoints.forBook(book);
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
        // 权威玩家状态：名字 + 门派（appearance[3]）+ 初始碎银
        PlayerActor actor = new PlayerActor();
        actor.name = s.roleName.isEmpty() ? "LocalHero" : s.roleName;
        actor.faction = Appearance.get(s.appearance, 3, "");
        actor.silver = STARTER_SILVER;
        s.actor = actor;

        fx.send(S2CPackets.propertyTable());
        fx.send(S2CPackets.recordTableEmpty());
        // 四大背包页 + 四个扩展页（客户端 bag UI 容器，必须在 0x0B 前创建）
        CustomPackets.sendAll(fx, BootstrapPackets.starterBagViewFrames());
        fx.send(S2CPackets.entryScene(cfg, res));
        fx.send(S2CPackets.playerAddObject(
                s.roleName.isEmpty() ? "LocalHero" : s.roleName, s.appearance, px, py, pz, po));
        fx.send(S2CPackets.serverLocation(
                S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID, px, py, pz, po));
        fx.send(actor.vitalUpdate());
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
        fx.send(S2CPackets.objectPropertyInt(PropertyId.HAVE_MAIN_FORM_STYLES, -1));
        fx.send(S2CPackets.objectPropertyInt(PropertyId.TIME_TICK, clockMinutes()));
        long elapsed = System.nanoTime() / 1_000_000L - s.bootSteadyMs;
        fx.send(S2CPackets.timeSync((int) (elapsed & 0xFFFFFFFFL), s.timeSeq++));
        fx.send(S2CPackets.objectPropertyByte(PropertyId.LOGIC_STATE, s.actor != null ? s.actor.logicState : 0));
        // 轻功：QingGongRec 记录行 + View 46（18 分类 + 52 技能实例）
        CustomPackets.sendAll(fx, BootstrapPackets.qinggongGrantFrames());
        // 主界面/内功面板前置任务（Task_Accepted + Task_Completed 位图）
        CustomPackets.sendAll(fx, BootstrapPackets.functionUnlockFrames());
        // 内功容器 View 43（6 本初始内功）
        CustomPackets.sendAll(fx, BootstrapPackets.neigongViewFrames());
        // 技能容器 View 40 + 技能开关 + 立即 0x38
        CustomPackets.sendAll(fx, SkillPackets.starterSkillFrames());
        // 快捷键：打坐（Ctrl+0）+ 心佛掌（数字页 2）+ 太极拳古谱（数字页 3）——同步进权威行
        fx.send(BootstrapPackets.sitcrossShortcutRow());
        if (s.actor != null) {
            s.actor.setShortcut(BootstrapPackets.SITCROSS_SHORTCUT_INDEX, "skill", "zs_default_01");
            for (BootstrapPackets.StarterShortcut sc : BootstrapPackets.starterShortcuts()) {
                s.actor.setShortcut(sc.index(), "skill", sc.id());
                fx.send(S2CPackets.shortcutAdd(sc.index(), "skill", sc.id()));
            }
        }
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
                s.operateMode, SkillPackets.STARTER_SKILLS.size(),
                BootstrapPackets.STARTER_QINGGONG_IDS.size(),
                BootstrapPackets.STARTER_BOOKS.size(), clockMinutes());
    }

    // ---------- C2S 解析 ----------

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
