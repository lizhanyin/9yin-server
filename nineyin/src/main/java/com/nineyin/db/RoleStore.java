package com.nineyin.db;

/** 角色存储接口：JSON 文件后端 / MySQL 双后端实现同一契约。 */
public interface RoleStore {

    boolean exists(String accountKey);

    StoredRole get(String accountKey);

    void put(String accountKey, StoredRole role);

    void updateLocation(String accountKey, float x, float y, float z, float orient);

    int size();

    /** 全量落盘/确保写入（断线、停服时调用；对同步后端可为空操作）。 */
    default void flush() {
    }

    default String backendName() {
        return getClass().getSimpleName();
    }
}
