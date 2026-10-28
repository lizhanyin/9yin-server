package com.nineyin.gm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        hub.enqueue(new GmCommand(7, "kick", 0, 0, "", "", "", "", 1));
        hub.enqueue(new GmCommand(7, "give_item", 0, 0, "", "", "", "item_a", 3));
        GmCommand cmd = hub.tryPop(7);
        assertNotNull(cmd);
        assertEquals("kick", cmd.action());
        assertEquals("give_item", hub.tryPop(7).action());
        assertNull(hub.tryPop(7));
        assertNull(hub.tryPop(999));
    }
}
