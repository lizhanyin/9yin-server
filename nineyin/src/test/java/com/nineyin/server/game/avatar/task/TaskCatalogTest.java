package com.nineyin.server.game.avatar.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 任务目录加载：内存 fixture（主表/prize/subfunction/npcconfig 四源 TSV）验证
 * GBK 带引号 TSV 解析、按列名定位、任务归属索引与奖励串渲染。
 */
class TaskCatalogTest {

    @TempDir
    Path shareRoot;

    private void write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append("\r\n");
        }
        Files.write(file, text.toString().getBytes(Charset.forName("GBK")));
    }

    private String taskHeader() {
        return "STRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\r\n"
                + "ID\tPlot\tTitle\tStep\tSearchID\tLine\tAcceptNpc\tSubmitNpc\tSceneID\tAcceptSceneID\tSubmitSceneID\tAutoAcceptID\tAutoSubmit\tPreTaskLimit\tNextTaskLimit\tTitleId\tContextId\tCompleteDialogId\tTaskTargetId\tTrackInfoID\tCanGiveup\tTimeLimitLength\tAcceptLimit\tSubmitLimit\tTaskItemList\tSubmitNpcEx";
    }

    @Test
    void loadsTaskPrizeStepsAndNpcOwnership() throws IOException {
        write(shareRoot.resolve(Path.of("task", "task", "task", "task_ut_001.txt")),
                taskHeader(),
                // 26 列方言：ID..SubmitNpcEx 逐位对齐表头（50 无前置，51 前置 50;60）。
                "50\t\t\t1\t\t1\tFuncNpc001\tFuncNpc002\t448\t448\t448\t\t\t\t\ttitle_50\tcontext_50\tcomplete_50\ttarget_50\tnext_50\t1\t0\t\t\t\t\t",
                "51\t\t\t2\t\t1\tFuncNpc002\tFuncNpc002\t448\t448\t448\t\t\t50;60\t\ttitle_51\tcontext_51\tcomplete_51\ttarget_51\tnext_51\t1\t0\t\t\titem_x*2\t");
        write(shareRoot.resolve(Path.of("task", "task", "prize", "prize_ut_001.txt")),
                "STRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\r\n"
                        + "ID\tTitle\tFaculty\tCapitalType1\tNormalCapital\tReputeName1\tReputeCount1\tReputeName3\tReputeCount2\tDropID\tAddPrize\tIsGive\tPrizeItemType\tItem1\tNum1\tItem2\tNum2\tItem3\tNum3\tItem4\tNum4\tLiveGroove\tLiveGrooveCount\tKarmaPrize",
                "50\t勤修苦练\t100\t1\t5000\t\t0\t\t0\t\t\t0\t0\tpill_a\t2\t\t0\t\t0\t\t0\t0\t0\t0",
                "51\t进阶\t0\t1\t0\trepute_wudang\t38\t\t0\tdrop_x\t\t0\t0\t\t0\t\t0\t\t0\t\t0\t1\t500\t0");
        write(shareRoot.resolve(Path.of("task", "task", "subfunction", "subfunction_ut_001.txt")),
                "STRING\tSTRING\tSTRING\tSTRING\tSTRING\r\nID\tType\tTypeID\tOrder\tRandCount",
                "51\tstory\t50\t1\t",
                "51\tstory\t60\t2\t",
                "51\tinteract\tobj_9\t3\t");
        // npcconfig 三行表头（中文注释/类型/列名），任务归属列官方名带 table@ 前缀。
        write(shareRoot.resolve(Path.of("npc", "npcconfig", "worldnpc", "city.txt")),
                "注释\t注释\t注释\t注释\t注释\t注释\r\n"
                        + "STRING\tSTRING\tSTRING\tSTRING\tSTRING\tSTRING\r\n"
                        + "ID\tName\tFuncID\ttable@TaskCanAccept\ttable@TaskCanSubmit\tScriptClass",
                "FuncNpc001\t马六\t\t50;\t49;\tFuncNpc",
                "FuncNpc002\t乔诗韵\t\t51;52;\t50;51;\tFuncNpc");

        TaskCatalog catalog = TaskCatalog.loadShare(shareRoot);

        TaskCatalog.TaskDef task = catalog.task(51);
        assertNotNull(task, "task 51 configured");
        assertEquals(1, task.line());
        assertEquals("FuncNpc002", task.submitNpc());
        assertEquals(List.of(50, 60), task.preTaskLimit());
        assertEquals("title_51", task.titleId());
        assertEquals("item_x*2", task.taskItemList());

        TaskCatalog.PrizeDef prize = catalog.prize(50);
        assertNotNull(prize, "prize 50 configured");
        assertEquals(100, prize.faculty());
        assertEquals(5000, prize.normalCapital());
        assertEquals("pill_a", prize.item1());
        // 奖励串 23 段：[1]钱 [2]历练 [11]Item1 [12]Num1。
        String rendered = prize.renderPrizeString();
        String[] segments = rendered.split(",", -1);
        assertEquals(23, segments.length, "23-segment prize string");
        assertEquals("5000", segments[0]);
        assertEquals("100", segments[1]);
        assertEquals("pill_a", segments[10]);
        assertEquals("2", segments[11]);

        List<TaskCatalog.Step> steps = catalog.steps(51);
        assertEquals(3, steps.size(), "three steps");
        assertEquals("story", steps.get(0).type());
        assertEquals(50, Integer.parseInt(steps.get(0).typeId()));
        assertEquals("interact", steps.get(2).type());

        TaskCatalog.NpcTasks npc = catalog.npcTasks("FuncNpc002");
        assertEquals(List.of(51, 52), npc.accept());
        assertEquals(List.of(50, 51), npc.submit());
        assertTrue(catalog.npcTasks("Unknown").accept().isEmpty());
    }

    @Test
    void prizeAllZeroRendersFlatZeroString() throws IOException {
        write(shareRoot.resolve(Path.of("task", "task", "prize", "prize_ut_002.txt")),
                "STRING\tSTRING\r\nID\tTitle\tFaculty\tNormalCapital",
                "70\t无奖励\t0\t0");
        TaskCatalog.PrizeDef prize = TaskCatalog.loadShare(shareRoot).prize(70);
        assertNotNull(prize);
        String[] segments = prize.renderPrizeString().split(",", -1);
        assertEquals(23, segments.length);
        for (String segment : segments) {
            assertEquals("0", segment, "all-zero prize string（is_have_prize 不渲染奖励区）");
        }
    }
}
