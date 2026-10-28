package com.nineyin.protocol;

import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.NpcTemplate;
import com.nineyin.scene.NpcTemplateRepository;

/**
 * NPC S2C 包构造：AddObject 0x0D（Type=4，模板表补 resource/name/action/weapon）。
 * Location 0x1F / Moving 0x20 复用 S2CPackets.serverLocation / serverMoving。
 */
public final class NpcPackets {

    private NpcPackets() {
    }

    public static byte[] npcAddObject(NpcInstance n, NpcTemplateRepository templates) {
        NpcTemplate tmpl = templates.lookup(n.configId);
        String cfg = n.configId;
        String resource = tmpl.resource().isEmpty() ? cfg : tmpl.resource();
        String display = tmpl.name().isEmpty() ? cfg : tmpl.name();
        String action = tmpl.actionSet();
        String weapon = tmpl.weaponMode();

        PacketWriter w = new PacketWriter()
                .u8(0x0D)
                .u32(n.objectId)
                .u32(n.ownerId)
                .f32(n.x).f32(n.y).f32(n.z).f32(n.orient);
        int headerSize = 0x3F;
        while (w.size() < headerSize) {
            w.u8(0);
        }
        int count = 0;
        count++; w.u16(181).u8(4);                                  // Type = NPC
        count++; w.u16(174).u8(n.monster ? 2 : 0);                  // Camp
        count++; w.u16(1439).string(cfg);                           // ConfigID
        count++; w.u16(1440).string(resource);                      // Resource
        if (!action.isEmpty()) {
            count++;
            w.u16(405).string(action);                              // ActionSet
        }
        String state = n.bornAction.isEmpty() || "nil".equals(n.bornAction) || "NULL".equals(n.bornAction)
                ? "stand" : n.bornAction;
        count++; w.u16(183).string(state);                          // State（BornAction）
        count++; w.u16(468).i32(100);                               // HPRatio
        count++; w.u16(577).i32(1000);                              // MaxHP
        count++; w.u16(464).i32(1000);                              // HP
        count++; w.u16(1557).i32(n.monster ? 1 : 0);                // NpcType
        count++;
        w.u16(178);
        S2CPackets.appendUcs2(w, display);                          // Name（宽字符）
        count++; w.u16(1559).u8(0);                                 // SelectState
        count++; w.u16(265).i32(0);                                 // CantBeSelect = 可选中
        if (!weapon.isEmpty()) {
            count++;
            w.u16(1597).string(weapon);                             // WeaponMode
        }
        count++; w.u16(1839).u8(1);                                 // Collide
        count++; w.u16(179).f32(1.5f);                              // CollideRadius

        byte[] msg = new byte[w.size()];
        byte[] body = w.toByteArray();
        System.arraycopy(body, 0, msg, 0, body.length);
        msg[0x3D] = (byte) (count & 0xFF);
        msg[0x3E] = (byte) ((count >> 8) & 0xFF);
        return msg;
    }
}
