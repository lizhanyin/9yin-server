package com.nineyin.game;

import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.MenuPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.protocol.SkillPackets;
import com.nineyin.scene.DoorRoutes;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxSession;
import com.nineyin.shop.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.nineyin.game.SceneFlow.STATE;

/**
 * 0x07 选中系统（33 字节包：seq@17 oid@21 owner@25 func@29）：
 * - func=0 点击：门 NPC（Door/GotoDoor）执行传送路由；其余 NPC 设 LastObject +
 *   有可玩服务（商店/仓库）时发帧对话菜单（custom 4/5/6/7）。
 * - func!=0 菜单动作：600000000/负数=离开（custom 8 关帧对话），
 *   805000000=开商店（View 61 + 商品行）、807000000=开仓库（View 4）。
 * 对齐 Go objectRequest/selectNPCMenu 的两段式所有权边界。
 */
public final class SelectFlow {

    public static final int OP_SELECT = 0x07;
    private static final float CLEAR_RADIUS = 28f;
    private static final int SHOP_PAGE_SIZE = 10;

    private static final Logger log = LoggerFactory.getLogger(SelectFlow.class);

    private final SceneManager sceneManager;
    private final ShopRepository shopRepository;
    private final SceneSwitcher switcher;
    /** 玩家互播目标选中协助（SceneFlow 注入；null = 无 PvP 选中支持）。 */
    private SceneFlow sceneFlow;

    public SelectFlow(SceneManager sceneManager, ShopRepository shopRepository, SceneSwitcher switcher) {
        this.sceneManager = sceneManager;
        this.shopRepository = shopRepository;
        this.switcher = switcher;
    }

    public void setSceneFlow(SceneFlow sceneFlow) {
        this.sceneFlow = sceneFlow;
    }

    public void register(FxOpcodeRouter router) {
        router.register(OP_SELECT, this::onSelect);
    }

    private SceneFlow.SceneState stateOf(FxSession fx) {
        SceneFlow.SceneState st = fx.channel().attr(STATE).get();
        if (st == null) {
            st = new SceneFlow.SceneState();
            fx.channel().attr(STATE).set(st);
        }
        return st;
    }

    private void onSelect(FxSession fx, byte[] plain) {
        if (plain.length < 29) {
            return;
        }
        LoginSession session = fx.channel().attr(LoginFlow.SESSION).get();
        SceneFlow.SceneState st = stateOf(fx);
        int seq = leU32(plain, 17);
        int oid = leU32(plain, 21);
        int owner = leU32(plain, 25);
        int func = plain.length >= 33 ? leU32(plain, 29) : 0;

        if (oid == 0 || owner == 0) {
            clearSelection(fx, st);
            log.info("Select clear target (ESC/deselect) seq={}", seq);
            return;
        }
        if (func != 0) {
            onMenuAction(fx, session, st, seq, oid, owner, func);
            return;
        }
        onObjectClick(fx, session, st, seq, oid, owner);
    }

    /** 帧对话菜单动作。 */
    private void onMenuAction(FxSession fx, LoginSession session, SceneFlow.SceneState st,
                              int seq, int oid, int owner, int func) {
        if (func == MenuPackets.FUNC_TALK_LEAVE || func < 0) {
            fx.send(CustomPackets.cinematicClose());
            clearSelection(fx, st);
            clearMenuState(st);
            log.info("cinematic talk leave seq={} npc={}", seq, oid);
            return;
        }
        if (func == MenuPackets.FUNC_TALK_SHOP) {
            NpcInstance npc = npcOf(st, oid, owner);
            String shopId = npc != null ? shopIdOf(npc) : "";
            if (shopId.isEmpty()) {
                log.warn("shop menu npc={} has no ShopID", oid);
                return;
            }
            fx.send(CustomPackets.cinematicClose());
            clearMenuState(st);
            openShop(fx, session, shopId);
            return;
        }
        if (func == MenuPackets.FUNC_TALK_DEPOT) {
            fx.send(CustomPackets.cinematicClose());
            clearMenuState(st);
            openDepot(fx);
            return;
        }
        // 未知 func：暮色验证流程（拿出钥匙/跟我走）与副本宝箱开启优先分发，其余清选区
        if (TwilightVerify.handleMenuAction(fx, session, st, sceneManager, oid, func)) {
            clearMenuState(st);
            return;
        }
        if (CloneLoot.handleMenuAction(fx, session, st, sceneManager, oid, func)) {
            clearMenuState(st);
            return;
        }
        clearSelection(fx, st);
        log.info("Select seq={} target={} func={} (unknown menu action)", seq, oid, func);
    }

    /** 点击（func=0）：门路由或选中 + 可玩服务菜单。 */
    private void onObjectClick(FxSession fx, LoginSession session, SceneFlow.SceneState st,
                               int seq, int oid, int owner) {
        // 门：Door/GotoDoor 是直接对象交互，不进 NPC 菜单
        DoorRoutes.Route portal = portalOf(st, oid);
        if (portal != null && session != null) {
            if (portal.sameScene()) {
                switcher.teleportWithinScene(fx, session, st,
                        portal.position().x(), portal.position().y(),
                        portal.position().z(), portal.position().orient());
            } else {
                switcher.switchScene(fx, session, st, portal.config(), portal.resource(),
                        portal.position().x(), portal.position().y(),
                        portal.position().z(), portal.position().orient());
            }
            log.info("portal click npc={} source={} sameScene={}", oid, portal.source(), portal.sameScene());
            return;
        }

        // 先清残留菜单，避免 ESC 被对话 UI 困住
        fx.send(S2CPackets.clearMenu());
        fx.send(SkillPackets.lastObject(1, 1, oid, owner));
        fx.send(S2CPackets.objectPropertyByte(PropertyId.SELECT_STATE, 1));
        st.selectedOid = oid;
        st.selectedOwner = owner;
        st.selectedX = 0;
        st.selectedZ = 0;
        NpcInstance npc = npcOf(st, oid, owner);
        if (npc != null) {
            st.selectedX = npc.x;
            st.selectedZ = npc.z;
        } else if (sceneFlow != null && SceneFlow.isPlayerOid(oid)) {
            // PvP：选中远端玩家——回填实时坐标（28m 自动清除生效）+ 单播真实血条
            sceneFlow.onPlayerSelected(fx, st, oid);
        }
        // 可玩服务（商店/仓库）→ 帧对话菜单；暮色验证 tagged NPC 与副本宝箱强制出专用菜单
        List<Integer> marks = playableMarks(npc);
        Integer verifyMark = TwilightVerify.menuMarkFor(npc);
        if (verifyMark != null) {
            marks = List.of(verifyMark);
        }
        Integer boxMark = CloneLoot.menuMarkFor(npc, sceneManager.templates());
        if (boxMark != null) {
            marks = List.of(boxMark);
        }
        if (!marks.isEmpty()) {
            long now = System.currentTimeMillis();
            if (npc != null && !(st.lastMenuOid == oid && st.lastMenuOwner == owner
                    && now - st.lastMenuAtMs < 1000)) {
                CustomPackets.sendAll(fx, MenuPackets.cinematicTalk(oid, owner, "", marks));
                st.lastMenuOid = oid;
                st.lastMenuOwner = owner;
                st.lastMenuAtMs = now;
                log.info("cinematic talk menu npc={} config={} marks={}", oid, npc.configId, marks);
            }
        } else {
            log.info("Select target={} -> LastObject only (no menu)", oid);
        }
    }

    /** 商店/仓库：仅暴露有服务端数据的服务（Go playableNPCServices）。 */
    private List<Integer> playableMarks(NpcInstance npc) {
        List<Integer> marks = new ArrayList<>(2);
        if (npc == null) {
            return marks;
        }
        String shopId = shopIdOf(npc);
        if (!shopId.isEmpty() && shopRepository.get(shopId) != null) {
            marks.add(MenuPackets.MARK_SHOP);
        }
        String depotId = depotIdOf(npc);
        if (!depotId.isEmpty() && !"0".equals(depotId)) {
            marks.add(MenuPackets.MARK_DEPOT);
        }
        return marks;
    }

    private String shopIdOf(NpcInstance npc) {
        if (!npc.shopId.isEmpty()) {
            return npc.shopId;
        }
        String fromTemplate = sceneManager.templates().lookup(npc.configId).shopId();
        return fromTemplate == null ? "" : fromTemplate;
    }

    private String depotIdOf(NpcInstance npc) {
        if (!npc.depotId.isEmpty()) {
            return npc.depotId;
        }
        return sceneManager.templates().lookup(npc.configId).depotId();
    }

    // 商店视图（form_shop.lua 从 View 61 的 createview 属性读配置）
    private void openShop(FxSession fx, LoginSession session, String shopId) {
        ShopRepository.Shop shop = shopRepository.get(shopId);
        if (shop == null || shop.items().isEmpty()) {
            log.warn("shop {} has no items", shopId);
            return;
        }
        // 会话快照：msgId=70 购买按 page/pos 在此挂单查找（列语义与 game_shop_items
        // 互证：0=config 1=amount(库存) 2=capital 3=price 5=page 6=position）
        session.openShop = shop;
        int pageCount = 1;
        List<ShopRepository.ShopItem> items = new ArrayList<>();
        for (ShopRepository.ShopItem item : shop.items()) {
            int page = colInt(item, 5, 1);
            if (page > pageCount) {
                pageCount = page;
            }
            if (colStr(item, 0).isEmpty()) {
                continue;
            }
            items.add(item);
        }
        // 0x16 视图清除 → 0x15 四头属性（101=ShopID/102=页大小/103=页数/104=类型）——§3.19 实机帧序
        fx.send(SkillPackets.viewClear(SkillPackets.VIEW_SHOP));
        com.nineyin.protocol.PacketWriter create = SkillPackets.createViewHeader(SkillPackets.VIEW_SHOP,
                        100, 4)
                .u16(PropertyId.SHOP_ID).string(shopId)
                .u16(PropertyId.SHOP_PAGE_NUM).i32(SHOP_PAGE_SIZE)
                .u16(PropertyId.SHOP_PAGE_COUNT).i32(pageCount)
                .u16(PropertyId.SHOP_TYPE).i32(shop.type());
        fx.send(create.toByteArray());
        int index = 0;
        for (ShopRepository.ShopItem item : items) {
            index++;
            int amount = colInt(item, 1, 1);
            int page = colInt(item, 5, 1);
            int position = colInt(item, 6, 0);
            int capital = colInt(item, 2, 2);
            int price = colInt(item, 3, 0);
            int objectIndex = (page - 1) * SHOP_PAGE_SIZE + position + 1;
            // §3.19 实机钉死：三价格槽全量下发（107/108/109=SellPrice0/1/2 零填充），
            // 价格落 107+capital（capital1→108、capital2→109；capital≥2 夹 2，与购买侧
            // "3=兑换同走碎银"一致）；110=MaxAmount 恒 99（单次购买上限）
            int slot = Math.min(Math.max(capital, 0), 2);
            com.nineyin.protocol.PacketWriter w = SkillPackets.viewAddHeader(SkillPackets.VIEW_SHOP,
                            objectIndex, 6)
                    .u16(PropertyId.CONFIG_ID).string(colStr(item, 0))
                    .u16(PropertyId.AMOUNT).i32(amount)
                    .u16(PRICE_GOLD).i32(slot == 0 ? price : 0)
                    .u16(PRICE_SILVER).i32(slot == 1 ? price : 0)
                    .u16(PRICE_CARD).i32(slot == 2 ? price : 0)
                    .u16(PropertyId.MAX_AMOUNT).i32(99);
            fx.send(w.toByteArray());
            if (index >= 100) {
                break;
            }
        }
        log.info("shop view opened shop={} items={} pageCount={} type={}",
                shopId, Math.min(items.size(), 100), pageCount, shop.type());
    }

    /** 商店视图价格槽（Go 表段：107=SellPrice0 / 108=SellPrice1 / 109=SellPrice2，按 capital 直映射）。 */
    static final int PRICE_GOLD = 107;
    static final int PRICE_SILVER = 108;
    static final int PRICE_CARD = 109;

    private void openDepot(FxSession fx) {
        fx.send(SkillPackets.createView(SkillPackets.VIEW_DEPOT, 18));
        log.info("depot view opened (capacity=18)");
    }

    private NpcInstance npcOf(SceneFlow.SceneState st, int oid, int owner) {
        if (st.world == null) {
            return null;
        }
        synchronized (st.world) {
            return st.world.npcById(oid);
        }
    }

    private DoorRoutes.Route portalOf(SceneFlow.SceneState st, int oid) {
        if (st.world == null) {
            return null;
        }
        synchronized (st.world) {
            for (SceneWorld.Portal portal : st.world.portals) {
                if (portal.objectId() == oid) {
                    return portal.route();
                }
            }
        }
        return null;
    }

    private void clearMenuState(SceneFlow.SceneState st) {
        st.lastMenuOid = 0;
        st.lastMenuOwner = 0;
        st.lastMenuAtMs = 0;
    }

    private void clearSelection(FxSession fx, SceneFlow.SceneState st) {
        for (byte[] frame : S2CPackets.clearSelectionFrames()) {
            fx.send(frame);
        }
        st.selectedOid = 0;
        st.selectedOwner = 0;
    }

    /** 移动后检查选中目标是否超出 28m，超时清除（由 SceneFlow 移动路径调用）。 */
    public static void autoClearIfNeeded(FxSession fx, LoginSession session, SceneFlow.SceneState st) {
        if (st.selectedOid == 0) {
            return;
        }
        // 选中时未定位到 NPC（selectedX/Z 为 0）则不判距离
        if (st.selectedX == 0 && st.selectedZ == 0) {
            return;
        }
        float dx = session.x - st.selectedX;
        float dz = session.z - st.selectedZ;
        if (dx * dx + dz * dz > CLEAR_RADIUS * CLEAR_RADIUS) {
            for (byte[] frame : S2CPackets.clearSelectionFrames()) {
                fx.send(frame);
            }
            log.info("auto-clear LastObject (out of range) was={}", st.selectedOid);
            st.selectedOid = 0;
            st.selectedOwner = 0;
        }
    }

    private static String colStr(ShopRepository.ShopItem item, int col) {
        List<String> cols = item.columns();
        return col < cols.size() ? cols.get(col) : "";
    }

    private static int colInt(ShopRepository.ShopItem item, int col, int def) {
        try {
            return Integer.parseInt(colStr(item, col));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
