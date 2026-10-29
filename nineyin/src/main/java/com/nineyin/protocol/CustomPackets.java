package com.nineyin.protocol;

import java.util.List;

/**
 * S2C 自定义消息（TVarList）构造器，对齐 Go encodeServerCustomValues：
 * 外层 0x1E（现代客户端同时接受 0x27 别名），op + u16 参数个数 + (u8 类型 + 值)*。
 * 消息 ID 范围 1..764。
 */
public final class CustomPackets {

    /** TVarList 对象值（低字 objectID、高字 ownerID）。 */
    public record ObjRef(int objectId, int ownerId) {
    }

    /** 宽字符串值（type 7，UTF-16LE）。 */
    public record Wide(String text) {
    }

    private CustomPackets() {
    }

    /** 0x1E | u16 个数 | 值列表。值类型：Integer→2、Long→3、Float→4、Double→5、String→6、Wide→7、ObjRef→8。 */
    public static byte[] intMessage(int messageId, Object... values) {
        PacketWriter w = new PacketWriter().u8(0x1E).u16(values.length + 1);
        cvI32(w, messageId);
        for (Object v : values) {
            appendValue(w, v);
        }
        return w.toByteArray();
    }

    static void appendValue(PacketWriter w, Object v) {
        if (v instanceof Integer i) {
            cvI32(w, i);
        } else if (v instanceof Long l) {
            w.u8(3).i64(l);
        } else if (v instanceof Float f) {
            w.u8(4).f32(f);
        } else if (v instanceof Double d) {
            w.u8(5).f64(d);
        } else if (v instanceof String s) {
            w.u8(6);
            byte[] b = FxText.narrow(s);
            w.u32(b.length + 1).raw(b).u8(0);
        } else if (v instanceof Wide wide) {
            w.u8(7);
            S2CPackets.appendUcs2(w, wide.text());
        } else if (v instanceof ObjRef ref) {
            w.u8(8).u32(ref.objectId()).u32(ref.ownerId());
        } else if (v instanceof int[] pair && pair.length == 2) {
            w.u8(8).u32(pair[0]).u32(pair[1]);
        } else {
            throw new IllegalArgumentException("unsupported custom value: " + v);
        }
    }

    private static void cvI32(PacketWriter w, int v) {
        w.u8(2).i32(v);
    }

    /** 服务器→客户端技能动作开关（msg 414：reset / switch 1223 / 1）。 */
    public static byte[] skillActionSwitch() {
        return intMessage(414, "reset", 1223, 1);
    }

    /**
     * 九阴志阶段帧（msg 419）：阶段生效即向全服在线连接广播；
     * 登录 UI bootstrap 亦补发当前阶段。值为全服阶段刻度 1..9。
     */
    public static byte[] ninJiuZhiStage(int stage) {
        return intMessage(419, stage);
    }

    /** NPC 头顶气泡（msg 43：对象标识、文本 ID、stage、mode=0 仅气泡）。 */
    public static byte[] npcBubble(int objectId, int ownerId, String textId) {
        return intMessage(43, objectId + "-" + ownerId, textId, 0, 0);
    }

    /** 章节提示卡（msg 383）。 */
    public static byte[] dramaPrompt(int chapter, String chapterName, int explore, int challenge,
                                     String brief, String detail, String title, int pictureCount) {
        return intMessage(383, chapter, chapterName, explore, challenge, brief, detail, title, pictureCount);
    }

    /** 帧对话开帧（msg 4）。 */
    public static byte[] cinematicBegin() {
        return intMessage(4);
    }

    /** 帧对话标题（msg 5）。 */
    public static byte[] cinematicTitle(String titleId) {
        return intMessage(5, titleId);
    }

    /** 帧对话菜单项（msg 6：funcID + 文本 ID）。 */
    public static byte[] cinematicItem(int funcId, String textId) {
        return intMessage(6, funcId, textId);
    }

    /** 帧对话收尾（msg 7：object/owner）。 */
    public static byte[] cinematicEnd(int objectId, int ownerId) {
        return intMessage(7, new ObjRef(objectId, ownerId));
    }

    /** 帧对话关闭（msg 8）。 */
    public static byte[] cinematicClose() {
        return intMessage(8);
    }

    /** 原生字符串动作（0x27 "action"：对象 + 动作名 + "1"）。 */
    public static byte[] nativeAction(int objectId, int ownerId, String action) {
        PacketWriter w = new PacketWriter().u8(0x27).u16(4);
        w.u8(6);
        byte[] name = FxText.narrow("action");
        w.u32(name.length + 1).raw(name).u8(0);
        appendValue(w, new ObjRef(objectId, ownerId));
        appendValue(w, action);
        appendValue(w, "1");
        return w.toByteArray();
    }

    /** 一次性批量发送。 */
    public static void sendAll(com.nineyin.server.FxSession fx, List<byte[]> frames) {
        for (byte[] frame : frames) {
            fx.send(frame);
        }
    }
}
