package com.nineyin.db;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号键 → 角色的文件存储（data/roles.json，对齐 C++ JSON 后端语义）。
 * 内存缓存 + 全量落盘；MySQL 后端在 DB 阶段通过 Summer Repository 接入。
 */
public final class RoleStore {

    private static final Logger log = LoggerFactory.getLogger(RoleStore.class);

    private final Path file;
    private final Map<String, StoredRole> roles = new ConcurrentHashMap<>();

    public RoleStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized boolean exists(String accountKey) {
        return roles.containsKey(accountKey);
    }

    public synchronized StoredRole get(String accountKey) {
        return roles.get(accountKey);
    }

    public synchronized void put(String accountKey, StoredRole role) {
        roles.put(accountKey, role);
        save();
    }

    public synchronized void updateLocation(String accountKey,
                                            float x, float y, float z, float orient) {
        StoredRole role = roles.get(accountKey);
        if (role == null) {
            return;
        }
        role.setX(x);
        role.setY(y);
        role.setZ(z);
        role.setOrient(orient);
    }

    public synchronized int size() {
        return roles.size();
    }

    /** 全量落盘（断线/停服时调用）。 */
    public synchronized void flush() {
        if (!roles.isEmpty()) {
            save();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, StoredRole> loaded = JSON.parseObject(
                    json, new TypeReference<Map<String, StoredRole>>() {
                    });
            if (loaded != null) {
                roles.putAll(loaded);
            }
            log.info("role store loaded {} roles from {}", roles.size(), file);
        } catch (Exception e) {
            log.error("role store load failed: {}", file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, StoredRole> snapshot = new HashMap<>(roles);
            Files.writeString(file, JSON.toJSONString(snapshot, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("role store save failed: {}", file, e);
        }
    }
}
