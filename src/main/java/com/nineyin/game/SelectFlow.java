package com.nineyin.game;

import com.nineyin.login.LoginSession;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.protocol.SkillPackets;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.nineyin.game.SceneFlow.STATE;

/**
 * 0x07 选中系统：33 字节包（seq@17 oid@21 owner@25 func@29）。
 * oid=0/owner=0 → 清选区；否则 ClearMenu + LastObject + SelectState=1；
 * func 为菜单动作（600000000 或负数 = 离开类 → 清选）。
 * 不下发 Npc_Func 对话菜单——残留剧情菜单会困住 ESC（对齐 C++ 决策）。
 */
public final class SelectFlow {

    public static final int OP_SELECT = 0x07;
    private static final float CLEAR_RADIUS = 28f;

    private static final Logger log = LoggerFactory.getLogger(SelectFlow.class);

    private final SceneManager sceneManager;

    public SelectFlow(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
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
        // 先清残留菜单，避免 ESC 被对话 UI 困住
        fx.send(S2CPackets.clearMenu());
        fx.send(SkillPackets.lastObject(1, 1, oid, owner));
        fx.send(S2CPackets.objectPropertyByte(PropertyId.SELECT_STATE, 1));
        st.selectedOid = oid;
        st.selectedOwner = owner;
        st.selectedX = 0;
        st.selectedZ = 0;
        if (st.world != null) {
            synchronized (st.world) {
                for (var n : st.world.npcs) {
                    if (n.objectId == oid && n.ownerId == owner) {
                        st.selectedX = n.x;
                        st.selectedZ = n.z;
                        break;
                    }
                }
            }
        }
        if (func != 0) {
            if (func == 600000000 || func < 0) {
                fx.send(SkillPackets.lastObject(1, 1, 0, 0));
                st.selectedOid = 0;
                st.selectedOwner = 0;
            }
            log.info("Select seq={} target={} owner={} func={} (menu action)", seq, oid, owner, func);
        } else {
            log.info("Select target={} -> LastObject only (no menu)", oid);
        }
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

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
