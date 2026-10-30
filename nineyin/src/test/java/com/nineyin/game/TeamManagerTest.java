package com.nineyin.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 组队存储：teams.json Go 同构持久化 + 建队/离队语义。 */
class TeamManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createLeaveLifecycle() {
        TeamManager mgr = TeamManager.inMemory();
        TeamManager.Team t = mgr.create(7, "队长甲");
        assertEquals(1, t.id);
        assertEquals(1, t.members.size());
        assertEquals("队长甲", t.captainName);
        assertNotNull(mgr.teamOf(7));
        // 重复建队返回原队
        assertEquals(t.id, mgr.create(7, "队长甲").id);
        // 离队：末员离队即解散
        assertTrue(mgr.leave(7));
        assertNull(mgr.teamOf(7));
        assertEquals(0, mgr.count());
    }

    @Test
    void persistsAcrossReload() throws Exception {
        TeamManager mgr = TeamManager.load(tempDir);
        TeamManager.Team t = mgr.create(9, "九爷");
        assertTrue(t.id >= 1);

        TeamManager reopened = TeamManager.load(tempDir);
        TeamManager.Team back = reopened.teamOf(9);
        assertNotNull(back, "teams.json 重读恢复队伍");
        assertEquals("九爷", back.captainName);
        String json = Files.readString(tempDir.resolve("teams.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"next_id\"") && json.contains("\"teams\""),
                "Go 同构顶层字段");
    }
}
