package com.nineyin.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ready 尾帧族（§3.22 实机钉死）：gamestep 通告、首登出生特效（140）、
 * 内置快捷键方案（195）、VIP/系统开关 reset（414）。帧值布局对照
 * go_capture_shanghong.bin / shop_probe_frames.txt 的逐字节解码。
 */
class BootstrapTailFramesTest {

    @Test
    void ninJiuZhiGameStepLayout() {
        byte[] msg = CustomPackets.ninJiuZhiGameStep(6);
        List<Object> vals = decode(msg);
        assertEquals(Arrays.asList(414, "gamestep", 6, 1), vals);
    }

    @Test
    void firstLoginBornEffectLayout() {
        byte[] msg = CustomPackets.firstLoginBornEffect(BootstrapPackets.BORN_EFFECT_TARGET);
        assertEquals(0x1E, msg[0]);
        assertEquals(Arrays.asList(140, "1-1", "born"), decode(msg));
    }

    @Test
    void shortcutSchemeFramesLayout() {
        List<byte[]> frames = BootstrapPackets.shortcutSchemeFrames();
        assertEquals(2, frames.size());
        List<Object> v1 = decode(frames.get(0));
        List<Object> v2 = decode(frames.get(1));
        assertEquals(195, v1.get(0));
        assertEquals(1, v1.get(1));
        assertEquals(BootstrapPackets.DEFAULT_SHORTCUT_SCHEME_1, v1.get(2));
        assertEquals(2, v2.get(1));
        assertEquals(BootstrapPackets.DEFAULT_SHORTCUT_SCHEME_2, v2.get(2));
        // 两方案仅 3-6 槽顺序不同，长度一致且为参考服原长（658）
        assertEquals(658, ((String) v1.get(2)).length());
        assertEquals(658, ((String) v2.get(2)).length());
    }

    @Test
    void enableVipSwitchFramesLayout() {
        List<byte[]> frames = CustomPackets.enableVipSwitchFrames();
        assertEquals(11, frames.size());
        int[] seen = new int[11];
        for (int i = 0; i < frames.size(); i++) {
            List<Object> vals = decode(frames.get(i));
            assertEquals(Arrays.asList(414, "reset", CustomPackets.ENABLE_VIP_SWITCHES[i], 1), vals);
            seen[i] = (Integer) vals.get(2);
        }
        Arrays.sort(seen);
        assertArrayEquals(new int[]{41, 42, 52, 53, 70, 230, 1021, 1223, 2861, 2878, 2892}, seen);
        // 既有技能动作开关帧（1223）即开关表的成员，字节完全一致
        int idx1223 = -1;
        for (int i = 0; i < CustomPackets.ENABLE_VIP_SWITCHES.length; i++) {
            if (CustomPackets.ENABLE_VIP_SWITCHES[i] == 1223) {
                idx1223 = i;
            }
        }
        byte[] legacy = CustomPackets.skillActionSwitch();
        assertArrayEquals(legacy, frames.get(idx1223));
    }

    /** 解 0x1E 帧的 TVarList 值（i32/串两型足够覆盖本组帧）。 */
    private static List<Object> decode(byte[] msg) {
        assertEquals(0x1E, msg[0]);
        int n = leU16(msg, 1);
        List<Object> vals = new ArrayList<>(n);
        int pos = 3;
        for (int i = 0; i < n; i++) {
            int tag = msg[pos] & 0xFF;
            pos++;
            switch (tag) {
                case 2 -> {
                    vals.add(leU32(msg, pos));
                    pos += 4;
                }
                case 6 -> {
                    int len = leU32(msg, pos);
                    pos += 4;
                    vals.add(new String(msg, pos, len - 1, StandardCharsets.US_ASCII));
                    pos += len;
                }
                default -> throw new AssertionError("unexpected tag " + tag);
            }
        }
        assertEquals(msg.length, pos);
        assertTrue(vals.size() >= 2);
        return vals;
    }

    private static int leU16(byte[] msg, int off) {
        return (msg[off] & 0xFF) | ((msg[off + 1] & 0xFF) << 8);
    }

    private static int leU32(byte[] msg, int off) {
        return (msg[off] & 0xFF) | ((msg[off + 1] & 0xFF) << 8)
                | ((msg[off + 2] & 0xFF) << 16) | ((msg[off + 3] & 0xFF) << 24);
    }
}
