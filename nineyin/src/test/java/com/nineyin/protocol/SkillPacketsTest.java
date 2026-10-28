package com.nineyin.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对齐 C++ encode_create_view / encode_skill_view_add / encode_skill_action_switch。 */
class SkillPacketsTest {

    @Test
    void createViewLayout() {
        byte[] msg = SkillPackets.createView(40, 800);
        assertEquals(7, msg.length);
        assertEquals(0x15, msg[0]);
        assertEquals(40, msg[1]);
        assertEquals(800, (msg[3] & 0xFF) | ((msg[4] & 0xFF) << 8));
        assertEquals(0, msg[5]);
        assertEquals(0, msg[6]);
    }

    @Test
    void skillViewAddLayout() {
        SkillPackets.StarterSkill sit = SkillPackets.STARTER_SKILLS.get(0);
        assertEquals("zs_default_01", sit.configId());
        byte[] msg = SkillPackets.skillViewAdd(1, sit);
        assertEquals(0x18, msg[0]);
        assertEquals(40, leU16(msg, 1));    // view
        assertEquals(1, leU16(msg, 3));     // index
        assertEquals(21, leU16(msg, 5));    // 属性数

        // 第一个属性 ItemType(1887) = 1000
        assertEquals(1887, leU16(msg, 7));
        assertEquals(1000, leU32(msg, 9));
        // 第二个属性 ConfigID(1439) 字符串 "zs_default_01"
        int p = 13;
        assertEquals(1439, leU16(msg, p));
        int len = leU32(msg, p + 2);
        assertEquals(14, len); // 含 NUL
        assertArrayEquals("zs_default_01".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOfRange(msg, p + 6, p + 6 + 13));
        p += 6 + len;

        // 21 个属性逐个数完，验证数量
        int count = 2;
        while (p < msg.length) {
            int idx = leU16(msg, p);
            p += 2;
            switch (idx) {
                case 2234, 1464, 2117 -> p += 1;   // byte 属性
                case 1439 -> {                       // 字符串属性
                    int l = leU32(msg, p);
                    p += 4 + l;
                }
                default -> p += 4;                   // int/float 属性
            }
            count++;
        }
        assertEquals(21, count);
    }

    @Test
    void skillActionSwitchLayout() {
        byte[] msg = SkillPackets.skillActionSwitch();
        assertEquals(0x1E, msg[0]);
        assertEquals(4, msg[1]);   // 参数个数
        assertEquals(0, msg[2]);
        assertEquals(2, msg[3]);   // i32
        assertEquals(414, leU32(msg, 4));
        assertTrue(new String(msg, 10, 5, StandardCharsets.US_ASCII).startsWith("reset")
                || contains(msg, "reset"));
    }

    @Test
    void lastObjectObjectForm() {
        byte[] msg = SkillPackets.lastObject(1, 1, 10002, 1);
        assertEquals(0x10, msg[0]);
        assertEquals(1, leU32(msg, 2));
        assertEquals(1, leU32(msg, 6));
        assertEquals(1, leU16(msg, 10));       // 属性数
        assertEquals(413, leU16(msg, 12));     // LastObject
        assertEquals(10002, leU32(msg, 14));   // 目标 oid（对象值 8 字节）
        assertEquals(1, leU32(msg, 18));
        assertEquals(22, msg.length);
    }

    private static boolean contains(byte[] msg, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= msg.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (msg[i + j] != b[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int leU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
