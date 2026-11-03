package com.nineyin.server.world.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.nineyin.server.world.npc.PatrolRoute.Waypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * mappath 巡逻路线表测试：三种行格式（前导空列/整数导航点 id/无 id 列）解析、
 * offline 与异场景过滤、相邻点去重、PathId 绑定与 35m 就近吸附。
 * 重点回归：整数 id 列不得读成 X（旧服此 bug 导致 NPC 坐标整体错位穿墙）。
 */
class PatrolRoutesTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void parseWaypointHandlesThreeRowFormats() {
        // 格式1：前导空列（主流）；停留列在朝向后（field5）。
        Waypoint a = PatrolRoutes.parseWaypoint(
                ",458.7,0.629,637.8,-10,5000,stand,nil,walk,nil,1,");
        assertEquals(458.7f, a.x(), 1e-4f);
        assertEquals(0.629f, a.y(), 1e-4f);
        assertEquals(637.8f, a.z(), 1e-4f);
        assertEquals(-10f, a.orient(), 1e-4f);
        assertEquals(5000L, a.dwellMs());
        assertFalse(a.hasFacing(), "-10 是哨兵朝向，按行进方向计算");
        assertFalse(a.run());
        // 格式2：整数导航点 id 前缀 —— id 必须跳过（旧服误读成 x），停留列同位。
        Waypoint b = PatrolRoutes.parseWaypoint(
                "59,458.7,0.629,637.8,-10,60000,stand,nil,walk,nil,1,");
        assertEquals(a.x(), b.x(), 1e-6f);
        assertEquals(a.y(), b.y(), 1e-6f);
        assertEquals(a.z(), b.z(), 1e-6f);
        assertEquals(60000L, b.dwellMs());
        // 格式3：无 id 列，x 直接开头（浮点首列），停留列在朝向后首位（field4）。
        Waypoint c = PatrolRoutes.parseWaypoint(
                "318.196625,3.794954,632.016357,0.494,4000,0,stand,nil,walk,nil,1,");
        assertEquals(318.196625f, c.x(), 1e-5f);
        assertEquals(3.794954f, c.y(), 1e-5f);
        assertEquals(632.016357f, c.z(), 1e-5f);
        assertEquals(0.494f, c.orient(), 1e-5f);
        assertEquals(4000L, c.dwellMs());
        assertTrue(c.hasFacing());
        // 停留上限钳制（数据实测最大 60s）。
        assertEquals(PatrolRoutes.DWELL_CAP_MILLIS,
                PatrolRoutes.parseWaypoint(",1,0,1,0,999999999,stand,nil,walk,nil,1,").dwellMs());
        // run 标记在尾部固定列。
        Waypoint run = PatrolRoutes.parseWaypoint(
                ",1,0,1,0,0,stand,nil,run,nil,1,");
        assertTrue(run.run());
        // 垃圾行 → null。
        assertNull(PatrolRoutes.parseWaypoint("not,a,number,"));
        assertNull(PatrolRoutes.parseWaypoint(","));
    }

    @Test
    void loadFiltersSceneFilesDedupesAndKeepsSections(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("city02_npc.ini"), """
                [AI_sz_001]
                r=,459.0,0.6,637.8,0,0,stand,nil,walk,nil,1,
                r=,433.9,0.6,637.7,0,0,stand,nil,walk,nil,1,

                [AI_sz_002]
                r=59,462.0,0.6,640.0,-10,0,stand,nil,walk,nil,1,
                r=,462.1,0.6,640.05,0,0,stand,nil,walk,nil,1,
                r=,480.0,0.6,642.0,0,0,stand,nil,walk,nil,1,
                """, GBK);
        // offline 排除；异场景（city03）不加载。
        Files.writeString(dir.resolve("city02_offline.ini"), """
                [AI_sz_offline]
                r=,1,0,1,0,0,stand,nil,walk,nil,1,
                r=,2,0,1,0,0,stand,nil,walk,nil,1,
                """, GBK);
        Files.writeString(dir.resolve("city03_npc.ini"), """
                [AI_jl_001]
                r=,1,0,1,0,0,stand,nil,walk,nil,1,
                r=,2,0,1,0,0,stand,nil,walk,nil,1,
                """, GBK);

        Map<String, PatrolRoute> table = PatrolRoutes.load(dir, "city02_suzhou");
        assertEquals(2, table.size());
        assertTrue(table.containsKey("AI_sz_001"));
        assertTrue(table.containsKey("AI_sz_002"));
        // 整数 id 行解析 + 相邻 <0.5m 去重（462.0 与 462.1 合并）。
        assertEquals(2, table.get("AI_sz_001").size());
        assertEquals(2, table.get("AI_sz_002").size());
        Waypoint first = table.get("AI_sz_002").at(0);
        assertEquals(462.0f, first.x(), 1e-4f);
        assertEquals(640.0f, first.z(), 1e-4f);
    }

    @Test
    void bindSnapsCreatorPathIdToNearestWaypoint() {
        Map<String, PatrolRoute> table = Map.of("AI_sz_001", new PatrolRoute("AI_sz_001", List.of(
                new Waypoint(458.7f, 0.629f, 637.8f, 0f, false, 0),
                new Waypoint(433.9f, 0.618f, 637.7f, 1.2f, false, 2000),
                new Waypoint(410.0f, 0.6f, 630.0f, 0f, false, 0))));
        NpcSpawn near = spawn("sz051", "CommonNpc", 459.139f, 0.630f, 637.788f);
        near.resolved.extensions.put("creator.PathId", "AI_sz_001");
        near.resolved.properties.put("WalkSpeed", com.nineyin.server.protocol.PropertyValues.Value.ofFloat32(1.5f));
        PatrolRoutes.BindStats stats = PatrolRoutes.bind(List.of(near), table);
        assertSame(table.get("AI_sz_001"), near.route);
        assertEquals(0, near.routeStartIdx, "最近路点是首点");
        assertEquals(458.7f, near.x, 1e-4f, "吸附到路点坐标");
        assertEquals(637.8f, near.z, 1e-4f);
        assertNotNull(near.schedule, "绑定即构建时间表");
        assertEquals(3, near.schedule.waypoints(), "时间表含全部 3 点（无高度异常）");
        assertEquals(1, stats.walkers());
        // 模板 PathID 缺省绑定（无 creator 覆盖）；同批绑定共享同一时间表时钟。
        NpcSpawn viaTemplate = spawn("sz052", "CommonNpc", 433.95f, 0.62f, 637.7f);
        viaTemplate.resolved.extensions.put("template.PathID", "AI_sz_001");
        PatrolRoutes.bind(List.of(viaTemplate), table);
        assertSame(table.get("AI_sz_001"), viaTemplate.route);
        assertEquals(1, viaTemplate.routeStartIdx);
        assertEquals(1.2f, viaTemplate.orient, 1e-4f, "有效路点朝向覆盖出生朝向");
    }

    @Test
    void clampedSpeedFallsBackAndClamps() {
        NpcSpawn npc = spawn("sz051", "CommonNpc", 0, 0, 0);
        assertEquals(1.25f, PatrolRoutes.clampedSpeed(npc, "WalkSpeed", 1.25f), 1e-6f, "缺失回退");
        assertEquals(4.0f, PatrolRoutes.clampedSpeed(npc, "RunSpeed", 4.0f), 1e-6f);
        npc.resolved.properties.put("WalkSpeed",
                com.nineyin.server.protocol.PropertyValues.Value.ofFloat32(0.3f));
        assertEquals(0.5f, PatrolRoutes.clampedSpeed(npc, "WalkSpeed", 1.25f), 1e-6f, "下限钳制");
        npc.resolved.properties.put("WalkSpeed",
                com.nineyin.server.protocol.PropertyValues.Value.ofFloat32(99f));
        assertEquals(15f, PatrolRoutes.clampedSpeed(npc, "WalkSpeed", 1.25f), 1e-6f, "上限钳制");
        npc.resolved.properties.put("WalkSpeed",
                com.nineyin.server.protocol.PropertyValues.Value.ofFloat32(2.5f));
        assertEquals(2.5f, PatrolRoutes.clampedSpeed(npc, "WalkSpeed", 1.25f), 1e-6f);
    }

    @Test
    void bindStandsForFarMissingOrCombat() {
        PatrolRoute route = new PatrolRoute("AI_sz_001", List.of(
                new Waypoint(0f, 0f, 0f, 0f, false, 0),
                new Waypoint(5f, 0f, 0f, 0f, false, 0)));
        Map<String, PatrolRoute> table = Map.of("AI_sz_001", route);
        // 出生点距最近路点 40m（45-5）> 35m 上限 → 站立。
        NpcSpawn far = spawn("far", "CommonNpc", 45f, 0f, 0f);
        far.resolved.extensions.put("creator.PathId", "AI_sz_001");
        // PathId 指向不存在的路线 → 站立。
        NpcSpawn missing = spawn("missing", "CommonNpc", 0f, 0f, 0f);
        missing.resolved.extensions.put("creator.PathId", "AI_nope");
        // 敌对脚本 → 不绑定。
        NpcSpawn combat = spawn("mon", "attacknpc", 1f, 0f, 0f);
        combat.resolved.extensions.put("creator.PathId", "AI_sz_001");
        // 无 PathId → 站立。
        NpcSpawn plain = spawn("plain", "CommonNpc", 1f, 0f, 0f);
        PatrolRoutes.BindStats stats = PatrolRoutes.bind(
                List.of(far, missing, combat, plain), table);
        assertNull(far.route);
        assertNull(missing.route);
        assertNull(combat.route);
        assertNull(plain.route);
        assertEquals(0, stats.walkers());
        assertEquals(1, stats.missingPath());
        assertEquals(1, stats.farOrShort());
        assertEquals(45f, far.x, 1e-4f, "远出生点坐标不被改写");
    }

    private static NpcSpawn spawn(String configId, String scriptClass, float x, float y, float z) {
        NpcCreator.Transform transform = new NpcCreator.Transform();
        transform.x = x;
        transform.y = y;
        transform.z = z;
        NpcResolver.ResolvedNpc resolved = new NpcResolver.ResolvedNpc(
                "test#" + configId, configId, scriptClass, transform);
        return new NpcSpawn(resolved, x, y, z, 0f);
    }
}
