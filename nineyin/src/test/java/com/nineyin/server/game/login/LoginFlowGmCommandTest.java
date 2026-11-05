package com.nineyin.server.game.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alibaba.fastjson2.JSONObject;
import com.nineyin.server.game.avatar.PlayerActor;
import com.nineyin.server.gm.GmCommand;
import com.nineyin.server.gm.GmHub;
import com.nineyin.server.protocol.CustomC2S;
import com.nineyin.server.transport.Connection;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * GM 队列消费端（Go main.go processGMCommand 的移动参数分支）：入队命令按
 * action 应用到 PlayerActor、写单帧 0x10、更新面板 lastAction 并完成回执；
 * 未进场景/坏值/未实现动作明确失败且不写帧。
 */
class LoginFlowGmCommandTest {

    private static final class Env {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final GmHub hub = new GmHub();
        final LoginSession session = new LoginSession();
        final LoginFlow flow;

        Env() {
            this(null);
        }

        Env(java.util.concurrent.ScheduledExecutorService scheduler) {
            session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, scheduler);
        }

        GmCommand enqueue(String action, Map<String, Object> params) {
            GmCommand command = new GmCommand(session.machine.id(), action, new JSONObject(params));
            hub.enqueue(command);
            return command;
        }
    }

    private static GmCommand.Reply reply(GmCommand command) {
        return command.awaitReply(Duration.ofMillis(1));
    }

    @Test
    void moveSpeedAndGravityAppliedWithFrameReplyAndLastAction() {
        Env env = new Env();
        GmCommand speed = env.enqueue("set_move_speed", Map.of("value", 30));
        GmCommand gravity = env.enqueue("set_gravity", Map.of("value", 5));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(speed).ok(), "合法移动速度应成功");
        assertEquals("移动速度设为 30.00", reply(speed).message());
        assertTrue(reply(gravity).ok(), "合法重力应成功");
        // motion 状态突变与帧布局由 PlayerActorMotionTest 覆盖；此处验证编排链。

        // 两条成功命令各写一帧普通 0x10 归档。
        byte[] first = env.channel.readOutbound();
        byte[] second = env.channel.readOutbound();
        assertTrue(first != null && second != null, "应写出两帧");
        assertNull(env.channel.readOutbound(), "不应有更多帧");
        assertEquals(0x10, first[0] & 0xFF);
        assertEquals(0x10, second[0] & 0xFF);

        // 面板 lastAction = 最后一次成功动作文案。
        assertEquals("下落重力设为 5.00", env.hub.get(env.session.machine.id()).lastAction);
    }

    @Test
    void badValueAndUnknownActionFailWithoutFrames() {
        Env env = new Env();
        GmCommand over = env.enqueue("set_move_speed", Map.of("value", 500));
        GmCommand unknown = env.enqueue("definitely_not_an_action", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(over).ok(), "超界值应失败");
        assertTrue(reply(over).message().contains("0.1"));
        assertFalse(reply(unknown).ok(), "未实现动作应失败");
        assertTrue(reply(unknown).message().contains("definitely_not_an_action"));
        assertNull(env.channel.readOutbound(), "失败命令不应写帧");
    }

    @Test
    void commandBeforeEnteringSceneFails() {
        Env env = new Env();
        env.session.player = null;
        GmCommand command = env.enqueue("set_move_speed", Map.of("value", 30));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(command).ok());
        assertEquals("角色尚未进入场景", reply(command).message());
    }

    /** 无命令时 drain 是静默空转（1s ticker 每秒调用）。 */
    @Test
    void drainIsNoopWithoutCommands() {
        Env env = new Env();
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertNull(env.channel.readOutbound());
    }

    // ------------------------------------------------------------- 四期面板动作

    @Test
    void zhenqiCapAppliesGateAndPushesPropsFrame() {
        Env env = new Env();
        GmCommand enable = env.enqueue("set_zhenqi_cap", Map.of("enabled", 1, "value", 300));
        GmCommand badValue = env.enqueue("set_zhenqi_cap", Map.of("enabled", 1, "value", 0));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(enable).ok(), "开启硬闸应成功");
        var meridian = env.session.player.meridian();
        assertTrue(meridian.capEnabled());
        assertEquals(300, meridian.capValue());
        assertEquals(300, meridian.zhenQiDayValue(), "ZhenQiDayValue 显示阈值");
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "经脉属性单帧 0x10");
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(8, count, "CurJingMai..ZQUnUsed 八属性");
        assertEquals(300, int32Property(frame, "ZhenQiDayValue"), "帧内 ZhenQiDayValue=300");
        assertFalse(reply(badValue).ok(), "阈值 <1 应失败");
        assertNull(env.channel.readOutbound(), "失败命令不写帧");
        assertTrue(env.hub.get(env.session.machine.id()).lastAction.startsWith("每日真气硬闸已开启"),
                "lastAction 回显开关文案");
    }

    @Test
    void powerLevelPushesLevelAndTitleFrames() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_power_level", Map.of("value", 137));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok());
        assertEquals(137, env.session.player.panel().powerLevel);
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "实力变更推单帧 0x10");
        // 0x10|isView|u32 oid|u32 owner|u16 count|条目：count=2 且含 PowerLevel=137
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(2, count, "PowerLevel + LevelTitle 两属性");
        assertTrue(containsInt32(frame, "PowerLevel", 137), "帧内 PowerLevel=137");
        assertNull(env.channel.readOutbound());
    }

    @Test
    void alignmentAppliesJusticeEvilFreeBase() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_alignment",
                Map.of("justice", 3200, "evil", 9, "freeBase", 0));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok());
        assertEquals(3200, env.session.player.panel().justice);
        assertEquals(9, env.session.player.panel().evil);
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10);
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(3, count, "Justice/Evil/FreeBase 三属性");
        GmCommand empty = env.enqueue("set_alignment", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertFalse(reply(empty).ok(), "全缺参数失败");
    }

    @Test
    void vitalsApplyAndRejectEmptyParams() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_vitals",
                Map.of("ene", 80, "maxEne", 100, "pkValue", 7, "drunkLevel", 3));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok());
        assertEquals(80, env.session.player.panel().ene);
        assertEquals(100, env.session.player.panel().maxEne);
        assertEquals(7, env.session.player.panel().pkValue);
        assertEquals(3, env.session.player.panel().drunkLevel);
        // 生存属性随 faculty 快照携行（嵌套 vitals 节，重登 restoreFaculty 回填 PanelState）。
        var vitals = env.session.player.facultySnapshot().vitals();
        assertTrue(vitals != null, "facultySnapshot 应并入 PanelState 生存属性层");
        assertEquals(80, vitals.ene());
        assertEquals(100, vitals.maxEne());
        assertEquals(7, vitals.pkValue());
        assertEquals(3, vitals.drunkLevel());
        assertEquals(0, vitals.sat(), "未提供的饱食保持 0");
        // 快照回填：新 actor restore 后 PanelState 恢复同值（PlayerActor.restoreFaculty 链）。
        PlayerActor restored = PlayerActor.create("赵灵儿", 99998, Clock.systemUTC());
        restored.restoreFaculty(env.session.player.facultySnapshot(), java.time.Instant.now());
        assertEquals(80, restored.panel().ene);
        assertEquals(100, restored.panel().maxEne);
        assertEquals(7, restored.panel().pkValue);
        assertEquals(3, restored.panel().drunkLevel);
        assertTrue(env.hub.get(env.session.machine.id()).lastAction.contains("杀戮 7"),
                "lastAction 回显生存属性明细");
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10);
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        assertEquals(4, count);
        GmCommand empty = env.enqueue("set_vitals", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertFalse(reply(empty).ok());
    }

    @Test
    void fiveAttributesApplySkipZeroAndPersistSnapshot() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_five_attributes",
                Map.of("attrStr", 666, "attrSta", 555, "attrDex", 0, "attrIng", 444, "attrSpi", 0));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok(), reply(command).message());
        // 覆盖式写入落快照；0=跳过该维（dex/spi 保持 create 种子 30）。
        var snapshot = env.session.player.facultySnapshot();
        assertEquals(666, snapshot.baseStr());
        assertEquals(555, snapshot.baseSta());
        assertEquals(30, snapshot.baseDex());
        assertEquals(444, snapshot.baseIng());
        assertEquals(30, snapshot.baseSpi());
        // 0x10 全量：Str/Sta/Ing = 基值 + 运功内功书加成（ng_wd_001：臂45/体31/身13/内46/罡13；
        // Env 无装备词缀）。0 维跳过的 Dex = 种子 30 + 书 13。
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "五维走 0x10 全量");
        assertEquals(711, int32Property(frame, "Str"));
        assertEquals(586, int32Property(frame, "Sta"));
        assertEquals(490, int32Property(frame, "Ing"));
        assertEquals(43, int32Property(frame, "Dex"), "0 维跳过，帧内=种子 30+书 13");
        assertTrue(env.hub.get(env.session.machine.id()).lastAction.contains("五维已更新"));

        GmCommand allZero = env.enqueue("set_five_attributes",
                Map.of("attrStr", 0, "attrSta", 0, "attrDex", 0, "attrIng", 0, "attrSpi", 0));
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertFalse(reply(allZero).ok(), "五维全 0/缺省失败");
    }

    @Test
    void titleWearAndCancelPushTitleId() {
        Env env = new Env();
        GmCommand wear = env.enqueue("wear_title", Map.of("titleId", 12));
        GmCommand bad = env.enqueue("wear_title", Map.of("titleId", -1));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(wear).ok(), "无 DB store 时内存佩戴成功");
        assertEquals(12, env.session.player.panel().wornTitleId);
        assertTrue(containsInt32(env.channel.readOutbound(), "TitleID", 12));
        assertFalse(reply(bad).ok(), "非正 titleId 失败");

        GmCommand cancel = env.enqueue("cancel_title", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(cancel).ok());
        assertEquals(0, env.session.player.panel().wornTitleId);
        assertTrue(containsInt32(env.channel.readOutbound(), "TitleID", 0));
    }

    @Test
    void statePropertiesCarryDomainBlock() {
        Env env = new Env();
        env.session.player.panel().wornTitleId = 12;
        env.session.player.panel().maxBingLuLevel = 10;
        env.session.player.panel().prestigeExp = 55000;
        env.session.player.panel().mount = "mount_test_01";
        List<String> names = env.session.player.stateProperties().stream()
                .map(com.nineyin.server.protocol.PropertyValues.Indexed::name)
                .toList();
        assertTrue(names.contains("TitleID"));
        assertTrue(names.contains("Mount"));
        assertTrue(names.contains("CurRideMount"));
        assertTrue(names.contains("BingLuType"));
        assertTrue(names.contains("MaxBingLuLevel"));
        assertTrue(names.contains("PrestigeExp"));
    }

    /** 0x10 帧内按官方表索引找属性并比对 i32 值（条目=u16 idx + 线型值；INT32=4B）。 */
    private static boolean containsInt32(byte[] frame, String name, int expected) {
        if (frame == null || (frame[0] & 0xFF) != 0x10) {
            return false;
        }
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        int wanted = com.nineyin.server.protocol.PropertyTable.idx(name);
        int offset = 12;
        for (int i = 0; i < count; i++) {
            int index = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
            offset += 2;
            if (index == wanted
                    && com.nineyin.server.protocol.PropertyTable.wireType(name)
                    == com.nineyin.server.protocol.WireType.INT32) {
                int value = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                        | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
                return value == expected;
            }
            offset += 4; // 本测试用到的属性均为 INT32
        }
        return false;
    }

    /**
     * 按官方表线型步进解析 0x10 帧里的 INT32 属性（containsInt32 只支持纯 INT32 帧；
     * 经脉属性帧含 CurJingMai/LastJingMai 串列，必须逐条目按线型跳步）。
     */
    private static int int32Property(byte[] frame, String name) {
        int count = (frame[10] & 0xFF) | (frame[11] & 0xFF) << 8;
        int wanted = com.nineyin.server.protocol.PropertyTable.playerIdx(name);
        int offset = 12;
        for (int i = 0; i < count; i++) {
            int index = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
            offset += 2;
            com.nineyin.server.protocol.PropertyTable.Entry entry =
                    com.nineyin.server.protocol.PropertyTable.byIndex(index);
            int type = entry == null ? 3 : entry.typeCode();
            switch (type) {
                case 1 -> {
                    if (index == wanted) {
                        return frame[offset] & 0xFF;
                    }
                    offset += 1;
                }
                case 2 -> {
                    if (index == wanted) {
                        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8;
                    }
                    offset += 2;
                }
                case 3, 5 -> {
                    if (index == wanted) {
                        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                                | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
                    }
                    offset += 4;
                }
                case 4 -> {
                    if (index == wanted) {
                        throw new AssertionError(name + " is INT64, not INT32");
                    }
                    offset += 8;
                }
                case 7, 8 -> {
                    int n = (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                            | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
                    offset += 4 + n;
                }
                case 9 -> offset += 8;
                default -> offset += 4;
            }
        }
        throw new AssertionError(name + " not found in frame");
    }

    // ------------------------------------------------------------- 货币/怒气

    @Test
    void currenciesApplyWithFramesSnapshotAndLastAction() {
        Env env = new Env();
        GmCommand silver = env.enqueue("set_silver", Map.of("silver", 555));
        GmCommand guanYin = env.enqueue("set_guanyin", Map.of("guanYin", 777));
        GmCommand gold = env.enqueue("set_gold", Map.of("gold", 9));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(silver).ok());
        assertEquals("碎银设为 555", reply(silver).message());
        assertTrue(reply(guanYin).ok());
        assertEquals("官银设为 777", reply(guanYin).message());
        assertTrue(reply(gold).ok());
        assertEquals("黄金设为 9", reply(gold).message());
        for (int i = 0; i < 3; i++) {
            byte[] frame = env.channel.readOutbound();
            assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "每条货币命令各写一帧 0x10");
        }
        assertNull(env.channel.readOutbound());

        GmHub.PlayerSnapshot snapshot = env.hub.get(env.session.machine.id());
        assertEquals(555, snapshot.silver);
        assertEquals(777, snapshot.guanYin.intValue());
        assertEquals(9, snapshot.gold.intValue());
        assertEquals("黄金设为 9", snapshot.lastAction, "lastAction=最后一次成功动作");
    }

    @Test
    void spSetAddFillApplyWithSaturation() {
        Env env = new Env();
        GmCommand set = env.enqueue("set_sp", Map.of("sp", 30));
        GmCommand add = env.enqueue("add_sp", Map.of("sp", 90));
        GmCommand fill = env.enqueue("fill_sp", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(set).ok());
        assertEquals("怒气设为 30/100", reply(set).message());
        assertTrue(reply(add).ok());
        assertEquals("怒气设为 100/100", reply(add).message(), "30+90 饱和到 100");
        assertTrue(reply(fill).ok());
        assertEquals("怒气设为 100/100", reply(fill).message());
        for (int i = 0; i < 3; i++) {
            byte[] frame = env.channel.readOutbound();
            assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "每条怒气命令各写一帧 0x10");
        }
        assertNull(env.channel.readOutbound());
        assertEquals(100, env.session.player.sp());
        assertEquals(100, env.hub.get(env.session.machine.id()).sp);
    }

    @Test
    void negativeCurrencyRejectedWithoutFrames() {
        Env env = new Env();
        GmCommand silver = env.enqueue("set_silver", Map.of("silver", -1));
        GmCommand guanYin = env.enqueue("set_guanyin", Map.of("guanYin", -5));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(silver).ok());
        assertFalse(reply(guanYin).ok());
        assertNull(env.channel.readOutbound());
    }

    // ------------------------------------------------- V2/V3 外观挂载动作

    /**
     * popup_gm_panel：单帧 0x27 现代壳命令名寻址 close_gmcc_form msg_id=2
     * （客户端 form_gmcc_main.open_gmcc_chat 显示 GMCC 面板；lua 取证
     * custom_handler.lua close_gmcc_form 分支 msg_id==2）。
     */
    @Test
    void popupGmPanelSendsGmccOpenFrame() {
        Env env = new Env();
        GmCommand command = env.enqueue("popup_gm_panel", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok(), "弹出 GM 面板应成功");
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null, "应写出一帧");
        assertEquals(0x27, frame[0] & 0xFF, "现代 custom 壳 0x27");
        assertEquals(2, (frame[1] & 0xFF) | (frame[2] & 0xFF) << 8, "两值：命令名 + msg_id");
        assertEquals(6, frame[3] & 0xFF, "首值线型 tag6 窄字符串（命令名寻址）");
        int len = (frame[4] & 0xFF) | (frame[5] & 0xFF) << 8 | (frame[6] & 0xFF) << 16
                | (frame[7] & 0xFF) << 24;
        assertEquals(16, len, "u32 = 字节数 15 + NUL");
        String commandName = new String(frame, 8, 15, java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("close_gmcc_form", commandName);
        assertEquals(0, frame[23], "字符串 NUL 结尾");
        assertEquals(2, frame[24] & 0xFF, "次值线型 tag2 int32");
        int msgId = (frame[25] & 0xFF) | (frame[26] & 0xFF) << 8 | (frame[27] & 0xFF) << 16
                | (frame[28] & 0xFF) << 24;
        assertEquals(2, msgId, "msg_id=2 → open_gmcc_chat 打开面板");
        assertNull(env.channel.readOutbound(), "仅一帧");
    }

    /**
     * set_weather：单帧 0x27 命令名寻址 on_weather_sky（sub_type=4 + SceneParaStore
     * 目录 + 过渡秒 + 空特效串），客户端原生 ChangeWeatherEffect 消费口径
     * （lua 取证 custom_handler.lua on_weather_sky / weather_set.lua start_weather）。
     * 未知键零帧失败；fadeSec 缺省 10。
     */
    @Test
    void weatherSwitchSendsOnWeatherSkyFrame() {
        Env env = new Env();
        GmCommand command = env.enqueue("set_weather",
                Map.of("weather", "heavy_rain_501", "fadeSec", 30));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(command).ok(), "天气切换应成功");
        byte[] frame = env.channel.readOutbound();
        assertTrue(frame != null, "应写出一帧");
        assertEquals(0x27, frame[0] & 0xFF, "现代 custom 壳 0x27");
        assertEquals(5, (frame[1] & 0xFF) | (frame[2] & 0xFF) << 8, "五值：命令名 + sub_type + 目录 + 过渡秒 + 特效串");
        assertEquals(6, frame[3] & 0xFF, "首值线型 tag6 窄字符串（命令名寻址）");
        int nameLen = readU32(frame, 4);
        assertEquals(15, nameLen, "u32 = 字节数 14 + NUL");
        assertEquals("on_weather_sky", new String(frame, 8, 14, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(0, frame[22], "命令名 NUL 结尾");
        assertEquals(2, frame[23] & 0xFF, "次值线型 tag2 int32（sub_type）");
        assertEquals(4, readU32(frame, 24), "sub_type=4 → ChangeWeatherEffect 切换分支");
        assertEquals(6, frame[28] & 0xFF, "第三值线型 tag6（天气渲染包目录）");
        int folderLen = readU32(frame, 29);
        assertEquals(15, folderLen, "heavy_rain_501 字节数 14 + NUL");
        assertEquals("heavy_rain_501", new String(frame, 33, 14, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(0, frame[47], "目录 NUL 结尾");
        assertEquals(2, frame[48] & 0xFF, "第四值线型 tag2（过渡秒）");
        assertEquals(30, readU32(frame, 49), "fadeSec=30 原样下发");
        assertEquals(6, frame[53] & 0xFF, "第五值线型 tag6（TXID 特效串）");
        assertEquals(1, readU32(frame, 54), "空串 u32=1（仅 NUL）");
        assertEquals(0, frame[58], "空特效串 NUL");
        assertNull(env.channel.readOutbound(), "仅一帧");
        assertTrue(env.hub.get(env.session.machine.id()).lastAction.contains("大雨"),
                "lastAction 回显天气标签");

        // fadeSec 缺省 10（同长度键复用首帧偏移：tag2@48 + u32@49）。
        GmCommand def = env.enqueue("set_weather", Map.of("weather", "light_rain_001"));
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(def).ok());
        byte[] defFrame = env.channel.readOutbound();
        assertEquals(2, defFrame[48] & 0xFF);
        assertEquals(10, readU32(defFrame, 49), "缺省过渡秒 10");

        // 未知键（入口漏拦/直入队列）：零帧失败，不注入任意路径。
        GmCommand unknown = env.enqueue("set_weather", Map.of("weather", "rule/weather/../../evil"));
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertFalse(reply(unknown).ok(), "未知天气键应失败");
        assertNull(env.channel.readOutbound(), "失败零帧");
    }

    private static int readU32(byte[] frame, int offset) {
        return (frame[offset] & 0xFF) | (frame[offset + 1] & 0xFF) << 8
                | (frame[offset + 2] & 0xFF) << 16 | (frame[offset + 3] & 0xFF) << 24;
    }

    /** 洗练六动作：无 inventory DB 时明确失败且不写帧（不半途改词条）。 */
    @Test
    void recastActionsFailWithoutInventoryStore() {
        Env env = new Env();
        GmCommand roll = env.enqueue("recast_slot_prop", Map.of("slot", 22));
        GmCommand add = env.enqueue("recast_add_affix",
                Map.of("slot", 22, "packIDs", List.of(63308), "affixKind", "prop"));
        GmCommand clear = env.enqueue("recast_clear_affix", Map.of("slot", 22));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(roll).ok());
        assertTrue(reply(roll).message().contains("物品 DB 未接入"));
        assertFalse(reply(add).ok());
        assertFalse(reply(clear).ok());
        assertNull(env.channel.readOutbound(), "失败命令不写帧");
    }

    /** 场景生成两动作：未进场景（无 world）明确失败且不写帧。 */
    @Test
    void spawnDummyAndChestFailWithoutScene() {
        Env env = new Env();
        GmCommand dummy = env.enqueue("spawn_dummy", Map.of("dummyHp", 500000));
        GmCommand chest = env.enqueue("spawn_chest", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(dummy).ok(), "未进场景木桩应失败");
        assertEquals("角色尚未进入场景", reply(dummy).message());
        assertFalse(reply(chest).ok(), "未进场景宝箱应失败");
        assertNull(env.channel.readOutbound(), "失败命令不写帧");
    }

    /** 战斗假人两动作：未进场景（无 world）明确失败且不写帧（bak 同款前缀文案）。 */
    @Test
    void combatDummyActionsFailWithoutScene() {
        Env env = new Env();
        GmCommand spawn = env.enqueue("spawn_combat_dummy", Map.of("dummyHp", 50000));
        GmCommand clear = env.enqueue("clear_combat_dummies", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(spawn).ok(), "未进场景生成假人应失败");
        assertEquals("生成战斗假人失败：角色尚未进入场景", reply(spawn).message());
        assertFalse(reply(clear).ok(), "未进场景清除假人应失败");
        assertEquals("清除战斗假人失败：角色尚未进入场景", reply(clear).message());
        assertNull(env.channel.readOutbound(), "失败命令不写帧");
    }

    /** 世界 BOSS 三动作：未进场景（无 world）明确失败且不写帧。 */
    @Test
    void worldBossActionsFailWithoutScene() {
        Env env = new Env();
        GmCommand spawn = env.enqueue("worldboss_spawn", Map.of());
        GmCommand clear = env.enqueue("worldboss_clear", Map.of());
        GmCommand list = env.enqueue("worldboss_list", Map.of());

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(spawn).ok(), "未进场景生成 BOSS 应失败");
        assertEquals("生成世界 BOSS 失败：角色尚未进入场景", reply(spawn).message());
        assertFalse(reply(clear).ok(), "未进场景清除 BOSS 应失败");
        assertEquals("清除世界 BOSS 失败：角色尚未进入场景", reply(clear).message());
        assertFalse(reply(list).ok(), "未进场景查看 BOSS 应失败");
        assertEquals("查看世界 BOSS 失败：角色尚未进入场景", reply(list).message());
        assertNull(env.channel.readOutbound(), "失败命令不写帧");
    }

    /**
     * 战斗假人生成/清除全链（真实 attacknpc 表克隆进临时根，mountSkin 同款
     * overrideServerRoot + finally 还原）：机关木人 AttackNpc 模板 → gmSpawns 打
     * combatDummy 标志 + combatNpcs 快照就位（血量/名字为入参，缺省 5 万/「战斗假人」）
     * → 一键清除只摘假人（同表训练木桩隔离不受影响）。
     */
    @Test
    void combatDummySpawnAndClearRoundTrip(@TempDir Path tempRoot) throws Exception {
        Path attackNpc = Path.of("E:/Workspace/project/game/9yin-go-server1")
                .resolve(Path.of("resources", "modern", "share", "npc", "npcconfig",
                        "worldnpc", "attacknpc.txt"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(attackNpc),
                "参考服 attacknpc 表不可用，跳过战斗假人编排断言");
        Path worldDir = tempRoot.resolve(Path.of("resources", "modern", "share",
                "npc", "npcconfig", "worldnpc"));
        java.nio.file.Files.createDirectories(worldDir);
        java.nio.file.Files.copy(attackNpc, worldDir.resolve("attacknpc.txt"));

        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            env.session.world = new com.nineyin.server.world.scene.SceneLifecycle(
                    new Connection(env.channel), new NpcDialogFlowTest.NoopScheduler());
            env.session.world.begin("ini\\scene\\born04", "born04");

            // 自定义假人 + 同表训练木桩（clear 隔离口径）。
            GmCommand spawn = env.enqueue("spawn_combat_dummy",
                    Map.of("dummyName", "木人甲", "dummyHp", 12345));
            GmCommand dummy = env.enqueue("spawn_dummy", Map.of("dummyHp", 777));
            env.flow.drainGmCommands(new Connection(env.channel), env.session);

            assertTrue(reply(spawn).ok(), "生成战斗假人应成功: " + reply(spawn).message());
            assertTrue(reply(spawn).message().contains("木人甲"), "回执含假人名");
            assertTrue(reply(spawn).message().contains("12345"), "回执含血量");
            assertTrue(reply(dummy).ok(), "训练木桩应成功");
            assertEquals(2, env.session.gmSpawns.size(), "假人与木桩均入摆放表");
            int dummyCombatId = 0;
            for (LoginSession.GmSpawn spawnEntry : env.session.gmSpawns.values()) {
                if (spawnEntry.combatDummy) {
                    dummyCombatId = spawnEntry.objectId;
                }
            }
            assertTrue(dummyCombatId > 0, "假人打 combatDummy 标志");
            assertEquals(2, env.session.world.combatNpcs().size(),
                    "假人与木桩（同表 AttackNpc）均入战斗快照");

            // 缺省参数：血量 5 万、名字「战斗假人」（bak 同款默认）。
            GmCommand defaults = env.enqueue("spawn_combat_dummy", Map.of());
            env.flow.drainGmCommands(new Connection(env.channel), env.session);
            assertTrue(reply(defaults).ok(), "缺省生成应成功");
            var combatNpcs = env.session.world.combatNpcs();
            assertEquals(3, combatNpcs.size(), "第二只假人入快照");
            boolean foundDefault = false;
            for (var info : combatNpcs) {
                if (info.id() == dummyCombatId) {
                    assertEquals(12345, info.maxHp(), "自定义血量生效");
                    assertEquals("木人甲", info.name(), "自定义名字生效");
                } else if ("战斗假人".equals(info.name())) {
                    assertEquals(50_000, info.maxHp(), "缺省血量 5 万");
                    foundDefault = true;
                }
            }
            assertTrue(foundDefault, "缺省名「战斗假人」的假人在快照中");

            // 一键清除：只摘两只假人，木桩保留。
            GmCommand clear = env.enqueue("clear_combat_dummies", Map.of());
            env.flow.drainGmCommands(new Connection(env.channel), env.session);
            assertTrue(reply(clear).ok(), "清除应成功");
            assertEquals("已清除 2 只战斗假人", reply(clear).message());
            assertEquals(1, env.session.gmSpawns.size(), "仅训练木桩保留");
            assertFalse(env.session.gmSpawns.values().iterator().next().combatDummy,
                    "保留条目是木桩非假人");
            assertEquals(1, env.session.world.combatNpcs().size(), "战斗快照剩木桩");
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /**
     * 伙伴系统全链（真实 attacknpc 表进临时根，combatDummySpawnAndClearRoundTrip
     * 同款 overrideServerRoot + finally 还原）：bot_ensure 幂等生成（bot 摆放标志 +
     * FightState 伙伴标记 + 预设名字/血量）→ 非法预设键拒绝 → follow 开关 →
     * companionTick 跟随（pendingLocation 远处 0x21 追击 moving=1、回到停距内
     * moving=0 停步）→ 切图世代失效自动重生（新对象号 + 重标记）→ bot_pull_here
     * 0x1F 瞬移 → 辅助配置 + 周期治疗（玩家掉血回满 + 间隔闸）→ bot_revive 回满。
     */
    @Test
    void botEnsureFollowSupportReviveRoundTrip(@TempDir Path tempRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                copyResource(tempRoot, Path.of("resources", "modern", "share", "npc",
                        "npcconfig", "worldnpc", "attacknpc.txt")),
                "参考服 attacknpc 表不可用，跳过伙伴编排断言");
        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            env.session.world = new com.nineyin.server.world.scene.SceneLifecycle(
                    new Connection(env.channel), new NpcDialogFlowTest.NoopScheduler());
            env.session.world.begin("school09", "school09");
            env.session.fight = new com.nineyin.server.world.fight.FightState();
            Connection connection = new Connection(env.channel);

            // 生成：bot 摆放 + FightState 标记 + 预设名字/血量（青禾 5000）。
            GmCommand ensure = env.enqueue("bot_ensure", Map.of());
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(ensure).ok(), "生成伙伴应成功: " + reply(ensure).message());
            assertTrue(reply(ensure).message().contains("青禾"), "回执含伙伴名");
            assertTrue(env.session.bot != null && env.session.bot.following, "默认跟随");
            int objectId = env.session.bot.objectId;
            LoginSession.GmSpawn spawn = env.session.gmSpawns.get(objectId);
            assertTrue(spawn != null && spawn.bot, "gmSpawns 打 bot 标志");
            var info = env.session.world.combatNpcInfo(objectId);
            assertTrue(info != null, "伙伴入战斗快照");
            assertEquals("青禾", info.name(), "预设名字覆盖");
            assertEquals(5_000, info.maxHp(), "预设血量覆盖");
            assertTrue(env.session.fight.isCompanion(objectId), "FightState 伙伴标记");
            assertEquals("青禾", env.hub.get(env.session.machine.id()).botName, "面板快照带伙伴");

            // 幂等：同预设再次 ensure → 在场回执不新增；非法预设键失败零帧。
            GmCommand again = env.enqueue("bot_ensure", Map.of());
            GmCommand bad = env.enqueue("bot_ensure", Map.of("bot", "no_such_bot"));
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(again).ok() && reply(again).message().contains("在场"));
            assertEquals(objectId, env.session.bot.objectId, "在场不重生成");
            assertEquals(1, env.session.gmSpawns.size());
            assertFalse(reply(bad).ok(), "非法预设键应失败");
            drainOutbound(env.channel);

            // 跟随：玩家远走 20m → 0x21 追击（moving=1）；回到停距内 → moving=0 停步。
            env.session.pendingLocation = locationAt(20, 0, 0);
            env.flow.companionTick(connection, env.session);
            byte[] chase = env.channel.readOutbound();
            assertTrue(chase != null && chase.length == 47 && (chase[0] & 0xFF) == 0x21,
                    "追击应发单条目 0x21");
            assertEquals(1, chase[43], "追击 moving=1（条目尾 u32）");
            drainOutbound(env.channel);
            // 拉伙伴：0x1F 权威坐标瞬移到玩家身前（朝向前方 2.5m）。
            GmCommand pull = env.enqueue("bot_pull_here", Map.of());
            env.session.pendingLocation = locationAt(100, 0, 0); // pull 用实时位
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(pull).ok(), "拉伙伴应成功: " + reply(pull).message());
            byte[] placed = lastFrame(env.channel, 0x1F);
            assertTrue(placed != null, "拉伙伴发 0x1F 权威坐标");
            // 拉后停距内（伙伴 102.5 / 玩家 100）：不追击；moving 置位模拟行进中到点 → 停步帧。
            env.flow.companionTick(connection, env.session);
            drainOutbound(env.channel);
            env.session.pendingLocation = locationAt(102.5f, 0, 0);
            env.session.bot.moving = true;
            env.flow.companionTick(connection, env.session);
            byte[] stop = env.channel.readOutbound();
            assertTrue(stop != null && (stop[0] & 0xFF) == 0x21, "停步发 0x21");
            assertEquals(0, stop[43], "停步 moving=0");
            assertFalse(env.session.bot.moving, "停步后 moving 复位");
            drainOutbound(env.channel);

            // 跟随开关。
            GmCommand stopFollow = env.enqueue("bot_follow_stop", Map.of());
            GmCommand startFollow = env.enqueue("bot_follow_start", Map.of());
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(stopFollow).ok() && reply(startFollow).ok());
            assertTrue(env.session.bot.following);

            // 辅助：开 300/1s → 玩家掉血 → companionTick 施法帧 + 回血 + 间隔闸。
            GmCommand support = env.enqueue("bot_support_configure",
                    Map.of("enabled", 1, "intervalSec", 1, "heal", 300));
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(support).ok(), "辅助配置应成功: " + reply(support).message());
            assertTrue(env.session.bot.supportEnabled && env.session.bot.supportHeal == 300);
            drainOutbound(env.channel);
            int maxHp = env.session.player.actorState().maxHp();
            env.session.player.applyNpcDamage(Math.min(200, maxHp - 1));
            int hurt = env.session.player.actorState().hp();
            assertTrue(hurt < maxHp, "玩家应已掉血");
            env.session.pendingLocation = locationAt(100, 0, 0); // 伙伴在玩家旁（12m 内）
            env.flow.companionTick(connection, env.session);
            assertEquals(maxHp, env.session.player.actorState().hp(), "辅助应把玩家回满");
            byte[] vital = lastFrame(env.channel, 0x10);
            assertTrue(vital != null, "治疗随 0x10 vital 面板帧");
            long healedAt = env.session.bot.lastHealAtMs;
            env.session.player.applyNpcDamage(10);
            env.flow.companionTick(connection, env.session); // 间隔未到：零帧不重复治疗
            assertTrue(env.channel.readOutbound() == null, "间隔闸内不再施法");
            assertEquals(healedAt, env.session.bot.lastHealAtMs);

            // 切图世代失效（begin 清 gmSpawns + FightState 重建）→ 下一拍自动重生。
            env.session.world.begin("school09", "school09");
            env.session.gmSpawns.clear();
            env.session.fight = new com.nineyin.server.world.fight.FightState();
            env.flow.companionTick(connection, env.session);
            assertTrue(env.session.bot.objectId != objectId, "重生换新对象号");
            assertTrue(env.session.fight.isCompanion(env.session.bot.objectId), "新世代重标记");
            assertEquals(1, env.session.gmSpawns.size(), "重生恰一只伙伴");
            drainOutbound(env.channel);

            // 复活：打残打死（FightState 直伤）→ bot_revive 回满 + 状态帧。
            int botId = env.session.bot.objectId;
            assertTrue(env.session.fight.selectTarget(env.session.world, botId, 1), "伙伴可选中");
            var combat = env.session.fight.npc(botId);
            env.session.fight.applyDamage(combat, 5_000, java.time.Instant.now());
            assertFalse(env.session.fight.npcAlive(botId), "伙伴应阵亡");
            GmCommand revive = env.enqueue("bot_revive", Map.of());
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(revive).ok(), "复活应成功: " + reply(revive).message());
            assertTrue(env.session.fight.npcAlive(botId), "复活后存活");
            assertEquals(5_000, env.session.fight.npc(botId).state().hp(), "复活回满血");
            assertTrue(lastFrame(env.channel, 0x10) != null, "复活随 0x10 战斗帧");
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /**
     * 门派声望全链（真实 repute_level.ini 进临时根，combatDummy 同款
     * overrideServerRoot + finally 还原）：set 单阵营 → 1 条 0x11（Repute_Record=44，
     * 名/值/等级三列）+ 0x10 PrestigeExp；add 叠加 → 0x12 倒序删旧 + 0x11 整刷；
     * all 全量按 [Repute] 表序；未知阵营失败零帧；set 0 摘行（整刷少一行）。
     */
    @Test
    void reputeApplySetAddAllRoundTrip(@TempDir Path tempRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                copyResource(tempRoot, Path.of("resources", "modern", "share", "rule",
                        "repute_level.ini")),
                "参考服 repute_level.ini 不可用，跳过声望编排断言");
        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            Connection connection = new Connection(env.channel);
            int campCount = com.nineyin.server.game.avatar.panel.ReputeLevelTable.load()
                    .camps().size();

            // set 单阵营：1×0x11（idx44、名+值+等级）+ 1×0x10 PrestigeExp=5000。
            GmCommand set = env.enqueue("repute_apply", Map.of(
                    "reputeName", "repute_wudang", "reputeMode", "set", "reputeValue", 5000));
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(set).ok(), "设置声望应成功: " + reply(set).message());
            assertTrue(reply(set).message().contains("repute_wudang"), "回执含阵营名");
            assertEquals(Map.of("repute_wudang", 5000L), env.session.reputes, "内存镜像");
            byte[] row = env.channel.readOutbound();
            assertTrue(row != null && (row[0] & 0xFF) == 0x11, "声望行走 0x11");
            assertEquals(44, (row[10] & 0xFF) | (row[11] & 0xFF) << 8, "记录表=Repute_Record(44)");
            assertTrue(containsBytes(row, "repute_wudang".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "行含声望名");
            assertTrue(containsBytes(row, new byte[]{(byte) 0x88, 0x13, 0, 0}), "行含值 5000(LE)");
            byte[] vital = env.channel.readOutbound();
            assertTrue(containsInt32(vital, "PrestigeExp", 5000), "0x10 汇总帧 PrestigeExp=5000");
            assertEquals(5000, env.session.player.panel().prestigeExp);
            assertNull(env.channel.readOutbound(), "无多余帧");

            // add 叠加：0x12 删旧行 + 0x11 新行 12000 + 0x10 PrestigeExp=12000。
            GmCommand add = env.enqueue("repute_apply", Map.of(
                    "reputeName", "repute_wudang", "reputeMode", "add", "reputeValue", 7000));
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(add).ok());
            assertEquals(12_000L, env.session.reputes.get("repute_wudang"), "add 叠加");
            byte[] del = env.channel.readOutbound();
            assertTrue(del != null && (del[0] & 0xFF) == 0x12, "整刷先 0x12 删旧");
            assertEquals(44, (del[10] & 0xFF) | (del[11] & 0xFF) << 8);
            byte[] newRow = env.channel.readOutbound();
            assertTrue(newRow != null && (newRow[0] & 0xFF) == 0x11);
            assertTrue(containsBytes(newRow, new byte[]{(byte) 0xE0, 0x2E, 0, 0}), "新行值 12000(LE)");
            assertTrue(containsInt32(env.channel.readOutbound(), "PrestigeExp", 12_000));
            assertNull(env.channel.readOutbound());

            // all 全量：0x12×1 + 0x11×表数 + 0x10×1；总量 = 表数×100。
            GmCommand all = env.enqueue("repute_apply", Map.of(
                    "reputeName", "all", "reputeMode", "set", "reputeValue", 100));
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(all).ok(), "all 应成功: " + reply(all).message());
            assertEquals(campCount, env.session.reputes.size(), "全阵营按 [Repute] 表序");
            int[] counts = countOutbound(env.channel);
            assertEquals(1, counts[0x12], "删 1 旧行");
            assertEquals(campCount, counts[0x11], "全阵营逐行");
            assertEquals(1, counts[0x10], "汇总帧一帧");
            assertEquals(campCount * 100, env.session.player.panel().prestigeExp, "面板总量");

            // 未知阵营失败零帧。
            GmCommand bad = env.enqueue("repute_apply", Map.of(
                    "reputeName", "repute_nosuch", "reputeValue", 1));
            env.flow.drainGmCommands(connection, env.session);
            assertFalse(reply(bad).ok(), "未知阵营应失败");
            assertNull(env.channel.readOutbound(), "失败不写帧");

            // set 0 摘行：整刷后少一行（0x12×表数 + 0x11×(表数-1) + 0x10×1）。
            GmCommand clear = env.enqueue("repute_apply", Map.of(
                    "reputeName", "repute_wudang", "reputeMode", "set", "reputeValue", 0));
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(clear).ok());
            assertEquals(campCount - 1, env.session.reputes.size(), "武当行被摘除");
            int[] recount = countOutbound(env.channel);
            assertEquals(campCount, recount[0x12]);
            assertEquals(campCount - 1, recount[0x11]);
            assertEquals(1, recount[0x10]);
            assertEquals((campCount - 1) * 100, env.session.player.panel().prestigeExp);
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /** 出站帧按 opcode 计数（{0x11: n, 0x12: n}；消费完出站流）。 */
    private static int[] countOutbound(EmbeddedChannel channel) {
        int[] counts = new int[256];
        byte[] frame;
        while ((frame = channel.readOutbound()) != null) {
            counts[frame[0] & 0xFF]++;
        }
        return counts;
    }

    /** 帧内包含指定字节序列（行帧名/LE 值断言用）。 */
    private static boolean containsBytes(byte[] frame, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= frame.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (frame[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 伙伴/场景测试用坐标 Location（orient=0，version=1）。 */
    private static com.nineyin.server.db.role.Roles.Location locationAt(float x, float y, float z) {
        return new com.nineyin.server.db.role.Roles.Location(
                new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                new com.nineyin.server.db.role.Roles.Position(x, y, z, 0), 1);
    }

    /** 清空 EmbeddedChannel 出站帧（步骤间隔离用）。 */
    private static void drainOutbound(EmbeddedChannel channel) {
        while (channel.readOutbound() != null) {
            // 丢弃
        }
    }

    /** 从出站流里找指定 opcode 的最后一帧（跨步骤断言 0x1F/0x10）。 */
    private static byte[] lastFrame(EmbeddedChannel channel, int opcode) {
        byte[] found = null;
        byte[] frame;
        while ((frame = channel.readOutbound()) != null) {
            if ((frame[0] & 0xFF) == opcode) {
                found = frame;
            }
        }
        return found;
    }

    /**
     * 世界 BOSS 生成/查看/清除全链（真实 bossnpc 表 + 合成刷新表进临时根，
     * combatDummySpawnAndClearRoundTrip 同款 overrideServerRoot + finally 还原）：
     * 场景 school09 经合成 scenes.ini 映射场景号 11 → 门派固定点每点 1 只
     * （bosslist 逐点轮选）+ 江湖 BOSS 场景池命中取随机点；gmSpawns 打 worldBoss
     * 标志 + BossNpc 入战斗快照（模板真实血量）；list 报在场数；clear 一键摘除。
     */
    @Test
    void worldBossSpawnListClearRoundTrip(@TempDir Path tempRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                copyResource(tempRoot, Path.of("resources", "modern", "share", "npc",
                        "npcconfig", "worldnpc", "bossnpc.txt")),
                "参考服 bossnpc 表不可用，跳过世界 BOSS 编排断言");
        java.nio.charset.Charset gbk = java.nio.charset.Charset.forName("GBK");
        Path shareRoot = tempRoot.resolve(Path.of("resources", "modern", "share"));
        Path npcRoot = shareRoot.resolve("npc");
        java.nio.file.Files.createDirectories(npcRoot);
        java.nio.file.Files.createDirectories(shareRoot.resolve("rule"));
        java.nio.file.Files.writeString(shareRoot.resolve(Path.of("rule", "scenes.ini")),
                "[11]\nConfig=school09\n", gbk);
        java.nio.file.Files.writeString(npcRoot.resolve("normalbossrefreshinfo.ini"), """
                [1]
                bosslist=bossworld001_hd;bossworld002_hd
                scene=11
                position=100.5,-6.3,200.25

                [2]
                bosslist=bossworld002_hd;bossworld004_hd
                scene=11
                position=300,-11.5,400
                """, gbk);
        java.nio.file.Files.writeString(npcRoot.resolve("jhscenebossrefreshinfo.ini"),
                "[bossnewworld001]\nScene=11,364\n", gbk);
        java.nio.file.Files.writeString(npcRoot.resolve("jhscenebossrefreshpoint.ini"),
                "[11]\nPoint=500,10,600\nPoint=700,20,800\n", gbk);

        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            env.session.world = new com.nineyin.server.world.scene.SceneLifecycle(
                    new Connection(env.channel), new NpcDialogFlowTest.NoopScheduler());
            env.session.world.begin("school09", "school09");

            GmCommand spawn = env.enqueue("worldboss_spawn", Map.of());
            env.flow.drainGmCommands(new Connection(env.channel), env.session);
            assertTrue(reply(spawn).ok(), "强制生成应成功: " + reply(spawn).message());
            assertEquals(3, env.session.gmSpawns.size(), "两个门派点 + 一只江湖 BOSS");
            var templates = new java.util.ArrayList<String>();
            for (LoginSession.GmSpawn spawnEntry : env.session.gmSpawns.values()) {
                assertTrue(spawnEntry.worldBoss, "全部摆放打 worldBoss 标志");
                templates.add(spawnEntry.npc.resolved.configId);
                assertTrue(spawnEntry.npc.resolved.properties.get("MaxHP").i32() > 0,
                        "bossnpc 模板自带真实血量");
            }
            assertTrue(templates.contains("bossworld001_hd"), "第 1 点取 bosslist 首模板");
            assertTrue(templates.contains("bossworld004_hd"), "第 2 点跨列表轮转到次位模板");
            assertTrue(templates.contains("bossnewworld001"), "江湖 BOSS 池命中生成");
            assertEquals(3, env.session.world.combatNpcs().size(), "BossNpc 全入战斗快照");

            GmCommand list = env.enqueue("worldboss_list", Map.of());
            env.flow.drainGmCommands(new Connection(env.channel), env.session);
            assertTrue(reply(list).ok(), "查看在场应成功");
            assertTrue(reply(list).message().contains("在场世界 BOSS 3 只"), "list 报在场数");

            GmCommand clear = env.enqueue("worldboss_clear", Map.of());
            env.flow.drainGmCommands(new Connection(env.channel), env.session);
            assertTrue(reply(clear).ok(), "清除应成功");
            assertEquals("已清除 3 只世界 BOSS", reply(clear).message());
            assertTrue(env.session.gmSpawns.isEmpty(), "摆放表清空");
            assertEquals(0, env.session.world.combatNpcs().size(), "战斗快照同步清空");
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /** propBuilt 串方言（"1,id;1,id"，PanelState.recomputeEquipBonuses 同款）解析/构建往返。 */
    @Test
    void propBuiltParseBuildRoundTrip() {
        assertEquals(List.of(11, 12), LoginFlow.parsePropBuiltPacks("1,11;1,12"));
        assertEquals(List.of(11, 12), LoginFlow.parsePropBuiltPacks("1,11;x,;1,12"),
                "脏段跳过");
        assertEquals(List.of(), LoginFlow.parsePropBuiltPacks(""));
        assertEquals(List.of(), LoginFlow.parsePropBuiltPacks(null));
        assertEquals("1,11;1,12", LoginFlow.buildPropBuilt(List.of(11, 12)));
        assertEquals("", LoginFlow.buildPropBuilt(List.of()));
        assertEquals("1,11", LoginFlow.buildPropBuilt(LoginFlow.parsePropBuiltPacks("1,11")),
                "构建结果可直接回写 equip.propBuilt");
    }

    /** grant_item 无 inventory DB 时明确失败且不写帧（不穿戴/不入包半途状态）。 */
    @Test
    void grantItemFailsGracefullyWithoutInventoryStore() {
        Env env = new Env();
        env.session.selected = testRoleSnapshot();
        GmCommand grant = env.enqueue("grant_item", Map.of("itemConfig", "cloth_b0001", "amount", 1));
        GmCommand missing = env.enqueue("grant_item", Map.of("itemConfig", "", "amount", 1));

        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertFalse(reply(grant).ok());
        assertTrue(reply(grant).message().contains("物品 DB 未接入"));
        assertFalse(reply(missing).ok());
        assertNull(env.channel.readOutbound(), "失败命令不写帧");
    }

    /**
     * equip_card：挂载风物志皮肤成功——CardRec 位图行（0x11）+ UseCardRec 行 +
     * ShowEquipType 属性帧（0x10）+ on_card_msg（0x1E），并落 data/skins.json。
     * 资源来自参考服 share（复制 card.ini 进临时根；LoginFlow 须在覆盖根之后
     * 构造——SkinStore 的 data/skins.json 路径在构造期解析）。
     */
    @Test
    void mountSkinPushesRecordsAndShowEquipType(@TempDir Path tempRoot) throws Exception {
        Path goRoot = Path.of("E:/Workspace/project/game/9yin-go-server1");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(
                        goRoot.resolve(Path.of("resources", "modern", "share", "rule", "card.ini"))),
                "参考服 share 资源不可用，跳过风物志挂载编排断言");
        Path ruleDir = tempRoot.resolve(Path.of("resources", "modern", "share", "rule"));
        java.nio.file.Files.createDirectories(ruleDir);
        java.nio.file.Files.copy(goRoot.resolve(Path.of("resources", "modern", "share", "rule", "card.ini")),
                ruleDir.resolve("card.ini"));

        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            GmCommand mount = env.enqueue("equip_card", Map.of("cardId", 1));
            GmCommand unknown = env.enqueue("equip_card", Map.of("cardId", 999_999));
            env.flow.drainGmCommands(new Connection(env.channel), env.session);

            assertTrue(reply(mount).ok(), "挂载皮肤 1（Weapon blade_card_001）应成功");
            assertTrue(reply(mount).message().contains("blade_card_001"));
            assertFalse(reply(unknown).ok(), "目录外皮肤号应失败");

            // 帧序：CardRec 行 0x11 → UseCardRec 行 0x11 → ShowEquipType 0x10 → on_card_msg 0x1E。
            List<Byte> opcodes = new java.util.ArrayList<>();
            byte[] frame;
            while ((frame = env.channel.readOutbound()) != null) {
                opcodes.add(frame[0]);
            }
            assertEquals(List.of((byte) 0x11, (byte) 0x11, (byte) 0x10, (byte) 0x1E), opcodes);
            // 存档落临时根 data/skins.json（collected=[1] mounted=[1]）。
            assertTrue(java.nio.file.Files.exists(tempRoot.resolve(Path.of("data", "skins.json"))));
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /** GM 动作挂载角色快照（session.selected；外观/位置取最小合法值）。 */
    private static com.nineyin.server.db.role.Roles.RoleSnapshot testRoleSnapshot() {
        return new com.nineyin.server.db.role.Roles.RoleSnapshot(
                618, 0, "李逍遥", false, 1, 1,
                new com.nineyin.server.db.role.Roles.Appearance(1, List.of()),
                new com.nineyin.server.db.role.Roles.Location(
                        new com.nineyin.server.db.role.Roles.Scene("school09", "school09"),
                        new com.nineyin.server.db.role.Roles.Position(0, 0, 0, 0), 1));
    }

    /** 参考资源候选根（E=测试机约定，G=本机 Go 镜像，F=部署服务根——tool_item.ini 仅 F 有）。 */
    private static final List<Path> RESOURCE_ROOTS = List.of(
            Path.of("E:/Workspace/project/game/9yin-go-server1"),
            Path.of("G:/pojie/ai/9yin-go-server1"),
            Path.of("F:/Game/Online/9yin/9yin_server_new"));

    /** 依次在候选根里找相对路径并复制到临时根；任一命中返回 true。 */
    private static boolean copyResource(Path tempRoot, Path relative) throws java.io.IOException {
        for (Path root : RESOURCE_ROOTS) {
            Path source = root.resolve(relative);
            if (java.nio.file.Files.isRegularFile(source)) {
                Path target = tempRoot.resolve(relative);
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.copy(source, target);
                return true;
            }
        }
        return false;
    }

    /**
     * equip_hair：纯发型卡（只含 Hat 部件，card.ini 1036）挂载成功且同部件互斥——
     * 换第二顶发型时替换旧发型；非发型卡（card 1=Weapon）明确拒绝。
     */
    @Test
    void equipHairMountsHatOnlyCardWithMutualExclusion(@TempDir Path tempRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                copyResource(tempRoot, Path.of("resources", "modern", "share", "rule", "card.ini")),
                "参考服 card.ini 不可用，跳过发型挂载断言");
        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            GmCommand first = env.enqueue("equip_hair", Map.of("cardId", 1036));
            GmCommand second = env.enqueue("equip_hair", Map.of("cardId", 1040));
            GmCommand notHair = env.enqueue("equip_hair", Map.of("cardId", 1));
            env.flow.drainGmCommands(new Connection(env.channel), env.session);

            assertTrue(reply(first).ok(), "纯发型卡 1036 应可挂载");
            assertTrue(reply(second).ok(), "纯发型卡 1040 应可挂载");
            assertTrue(reply(second).message().contains("替换"), "第二顶发型应替换第一顶");
            assertFalse(reply(notHair).ok(), "Weapon 部件卡不是发型卡应拒绝");
            assertTrue(reply(notHair).message().contains("不是纯发型卡"));
            assertTrue(java.nio.file.Files.exists(tempRoot.resolve(Path.of("data", "skins.json"))));
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /**
     * mount_ride：坐骑道具（ride_jh_whz_0001，ArtPack=9583 → itemartstatic
     * npc\ride_camel_0104）骑乘成功——NewRideRec 行（0x11）+ 骑乘态 0x10 +
     * data/坐骑.json 落档；再点同骑 = 下马（0x12 删行 + 0x10 空模型）；
     * 未知道具明确失败。
     */
    @Test
    void mountRideTogglesAndPersists(@TempDir Path tempRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                copyResource(tempRoot, Path.of("resources", "modern", "share", "item", "tool_item.ini"))
                        && copyResource(tempRoot, Path.of("resources", "modern", "share", "item", "itemartstatic.ini"))
                        && copyResource(tempRoot, Path.of("resources", "modern", "share", "ride", "new_ride.ini")),
                "参考服坐骑资源不可用（tool_item/itemartstatic/new_ride），跳过骑乘断言");
        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            GmCommand ride = env.enqueue("mount_ride", Map.of("mountConfig", "ride_jh_whz_0001"));
            GmCommand dismount = env.enqueue("mount_ride", Map.of("mountConfig", "ride_jh_whz_0001"));
            GmCommand unknown = env.enqueue("mount_ride", Map.of("mountConfig", "no_such_mount"));
            env.flow.drainGmCommands(new Connection(env.channel), env.session);

            assertTrue(reply(ride).ok(), "望辉洲-骆驼 应可骑乘: " + reply(ride).message());
            assertTrue(reply(ride).message().contains("npc\\ride_camel_0104"));
            assertTrue(reply(dismount).ok(), "再点同骑应下马");
            assertTrue(reply(dismount).message().contains("下马"));
            assertFalse(reply(unknown).ok(), "未知坐骑道具应失败");
            assertTrue(java.nio.file.Files.exists(tempRoot.resolve(Path.of("data", "坐骑.json"))));

            // 帧序：骑乘 = NewRideRec 行 0x11 → 骑乘态 0x10；下马 = 0x12 删行 → 0x11 行 → 0x10。
            List<Byte> opcodes = new java.util.ArrayList<>();
            byte[] frame;
            while ((frame = env.channel.readOutbound()) != null) {
                opcodes.add(frame[0]);
            }
            assertEquals(List.of((byte) 0x11, (byte) 0x10,
                    (byte) 0x12, (byte) 0x11, (byte) 0x10), opcodes);
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /**
     * idle_stance_set：合法动作名（khbd_stand_01）切换 + PlayActionName 0x10 帧 +
     * data/待机站姿.json 落档；未知动作名拒绝；空参 = 重推当前站姿（不改档）。
     */
    @Test
    void idleStanceSetValidatesAndPersists(@TempDir Path tempRoot) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                copyResource(tempRoot, Path.of("resources", "modern", "ini", "b_action_index.ini")),
                "参考服 b_action_index.ini 不可用，跳过站姿断言");
        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            GmCommand set = env.enqueue("idle_stance_set", Map.of("stanceAction", "khbd_stand_01"));
            GmCommand bogus = env.enqueue("idle_stance_set", Map.of("stanceAction", "no_such_action"));
            GmCommand reapply = env.enqueue("idle_stance_set", Map.of("stanceAction", ""));
            env.flow.drainGmCommands(new Connection(env.channel), env.session);

            assertTrue(reply(set).ok(), "khbd_stand_01 应可切换");
            assertFalse(reply(bogus).ok(), "未知动作名应拒绝");
            assertTrue(reply(bogus).message().contains("不存在"));
            assertTrue(reply(reapply).ok(), "空参应重推当前站姿");
            assertTrue(reply(reapply).message().contains("khbd_stand_01"));
            assertTrue(java.nio.file.Files.exists(tempRoot.resolve(Path.of("data", "待机站姿.json"))));

            List<Byte> opcodes = new java.util.ArrayList<>();
            byte[] frame;
            while ((frame = env.channel.readOutbound()) != null) {
                opcodes.add(frame[0]);
            }
            // 切换 0x10 + 重推 0x10（拒绝路径零帧）。
            assertEquals(List.of((byte) 0x10, (byte) 0x10), opcodes);
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }

    /**
     * set_faction（Go set_faction 同序）：闭名单预设 → saveFaction CAS 落库
     * （appearance[8]，重开仓库可读回）→ 内存 faction + 专用单帧 0x10 →
     * 面板快照 faction/lastAction；未知预设失败且零帧。默认档 force_yihua → school_wudang。
     */
    @Test
    void factionSetPersistsSnapshotAndPushesFrame(@TempDir Path tempRoot) throws Exception {
        com.nineyin.server.db.role.RoleStoreFacade store = new com.nineyin.server.db.role.RoleStoreFacade(
                com.nineyin.server.db.role.JsonRoleRepository.open(tempRoot.resolve("roles.json")));
        GmHub hub = new GmHub();
        LoginFlow flow = new LoginFlow(store, null, hub, Clock.systemUTC(), null, null);
        EmbeddedChannel channel = new EmbeddedChannel();
        LoginSession session = new LoginSession();
        session.selected = store.create(store.login("faction-gm-test").account(), "赵灵儿", null);
        assertEquals(com.nineyin.server.db.role.RoleStoreFacade.DEFAULT_ROLE_FACTION,
                session.selected.appearance().values().get(8), "新档默认门派应为移花宫");
        session.player = PlayerActor.create("赵灵儿", 1, Clock.systemUTC());
        GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
        snapshot.sessionId = session.machine.id();
        snapshot.name = "赵灵儿";
        hub.attach(snapshot.sessionId, snapshot);

        GmCommand set = new GmCommand(session.machine.id(), "set_faction",
                new JSONObject(Map.of("faction", "wudang")));
        GmCommand unknown = new GmCommand(session.machine.id(), "set_faction",
                new JSONObject(Map.of("faction", "no_such_school")));
        hub.enqueue(set);
        hub.enqueue(unknown);
        flow.drainGmCommands(new Connection(channel), session);

        assertTrue(reply(set).ok(), "武当预设应成功: " + reply(set).message());
        assertTrue(reply(set).message().contains("武当"));
        assertTrue(reply(set).message().contains("school_wudang"));
        assertFalse(reply(unknown).ok(), "未知预设应失败");
        assertTrue(reply(unknown).message().contains("no_such_school"));

        // 内存 + 会话快照 + hub 快照同步。
        assertEquals("school_wudang", session.player.faction());
        assertEquals("school_wudang", session.selected.appearance().values().get(8));
        assertEquals("school_wudang", hub.get(session.machine.id()).faction);
        assertTrue(hub.get(session.machine.id()).lastAction.contains("武当"));

        // 持久化：重开仓库按 key 登录读回 appearance[8]（CAS 版本已随新 snapshot 前进）。
        assertEquals("school_wudang", com.nineyin.server.db.role.RoleStoreFacade.roleFaction(
                new com.nineyin.server.db.role.RoleStoreFacade(
                        com.nineyin.server.db.role.JsonRoleRepository.open(tempRoot.resolve("roles.json")))
                        .login("faction-gm-test").snapshot().appearance().values()));

        // 成功路径单帧 0x10（School/Force/NewSchool 三属性专用帧），拒绝路径零帧。
        byte[] frame = channel.readOutbound();
        assertTrue(frame != null && (frame[0] & 0xFF) == 0x10, "应写出 faction 单帧 0x10");
        assertNull(channel.readOutbound(), "未知预设不应写帧");
    }

    /**
     * 御风神水两动作全链：挂 = msg169（0x1E 壳九值 v0=0）+ 0x10 BufferInfo12 槽串
     * （StaticData 首字段 2835=10293 的十六进制）双帧；重复 add 同槽续期；摘 =
     * msg169(v0=1) + 空槽帧；scheduler 到期任务补发摘除双帧且续期后旧任务空转。
     */
    @Test
    void yufengAddRemoveRoundTrip() {
        java.time.Instant now = java.time.Instant.now();
        // 极简流（scheduler=null）不 NPE：挂/摘双帧照发，仅无到期补发。
        Env plain = new Env();
        GmCommand plainAdd = plain.enqueue("yufeng_add", Map.of());
        plain.flow.drainGmCommands(new Connection(plain.channel), plain.session);
        assertTrue(reply(plainAdd).ok(), "无调度器流添加御风应成功: " + reply(plainAdd).message());
        byte[] plainMsg = plain.channel.readOutbound();
        byte[] plainSlot = plain.channel.readOutbound();
        assertEquals(0x1E, plainMsg[0] & 0xFF, "msg169 壳");
        assertEquals(0x10, plainSlot[0] & 0xFF, "BufferInfo 槽帧");

        LoginFlowTest.ManualScheduler scheduler = new LoginFlowTest.ManualScheduler();
        Env env = new Env(scheduler);
        GmCommand add = env.enqueue("yufeng_add", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);

        assertTrue(reply(add).ok(), "添加御风应成功: " + reply(add).message());
        assertTrue(reply(add).message().contains("槽 12"), "首个空闲槽 12: " + reply(add).message());
        byte[] msgAdd = env.channel.readOutbound();
        byte[] slotAdd = env.channel.readOutbound();
        assertNull(env.channel.readOutbound(), "挂 = 恰好两帧");
        // msg169：0x1E + U16 九值 + tag2 int32 169 + tag2 v0=0 + tag6 窄串 buf_ride_yufeng。
        assertEquals(0x1E, msgAdd[0] & 0xFF);
        assertEquals(9, (msgAdd[1] & 0xFF) | (msgAdd[2] & 0xFF) << 8, "九值形态");
        assertEquals(2, msgAdd[3] & 0xFF, "首值 tag2 int32");
        assertEquals(169, (msgAdd[4] & 0xFF) | (msgAdd[5] & 0xFF) << 8
                | (msgAdd[6] & 0xFF) << 16 | (msgAdd[7] & 0xFF) << 24, "messageId 169");
        assertEquals(2, msgAdd[8] & 0xFF, "v0 tag2");
        assertEquals(0, (msgAdd[9] & 0xFF) | (msgAdd[10] & 0xFF) << 8
                | (msgAdd[11] & 0xFF) << 16 | (msgAdd[12] & 0xFF) << 24, "v0=0 挂");
        String addText = new String(msgAdd, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(addText.contains("buf_ride_yufeng"), "窄串 buff id");
        // 槽串：StaticData 10293 = 0x2835，OwnerID/Serial=1、Level=1、Count=1（十六进制）。
        assertEquals(0x10, slotAdd[0] & 0xFF);
        String slotText = new String(slotAdd, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(slotText.contains("2835,1,1,1,"), "BufferInfo 槽串首字段 2835: " + slotText);
        PlayerActor.ActiveBuffLease lease =
                env.session.player.activeBuffLease(PlayerActor.YUFENG_BUFF_CONFIG, now);
        assertTrue(lease != null && lease.slot() == 12, "租约落槽 12");

        // 重复 add：同槽续期（回执注明），仍恰好两帧，到期任务累积（旧任务将被空转）。
        GmCommand renew = env.enqueue("yufeng_add", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(renew).ok(), "续期应成功");
        assertTrue(reply(renew).message().contains("续期"), "回执注明续期");
        byte[] renewMsg = env.channel.readOutbound();
        byte[] renewSlot = env.channel.readOutbound();
        assertEquals(0x1E, renewMsg[0] & 0xFF);
        assertEquals(0x10, renewSlot[0] & 0xFF);
        assertNull(env.channel.readOutbound());
        PlayerActor.ActiveBuffLease renewed =
                env.session.player.activeBuffLease(PlayerActor.YUFENG_BUFF_CONFIG,
                        java.time.Instant.now());
        assertTrue(renewed != null && renewed.slot() == 12, "续期不换槽");
        assertTrue(!renewed.expiresUtc().isBefore(lease.expiresUtc()), "到期时间不早于原租约");

        // 摘：msg169(v0=1) + 空槽帧，租约清空。
        GmCommand remove = env.enqueue("yufeng_remove", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(remove).ok(), "取消御风应成功: " + reply(remove).message());
        assertTrue(reply(remove).message().contains("槽 12"));
        byte[] msgRemove = env.channel.readOutbound();
        byte[] slotRemove = env.channel.readOutbound();
        assertNull(env.channel.readOutbound(), "摘 = 恰好两帧");
        assertEquals(0x1E, msgRemove[0] & 0xFF);
        assertEquals(1, (msgRemove[9] & 0xFF) | (msgRemove[10] & 0xFF) << 8
                | (msgRemove[11] & 0xFF) << 16 | (msgRemove[12] & 0xFF) << 24, "v0=1 摘");
        assertEquals(0x10, slotRemove[0] & 0xFF);
        assertFalse(new String(slotRemove, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("2835"), "空槽帧无 StaticData");
        assertNull(env.session.player.activeBuffLease(
                PlayerActor.YUFENG_BUFF_CONFIG, java.time.Instant.now()), "租约已清");

        // 未持有时摘除：不失败，仍补发 msg169 重同步帧（无槽帧）。
        GmCommand removeEmpty = env.enqueue("yufeng_remove", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(removeEmpty).ok(), "未持有摘除应回执不失败");
        byte[] emptyMsg = env.channel.readOutbound();
        assertEquals(0x1E, emptyMsg[0] & 0xFF);
        assertNull(env.channel.readOutbound(), "无租约无槽帧");

        // 到期补发：再挂一次 → 跑全部到期任务（此前两次 add 的旧任务应空转）→
        // 最新任务摘除双帧；旧任务因租约到期已前移不再匹配，零多余帧。
        GmCommand addAgain = env.enqueue("yufeng_add", Map.of());
        env.flow.drainGmCommands(new Connection(env.channel), env.session);
        assertTrue(reply(addAgain).ok());
        env.channel.readOutbound();
        env.channel.readOutbound();
        for (Runnable task : List.copyOf(scheduler.tasks)) {
            task.run();
        }
        byte[] expiredMsg = env.channel.readOutbound();
        byte[] expiredSlot = env.channel.readOutbound();
        assertNull(env.channel.readOutbound(), "到期恰好补发两帧（旧任务空转）");
        assertEquals(0x1E, expiredMsg[0] & 0xFF);
        assertEquals(1, (expiredMsg[9] & 0xFF) | (expiredMsg[10] & 0xFF) << 8
                | (expiredMsg[11] & 0xFF) << 16 | (expiredMsg[12] & 0xFF) << 24, "v0=1 到期摘");
        assertEquals(0x10, expiredSlot[0] & 0xFF);
        assertFalse(new String(expiredSlot, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("2835"), "到期空槽帧");
        assertNull(env.session.player.activeBuffLease(
                PlayerActor.YUFENG_BUFF_CONFIG, java.time.Instant.now()), "到期后租约清空");
    }

    /**
     * 身份系统全链（手写最小 origin/prize/repute 表进临时根，combatDummy 同款
     * overrideServerRoot + finally 还原）：origin_activate_all 全量激活（Origin_Active
     * 单窄串行 0x11 × N，未领取状态不动）→ 702 领奖（未知物品跳过不阻断 + repute_*
     * 阵营叠加整刷 Repute 行 + PrestigeExp 单帧 + Origin_Prized 行 + 重复幂等零帧）→
     * 706 按内存镜像重推（激活 N + 领奖 M）→ 700 单激活（int/str 两口径；目录外拒绝零帧）。
     */
    @Test
    void originActivateAllAndPrizeRoundTrip(@TempDir Path tempRoot) throws Exception {
        Path originDir = tempRoot.resolve(Path.of("resources", "modern", "share", "task", "origin"));
        java.nio.file.Files.createDirectories(originDir);
        java.nio.file.Files.writeString(originDir.resolve("origin.ini"),
                "[1]\nType1=1\n\n[2]\nType1=1\n\n[3]\nType1=2\n");
        java.nio.file.Files.writeString(originDir.resolve("prize_item.ini"),
                "[2]\nr=unknown_item_x,1\nr=unknown_item_y,3\n");
        java.nio.file.Files.writeString(originDir.resolve("prize_title.ini"),
                "[2]\nr=5\n");
        java.nio.file.Files.writeString(originDir.resolve("prize_prop.ini"),
                "[2]\nr=repute_shaolin,50\nr=repute_other,7\n");
        Path ruleDir = tempRoot.resolve(Path.of("resources", "modern", "share", "rule"));
        java.nio.file.Files.createDirectories(ruleDir);
        java.nio.file.Files.writeString(ruleDir.resolve("repute_level.ini"),
                "[Repute]\nr=repute_shaolin,repute_jianghu\n\n[Level]\n1=0\n2=100\n3=1000\n");

        Path previousRoot = com.nineyin.util.ServerPaths.serverRoot();
        com.nineyin.util.ServerPaths.overrideServerRoot(tempRoot);
        try {
            Env env = new Env();
            env.session.selected = testRoleSnapshot();
            Connection connection = new Connection(env.channel);

            // 一键激活：3 行 Origin_Active（0x11），未领取状态不动。
            GmCommand all = env.enqueue("origin_activate_all", Map.of());
            env.flow.drainGmCommands(connection, env.session);
            assertTrue(reply(all).ok(), "一键激活应成功: " + reply(all).message());
            assertTrue(reply(all).message().contains("3 个身份"), "回执含计数");
            int activeRows = 0;
            byte[] frame;
            while ((frame = env.channel.readOutbound()) != null) {
                assertEquals(0x11, frame[0] & 0xFF, "Origin_Active 行帧");
                activeRows++;
            }
            assertEquals(3, activeRows, "恰三行激活");
            assertEquals(3, env.session.origins.size());
            assertEquals(1, env.session.origins.get("2"), "未领取状态不动");

            // 702 领奖（身份 2，窄串口径）：未知物品跳过不阻断；repute_shaolin +50
            //（repute_other 非阵营跳过）→ Repute 行 0x11 + PrestigeExp 0x10 + Origin_Prized 0x11。
            env.flow.handleOriginCustom(connection, env.session, CustomC2S.Message.of(List.of(
                    CustomC2S.Value.ofInt(702), CustomC2S.Value.ofText("2"))));
            byte[] reputeRow = env.channel.readOutbound();
            byte[] prestige = env.channel.readOutbound();
            byte[] prizedRow = env.channel.readOutbound();
            assertNull(env.channel.readOutbound(), "领奖恰三帧（无物品入包帧）");
            assertEquals(0x11, reputeRow[0] & 0xFF, "Repute_Record 行");
            assertEquals(0x10, prestige[0] & 0xFF, "PrestigeExp 单帧");
            assertEquals(0x11, prizedRow[0] & 0xFF, "Origin_Prized 行");
            assertEquals(2, env.session.origins.get("2"), "status=2 已领奖");
            assertEquals(50L, env.session.reputes.get("repute_shaolin"), "阵营叠加");
            assertFalse(env.session.reputes.containsKey("repute_other"), "非阵营属性跳过");
            assertEquals(50, env.session.player.panel().prestigeExp, "声望总量随帧");

            // 重复 702：幂等零帧。
            env.flow.handleOriginCustom(connection, env.session, CustomC2S.Message.of(List.of(
                    CustomC2S.Value.ofInt(702), CustomC2S.Value.ofText("2"))));
            assertNull(env.channel.readOutbound(), "重复领奖零帧");

            // 706：按内存镜像重推 3 激活 + 1 领奖 = 4 行。
            env.flow.handleOriginCustom(connection, env.session,
                    CustomC2S.Message.of(List.of(CustomC2S.Value.ofInt(706))));
            int replayed = 0;
            while ((frame = env.channel.readOutbound()) != null) {
                assertEquals(0x11, frame[0] & 0xFF);
                replayed++;
            }
            assertEquals(4, replayed, "重推 3 激活 + 1 领奖");

            // 700 单激活（新会话未做全量激活，int32 口径）：目录内入集一行。
            Env fresh = new Env();
            fresh.session.selected = testRoleSnapshot();
            Connection freshConnection = new Connection(fresh.channel);
            fresh.flow.handleOriginCustom(freshConnection, fresh.session, CustomC2S.Message.of(
                    List.of(CustomC2S.Value.ofInt(700), CustomC2S.Value.ofInt(3))));
            byte[] activated = fresh.channel.readOutbound();
            assertTrue(activated != null && (activated[0] & 0xFF) == 0x11, "700 激活一行");
            assertEquals(1, fresh.session.origins.size());
            assertEquals(1, fresh.session.origins.get("3"));

            // 目录外身份：拒绝零帧。
            fresh.flow.handleOriginCustom(freshConnection, fresh.session, CustomC2S.Message.of(
                    List.of(CustomC2S.Value.ofInt(700), CustomC2S.Value.ofText("999"))));
            assertNull(fresh.channel.readOutbound(), "目录外身份零帧");
            assertEquals(1, fresh.session.origins.size());
        } finally {
            com.nineyin.util.ServerPaths.overrideServerRoot(previousRoot);
        }
    }
}
