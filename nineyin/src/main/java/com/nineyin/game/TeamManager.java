package com.nineyin.game;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 组队管理：data/teams.json（与参考服 Go teamStore 同构 {next_id, teams:[]}）。
 * 队伍 = 队长 + 成员表（roleId+名字）；跨会话邀请/踢人广播待 op 语义全部钉死后接入。
 */
public final class TeamManager {

    public static final int VIEW_TEAM = 190;

    public record Member(long roleId, String name) {
    }

    public static final class Team {
        public int id;
        public long captainRoleId;
        public String captainName;
        public final List<Member> members = new ArrayList<>();
    }

    private static final Logger log = LoggerFactory.getLogger(TeamManager.class);

    private final Path file;                                   // 可空 = 仅内存（测试）
    private final List<Team> teams = new ArrayList<>();
    private int nextId = 1;

    private TeamManager(Path file) {
        this.file = file;
    }

    public static TeamManager inMemory() {
        return new TeamManager(null);
    }

    public static TeamManager load(Path dataDir) {
        TeamManager mgr = new TeamManager(dataDir.resolve("teams.json"));
        mgr.readJson();
        log.info("team store ready path={} teams={}", mgr.file, mgr.teams.size());
        return mgr;
    }

    /** 310 建队：队长即首个成员；同角色已在队时返回原队。 */
    public synchronized Team create(long roleId, String name) {
        Team existing = teamOf(roleId);
        if (existing != null) {
            return existing;
        }
        Team t = new Team();
        t.id = nextId++;
        t.captainRoleId = roleId;
        t.captainName = name;
        t.members.add(new Member(roleId, name));
        teams.add(t);
        save("create");
        return t;
    }

    /** 312/313 离队：末员离队即解散；返回是否生效。 */
    public synchronized boolean leave(long roleId) {
        Team t = teamOf(roleId);
        if (t == null) {
            return false;
        }
        t.members.removeIf(m -> m.roleId() == roleId);
        if (t.members.isEmpty()) {
            teams.remove(t);
        } else if (t.captainRoleId == roleId) {
            // 队长离队：顺位移交（参考服 ChangeCaptain 语义的最小实现）
            Member next = t.members.get(0);
            t.captainRoleId = next.roleId();
            t.captainName = next.name();
        }
        save("leave");
        return true;
    }

    public synchronized Team teamOf(long roleId) {
        for (Team t : teams) {
            for (Member m : t.members) {
                if (m.roleId() == roleId) {
                    return t;
                }
            }
        }
        return null;
    }

    public synchronized int count() {
        return teams.size();
    }

    // ---------- 持久化 ----------

    private void readJson() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            JSONObject root = JSON.parseObject(text);
            teams.clear();
            nextId = root.getIntValue("next_id");
            JSONArray arr = root.getJSONArray("teams");
            if (arr != null) {
                for (Object o : arr) {
                    JSONObject j = (JSONObject) o;
                    Team t = new Team();
                    t.id = j.getIntValue("id");
                    t.captainRoleId = j.getLongValue("captain_role_id");
                    t.captainName = j.getString("captain_name");
                    JSONArray ms = j.getJSONArray("members");
                    if (ms != null) {
                        for (Object mo : ms) {
                            JSONObject mj = (JSONObject) mo;
                            t.members.add(new Member(mj.getLongValue("role_id"),
                                    mj.getString("name")));
                        }
                    }
                    teams.add(t);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("team store read failed: {}", file, e);
        }
    }

    private void save(String cause) {
        if (file == null) {
            return;
        }
        JSONObject root = new JSONObject(true);
        root.put("next_id", nextId);
        JSONArray arr = new JSONArray();
        for (Team t : teams) {
            JSONObject j = new JSONObject(true);
            j.put("id", t.id);
            j.put("captain_role_id", t.captainRoleId);
            j.put("captain_name", t.captainName);
            JSONArray ms = new JSONArray();
            for (Member m : t.members) {
                JSONObject mj = new JSONObject(true);
                mj.put("role_id", m.roleId());
                mj.put("name", m.name());
                ms.add(mj);
            }
            j.put("members", ms);
            arr.add(j);
        }
        root.put("teams", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(root, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("team store save failed ({}): {}", cause, file, e);
        }
    }
}
