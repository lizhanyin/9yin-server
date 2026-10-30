package com.nineyin.game;

/**
 * CustomMsg 参数校验器，对齐参考服（Go）的统一校验层：参考服对每条 custom 消息先按
 * {@code TVarList(NNN, ...)} 校验 msgId 与参数布局（个数 + 类型 + 字面值），不符即拒绝并打
 * {@code <handler> expects TVarList(291, mode), got <vals>}。本类把该规格做成声明式：
 *
 * <pre>{@code
 * // 291 切 PK 模式：单 int 参数
 * TVarList.Result r = TVarList.check(msg, 291, TVarList.intArg("mode"));
 * // 560 充值商店：前两位是字面值（buy expects [560,3,charge_id,nums]）
 * TVarList.check(msg, 560, TVarList.literal(560), TVarList.literal(3),
 *         TVarList.intArg("charge_id"), TVarList.intArg("nums"));
 * }</pre>
 *
 * 校验只管布局；语义（取值范围、状态迁移）仍由各 handler 在通过后自行判断。
 * 错误文本与参考服同构，便于实机日志两边对照钉协议。
 */
public final class TVarList {

    private TVarList() {
    }

    /** 参数类型（int 允许 i32/i64 及可精确取整的浮点枚举值，见 Val#asExactInt）。 */
    public enum Kind {INT, STRING, ANY}

    /** 一个参数位的期望：命名类型位，或必须相等的整数字面值。 */
    public record Arg(String name, Kind kind, int literal, boolean literalSet) {

        static Arg of(String name, Kind kind) {
            return new Arg(name, kind, 0, false);
        }

        static Arg lit(int value) {
            return new Arg(Integer.toString(value), null, value, true);
        }
    }

    /** 校验结果：expected/got 已格式化为参考服日志形态，失败时可直接拼进 "xx expects %s, got %s"。 */
    public record Result(boolean ok, String expected, String got) {
    }

    /** 期望一个整数参数（枚举值发成 double 也接受，与现有 handler 的 asExactInt 口径一致）。 */
    public static Arg intArg(String name) {
        return Arg.of(name, Kind.INT);
    }

    /** 期望一个字符串参数（窄/宽字符串均可）。 */
    public static Arg strArg(String name) {
        return Arg.of(name, Kind.STRING);
    }

    /** 任意类型参数。 */
    public static Arg anyArg(String name) {
        return Arg.of(name, Kind.ANY);
    }

    /** 期望值等于给定整数的字面值位（如 560 消息的自校验头 [560,3,...]）。 */
    public static Arg literal(int value) {
        return Arg.lit(value);
    }

    /**
     * 校验 msg：msgId 相等、参数个数恰好等于规格（参考服 "with no extra values" 语义，
     * 多发也算错）、逐位类型/字面值相符。
     */
    public static Result check(CustomMsg msg, int msgId, Arg... args) {
        String expected = format(msgId, args);
        String got = describe(msg);
        if (msg == null || !msg.ok() || msg.msgId() != msgId) {
            return new Result(false, expected, got);
        }
        if (msg.size() - 1 != args.length) {
            return new Result(false, expected, got);
        }
        for (int i = 0; i < args.length; i++) {
            if (!matches(msg.val(i + 1), args[i])) {
                return new Result(false, expected, got);
            }
        }
        return new Result(true, expected, got);
    }

    private static boolean matches(CustomMsg.Val v, Arg a) {
        if (v == null) {
            return false;
        }
        if (a.literalSet()) {
            return v.asExactInt() && v.intOrZero() == a.literal();
        }
        return switch (a.kind()) {
            case INT -> v.asExactInt();
            case STRING -> v.str != null;
            case ANY -> true;
        };
    }

    /** 期望文本："TVarList(291, mode)" / "TVarList(292)" / "TVarList(560, 560, 3, charge_id, nums)"。 */
    public static String format(int msgId, Arg... args) {
        StringBuilder sb = new StringBuilder(24).append("TVarList(").append(msgId);
        for (Arg a : args) {
            sb.append(", ").append(a.name());
        }
        return sb.append(')').toString();
    }

    /** 实际值摘要（Val.toString 空格连接；单值 48 字符、总长 160 截断，与 SceneFlow 监控日志一致）。 */
    public static String describe(CustomMsg msg) {
        if (msg == null || !msg.ok()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < msg.size() && sb.length() < 160; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String v = msg.val(i).toString();
            if (v.length() > 48) {
                v = v.substring(0, 48) + "…";
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
