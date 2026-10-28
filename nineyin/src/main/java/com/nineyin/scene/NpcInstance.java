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
    public boolean dead;
    public long respawnAtMs;

    // creator 实例级业务覆盖
    public String shopId = "";
    public String depotId = "";
    public String doorPackageId = "";
    public String playerTransferPosition = "";
    public float springRange;
}
