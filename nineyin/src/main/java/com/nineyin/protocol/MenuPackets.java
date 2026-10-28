package com.nineyin.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 0x1C SERVER_MENU 与帧对话菜单，对齐 Go serverMenu/encodeCinematicTalkMenu：
 * 菜单项 = (u8 类型, u16 mark, u32 UTF-16LE 字节数含 NUL, UTF-16LE + NUL)。
 */
public final class MenuPackets {

    /** 菜单项 mark：服务端本地路由 ID，与旧 Npc_Func 整数命名空间分离。 */
    public static final int MARK_SHOP = 0x1001;
    public static final int MARK_DEPOT = 0x1002;
    public static final int MARK_TRANSPORT = 0x1003;
    public static final int MARK_JOB = 0x1004;
    public static final int MARK_GEM = 0x1005;
    public static final int MARK_GUILD = 0x1006;
    public static final int MARK_HOME = 0x1007;
    public static final int MARK_TASK = 0x1008;

    /** 帧对话 funcID（modernTalkFuncID）：805000000=商店 807000000=仓库 600000000=离开。 */
    public static final int FUNC_TALK_SHOP = 805000000;
    public static final int FUNC_TALK_DEPOT = 807000000;
    public static final int FUNC_TALK_LEAVE = 600000000;

    public record MenuItem(int type, int mark, String content) {
        public MenuItem(int mark, String content) {
            this(0, mark, content);
        }
    }

    private MenuPackets() {
    }

    public static byte[] serverMenu(int objectId, int ownerId, List<MenuItem> items) {
        PacketWriter w = new PacketWriter()
                .u8(0x1C)
                .u32(objectId)
                .u32(ownerId)
                .u16(items.size());
        for (MenuItem item : items) {
            byte[] units = item.content().getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            w.u8(item.type());
            w.u16(item.mark());
            w.u32(units.length + 2);
            w.raw(units);
            w.u16(0);
        }
        return w.toByteArray();
    }

    /** 帧对话帧组：4 开、（可选）5 标题、每项 6（mark→funcID/textID）、7 收尾绑定对象。 */
    public static List<byte[]> cinematicTalk(int objectId, int ownerId, String titleId,
                                             List<Integer> marks) {
        List<byte[]> frames = new ArrayList<>(marks.size() + 3);
        frames.add(CustomPackets.cinematicBegin());
        if (titleId != null && !titleId.isEmpty()) {
            frames.add(CustomPackets.cinematicTitle(titleId));
        }
        for (int mark : marks) {
            frames.add(CustomPackets.cinematicItem(funcIdForMark(mark), textIdForMark(mark)));
        }
        frames.add(CustomPackets.cinematicEnd(objectId, ownerId));
        return frames;
    }

    /** mark → 帧对话 funcID（对齐 Go modernTalkFuncID）。 */
    public static int funcIdForMark(int mark) {
        return switch (mark) {
            case MARK_SHOP -> FUNC_TALK_SHOP;
            case MARK_DEPOT -> FUNC_TALK_DEPOT;
            case MARK_TRANSPORT -> 860000022;
            case MARK_JOB -> 806000036;
            case MARK_GEM -> 806000013;
            case MARK_GUILD -> 809000000;
            case MARK_HOME -> 881000001;
            case MARK_TASK -> 100000001;
            default -> 860000000 + mark;
        };
    }

    /** mark → 客户端文本 ID（对齐 Go modernTalkTextID）。 */
    public static String textIdForMark(int mark) {
        return switch (mark) {
            case MARK_SHOP -> "ui_shop";
            case MARK_DEPOT -> "ui_depot";
            case MARK_TRANSPORT -> "ui_transport";
            case MARK_JOB -> "ui_life_job";
            case MARK_GEM -> "ui_equip_gem";
            case MARK_GUILD -> "ui_guild";
            case MARK_HOME -> "ui_home_point";
            case MARK_TASK -> "ui_form_talk_receive_task";
            default -> "ui_talk";
        };
    }
}
