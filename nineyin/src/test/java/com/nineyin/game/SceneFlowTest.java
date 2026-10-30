package com.nineyin.game;

import com.nineyin.auth.AccountKeys;
import com.nineyin.db.JsonRoleStore;
import com.nineyin.db.RoleStore;
import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.PacketWriter;
import com.nineyin.scene.SceneManager;
import com.nineyin.server.FxOpcodeRouter;
import com.nineyin.server.FxServerHandler;
import com.nineyin.transport.FxConnection;
import com.nineyin.transport.FxFrame;
import com.nineyin.transport.FxFrameAccumulator;
import com.nineyin.transport.FxFrameDecoder;
import com.nineyin.transport.FxFrameEncoder;
import com.nineyin.transport.FxKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景同步全链路：登录→建角(book1 千灯镇 fixture)→进场景→NPC 下发/巡逻/打坐/快捷键/移动。 */
class SceneFlowTest {

    private static final Charset GBK = Charset.forName("GBK");
    private final FxKey key = FxKey.initial();

    @TempDir
    Path tempDir;

    private RoleStore roleStore;
    private EmbeddedChannel channel;

    @BeforeEach
    void setup() throws Exception {
        Path share = buildShareRoot();
        roleStore = new JsonRoleStore(tempDir.resolve("roles.json"));
        FxOpcodeRouter router = new FxOpcodeRouter();
        LoginFlow loginFlow = new LoginFlow(roleStore);
        // 技能目录：fixture ini 链（test_self 自 buff 技 / test_atk 单体伤害技）
        SkillCatalog skillCatalog = new SkillCatalog();
        skillCatalog.load(tempDir);
        SkillInitTable skillInit = new SkillInitTable();
        skillInit.load(share.resolve("skill").resolve("skill_init.ini"));
        // 充值商城货品 fixture（share 根上级的 ini/ 目录，对齐 resources/modern 布局）
        Path iniRoot = tempDir.resolve("ini");
        Files.createDirectories(iniRoot);
        Files.writeString(iniRoot.resolve("charge_shop_goods.ini"), """
                [Goods]
                1=fashion_qg_test,0202,2,0,880,1,0,0,0
                2=box_test_001,0400,2,0,15,1,0,0,0
                """, GBK);
        SceneFlow sceneFlow = new SceneFlow(new SceneManager(share), roleStore, new QinggongCatalog(),
                null, null, skillCatalog, skillInit);
        loginFlow.addReadyHook(sceneFlow::sendSceneNpcs);
        loginFlow.register(router);
        sceneFlow.register(router);
        FxConnection connection = new FxConnection();
        channel = new EmbeddedChannel(
                new FxFrameDecoder(connection),
                new FxFrameEncoder(connection),
                new FxServerHandler(router, connection));
    }

    /** book1 出生点 (874.9, -32.4, 746.7) 附近的 fixture 场景。 */
    private Path buildShareRoot() throws Exception {
        Path share = tempDir.resolve("share");
        Path npcconfig = share.resolve("npc/npcconfig/worldnpc");
        Files.createDirectories(npcconfig);
        Files.writeString(npcconfig.resolve("city_commonnpc.txt"),
                "编号\t脚本\t名字\t资源\nID\tscript\tName\tResource\n"
                        + "npc_qd_001\tcommonnpc\t张铁匠\tworldnpc\\qd_tiejiang\n"
                        + "npc_qd_002\tcommonnpc\t李药商\tworldnpc\\qd_yaoshang\n"
                        + "mon_test_001\tattacknpc\t山贼\tworldnpc\\qd_shanzei\n"
                        + "hd_act_001\tcommonnpc\t活动怪\tworldnpc\\act\n",
                GBK);
        Path creator = share.resolve("creator/npc_creator/born04_qiandengzhen");
        Files.createDirectories(creator);
        Files.writeString(creator.resolve("file.ini"), "1=FuncNpc.xml\n", GBK);
        Files.writeString(creator.resolve("FuncNpc.xml"), """
                <root>
                  <item x="874" y="1" z="746" ay="0" id="npc_qd_001" BornAction="talk"/>
                  <item x="880" y="1" z="750" id="npc_qd_002" PathId="AI_qd_001"/>
                  <item x="900" y="1" z="770" id="mon_test_001" PathId="AI_qd_001"/>
                  <item x="875" y="1" z="747" id="hd_act_001"/>
                </root>
                """, GBK);
        Path mappath = share.resolve("creator/mappath");
        Files.createDirectories(mappath);
        Files.writeString(mappath.resolve("born04_npc.ini"), """
                [AI_qd_001]
                r=,880,1,750,0,200
                r=,880.6,1,750.6,0,200
                r=,881.2,1,751.2,0,200
                """, GBK);
        writeSkillFixture(share);
        return share;
    }

    /** 技能编译链 fixture：test_self（自 buff，5s 持续 + 两段动作）/ test_atk（单体伤害 113）。 */
    private void writeSkillFixture(Path share) throws Exception {
        Path skill = share.resolve("skill");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("skill_new.ini"), """
                [test_self]
                StaticData=900001
                script=SkillNormal
                ItemType=1000
                AddCoolDownTime=7000

                [test_atk]
                StaticData=900002
                script=SkillNormal
                ItemType=1000
                """, GBK);
        Files.writeString(skill.resolve("skill_static.ini"), """
                [900001]
                MinVarPropNo=800001
                MaxVarPropNo=800005
                CoolDownCategory=12446
                CoolDownTeam=50267
                IsDamage=0
                TaoLu=test

                [900002]
                MinVarPropNo=800011
                MaxVarPropNo=800015
                CoolDownCategory=12447
                CoolDownTeam=50267
                IsDamage=1
                TaoLu=test
                """, GBK);
        Files.writeString(skill.resolve("skill_normal_varprop.ini"), """
                [800001]
                Level=1
                Consume=15608
                HitShapePkg=1
                CoolDownTime=15000

                [800011]
                Level=1
                Consume=15608
                HitShapePkg=1
                CoolDownTime=15000
                DamageCalculate=23033
                """, GBK);
        Files.writeString(skill.resolve("skill_consume.ini"), """
                [15608]
                AConsumeMP=11
                AConsumeSP=0
                AConsumeQGP=0
                """, GBK);
        Files.writeString(skill.resolve("damage_calculate.ini"), """
                [23033]
                r=494,JujDamage,113,1,1,
                """, GBK);
        Files.writeString(skill.resolve("attack_hitshape.ini"), """
                [1]
                HitShape=0
                HitShapePara1=0
                HitShapePara2=0
                """, GBK);
        Files.writeString(skill.resolve("buff_new.ini"), """
                [buf_test_self]
                StaticData=15105
                LifeTime=5000
                """, GBK);
        Files.writeString(skill.resolve("buff_static.ini"), """
                [15105]
                IsDamage=0
                MinVarPropNo=296913
                MaxVarPropNo=296913
                """, GBK);
        Files.writeString(skill.resolve("buff_varprop.ini"), """
                [296913]
                Level=1
                LifeTime=5000
                """, GBK);
        Files.writeString(skill.resolve("skill_init.ini"), """
                [test_self]
                staticData=900001
                itemType=1000
                level=1
                maxlevel=1
                coolDown=0

                [test_atk]
                staticData=900002
                itemType=1000
                level=1
                maxlevel=1
                coolDown=0
                """, GBK);
        Path action = tempDir.resolve("ini/action");
        Files.createDirectories(action);
        Files.writeString(action.resolve("zhaoshi_player.ini"), """
                [test_self]
                1=self_act;0;30;-1;-1;1
                2=self_act2;0;20;0;0;1

                [test_atk]
                1=atk_act;0;43;-1;-1;1
                """, GBK);
    }

    private void send(byte[] plain) {
        channel.writeInbound(Unpooled.wrappedBuffer(FxFrame.encode(plain, key)));
    }

    private List<byte[]> drainOutbound() {
        List<byte[]> frames = new ArrayList<>();
        FxFrameAccumulator acc = new FxFrameAccumulator(FxFrame.DEFAULT_MAX_FRAME_SIZE);
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            byte[] wire = new byte[buf.readableBytes()];
            buf.readBytes(wire);
            int pos = 0;
            while (pos < wire.length) {
                pos += acc.feed(wire, pos, wire.length - pos);
                if (acc.hasCompleteFrame()) {
                    frames.add(acc.takeFrame(key));
                }
            }
        }
        return frames;
    }

    private static int op(byte[] f) {
        return f[0] & 0xFF;
    }

    private static byte[] loginPacket() {
        byte[] blob = new byte[32];
        PacketWriter w = new PacketWriter().u8(0x02);
        while (w.size() < 0x15) {
            w.u8(0);
        }
        return w.u32(32).raw(blob).toByteArray();
    }

    private static byte[] createRolePacket() {
        PacketWriter w = new PacketWriter().u8(0x05).u32(0);
        w.raw("行走江湖".getBytes(StandardCharsets.UTF_16LE)).u16(0);
        while (w.size() < 0x49) {
            w.u8(0);
        }
        for (String s : List.of("book1")) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            w.u8(6).u32(b.length + 1).raw(b).u8(0);
        }
        return w.toByteArray();
    }

    /** 67 字节移动位置包：0x26 起连续 4 组 (类型 4 + f32)。 */
    private static byte[] movePacket(float x, float y, float z, float orient) {
        PacketWriter w = new PacketWriter().u8(0x0A);
        while (w.size() < 0x26) {
            w.u8(0);
        }
        w.u8(4).f32(x).u8(4).f32(y).u8(4).f32(z).u8(4).f32(orient);
        while (w.size() < 67) {
            w.u8(0);
        }
        return w.toByteArray();
    }

    /** 0x0A 自定义消息（21 字节头 + 类型化值）。 */
    private static PacketWriter custom() {
        PacketWriter w = new PacketWriter().u8(0x0A);
        while (w.size() < 21) {
            w.u8(0);
        }
        return w;
    }

    private static byte[] sitCmd(int cmd) {
        return custom().u16(2).u8(2).i32(240).u8(2).i32(cmd).toByteArray();
    }

    private static byte[] shortcutSet(int idx, String kind, String id) {
        byte[] k = kind.getBytes(StandardCharsets.UTF_8);
        byte[] i = id.getBytes(StandardCharsets.UTF_8);
        return custom().u16(4)
                .u8(2).i32(200)
                .u8(2).i32(idx)
                .u8(6).u32(k.length + 1).raw(k).u8(0)
                .u8(6).u32(i.length + 1).raw(i).u8(0)
                .toByteArray();
    }

    @Test
    void fullSceneFlow() throws Exception {
        // 登录 → 建角 → 选角 → Ready
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();

        send(new byte[]{0x09});
        List<byte[]> ready = drainOutbound();
        // NPC 下发：3 个 NPC（活动怪被滤除），每个 AddObject(0x0D) + Location(0x1F)
        List<byte[]> adds = ready.stream().filter(f -> op(f) == 0x0D).toList();
        List<byte[]> locs = ready.stream().filter(f -> op(f) == 0x1F).toList();
        assertEquals(3, adds.size());
        assertEquals(3, locs.size());
        // 第一个 AddObject：oid=10001，Type 属性（181）=4
        byte[] first = adds.get(0);
        assertEquals(10001, leU32(first, 1));
        // 属性区从 0x3F 起：u16 181 + byte 4
        int p = 0x3F;
        assertEquals(181, (first[p] & 0xFF) | ((first[p + 1] & 0xFF) << 8));
        assertEquals(4, first[p + 2]);
        // 活动怪不在下发列表
        for (byte[] a : adds) {
            int oid = leU32(a, 1);
            assertTrue(oid <= 10003, "hd_act_001 must be filtered, got oid " + oid);
        }

        // 打坐：240 cmd 1 → LogicState 102 + State "sitdown"；同一条 0x0A 还触发首轮巡逻广播 0x20×2
        send(sitCmd(1));
        List<byte[]> sitBatch = drainOutbound();
        List<byte[]> sit = sitBatch.stream().filter(f -> op(f) == 0x10).toList();
        assertEquals(2, sit.size());
        assertEquals(462, (sit.get(0)[12] & 0xFF) | ((sit.get(0)[13] & 0xFF) << 8));
        assertEquals(102, sit.get(0)[14]);
        assertEquals(183, (sit.get(1)[12] & 0xFF) | ((sit.get(1)[13] & 0xFF) << 8));
        assertTrue(containsUtf8(sit.get(1), "sitdown"));
        assertEquals(2, sitBatch.stream().filter(f -> op(f) == 0x20).count(),
                "两个巡逻 NPC 各广播一条 0x20");

        // 移动：取消打坐（LogicState 0 + stand）+ 位置落库（500ms 节流内不再广播巡逻）
        send(movePacket(875.5f, -31f, 748f, 0.5f));
        List<byte[]> afterMove = drainOutbound();
        assertTrue(afterMove.stream().anyMatch(f -> op(f) == 0x10
                && f[12] == (byte) 0xCE && f[13] == 0x01 && f[14] == 0), "sit must be cancelled");
        String accountKey = AccountKeys.parseLoginAccountKey(loginPacket());
        assertEquals(875.5f, roleStore.get(accountKey).getX(), 0.001f, "position must persist");

        // 到达确认：等腿到达后（单腿 travel 下限 700ms + wait 200ms）任意 0x0A 触发 0x1F
        Thread.sleep(1100);
        send(sitCmd(0)); // 任意 0x0A
        List<byte[]> afterArrive = drainOutbound();
        assertTrue(afterArrive.stream().anyMatch(f -> op(f) == 0x1F),
                "arrive confirm 0x1F expected");

        // 快捷键：200 设 idx 5 → 0x11 增行（权威行在 actor 上：打坐 1 + 心佛掌 7 + 太极 8 + 本次 1）
        send(shortcutSet(5, "skill", "CS_jh_xfz01"));
        List<byte[]> sc = drainOutbound();
        assertTrue(sc.stream().anyMatch(f -> op(f) == 0x11 && containsUtf8(f, "CS_jh_xfz01")),
                "shortcut add row expected");
        LoginSession login = channel.attr(LoginFlow.SESSION).get();
        assertEquals(17, login.actor.shortcuts().size());
    }

    @Test
    void termsSign819MarksAgreedAndPushesProperties() throws Exception {
        // 登录 → 建角 → 选角 → Ready（819 在进场景后由 dispatchCustom 处理）
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();

        String accountKey = AccountKeys.parseLoginAccountKey(loginPacket());
        assertEquals(0, roleStore.termsState(accountKey)[0], "登录初始应为未同意");

        // 首次 819：落库 agreed=1 + at>0，并回推属性 269（签署时间）/270（签署标志）
        send(custom().u16(1).u8(2).i32(819).toByteArray());
        List<byte[]> out = drainOutbound();
        long[] t = roleStore.termsState(accountKey);
        assertEquals(1, t[0]);
        assertTrue(t[1] > 0, "同意时间必须记录");
        assertTrue(out.stream().anyMatch(f -> op(f) == 0x10
                && f[12] == 0x0D && f[13] == 0x01), "应回推属性 269 签署时间");
        assertTrue(out.stream().anyMatch(f -> op(f) == 0x10
                && f[12] == 0x0E && f[13] == 0x01), "应回推属性 270 签署标志");

        // 重复 819（客户端每分钟重弹再点）：幂等，时间不刷新
        send(custom().u16(1).u8(2).i32(819).toByteArray());
        drainOutbound();
        assertEquals(t[1], roleStore.termsState(accountKey)[1], "重复同意不得刷新时间");
    }

    @Test
    void pkAndClientTogglesMatchGoServer() throws Exception {
        // 登录 → 建角 → 选角 → Ready
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();
        LoginSession login = channel.attr(LoginFlow.SESSION).get();

        // 291 PK 模式 =2：0x10 推送 PKMode(436=0x01B4, short) 值 2
        send(custom().u16(2).u8(2).i32(291).u8(2).i32(2).toByteArray());
        List<byte[]> pk = drainOutbound();
        assertTrue(pk.stream().anyMatch(f -> op(f) == 0x10
                        && f[12] == (byte) 0xB4 && f[13] == 0x01
                        && f[14] == 2 && f[15] == 0),
                "291 应以 i16 推送属性 436=2");
        assertEquals(2, login.pkMode);

        // 291 非法模式 5：拒绝，不推 436，状态不变
        send(custom().u16(2).u8(2).i32(291).u8(2).i32(5).toByteArray());
        List<byte[]> badPk = drainOutbound();
        assertTrue(badPk.stream().noneMatch(f -> op(f) == 0x10
                        && f[12] == (byte) 0xB4 && f[13] == 0x01),
                "非法模式不得推送 436");
        assertEquals(2, login.pkMode);

        // 292 警告开关：无参 toggle 0→1→0（0x10 属性待抓包钉死，只验状态）
        send(custom().u16(1).u8(2).i32(292).toByteArray());
        drainOutbound();
        assertTrue(login.warningOn, "292 首次应翻转为开");
        send(custom().u16(1).u8(2).i32(292).toByteArray());
        drainOutbound();
        assertFalse(login.warningOn, "292 二次应翻转为关");

        // 293 PK 保护：[type=1, onoff=1] 落状态
        send(custom().u16(3).u8(2).i32(293).u8(2).i32(1).u8(2).i32(1).toByteArray());
        drainOutbound();
        assertEquals(1, login.pkProtectType);
        assertTrue(login.pkProtectOn);

        // 61 显示装备类型：type=1 落状态；负值拒绝
        send(custom().u16(2).u8(2).i32(61).u8(2).i32(1).toByteArray());
        drainOutbound();
        assertEquals(1, login.showEquipType);
        send(custom().u16(2).u8(2).i32(61).u8(2).i32(-1).toByteArray());
        drainOutbound();
        assertEquals(1, login.showEquipType, "负值不得覆盖");
    }

    private static byte[] useSkill(String id) {
        byte[] b = id.getBytes(StandardCharsets.UTF_8);
        return custom().u16(2)
                .u8(2).i32(211)
                .u8(6).u32(b.length + 1).raw(b).u8(0)
                .toByteArray();
    }

    /** 211 校验链与释放：未学习拒绝 → 自 buff 技完整链（CD 行/vital/State/扣蓝）→ CD 内拒绝 → 需目标无选中拒绝。 */
    @Test
    void skill211ValidationAndCast() throws Exception {
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();
        LoginSession login = channel.attr(LoginFlow.SESSION).get();

        // 未学习技能：拒绝且零技能帧（0x0A 可能伴随巡逻广播 0x20，只断言无 0x11/0x10 技能响应）、零扣费
        send(useSkill("no_such_skill"));
        List<byte[]> rejected = drainOutbound();
        assertTrue(rejected.stream().noneMatch(f -> op(f) == 0x11 || op(f) == 0x10),
                "unlearned skill must not emit skill frames: " + rejected.size());
        assertEquals(500, login.actor.mp);

        // 已学习 + 可编译：0x11 cooldown 行（表 5）→ 0x10 vital → 0x10 State 起手动作
        send(useSkill("test_self"));
        List<byte[]> cast = drainOutbound();
        List<byte[]> skillFrames = cast.stream().filter(f -> op(f) == 0x11 || op(f) == 0x10).toList();
        assertEquals(3, skillFrames.size(), "cooldown row + vital + state, got " + skillFrames.size());
        assertEquals(0x11, op(skillFrames.get(0)));
        assertEquals(5, leU32(skillFrames.get(0), 10) & 0xFFFF, "cooldown_rec = table index 5");
        assertEquals(0x10, op(skillFrames.get(1)));
        assertEquals(0x10, op(skillFrames.get(2)));
        assertTrue(containsUtf8(skillFrames.get(2), "self_act"), "State 起手动作 self_act");
        assertEquals(489, login.actor.mp, "MP 500 - 11");
        assertEquals("test_self", login.actor.curSkillId);
        assertEquals("test_self", login.actor.curSkillEffectId);
        assertEquals(1, login.actor.curSkillLevel);
        assertEquals(PlayerActor.LOGIC_STATE_FIGHTING, login.actor.logicState);

        // 个人 CD（15s）内再放：拒绝且不重复扣费
        send(useSkill("test_self"));
        List<byte[]> cooled = drainOutbound();
        assertTrue(cooled.stream().noneMatch(f -> op(f) == 0x11 || op(f) == 0x10),
                "cooldown rejection must not emit skill frames");
        assertEquals(489, login.actor.mp);

        // 需目标技能而无选中目标：拒绝
        send(useSkill("test_atk"));
        List<byte[]> noTarget = drainOutbound();
        assertTrue(noTarget.stream().noneMatch(f -> op(f) == 0x11 || op(f) == 0x10),
                "no selected target must reject without skill frames");
        assertEquals(489, login.actor.mp);
    }

    /**
     * 2026-10-30 custom 轮：exe 钉死布局的消息（924/560）+ 日志对齐桩
     * （144/180/209/249/270/382/517/784/865/868/943）——全部走 C2S 校验层，
     * 除 924 回显外无 S2C 帧，金币/状态不被错误修改。
     */
    @Test
    void customRoundNailedMsgIdsValidateAndEcho() throws Exception {
        // 登录后未选角：560 早到只记日志（charge shop 560 arrived before role selection）
        send(loginPacket());
        drainOutbound();
        send(custom().u16(4).u8(2).i32(560).u8(2).i32(3).u8(2).i32(1).u8(2).i32(1).toByteArray());
        assertTrue(drainOutbound().isEmpty(), "before role selection: no reply");

        // 完成登录链
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();
        LoginSession login = channel.attr(LoginFlow.SESSION).get();

        // 924 消息延迟：合法 [924, i64 tick] → 回显 0x1E custom（msgId 924 + 同 tick）
        send(custom().u16(2).u8(2).i32(924).u8(3).i64(0x1122334455L).toByteArray());
        List<byte[]> delay = drainOutbound().stream().filter(f -> op(f) == 0x1E).toList();
        assertEquals(1, delay.size(), "924 must echo one custom frame");
        byte[] echo = delay.get(0);
        assertEquals(2, (echo[1] & 0xFF) | ((echo[2] & 0xFF) << 8), "echo value count = 2");
        assertEquals(2, echo[3] & 0xFF, "msgId encoded as i32");
        assertEquals(924, leU32(echo, 4));
        assertEquals(3, echo[8] & 0xFF, "tick encoded as i64");
        assertEquals(0x1122334455L, leU64(echo, 9));

        // 924 错误布局（f64 tick）：拒绝，无回显
        send(custom().u16(2).u8(2).i32(924).u8(5).f64(1.0).toByteArray());
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) == 0x1E),
                "malformed 924 must not echo");

        // 560 cmd=2（展示领取）：忽略
        send(custom().u16(2).u8(2).i32(560).u8(2).i32(2).toByteArray());
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) == 0x1E || op(f) == 0x10));

        // 560 购买参数个数错误（expects [560,3,charge_id,nums]）：无响应
        send(custom().u16(3).u8(2).i32(560).u8(2).i32(3).u8(2).i32(1).toByteArray());
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) == 0x1E || op(f) == 0x10));

        // 560 未知 charge_id：no goods 拒绝
        send(custom().u16(4).u8(2).i32(560).u8(2).i32(3).u8(2).i32(99).u8(2).i32(1).toByteArray());
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) == 0x1E || op(f) == 0x10));

        // 560 合法购买（charge_id=1, nums=1）：JSON 存档无库存后端 → has no bag，金币不动
        send(custom().u16(4).u8(2).i32(560).u8(2).i32(3).u8(2).i32(1).u8(2).i32(1).toByteArray());
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) == 0x1E || op(f) == 0x10));
        assertEquals(99999, login.actor.gold, "no-bag reject must not charge");

        // 943 兵录：缺 sub / 非 int sub / unhandled sub（含 name/num 摘要）
        send(custom().u16(1).u8(2).i32(943).toByteArray());
        send(custom().u16(2).u8(2).i32(943).u8(6).u32(4).raw("abc".getBytes()).u8(0).toByteArray());
        byte[] nm = "dao".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(4).u8(2).i32(943).u8(2).i32(1)
                .u8(6).u32(nm.length + 1).raw(nm).u8(0).u8(2).i32(5).toByteArray());
        assertTrue(drainOutbound().isEmpty(), "943 branches emit no frames");

        // 784 乱斗：缺 sub / 非 int sub / sub=10 OPEN_LIMITE / 非 10 忽略
        send(custom().u16(1).u8(2).i32(784).toByteArray());
        byte[] bad = "x".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(784).u8(6).u32(bad.length + 1).raw(bad).u8(0).toByteArray());
        send(custom().u16(4).u8(2).i32(784).u8(2).i32(10).u8(2).i32(2).u8(2).i32(3001).toByteArray());
        send(custom().u16(2).u8(2).i32(784).u8(2).i32(3).toByteArray());
        assertTrue(drainOutbound().isEmpty(), "784 branches emit no frames");

        // 865 踢馆：坏 sub / 合法 6、7（分组场景生命周期未实装，日志对齐）
        send(custom().u16(2).u8(2).i32(865).u8(2).i32(9).toByteArray());
        send(custom().u16(2).u8(2).i32(865).u8(2).i32(6).toByteArray());
        send(custom().u16(2).u8(2).i32(865).u8(2).i32(7).toByteArray());
        // 868 轮盘：缺 action / action=start
        send(custom().u16(1).u8(2).i32(868).toByteArray());
        byte[] act = "start".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(868).u8(6).u32(act.length + 1).raw(act).u8(0).toByteArray());
        // 180 卡片 / 209 任务 / 270 暮色道具技 / 382 关闭掉落 / 144 副本结算 / 249 御风确认 / 517 GM 探针
        send(custom().u16(2).u8(2).i32(180).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(209).u8(2).i32(1).toByteArray());
        byte[] tw = "tw_skill_1".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(270).u8(6).u32(tw.length + 1).raw(tw).u8(0).toByteArray());
        send(custom().u16(1).u8(2).i32(382).toByteArray());
        send(custom().u16(1).u8(2).i32(144).toByteArray());
        send(custom().u16(1).u8(2).i32(249).toByteArray());
        send(custom().u16(2).u8(2).i32(517).u8(2).i32(7).toByteArray());
        assertTrue(drainOutbound().isEmpty(),
                "stub handlers emit no frames (patrol 0x20 excluded by empty scene state)");
    }

    private static long leU64(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24)
                | ((b[off + 4] & 0xFFL) << 32) | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48) | ((b[off + 7] & 0xFFL) << 56);
    }

    /** 第四轮：编号钉死批（215/19/301/688/692/700~706/713/770/864/145/380）校验层，全部无 S2C 帧。 */
    @Test
    void customRound4PinnedMsgIdsValidate() throws Exception {
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();

        byte[] ng = "ng_wudang_01".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(215).u8(6).u32(ng.length + 1).raw(ng).u8(0).toByteArray());

        // 19 时装：无 sub / int sub / str sub 三分支
        send(custom().u16(1).u8(2).i32(19).toByteArray());
        send(custom().u16(2).u8(2).i32(19).u8(2).i32(3).toByteArray());
        byte[] fs = "wear".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(19).u8(6).u32(fs.length + 1).raw(fs).u8(0).toByteArray());

        // 301 VIP：无命令 / 非整数命令 / 未知命令
        send(custom().u16(1).u8(2).i32(301).toByteArray());
        send(custom().u16(2).u8(2).i32(301).u8(6).u32(fs.length + 1).raw(fs).u8(0).toByteArray());
        send(custom().u16(2).u8(2).i32(301).u8(2).i32(9).toByteArray());

        // 688 珍宝阁：非 int 参数 / 全 int unhandled sub
        send(custom().u16(2).u8(2).i32(688).u8(6).u32(fs.length + 1).raw(fs).u8(0).toByteArray());
        send(custom().u16(3).u8(2).i32(688).u8(2).i32(1).u8(2).i32(2).toByteArray());

        // 692 面料：缺参 / 带参
        send(custom().u16(1).u8(2).i32(692).toByteArray());
        send(custom().u16(3).u8(2).i32(692).u8(2).i32(1).u8(2).i32(2).toByteArray());

        // origin 家族：缺 id / 坏类型 / 合法 id（700/701/702/705/706 各一）
        for (int mid : new int[]{700, 701, 702, 705, 706}) {
            send(custom().u16(1).u8(2).i32(mid).toByteArray());
            send(custom().u16(2).u8(2).i32(mid).u8(6).u32(fs.length + 1).raw(fs).u8(0).toByteArray());
            send(custom().u16(2).u8(2).i32(mid).u8(2).i32(7).toByteArray());
        }

        send(custom().u16(2).u8(2).i32(713).u8(6).u32(fs.length + 1).raw(fs).u8(0).toByteArray());
        send(custom().u16(3).u8(2).i32(770).u8(2).i32(1).u8(2).i32(2).toByteArray());
        byte[] job = "fishing".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(864).u8(6).u32(job.length + 1).raw(job).u8(0).toByteArray());
        send(custom().u16(2).u8(2).i32(145).u8(2).i32(1).toByteArray());
        send(custom().u16(1).u8(2).i32(380).toByteArray());
        send(custom().u16(2).u8(2).i32(380).u8(2).i32(4).toByteArray());
        send(custom().u16(2).u8(2).i32(380).u8(6).u32(fs.length + 1).raw(fs).u8(0).toByteArray());
        // 未知 msgId：default 分支（对齐 unhandled custom msg_id）也不得发帧
        send(custom().u16(2).u8(2).i32(777).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(777).u8(2).i32(1).toByteArray());

        // 巡逻广播（0x20，任意 0x0A 驱动、500ms 节流）与本轮无关，排除后必须无帧
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) != 0x20),
                "round-4 validation handlers emit no frames");
    }

    /** 第六轮：反汇编钉死批（1/34/71/99/150/161/195/215/326/388/353/359/361/961）；99 走 223 同路推 OperateMode。 */
    @Test
    void customRound6DisasmPinnedMsgIds() throws Exception {
        send(loginPacket());
        drainOutbound();
        send(createRolePacket());
        drainOutbound();
        send(new byte[]{0x04});
        drainOutbound();
        send(new byte[]{0x09});
        drainOutbound();
        LoginSession login = channel.attr(LoginFlow.SESSION).get();

        // 99 操作模式（反汇编修正：Go switch 的 case 值；223/5223 保留兼容）→ 与 223 同路
        send(custom().u16(2).u8(2).i32(99).u8(2).i32(1).toByteArray());
        List<byte[]> mode = drainOutbound().stream().filter(f -> op(f) == 0x10).toList();
        assertEquals(1, mode.size(), "99 should push one 0x10 like 223");
        assertEquals(1, login.operateMode);

        // 聊天 / 道具动作 / 经脉 / 快捷键方案 / 修炼进度 / 组队 / 邮件 / 骑乘技能：校验层无帧
        send(custom().u16(2).u8(2).i32(1).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(34).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(71).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(150).u8(2).i32(1).toByteArray());
        byte[] nm = "ren".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(161).u8(6).u32(nm.length + 1).raw(nm).u8(0).toByteArray());
        send(custom().u16(2).u8(2).i32(195).u8(2).i32(2).toByteArray());
        byte[] ng = "ng_test".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(215).u8(6).u32(ng.length + 1).raw(ng).u8(0).toByteArray());
        send(custom().u16(2).u8(2).i32(326).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(388).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(353).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(359).u8(2).i32(1).toByteArray());
        send(custom().u16(2).u8(2).i32(361).u8(2).i32(1).toByteArray());
        byte[] rs = "ride_1".getBytes(StandardCharsets.UTF_8);
        send(custom().u16(2).u8(2).i32(961).u8(6).u32(rs.length + 1).raw(rs).u8(0).toByteArray());
        assertTrue(drainOutbound().stream().noneMatch(f -> op(f) != 0x20),
                "round-6 validation handlers emit no frames (99 excluded above)");

        // 1 聊天：未出生拒绝（登录后未 Ready 前无 actor——上面已完成 Ready，此处验证 actor 分支即可）
        // （actor 存在时走 pending 分支，同样无帧）
    }

    private static boolean containsUtf8(byte[] frame, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= frame.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (frame[i + j] != b[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int leU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
