package com.nineyin.game;

import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.rule.AiRuleData;
import com.nineyin.rule.BuffCatalog;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.NpcTemplate;
import com.nineyin.scene.NpcTemplateRepository;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 事件引擎：四表加载、出生 buff、难度门控、死亡掉宝箱矩阵（难度 × 评价公式）、
 * HPRatio 周期事件、重生再出生、出招循环（伤害/大招/嗜血/回血/OpEvent24）；
 * 外加真实 share 数据冒烟（NINEYIN_SERVER_ROOT）。
 */
class AiEventEngineTest {

    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path tempDir;

    private AiRuleData rules;
    private NpcTemplateRepository templates;
    private AiEventEngine engine;
    private SceneWorld world;
    private NpcInstance boss;

    @BeforeEach
    void setup() throws Exception {
        Path share = buildShareRoot();
        rules = new AiRuleData();
        rules.load(share);
        templates = new NpcTemplateRepository();
        templates.load(share.resolve("npc").resolve("npcconfig"));
        engine = new AiEventEngine(rules, templates);
        world = new SceneWorld("clone003", true);
        boss = bossNpc("BossClone003005");
        world.addDynamic(boss);
    }

    @Test
    void loaderParsesFourTables() {
        assertEquals(2, rules.packageCount());
        assertEquals(8, rules.eventCount());
        assertEquals(3, rules.conditionCount());
        assertEquals(1, rules.formulaCount());
        var pkg = rules.pkg(4407);
        assertNotNull(pkg);
        assertEquals("暮色之村韩似锦（新）", pkg.text());
        assertEquals(6, pkg.eventIds().size());
        assertEquals(List.of("101", "&", "102"), rules.formula(300).tokens());
        var fn = rules.condition(100);
        assertEquals("Func", fn.name());
        assertEquals("8", fn.para1());
        assertEquals("1", fn.para2());
        assertEquals(30, rules.condition(102).max());
    }

    @Test
    void templateSkillTableParsed() {
        List<NpcTemplate.SkillRef> skills = templates.lookup("BossClone003005").skills();
        assertEquals(2, skills.size());
        assertEquals("clone003_hsj_01", skills.get(0).id());
        assertEquals("clone003_hsj_sx", skills.get(1).id());
        assertTrue(AiEventEngine.isBigSkill("clone003_zhoulifeng_sxks"));
        assertFalse(AiEventEngine.isBigSkill("clone003_zhoulifeng_01"));
    }

    @Test
    void buffCatalogResolvesStaticData() throws Exception {
        BuffCatalog catalog = new BuffCatalog();
        catalog.load(tempDir.resolve("share").resolve("skill"));
        assertEquals(1, catalog.size());
        assertEquals(123, catalog.staticDataOf("buff_test_hsj"));
        assertEquals(0, catalog.staticDataOf("unknown"));
    }

    @Test
    void rotationDamagesPlayerWithXixueHeal() {
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        engine.useRngForTest(new Random(42));   // 每次出招两掷（未命中 10%/暴击 5%），42 序列均不触发
        engine.onNpcDamaged(world, boss, fx, 4000L);
        engine.pump(world, 4000L);   // 出招第 1 拍：常规技 _01 攻击 120（25% 进瘀血，扣血 90）
        assertEquals(1000 - 90, login.actor.hp);
        assertEquals(30, login.actor.hitHp, "瘀血层应累计 25% 伤害");
        boss.hp = boss.maxHp / 2;
        boss.buffs.add("buff_bossclone003001_xixue");
        engine.pump(world, 8000L);   // 第 2 拍（间隔 4s）：拼音大招攻击 260（扣血 195）+ 嗜血回 1000
        assertEquals(1000 - 90 - 195, login.actor.hp);
        assertTrue(boss.hp > boss.maxHp / 2, "嗜血大招应回复 1000");
    }

    @Test
    void parryHalvesBossDamageAndBreaksOnEmpty() {
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        engine.useRngForTest(new Random(42));
        login.actor.parrying = true;
        engine.onNpcDamaged(world, boss, fx, 4000L);
        engine.pump(world, 4000L);   // 120 攻击 × 0.5 招架穿透 = 60（扣血 45/瘀血 15）；耐力 100−120 → 0 破招
        assertEquals(1000 - 45, login.actor.hp);
        assertEquals(15, login.actor.hitHp);
        assertEquals(0, login.actor.parryEndurance, "耐力耗尽招架被破");
        engine.pump(world, 8000L);   // 招架已破（耐力 0）：拼音大招全额 260 → 扣血 195
        assertEquals(1000 - 45 - 195, login.actor.hp);
    }

    /**
     * 普通怪普攻（无 AI 包 monster）：战斗窗口内 2s 间隔出招、攻击缺省 20；
     * 间隔内不出手；受控跳过；超出 4m 攻击距离不出手。
     */
    @Test
    void monsterBasicAttackDamagesPlayerOnInterval() {
        NpcInstance mon = bossNpc("mon_plain_001");   // 无 AI 包、无模板技能 → 纯普攻
        world.addDynamic(mon);
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        login.x = 10;
        login.z = -19;                                // 距怪 1m（贴身）
        engine.useRngForTest(new Random(42));
        engine.onNpcDamaged(world, mon, fx, 4000L);
        engine.pump(world, 4000L);                    // 首拍立即普攻（damage=20：扣血 15 + 瘀血 5）
        assertEquals(1000 - 15, login.actor.hp, "普攻 20：75% 扣血");
        assertEquals(5, login.actor.hitHp, "25% 进瘀血层");
        int afterFirst = login.actor.hp;
        engine.pump(world, 5000L);                    // 距上次 1s < 2s：不出手
        assertEquals(afterFirst, login.actor.hp, "间隔内不得重复出手");
        engine.pump(world, 7000L);                    // 距上次 3s ≥ 2s：再次普攻
        assertEquals(1000 - 30, login.actor.hp, "第二击扣血");
    }

    @Test
    void monsterOutOfReachDoesNotAttack() {
        NpcInstance mon = bossNpc("mon_far_001");
        world.addDynamic(mon);
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        login.x = 0;
        login.z = 0;                                  // 怪在 (10,-20)：距离 ≈22.4m
        engine.useRngForTest(new Random(42));
        engine.onNpcDamaged(world, mon, fx, 4000L);
        engine.pump(world, 4000L);
        engine.pump(world, 7000L);
        assertEquals(1000, login.actor.hp, "超出攻击距离不得出手");
    }

    @Test
    void controlledMonsterSkipsAttack() {
        NpcInstance mon = bossNpc("mon_ctrl_001");
        mon.controlledUntilMs = Long.MAX_VALUE;
        world.addDynamic(mon);
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        login.x = 10;
        login.z = -19;
        engine.useRngForTest(new Random(42));
        engine.onNpcDamaged(world, mon, fx, 4000L);
        engine.pump(world, 4000L);
        assertEquals(1000, login.actor.hp, "受控怪不出手");
    }

    /** 控制回调接线：BOSS 技命中玩家（未被招架）后 ControlSink 收到 skillId。 */
    @Test
    void controlSinkInvokedOnNpcSkillHit() {
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        engine.useRngForTest(new Random(42));
        List<String> invoked = new java.util.ArrayList<>();
        engine.setControlSink((actor, skillId, now) -> {
            invoked.add(skillId);
            return true;
        });
        engine.onNpcDamaged(world, boss, fx, 4000L);
        engine.pump(world, 4000L);   // 常规技 _01 命中 → sink 调用一次
        assertEquals(List.of("clone003_hsj_01"), invoked);
    }

    @Test
    void regenBuffHealsDuringCombat() {
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        boss.buffs.add("buff_clone003_hanshijin_HPMP");
        boss.hp = 1000;
        engine.useRngForTest(new Random(42));
        engine.onNpcDamaged(world, boss, fx, 4000L);
        engine.pump(world, 4000L);
        assertTrue(boss.hp > 1000, "HPMP 回血 buff 战斗中每秒回复");
        assertTrue(login.actor.hp < 1000, "出招同步进行");
    }

    @Test
    void opEvent24CastsSkillImmediately() {
        NpcInstance zlf = bossNpc("BossClone003001");   // 包 4399，无技能表（纯事件出招）
        world.addDynamic(zlf);
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        engine.useRngForTest(new Random(42));
        engine.onNpcDamaged(world, zlf, fx, 1000L);
        engine.pump(world, 1000L);   // 9007(OpEvent=24) 立即释放拼音大招（攻击 260，扣血 195）
        assertEquals(1000 - 195, login.actor.hp);
    }

    @Test
    void opEvent70SummonsGroupWithSpread() {
        NpcInstance zlf = bossNpc("BossClone003001");   // 包 4399 含 9008 召唤组
        world.addDynamic(zlf);
        engine.pump(world, 1000L);
        List<NpcInstance> summons = world.npcs.stream()
                .filter(n -> "ai_spawn".equals(n.tag)).toList();
        assertEquals(3, summons.size(), "召唤组应生成 3 只");
        assertTrue(summons.stream().anyMatch(n -> Math.abs(n.x - zlf.x) > 0.1f), "散开站位");
    }

    @Test
    void buffVisualFrameBroadcast() throws Exception {
        BuffCatalog catalog = new BuffCatalog();
        catalog.load(tempDir.resolve("share").resolve("skill"));
        List<byte[]> frames = new java.util.ArrayList<>();
        AiEventEngine visualEngine = new AiEventEngine(rules, templates, catalog);
        visualEngine.setBroadcaster(new AiEventEngine.Broadcaster() {
            @Override
            public void onSpawned(SceneWorld w, NpcInstance n) {
            }

            @Override
            public void onBossAction(SceneWorld w, byte[] frame) {
                frames.add(frame);
            }
        });
        visualEngine.pump(world, 1000L);   // 出生 9001 加 buff_test_hsj(static=123) → BufferInfo 帧
        assertTrue(frames.stream().anyMatch(f -> f.length > 0 && f[0] == 0x10),
                "应广播 0x10 BufferInfo 属性帧");
    }

    @Test
    void phaseTriggerCastsBigSkillAt30Percent() {
        List<byte[]> frames = new java.util.ArrayList<>();
        engine.setBroadcaster(new AiEventEngine.Broadcaster() {
            @Override
            public void onSpawned(SceneWorld w, NpcInstance n) {
            }

            @Override
            public void onBossAction(SceneWorld w, byte[] frame) {
                frames.add(frame);
            }
        });
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        engine.useRngForTest(new Random(42));
        boss.hp = boss.maxHp / 4;   // 25% ≤ 30%
        engine.onNpcDamaged(world, boss, fx, 4000L);
        engine.pump(world, 4000L);  // 阶段大招（扣血 195）+ 轮转常规（扣血 90）
        assertEquals(1000 - 195 - 90, login.actor.hp);
        assertTrue(frames.stream().anyMatch(f -> f.length > 0 && f[0] == 0x05),
                "应广播 0x05 喊话 worldInfo");
    }

    @Test
    void rollLootGradesByBoxLetter() {
        String aLoot = CloneLoot.rollLoot("BossBox003005A", "韩似锦", world);
        assertTrue(aLoot.startsWith("打开「韩似锦的宝箱」，获得了："));
        List<String> rares = List.of("上天梯", "雁行功", "三才阵秘籍", "门派一阶内功书页",
                "天魔追魂刀残卷", "铜制装备图谱", "铁制装备图谱");
        assertTrue(rares.stream().anyMatch(aLoot::contains), "甲等箱必含珍稀掉落");
        assertTrue(CloneLoot.rollLoot("BossBox003005C", "韩似锦", world).startsWith("打开「韩似锦的宝箱」"));
    }

    @Test
    void boxOpenFlowRemovesBoxAndAnnouncesLoot() throws Exception {
        NpcInstance box = new NpcInstance();
        box.configId = "BossBox003005A";
        box.x = 10;
        box.y = 0;
        box.z = -20;
        world.addDynamic(box);
        assertEquals(0x2103, CloneLoot.menuMarkFor(box, templates));
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        login.x = 10;
        login.z = -20;
        SceneFlow.SceneState st = new SceneFlow.SceneState();
        st.world = world;
        st.sentIds.add(box.objectId);
        SceneManager sm = new SceneManager(tempDir.resolve("share"));
        CloneLoot.delayOverrideMs = 50;
        try {
            assertTrue(CloneLoot.handleMenuAction(fx, login, st, sm,
                    box.objectId, CloneLoot.FUNC_OPEN_BOX));
            for (int i = 0; i < 50 && world.npcById(box.objectId) != null; i++) {
                Thread.sleep(20);
            }
            assertNull(world.npcById(box.objectId), "开箱读条后宝箱应移除");
            List<byte[]> out = new java.util.ArrayList<>();
            byte[] m;
            EmbeddedChannel ch = (EmbeddedChannel) fx.channel();
            while ((m = ch.readOutbound()) != null) {
                out.add(m);
            }
            assertTrue(out.stream().anyMatch(b -> b[0] == 0x05), "应发掉落 worldInfo");
        } finally {
            CloneLoot.delayOverrideMs = -1;
        }
    }

    @Test
    void boxOpenRejectedWhenTooFar() {
        NpcInstance box = new NpcInstance();
        box.configId = "BossBox003005A";
        box.x = 10;
        box.z = -20;
        world.addDynamic(box);
        FxSession fx = attachPlayer();
        LoginSession login = fx.channel().attr(LoginFlow.SESSION).get();
        login.x = 40;
        login.z = -20;   // 距离 30m > OpenRange 3m
        SceneFlow.SceneState st = new SceneFlow.SceneState();
        st.world = world;
        SceneManager sm = new SceneManager(tempDir.resolve("share"));
        assertTrue(CloneLoot.handleMenuAction(fx, login, st, sm, box.objectId, CloneLoot.FUNC_OPEN_BOX));
        assertNotNull(world.npcById(box.objectId), "距离不足不应移除宝箱");
    }

    @Test
    void bornEventsFireOnFirstPump() {
        engine.pump(world, 1000L);
        assertTrue(boss.buffs.contains("buff_test_hsj"), "出生事件应加基础 buff");
        assertFalse(boss.buffs.contains("buff_test_hard"), "难度 1 不应加困难 buff");
    }

    @Test
    void difficultyGatesBornBuff() {
        world.dungeonDifficulty = 2;
        engine.pump(world, 1000L);
        assertTrue(boss.buffs.containsAll(Set.of("buff_test_hsj", "buff_test_hard")));
    }

    @Test
    void deathBoxMatrixDifficulty1() {
        boss.hp = 0;
        engine.onNpcDeath(world, boss, 0);
        Set<String> spawned = spawnedConfigs();
        assertTrue(spawned.contains("BossBox003005A"), "普通难度应掉 A 箱");
        assertFalse(spawned.contains("BossBox003005B"));
        assertFalse(spawned.contains("BossBox003005C"), "公式=难度2&HP≤30 不满足");
    }

    @Test
    void deathBoxMatrixDifficulty2WithFormula() {
        world.dungeonDifficulty = 2;
        boss.hp = 0;   // 死亡时 HPRatio=0 落在 [0,30] 区间
        engine.onNpcDeath(world, boss, 0);
        Set<String> spawned = spawnedConfigs();
        assertTrue(spawned.contains("BossBox003005B"), "困难难度应掉 B 箱");
        assertTrue(spawned.contains("BossBox003005C"), "公式 101&102（难度2&HP≤30）应掉 C 箱");
        assertFalse(spawned.contains("BossBox003005A"));
    }

    @Test
    void periodicHpConditionFiresOnlyWhenLow() {
        engine.pump(world, 1000L);
        assertFalse(boss.buffs.contains("buff_test_enrage"), "满血不应触发");
        boss.hp = boss.maxHp / 5;   // 20% ≤ 30%
        engine.pump(world, 6000L);  // 泵间隔 + 事件重触发冷却均需跨过
        assertTrue(boss.buffs.contains("buff_test_enrage"), "HP≤30% 应触发周期事件");
    }

    @Test
    void respawnRefiresBornEvents() {
        engine.pump(world, 1000L);
        boss.buffs.clear();
        boss.dead = true;
        engine.pump(world, 2000L);            // 死亡期间重置出生标记
        boss.dead = false;
        boss.hp = boss.maxHp;
        engine.pump(world, 3200L);            // 重生后重新走出生事件
        assertTrue(boss.buffs.contains("buff_test_hsj"));
    }

    /** 真实 share 数据冒烟：包 4407 全矩阵 + 出生 buff + 难度 1 掉 A 箱。 */
    @Test
    void realShareBossAiSmoke() throws Exception {
        String env = System.getenv("NINEYIN_SERVER_ROOT");
        Assumptions.assumeTrue(env != null && !env.isEmpty(),
                "需要 NINEYIN_SERVER_ROOT 指向真实服务器根");
        Path share = Path.of(env, "resources", "modern", "share");
        Assumptions.assumeTrue(Files.isDirectory(share), "share 目录不存在: " + share);

        AiRuleData real = new AiRuleData();
        real.load(share);
        assertTrue(real.packageCount() > 1000, "真实包数量级");
        AiEventEngine realEngine = new AiEventEngine(real, new NpcTemplateRepository());
        var pkg = real.pkg(4407);
        assertNotNull(pkg, "韩似锦（新）包");
        assertTrue(pkg.eventIds().size() >= 25);
        List<String> deathBoxes = realEngine.eventsOf(pkg, 3).stream()
                .map(AiRuleData.AiEvent::para1)
                .filter(p -> p.startsWith("BossBox003005"))
                .collect(Collectors.toList());
        assertTrue(deathBoxes.containsAll(List.of(
                "BossBox003005A", "BossBox003005B", "BossBox003005C")), "九宫格 A/B/C 齐备");
        assertEquals(List.of("16111", "&", "16383"), real.formula(23706).tokens());
        assertEquals("Func", real.condition(16111).name());
        assertEquals(30, real.condition(17257).max());

        NpcTemplateRepository realTemplates = new NpcTemplateRepository();
        realTemplates.load(share.resolve("npc").resolve("npcconfig"));
        assertTrue(realTemplates.lookup("BossBox003005A").maxHp() >= 0, "宝箱模板存在");
        assertEquals("韩似锦", realTemplates.lookup("BossClone003005").name());
        List<NpcTemplate.SkillRef> zlfSkills = realTemplates.lookup("BossClone003001").skills();
        assertTrue(zlfSkills.size() >= 4, "周厉风技能表 01/02/03+大招");
        assertEquals("clone003_zhoulifeng_01", zlfSkills.get(0).id());
        assertTrue(zlfSkills.stream().anyMatch(s -> s.id().endsWith("sxks")), "嗜血狂杀在列");

        BuffCatalog realBuffs = new BuffCatalog();
        realBuffs.load(share.resolve("skill"));
        assertTrue(realBuffs.size() > 1000, "真实 buff 数量级");
        assertEquals(2938, realBuffs.staticDataOf("buf_boss_immunity"));
        assertEquals(7946, realBuffs.staticDataOf("buff_bossclone003001_xixue"));

        NpcTemplate realBox = realTemplates.lookup("BossBox003001A");
        assertEquals("BoxNpc", realBox.scriptClass());
        assertEquals("周厉风", realBox.name());
        assertNotNull(realBox.box(), "宝箱交互参数");
        assertEquals(3f, realBox.box().openRange());
        assertEquals("interact041", realBox.box().openAction());
        assertEquals(5000L, realBox.box().delayMs());

        realEngine = new AiEventEngine(real, realTemplates);
        SceneWorld realWorld = new SceneWorld("clone003", true);
        NpcInstance hsj = bossNpc("BossClone003005");
        realWorld.addDynamic(hsj);
        realEngine.pump(realWorld, System.currentTimeMillis());
        assertTrue(hsj.buffs.contains("buf_boss_immunity"), "出生自带 boss 免疫 buff");
        assertTrue(hsj.buffs.contains("buf_clone003_hsj"));
        hsj.hp = 0;
        realEngine.onNpcDeath(realWorld, hsj, 0);
        assertTrue(realWorld.npcs.stream().anyMatch(
                        n -> "BossBox003005A".equals(n.configId) && "ai_spawn".equals(n.tag)),
                "难度 1 死亡应生成 BossBox003005A");
    }

    // ---------- fixture ----------

    /** 裸通道玩家会话（含 actor），供出招伤害断言。 */
    private FxSession attachPlayer() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FxSession fx = FxSession.attach(ch);
        LoginSession login = new LoginSession(fx);
        login.actor = new PlayerActor();
        login.roleName = "test";
        ch.attr(LoginFlow.SESSION).set(login);
        return fx;
    }

    private NpcInstance bossNpc(String configId) {
        NpcInstance n = new NpcInstance();
        n.configId = configId;
        n.x = 10;
        n.y = 0;
        n.z = -20;
        n.orient = 1f;
        n.homeX = 10;
        n.homeY = 0;
        n.homeZ = -20;
        n.monster = true;
        var t = templates.lookup(configId);
        n.maxHp = t.maxHp() > 0 ? t.maxHp() : 1000;
        n.hp = n.maxHp;
        return n;
    }

    private Set<String> spawnedConfigs() {
        synchronized (world) {
            return world.npcs.stream()
                    .filter(n -> "ai_spawn".equals(n.tag))
                    .map(n -> n.configId)
                    .collect(Collectors.toSet());
        }
    }

    private Path buildShareRoot() throws Exception {
        Path share = tempDir.resolve("share");
        Path rule = share.resolve("rule");
        Path skill = share.resolve("skill");
        Path npcconfig = share.resolve("npc/npcconfig/clonenpc/funnpc");
        Files.createDirectories(rule);
        Files.createDirectories(skill);
        Files.createDirectories(npcconfig);
        Files.writeString(rule.resolve("ai_editor_package.ini"), """
                [4407]
                EventGroup=9001,9002,9003,9004,9005,9006
                Text=暮色之村韩似锦（新）

                [4399]
                EventGroup=9007,9008
                Text=暮色之村周厉风（新）
                """, GBK);
        Files.writeString(rule.resolve("ai_editor_event.ini"), """
                [9001]
                EventType=1
                OpRate=100
                OpType=1
                OpEvent=2
                Para1=buff_test_hsj

                [9002]
                EventType=1
                OpRate=100
                SelfCondition=101
                OpEvent=2
                Para1=buff_test_hard

                [9003]
                EventType=3
                OpRate=100
                SelfCondition=100
                OpEvent=25
                Para1=BossBox003005A

                [9004]
                EventType=3
                OpRate=100
                SelfCondition=101
                OpEvent=25
                Para1=BossBox003005B

                [9005]
                EventType=4
                OpRate=100
                SelfCondition=102
                OpEvent=2
                Para1=buff_test_enrage

                [9006]
                EventType=3
                OpRate=100
                SelfCondition=300
                OpEvent=25
                Para1=BossBox003005C

                [9007]
                EventType=4
                OpRate=100
                OpEvent=24
                Para1=clone003_hsj_sx

                [9008]
                EventType=1
                OpRate=100
                OpEvent=70
                Para1=SummonA,SummonB,SummonC
                """, GBK);
        Files.writeString(rule.resolve("condition.ini"), """
                [100]
                Type=19
                Name=Func
                Para1=8
                Para2=1

                [101]
                Type=19
                Name=Func
                Para1=8
                Para2=2

                [102]
                Type=1
                Name=HPRatio
                min=0
                max=30
                """, GBK);
        Files.writeString(rule.resolve("condition_formula.ini"), """
                [300]
                Formula=101&102
                """, GBK);
        Files.writeString(skill.resolve("buff_new.ini"), """
                [buff_test_hsj]
                StaticData=123
                TargetAddSelf=1
                """, GBK);
        Files.writeString(npcconfig.resolve("boxnpc.txt"), """
                编号\t脚本\t名字\t资源\tMaxHP\t技能表\t开箱距离\t开箱动作\t读条
                ID\tscript\tName\tResource\tMaxHP\ttable@SkillRec\tOpenRange\tOpenAction\tDelayTime
                BossClone003005\tbossnpc\t韩似锦\tclonenpc\\boss005\t50000\tclone003_hsj_01,1,0;clone003_hsj_sx,1,0\t\t\t
                BossBox003005A\tboxnpc\t韩似锦\tclonenpc\\box005a\t1\t\t3\tinteract041\t5000
                BossBox003005B\tboxnpc\t韩似锦\tclonenpc\\box005b\t1\t\t3\tinteract041\t5000
                BossBox003005C\tboxnpc\t韩似锦\tclonenpc\\box005c\t1\t\t3\tinteract041\t5000
                """, GBK);
        return share;
    }
}
