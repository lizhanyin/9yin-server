package com.nineyin.server.game.avatar.skin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.protocol.RecordSchemas;
import com.nineyin.server.protocol.SceneMessages;

/**
 * 风物志皮肤协议帧（V3「风物志挂载」；风物志=装备的皮肤外观，协议侧官方名
 * Card）：
 *
 * <ul>
 *   <li>收集：{@code CardRec[3]} 按位记录（一位一皮肤，32 个/行；serial=皮肤号+1，
 *       与 Task_Completed/引擎 FxGameLogic 位判定同式——皮肤 1=行 0 位 1，皮肤
 *       31=行 0 位 31，皮肤 32=行 1 位 0）——行序走 0x11 追加方言（同
 *       Task_Completed 的既证模式，每帧一行）；</li>
 *   <li>挂载：{@code UseCardRec[2,2]} 两 u16 列（皮肤 ID + 显示类型 0），
 *       追加行；卸下按零基行号 0x12 删行（倒序防重排）；</li>
 *   <li>显示通道：玩家属性 ShowEquipType（byte，2=优先显示皮肤模型）+
 *       CardID（int，当前挂载皮肤）。收录/挂载状态由记录帧本身驱动客户端
 *       CollectCardManager（IsGetCard）与 data_binder 刷新——**不要**发裸值
 *       custom 180：S2C 180 是子操作分发（0 开兑换窗/1 开替换窗/2 开收录窗/
 *       3 收录回执/4 武道购买），裸皮肤号会被当 opt_type 误开窗。</li>
 * </ul>
 */
public final class SkinMessages {

    /** 官方 447 schema 方言下标（0x0A 帧内位置）；名字是客户端记录官方名。 */
    public static final int RECORD_CARD = RecordSchemas.indexByName("CardRec");
    public static final int RECORD_USE_CARD = RecordSchemas.indexByName("UseCardRec");

    /** S2C/C2C custom 180 = CLIENT_CUSTOMMSG_CARD（客户端 on_card_msg 处理器）。 */
    public static final int CUSTOM_MSG_CARD = 180;

    /** C2S 180 子操作（lua64 client_custom_define/form_card/form_collect_card 钉死）。 */
    public static final int CARD_SUB_USE = 0;
    public static final int CARD_SUB_CANCEL = 1;
    public static final int CARD_SUB_COLLECT = 7;

    /** ShowEquipType=2：皮肤模型优先于装备外形显示（GM「风物志卡牌」口径）。 */
    public static final int SHOW_EQUIP_TYPE_SKIN = 2;
    public static final int SHOW_EQUIP_TYPE_NORMAL = 0;

    private SkinMessages() {
    }

    /**
     * 收集位图整推：皮肤号 1..maxId 按 32 位分组，行 0..n-1 显式行号推送
     * （空行也推——整推语义按行号对齐，缺行会错位后续位图）。增量更新须先
     * {@link #collectedClearFrames} 删旧行再整刷（追加方言下重推会追加成新行）。
     */
    public static List<byte[]> collectedFrames(int objectId, int ownerId, Collection<Integer> skinIds) {
        long[] rows = bitmapRows(skinIds);
        List<byte[]> frames = new ArrayList<>(rows.length);
        for (int row = 0; row < rows.length; row++) {
            frames.add(RecordMessages.recordAddInt32Row(objectId, ownerId, RECORD_CARD, row, rows[row]));
        }
        return frames;
    }

    /** 删除已存在的收集位图行（旧行数 0..oldRows-1 倒序，整刷前清场）。 */
    public static List<byte[]> collectedClearFrames(int objectId, int ownerId, int oldRows) {
        List<byte[]> frames = new ArrayList<>(oldRows);
        for (int row = oldRows - 1; row >= 0; row--) {
            frames.add(RecordMessages.recordDelRow(objectId, ownerId, RECORD_CARD, row));
        }
        return frames;
    }

    /** 当前收集集合的位图行数（整刷/清场的行数口径）。 */
    public static int collectedRowCount(Collection<Integer> skinIds) {
        return bitmapRows(skinIds).length;
    }

    /** 已挂载皮肤整推：UseCardRec 行按挂载顺序追加（两 u16：皮肤号 + 显示类型 0）。 */
    public static List<byte[]> mountedFrames(int objectId, int ownerId, List<Integer> mounted) {
        List<byte[]> frames = new ArrayList<>(mounted.size());
        for (int skinId : mounted) {
            frames.add(RecordMessages.recordAddCells(objectId, ownerId, RECORD_USE_CARD,
                    RecordMessages.Cell.ofInt(skinId), RecordMessages.Cell.ofInt(0)));
        }
        return frames;
    }

    /** 卸下全部：按行号倒序 0x12 删行（正序删会即时重排剩余行号）。 */
    public static List<byte[]> unmountAllFrames(int objectId, int ownerId, int rowCount) {
        List<byte[]> frames = new ArrayList<>(rowCount);
        for (int row = rowCount - 1; row >= 0; row--) {
            frames.add(RecordMessages.recordDelRow(objectId, ownerId, RECORD_USE_CARD, row));
        }
        return frames;
    }

    /** ShowEquipType（+CardID 可选）单帧 0x10：皮肤挂载显示开关。 */
    public static byte[] showEquipTypeFrame(int objectId, int ownerId, int showEquipType, int skinId) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(2);
        Props.add(properties, "ShowEquipType", PropertyValues.Value.ofByte(showEquipType));
        Props.add(properties, "CardID", PropertyValues.Value.ofInt32(skinId));
        return SceneMessages.objectProperties(objectId, ownerId, 0, properties);
    }

    /** on_card_msg 通知已删：S2C 180 是子操作分发，收录/挂载由记录帧驱动，裸皮肤号会被当 opt_type 误开窗。 */

    /**
     * 位图行（一位一皮肤，serial=皮肤号+1——与 {@code TaskMessages.completedBit}
     * /引擎 FxGameLogic 位判定同式：皮肤 0=行 0 位 0、皮肤 1=行 0 位 1、皮肤 31=
     * 行 0 位 31、皮肤 32=行 1 位 0；至少一行建立通道）。
     */
    static long[] bitmapRows(Collection<Integer> skinIds) {
        int maxRow = 0;
        for (int skinId : skinIds) {
            if (skinId < 0) {
                continue;
            }
            maxRow = Math.max(maxRow, (skinId + 1) / 32);
        }
        long[] rows = new long[maxRow + 1];
        for (int skinId : skinIds) {
            if (skinId < 0) {
                continue;
            }
            long serial = skinId + 1;
            long row = serial / 32;
            long bit = serial % 32;
            if (bit == 0) {
                row--;
                bit = 32;
            }
            rows[(int) row] |= 1L << (bit - 1);
        }
        return rows;
    }
}
