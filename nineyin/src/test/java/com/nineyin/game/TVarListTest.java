package com.nineyin.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TVarList 参数校验器：布局（msgId/个数/类型/字面值）与参考服 expects TVarList(NNN, ...) 同构。 */
class TVarListTest {

    private static CustomMsg msg(int msgId, CustomMsg.Val... args) {
        return CustomMsg.of(msgId, args);
    }

    private static CustomMsg.Val intVal(int v) {
        return new CustomMsg.Val(CustomMsg.TYPE_I32, v, 0, 0, null, 0, 0);
    }

    private static CustomMsg.Val strVal(String s) {
        return new CustomMsg.Val(CustomMsg.TYPE_STRING, 0, 0, 0, s, 0, 0);
    }

    private static CustomMsg.Val dblVal(double d) {
        return new CustomMsg.Val(CustomMsg.TYPE_F64, 0, 0, d, null, 0, 0);
    }

    @Test
    void layoutOkAndTypeMismatch() {
        // 291 单 int 参数：通过
        assertTrue(TVarList.check(msg(291, intVal(2)), 291, TVarList.intArg("mode")).ok());
        // 参数类型不符：拒绝，错误文本对齐参考服
        TVarList.Result bad = TVarList.check(msg(291, strVal("peace")), 291, TVarList.intArg("mode"));
        assertFalse(bad.ok());
        assertEquals("TVarList(291, mode)", bad.expected());
        assertEquals("int:291 string:peace", bad.got());
    }

    @Test
    void argCountMustMatchExactly() {
        // 292 无参：恰一个值（msgId）通过，带多余值拒绝（参考服 "with no extra values"）
        assertTrue(TVarList.check(msg(292), 292).ok());
        TVarList.Result extra = TVarList.check(msg(292, intVal(1)), 292);
        assertFalse(extra.ok());
        assertEquals("TVarList(292)", extra.expected());
        // 参数缺失同样拒绝
        assertFalse(TVarList.check(msg(293, intVal(1)), 293,
                TVarList.intArg("type"), TVarList.intArg("onoff")).ok());
        // 293 双 int 通过
        assertTrue(TVarList.check(msg(293, intVal(1), intVal(1)), 293,
                TVarList.intArg("type"), TVarList.intArg("onoff")).ok());
    }

    @Test
    void msgIdMustMatch() {
        assertFalse(TVarList.check(msg(999, intVal(1)), 291, TVarList.intArg("mode")).ok());
    }

    @Test
    void doubleEnumValueAcceptedAsInt() {
        // 现代客户端部分表单把枚举值发成 double（如 faculty 1006/23），与 asExactInt 口径一致
        assertTrue(TVarList.check(msg(61, dblVal(1.0)), 61, TVarList.intArg("type")).ok());
        assertFalse(TVarList.check(msg(61, dblVal(1.5)), 61, TVarList.intArg("type")).ok());
    }

    @Test
    void literalPositionsMustEqual() {
        // 560 充值商店：buy expects [560,3,charge_id,nums]
        TVarList.Arg[] spec = {TVarList.literal(560), TVarList.literal(3),
                TVarList.intArg("charge_id"), TVarList.intArg("nums")};
        assertTrue(TVarList.check(msg(560, intVal(560), intVal(3), intVal(1001), intVal(2)), 560, spec).ok());
        assertFalse(TVarList.check(msg(560, intVal(560), intVal(4), intVal(1001), intVal(2)), 560, spec).ok());
        assertEquals("TVarList(560, 560, 3, charge_id, nums)", TVarList.format(560, spec));
    }

    @Test
    void stringAndAnyArgs() {
        // 200 快捷键：int index + 两个 string
        assertTrue(TVarList.check(msg(200, intVal(5), strVal("skill"), strVal("CS_jh_xfz01")), 200,
                TVarList.intArg("index"), TVarList.strArg("kind"), TVarList.strArg("id")).ok());
        assertFalse(TVarList.check(msg(200, intVal(5), intVal(1), strVal("x")), 200,
                TVarList.intArg("index"), TVarList.strArg("kind"), TVarList.strArg("id")).ok());
        // ANY 兜底
        assertTrue(TVarList.check(msg(1, intVal(5)), 1, TVarList.anyArg("v")).ok());
    }

    @Test
    void describeTruncatesLongValues() {
        CustomMsg m = msg(200, strVal("x".repeat(200)));
        String got = TVarList.describe(m);
        assertTrue(got.length() < 160 + 60, "超长参数必须截断");
        assertTrue(got.endsWith("…"));
        assertEquals("<empty>", TVarList.describe(null));
    }
}
