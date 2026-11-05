package com.nineyin.server.game.avatar.wuxue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.util.GoJson;

/**
 * 武学等级存档：{@code data/武学等级.json} 主档（MountStore 同款原子替换写；
 * 无 DB 快照——role_skills 表无 level 列且由外部 dump 维护，不加列）。
 *
 * <p>档值口径：{@code {version, roles:{"<roleId>": {"<skillId>": level}}}}，
 * 仅存 &gt;1 的等级（GM learn_all_taolu 满级档）；登录时
 * {@code LoginFlow.learnedSkillLevels} 按 id 合并（默认 1），
 * forget_all_taolu 清档。
 */
public final class SkillLevelStore {

    private static final int STORE_VERSION = 1;

    private final Path path;
    private final Map<Long, Map<String, Integer>> roles = new TreeMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public static SkillLevelStore open(Path path) {
        SkillLevelStore store = new SkillLevelStore(path.toAbsolutePath().normalize());
        if (!Files.exists(store.path)) {
            return store;
        }
        String text;
        try {
            text = Files.readString(store.path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read wuxue level store", e);
        }
        JSONObject document = JSON.parseObject(text);
        int version = document.getIntValue("version");
        if (version != STORE_VERSION) {
            throw new IllegalStateException("decode wuxue level store: unsupported version " + version);
        }
        JSONObject rolesNode = document.getJSONObject("roles");
        if (rolesNode != null) {
            for (Map.Entry<String, Object> entry : rolesNode.entrySet()) {
                JSONObject levels = rolesNode.getJSONObject(entry.getKey());
                if (levels == null || levels.isEmpty()) {
                    continue;
                }
                Map<String, Integer> parsed = new TreeMap<>();
                for (Map.Entry<String, Object> level : levels.entrySet()) {
                    int value = level.getValue() instanceof Number number
                            ? number.intValue() : Integer.parseInt(String.valueOf(level.getValue()));
                    if (value > 1) {
                        parsed.put(level.getKey(), value);
                    }
                }
                if (!parsed.isEmpty()) {
                    store.roles.put(Long.parseLong(entry.getKey()), parsed);
                }
            }
        }
        return store;
    }

    private SkillLevelStore(Path path) {
        this.path = path;
    }

    /** 单技能档值；无档/无该技能 → 0（调用方默认 1）。 */
    public int level(long roleId, String skillId) {
        if (roleId == 0 || skillId == null) {
            return 0;
        }
        lock.lock();
        try {
            Integer value = roles.getOrDefault(roleId, Map.of()).get(skillId);
            return value == null ? 0 : value;
        } finally {
            lock.unlock();
        }
    }

    /** 整角色覆写（GM learn_all_taolu；空表等价 clear）。 */
    public void apply(long roleId, Map<String, Integer> levels) {
        if (roleId == 0) {
            throw new IllegalArgumentException("wuxue level store: zero role id");
        }
        Map<String, Integer> effective = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : levels.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 1) {
                effective.put(entry.getKey(), entry.getValue());
            }
        }
        lock.lock();
        try {
            if (effective.isEmpty()) {
                roles.remove(roleId);
            } else {
                roles.put(roleId, effective);
            }
            persist();
        } finally {
            lock.unlock();
        }
    }

    /** 清角色档（GM forget_all_taolu）。 */
    public void clear(long roleId) {
        lock.lock();
        try {
            if (roles.remove(roleId) != null) {
                persist();
            }
        } finally {
            lock.unlock();
        }
    }

    /** GoJson MarshalIndent 兼容输出 + 原子替换（roles → roleId → skillId → level）。 */
    private void persist() {
        GoJson.Object document = new GoJson.Object();
        document.field("version", STORE_VERSION);
        if (!roles.isEmpty()) {
            GoJson.Object rolesNode = new GoJson.Object();
            List<Long> ids = new java.util.ArrayList<>(roles.keySet());
            ids.sort(Comparator.naturalOrder());
            for (Long id : ids) {
                GoJson.Object levelsNode = new GoJson.Object();
                for (Map.Entry<String, Integer> level : roles.get(id).entrySet()) {
                    levelsNode.field(level.getKey(), level.getValue().longValue());
                }
                rolesNode.raw(Long.toString(id), levelsNode.render(2));
            }
            document.raw("roles", rolesNode.render(1));
        }
        String text = document.render(0) + "\n";
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = Files.createTempFile(directory, ".wuxue-levels-", ".tmp");
            try {
                Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                Files.deleteIfExists(temporary);
                throw inner;
            }
        } catch (IOException e) {
            throw new IllegalStateException("write wuxue level store", e);
        }
    }
}
