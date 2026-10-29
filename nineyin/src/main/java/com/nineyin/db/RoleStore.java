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

    /**
     * 用户须知签署状态：{agreed(0/1), agreedAtEpochSec(0=未知)}；账号无行按未同意。
     */
    default long[] termsState(String accountKey) {
        return new long[]{0, 0};
    }

    /**
     * 标记账号已同意用户须知（幂等，重复同意不刷新时间；账号无行则补建）。
     * 返回 true = 本次由未同意转为同意。
     */
    default boolean markTermsAgreed(String accountKey) {
        return false;
    }

    /** 重置为未同意（清 agreed_terms 与时间）。 */
    default void resetTermsAgreed(String accountKey) {
    }

    default String backendName() {
        return getClass().getSimpleName();
    }
}
