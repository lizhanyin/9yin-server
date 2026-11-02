package com.nineyin.server.game.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;

import org.junit.jupiter.api.Test;

/**
 * 移动参数 GM setter（Go player_actor.go setMoveSpeed/setGravity 向量）：
 * MoveSpeed/RunSpeed 同值联动、Gravity 独立变更，返回单帧 0x10 属性归档。
 */
class PlayerActorMotionTest {

    private static PlayerActor player() {
        return PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
    }

    /** 0x10 帧头：[0]=opcode [1]=isView [2..5]=objectId [6..9]=ownerId [10..11]=count(LE)。 */
    private static int frameCount(byte[] frame) {
        return (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
    }

    @Test
    void setMoveSpeedLinksRunSpeedAndArchivesBoth() {
        PlayerActor player = player();
        byte[] frame = player.setMoveSpeed(33.5f);
        assertEquals(0x10, frame[0] & 0xFF, "应为普通 0x10 属性归档帧");
        assertEquals(0, frame[1] & 0xFF, "isViewObject=0");
        assertEquals(1, frame[2] & 0xFF, "objectId=1（LE）");
        assertEquals(2, frameCount(frame), "MoveSpeed+RunSpeed 两行");
        PlayerActor.Motion motion = player.motionSnapshot();
        assertEquals(33.5f, motion.moveSpeed, 0f);
        assertEquals(33.5f, motion.runSpeed, 0f, "RunSpeed 须与 MoveSpeed 同值联动");
        assertEquals(9.8f, motion.gravity, 0f, "gravity 不受移动速度影响");
    }

    @Test
    void setGravityOnlyChangesGravity() {
        PlayerActor player = player();
        byte[] frame = player.setGravity(0.5f);
        assertEquals(0x10, frame[0] & 0xFF);
        assertEquals(1, frameCount(frame), "仅 Gravity 一行");
        PlayerActor.Motion motion = player.motionSnapshot();
        assertEquals(0.5f, motion.gravity, 0f);
        assertEquals(5f, motion.moveSpeed, 0f, "默认移动速度不变（2026-11-02 起 5）");
        assertEquals(5f, motion.runSpeed, 0f);
    }

    // ------------------------------------------------------------- 货币/怒气

    @Test
    void silverGuanYinGoldSettersEmitSingleCapitalFrames() {
        PlayerActor player = player();
        player.setSilver(12345);
        assertEquals(12345, player.silver());
        assertEquals(0x10, player.silverUpdate()[0] & 0xFF, "碎银=CapitalType1 单帧 0x10");

        byte[] guanYin = player.setGuanYin(999);
        assertEquals(999, player.guanYin());
        assertEquals(0x10, guanYin[0] & 0xFF, "官银=CapitalType2 单帧 0x10");

        byte[] gold = player.setGold(88);
        assertEquals(88, player.gold());
        assertEquals(0x10, gold[0] & 0xFF, "黄金=CapitalType0 单帧 0x10");
    }

    @Test
    void spSetAndAddSaturateZeroToHundred() {
        PlayerActor player = player();
        assertEquals(100, player.sp(), "默认怒气 100（Go parity）");

        PlayerActor.SPUpdate set = player.setSP(40);
        assertEquals(40, set.sp());
        assertEquals(0x10, set.frame()[0] & 0xFF);
        assertEquals(40, player.sp());

        assertEquals(100, player.addSP(80).sp(), "40+80 饱和到 100");
        assertEquals(0, player.setSP(-5).sp(), "负值钳到 0");
        assertEquals(70, player.addSP(70).sp());
        assertEquals(0, player.addSP(-99).sp(), "70-99 钳到 0");
        assertEquals(100, player.setSP(500).sp(), "超界钳到 100");
    }
}
