package com.nineyin.game.npc;

import com.nineyin.protocol.PropertyValues.Value;

/**
 * 一次 NPC 摆放：解析后的属性 + creator 坐标，对齐 Go npcSpawn。
 * clone() 深拷贝属性/扩展表；synchronizeTransform() 把坐标写回 Pos* 属性。
 */
public final class NpcSpawn {

    public final NpcResolver.ResolvedNpc resolved;
    public float x;
    public float y;
    public float z;
    public float orient;

    public NpcSpawn(NpcResolver.ResolvedNpc resolved, float x, float y, float z, float orient) {
        this.resolved = resolved;
        this.x = x;
        this.y = y;
        this.z = z;
        this.orient = orient;
    }

    public static NpcSpawn fromInstance(NpcResolver.ResolvedNpc resolved, NpcCreator.Transform transform) {
        float orient = transform.ay == null ? 0f : transform.ay;
        return new NpcSpawn(resolved, transform.x, transform.y, transform.z, orient);
    }

    /** Go clone：属性/扩展表独立副本（synchronizeTransform 不污染目录缓存）。 */
    public NpcSpawn cloneSpawn() {
        NpcResolver.ResolvedNpc copy = new NpcResolver.ResolvedNpc(
                resolved.instanceKey, resolved.configId, resolved.scriptClass, resolved.transform);
        copy.properties.putAll(resolved.properties);
        copy.extensions.putAll(resolved.extensions);
        return new NpcSpawn(copy, x, y, z, orient);
    }

    /** Go synchronizeTransform：坐标是运行时状态，写回属性表。 */
    public void synchronizeTransform() {
        resolved.properties.put("PosiX", Value.ofFloat32(x));
        resolved.properties.put("PosiY", Value.ofFloat32(y));
        resolved.properties.put("PosiZ", Value.ofFloat32(z));
        resolved.properties.put("Orient", Value.ofFloat32(orient));
    }
}
