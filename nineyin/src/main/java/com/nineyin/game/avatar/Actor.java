package com.nineyin.game.avatar;

/**
 * 服务端权威角色战斗状态，对齐 Go internal/entity/actor.go 的登录时刻子集。
 * NewPlayer：MaxHP=1000/MaxMP=500/轻功 150/SpeedRatio 100、满资源。
 */
public final class Actor {

    public static final int LOGIC_STATE_NORMAL = 0;
    public static final int LOGIC_STATE_FIGHTING = 1;
    public static final int LOGIC_STATE_SITCROSS = 102;
    public static final int LOGIC_STATE_DIED = 120;

    public record State(int objectId, int ownerId, String name,
                        int hp, int baseMaxHp, int maxHp, int hitHp,
                        int mp, int baseMaxMp, int maxMp,
                        int qingGongPoint, int maxQingGongPoint, int maxQingGongPointAdd,
                        int speedRatio, int logicState) {
    }

    private final int objectId;
    private final int ownerId;
    private final String name;
    private int hp;
    private int baseMaxHp;
    private int maxHp;
    private int hitHp;
    private int mp;
    private int baseMaxMp;
    private int maxMp;
    private int qingGong;
    private int maxQingGong;
    private int maxQingGongAdd;
    private int speedRatio = 100;
    private int logicState;

    public Actor(int objectId, int ownerId, String name, int maxHp, int maxMp) {
        this.objectId = objectId;
        this.ownerId = ownerId;
        this.name = name;
        this.baseMaxHp = Math.max(maxHp, 1);
        this.maxHp = this.baseMaxHp;
        this.hp = this.baseMaxHp;
        this.hitHp = this.baseMaxHp;
        this.baseMaxMp = Math.max(maxMp, 1);
        this.maxMp = this.baseMaxMp;
        this.mp = this.baseMaxMp;
        this.qingGong = 150;
        this.maxQingGong = 150;
    }

    public static Actor newPlayer(int objectId, int ownerId, String name) {
        return new Actor(objectId, ownerId, name, 1000, 500);
    }

    public State snapshot() {
        return new State(objectId, ownerId, name, hp, baseMaxHp, maxHp, hitHp,
                mp, baseMaxMp, maxMp, qingGong, maxQingGong, maxQingGongAdd,
                speedRatio, logicState);
    }

    /** Go SetDerivedMaxima：内功加成后的真实上限；满资源时同步拉满（协议仍发 base+Add）。 */
    public void setDerivedMaxima(int totalMaxHp, int totalMaxMp) {
        if (totalMaxHp < 1) {
            totalMaxHp = 1;
        }
        if (totalMaxMp < 1) {
            totalMaxMp = 1;
        }
        if (this.maxHp == totalMaxHp && this.maxMp == totalMaxMp) {
            return;
        }
        boolean hpWasFull = this.hp >= this.maxHp;
        boolean mpWasFull = this.mp >= this.maxMp;
        this.maxHp = totalMaxHp;
        this.maxMp = totalMaxMp;
        if (hpWasFull) {
            this.hp = totalMaxHp;
        } else if (this.hp > totalMaxHp) {
            this.hp = totalMaxHp;
        }
        if (this.hitHp > totalMaxHp) {
            this.hitHp = totalMaxHp;
        }
        if (mpWasFull) {
            this.mp = totalMaxMp;
        } else if (this.mp > totalMaxMp) {
            this.mp = totalMaxMp;
        }
    }

    public void setLogicState(int state) {
        this.logicState = state;
    }
}
