package com.nineyin.game;

import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗互播帧断言（第二批）：peerCombatUpdate 子集白名单、
 * msg20 nativeSkillAction target oid 参数化、STATE 双发帧构造。
 */
class CombatVisibilityTest {

    private static final int OID = 500001;
    private static final int OWNER = S2CPackets.PLAYER_OWNER_ID;

    // ---------- peerCombatUpdate：头结构 + 属性白名单 ----------

    @Test
    void peerCombatUpdateHeaderCarriesAssignedOid() {
        PlayerActor actor = new PlayerActor();
        byte[] f = actor.peerCombatUpdate(OID, OWNER);
        assertEquals(0x10, f[0] & 0xFF, "批量属性帧 opcode");
        assertEquals(OID, readU32(f, 2));
        assertEquals(OWNER, readU32(f, 6));
        int count = readU16(f, 10);
        assertTrue(count >= 8, "子集至少 8 项（血量/瘀血/死亡/逻辑态/技能系）");
    }

    @Test
    void peerCombatUpdateWhitelistExcludesPrivateProps() {
        PlayerActor actor = new PlayerActor();
        byte[] f = actor.peerCombatUpdate(OID, OWNER);
        // 必含（战斗可见性核心）
        for (int idx : new int[]{PropertyId.HP, PropertyId.HP_RATIO, PropertyId.MAX_HP,
                PropertyId.HIT_HP, PropertyId.DEAD, PropertyId.LOGIC_STATE,
                PropertyId.CUR_SKILL_ID}) {
            assertTrue(containsU16(f, idx), "peer 帧应含属性 " + idx);
        }
        // 必不含（私有：货币/轻功点/buff 槽）
        for (int idx : new int[]{PropertyId.CAPITAL_TYPE1, PropertyId.CAPITAL_TYPE0,
                PropertyId.QINGGONG_POINT, PropertyId.MAX_QINGGONG_POINT,
                PropertyId.bufferInfoSlot(1)}) {
            assertFalse(containsU16(f, idx), "peer 帧不得泄露属性 " + idx);
        }
    }

    @Test
    void peerCombatUpdateTracksDamageAndDeath() {
        PlayerActor actor = new PlayerActor();
        byte[] before = actor.peerCombatUpdate(OID, OWNER);
        actor.applyDamage(50);
        byte[] after = actor.peerCombatUpdate(OID, OWNER);
        assertNotEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(after),
                "受击后血量子集帧应变化");
        // 打空血 → DEAD 位进入帧（u8 值 1 紧随 idx 463）
        actor.applyDamage(Integer.MAX_VALUE);
        byte[] dead = actor.peerCombatUpdate(OID, OWNER);
        assertTrue(containsPropByte(dead, PropertyId.DEAD, (byte) 1), "死亡帧 DEAD=1");
    }

    // ---------- msg20：NPC 出招帧 target 参数化 ----------

    @Test
    void nativeSkillActionCarriesTargetOid() {
        byte[] selfView = SceneFlow.nativeSkillAction(900001, 1, "npc_skill_01",
                S2CPackets.PLAYER_OBJECT_ID, OWNER);
        byte[] peerView = SceneFlow.nativeSkillAction(900001, 1, "npc_skill_01", OID, OWNER);
        assertTrue(containsU32(selfView, 900001), "施法者 NPC oid 应在帧内");
        assertTrue(containsU32(peerView, OID), "peer 版 target=分配 oid");
        assertTrue(containsU32(selfView, S2CPackets.PLAYER_OBJECT_ID), "自机版 target=1");
        assertFalse(containsU32(selfView, OID), "自机版不得出现分配 oid");
    }

    // ---------- STATE 双发帧构造 ----------

    @Test
    void stateFrameOidVariantMatchesSelfVersionExceptOid() {
        // 4 参重载与 2 参（oid=1）构造一致性：同 STATE 文本、仅 oid 不同
        byte[] self = S2CPackets.objectPropertyString(PropertyId.STATE, "zhaoshi_a");
        byte[] peer = S2CPackets.objectPropertyString(OID, OWNER, PropertyId.STATE, "zhaoshi_a");
        assertEquals(self.length, peer.length);
        assertEquals(OID, readU32(peer, 2));
        assertEquals(S2CPackets.PLAYER_OBJECT_ID, readU32(self, 2));
    }

    // ---------- 解析辅助 ----------

    private static int readU32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    /** 帧内是否出现 u16 属性索引（白名单断言的烟雾级近似）。 */
    private static boolean containsU16(byte[] b, int v) {
        byte lo = (byte) (v & 0xFF);
        byte hi = (byte) ((v >> 8) & 0xFF);
        for (int i = 0; i + 1 < b.length; i++) {
            if (b[i] == lo && b[i + 1] == hi) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsU32(byte[] b, int v) {
        byte[] pat = {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
        outer:
        for (int i = 0; i + 3 < b.length; i++) {
            for (int j = 0; j < 4; j++) {
                if (b[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** u8 属性形态：u16 idx + u8 value 连续出现。 */
    private static boolean containsPropByte(byte[] b, int idx, byte value) {
        byte lo = (byte) (idx & 0xFF);
        byte hi = (byte) ((idx >> 8) & 0xFF);
        for (int i = 0; i + 2 < b.length; i++) {
            if (b[i] == lo && b[i + 1] == hi && b[i + 2] == value) {
                return true;
            }
        }
        return false;
    }
}
