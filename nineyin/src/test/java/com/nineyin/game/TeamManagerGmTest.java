package com.nineyin.game;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GM team 命令的 TeamManager 生命周期（create/invite/join/kick/captain，对齐 menu2 gmTeamCommand 语义）。 */
class TeamManagerGmTest {

    private TeamManager mgr;

    @BeforeEach
    void setup() {
        mgr = TeamManager.inMemory();
    }

    @Test
    void createInviteKickLifecycle() {
        TeamManager.Team t = mgr.create(1, "甲");
        assertEquals(1, t.id);
        assertEquals(t, mgr.create(1, "甲"));   // 幂等返回原队

        assertNull(mgr.invite(1, 2, "乙"));      // 乙入甲队
        assertEquals(2, mgr.teamOf(2).members.size());

        assertNull(mgr.kick(1, 2, "乙"));
        assertNull(mgr.teamOf(2));   // 乙被移出后不在任何队
        assertEquals("队伍中没有 丙", mgr.kick(1, 3, "丙"));
        assertEquals("只有队长可以移出成员", mgr.kick(2, 1, "甲"));  // 乙无队且非队长
    }

    @Test
    void joinCaptainAndDescribe() {
        mgr.create(10, "队长");
        assertNull(mgr.join(10, 20, "队员"));
        assertNotNull(mgr.describe(20));
        assertTrue(mgr.describe(20).contains("队长"));

        assertNull(mgr.changeCaptain(10, 20, "队员"));
        assertEquals(20, mgr.teamOf(10).captainRoleId);
        assertEquals("只有队长可以移交队长", mgr.changeCaptain(10, 20, "队员"));  // 旧队长已无权

        assertTrue(mgr.leave(10));
        assertEquals(20, mgr.teamOf(20).captainRoleId);  // 队长离队移交
        assertTrue(mgr.leave(20));
        assertNull(mgr.describe(20));                    // 全员离队解散
        assertEquals(0, mgr.count());
    }

    @Test
    void inviteRequiresTeam() {
        assertEquals("你当前不在队伍中，先 create 建队", mgr.invite(99, 2, "乙"));
    }
}
