package com.nineyin.server.game.avatar.mount;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.protocol.RecordSchemas;
import com.nineyin.server.protocol.SceneMessages;

/**
 * 坐骑协议帧（客户端取证，form_new_allmount / form_main_shortcut_ride）：
 *
 * <ul>
 *   <li>拥有集合：{@code NewRideRec} 官方列型 [u32,u32,f64,u8]（线型 3/3/6/1）追加行
 *       （col0=坐骑包 StaticID、col1=UsedDate、col2=f64 ValidTime（库内既有档值 30，
 *       官方单位待抓包）、col3&gt;0=当前选中）；增量更新先 0x12 倒序删行再整刷
 *       （同 CardRec 方言）；</li>
 *   <li>骑乘态：0x10 属性族——{@code Mount}(77, string，非空=骑乘中，值
 *       {@code npc\ride_XXX} 引擎按 {@code ini\npc\<Mount>.ini} 加载)、
 *       {@code NewRideID}(1333, 当前坐骑包)、{@code CurRiderType}(82, MountType)；
 *       下马 = Mount 空串 + 双 0。</li>
 * </ul>
 */
public final class MountMessages {

    /** 官方 447 schema 方言下标。 */
    public static final int RECORD_NEW_RIDE = RecordSchemas.indexByName("NewRideRec");

    private MountMessages() {
    }

    /** 拥有集合整推：一行一坐骑（col2=ValidTime、col3=Selected 位）。 */
    public static List<byte[]> ownedFrames(int objectId, int ownerId,
                                           Collection<MountStore.Entry> mounts) {
        List<byte[]> frames = new ArrayList<>(mounts.size());
        for (MountStore.Entry entry : mounts) {
            frames.add(RecordMessages.recordAddCells(objectId, ownerId, RECORD_NEW_RIDE,
                    RecordMessages.Cell.ofInt(entry.packId()),
                    RecordMessages.Cell.ofInt(entry.usedDate()),
                    RecordMessages.Cell.ofDouble(entry.validTime()),
                    RecordMessages.Cell.ofInt(entry.selected() ? 1 : 0)));
        }
        return frames;
    }

    /** 删除已存在的拥有行（旧行数 0..oldRows-1 倒序，整刷前清场）。 */
    public static List<byte[]> ownedClearFrames(int objectId, int ownerId, int oldRows) {
        List<byte[]> frames = new ArrayList<>(oldRows);
        for (int row = oldRows - 1; row >= 0; row--) {
            frames.add(RecordMessages.recordDelRow(objectId, ownerId, RECORD_NEW_RIDE, row));
        }
        return frames;
    }

    /** 骑乘态单帧 0x10：上马（model 非空）或下马（model 空串 + 双 0）。 */
    public static byte[] rideStateFrame(int objectId, int ownerId, String model,
                                        int packId, int riderType) {
        List<com.nineyin.server.protocol.PropertyValues.Indexed> properties = new ArrayList<>(3);
        Props.add(properties, "Mount", com.nineyin.server.protocol.PropertyValues.Value.ofString(
                model == null ? "" : model));
        Props.add(properties, "NewRideID", com.nineyin.server.protocol.PropertyValues.Value.ofInt32(packId));
        Props.add(properties, "CurRiderType", com.nineyin.server.protocol.PropertyValues.Value.ofInt32(riderType));
        return SceneMessages.objectProperties(objectId, ownerId, 0, properties);
    }
}
