package com.nineyin.game;

import com.nineyin.login.LoginSession;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.MenuPackets;
import com.nineyin.protocol.NpcPackets;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.NpcTemplate;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import com.nineyin.server.GameScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 暮色之村（clone003）剧情验证流程（GM「暮色剧情验证」按钮的服务端实现）：
 * - stages：进入 clone003，全量摆位按 creator 固定坐标下发（NpcProgress 波次全部揭示）
 * - mechanism：庙内生成钥匙箱（BoxClone003001），点击出帧对话菜单，
 *   真实确认 funcId=860002105（拿出钥匙）后箱子消失并提示获得旧钥匙
 * - npc_move：生成躲起来的村民（funnpcclone003111），点击确认 funcId=860002106
 *   （跟我走）后按服务端移动帧走向护送村民结束点
 * 坐标均取自 share/creator/npc_creator/clone003 的原版数据。
 */
public final class TwilightVerify {

    private static final Logger log = LoggerFactory.getLogger(TwilightVerify.class);

    public static final String SCENE_CONFIG = "ini\\scene\\clone003";
    public static final String SCENE_RESOURCE = "clone003";

    /** 进村出生点：村口大门（BlockClone003001 funcnpc.xml）。 */
    public static final float[] ENTRANCE = {129.595f, 11.810f, -92.450f, 5.043f};
    /** 钥匙箱生成点：庙内箱子机关（MacClone003003 itemnpc.xml）。 */
    public static final float[] BOX_POS = {-79.063f, 36.716f, -35.962f, 1.620f};
    /** 躲起来的村民生成点（funnpcclone003111 funcnpc.xml）。 */
    public static final float[] VILLAGER_POS = {32.571f, 23.020f, 31.887f, 0f};
    /** 护送村民结束点（TriggerNpc003006 funcnpc.xml）。 */
    public static final float[] ESCORT_END = {213.687f, 5.720f, -144.375f, -1.415f};

    public static final String BOX_CONFIG = "BoxClone003001";
    public static final String VILLAGER_CONFIG = "funnpcclone003111";

    public static final String TAG_BOX = "twilight_box";
    public static final String TAG_VILLAGER = "twilight_villager";

    /** 帧对话自定义 mark（funcId = 860000000 + mark，走 MenuPackets 默认映射）。 */
    public static final int MARK_TAKE_KEY = 0x2101;
    public static final int MARK_FOLLOW_ME = 0x2102;
    public static final int FUNC_TAKE_KEY = 860000000 + MARK_TAKE_KEY;
    public static final int FUNC_FOLLOW_ME = 860000000 + MARK_FOLLOW_ME;

    /** clone003 数据中的最大 NpcProgress 波次（锦衣卫刀）。 */
    public static final int MAX_PROGRESS = 7;

    private static final float VILLAGER_SPEED = 4.0f;

    private TwilightVerify() {
    }

    public static boolean inScene(LoginSession session) {
        return SCENE_RESOURCE.equalsIgnoreCase(session.spawnResource);
    }

    /** 不在暮色之村则切图进村（异步重进链完成后自动下发摆位）。 */
    public static void ensureScene(FxSession fx, LoginSession session, SceneFlow.SceneState st,
                                   SceneSwitcher switcher) {
        if (inScene(session)) {
            return;
        }
        switcher.switchScene(fx, session, st, SCENE_CONFIG, SCENE_RESOURCE,
                ENTRANCE[0], ENTRANCE[1], ENTRANCE[2], ENTRANCE[3]);
    }

    /** 生成一个动态 NPC（模板查 hp，缺省 1000）。 */
    public static NpcInstance spawn(SceneManager sceneManager, SceneWorld world,
                                    String configId, float[] pos, String tag) {
        NpcInstance n = new NpcInstance();
        n.configId = configId;
        n.x = n.homeX = pos[0];
        n.y = n.homeY = pos[1];
        n.z = n.homeZ = pos[2];
        n.orient = pos[3];
        n.tag = tag;
        NpcTemplate tmpl = sceneManager.templates().lookup(configId);
        n.maxHp = tmpl.maxHp() > 0 ? tmpl.maxHp() : 1000;
        n.hp = n.maxHp;
        n.attack = tmpl.attack();
        n.defense = tmpl.defense();
        n.accuracy = tmpl.accuracy();
        n.evasion = tmpl.evasion();
        synchronized (world) {
            world.addDynamic(n);
        }
        log.info("twilight spawn {} oid={} tag={} at ({},{},{})", configId, n.objectId, tag,
                n.x, n.y, n.z);
        return n;
    }

    /** 清掉指定 tag 的动态 NPC 并通知客户端移除。 */
    public static void despawnTagged(FxSession fx, SceneFlow.SceneState st, String tag) {
        if (st.world == null) {
            return;
        }
        synchronized (st.world) {
            for (NpcInstance n : st.world.npcs) {
                if (tag.equals(n.tag)) {
                    fx.send(S2CPackets.removeObject(n.objectId, n.ownerId));
                    st.sentIds.remove(n.objectId);
                }
            }
            st.world.removeTagged(tag);
        }
    }

    /** 立即向客户端下发一个 NPC（已进场景且摆位已发过时）。 */
    public static void sendNpc(FxSession fx, SceneFlow.SceneState st, SceneManager sceneManager,
                               NpcInstance n) {
        if (!st.npcsSent || st.awaitingReentryReady || st.sentIds.contains(n.objectId)) {
            return;
        }
        fx.send(NpcPackets.npcAddObject(n, sceneManager.templates()));
        fx.send(S2CPackets.serverLocation(n.objectId, n.ownerId, n.x, n.y, n.z, n.orient));
        st.sentIds.add(n.objectId);
    }

    /** 点击 tagged NPC 时的菜单 mark（null=普通 NPC 走原逻辑）。 */
    public static Integer menuMarkFor(NpcInstance npc) {
        if (npc == null) {
            return null;
        }
        return switch (npc.tag) {
            case TAG_BOX -> MARK_TAKE_KEY;
            case TAG_VILLAGER -> MARK_FOLLOW_ME;
            default -> null;
        };
    }

    /** 菜单项显示文本（文本 ID 协议之外的本地标记，日志/未来扩展用）。 */
    public static String menuText(int mark) {
        return mark == MARK_TAKE_KEY ? "拿出钥匙" : mark == MARK_FOLLOW_ME ? "跟我走" : "";
    }

    /** 帧对话菜单动作分发；返回 false 表示非本流程的 func。 */
    public static boolean handleMenuAction(FxSession fx, LoginSession session, SceneFlow.SceneState st,
                                           SceneManager sceneManager, int oid, int func) {
        if (func != FUNC_TAKE_KEY && func != FUNC_FOLLOW_ME) {
            return false;
        }
        NpcInstance npc = st.world == null ? null : st.world.npcById(oid);
        if (npc == null) {
            return false;
        }
        fx.send(CustomPackets.cinematicClose());
        if (func == FUNC_TAKE_KEY && TAG_BOX.equals(npc.tag)) {
            fx.send(S2CPackets.removeObject(npc.objectId, npc.ownerId));
            st.sentIds.remove(npc.objectId);
            synchronized (st.world) {
                st.world.removeNpc(npc.objectId);
            }
            fx.send(S2CPackets.worldInfo(0, "从满是灰尘的盒子里拿出了一把旧钥匙"));
            log.info("twilight mechanism done: box oid={} removed, old key granted to {}",
                    oid, session.roleName);
            return true;
        }
        if (func == FUNC_FOLLOW_ME && TAG_VILLAGER.equals(npc.tag)) {
            startVillagerEscort(fx, st, npc);
            return true;
        }
        return false;
    }

    /** 村民走向护送结束点：0x20 移动帧 + 到点 0x1F 落位。 */
    private static void startVillagerEscort(FxSession fx, SceneFlow.SceneState st, NpcInstance npc) {
        float dx = ESCORT_END[0] - npc.x;
        float dz = ESCORT_END[2] - npc.z;
        long travelMs = (long) (Math.sqrt(dx * dx + dz * dz) / VILLAGER_SPEED * 1000);
        fx.send(S2CPackets.serverMoving(npc.objectId, npc.ownerId,
                ESCORT_END[0], ESCORT_END[1], ESCORT_END[2], ESCORT_END[3]));
        GameScheduler.schedule(travelMs, () -> {
            npc.x = ESCORT_END[0];
            npc.y = ESCORT_END[1];
            npc.z = ESCORT_END[2];
            npc.orient = ESCORT_END[3];
            fx.send(S2CPackets.serverLocation(npc.objectId, npc.ownerId,
                    npc.x, npc.y, npc.z, npc.orient));
            log.info("twilight villager oid={} arrived escort end ({},{},{}) after {}ms",
                    npc.objectId, npc.x, npc.y, npc.z, travelMs);
        });
        log.info("twilight villager oid={} following player to escort end, {}ms",
                npc.objectId, travelMs);
    }
}
