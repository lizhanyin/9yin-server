package com.nineyin.db;

import java.util.List;

/** 已持久化的角色（对应 C++ StoredRole / roles.json 条目）。 */
public final class StoredRole {

    private String name = "";
    private List<String> appearance = List.of();
    private String sceneConfig = "";
    private String sceneResource = "";
    private float x;
    private float y;
    private float z;
    private float orient;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAppearance() {
        return appearance;
    }

    public void setAppearance(List<String> appearance) {
        this.appearance = appearance;
    }

    public String getSceneConfig() {
        return sceneConfig;
    }

    public void setSceneConfig(String sceneConfig) {
        this.sceneConfig = sceneConfig;
    }

    public String getSceneResource() {
        return sceneResource;
    }

    public void setSceneResource(String sceneResource) {
        this.sceneResource = sceneResource;
    }

    public float getX() {
        return x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }

    public float getZ() {
        return z;
    }

    public void setZ(float z) {
        this.z = z;
    }

    public float getOrient() {
        return orient;
    }

    public void setOrient(float orient) {
        this.orient = orient;
    }
}
