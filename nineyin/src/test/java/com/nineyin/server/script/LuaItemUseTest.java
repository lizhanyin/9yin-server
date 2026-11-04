package com.nineyin.server.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.script.LuaScriptService.ItemUse;
import com.nineyin.server.script.LuaScriptService.ItemUseContext;
import com.nineyin.util.ServerPaths;

/**
 * scripts/item/&lt;configId&gt;.lua 的 on_use 数据桥：三个锚点脚本（开包/回血/限时属性）
 * 走真实 Lua55 引擎；缺脚本 → null（调用方走 INI 回退）。
 */
class LuaItemUseTest {

    @BeforeAll
    static void pointScriptsAtRepoRoot() {
        // surefire cwd=nineyin 模块目录；scripts/ 在仓库根。单例可能已被更早的测试类
        // 以错误根初始化（formula 缺失 → broken），reload 重建后本类用例自愈。
        ServerPaths.overrideServerRoot(
                Path.of("").toAbsolutePath().normalize().getParent());
        LuaScriptService.getInstance().reload();
    }

    private static ItemUseContext ctx() {
        return new ItemUseContext(1, 1_700_000_000_000L, 500, 1000, 50, 100, 0, 0.5, 0.5);
    }

    @Test
    void boxScriptReturnsOpenBox() {
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "item", "box_chinajoy_2026_giftbox.lua")),
                "box anchor script missing");
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");
        ItemUse out = service.itemUse("box_chinajoy_2026_giftbox", ctx());
        assertNotNull(out);
        assertEquals("comb_ai_drop_box_chinajoy_2026_giftbox", out.openDropId());
        assertEquals(1, out.rolls());
        assertTrue(out.consume());
        assertEquals(0, out.healHp());
        assertTrue(out.grants().isEmpty());
    }

    @Test
    void healScriptReturnsHealHp() {
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "item", "yaopin_sc_1001.lua")),
                "yaopin anchor script missing");
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");
        ItemUse out = service.itemUse("yaopin_sc_1001", ctx());
        assertNotNull(out);
        assertEquals(100, out.healHp());
        assertEquals(0, out.healMp());
        assertNull(out.openDropId());
        assertTrue(out.consume());
    }

    @Test
    void tempStatScriptParsed() {
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(Files.isRegularFile(
                ServerPaths.projectPath("scripts", "item", "useitemhs_dy_06.lua")),
                "useitemhs anchor script missing");
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");
        ItemUse out = service.itemUse("useitemhs_dy_06", ctx());
        assertNotNull(out);
        assertNotNull(out.tempStat());
        assertEquals(25, out.tempStat().attackAdd());
        assertEquals(86_400_000L, out.tempStat().lifeMs());
        assertTrue(out.consume());
    }

    @Test
    void missingScriptYieldsNullForIniFallback() {
        LuaScriptService service = LuaScriptService.getInstance();
        Assumptions.assumeTrue(service.luaAvailable(), "lua engine unavailable in this env");
        assertNull(service.itemUse("no_such_item_script", ctx()));
    }
}
