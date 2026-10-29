package com.nineyin.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 已持久化的角色（对应 C++ StoredRole / roles.json 条目）。 */
public final class StoredRole {

    /** 库内角色 ID（get() 回填；0 表示未知/尚未入库）。 */
    private long roleId;
    private String name = "";
    private List<String> appearance = List.of();
    private String sceneConfig = "";
    private String sceneResource = "";
    private float x;
    private float y;
    private float z;
    private float orient;
    /**
     * 原生存档条目中 name/appearance/scene/position 之外的字段
     * （power_level、nei_gong_points、justice、ene/sat 等，键保持原样）。
     * Java 端不解释其含义，读写均原样透传，保证与原生 C++ 版互通不丢数据。
     */
    private Map<String, Object> extra = new LinkedHashMap<>();

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(long roleId) {
        this.roleId = roleId;
    }

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

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
    }
}
