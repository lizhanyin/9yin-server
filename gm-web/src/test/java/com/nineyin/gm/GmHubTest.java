package com.nineyin.gm;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmHubTest {

    @Test
    void attachUpdateDetach() {
        GmHub hub = new GmHub();
        GmHub.PlayerSnapshot snap = new GmHub.PlayerSnapshot();
        snap.name = "测试侠";
        hub.attach(1, snap);
        assertEquals(1, hub.onlineCount());
        assertEquals(1, hub.status().connectedClients);

        hub.update(1, s -> {
            s.sceneResource = "city05";
            s.x = 915f;
        });
        assertEquals("city05", hub.get(1).sceneResource);
        assertEquals(915f, hub.get(1).x);
        assertEquals(1, hub.snapshots().size());

        hub.detach(1);
        assertEquals(0, hub.onlineCount());
        assertNull(hub.get(1));
    }

    @Test
    void commandQueue() {
        GmHub hub = new GmHub();
        hub.enqueue(new GmCommand(7, "kick", null));
        JSONObject params = new JSONObject();
        params.put("itemConfig", "item_a");
        params.put("amount", 3);
        hub.enqueue(new GmCommand(7, "add_item", params));
        GmCommand cmd = hub.tryPop(7);
        assertNotNull(cmd);
        assertEquals("kick", cmd.action());
        GmCommand next = hub.tryPop(7);
        assertEquals("add_item", next.action());
        assertEquals("item_a", next.params().getString("itemConfig"));
        assertEquals(3, next.params().getIntValue("amount"));
        assertNull(hub.tryPop(7));
        assertNull(hub.tryPop(999));
    }

    @Test
    void minimalLoginToggle() {
        GmHub hub = new GmHub();
        assertFalse(hub.minimalLogin());
        assertTrue(hub.toggleMinimalLogin());
        assertTrue(hub.minimalLogin());
        assertFalse(hub.toggleMinimalLogin());
        assertFalse(hub.minimalLogin());
    }
}
