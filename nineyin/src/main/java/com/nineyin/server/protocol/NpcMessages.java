package com.nineyin.server.protocol;

import java.util.List;

import com.nineyin.server.world.npc.NpcSpawn;

/**
 * NPC 场景对象帧：0x0D AddObject / 0x20 Moving / 0x0E Remove / 0x1F Location /
 * 0x10 transform 四属性，逐行移植 Go main.go npcAddObject/serverMoving 家族
 * 与 npc_catalog.go 的 appendNPCProperty（宽字符串 = UTF-16LE）。
 */
public final class NpcMessages {

    /** Go MotionState：九槽 ServerMoving 载荷，尾五槽语义未证，保持零。 */
    public record MotionState(float destX, float destY, float destZ, float destOrient,
                              float motion0, float motion1, float motion2, float motion3, int extra) {
    }

    /** Go motionFromDest。 */
    public static MotionState motionFromDest(float x, float y, float z, float orient) {
        return new MotionState(x, y, z, orient, 0, 0, 0, 0, 0);
    }

    /** Go serverMoving：45 字节定长。 */
    public static byte[] serverMoving(int objectId, int ownerId, MotionState state) {
        PacketWriter writer = PacketWriter.ofCapacity(45);
        return writer.writeU8(0x20)
                .writeU32(objectId).writeU32(ownerId)
                .writeF32(state.destX()).writeF32(state.destY())
                .writeF32(state.destZ()).writeF32(state.destOrient())
                .writeF32(state.motion0()).writeF32(state.motion1())
                .writeF32(state.motion2()).writeF32(state.motion3())
                .writeU32(state.extra())
                .toBytes();
    }

    /**
     * 官方 ServerAllDest（0x21，0x2C 同义）：移动实体的批量广播——
     * `op | u16 count | count×44B`，每条记录 `id u32 | owner u32 | 目的地 x/y/z |
     * orient | 当前速度 | 0 | 0 | 0 | 移动标志 u32`。速度与移动标志驱动客户端
     * 走/停动画（行进 spd=WalkSpeed/RunSpeed 且 flags=1；到达停留 spd=0 且
     * flags=0），官方真机每秒一拍全量广播。0x20 ServerMoving 为 Go 参考服
     * 未验证帧型（官方抓包从未出现），巡逻移动一律走本帧。
     */
    public record AllDestEntry(int objectId, int ownerId,
                               float destX, float destY, float destZ, float destOrient,
                               float speed, int moveFlags) {
    }

    /** 编码一条 0x21 记录（44 字节）。 */
    public static void appendAllDestEntry(PacketWriter writer, AllDestEntry entry) {
        writer.writeU32(entry.objectId()).writeU32(entry.ownerId())
                .writeF32(entry.destX()).writeF32(entry.destY())
                .writeF32(entry.destZ()).writeF32(entry.destOrient())
                .writeF32(entry.speed())
                .writeF32(0f).writeF32(0f).writeF32(0f)
                .writeU32(entry.moveFlags());
    }

    /** ServerAllDest 批量帧（entries 为空返回 null）。 */
    public static byte[] serverAllDest(List<AllDestEntry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        PacketWriter writer = PacketWriter.ofCapacity(3 + entries.size() * 44);
        writer.writeU8(0x21).writeU16(entries.size());
        for (AllDestEntry entry : entries) {
            appendAllDestEntry(writer, entry);
        }
        return writer.toBytes();
    }

    /** Go npcAddObject：0x3f 头（ID/owner/坐标）+ 保留零 + 属性归档，count 回填 @0x3d。 */
    public static byte[] npcAddObject(int objectId, NpcSpawn npc) {
        PacketWriter writer = PacketWriter.ofCapacity(0x120);
        writer.writeU8(0x0D)
                .writeU32(objectId).writeU32(SceneMessages.PLAYER_OWNER_ID)
                .writeF32(npc.x).writeF32(npc.y).writeF32(npc.z).writeF32(npc.orient);
        while (writer.length() < 0x3f) {
            writer.writeU8(0);
        }
        List<PropertyValues.Indexed> properties =
                npc.resolved.orderedProperties(NpcSchema.visibleNpcModernV1());
        for (PropertyValues.Indexed property : properties) {
            writer.appendIndexed(property);
        }
        writer.patchU16(0x3d, properties.size());
        return writer.toBytes();
    }

    /** Go sceneRemoveObject（0x0E，9 字节）。 */
    public static byte[] sceneRemoveObject(int objectId, int ownerId) {
        PacketWriter writer = PacketWriter.ofCapacity(9);
        return writer.writeU8(0x0E).writeU32(objectId).writeU32(ownerId).toBytes();
    }

    /** Go serverLocation（Transform 版，25 字节）。 */
    public static byte[] serverLocation(int objectId, int ownerId,
                                        float x, float y, float z, float orient) {
        PacketWriter writer = PacketWriter.ofCapacity(25);
        return writer.writeU8(0x1F).writeU32(objectId).writeU32(ownerId)
                .writeF32(x).writeF32(y).writeF32(z).writeF32(orient)
                .toBytes();
    }

    private NpcMessages() {
    }
}
