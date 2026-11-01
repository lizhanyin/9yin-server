package com.nineyin.server.game.avatar;

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

    // ------------------------------------------------------------------ 战斗一期

    public boolean isDead() {
        return hp <= 0 || logicState == LOGIC_STATE_DIED;
    }

    /**
     * Go ApplyDamage：死亡是状态迁移不是 HP==0 的客户端解释。受击先把淤血条
     * （HitHP）抬到受击前血量；归零即 Died（HP/HitHP 双清零）。
     *
     * @return 状态是否变化（已死/非法伤害返回 false 不结算）
     */
    public boolean applyDamage(int amount) {
        if (amount <= 0 || hp == 0) {
            return false;
        }
        if (hitHp < hp) {
            hitHp = hp;
        }
        hp -= amount;
        if (hp <= 0) {
            hp = 0;
            hitHp = 0;
            logicState = LOGIC_STATE_DIED;
        } else {
            logicState = LOGIC_STATE_FIGHTING;
        }
        return true;
    }

    /** Go SpendMP：出招 MP 权威闸门。 */
    public boolean spendMp(int cost) {
        if (cost < 0 || mp < cost) {
            return false;
        }
        mp -= cost;
        return true;
    }

    /**
     * Go UseQingGong（无消耗系数档）：成功扣点；成功后余量 &lt; 50 自动回满
     * （回满在闸门之后——动作必须先付得起）。返回实付点数，付不起返回 -1。
     */
    public int useQingGong(int baseCost) {
        if (baseCost < 0) {
            return -1;
        }
        if (qingGong < baseCost) {
            return -1;
        }
        qingGong -= baseCost;
        if (qingGong < 50) {
            qingGong = maxQingGong + maxQingGongAdd;
        }
        return baseCost;
    }

    /** 重生（NPC 语义）：满血满淤血回 NORMAL；复活是显式状态迁移，不走治疗。 */
    public void respawnFull() {
        hp = maxHp;
        hitHp = maxHp;
        mp = maxMp;
        logicState = LOGIC_STATE_NORMAL;
    }
}
