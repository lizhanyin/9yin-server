package com.nineyin.scene;

import java.util.ArrayList;
import java.util.List;

/** 场景内 NPC 实例（对应 C++ NpcInstance），object_id 从 10001 递增。 */
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
}
