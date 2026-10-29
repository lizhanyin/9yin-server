package com.nineyin.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景内 NPC 实例（对应 C++ NpcInstance），object_id 从 10001 递增。
 * hp/maxHp/dead/respawnAtMs 为轻量战斗状态；shopId/doorPackageId 等
 * 为 creator 实例级业务覆盖（模板缺省值在 NpcTemplate 上）。
 */
public final class NpcInstance {

    public int objectId;
    public int ownerId = 1;
    public String configId = "";
    public float x;
    public float y;
    public float z;
    public float orient;
    public float homeX;
    public float homeY;
    public float homeZ;
    public boolean monster;
    public int patrolMode;
    public String pathId = "";
    public String bornAction = "";
    public float patrolSign;
    public final List<Waypoint> route = new ArrayList<>();
    public int routeIdx;
    public int routeDir = 1;

    // 轻量战斗
    public int hp;
    public int maxHp;
    public int attack;        // 外功攻击（模板缺列时 0，走运行时默认值）
    public int defense;       // 外功防御
    public int accuracy;      // 命中值
    public int evasion;       // 闪避值
    public boolean dead;
    public long respawnAtMs;
    public long lastCombatMs;   // 最近一次受击（AI 事件 EventType=6 战斗窗口判据）

    // AI 事件引擎（buff 仅记录 ID；属性加成/视觉帧后续接入）
    public final java.util.Set<String> buffs =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 副本摆位（creator XML）
    public int npcProgress;        // NpcProgress：副本进度门控（0=常驻）
    public long refreshMs = -1;    // RefreshTime：数据重生间隔；-1=未配置走默认
    public String tag = "";        // 动态生成标记（GM 剧情验证流程）

    // creator 实例级业务覆盖
    public String shopId = "";
    public String depotId = "";
    public String doorPackageId = "";
    public String playerTransferPosition = "";
    public float springRange;
}
