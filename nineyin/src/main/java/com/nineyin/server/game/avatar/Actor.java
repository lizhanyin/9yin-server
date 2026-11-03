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
    // SpeedRatio 参考服 exe 出招面板实发 0（127-0-0-1-50768）；旧 Go 源码 newPlayer 的
    // 100 已过时，出招重推 100 会干扰客户端移动态。
    private int speedRatio = 0;
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
     * Go UseQingGong（无消耗系数档）：成功扣点，返回实付点数，付不起返回 -1。
     * 2026-11-03 去掉「余量 &lt;50 自动回满」本地规则：官方轻功回复是分档慢回复
     * （101-150 每 3s +10 / 51-100 +5 / 0-50 +3，玩家资料 0→150 需 96s 实测），
     * 回满会让消耗经济失去意义。
     */
    public int useQingGong(int baseCost) {
        if (baseCost < 0) {
            return -1;
        }
        if (qingGong < baseCost) {
            return -1;
        }
        qingGong -= baseCost;
        return baseCost;
    }

    /**
     * 官方分档回复一步（玩家资料实测口径，2026-11-03）：按回复前余量取档——
     * 101..上限 → +10、51..100 → +5、0..50 → +3；封顶 max+Add。返回是否有变化。
     */
    public boolean recoverQingGongTiered() {
        int cap = maxQingGong + maxQingGongAdd;
        if (qingGong >= cap) {
            return false;
        }
        int gain = qingGong >= 101 ? 10 : qingGong >= 51 ? 5 : 3;
        qingGong = Math.min(cap, qingGong + gain);
        return true;
    }

    /**
     * 持续态扣点（qinggong_9 轻身缓降 CCQGP 按秒消耗）：不触发 &lt;50 自动回满
     * （回满是跳跃动作语义，缓降要真实耗尽触发摘除）。付不起返回 -1。
     */
    public int drainQingGong(int cost) {
        if (cost < 0 || qingGong < cost) {
            return -1;
        }
        qingGong -= cost;
        return cost;
    }

    /** 重生（NPC 语义）：满血满淤血回 NORMAL；复活是显式状态迁移，不走治疗。 */
    public void respawnFull() {
        hp = maxHp;
        hitHp = maxHp;
        mp = maxMp;
        logicState = LOGIC_STATE_NORMAL;
    }

    /**
     * Go RestoreWhileSit：打坐（LS_SITCROSS）每秒权威结算一步。死亡/非坐姿不恢复
     * ——UI 的 HPUpSpeed 等速率字段仅展示，真实恢复只发生在服务器 tick。
     * 淤血条（HitHP）不低于回复后的 HP。
     *
     * @return 资源是否发生变化（调用方据此发 0x10）
     */
    public boolean restoreWhileSit(int hpAmount, int mpAmount) {
        if (hpAmount < 0 || mpAmount < 0) {
            return false;
        }
        if (logicState != LOGIC_STATE_SITCROSS || hp <= 0) {
            return false;
        }
        boolean changed = false;
        if (hpAmount > 0 && hp < maxHp) {
            hp = Math.min(maxHp, hp + hpAmount);
            if (hitHp < hp) {
                hitHp = hp;
            }
            changed = true;
        }
        if (mpAmount > 0 && mp < maxMp) {
            mp = Math.min(maxMp, mp + mpAmount);
            changed = true;
        }
        return changed;
    }
}
