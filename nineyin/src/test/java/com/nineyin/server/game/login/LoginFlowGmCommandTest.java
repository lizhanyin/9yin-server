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
            session.player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
            GmHub.PlayerSnapshot snapshot = new GmHub.PlayerSnapshot();
            snapshot.sessionId = session.machine.id();
            snapshot.name = "李逍遥";
            hub.attach(snapshot.sessionId, snapshot);
            flow = new LoginFlow(null, null, hub, Clock.systemUTC(), null, null);
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
}
