package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.BootstrapPackets;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.InventoryPackets;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.protocol.SkillPackets;
import com.nineyin.scene.SceneManager;
import com.nineyin.server.FxSession;
import com.nineyin.server.GameScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.nineyin.game.SceneFlow.STATE;

/**
 * 场景切换链（对照 Go sceneRuntime）：
 * 0x0C ExitScene → 750ms（旧场景 Lua 异步清理）→ 0x0B EntryScene → 0x0D 玩家重进 →
 * 标记 awaitingReentryReady；目标场景 0x09 ClientReady 后补 0x1F + 权威状态 +
 * 轻功/快捷键/View43/View40 重授（0x0C 会销毁玩家对象附加的记录与视图行）。
 * 场景内瞬移只发 0x1F 并裁剪远端 NPC。
 */
public final class SceneSwitcher {

    /** 0x0C 之后旧场景异步清理完成前的等待（实测客户端 12ms 内到达会丢新玩家）。 */
    public static final long REENTRY_DELAY_MS = 750;

    private static final Logger log = LoggerFactory.getLogger(SceneSwitcher.class);

    private final SceneManager sceneManager;
    private final RoleStore roleStore;
    private final SceneFlow sceneFlow;
    private final NinJiuZhi ninjiuzhi;

    public SceneSwitcher(SceneManager sceneManager, RoleStore roleStore, SceneFlow sceneFlow) {
        this(sceneManager, roleStore, sceneFlow, NinJiuZhi.alwaysOpen());
    }

    public SceneSwitcher(SceneManager sceneManager, RoleStore roleStore, SceneFlow sceneFlow,
                         NinJiuZhi ninjiuzhi) {
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
        this.sceneFlow = sceneFlow;
        this.ninjiuzhi = ninjiuzhi;
    }

    /** 跨图切换：立即 0x0C，750ms 后 0x0B + 0x0D；完成后等目标场景 Ready 补链。
     *  九阴志 scenes 门控未开放的场景拒绝切换（客户端提示 + 日志）。 */
    public void switchScene(FxSession fx, LoginSession login, SceneFlow.SceneState st,
                            String config, String resource,
                            float x, float y, float z, float orient) {
        if (!ninjiuzhi.sceneOpen(resource)) {
            log.warn("switch rejected by ninjiuzhi gating: scene={} stage={}", resource, ninjiuzhi.stage());
            fx.send(S2CPackets.worldInfo(0, "该地区尚未开放（九阴志阶段 " + ninjiuzhi.stage() + "）"));
            return;
        }
        fx.send(new byte[]{0x0C});
        st.awaitingReentryReady = true;
        st.npcsSent = false;
        st.sentIds.clear();
        st.qinggongGranted = false;
        st.progressGranted = false;
        st.world = sceneManager.world(resource);
        login.spawnConfig = config;
        login.spawnResource = resource;
        login.x = x;
        login.y = y;
        login.z = z;
        login.orient = orient;
        GameScheduler.schedule(REENTRY_DELAY_MS, () -> {
            try {
                fx.send(S2CPackets.entryScene(config, resource));
                fx.send(S2CPackets.playerAddObject(login.roleName, login.appearance, x, y, z, orient));
                log.info("re-entry sent scene={} resource={} pos=({},{},{},{})",
                        config, resource, x, y, z, orient);
            } catch (Exception e) {
                log.error("re-entry send failed", e);
            }
        });
        if (login.accountKey.length() > 8) {
            roleStore.updateLocation(login.accountKey, x, y, z, orient);
        }
    }

    /** 目标场景 ClientReady（0x09）后的补链。 */
    public void finishReentry(FxSession fx, LoginSession login, SceneFlow.SceneState st,
                             SceneFlow sceneFlow) {
        st.awaitingReentryReady = false;
        fx.send(S2CPackets.serverLocation(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID,
                login.x, login.y, login.z, login.orient));
        if (login.actor != null) {
            fx.send(login.actor.vitalUpdate());
        }
        restorePlayerAttachments(fx, login, st);
        sceneFlow.sendSceneNpcs(login);
        log.info("reentry finished scene={} npcs resent", login.spawnResource);
    }

    /** 0x0C 销毁玩家对象后必须重授的记录行与视图（九阴志门控与登录链一致）。 */
    public void restorePlayerAttachments(FxSession fx, LoginSession login, SceneFlow.SceneState st) {
        // 参考实现：重进场景后重建 16 背包视图 + 装备纸娃娃 View 1 并重发物品
        CustomPackets.sendAll(fx, InventoryPackets.bagViewCreateFrames());
        CustomPackets.sendAll(fx, InventoryPackets.itemBagFrames());
        if (login.inventoryFrames != null) {
            CustomPackets.sendAll(fx, login.inventoryFrames);
        }
        CustomPackets.sendAll(fx, BootstrapPackets.qinggongGrantFrames());
        CustomPackets.sendAll(fx, BootstrapPackets.functionUnlockFrames(ninjiuzhi::taskOpen));
        CustomPackets.sendAll(fx, BootstrapPackets.neigongViewFrames(
                id -> ninjiuzhi.isOpen(NinJiuZhi.Category.INNER_BOOKS, id)));
        CustomPackets.sendAll(fx, login.roleSkillFrames != null
                ? login.roleSkillFrames : SkillPackets.starterSkillFrames());
        if (login.actor != null) {
            fx.send(BootstrapPackets.sitcrossShortcutRow());
            for (PlayerActor.Shortcut sc : login.actor.shortcuts()) {
                fx.send(S2CPackets.shortcutAdd(sc.index(), sc.kind(), sc.id()));
            }
        }
        fx.send(CustomPackets.skillActionSwitch());
        fx.send(CustomPackets.ninJiuZhiStage(ninjiuzhi.stage()));
        st.qinggongGranted = true;
        st.progressGranted = true;
    }

    /** 场景内瞬移：仅 0x1F；裁剪视野外 NPC 并补发新近 NPC。 */
    public void teleportWithinScene(FxSession fx, LoginSession login, SceneFlow.SceneState st,
                                    float x, float y, float z, float orient) {
        if (st.awaitingReentryReady) {
            log.warn("teleport rejected: target scene still loading");
            return;
        }
        login.x = x;
        login.y = y;
        login.z = z;
        login.orient = orient;
        fx.send(S2CPackets.serverLocation(S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID,
                x, y, z, orient));
        if (st.world != null) {
            synchronized (st.world) {
                st.sentIds.removeIf(oid -> {
                    var n = st.world.npcById(oid);
                    if (n == null) {
                        return true;
                    }
                    float dx = n.x - x;
                    float dz = n.z - z;
                    boolean far = dx * dx + dz * dz > SceneFlow.VIEW_RADIUS * SceneFlow.VIEW_RADIUS;
                    if (far) {
                        fx.send(S2CPackets.removeObject(oid, n.ownerId));
                    }
                    return far;
                });
            }
        }
        st.lastNpcX = x;
        st.lastNpcZ = z;
        sceneStreamAfterTeleport(fx, st, login);
        if (login.accountKey.length() > 8) {
            roleStore.updateLocation(login.accountKey, x, y, z, orient);
        }
        log.info("teleport pos=({},{},{},{})", x, y, z, orient);
    }

    private void sceneStreamAfterTeleport(FxSession fx, SceneFlow.SceneState st, LoginSession login) {
        if (!st.npcsSent || st.world == null) {
            return;
        }
        int added = 0;
        synchronized (st.world) {
            for (var n : sceneFlow.visibleNpcs(st.world, login.x, login.z)) {
                if (st.sentIds.contains(n.objectId)) {
                    continue;
                }
                float dx = n.x - login.x;
                float dz = n.z - login.z;
                if (dx * dx + dz * dz > SceneFlow.VIEW_RADIUS * SceneFlow.VIEW_RADIUS) {
                    continue;
                }
                fx.send(com.nineyin.protocol.NpcPackets.npcAddObject(n, sceneManager.templates()));
                fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
                st.sentIds.add(n.objectId);
                if (++added >= 50) {
                    break;
                }
            }
        }
    }

    public static SceneFlow.SceneState stateOf(FxSession fx) {
        SceneFlow.SceneState st = fx.channel().attr(STATE).get();
        if (st == null) {
            st = new SceneFlow.SceneState();
            fx.channel().attr(STATE).set(st);
        }
        return st;
    }
}
