package com.nineyin.scene;

/** 巡逻路点（mappath ini 行：x,y,z,orient,wait）。 */
public record Waypoint(float x, float y, float z, float orient, int waitMs) {

    public static final Waypoint ORIGIN = new Waypoint(0, 0, 0, 0, 200);
}
