package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.db.StoredRole;
import com.nineyin.gm.GmCommand;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * GM 命令服务端执行器（对齐 Go processGMCommand 的动作集）：
 * 资源（碎银/怒气/速度/重力）、瞬移/切图（含预设）、门派、协议探针
 * （NPC 气泡/章节卡/红霸体/State 动作）。kick/minimal_login 在 NineyinServer。
 */
public final class GmActions {

    private static final Logger log = LoggerFactory.getLogger(GmActions.class);

    /** GM 假玩家 oid 段：800001 起（玩家互播 500001+、NPC 静态 10001+/动态 900001+ 之外）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DUMMY_OID_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(800001);

    /** 门派预设（白名单，对齐 Go gmFactionPresets）。 */
    public record FactionPreset(String label, String faction) {
    }

    public static final Map<String, FactionPreset> FACTIONS = Map.ofEntries(
            Map.entry("shaolin", new FactionPreset("少林", "school_shaolin")),
            Map.entry("wudang", new FactionPreset("武当", "school_wudang")),
            Map.entry("emei", new FactionPreset("峨眉", "school_emei")),
            Map.entry("gaibang", new FactionPreset("丐帮", "school_gaibang")),
            Map.entry("tangmen", new FactionPreset("唐门", "school_tangmen")),
            Map.entry("junzitang", new FactionPreset("君子堂", "school_junzitang")),
            Map.entry("jinyiwei", new FactionPreset("锦衣卫", "school_jinyiwei")),
            Map.entry("jilegu", new FactionPreset("极乐谷", "school_jilegu")),
            Map.entry("mingjiao", new FactionPreset("明教", "school_mingjiao")),
            Map.entry("tianshan", new FactionPreset("天山派", "school_tianshan")),
            Map.entry("kunlun", new FactionPreset("昆仑派", "school_kunlun")),
            Map.entry("xingtianbao", new FactionPreset("刑天城", "school_xingtianbao")),
            Map.entry("yihuagong", new FactionPreset("移花宫", "force_yihua")),
            Map.entry("taohuadao", new FactionPreset("桃花岛", "force_taohua")),
            Map.entry("xujia", new FactionPreset("徐家庄", "force_xujia")),
            Map.entry("wanshou", new FactionPreset("万兽山庄", "force_wanshou")),
            Map.entry("jinzhen", new FactionPreset("金针沈家", "force_jinzhen")),
            Map.entry("wugenmen", new FactionPreset("无根门", "force_wugen")),
            Map.entry("tianlun", new FactionPreset("天轮寺", "force_tianlun")),
            Map.entry("qingyi", new FactionPreset("青衣阁", "force_qingyi")),
            Map.entry("xuedao", new FactionPreset("血刀门", "newschool_xuedao")),
            Map.entry("huashan", new FactionPreset("华山派", "newschool_huashan")),
            Map.entry("gumu", new FactionPreset("古墓派", "newschool_gumu")),
            Map.entry("damo", new FactionPreset("达摩派", "newschool_damo")),
            Map.entry("shenshui", new FactionPreset("神水宫", "newschool_shenshui")),
            Map.entry("changfeng", new FactionPreset("长风镖局", "newschool_changfeng")),
            Map.entry("nianluo", new FactionPreset("念萝坝", "newschool_nianluo")),
            Map.entry("wuxian", new FactionPreset("无仙教", "newschool_wuxian")),
            Map.entry("shenji", new FactionPreset("神机营", "newschool_shenji")),
            Map.entry("xingmiao", new FactionPreset("星渺阁", "newschool_xingmiao")),
            Map.entry("tianya", new FactionPreset("天涯海阁", "newschool_tianya")),
            Map.entry("wanghui", new FactionPreset("王回洲", "newschool_wanghui")),
            Map.entry("shenjihui", new FactionPreset("神机汇", "newschool_shenjihui")));

    /** 场景预设（config,resource,x,y,z,orient；对齐 Go gmScenePresets）。 */
    public static final Map<String, String> SCENE_PRESETS = Map.ofEntries(
            Map.entry("yanjing", "ini\\scene\\city01_YanJing,city01,837.625,-35.939,-750.860,0.680"),
            Map.entry("suzhou", "ini\\scene\\city02_SuZhou,city02,438.140,0.800,1188.282,-2.325"),
            Map.entry("jinling", "ini\\scene\\city03_JinLing,city03,1675.078,10.726,-101.141,0.335"),
            Map.entry("luoyang", "ini\\scene\\city04_LuoYang,city04,243.387,43.277,403.821,5.024"),
            Map.entry("chengdu", "ini\\scene\\city05_ChengDu,city05,911.073,31.192,537.350,1.742"),
            Map.entry("jimingyi", "ini\\scene\\born01_MoHeZhen,born01,998.817,8.364,530.187,-1.498"),
            Map.entry("yanyuzhuang", "ini\\scene\\born03_YanYuZhuang,born03,870.655,58.287,717.367,-1.513"),
            Map.entry("qiandengzhen", "ini\\scene\\born04_QianDengZhen,born04,883.000,-29.000,664.000,4.680"),
            Map.entry("jinyiwei", "ini\\scene\\school01_JinYiWei,school01,480.046,49.653,473.986,0.621"),
            Map.entry("gaibang", "ini\\scene\\school02_GaiBang,school02,522.804,10.774,694.848,2.056"),
            Map.entry("junzitang", "ini\\scene\\school03_JunZiTang,school03,1168.737,9.440,515.075,4.740"),
            Map.entry("jilegu", "ini\\scene\\school04_JiLeGu,school04,851.084,14.440,373.077,2.892"),
            Map.entry("tangmen", "ini\\scene\\school05_TangMen,school05,716.682,6.774,495.249,5.705"),
            Map.entry("emei", "ini\\scene\\school06_EMei,school06,687.422,36.738,726.217,5.836"),
            Map.entry("wudang", "ini\\scene\\school07_WuDang,school07,324.751,-0.405,812.974,2.137"),
            Map.entry("shaolin", "ini\\scene\\school08_ShaoLin,school08,517.669,-52.775,670.125,2.537"),
            Map.entry("yihuagong", "ini\\scene\\school09_YiHuaGong,school09,340.160,47.979,657.284,2.588"),
            Map.entry("taohuadao", "ini\\scene\\school10_taohuadao,school10,-603.500,95.779,-259.100,0.255"),
            Map.entry("wugenmen", "ini\\scene\\school11_wugenmen,school11,34.200,4.600,245.500,1.500"),
            Map.entry("xujia", "ini\\scene\\city05_ChengDu,city05,569.750,39.045,272.750,0.000"),
            Map.entry("jinzhen", "ini\\scene\\city05_ChengDu,city05,354.750,81.061,800.000,0.000"),
            Map.entry("wanshou", "ini\\scene\\school02_GaiBang,school02,338.321,87.146,1016.193,2.927"),
            Map.entry("tianlun", "ini\\scene\\adv156_tianlun,adv156,-45.777,-34.792,-243.900,0.000"),
            Map.entry("qingyi", "ini\\scene\\adv158_qingyi,adv158,1316.718,21.183,1389.666,0.000"),
            Map.entry("changfeng", "ini\\scene\\city03_JinLing,city03,1582.000,9.000,470.000,0.000"),
            Map.entry("wanghui", "ini\\scene\\school28_wanghui,school28,119.267,12.734,3.625,0.000"),
            Map.entry("xuedao", "ini\\scene\\born02_ERenGU,born02,552.000,32.500,922.000,0.000"),
            Map.entry("huashan", "ini\\scene\\school17_huashanpai,school17,1563.800,133.500,694.700,0.000"),
            Map.entry("gumu", "ini\\scene\\school14_gumupai,school14,-277.595,355.876,-325.481,0.000"),
            Map.entry("damo", "ini\\scene\\school18_damo,school18,-373.021,276.227,-573.989,0.000"),
            Map.entry("shenshui", "ini\\scene\\school19_shenshuigong,school19,2269.324,162.736,-1379.467,0.000"),
            Map.entry("nianluo", "ini\\scene\\school15_nianluoba,school15,2133.586,108.031,1007.968,0.000"),
            Map.entry("wuxian", "ini\\scene\\school12_wuxianjiao,school12,1451.899,49.252,799.405,0.000"),
            Map.entry("shenji", "ini\\scene\\school21_shenjiying,school21,-45.400,18.600,-46.900,0.000"),
            Map.entry("xingmiao", "ini\\scene\\school23_xingmiaoge,school23,259.598,246.446,70.540,0.000"),
            Map.entry("tianya", "ini\\scene\\school25_tianya,school25,1745.635,18.729,1066.640,0.000"),
            Map.entry("shenjihui", "ini\\scene\\school31_shenjihui,school31,-48.765,-153.637,-4770.149,0.000"),
            // 踢馆传送（GM 网页「踢馆传送」面板，34 个；坐标取各势力 NPC 簇质心，
            // 双龙堡数据实际摆在君子堂（面板标峨眉，峨眉地图无摆位））
            Map.entry("tiguan_1", "ini\\scene\\city05_ChengDu,city05,907.800,22.400,775.800,0.000"),
            Map.entry("tiguan_2", "ini\\scene\\born04_QianDengZhen,born04,401.300,-4.600,986.100,0.000"),
            Map.entry("tiguan_3", "ini\\scene\\city05_ChengDu,city05,278.000,76.400,776.800,0.000"),
            Map.entry("tiguan_4", "ini\\scene\\city05_ChengDu,city05,584.800,38.800,282.900,0.000"),
            Map.entry("tiguan_5", "ini\\scene\\city05_ChengDu,city05,296.300,105.800,214.600,0.000"),
            Map.entry("tiguan_6", "ini\\scene\\born02_ERenGU,born02,-50.700,8.400,-32.100,0.000"),
            Map.entry("tiguan_7", "ini\\scene\\born03_YanYuZhuang,born03,266.400,26.500,1271.300,0.000"),
            Map.entry("tiguan_8", "ini\\scene\\scene02_PengLaiXianJing,scene02,-114.700,49.400,610.400,0.000"),
            Map.entry("tiguan_9", "ini\\scene\\scene05_NianLuoBa,scene05,1773.700,89.300,1065.500,0.000"),
            Map.entry("tiguan_10", "ini\\scene\\scene07_MeiHuaLing,scene07,780.400,36.500,574.700,0.000"),
            Map.entry("tiguan_11", "ini\\scene\\school02_GaiBang,school02,550.500,53.200,885.100,0.000"),
            Map.entry("tiguan_12", "ini\\scene\\school08_ShaoLin,school08,-220.000,40.000,484.400,0.000"),
            Map.entry("tiguan_13", "ini\\scene\\born03_YanYuZhuang,born03,1009.800,80.700,499.300,0.000"),
            Map.entry("tiguan_14", "ini\\scene\\scene03_FanHuangPingYuan,scene03,1102.800,-14.400,897.400,0.000"),
            Map.entry("tiguan_15", "ini\\scene\\city01_YanJing,city01,1540.200,-3.100,1489.900,0.000"),
            Map.entry("tiguan_16", "ini\\scene\\school05_TangMen,school05,823.300,30.500,751.900,0.000"),
            Map.entry("tiguan_17", "ini\\scene\\school05_TangMen,school05,72.300,16.800,765.400,0.000"),
            Map.entry("tiguan_18", "ini\\scene\\city01_YanJing,city01,349.100,-143.700,1375.000,0.000"),
            Map.entry("tiguan_20", "ini\\scene\\city04_LuoYang,city04,1459.200,17.000,-195.600,0.000"),
            Map.entry("tiguan_21", "ini\\scene\\born02_ERenGU,born02,-391.500,43.200,843.600,0.000"),
            Map.entry("tiguan_22", "ini\\scene\\scene06_LingXiaoCheng,scene06,580.400,152.400,125.300,0.000"),
            Map.entry("tiguan_23", "ini\\scene\\city01_YanJing,city01,1451.600,107.400,-87.100,0.000"),
            Map.entry("tiguan_24", "ini\\scene\\scene08_SaiWaiDaMo,scene08,181.000,31.600,-312.000,0.000"),
            Map.entry("tiguan_25", "ini\\scene\\born03_YanYuZhuang,born03,-608.600,13.600,703.700,0.000"),
            Map.entry("tiguan_26", "ini\\scene\\city04_LuoYang,city04,422.700,-0.400,943.700,0.000"),
            Map.entry("tiguan_27", "ini\\scene\\born01_MoHeZhen,born01,-205.800,164.900,841.900,0.000"),
            Map.entry("tiguan_28", "ini\\scene\\city04_LuoYang,city04,18.500,-4.100,320.000,0.000"),
            Map.entry("tiguan_29", "ini\\scene\\school05_TangMen,school05,995.800,40.400,345.300,0.000"),
            Map.entry("tiguan_30", "ini\\scene\\school07_WuDang,school07,884.600,-6.300,764.500,0.000"),
            Map.entry("tiguan_31", "ini\\scene\\school01_JinYiWei,school01,1088.200,44.900,649.400,0.000"),
            Map.entry("tiguan_32", "ini\\scene\\scene08_SaiWaiDaMo,scene08,1322.000,44.500,928.100,0.000"),
            Map.entry("tiguan_33", "ini\\scene\\school03_JunZiTang,school03,31.300,0.700,1074.600,0.000"),
            Map.entry("tiguan_34", "ini\\scene\\adv148,adv148,-75.800,17.100,-142.300,0.000"),
            Map.entry("tiguan_35", "ini\\scene\\adv153,adv153,-13.000,54.700,-150.400,0.000"));

    private final SceneManager sceneManager;
    private final RoleStore roleStore;
    private final SceneSwitcher switcher;
    private final SceneFlow sceneFlow;
    private final NinJiuZhi ninjiuzhi;
    private final WorldBossManager worldBoss;
    /** 称号库（game_titles/role_titles；null=JSON 后端，称号动作回退会话内存）。 */
    private final com.nineyin.db.TitleStore titleStore;

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher) {
        this(sceneManager, roleStore, switcher, null, null);
    }

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher,
                     SceneFlow sceneFlow) {
        this(sceneManager, roleStore, switcher, sceneFlow, null);
    }

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher,
                     SceneFlow sceneFlow, NinJiuZhi ninjiuzhi) {
        this(sceneManager, roleStore, switcher, sceneFlow, ninjiuzhi, null);
    }

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher,
                     SceneFlow sceneFlow, NinJiuZhi ninjiuzhi, WorldBossManager worldBoss) {
        this(sceneManager, roleStore, switcher, sceneFlow, ninjiuzhi, worldBoss, null);
    }

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher,
                     SceneFlow sceneFlow, NinJiuZhi ninjiuzhi, WorldBossManager worldBoss,
                     com.nineyin.db.TitleStore titleStore) {
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
        this.switcher = switcher;
        this.sceneFlow = sceneFlow;
        this.ninjiuzhi = ninjiuzhi != null ? ninjiuzhi : NinJiuZhi.alwaysOpen();
        this.worldBoss = worldBoss;
        this.titleStore = titleStore;
    }

    /** 执行一条命令；返回错误消息（null = 成功）。未知动作返回提示。 */
    public String execute(FxSession fx, LoginSession session, SceneFlow.SceneState st, GmCommand cmd) {
        PlayerActor actor = session.actor;
        if (actor == null) {
            return "角色尚未进入场景";
        }
        switch (cmd.action()) {
            case "reload_scripts" -> {
                // 技能/公式 Lua 热重载（scripts/；重建脚本 state + 清技能定义缓存，不重启）
                if (sceneFlow == null) {
                    return "场景系统未接线";
                }
                String result = sceneFlow.reloadScripts();
                log.info("GM reload scripts: {}", result);
                return result;
            }
            case "set_silver" -> {
                long silver = cmd.params().getLongValue("silver");
                actor.silver = Math.max(0, silver);
                fx.send(silverUpdate(actor));
                return null;
            }
            case "set_gold" -> {
                long gold = cmd.params().getLongValue("gold");
                actor.gold = Math.max(0, gold);
                fx.send(goldUpdate(actor));
                return null;
            }
            case "send_test_mail" -> {
                // 对齐 Go GM 面板动作：`GM send_test_mail config=%s amount=%d rows=%d`
                String config = cmd.params().getString("itemConfig");
                int amount = cmd.params().getIntValue("amount");
                if (config == null || config.isBlank()) {
                    return "itemConfig 不能为空";
                }
                if (sceneFlow == null) {
                    return "邮件系统未接线";
                }
                sceneFlow.sendTestMail(fx, session, config, amount <= 0 ? 1 : amount);
                return null;
            }
            case "give_item" -> {
                // GM 发物品入包（扩容包/道具测试用；走 SceneFlow.giveItem 容量感知路径）
                String config = cmd.params().getString("itemConfig");
                int amount = cmd.intParam("amount", 1);
                if (config == null || config.isBlank()) {
                    return "itemConfig 不能为空";
                }
                if (sceneFlow == null) {
                    return "库存系统未接线";
                }
                return sceneFlow.giveItem(fx, session, config, amount <= 0 ? 1 : amount);
            }
            case "spawn_dummy" -> {
                // 玩家互播目验（单客户端）：注入假玩家 add+location 帧（oid 800001 段）；
                // 前端可带 dummyHp → 补发 HP(464)/MaxHP(577) 属性帧
                int oid = DUMMY_OID_SEQ.getAndIncrement();
                int hp = cmd.intParam("dummyHp", 0);
                float dx = session.x + 3f;
                float dz = session.z + 3f;
                session.dummyOids.add(oid);
                fx.send(S2CPackets.playerAddObject(oid, S2CPackets.PLAYER_OWNER_ID,
                        "木桩侠" + (oid - 800000), session.appearance, dx, session.y, dz, session.orient));
                fx.send(S2CPackets.serverLocation(oid, S2CPackets.PLAYER_OWNER_ID,
                        dx, session.y, dz, session.orient));
                if (hp > 0) {
                    fx.send(S2CPackets.objectPropertyInt(oid, S2CPackets.PLAYER_OWNER_ID, 577, hp));
                    fx.send(S2CPackets.objectPropertyInt(oid, S2CPackets.PLAYER_OWNER_ID, 464, hp));
                }
                return "dummy oid=" + oid + (hp > 0 ? " hp=" + hp : "");
            }
            case "despawn_dummy" -> {
                for (int oid : session.dummyOids) {
                    fx.send(S2CPackets.removeObject(oid, S2CPackets.PLAYER_OWNER_ID));
                }
                int n = session.dummyOids.size();
                session.dummyOids.clear();
                return "removed " + n;
            }
            case "set_sp" -> {
                actor.sp = clamp(cmd.intParam("sp", 0), 0, PlayerActor.MAX_SP);
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "add_sp" -> {
                actor.sp = clamp(actor.sp + cmd.intParam("sp", 0), 0, PlayerActor.MAX_SP);
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "fill_sp" -> {
                actor.sp = PlayerActor.MAX_SP;
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "terms_agree" -> {
                boolean first = roleStore.markTermsAgreed(session.accountKey);
                long[] t = roleStore.termsState(session.accountKey);
                session.termsAgreed = (int) t[0];
                session.termsAgreedAtSec = t[1];
                log.info("GM terms agreed account={} first={}", session.accountKey, first);
                return "用户须知已同意" + (first ? "（首次，时间已记录）" : "（此前已同意）");
            }
            case "terms_reset" -> {
                roleStore.resetTermsAgreed(session.accountKey);
                session.termsAgreed = 0;
                session.termsAgreedAtSec = 0;
                log.info("GM terms reset account={}", session.accountKey);
                return "用户须知已重置为未同意";
            }
            case "terms_status" -> {
                long[] t = roleStore.termsState(session.accountKey);
                return "agreed=" + t[0] + " agreed_at=" + t[1];
            }
            case "set_move_speed" -> {
                float value = clamp(cmd.params().getFloatValue("value"), 0.1f, 100f);
                actor.moveSpeed = value;
                actor.runSpeed = value;
                fx.send(S2CPackets.batch(1, 1)
                        .f32(PropertyId.MOVE_SPEED, value)
                        .f32(PropertyId.RUN_SPEED, value)
                        .build());
                return null;
            }
            case "set_gravity" -> {
                float value = clamp(cmd.params().getFloatValue("value"), 0.1f, 100f);
                actor.gravity = value;
                fx.send(S2CPackets.batch(1, 1)
                        .f32(PropertyId.GRAVITY, value)
                        .build());
                return null;
            }
            case "teleport" -> {
                float[] pos = parsePosition(cmd.strParam("position"));
                if (pos == null) {
                    return "坐标格式应为 x,y,z,orient";
                }
                switcher.teleportWithinScene(fx, session, st, pos[0], pos[1], pos[2], pos[3]);
                return null;
            }
            case "switch_scene" -> {
                String[] dest = parseDestination(cmd.strParam("destination"));
                if (dest == null) {
                    return "目的地格式应为 config,resource,x,y,z,orient";
                }
                if (!ninjiuzhi.sceneOpen(dest[1])) {
                    return "场景 " + dest[1] + " 未到九阴志开放阶段（当前 " + ninjiuzhi.stage() + "）";
                }
                switcher.switchScene(fx, session, st, dest[0], dest[1],
                        Float.parseFloat(dest[2]), Float.parseFloat(dest[3]),
                        Float.parseFloat(dest[4]), Float.parseFloat(dest[5]));
                return null;
            }
            case "switch_scene_preset" -> {
                String destination = SCENE_PRESETS.get(cmd.strParam("preset"));
                if (destination == null) {
                    return "未知场景预设: " + cmd.strParam("preset");
                }
                String[] dest = parseDestination(destination);
                if (!ninjiuzhi.sceneOpen(dest[1])) {
                    return "场景 " + dest[1] + " 未到九阴志开放阶段（当前 " + ninjiuzhi.stage() + "）";
                }
                switcher.switchScene(fx, session, st, dest[0], dest[1],
                        Float.parseFloat(dest[2]), Float.parseFloat(dest[3]),
                        Float.parseFloat(dest[4]), Float.parseFloat(dest[5]));
                return null;
            }
            case "set_faction" -> {
                FactionPreset preset = FACTIONS.get(cmd.strParam("faction"));
                if (preset == null) {
                    return "未知门派预设: " + cmd.strParam("faction");
                }
                actor.faction = preset.faction();
                S2CPackets.Batch b = S2CPackets.playerBatch();
                S2CPackets.appendFaction(b, preset.faction());
                fx.send(b.build());
                persistFaction(session, preset.faction());
                return null;
            }
            case "npc_bubble" -> {
                int oid = st.selectedOid != 0 ? st.selectedOid
                        : (st.world != null && !st.world.npcs.isEmpty() ? st.world.npcs.get(0).objectId : 0);
                if (oid == 0) {
                    return "场景内没有 NPC";
                }
                fx.send(CustomPackets.npcBubble(oid, 1, cmd.strParam("value").isEmpty()
                        ? "ui_shop" : cmd.strParam("value")));
                return null;
            }
            case "drama_prompt" -> {
                fx.send(CustomPackets.dramaPrompt(1, "ui_shop", 1, 1, "ui_shop", "", "", 0));
                return null;
            }
            case "red_super_armor" -> {
                actor.activateBuff(PlayerActor.BUFF_SLOT_RED_ARMOR,
                        PlayerActor.RED_ARMOR_STATIC, 20000, "", 1);
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "action_state_fishing" -> {
                sendState(fx, "interact268");
                return null;
            }
            case "action_state_hsqs05" -> {
                sendState(fx, "hsqs_05_h");
                return null;
            }
            case "action_state_sgmd" -> {
                sendState(fx, "hsqs_07");
                return null;
            }
            case "action_state_stop" -> {
                sendState(fx, "stand");
                return null;
            }
            case "action_native_fishing" -> {
                fx.send(CustomPackets.nativeAction(1, 1, "interact268"));
                return null;
            }
            // ---------- 世界 BOSS（定时刷新由 WorldBossManager 周期驱动） ----------
            case "worldboss_spawn" -> {
                if (worldBoss == null) {
                    return "世界BOSS模块未启用";
                }
                int spawned = worldBoss.forceSpawnAll();
                fx.send(S2CPackets.worldInfo(0,
                        "世界BOSS：强制生成 " + spawned + " 个刷新点。" + worldBoss.describe()));
                return null;
            }
            case "worldboss_clear" -> {
                if (worldBoss == null) {
                    return "世界BOSS模块未启用";
                }
                int removed = worldBoss.clearAll();
                fx.send(S2CPackets.worldInfo(0, "世界BOSS：已清除 " + removed + " 只在场BOSS"));
                return null;
            }
            case "worldboss_list" -> {
                if (worldBoss == null) {
                    return "世界BOSS模块未启用";
                }
                fx.send(S2CPackets.worldInfo(0, worldBoss.describe()));
                return null;
            }
            // ---------- 暮色剧情验证（clone003） ----------
            case "twilight_stages_start" -> {
                return twilightStagesStart(fx, session, st);
            }
            case "twilight_stages_remove" -> {
                return twilightStagesRemove(fx, session, st);
            }
            case "twilight_mechanism_start" -> {
                TwilightVerify.ensureScene(fx, session, st, switcher);
                if (st.world == null) {
                    st.world = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
                }
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
                NpcInstance box = TwilightVerify.spawn(sceneManager, st.world,
                        TwilightVerify.BOX_CONFIG, TwilightVerify.BOX_POS, TwilightVerify.TAG_BOX);
                TwilightVerify.sendNpc(fx, st, sceneManager, box);
                return null;
            }
            case "twilight_mechanism_remove" -> {
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
                return null;
            }
            case "twilight_npc_move_start" -> {
                TwilightVerify.ensureScene(fx, session, st, switcher);
                if (st.world == null) {
                    st.world = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
                }
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
                NpcInstance villager = TwilightVerify.spawn(sceneManager, st.world,
                        TwilightVerify.VILLAGER_CONFIG, TwilightVerify.VILLAGER_POS,
                        TwilightVerify.TAG_VILLAGER);
                TwilightVerify.sendNpc(fx, st, sceneManager, villager);
                return null;
            }
            case "twilight_npc_move_remove" -> {
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
                return null;
            }
            case "twilight_difficulty" -> {
                int level = cmd.intParam("level", 1);
                if (level < 1 || level > 4) {
                    return "level 应为 1~4（普通/困难/地狱/修罗）";
                }
                int rating = Math.max(1, Math.min(3, cmd.intParam("rating", 1)));
                SceneWorld world = st.world != null && st.world.cloneScene
                        ? st.world : sceneManager.world(TwilightVerify.SCENE_RESOURCE);
                world.dungeonDifficulty = level;
                world.dungeonRating = rating;
                fx.send(S2CPackets.worldInfo(0,
                        "副本难度：" + difficultyName(level) + "，评价档：" + rating));
                return null;
            }
            // ---------- 九阴志全局门控 ----------
            case "ninjiuzhi_set" -> {
                String err = ninjiuzhi.setStage(cmd.intParam("stage", 1));
                if (err != null) {
                    return err;
                }
                fx.send(S2CPackets.worldInfo(0, ninjiuzhi.status()));
                return null;
            }
            case "ninjiuzhi_event" -> {
                String kind = cmd.strParam("eventKind");
                if (!kind.equals("kill") && !kind.equals("task")) {
                    return "eventKind 应为 kill 或 task";
                }
                ninjiuzhi.addEvent(kind);
                fx.send(S2CPackets.worldInfo(0, ninjiuzhi.status()));
                return null;
            }
            case "ninjiuzhi_reset" -> {
                ninjiuzhi.reset();
                fx.send(S2CPackets.worldInfo(0, ninjiuzhi.status()));
                return null;
            }
            case "ninjiuzhi_status" -> {
                fx.send(S2CPackets.worldInfo(0, ninjiuzhi.status()));
                return null;
            }
            // ---------- menu2 面板动作补齐（第一批：属性/称号/气泡/物品/宝箱/探针，
            // 帧语义逆向自 9yin-game-native-menu2.exe，属性 ID 按名字换算本服表，见 docs/gm-menu2-actions.md） ----------
            case "set_guanyin" -> {  // 官银 CapitalType2=417
                long guanYin = cmd.params().getLongValue("guanYin");
                actor.guanYin = Math.max(0, guanYin);
                fx.send(S2CPackets.playerBatch()
                        .i64(PropertyId.CAPITAL_TYPE2, actor.guanYin).build());
                return null;
            }
            case "set_vitals" -> {  // 体力/饱食/杀戮/酒量（maxEneAdd/maxSatAdd 本服表无同名属性，忽略）
                S2CPackets.Batch b = S2CPackets.playerBatch();
                int n = 0;
                int v;
                if ((v = cmd.intParam("ene", -1)) >= 0) { b.i32(PropertyId.ENE, v); n++; }
                if ((v = cmd.intParam("maxEne", -1)) >= 0) { b.i32(PropertyId.MAX_ENE, v); n++; }
                if ((v = cmd.intParam("resumeEne", -1)) >= 0) { b.i32(PropertyId.RESUME_ENE, v); n++; }
                if ((v = cmd.intParam("sat", -1)) >= 0) { b.i32(PropertyId.SAT, v); n++; }
                if ((v = cmd.intParam("maxSat", -1)) >= 0) { b.i32(PropertyId.MAX_SAT, v); n++; }
                if ((v = cmd.intParam("drunkLevel", -1)) >= 0) { b.i32(PropertyId.DRUNK_LEVEL, v); n++; }
                if ((v = cmd.intParam("pkValue", -1)) >= 0) { b.i32(PropertyId.PK_VALUE, v); n++; }
                if (n == 0) {
                    return "未提供任何数值（ene/maxEne/resumeEne/sat/maxSat/drunkLevel/pkValue）";
                }
                fx.send(b.build());
                return null;
            }
            case "set_alignment" -> {  // 善/恶/基础狂邪
                S2CPackets.Batch b = S2CPackets.playerBatch();
                int n = 0;
                int v;
                if ((v = cmd.intParam("justice", -1)) >= 0) { b.i32(PropertyId.JUSTICE, v); n++; }
                if ((v = cmd.intParam("evil", -1)) >= 0) { b.i32(PropertyId.EVIL, v); n++; }
                if ((v = cmd.intParam("freeBase", -1)) >= 0) { b.i32(PropertyId.FREE_BASE, v); n++; }
                if (n == 0) {
                    return "未提供 justice/evil/freeBase";
                }
                fx.send(b.build());
                return null;
            }
            case "set_five_attributes" -> {  // 五维（臂力/体魄/身法/内息/罡气；-1 不改）
                S2CPackets.Batch b = S2CPackets.playerBatch();
                int n = 0;
                int v;
                if ((v = cmd.intParam("attrStr", -1)) >= 0) { b.i32(PropertyId.STR, v); n++; }
                if ((v = cmd.intParam("attrSta", -1)) >= 0) { b.i32(PropertyId.STA, v); n++; }
                if ((v = cmd.intParam("attrDex", -1)) >= 0) { b.i32(PropertyId.DEX, v); n++; }
                if ((v = cmd.intParam("attrIng", -1)) >= 0) { b.i32(PropertyId.ING, v); n++; }
                if ((v = cmd.intParam("attrSpi", -1)) >= 0) { b.i32(PropertyId.SPI, v); n++; }
                if (n == 0) {
                    return "修改五维失败：未提供五维数值";
                }
                fx.send(b.build());
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "set_power_level" -> {  // 实力等级 PowerLevel=210 + 称号名（titleByPowerLevel 映射）
                int stage = cmd.intParam("stage", -1);
                if (stage < 0) {
                    return "设置实力等级失败：未提供 stage";
                }
                fx.send(S2CPackets.playerBatch()
                        .i32(PropertyId.POWER_LEVEL, stage).build());
                return "实力等级设为 " + stage + "，称号 " + powerTitle(stage);
            }
            case "set_pk_mode" -> {  // PKMode=436(u8) + HasLearnExtSkill=434(u8)，0~3
                int mode = cmd.intParam("pkMode", -1);
                if (mode < 0) {
                    mode = cmd.intParam("value", -1);
                }
                if (mode < 0 || mode > 3) {
                    return "pkMode 应为 0~3（和平/江湖/阵营/戕屠）";
                }
                session.pkMode = mode;
                fx.send(S2CPackets.playerBatch()
                        .u8(PropertyId.PK_MODE, mode)
                        .u8(PropertyId.HAS_LEARN_EXT_SKILL, 0).build());
                return "攻击模式已切换为 " + PK_MODE_NAMES[mode];
            }
            case "grant_title" -> {  // 发放称号（role_titles 落库；无库回退会话内存）
                int titleId = cmd.intParam("titleId", 0);
                if (titleId < 1) {
                    return "发放称号失败：titleId must be >= 1";
                }
                if (titleStore != null && session.roleId > 0) {
                    if (!titleStore.grant(session.roleId, titleId)) {
                        return "未发放：称号 " + titleId + " 不存在或已拥有";
                    }
                    return "已发放称号 " + titleId;
                }
                if (!session.grantedTitles.contains(titleId)) {
                    session.grantedTitles.add(titleId);
                }
                return "已发放称号 " + titleId + "（共 " + session.grantedTitles.size() + " 个）";
            }
            case "wear_title" -> {  // 佩戴称号 TitleID=359（校验已拥有，is_worn 落库）
                int titleId = cmd.intParam("titleId", 0);
                if (titleId < 1) {
                    return "佩戴称号失败：titleId must be >= 1";
                }
                boolean owns = titleStore != null && session.roleId > 0
                        ? titleStore.owns(session.roleId, titleId)
                        : session.grantedTitles.contains(titleId);
                if (!owns) {
                    return "未拥有称号 " + titleId + "，无法佩戴";
                }
                if (titleStore != null && session.roleId > 0) {
                    titleStore.wear(session.roleId, titleId);
                }
                session.wornTitleId = titleId;
                fx.send(S2CPackets.playerBatch()
                        .i32(PropertyId.TITLE_ID, titleId).build());
                return null;
            }
            case "cancel_title" -> {  // 取消佩戴（is_worn 清零 + TitleID=0）
                if (titleStore != null && session.roleId > 0) {
                    titleStore.cancelWorn(session.roleId);
                }
                session.wornTitleId = 0;
                fx.send(S2CPackets.playerBatch()
                        .i32(PropertyId.TITLE_ID, 0).build());
                return null;
            }
            case "clear_titles" -> {  // 清空称号（role_titles 删行 + TitleID=0）
                if (titleStore != null && session.roleId > 0) {
                    titleStore.clear(session.roleId);
                }
                session.grantedTitles.clear();
                session.wornTitleId = 0;
                fx.send(S2CPackets.playerBatch()
                        .i32(PropertyId.TITLE_ID, 0).build());
                return null;
            }
            case "speech_nul" -> {  // 气泡（空终止+频道，0x1A）
                String text = cmd.strParam("speechText");
                if (text.isBlank()) {
                    return "气泡发送失败：聊天内容为空";
                }
                fx.send(CustomPackets.speechNullTerm(S2CPackets.PLAYER_OBJECT_ID,
                        cmd.intParam("speechMode", 1), text));
                return null;
            }
            case "speech_len" -> {  // 气泡（旧：长度前缀，0x1A）
                String text = cmd.strParam("speechText");
                if (text.isBlank()) {
                    return "气泡发送失败：聊天内容为空";
                }
                fx.send(CustomPackets.speechLenPrefix(S2CPackets.PLAYER_OBJECT_ID, text));
                return null;
            }
            case "chat_broadcast" -> {  // 聊天广播（空终止气泡帧，频道 speechMode）
                String text = cmd.strParam("speechText");
                if (text.isBlank()) {
                    return "聊天广播失败：聊天内容为空";
                }
                fx.send(CustomPackets.speechNullTerm(S2CPackets.PLAYER_OBJECT_ID,
                        cmd.intParam("speechMode", 1), text));
                return null;
            }
            case "add_item", "grant_item" -> {  // 面板发物（grant_item 的 wearNow 暂不入包即穿，走背包）
                String config = cmd.params().getString("itemConfig");
                int amount = cmd.intParam("amount", 1);
                if (config == null || config.isBlank()) {
                    return "添加物品失败：itemConfig 不能为空";
                }
                if (sceneFlow == null) {
                    return "添加物品失败：库存系统未接线";
                }
                return sceneFlow.giveItem(fx, session, config, amount <= 0 ? 1 : amount);
            }
            case "spawn_chest" -> {  // 镀银宝箱（menu2 config=BoxNpc10006，大小写敏感，玩家身旁）
                if (st == null || st.world == null) {
                    return "生成宝箱失败：角色尚未进入场景";
                }
                float dx = session.x + 2f;
                float dz = session.z + 2f;
                NpcInstance chest = TwilightVerify.spawn(sceneManager, st.world,
                        "BoxNpc10006", new float[]{dx, session.y, dz, session.orient}, "gm_chest");
                TwilightVerify.sendNpc(fx, st, sceneManager, chest);
                return "已生成镀银宝箱（对象=" + chest.objectId + "），点击开箱。";
            }
            case "idle_stance_set" -> {  // 待机站姿（idle_stance_ 前缀 key 或动作名直传）
                String stance = cmd.strParam("stanceAction");
                if (stance.isBlank()) {
                    stance = cmd.strParam("key");
                }
                if (stance.isBlank()) {
                    return "未知待机站姿：未提供 stanceAction";
                }
                if (stance.startsWith("idle_stance_")) {
                    stance = stance.substring("idle_stance_".length());
                }
                if (stance.isBlank()) {
                    return "未知待机站姿";
                }
                sendState(fx, stance);
                return "已通过 State 发送待机站姿 " + stance;
            }
            case "custom_probe" -> {  // 自定义消息探针（0x1E：msgId + subType）
                int msgId = cmd.intParam("msgId", 0);
                int subType = cmd.intParam("subType", 0);
                if (msgId == 0) {
                    return "自定义消息发送失败：未提供 msgId";
                }
                fx.send(CustomPackets.intMessage(msgId, subType));
                return "已发送 S2C 自定义消息 ID=" + msgId + " sub=" + subType;
            }
            default -> {
                return "动作未实现: " + cmd.action();
            }
        }
    }

    /**
     * 暮色剧情归位验证：不在村则从村口大门进村（重进链完成后全量下发）；
     * 已在村则把波次进度推到最大并全量重发摆位（creator 固定坐标，不跟随玩家）。
     */
    private String twilightStagesStart(FxSession fx, LoginSession session, SceneFlow.SceneState st) {
        SceneWorld world = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
        world.dungeonProgress = TwilightVerify.MAX_PROGRESS;
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
        if (!TwilightVerify.inScene(session)) {
            switcher.switchScene(fx, session, st, TwilightVerify.SCENE_CONFIG,
                    TwilightVerify.SCENE_RESOURCE, TwilightVerify.ENTRANCE[0],
                    TwilightVerify.ENTRANCE[1], TwilightVerify.ENTRANCE[2],
                    TwilightVerify.ENTRANCE[3]);
            return null;
        }
        st.world = world;
        resendAllNpcs(fx, st, session);
        return null;
    }

    /** 重置/清理：丢弃 clone003 世界缓存（进度回到 1），在村则重发初始摆位。 */
    private String twilightStagesRemove(FxSession fx, LoginSession session, SceneFlow.SceneState st) {
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
        sceneManager.resetWorld(TwilightVerify.SCENE_RESOURCE);
        SceneWorld fresh = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
        if (TwilightVerify.inScene(session)) {
            st.world = fresh;
            resendAllNpcs(fx, st, session);
        }
        return null;
    }

    /** 全量重发：先移除客户端已见对象（含进度回退后应消失的波次），再走标准下发。 */
    private void resendAllNpcs(FxSession fx, SceneFlow.SceneState st, LoginSession session) {
        if (sceneFlow == null) {
            log.warn("SceneFlow 未接入，无法重发摆位");
            return;
        }
        if (st.world != null) {
            synchronized (st.world) {
                for (Integer oid : st.sentIds) {
                    var n = st.world.npcById(oid);
                    fx.send(S2CPackets.removeObject(oid, n != null ? n.ownerId : 1));
                }
            }
        }
        st.sentIds.clear();
        sceneFlow.sendSceneNpcs(session);
    }

    private static String difficultyName(int level) {
        return switch (level) {
            case 2 -> "困难";
            case 3 -> "地狱";
            case 4 -> "修罗";
            default -> "普通";
        };
    }

    /** PK 模式名（对齐 menu2：和平/江湖/阵营/戕屠）。 */
    private static final String[] PK_MODE_NAMES = {"和平", "江湖", "阵营", "戕屠"};

    /** 实力等级→称号 config（对齐 menu2 titleByPowerLevel：≤5→title001 … >150→title027）。 */
    private static String powerTitle(int stage) {
        if (stage <= 5) {
            return "title001";
        }
        if (stage <= 120) {
            return String.format("title%03d", Math.min(24, (stage - 1) / 5 + 1));
        }
        if (stage <= 130) {
            return "title025";
        }
        if (stage <= 150) {
            return "title026";
        }
        return "title027";
    }

    private String sendState(FxSession fx, String action) {
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, action));
        return null;
    }

    private static byte[] silverUpdate(PlayerActor actor) {
        return S2CPackets.playerBatch()
                .i64(PropertyId.CAPITAL_TYPE1, actor.silver)
                .build();
    }

    private static byte[] goldUpdate(PlayerActor actor) {
        return S2CPackets.playerBatch()
                .i64(PropertyId.CAPITAL_TYPE0, actor.gold)
                .build();
    }

    private void persistFaction(LoginSession session, String faction) {
        if (session.accountKey.isEmpty()) {
            return;
        }
        java.util.List<String> appearance = new java.util.ArrayList<>(session.appearance);
        while (appearance.size() < 9) {
            appearance.add("");
        }
        appearance.set(3, faction);
        session.appearance = appearance;
        StoredRole role = new StoredRole();
        role.setName(session.roleName);
        role.setAppearance(appearance);
        role.setSceneConfig(session.spawnConfig);
        role.setSceneResource(session.spawnResource);
        role.setX(session.x);
        role.setY(session.y);
        role.setZ(session.z);
        role.setOrient(session.orient);
        roleStore.put(session.accountKey, role);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static float[] parsePosition(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new float[]{Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()), Float.parseFloat(parts[3].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String[] parseDestination(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 6 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return null;
        }
        try {
            for (int i = 2; i < 6; i++) {
                Float.parseFloat(parts[i].trim());
            }
            return parts;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
