package com.nineyin.login;

import com.nineyin.game.PlayerActor;
import com.nineyin.server.FxSession;

import java.util.List;

/**
 * 登录会话状态（对应 C++ handle_client 内的 session_* 局部变量）。
 * 进场景后权威战斗/资源状态在 PlayerActor 上（actor），
 * 这里只承载登录链路与位置等会话字段。
 */
public final class LoginSession {

    public final FxSession fx;

    public String accountKey = "";
    public String roleName = "";
    public List<String> appearance = List.of();
    public boolean hasRole;

    public String spawnConfig = "";
    public String spawnResource = "";
    public float x;
    public float y;
    public float z;
    public float orient;

    /**
     * 玩家互播 objectId（SceneFlow 进场景时分配，500001 起避开 NPC 静态段 10001+
     * 与动态段 900001+）。仅用于**其他会话**看到的本玩家；自己看自己恒
     * PLAYER_OBJECT_ID=1（客户端自机渲染/输入绑定依赖）。
     */
    public int playerObjectId;

    /** GM spawn_dummy 注入的假玩家 oid（despawn_dummy 清理用）。 */
    public final java.util.List<Integer> dummyOids = new java.util.ArrayList<>();

    public boolean uiBootstrapped;
    public int timeSeq;
    public final long bootSteadyMs = System.nanoTime() / 1_000_000L;

    public int operateMode;
    public int pingCount;

    /** PK 模式（msgId=291）：0=和平 1=善恶 2=杀戮；权威值随 0x10 PKMode(436) 推送。 */
    public int pkMode;
    /** 警告开关（msgId=292，无参 toggle）。 */
    public boolean warningOn;
    /** PK 保护开关（msgId=293，[type, onoff]）。 */
    public int pkProtectType;
    public boolean pkProtectOn;
    /** 装备显示类型（msgId=61）。 */
    public int showEquipType;

    /** 用户须知签署状态（登录时装载，0=未同意）。 */
    public int termsAgreed;
    public long termsAgreedAtSec;

    /** 本会话已出现的 custom msgId（监控：未识别消息首次出现打 INFO）。 */
    public final java.util.Set<Integer> seenCustomMsgIds = new java.util.HashSet<>();

    /** 权威玩家状态（选角时创建）。 */
    public PlayerActor actor;

    /** 库内角色 ID（StoredRole 回填；0 = 新号/JSON 后端，无库存数据）。 */
    public long roleId;

    /** 本角色的技能 view 帧缓存（首个 ClientReady 构建；0x0C 重进场景复用）。 */
    public List<byte[]> roleSkillFrames;

    /** choose 阶段已整推技能目录（View 40 容器已建，ready 只重推行不重建容器）。 */
    public boolean skillCatalogSent;

    /** 库存物品帧缓存（选角链构建；0x0C 重进场景重发）。 */
    public List<byte[]> inventoryFrames;

    /** 库存活跃状态（msgId=30 移动物品时同步维护；null=无库存数据）。 */
    public List<com.nineyin.db.RoleInventoryStore.Equipped> liveEquipped;
    public List<com.nineyin.db.RoleInventoryStore.Bagged> liveBagged;
    /** 扩容包锚点快照（选角链加载；装备/续期/到期时同步维护）。 */
    public List<com.nineyin.db.RoleInventoryStore.Bagpack> liveBagpacks;

    /**
     * 已绑定物品 uid（首次穿戴时记录，对齐参考服 `bound (first wear)`）。
     * 会话级状态；跨会话/落库持久化待存档格式钉死后接入。
     */
    public final java.util.Set<String> boundUids = new java.util.HashSet<>();

    /**
     * 当前打开的商店快照（SelectFlow.openShop 写入；msgId=70 购买按 page/pos
     * 在此挂单查找——列语义与 game_shop_items 互证：
     * 0=config 1=amount(库存) 2=capital 3=price 5=page 6=position）。
     */
    public com.nineyin.shop.ShopRepository.Shop openShop;

    public LoginSession(FxSession fx) {
        this.fx = fx;
    }
}
